package app.organicmaps.transit;

import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.log.Logger;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * The operator's departures at a stop (saraksti.lv gpsdata.ashx?stopid=...), as shown by Mobilly and the stop
 * boards: each upcoming departure with its expected time and the vehicle that will run it. Unlike the vehicle feed,
 * it covers trips that have not left their first stop yet, so a vehicle still coming in late shows on the departure
 * it will run, e.g. trolleybus 15 due 21:52 at 21:59 with 17671. Turned into {@link RigaJourneyPlanner.Prediction}s
 * for the trips the vehicle feed did not match.
 */
final class RigaStopDepartures
{
  interface Fetcher { String fetch(String stopId) throws Exception; }

  static final String URL = "https://saraksti.lv/gpsdata.ashx?stopid=";
  /** A departure this much earlier or later than the timetable is taken as another trip. */
  static final long EARLY = 3 * 60000, LATE = 40 * 60000;
  /** What leaving a listed departure unmatched costs, against a delay, when lining them up. */
  static final long UNMATCHED = 15 * 60000;
  /** What skipping a timetable departure costs when it should still be listed (later than now). */
  static final long SKIPPED = 5 * 60000;
  /** A stop's departures are reused this long (alternatives and refreshes ask again within seconds). */
  static final long REUSE = 20000;
  /** A fleet number; anything else in that column is no vehicle. */
  private static final Pattern VEHICLE = Pattern.compile("\\d{3,6}");
  /** Stops are asked a few at a time, shared by every source. */
  private static final ExecutorService POOL = Executors.newFixedThreadPool(4, task -> {
    Thread thread = new Thread(task, "RigaStopDepartures");
    thread.setDaemon(true);
    return thread;
  });

  static final class Row
  {
    final String stop, shape, vehicle;
    final long time;
    Row(String stop, String shape, long time, String vehicle)
    {
      this.stop = stop;
      this.shape = shape;
      this.time = time;
      this.vehicle = vehicle;
    }
  }

  private RigaStopDepartures() {}

  /**
   * "stop,0075" then "trol,15,a-b,82920,17671,Ķengarags" lines: mode, line, direction, seconds after a Riga
   * midnight (more than a day for night trips; around midnight the list can count from either), fleet number,
   * headsign. Each time is taken on the day that puts it within 12 hours of {@code now}.
   */
  static List<Row> parse(String text, long now)
  {
    List<Row> rows = new ArrayList<>();
    String stop = null;
    long midnight = RigaTimeChoice.startOfDay(now);
    for (String line : text.split("\n"))
    {
      String[] f = line.trim().split(",", -1);
      if (f.length >= 2 && f[0].equals("stop")) { stop = f[1]; continue; }
      if (stop == null || f.length < 5 || f[0].isEmpty() || f[1].isEmpty() || f[2].isEmpty()) continue;
      long seconds;
      try { seconds = Long.parseLong(f[3]); }
      catch (NumberFormatException e) { continue; }
      if (seconds < 0 || seconds > 48 * 3600) continue;
      long time = midnight + seconds * 1000;
      // Just after midnight the list can be on the next day's clock while "now" is still on the previous one.
      if (time < now - 12 * 3600000L) time += 24 * 3600000L;
      else if (time > now + 12 * 3600000L) time -= 24 * 3600000L;
      rows.add(new Row(stop, "riga_" + f[0] + "_" + f[1] + "_" + f[2], time,
                       VEHICLE.matcher(f[4]).matches() ? f[4] : ""));
    }
    return rows;
  }

