package app.organicmaps.transit;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Reads an immutable database snapshot and reuses its static data between live recalculations. */
final class RigaJourneyDatabase
{
  private final RigaTransitStore mStore;
  private final RigaJourneyPlanner.Limits mLimits;
  private final int mShapeLimit;
  private final int mGeometryLimit;
  private String mGeneration;
  private Raw mRaw;
  // Instantiated service days are reused for departures in the same half hour; the trip list
  // identity also keeps the planner's sorted connection index warm between builds and refreshes.
  static final long BUCKET = 3600000L;
  private long mBucket = Long.MIN_VALUE;
  private RigaJourneyPlanner.Timetable mTimetable;
  private final Map<String, RigaJourneyGeometry> mShapes = new HashMap<>();
  private int mShapePoints;

  RigaJourneyDatabase(RigaTransitStore store)
  {
    this(store, RigaJourneyPlanner.DEFAULT_LIMITS);
  }

  RigaJourneyDatabase(RigaTransitStore store, RigaJourneyPlanner.Limits limits)
  {
    this(store, limits, 20000, 500000);
  }

  RigaJourneyDatabase(RigaTransitStore store, RigaJourneyPlanner.Limits limits, int shapeLimit, int geometryLimit)
  {
    mStore = store;
    mLimits = limits;
    mShapeLimit = shapeLimit;
    mGeometryLimit = geometryLimit;
  }

  private static final class CalendarRule
  {
    final String start;
    final String end;
    final int days;
    CalendarRule(String start, String end, int days)
    {
      this.start = start;
      this.end = end;
      this.days = days;
    }
  }

  private static final class Trip
  {
    String id;
    String service;
    String routeId;
    String name;
    String headsign;
    String shape;
    int type;
    /** Stop times packed as {stop, sequence, arrival, departure, flags}: a nationwide day has over a million. */
    int[] times = new int[5 * 8];
    int count;
    void add(int stop, int sequence, int arrival, int departure, boolean pickup, boolean dropOff)
    {
      if (5 * count == times.length)
        times = java.util.Arrays.copyOf(times, times.length * 2);
      int i = 5 * count++;
      times[i] = stop; times[i + 1] = sequence; times[i + 2] = arrival; times[i + 3] = departure;
      times[i + 4] = (pickup ? 1 : 0) | (dropOff ? 2 : 0);
    }
    int stop(int i) { return times[5 * i]; }
    int sequence(int i) { return times[5 * i + 1]; }
    int arrival(int i) { return times[5 * i + 2]; }
    int departure(int i) { return times[5 * i + 3]; }
    int flags(int i) { return times[5 * i + 4]; }
  }

  private static final class Raw
  {
    final List<RigaJourneyPlanner.Stop> stops = new ArrayList<>();
    final Map<String, Integer> stopIds = new HashMap<>();
    final Map<String, Trip> trips = new HashMap<>();
    final Map<String, CalendarRule> calendar = new HashMap<>();
    final Map<String, Integer> exceptions = new HashMap<>();
    List<RigaJourneyPlanner.Footpath> footpaths;
    /** Trips in id order, sorted once after reading. */
    List<Trip> ordered;
  }

  String generation() { return mStore.generation(); }

  /** Where routed walks are kept between app starts. */
  java.io.File walkCacheFile() { return new java.io.File(mStore.snapshotFile().getParentFile(), "riga-walks.cache"); }

