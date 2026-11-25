package app.organicmaps.transit;

import android.content.Context;
import androidx.annotation.NonNull;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Next departures for a stop picked on the map. Map stops and timetable stops are separate data: they
 * are matched by name nearby (both directions of a street usually share it), else by being very close.
 */
public final class RigaStopBoard
{
  /** One line in one direction with its next departures. */
  public static final class Row
  {
    public final String number;
    public final String headsign;
    public final int type;
    public final int color;
    public final List<Long> times = new ArrayList<>();
    Row(String number, String headsign, int type)
    {
      this.number = number;
      this.headsign = headsign;
      this.type = type;
      this.color = RigaModeStyle.color(type);
    }
  }

  static final double NAMED_METRES = 200;
  static final double CLOSE_METRES = 35;
  static final long HORIZON = 2 * 3600000L;
  static final int ROWS = 8;
  static final int TIMES = 3;

  private RigaStopBoard() {}

  /** Whether {@code lat, lon} is in the area the Riga timetable serves. */
  public static boolean covers(double lat, double lon)
  {
    return TransitArea.covers(lat, lon);
  }

  /** Whether the timetable is installed; without it there is nothing to show. */
  public static boolean installed(@NonNull Context context) { return new RigaTransitStore(context).exists(); }

  /** Rows for the stop called {@code title} at {@code lat, lon}; empty when no timetable stop matches. Worker thread. */
  @NonNull
  public static List<Row> board(@NonNull Context context, @NonNull String title, double lat, double lon, long now)
  {
    RigaTransitStore store = new RigaTransitStore(context);
    if (!store.exists()) return new ArrayList<>();
    List<RigaTransitStore.Stop> stops = match(store.near(lat, lon, NAMED_METRES), title, lat, lon);
    List<RigaTransitStore.Departure> departures = new ArrayList<>();
    for (RigaTransitStore.Stop stop : stops) departures.addAll(store.departures(stop.id, now));
    return rows(boarding(departures, stops), now);
  }

  static List<RigaTransitStore.Stop> match(List<RigaTransitStore.Stop> nearby, String title, double lat, double lon)
  {
    String wanted = normal(title);
    List<RigaTransitStore.Stop> named = new ArrayList<>();
    List<RigaTransitStore.Stop> close = new ArrayList<>();
    for (RigaTransitStore.Stop stop : nearby)
    {
      String name = normal(stop.name);
      if (!wanted.isEmpty() && !name.isEmpty() && (name.equals(wanted) || abbreviated(name, wanted)))
        named.add(stop);
      else if (metres(lat, lon, stop.lat, stop.lon) <= CLOSE_METRES)
        close.add(stop);
    }
    return named.isEmpty() ? close : named;
  }

  /** "adeglavaiela" for "A.Deglava iela" matches "augustadeglavaiela": same initial and same rest. */
  private static boolean abbreviated(String a, String b)
  {
    String shorter = a.length() <= b.length() ? a : b;
    String longer = a.length() <= b.length() ? b : a;
    if (shorter.length() < 4 || shorter.charAt(0) != longer.charAt(0)) return false;
    return longer.endsWith(shorter.substring(1)) && longer.length() - shorter.length() <= 12;
  }

  /** Drops trips that end here: a vehicle "to Stacijas laukums" at Stacijas laukums takes no one anywhere. */
  static List<RigaTransitStore.Departure> boarding(List<RigaTransitStore.Departure> departures,
                                                   List<RigaTransitStore.Stop> stops)
  {
    java.util.Set<String> here = new java.util.HashSet<>();
    for (RigaTransitStore.Stop stop : stops) here.add(normal(stop.name));
    List<RigaTransitStore.Departure> result = new ArrayList<>();
    for (RigaTransitStore.Departure departure : departures)
      if (!here.contains(normal(departure.destination))) result.add(departure);
    return result;
  }

  static List<Row> rows(List<RigaTransitStore.Departure> departures, long now)
  {
    departures.sort(java.util.Comparator.comparingLong(d -> d.time));
    Map<String, Row> rows = new LinkedHashMap<>();
    for (RigaTransitStore.Departure departure : departures)
    {
      if (departure.time < now - 60000 || departure.time > now + HORIZON) continue;
      String key = departure.type + "|" + departure.route + "|" + departure.destination;
      Row row = rows.get(key);
      if (row == null)
      {
        if (rows.size() >= ROWS) continue;
        row = new Row(departure.route, departure.destination, departure.type);
        rows.put(key, row);
      }
      if (row.times.size() < TIMES && !row.times.contains(departure.time)) row.times.add(departure.time);
    }
    return new ArrayList<>(rows.values());
  }

  static String normal(String name)
  {
    String plain = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  static double metres(double lat1, double lon1, double lat2, double lon2)
  {
    double y = Math.toRadians(lat2 - lat1);
    double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
    return Math.hypot(x, y) * 6371000;
  }
}