  /**
   * Estimates for the trips the vehicle feed did not match ({@code known}), from the departures listed at stops.
   * At each stop, a line's listed departures and its timetable departures are lined up in order, so a vehicle coming
   * in late keeps its own departure instead of taking the next one.
   */
  static Map<Integer, RigaJourneyPlanner.Prediction> predict(RigaJourneyPlanner.Timetable data, List<Row> rows,
      Map<Integer, RigaJourneyPlanner.Prediction> known, long now)
  {
    Set<String> stopIds = new HashSet<>();
    for (RigaJourneyPlanner.Stop stop : data.stops)
      if (stop.id != null) stopIds.add(stop.id);
    Map<String, List<Row>> groups = new HashMap<>();
    for (Row row : rows)
      if (stopIds.contains(row.stop))
        groups.computeIfAbsent(row.stop + "|" + row.shape, k -> new ArrayList<>()).add(row);
    if (groups.isEmpty()) return Collections.emptyMap();
    Set<String> shapes = new HashSet<>(), ids = new HashSet<>();
    for (Row row : rows) { shapes.add(row.shape); ids.add(row.stop); }
    boolean[] asked = new boolean[data.stops.size()];
    for (int i = 0; i < asked.length; ++i) asked[i] = ids.contains(data.stops.get(i).id);
    // Trip departures at each stop, by stop and shape.
    Map<String, List<long[]>> departures = new HashMap<>();
    for (int t = 0; t < data.trips.size(); ++t)
    {
      RigaJourneyPlanner.Trip trip = data.trips.get(t);
      if (trip.shapeId == null || !shapes.contains(trip.shapeId)) continue;
      for (int e = 0; e + 1 < trip.events.size(); ++e)
      {
        RigaJourneyPlanner.Event event = trip.events.get(e);
        if (!asked[event.stop]) continue;
        String key = data.stops.get(event.stop).id + "|" + trip.shapeId;
        if (!groups.containsKey(key)) continue;
        if (event.departure < now - LATE || event.departure > now + 4 * 3600000L) continue;
        departures.computeIfAbsent(key, k -> new ArrayList<>()).add(new long[] {event.departure, t, e});
      }
    }
    Map<Integer, RigaJourneyPlanner.Prediction> result = new HashMap<>();
    for (Map.Entry<String, List<Row>> group : groups.entrySet())
    {
      List<long[]> trips = departures.get(group.getKey());
      if (trips == null) continue;
      List<Row> listed = new ArrayList<>(group.getValue());
      Collections.sort(listed, (a, b) -> Long.compare(a.time, b.time));
      Collections.sort(trips, (a, b) -> Long.compare(a[0], b[0]));
      // A vehicle the feed already places on a trip: that row is that trip.
      List<long[]> open = new ArrayList<>();
      for (long[] trip : trips)
      {
        RigaJourneyPlanner.Prediction prediction = known.get((int) trip[1]);
        if (prediction == null) { open.add(trip); continue; }
        for (int r = 0; r < listed.size(); ++r)
          if (!listed.get(r).vehicle.isEmpty() && listed.get(r).vehicle.equals(prediction.vehicleId))
          {
            listed.remove(r);
            break;
          }
      }
      int[] pairs = align(listed, open, now);
      for (int r = 0; r < listed.size(); ++r)
      {
        if (pairs[r] < 0) continue;
        long[] trip = open.get(pairs[r]);
        int index = (int) trip[1];
        if (result.containsKey(index)) continue;
        Row row = listed.get(r);
        long delay = row.time - trip[0];
        RigaJourneyPlanner.Trip timetable = data.trips.get(index);
        // Boardable from the first stop it has not passed yet, on the estimate.
        int first = 0;
        while (first < (int) trip[2] && timetable.events.get(first).departure + delay < now - 60000) ++first;
        result.put(index, new RigaJourneyPlanner.Prediction((int) (delay / 1000), first, row.vehicle,
            row.vehicle.isEmpty() ? "stop:" + row.stop : row.vehicle, now));
      }
    }
    return result;
  }

