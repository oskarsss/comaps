package app.organicmaps.transit;

import static org.junit.Assume.assumeTrue;
import android.app.Application;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Replays a recorded live feed through the app's own matching and fleet-number code and counts how often the
 * number shown is the vehicle that then ran the trip. Opt-in, for tuning {@link RigaNextVehicle}:
 * RIGA_REPLAY_DB = the app's riga-timetable.sqlite, RIGA_REPLAY_FEED = lines of "unix seconds,feed line" polled
 * every 30 s from saraksti.lv/gpsdata.ashx?gps.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class RigaNextVehicleReplayTest
{
  private static final int[] MINUTES = {15, 10, 5, 2, 0};

  @Test public void replay() throws Exception
  {
    String db = System.getenv("RIGA_REPLAY_DB"), feed = System.getenv("RIGA_REPLAY_FEED");
    assumeTrue(db != null && feed != null);
    Application app = RuntimeEnvironment.getApplication();
    Files.copy(new File(db).toPath(), new File(app.getFilesDir(), "riga-timetable.sqlite").toPath(),
               StandardCopyOption.REPLACE_EXISTING);
    RigaJourneyDatabase database = new RigaJourneyDatabase(new RigaTransitStore(app));

    TreeMap<Long, List<RigaVehicle>> snapshots = new TreeMap<>();
    Map<Long, StringBuilder> text = new TreeMap<>();
    try (BufferedReader in = new BufferedReader(new FileReader(feed)))
    {
      for (String line; (line = in.readLine()) != null;)
      {
        int comma = line.indexOf(',');
        if (comma < 0) continue;
        long t = Long.parseLong(line.substring(0, comma)) * 1000;
        text.computeIfAbsent(t, k -> new StringBuilder()).append(line.substring(comma + 1)).append('\n');
      }
    }
    for (Map.Entry<Long, StringBuilder> e : text.entrySet())
      snapshots.put(e.getKey(), RigaVehicle.parse(e.getValue().toString(), e.getKey()));
    long first = snapshots.firstKey(), last = snapshots.lastKey();

    // Who ran each trip: the first vehicle the feed lists on its direction and start minute.
    Map<String, String> runner = new HashMap<>();
    for (List<RigaVehicle> snapshot : snapshots.values())
      for (RigaVehicle v : snapshot)
        if (v.id.matches("\\d{3,6}"))
          runner.putIfAbsent(RigaNextVehicle.line(v) + "_" + v.direction + "@" + v.startMinutes, v.id);

    // What the app's matcher says at each poll, and what it would remember (it keeps a leg's number).
    Map<Long, Map<String, String>> matched = new HashMap<>();
    for (Map.Entry<Long, List<RigaVehicle>> e : snapshots.entrySet())
    {
      RigaJourneyPlanner.Timetable data = database.load(e.getKey(), () -> false);
      Map<String, String> byTrip = new HashMap<>();
      for (Map.Entry<Integer, RigaJourneyPlanner.Prediction> p :
           RigaJourneyMatcher.match(data, e.getValue(), e.getKey(), database::shape, () -> false).entrySet())
        byTrip.put(data.trips.get(p.getKey()).id, p.getValue().vehicleId);
      matched.put(e.getKey(), byTrip);
    }

    RigaJourneyPlanner.Timetable all = database.load(first + 15 * 60000, () -> false);
    int[][][] counts = new int[2][MINUTES.length][3];
    java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("HH:mm");
    clock.setTimeZone(RigaTransitStore.ZONE);
    List<String> misses = new ArrayList<>();
    for (RigaJourneyPlanner.Trip trip : all.trips)
    {
      if (!trip.shapeId.startsWith("riga_") || trip.events.size() < 2) continue;
      long start = (trip.events.get(0).departure - trip.serviceOrigin) / 60000;
      String ran = null;
      for (long s : new long[] {start, start - 1, start + 1})
        if (ran == null) ran = runner.get(trip.shapeId + "@" + s);
      if (ran == null) continue;
      for (int kind = 0; kind < 2; ++kind)
      {
        int board = kind == 0 ? 0 : Math.min(6, trip.events.size() - 2);
        long departure = trip.events.get(board).departure;
        if (departure - 15 * 60000 < first || departure > last - 60000) continue;
        for (int m = 0; m < MINUTES.length; ++m)
        {
          Long now = snapshots.floorKey(departure - MINUTES[m] * 60000L);
          if (now == null) continue;
          String shown = matched.get(now).get(trip.id);
          for (Long t : snapshots.subMap(now - 3600000, true, now, true).keySet())
            if (shown == null) shown = matched.get(t).get(trip.id); // remembered from an earlier match
          if (shown == null && departure - now < 45 * 60000)
          {
            RigaJourneyPlanner.Timetable data = database.load(now, () -> false);
            RigaJourneyPlanner.Trip same = null;
            for (RigaJourneyPlanner.Trip t : data.trips)
              if (t.id.equals(trip.id)) same = t;
            Map<String, RigaVehicle> seen = new HashMap<>();
            for (List<RigaVehicle> snapshot : snapshots.subMap(now - RigaNextVehicle.GONE, true, now, true).values())
              for (RigaVehicle v : snapshot) seen.put(v.id, v);
            if (same != null)
              shown = RigaNextVehicle.forLeg(database, data, same, board, snapshots.get(now),
                                             new ArrayList<>(seen.values()), departure, now);
          }
          int outcome = shown == null ? 2 : shown.equals(ran) ? 0 : 1;
          counts[kind][m][outcome]++;
          if (outcome != 0 && MINUTES[m] == 2)
            misses.add((kind == 0 ? "first " : "later ") + trip.shapeId + " " + trip.id + " at "
                       + clock.format(new java.util.Date(departure))
                       + " shown " + shown + " ran " + ran);
        }
      }
    }
    StringBuilder out = new StringBuilder("right / wrong / none, minutes before departure:");
    for (int m : MINUTES) out.append(String.format("%14d", m));
    for (int kind = 0; kind < 2; ++kind)
    {
      out.append(kind == 0 ? "\nboarding at the first stop: " : "\nboarding 6 stops in:        ");
      out.append("                ");
      for (int[] c : counts[kind]) out.append(String.format("%5d/%3d/%3d ", c[0], c[1], c[2]));
    }
    for (String miss : misses) out.append("\n  ").append(miss);
    System.out.println(out);
    Files.write(new File(System.getenv().getOrDefault("RIGA_REPLAY_OUT", "/dev/null")).toPath(),
                out.toString().getBytes());
  }
}