  synchronized RigaJourneyPlanner.Timetable load(long departure, BooleanSupplier cancelled) throws IOException
  {
    RigaJourneyPlanner.checkCancelled(cancelled);
    String generation = mStore.generation();
    if (mRaw == null || !generation.equals(mGeneration))
    {
      mShapes.clear();
      mShapePoints = 0;
      long started = System.nanoTime();
      Raw raw = read(cancelled);
      android.util.Log.i("RigaJourneyDatabase", "Timetable read in " + (System.nanoTime() - started) / 1000000 + " ms");
      // A replacement during this read is harmless: the next query must open the newer file.
      if (generation.equals(mStore.generation()))
      {
        mRaw = raw;
        mGeneration = generation;
        mTimetable = null;
      }
      else
      {
        mRaw = null;
        mTimetable = null;
        return instantiate(raw, departure, departure, cancelled);
      }
    }
    long bucket = Math.floorDiv(departure, BUCKET) * BUCKET;
    if (mTimetable == null || bucket != mBucket)
    {
      RigaJourneyPlanner.Timetable previous = mTimetable;
      mTimetable = null;
      long started = System.nanoTime();
      mTimetable = instantiate(mRaw, bucket, bucket + BUCKET, previous, cancelled);
      android.util.Log.i("RigaJourneyDatabase", "Service days instantiated in " + (System.nanoTime() - started) / 1000000
          + " ms, trips " + mTimetable.trips.size());
      mBucket = bucket;
    }
    return mTimetable;
  }

  /** Loads static data ahead of a likely request; safe to call repeatedly from a worker. */
  void prewarm(long departure, BooleanSupplier cancelled) throws IOException { load(departure, cancelled); }

  synchronized RigaJourneyGeometry shape(String id) throws IOException
  {
    if (mGeneration == null || !mGeneration.equals(mStore.generation()))
      throw new IOException("Timetable changed during planning; calculate again");
    if (id.isEmpty())
      return null;
    if (mShapes.containsKey(id))
      return mShapes.get(id);
    List<RigaJourneyGeometry.Point> points = new ArrayList<>();
    try (SQLiteDatabase db = mStore.open(); Cursor cursor = db.rawQuery(
        "SELECT shape_pt_lat,shape_pt_lon FROM shapes WHERE shape_id=? ORDER BY shape_pt_sequence LIMIT " + (mShapeLimit + 1),
        new String[]{id}))
    {
      while (cursor.moveToNext())
      {
        double lat = cursor.getDouble(0);
        double lon = cursor.getDouble(1);
        if (points.size() >= mShapeLimit || !TransitArea.plausible(lat, lon))
          throw new IOException("Invalid or oversized route shape");
        points.add(new RigaJourneyGeometry.Point(lat, lon));
      }
    }
    if (mShapePoints + points.size() > mGeometryLimit)
      throw new IOException("Route geometry exceeds planning limits");
    mShapePoints += points.size();
    RigaJourneyGeometry shape = points.size() < 2 ? null : new RigaJourneyGeometry(points);
    mShapes.put(id, shape);
    return shape;
  }

  List<RigaJourneyGeometry.Point> legPoints(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Leg leg)
      throws IOException
  {
    if (leg.trip != null)
    {
      RigaJourneyGeometry geometry = shape(leg.trip.shapeId);
      double[] anchors = geometry == null ? null : geometry.anchors(data, leg.trip);
      if (anchors != null)
        return geometry.clip(anchors[leg.firstEvent], anchors[leg.lastEvent]);
    }
    List<RigaJourneyGeometry.Point> points = new ArrayList<>();
    if (leg.trip == null)
    {
      RigaJourneyPlanner.Stop from = data.stops.get(leg.from);
      RigaJourneyPlanner.Stop to = data.stops.get(leg.to);
      points.add(new RigaJourneyGeometry.Point(from.lat, from.lon));
      points.add(new RigaJourneyGeometry.Point(to.lat, to.lon));
    }
    else
      for (int i = leg.firstEvent; i <= leg.lastEvent; ++i)
      {
        RigaJourneyPlanner.Stop stop = data.stops.get(leg.trip.events.get(i).stop);
        points.add(new RigaJourneyGeometry.Point(stop.lat, stop.lon));
      }
    return points;
  }