  /**
   * Lines up listed departures with timetable departures in order (both sorted by time): the pairing with the least
   * total delay, where a listed departure left out costs {@link #UNMATCHED} and a timetable one still due costs
   * {@link #SKIPPED}. @return for each listed departure the index of its timetable departure, or -1.
   */
  static int[] align(List<Row> listed, List<long[]> trips, long now)
  {
    int n = listed.size(), m = trips.size();
    long[][] cost = new long[n + 1][m + 1];
    for (int i = 0; i <= n; ++i)
      for (int j = 0; j <= m; ++j)
      {
        if (i == 0 && j == 0) continue;
        long best = Long.MAX_VALUE;
        if (i > 0) best = Math.min(best, cost[i - 1][j] + UNMATCHED);
        if (j > 0) best = Math.min(best, cost[i][j - 1] + skip(trips.get(j - 1), listed, now));
        if (i > 0 && j > 0)
        {
          long delay = listed.get(i - 1).time - trips.get(j - 1)[0];
          if (delay >= -EARLY && delay <= LATE)
            best = Math.min(best, cost[i - 1][j - 1] + Math.abs(delay));
        }
        cost[i][j] = best;
      }
    int[] pairs = new int[n];
    Arrays.fill(pairs, -1);
    for (int i = n, j = m; i > 0 || j > 0;)
    {
      if (i > 0 && j > 0)
      {
        long delay = listed.get(i - 1).time - trips.get(j - 1)[0];
        if (delay >= -EARLY && delay <= LATE && cost[i][j] == cost[i - 1][j - 1] + Math.abs(delay))
        {
          pairs[--i] = --j;
          continue;
        }
      }
      if (i > 0 && cost[i][j] == cost[i - 1][j] + UNMATCHED) --i;
      else --j;
    }
    return pairs;
  }

  /** A timetable departure not listed: free once it is past or beyond the listed ones, else it should be there. */
  private static long skip(long[] trip, List<Row> listed, long now)
  {
    long last = listed.isEmpty() ? 0 : listed.get(listed.size() - 1).time;
    return trip[0] > now + 60000 && trip[0] < last ? SKIPPED : 0;
  }

  /** Each stop's departures, cached for {@link #REUSE}; a stop that fails is left out. */
  static final class Source
  {
    private static final class Entry
    {
      final long at;
      final List<Row> rows;
      Entry(long at, List<Row> rows) { this.at = at; this.rows = rows; }
    }

    private final Fetcher mFetcher;
    private final Map<String, Entry> mCache = new HashMap<>();
    Source(Fetcher fetcher) { mFetcher = fetcher; }

    List<Row> rows(List<String> stops, long now, BooleanSupplier cancelled)
    {
      List<Row> rows = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      synchronized (mCache)
      {
        for (String stop : stops)
        {
          Entry cached = mCache.get(stop);
          if (cached != null && now >= cached.at && now - cached.at < REUSE) rows.addAll(cached.rows);
          else missing.add(stop);
        }
      }
      if (missing.isEmpty()) return rows;
      List<Future<List<Row>>> futures = new ArrayList<>();
      for (String stop : missing)
        futures.add(POOL.submit(() -> parse(mFetcher.fetch(stop), System.currentTimeMillis())));
      try
      {
        for (int i = 0; i < missing.size(); ++i)
        {
          if (cancelled.getAsBoolean()) break;
          try
          {
            List<Row> list = futures.get(i).get(20, TimeUnit.SECONDS);
            rows.addAll(list);
            synchronized (mCache) { mCache.put(missing.get(i), new Entry(now, list)); }
          }
          catch (Exception e)
          {
            Logger.w("RigaMatch", "Departures at stop " + missing.get(i) + " failed: " + e);
          }
        }
      }
      finally
      {
        for (Future<List<Row>> future : futures) future.cancel(true);
      }
      synchronized (mCache)
      {
        if (mCache.size() > 200) mCache.clear();
      }
      return rows;
    }
  }

  /** The departures listed at a stop (the request needs saraksti.lv's own header). */
  static String download(String stopId) throws IOException
  {
    return RigaTransitNetwork.text(URL + URLEncoder.encode(stopId, "UTF-8"), RigaTransitNetwork::download, 256 * 1024);
  }

  /** {@code known} with the stop estimates added for the trips it lacks. */
  static Map<Integer, RigaJourneyPlanner.Prediction> merge(Map<Integer, RigaJourneyPlanner.Prediction> known,
                                                          @Nullable Map<Integer, RigaJourneyPlanner.Prediction> stops)
  {
    if (stops == null || stops.isEmpty()) return known;
    Map<Integer, RigaJourneyPlanner.Prediction> merged = new HashMap<>(stops);
    merged.putAll(known);
    return merged;
  }
}
