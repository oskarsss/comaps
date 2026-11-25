package app.organicmaps.transit;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A separately downloaded, immutable timetable. A complete import replaces the file atomically. */
final class RigaTransitStore
{
  static final TimeZone ZONE = TimeZone.getTimeZone("Europe/Riga");
  private final File mFile;
  private final long mRowLimit;

  RigaTransitStore(Context context)
  {
    this(context, 5000000);
  }

  // An internal ceiling can be reduced for bounded fault tests instead of creating huge feeds.
  RigaTransitStore(Context context, long rowLimit)
  {
    mFile = new File(context.getFilesDir(), "riga-timetable.sqlite");
    mRowLimit = rowLimit;
  }

  boolean exists()
  {
    return mFile.isFile();
  }

  String generation()
  {
    return mFile.lastModified() + ":" + mFile.length();
  }

  /** Removes the timetable and everything derived from it. */
  void delete()
  {
    synchronized (RigaTransitStore.class)
    {
      //noinspection ResultOfMethodCallIgnored
      mFile.delete();
      //noinspection ResultOfMethodCallIgnored
      snapshotFile().delete();
      //noinspection ResultOfMethodCallIgnored
      checkedFile().delete();
    }
  }

  int version()
  {
    if (!exists())
      return 0;
    try (SQLiteDatabase db = open())
    {
      return db.getVersion();
    }
    catch (RuntimeException e)
    {
      return 0;
    }
  }

  private File checkedFile()
  {
    return new File(mFile.getParentFile(), "riga-timetable.checked");
  }

  /** When the feeds were last fetched, even if nothing had changed. */
  long updated()
  {
    return Math.max(mFile.lastModified(), checkedFile().lastModified());
  }

  /** Records an update check that found every feed unchanged, without touching the database generation. */
  void touch() throws IOException
  {
    File marker = checkedFile();
    if (!marker.exists() && !marker.createNewFile())
      throw new IOException("Cannot record timetable check");
    //noinspection ResultOfMethodCallIgnored
    marker.setLastModified(System.currentTimeMillis());
  }

  /** Derived parse cache next to the timetable; safe to delete at any time. */
  File snapshotFile()
  {
    return new File(mFile.getParentFile(), "riga-timetable.snapshot");
  }

  SQLiteDatabase open()
  {
    return SQLiteDatabase.openDatabase(mFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
  }

  static final class Stop
  {
    final String id;
    final String name;
    final double lat;
    final double lon;
    Stop(String id, String name)
    {
      this(id, name, 0, 0);
    }
    Stop(String id, String name, double lat, double lon)
    {
      this.id = id;
      this.name = name;
      this.lat = lat;
      this.lon = lon;
    }
    @Override public String toString() { return name + " · " + id; }
  }

  List<Stop> stops(String query)
  {
    List<Stop> stops = new ArrayList<>();
    try (SQLiteDatabase db = open();
         Cursor c = db.rawQuery("SELECT stop_id,stop_name,stop_lat,stop_lon FROM stops WHERE stop_name LIKE ? ESCAPE '\\' "
                               + "ORDER BY stop_name LIMIT 100",
                               new String[]{"%" + query.replace("\\", "\\\\").replace("%", "\\%")
                                                          .replace("_", "\\_") + "%"}))
    {
      while (c.moveToNext())
        stops.add(new Stop(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3)));
    }
    return stops;
  }