  private void ceiling(SQLiteDatabase db, String table, long limit) throws IOException
  {
    try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null))
    {
      cursor.moveToFirst();
      if (cursor.getLong(0) > limit)
        throw new IOException("Timetable exceeds journey planning limits: " + table);
    }
  }

  private static final int SNAPSHOT_VERSION = 1;

  /** Reads the parsed timetable from its snapshot, falling back to SQLite and refreshing the snapshot. */
  private Raw read(BooleanSupplier cancelled) throws IOException
  {
    String generation = mStore.generation();
    java.io.File file = mStore.snapshotFile();
    if (file.isFile())
    {
      try
      {
        Raw raw = readSnapshot(file, generation, cancelled);
        if (raw != null)
        {
          raw.footpaths = transfers(raw.stops, cancelled);
          return raw;
        }
      }
      catch (java.util.concurrent.CancellationException e) { throw e; }
      catch (IOException | RuntimeException e) { android.util.Log.w("RigaJourneyDatabase", "Ignoring timetable snapshot", e); }
      //noinspection ResultOfMethodCallIgnored
      file.delete();
    }
    Raw raw = readDatabase(cancelled);
    try
    {
      if (generation.equals(mStore.generation())) writeSnapshot(file, generation, raw);
    }
    catch (IOException | RuntimeException e)
    {
      // Only a cache: planning continues from SQLite next time.
      android.util.Log.w("RigaJourneyDatabase", "Couldn't store timetable snapshot", e);
      //noinspection ResultOfMethodCallIgnored
      new java.io.File(file.getPath() + ".tmp").delete();
    }
    return raw;
  }

  private static void writeSnapshot(java.io.File file, String generation, Raw raw) throws IOException
  {
    java.io.File temporary = new java.io.File(file.getPath() + ".tmp");
    try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
        new java.io.FileOutputStream(temporary), 1 << 16)))
    {
      out.writeInt(SNAPSHOT_VERSION);
      out.writeUTF(generation);
      out.writeInt(raw.stops.size());
      for (RigaJourneyPlanner.Stop stop : raw.stops)
      {
        out.writeUTF(stop.id); out.writeUTF(stop.name); out.writeUTF(stop.parent);
        out.writeDouble(stop.lat); out.writeDouble(stop.lon);
      }
      out.writeInt(raw.trips.size());
      for (Trip trip : raw.trips.values())
      {
        out.writeUTF(trip.id); out.writeUTF(trip.service); out.writeUTF(trip.routeId); out.writeUTF(trip.name);
        out.writeUTF(trip.headsign); out.writeUTF(trip.shape); out.writeInt(trip.type);
        out.writeInt(trip.count);
        for (int i = 0; i < trip.count; ++i)
        {
          out.writeInt(trip.stop(i)); out.writeInt(trip.sequence(i)); out.writeInt(trip.arrival(i));
          out.writeInt(trip.departure(i)); out.writeByte(trip.flags(i));
        }
      }
      out.writeInt(raw.calendar.size());
      for (Map.Entry<String, CalendarRule> entry : raw.calendar.entrySet())
      {
        out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue().start); out.writeUTF(entry.getValue().end);
        out.writeInt(entry.getValue().days);
      }
      out.writeInt(raw.exceptions.size());
      for (Map.Entry<String, Integer> entry : raw.exceptions.entrySet())
      {
        out.writeUTF(entry.getKey()); out.writeInt(entry.getValue());
      }
      out.writeInt(0x52494741); // End marker: a truncated snapshot is rejected.
    }
    if (!temporary.renameTo(file))
    {
      //noinspection ResultOfMethodCallIgnored
      temporary.delete();
      throw new IOException("Couldn't replace timetable snapshot");
    }
  }

  private Raw readSnapshot(java.io.File file, String generation, BooleanSupplier cancelled) throws IOException
  {
    try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
        new java.io.FileInputStream(file), 1 << 16)))
    {
      if (in.readInt() != SNAPSHOT_VERSION || !in.readUTF().equals(generation)) return null;
      Raw raw = new Raw();
      int stops = in.readInt();
      if (stops < 0 || stops > mLimits.stops) throw new IOException("Invalid snapshot");
      for (int i = 0; i < stops; ++i)
      {
        RigaJourneyPlanner.Stop stop = new RigaJourneyPlanner.Stop(in.readUTF(), in.readUTF(), in.readUTF(),
            in.readDouble(), in.readDouble());
        raw.stopIds.put(stop.id, raw.stops.size());
        raw.stops.add(stop);
      }
      int trips = in.readInt();
      if (trips < 0 || trips > mLimits.trips) throw new IOException("Invalid snapshot");
      long rows = 0;
      for (int t = 0; t < trips; ++t)
      {
        if ((t & 255) == 0) RigaJourneyPlanner.checkCancelled(cancelled);
        Trip trip = new Trip();
        trip.id = in.readUTF(); trip.service = in.readUTF(); trip.routeId = in.readUTF(); trip.name = in.readUTF();
        trip.headsign = in.readUTF(); trip.shape = in.readUTF(); trip.type = in.readInt();
        int times = in.readInt();
        if (times < 0 || (rows += times) > (long) mLimits.connections + mLimits.trips) throw new IOException("Invalid snapshot");
        trip.times = new int[5 * Math.max(times, 1)];
        trip.count = times;
        for (int i = 0; i < times; ++i)
        {
          int at = 5 * i;
          int stop = in.readInt();
          if (stop < 0 || stop >= stops) throw new IOException("Invalid snapshot");
          trip.times[at] = stop; trip.times[at + 1] = in.readInt(); trip.times[at + 2] = in.readInt();
          trip.times[at + 3] = in.readInt(); trip.times[at + 4] = in.readByte();
        }
        raw.trips.put(trip.id, trip);
      }
      int rules = in.readInt();
      for (int i = 0; i < rules; ++i)
        raw.calendar.put(in.readUTF(), new CalendarRule(in.readUTF(), in.readUTF(), in.readInt()));
      int exceptions = in.readInt();
      for (int i = 0; i < exceptions; ++i)
        raw.exceptions.put(in.readUTF(), in.readInt());
      if (in.readInt() != 0x52494741) throw new IOException("Truncated snapshot");
      return raw;
    }
  }

  private Raw readDatabase(BooleanSupplier cancelled) throws IOException
  {
    Raw raw = new Raw();
    try (SQLiteDatabase db = mStore.open())
    {
      if (db.getVersion() < 2)
        throw new IOException("Update the downloaded timetable to enable journey planning");
      ceiling(db, "stops", mLimits.stops);
      ceiling(db, "trips", mLimits.trips);
      ceiling(db, "stop_times", (long) mLimits.connections + mLimits.trips);
      try (Cursor cursor = db.rawQuery("SELECT stop_id,stop_name,parent_station,stop_lat,stop_lon FROM stops", null))
      {
        while (cursor.moveToNext())
        {
          double lat = cursor.getDouble(3);
          double lon = cursor.getDouble(4);
          if (!TransitArea.plausible(lat, lon))
            continue;
          String id = cursor.getString(0);
          if (raw.stopIds.containsKey(id))
            throw new IOException("Duplicate stop identifier");
          raw.stopIds.put(id, raw.stops.size());
          raw.stops.add(new RigaJourneyPlanner.Stop(id, cursor.getString(1), cursor.getString(2), lat, lon));
        }
      }
      try (Cursor cursor = db.rawQuery("SELECT t.trip_id,t.service_id,t.route_id,r.route_short_name,t.trip_headsign,"
                                      + "t.shape_id,r.route_type FROM trips t JOIN routes r ON r.route_id=t.route_id", null))
      {
        while (cursor.moveToNext())
        {
          Trip trip = new Trip();
          trip.id = cursor.getString(0);
          trip.service = cursor.getString(1);
          trip.routeId = cursor.getString(2);
          trip.name = cursor.getString(3);
          trip.headsign = cursor.getString(4);
          trip.shape = cursor.getString(5);
          trip.type = cursor.getInt(6);
          raw.trips.put(trip.id, trip);
        }
      }
      try (Cursor cursor = db.rawQuery("SELECT trip_id,stop_id,stop_sequence,arrival_seconds,departure_seconds,"
                                      + "pickup_type,drop_off_type FROM stop_times WHERE stop_sequence!='' "
                                      + "ORDER BY trip_id,stop_sequence", null))
      {
        int rows = 0;
        while (cursor.moveToNext())
        {
          if ((++rows & 1023) == 0)
            RigaJourneyPlanner.checkCancelled(cancelled);
          Trip trip = raw.trips.get(cursor.getString(0));
          Integer stop = raw.stopIds.get(cursor.getString(1));
          if (trip == null)
            continue;
          if (stop == null)
            throw new IOException("Trip refers to an invalid stop");
          int sequence;
          try
          {
            sequence = Integer.parseInt(cursor.getString(2));
          }
          catch (NumberFormatException e)
          {
            throw new IOException("Invalid timetable stop sequence", e);
          }
          trip.add(stop, sequence, cursor.getInt(3), cursor.getInt(4), regular(cursor.getString(5)),
                   regular(cursor.getString(6)));
        }
      }
      try (Cursor cursor = db.rawQuery("SELECT service_id,start_date,end_date,monday,tuesday,wednesday,thursday,"
                                      + "friday,saturday,sunday FROM calendar", null))
      {
        while (cursor.moveToNext())
        {
          int days = 0;
          for (int i = 0; i < 7; ++i)
            if (cursor.getString(i + 3).equals("1"))
              days |= 1 << i;
          raw.calendar.put(cursor.getString(0), new CalendarRule(cursor.getString(1), cursor.getString(2), days));
        }
      }
      try (Cursor cursor = db.rawQuery("SELECT service_id,date,exception_type FROM calendar_dates", null))
      {
        while (cursor.moveToNext())
          raw.exceptions.put(cursor.getString(0) + "@" + cursor.getString(1), cursor.getInt(2));
      }
    }
    raw.footpaths = transfers(raw.stops, cancelled);
    return raw;
  }

  private static boolean regular(String rule)
  {
    return rule.isEmpty() || rule.equals("0");
  }

  private RigaJourneyPlanner.Timetable instantiate(Raw raw, long departure, long latest, BooleanSupplier cancelled)
      throws IOException
  {
    return instantiate(raw, departure, latest, null, cancelled);
  }

  /**
   * The trips running around {@code departure}. Instances of {@code previous} (an earlier hour of the same
   * timetable) are reused: they are immutable, and rebuilding every event of Latvia each hour took seconds.
   */
  private RigaJourneyPlanner.Timetable instantiate(Raw raw, long departure, long latest,
                                                   RigaJourneyPlanner.Timetable previous, BooleanSupplier cancelled)
      throws IOException
  {
    List<RigaJourneyPlanner.Trip> result = new ArrayList<>();
    Map<String, RigaJourneyPlanner.Trip> reusable = new HashMap<>();
    if (previous != null)
      for (RigaJourneyPlanner.Trip trip : previous.trips)
        reusable.put(trip.id, trip);
    SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
    format.setTimeZone(RigaTransitStore.ZONE);
    long end = latest + RigaJourneyPlanner.WINDOW;
    long eventCount = 0;
    if (raw.ordered == null)
    {
      List<Trip> ordered = new ArrayList<>(raw.trips.values());
      Collections.sort(ordered, java.util.Comparator.comparing(t -> t.id));
      raw.ordered = ordered;
    }
    for (int offset = -3; offset <= 2; ++offset)
    {
      Calendar day = Calendar.getInstance(RigaTransitStore.ZONE);
      day.setTimeInMillis(departure);
      day.add(Calendar.DAY_OF_MONTH, offset);
      String key = format.format(day.getTime());
      int weekday = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday = 0.
      long origin = RigaTransitStore.serviceOrigin(day);
      int checked = 0;
      for (Trip trip : raw.ordered)
      {
        if ((++checked & 255) == 0)
          RigaJourneyPlanner.checkCancelled(cancelled);
        if (trip.count < 2 || !active(raw, trip.service, key, weekday))
          continue;
        long first = origin + trip.departure(0) * 1000L;
        long last = origin + trip.arrival(trip.count - 1) * 1000L;
        // Retain recently scheduled trips so an observed delay can make them boardable again.
        if (last + 7200000L < departure || first - 300000L > end)
          continue;
        if (result.size() >= mLimits.trips || (eventCount += trip.count) > (long) mLimits.connections + mLimits.trips)
          throw new IOException("Too many journey service instances");
        String id = trip.id + "@" + key;
        RigaJourneyPlanner.Trip instance = reusable.get(id);
        if (instance == null)
        {
          List<RigaJourneyPlanner.Event> events = new ArrayList<>(trip.count);
          for (int i = 0; i < trip.count; ++i)
          {
            int flags = trip.flags(i);
            events.add(new RigaJourneyPlanner.Event(trip.stop(i), trip.sequence(i), origin + trip.arrival(i) * 1000L,
                                                    origin + trip.departure(i) * 1000L, (flags & 1) != 0,
                                                    (flags & 2) != 0));
          }
          instance = new RigaJourneyPlanner.Trip(id, trip.routeId, trip.name, trip.headsign, trip.type, trip.shape,
                                                 origin, Collections.unmodifiableList(events));
        }
        result.add(instance);
      }
    }
    return new RigaJourneyPlanner.Timetable(Collections.unmodifiableList(raw.stops),
        Collections.unmodifiableList(result), Collections.unmodifiableList(raw.footpaths));
  }

  private static boolean active(Raw data, String service, String date, int weekday)
  {
    Integer exception = data.exceptions.get(service + "@" + date);
    if (exception != null)
      return exception == 1;
    CalendarRule rule = data.calendar.get(service);
    return rule != null && rule.start.compareTo(date) <= 0 && rule.end.compareTo(date) >= 0
           && (rule.days & (1 << weekday)) != 0;
  }

  private List<RigaJourneyPlanner.Footpath> transfers(List<RigaJourneyPlanner.Stop> stops, BooleanSupplier cancelled)
      throws IOException
  {
    Map<String, List<Integer>> groups = new HashMap<>();
    for (int i = 0; i < stops.size(); ++i)
    {
      RigaJourneyPlanner.Stop stop = stops.get(i);
      String name = stop.name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
      if (!name.isEmpty())
        groups.computeIfAbsent("name:" + name, ignored -> new ArrayList<>()).add(i);
      if (!stop.parent.isEmpty())
        groups.computeIfAbsent("parent:" + stop.parent, ignored -> new ArrayList<>()).add(i);
    }
    List<RigaJourneyPlanner.Footpath> result = new ArrayList<>();
    Set<Long> connected = new HashSet<>();
    long candidates = 0;
    for (List<Integer> group : groups.values())
    {
      for (int a = 0; a < group.size(); ++a)
      {
        RigaJourneyPlanner.checkCancelled(cancelled);
        for (int b = a + 1; b < group.size(); ++b)
        {
          if (++candidates > mLimits.footpaths * 2L)
            throw new IOException("Too many platform transfer candidates");
          int from = group.get(a);
          int to = group.get(b);
          long key = ((long) Math.min(from, to) << 32) | Math.max(from, to);
          if (connected.contains(key))
            continue;
          RigaJourneyPlanner.Stop first = stops.get(from);
          RigaJourneyPlanner.Stop second = stops.get(to);
          double distance = distance(first.lat, first.lon, second.lat, second.lon);
          // Only nearby platforms of the same named stop/station are connected. Do not invent
          // arbitrary cross-river or cross-city walking routes from straight-line proximity.
          if (distance > 120)
            continue;
          if (result.size() + 2 > mLimits.footpaths)
            throw new IOException("Too many platform transfers");
          connected.add(key);
          int metres = (int) Math.ceil(distance);
          int seconds = Math.max(45, metres);
          result.add(new RigaJourneyPlanner.Footpath(from, to, seconds, metres));
          result.add(new RigaJourneyPlanner.Footpath(to, from, seconds, metres));
        }
      }
    }
    return result;
  }

  static double distance(double lat1, double lon1, double lat2, double lon2)
  {
    double y = Math.toRadians(lat2 - lat1);
    double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
    return Math.sqrt(x * x + y * y) * 6371000;
  }
}