  /** Stops within about {@code metres} of a point (a box, refined by the caller). */
  List<Stop> near(double lat, double lon, double metres)
  {
    double dLat = metres / 111320.0;
    double dLon = metres / (111320.0 * Math.cos(Math.toRadians(lat)));
    List<Stop> stops = new ArrayList<>();
    try (SQLiteDatabase db = open();
         Cursor c = db.rawQuery("SELECT stop_id,stop_name,stop_lat,stop_lon FROM stops WHERE "
                                + "CAST(stop_lat AS REAL) BETWEEN ? AND ? AND CAST(stop_lon AS REAL) BETWEEN ? AND ?",
                                new String[]{Double.toString(lat - dLat), Double.toString(lat + dLat),
                                             Double.toString(lon - dLon), Double.toString(lon + dLon)}))
    {
      while (c.moveToNext())
        stops.add(new Stop(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3)));
    }
    return stops;
  }

  static final class Departure
  {
    final String route;
    final String destination;
    final long time;
    final int type;
    Departure(String route, String destination, long time, int type)
    {
      this.route = route;
      this.destination = destination;
      this.time = time;
      this.type = type;
    }
  }

  // GTFS times are elapsed seconds since noon minus 12 hours, including on DST transition days.
  static long serviceOrigin(Calendar date)
  {
    Calendar noon = (Calendar) date.clone();
    noon.set(Calendar.HOUR_OF_DAY, 12);
    noon.set(Calendar.MINUTE, 0);
    noon.set(Calendar.SECOND, 0);
    noon.set(Calendar.MILLISECOND, 0);
    return noon.getTimeInMillis() - 12 * 3600000L;
  }

  List<Departure> departures(String stop, long now)
  {
    List<Departure> result = new ArrayList<>();
    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
    format.setTimeZone(ZONE);
    String[] weekdays = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};
    try (SQLiteDatabase db = open())
    {
      // Extended GTFS times can originate on earlier service days. A rolling 24 hours
      // can also reach the second following civil date when Riga skips an hour in spring.
      // The timestamp predicates below still restrict results to the exact requested window.
      for (int offset = -3; offset <= 2; ++offset)
      {
        Calendar date = Calendar.getInstance(ZONE);
        date.setTimeInMillis(now);
        date.add(Calendar.DAY_OF_MONTH, offset);
        String day = format.format(date.getTime());
        String weekday = weekdays[date.get(Calendar.DAY_OF_WEEK) - 1];
        long origin = serviceOrigin(date);
        String sql = "SELECT r.route_short_name,t.trip_headsign,s.departure_seconds,r.route_type FROM stop_times s "
                     + "JOIN trips t ON t.trip_id=s.trip_id JOIN routes r ON r.route_id=t.route_id "
                     + "WHERE s.stop_id=? AND s.pickup_type!='1' AND s.departure_seconds>=? "
                     + "AND s.departure_seconds<=? AND t.service_id IN ("
                     + "SELECT service_id FROM calendar WHERE start_date<=? AND end_date>=? AND " + weekday + "='1' "
                     + "UNION SELECT service_id FROM calendar_dates WHERE date=? AND exception_type='1') "
                     + "AND t.service_id NOT IN (SELECT service_id FROM calendar_dates WHERE date=? AND exception_type='2') "
                     + "ORDER BY s.departure_seconds LIMIT 50";
        try (Cursor c = db.rawQuery(sql, new String[]{stop, Long.toString((now - origin + 999) / 1000),
                            Long.toString((now + 24 * 3600000L - origin) / 1000), day, day, day, day}))
        {
          while (c.moveToNext())
            result.add(new Departure(c.getString(0), c.getString(1), origin + c.getLong(2) * 1000, c.getInt(3)));
        }
      }
    }
    Collections.sort(result, Comparator.comparingLong(d -> d.time));
    return result.subList(0, Math.min(50, result.size()));
  }

  boolean coversDate(long time)
  {
    SimpleDateFormat format=new SimpleDateFormat("yyyyMMdd",Locale.ROOT);format.setTimeZone(ZONE);
    String day=format.format(new java.util.Date(time));
    try (SQLiteDatabase db=open();Cursor cursor=db.rawQuery(
        "SELECT 1 FROM calendar WHERE start_date<=? AND end_date>=? UNION ALL "
        + "SELECT 1 FROM calendar_dates WHERE date=? AND exception_type='1' LIMIT 1",new String[]{day,day,day}))
    { return cursor.moveToFirst(); }
  }

  String validity()
  {
    try (SQLiteDatabase db = open(); Cursor c = db.rawQuery(
        "SELECT MIN(start_date),MAX(end_date) FROM (SELECT start_date,end_date FROM calendar "
        + "UNION ALL SELECT date,date FROM calendar_dates WHERE exception_type='1')", null))
    {
      c.moveToFirst();
      return c.getString(0) + " – " + c.getString(1);
    }
  }

  /** One GTFS archive in a merged install. Identifiers of prefixed feeds never collide with the city feed. */
  static final class Feed
  {
    final String prefix;
    final File archive;
    final boolean required;
    Feed(String prefix, File archive, boolean required)
    {
      this.prefix = prefix;
      this.archive = archive;
      this.required = required;
    }
  }

  static final int VERSION = 3;
  // Columns holding GTFS identifiers; they get the feed prefix so merged feeds stay distinct.
  private static final java.util.Set<String> IDENTIFIERS = new java.util.HashSet<>(java.util.Arrays.asList(
      "stop_id", "parent_station", "route_id", "trip_id", "service_id", "shape_id"));
  private static final String[][] TABLES = {
      {"stops", "stop_id,stop_name,stop_lat,stop_lon,parent_station,location_type", "required"},
      {"routes", "route_id,route_short_name,route_type,route_long_name", "required"},
      {"trips", "trip_id,route_id,service_id,trip_headsign,shape_id,direction_id", "required"},
      {"calendar", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date", "optional"},
      {"calendar_dates", "service_id,date,exception_type", "optional"},
      {"stop_times", "trip_id,stop_id,departure_time,pickup_type,arrival_time,stop_sequence,drop_off_type", "required"},
      {"shapes", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence", "optional"}};
  // The column each table's rows are attributed to a feed by, to drop a failed optional feed.
  private static final String[] KEYS = {"stop_id", "route_id", "trip_id", "service_id", "service_id", "trip_id", "shape_id"};

  void install(File archive) throws IOException
  {
    install(Collections.singletonList(new Feed("", archive, true)));
  }

  void install(List<Feed> feeds) throws IOException
  {
    synchronized (RigaTransitStore.class) { installLocked(feeds); }
  }

  private void installLocked(List<Feed> feeds) throws IOException
  {
    File temporary = new File(mFile.getPath() + ".new");
    temporary.delete();
    try
    {
      try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(temporary, null))
      {
        db.beginTransaction();
        try
        {
          for (String[] table : TABLES)
            create(db, table[0], table[1]);
          for (Feed feed : feeds)
          {
            try
            {
              loadFeed(db, feed);
            }
            catch (IOException | RuntimeException e)
            {
              if (feed.required || feed.prefix.isEmpty())
                throw e;
              // An optional feed is all or nothing: a broken national feed must not block city routes.
              android.util.Log.w("RigaTransitStore", "Skipping feed " + feed.prefix, e);
              for (int i = 0; i < TABLES.length; ++i)
                db.execSQL("DELETE FROM " + TABLES[i][0] + " WHERE substr(" + KEYS[i] + ",1,?)=?",
                           new Object[]{feed.prefix.length(), feed.prefix});
            }
          }
          db.execSQL("CREATE INDEX departures ON stop_times(stop_id,departure_seconds)");
          db.execSQL("CREATE INDEX trip_sequence ON stop_times(trip_id,stop_sequence)");
          db.execSQL("CREATE INDEX shape_sequence ON shapes(shape_id,shape_pt_sequence)");
          db.execSQL("CREATE UNIQUE INDEX trips_id ON trips(trip_id)");
          db.execSQL("CREATE UNIQUE INDEX routes_id ON routes(route_id)");
          db.execSQL("CREATE INDEX exception_dates ON calendar_dates(date,exception_type,service_id)");
          // Feeds without headsigns, or with the line's "A - B" name as one, show where the trip ends.
          db.execSQL("UPDATE trips SET trip_headsign=coalesce((SELECT st.stop_name FROM stop_times s "
                     + "JOIN stops st ON st.stop_id=s.stop_id WHERE s.trip_id=trips.trip_id "
                     + "ORDER BY s.stop_sequence DESC LIMIT 1),trip_headsign) WHERE trip_headsign='' "
                     + "OR trip_headsign=(SELECT r.route_long_name FROM routes r WHERE r.route_id=trips.route_id)");
          try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM stop_times s JOIN trips t ON t.trip_id=s.trip_id "
                                     + "JOIN routes r ON r.route_id=t.route_id WHERE t.service_id IN "
                                     + "(SELECT service_id FROM calendar UNION SELECT service_id FROM calendar_dates "
                                     + "WHERE exception_type='1')", null))
          {
            if (!c.moveToFirst() || c.getLong(0) == 0)
              throw new IOException("Timetable has no departures");
          }
          db.setVersion(VERSION);
          db.setTransactionSuccessful();
        }
        finally
        {
          db.endTransaction();
        }
      }
      // POSIX rename replaces the previous complete database atomically; failed imports keep it intact.
      if (!temporary.renameTo(mFile))
        throw new IOException("Cannot install timetable");
      // The parse snapshot is keyed by generation anyway; drop the stale copy to save space.
      //noinspection ResultOfMethodCallIgnored
      snapshotFile().delete();
    }
    finally
    {
      temporary.delete();
    }
  }

  private void loadFeed(SQLiteDatabase db, Feed feed) throws IOException
  {
    try (ZipFile zip = new ZipFile(feed.archive))
    {
      long expandedBytes = 0;
      java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements())
      {
        long size = entries.nextElement().getSize();
        if (size < 0 || size > 256 * 1024 * 1024L)
          throw new IOException("Timetable entry too large");
        expandedBytes += size;
        if (expandedBytes > 512 * 1024 * 1024L)
          throw new IOException("Timetable archive too large");
      }
      boolean services = false;
      for (String[] table : TABLES)
        services |= load(db, zip, table[0], table[1], table[2].equals("optional"), feed.prefix)
                    && table[0].startsWith("calendar");
      if (!services)
        throw new IOException("Timetable has no service calendar");
    }
  }

  private static void create(SQLiteDatabase db, String table, String columns)
  {
    String definition = columns.replace(",", " TEXT,") + " TEXT";
    definition = definition.replace("stop_sequence TEXT", "stop_sequence INTEGER")
                           .replace("shape_pt_sequence TEXT", "shape_pt_sequence INTEGER");
    db.execSQL("CREATE TABLE " + table + " (" + definition
               + (table.equals("stop_times") ? ",departure_seconds INTEGER,arrival_seconds INTEGER" : "") + ")");
  }

  /** A line name for feeds that only give "Rīga - Jelgava": the end that is not Riga. */
  static String lineName(String longName)
  {
    String[] ends = longName.split("\\s+-\\s+");
    if (ends.length < 2)
      return longName.trim();
    String first = ends[0].trim(), last = ends[ends.length - 1].trim();
    return first.startsWith("Rīga") ? last : first;
  }

  /** Loads one table of one feed; returns whether the archive had it. */
  private boolean load(SQLiteDatabase db, ZipFile zip, String table, String columns, boolean optional, String prefix)
      throws IOException
  {
    String[] names = columns.split(",");
    ZipEntry entry = zip.getEntry(table + ".txt");
    if (entry == null)
    {
      if (optional)
        return false;
      throw new IOException("Missing " + table + ".txt");
    }
    int count = names.length + (table.equals("stop_times") ? 2 : 0);
    String placeholders = String.join(",", Collections.nCopies(count, "?"));
    // Detailed national shapes are simplified to within 10 m and identical ones are stored once.
    ShapeSimplifier shapes = table.equals("shapes") && !prefix.isEmpty() ? new ShapeSimplifier() : null;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8));
         SQLiteStatement statement = db.compileStatement("INSERT INTO " + table + " VALUES (" + placeholders + ")"))
    {
      TransitCsv csv = new TransitCsv(reader);
      List<String> header = csv.readRow();
      if (header == null)
        throw new IOException("Empty " + table);
      int[] indices = new int[names.length];
      for (int i = 0; i < names.length; ++i)
      {
        indices[i] = header.indexOf(names[i]);
        if (indices[i] < 0 && !isOptionalColumn(names[i]))
          throw new IOException("Missing column " + names[i]);
      }
      List<String> row;
      long rows = 0;
      String[] values = new String[names.length];
      while ((row = csv.readRow()) != null)
      {
        if (row.size() == 1 && row.get(0).isEmpty())
          continue;
        if (++rows > mRowLimit || row.size() != header.size())
          throw new IOException("Invalid or oversized " + table);
        String departure = null;
        String arrival = null;
        for (int i = 0; i < names.length; ++i)
        {
          String value = indices[i] < 0 ? "" : row.get(indices[i]).trim();
          if (!value.isEmpty() && IDENTIFIERS.contains(names[i]))
            value = prefix + value;
          values[i] = value;
          if (names[i].equals("departure_time"))
            departure = value;
          if (names[i].equals("arrival_time"))
            arrival = value;
        }
        if (table.equals("routes"))
        {
          if (values[1].isEmpty())
            values[1] = lineName(values[3]);
          try
          {
            values[2] = Integer.toString(RigaModeStyle.base(Integer.parseInt(values[2])));
          }
          catch (NumberFormatException e)
          {
            throw new IOException("Invalid route type " + values[2], e);
          }
        }
        if (shapes != null)
        {
          shapes.add(statement, values);
          continue;
        }
        if (table.equals("stop_times"))
        {
          // Unspecified intermediate times cannot be presented as scheduled departures.
          if (departure == null || departure.isEmpty())
            continue;
          statement.bindLong(count - 1, TransitCsv.seconds(departure));
          statement.bindLong(count, TransitCsv.seconds(arrival == null || arrival.isEmpty() ? departure : arrival));
        }
        insert(statement, values);
      }
      if (shapes != null)
        shapes.finish(db, statement);
    }
    return true;
  }

  /** Buffers one shape at a time (feeds list them grouped), simplifies it and stores each distinct path once. */
  private static final class ShapeSimplifier
  {
    private static final double TOLERANCE = 10;
    private final java.util.Map<String, String> mPaths = new java.util.HashMap<>();
    private final java.util.Map<String, String> mAliases = new java.util.HashMap<>();
    private final java.util.Set<String> mDone = new java.util.HashSet<>();
    private final List<double[]> mPoints = new java.util.ArrayList<>();
    private String mShape;

    void add(SQLiteStatement statement, String[] values) throws IOException
    {
      if (!values[0].equals(mShape))
        flush(statement);
      try
      {
        mPoints.add(new double[]{Double.parseDouble(values[3]), Double.parseDouble(values[1]),
                                 Double.parseDouble(values[2])});
      }
      catch (NumberFormatException e)
      {
        throw new IOException("Invalid shape point", e);
      }
      mShape = values[0];
    }

    void finish(SQLiteDatabase db, SQLiteStatement statement)
    {
      flush(statement);
      if (mAliases.isEmpty())
        return;
      db.execSQL("CREATE INDEX trip_shapes ON trips(shape_id)");
      for (java.util.Map.Entry<String, String> alias : mAliases.entrySet())
        db.execSQL("UPDATE trips SET shape_id=? WHERE shape_id=?", new Object[]{alias.getValue(), alias.getKey()});
      db.execSQL("DROP INDEX trip_shapes");
    }

    private void flush(SQLiteStatement statement)
    {
      if (mShape == null || mPoints.isEmpty())
        return;
      // A shape split across the file is kept as is rather than merged; ids stay unique.
      String shape = mDone.add(mShape) ? mShape : null;
      mPoints.sort((x, y) -> Double.compare(x[0], y[0]));
      boolean[] keep = simplify(mPoints);
      StringBuilder path = new StringBuilder();
      for (int i = 0; i < keep.length; ++i)
        if (keep[i])
          path.append(String.format(java.util.Locale.ROOT, "%.5f,%.5f;", mPoints.get(i)[1], mPoints.get(i)[2]));
      String same = shape == null ? null : mPaths.get(path.toString());
      if (same != null)
        mAliases.put(shape, same);
      else
      {
        if (shape != null)
          mPaths.put(path.toString(), shape);
        for (int i = 0; i < keep.length; ++i)
        {
          if (!keep[i])
            continue;
          double[] p = mPoints.get(i);
          statement.bindString(1, mShape);
          statement.bindString(2, Double.toString(p[1]));
          statement.bindString(3, Double.toString(p[2]));
          statement.bindLong(4, (long) p[0]);
          statement.executeInsert();
        }
      }
      mPoints.clear();
    }

    /** Douglas-Peucker on a local metric projection. */
    static boolean[] simplify(List<double[]> points)
    {
      int n = points.size();
      boolean[] keep = new boolean[n];
      keep[0] = keep[n - 1] = true;
      double scale = Math.cos(Math.toRadians(points.get(0)[1]));
      java.util.ArrayDeque<int[]> ranges = new java.util.ArrayDeque<>();
      ranges.push(new int[]{0, n - 1});
      while (!ranges.isEmpty())
      {
        int[] range = ranges.pop();
        int a = range[0], b = range[1];
        if (b <= a + 1)
          continue;
        double ax = points.get(a)[2] * scale * 111320, ay = points.get(a)[1] * 111320;
        double dx = points.get(b)[2] * scale * 111320 - ax, dy = points.get(b)[1] * 111320 - ay;
        double length = dx * dx + dy * dy, best = -1;
        int farthest = -1;
        for (int i = a + 1; i < b; ++i)
        {
          double px = points.get(i)[2] * scale * 111320 - ax, py = points.get(i)[1] * 111320 - ay;
          double t = length == 0 ? 0 : Math.max(0, Math.min(1, (px * dx + py * dy) / length));
          double distance = Math.hypot(px - t * dx, py - t * dy);
          if (distance > best)
          {
            best = distance;
            farthest = i;
          }
        }
        if (best > TOLERANCE)
        {
          keep[farthest] = true;
          ranges.push(new int[]{a, farthest});
          ranges.push(new int[]{farthest, b});
        }
      }
      return keep;
    }
  }

  private static void insert(SQLiteStatement statement, String[] values)
  {
    for (int i = 0; i < values.length; ++i)
      statement.bindString(i + 1, values[i]);
    statement.executeInsert();
  }

  private static boolean isOptionalColumn(String name)
  {
    return java.util.Arrays.asList("pickup_type", "trip_headsign", "parent_station", "location_type", "shape_id",
                                   "direction_id", "arrival_time", "stop_sequence", "drop_off_type",
                                   "route_long_name").contains(name);
  }

  boolean supportsJourneys()
  {
    if (!exists())
      return false;
    try (SQLiteDatabase db = open())
    {
      if (db.getVersion() < 2)
        return false;
      try (Cursor cursor = db.rawQuery("SELECT 1 FROM stop_times WHERE stop_sequence!='' LIMIT 1", null))
      {
        return cursor.moveToFirst();
      }
    }
  }
}
