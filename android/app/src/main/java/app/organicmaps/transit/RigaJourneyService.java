package app.organicmaps.transit;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** One cancellable calculation, with a scheduled fallback when the optional live dependency fails. */
final class RigaJourneyService
{
  static final class Result
  {
    final RigaJourneyPlanner.Timetable data;
    final RigaJourneyPlanner.Journey journey;
    final int matched;
    final long received;
    final boolean liveFailed;
    final double[][] lines;
    final boolean[] walking;
    // Set by the service only: the vehicles to show and, during a trip, the first ride that can no longer be
    // reached in time (-1 for none).
    private List<RigaVehicle> vehicles = Collections.emptyList();
    private int missedLeg = -1;
    Result(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Journey journey, int matched,
           long received, boolean liveFailed, double[][] lines, boolean[] walking)
    {
      this.data = data;
      this.journey = journey;
      this.matched = matched;
      this.received = received;
      this.liveFailed = liveFailed;
      this.lines = lines;
      this.walking = walking;
    }

    /** Rides at least once: not the whole way on foot. */
    boolean rides()
    {
      return journey != null && journey.boardings > 0;
    }

    /** Rides, and every leg has its line on the map. */
    boolean ridesDrawn()
    {
      return rides() && lines.length == journey.legs.size();
    }

    /** When to leave the start: the first ride less the walk to it. */
    long leaves()
    {
      return RigaJourneyAlternatives.leave(journey);
    }

    long arrives()
    {
      return journey.arrival;
    }

    /** When the first ride leaves; the arrival when it has none. */
    long firstRideLeaves()
    {
      for (RigaJourneyPlanner.Leg leg : journey.legs) if (leg.trip != null) return leg.departure;
      return journey.arrival;
    }

    /** From leaving to arriving. */
    long takes()
    {
      return arrives() - leaves();
    }

    /** The first leg's departure, or the journey's own without legs. */
    long startsAt()
    {
      return journey.legs.isEmpty() ? journey.departure : journey.legs.get(0).departure;
    }

    /** The last leg's arrival, or the journey's own without legs. */
    long endsAt()
    {
      return journey.legs.isEmpty() ? journey.arrival : journey.legs.get(journey.legs.size() - 1).arrival;
    }

    /** Its key among the offered journeys: its trips, or {@link RigaWalkChoice#KEY} for the walk. */
    String key()
    {
      return journey.boardings == 0 ? RigaWalkChoice.KEY : RigaJourneyAlternatives.signature(journey);
    }

    RigaJourneyPlanner.Stop stop(int index)
    {
      return data.stops.get(index);
    }

    /** A stop's name; the journey's own start and finish (the last two stops) take the given titles. */
    String stopName(int index, String start, String finish)
    {
      int stops = data.stops.size();
      return index == stops - 2 ? start : index == stops - 1 ? finish : stop(index).name;
    }

    List<RigaVehicle> vehicles()
    {
      return vehicles;
    }

    /** During a trip: a ride can no longer be reached in time. */
    boolean missed()
    {
      return missedLeg >= 0;
    }

    /** The first ride that can no longer be reached in time, or null. */
    @androidx.annotation.Nullable
    RigaJourneyPlanner.Trip missedTrip()
    {
      return missed() ? journey.legs.get(missedLeg).trip : null;
    }

    /** The same answer with its legs drawn differently: vehicles and a missed ride are kept. */
    Result reshaped(RigaJourneyPlanner.Journey journey, double[][] lines, boolean[] walking)
    {
      Result result = new Result(data, journey, matched, received, liveFailed, lines, walking);
      result.vehicles = vehicles;
      RigaJourneyPlanner.Trip missed = missedTrip();
      for (int i = 0; missed != null && i < journey.legs.size(); ++i)
        if (journey.legs.get(i).trip == missed)
        {
          result.missedLeg = i;
          break;
        }
      return result;
    }
  }

  private final RigaJourneyDatabase mDatabase;
  private final RigaLiveMap.Fetcher mVehicles;
  // Serial provider worker only. A new service/database generation starts a new cache.
  void clearWalkingCache() { mWalks.clear(); mWalkGeneration=null; }
  int walkCacheHits, walkCacheMisses;
  /** Releases the retained native pedestrian graph; the next walk reloads current map data. */
  private boolean mGraphLoaded;
  void releaseWalkingGraph() { app.organicmaps.sdk.Framework.nativeResetPedestrianConnections(); mGraphLoaded = false; }
  /** Loads the pedestrian graph with a tiny walk, outside any routing deadline. */
  private void loadWalkingGraph(double lat, double lon)
  {
    if (mGraphLoaded) return;
    app.organicmaps.sdk.Framework.nativePedestrianConnection(lat, lon, lat + 0.0005, lon + 0.0005);
    mGraphLoaded = true;
  }
  /** Loads the timetable and the Latvia pedestrian graph before the user asks for a route. */
  void prewarm(double lat, double lon, BooleanSupplier cancelled) throws Exception
  {
    mDatabase.prewarm(System.currentTimeMillis(), cancelled);
    RigaJourneyPlanner.Timetable data = mDatabase.load(System.currentTimeMillis(), cancelled);
    RigaJourneyPlanner.ConnectionIndex.of(data.trips, data.stops.size() + 2, RigaJourneyPlanner.DEFAULT_LIMITS, cancelled);
    if (Double.isFinite(lat) && Double.isFinite(lon)) loadWalkingGraph(lat, lon);
  }
  private String mWalkGeneration;
  private final Map<String, double[]> mWalks = new java.util.LinkedHashMap<>();
  static final class MissingMapException extends java.io.IOException {}
  private double[] walk(double a, double b, double c, double d)
  {
    String key = walkKey(a, b, c, d);
    double[] result = walkCache().get(key);
    if (result != null) { ++walkCacheHits; mWalks.put(key, mWalks.remove(key)); return result; }
    ++walkCacheMisses;
    result = app.organicmaps.sdk.Framework.nativePedestrianConnection(a,b,c,d);
    if (result.length == 1 && (result[0] == -4 || result[0] == -9 || result[0] == -11))
    {
      // A map downloaded later must be visible to the next attempt.
      releaseWalkingGraph();
      throw new java.io.UncheckedIOException(new MissingMapException());
    }
    if (mWalks.size() >= WALK_CACHE) mWalks.remove(mWalks.keySet().iterator().next());
    // Do not retain failures: a map may finish downloading before the next request.
    if (result.length != 0) { mWalks.put(key, result); mWalksDirty = true; }
    return result;
  }

  /** A walk routed before, here or in an earlier run, without routing it now. */
  private double[] knownWalk(double a, double b, double c, double d)
  {
    return walkCache().get(walkKey(a, b, c, d));
  }

  private static String walkKey(double a, double b, double c, double d) { return a + ":" + b + ":" + c + ":" + d; }

  private static final int WALK_CACHE = 4096;
  private static final int WALK_FORMAT = 1;
  private boolean mWalksLoaded, mWalksDirty;

  /** The walk cache of the current timetable, read from disk on first use. */
  private Map<String, double[]> walkCache()
  {
    String generation = mDatabase.generation();
    if (!generation.equals(mWalkGeneration))
    {
      mWalks.clear();
      mWalkGeneration = generation;
      mWalksLoaded = false;
      mWalksDirty = false;
    }
    if (!mWalksLoaded)
    {
      mWalksLoaded = true;
      readWalks(mDatabase.walkCacheFile(), generation);
    }
    return mWalks;
  }

  private void readWalks(java.io.File file, String generation)
  {
    if (file == null || !file.isFile()) return;
    try (java.io.DataInputStream in = new java.io.DataInputStream(
             new java.io.BufferedInputStream(new java.io.FileInputStream(file), 1 << 16)))
    {
      if (in.readInt() != WALK_FORMAT || !in.readUTF().equals(generation)) return;
      int count = in.readInt();
      if (count < 0 || count > WALK_CACHE) return;
      for (int i = 0; i < count; ++i)
      {
        String key = in.readUTF();
        int length = in.readInt();
        if (length < 6 || length > 40002) { mWalks.clear(); return; }
        double[] path = new double[length];
        for (int j = 0; j < length; ++j) path[j] = in.readDouble();
        mWalks.put(key, path);
      }
    }
    catch (java.io.IOException | RuntimeException e)
    {
      mWalks.clear();
    }
  }

  /** Keeps routed walks for the next app start; a walk between two stops costs 0.3 s or more to route. */
  void saveWalks()
  {
    java.io.File file = mDatabase.walkCacheFile();
    if (!mWalksDirty || file == null || mWalkGeneration == null) return;
    java.io.File temporary = new java.io.File(file.getPath() + ".tmp");
    try (java.io.DataOutputStream out = new java.io.DataOutputStream(
             new java.io.BufferedOutputStream(new java.io.FileOutputStream(temporary), 1 << 16)))
    {
      out.writeInt(WALK_FORMAT);
      out.writeUTF(mWalkGeneration);
      out.writeInt(mWalks.size());
      for (Map.Entry<String, double[]> entry : mWalks.entrySet())
      {
        out.writeUTF(entry.getKey());
        out.writeInt(entry.getValue().length);
        for (double value : entry.getValue()) out.writeDouble(value);
      }
    }
    catch (java.io.IOException e)
    {
      temporary.delete();
      return;
    }
    if (temporary.renameTo(file)) mWalksDirty = false;
    else temporary.delete();
  }

  RigaJourneyService(RigaJourneyDatabase database, RigaLiveMap.Fetcher vehicles)
  {
    mDatabase = database;
    mVehicles = vehicles;
  }

  private volatile RigaStopDepartures.Source mStops;

  /** Where the departures listed at stops come from (none by default: only the vehicle feed is used). */
  void setStopDepartures(RigaStopDepartures.Fetcher fetcher)
  {
    mStops = fetcher == null ? null : new RigaStopDepartures.Source(fetcher);
  }

  /**
   * {@code known} with estimates from the departures listed at {@code stops}, for the trips the vehicle feed
   * has not placed yet (a late vehicle still on its way to its first stop). Left as it is when that fails.
   */
  private Map<Integer, RigaJourneyPlanner.Prediction> withStops(RigaJourneyPlanner.Timetable data,
      Map<Integer, RigaJourneyPlanner.Prediction> known, List<String> stops, BooleanSupplier cancelled)
  {
    RigaStopDepartures.Source source = mStops;
    if (source == null || stops.isEmpty()) return known;
    try
    {
      long now = System.currentTimeMillis();
      List<RigaStopDepartures.Row> rows = source.rows(stops, now, cancelled);
      RigaJourneyPlanner.checkCancelled(cancelled);
      Map<Integer, RigaJourneyPlanner.Prediction> found = RigaStopDepartures.predict(data, rows, known, now);
      app.organicmaps.sdk.util.log.Logger.i("RigaMatch", "Stops " + stops + ": " + rows.size() + " departures, "
          + found.size() + " trips estimated");
      return RigaStopDepartures.merge(known, found);
    }
    catch (java.util.concurrent.CancellationException e)
    {
      throw e;
    }
    catch (Exception e)
    {
      app.organicmaps.sdk.util.log.Logger.w("RigaMatch", "Stop departures failed", e);
      return known;
    }
  }

  /** At most this many stops are asked about per calculation or refresh. */
  private static final int MAX_ASKED = 6;

  private static List<String> boardingStops(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Journey journey,
                                            long now)
  {
    return boardingStops(data, journey, new java.util.ArrayList<>(), now);
  }

  /** Where the rides still to come are boarded, soonest first (the stops worth asking about). */
  private static List<String> boardingStops(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Journey journey,
                                            List<String> into, long now)
  {
    for (RigaJourneyPlanner.Leg leg : journey.legs)
    {
      if (leg.trip == null || leg.departure < now - 5 * 60000L || leg.departure > now + 2 * 3600000L) continue;
      String id = data.stops.get(leg.trip.events.get(leg.firstEvent).stop).id;
      if (id != null && !into.contains(id) && into.size() < MAX_ASKED) into.add(id);
    }
    return into;
  }

  Result calculate(String from, String to, long departure, boolean live, BooleanSupplier cancelled) throws Exception
  {
    return calculate(from, to, null, departure, live, cancelled);
  }

  Result calculateLocations(double fromLat, double fromLon, double toLat, double toLon,
                            long departure, boolean live, BooleanSupplier cancelled) throws Exception
  {
    // The native graph is retained between builds and live refreshes; the provider releases it when idle.
    return calculate(null, null, new double[]{fromLat, fromLon, toLat, toLon}, departure, live, cancelled);
  }

  /** The best scheduled journey only (no live data, no geometry); keeps the shown plan's state as it is. */
  /**
   * A quick, approximate journey for the mode chips: walks are straight lines with a detour factor, so no
   * pedestrian graph is loaded and the shown plan's walking cache is untouched.
   */
  Result estimate(double fromLat, double fromLon, double toLat, double toLon, long departure) throws Exception
  {
    RigaJourneyPlanner.Timetable data = mDatabase.load(departure, () -> false);
    RigaLocationRouter router = new RigaLocationRouter(RigaJourneyService::straightWalk, RigaJourneyService::earliest);
    RigaLocationRouter.Result result = router.plan(data, fromLat, fromLon, toLat, toLon, departure,
        Collections.emptyMap(), () -> false);
    return new Result(result.data, result.journey, 0, 0, false, new double[0][], new boolean[0]);
  }

  /** The router's "now", before which no journey leaves: the chosen time when the rider looks earlier today. */
  private static long earliest()
  {
    return RigaTimeChoice.earliest(System.currentTimeMillis());
  }

  /** Seconds, metres and the two end points, like a routed walk; streets add about 30 % to the line. */
  static double[] straightWalk(double fromLat, double fromLon, double toLat, double toLon)
  {
    double x = Math.toRadians(toLon - fromLon) * Math.cos(Math.toRadians((fromLat + toLat) / 2));
    double y = Math.toRadians(toLat - fromLat);
    double metres = Math.sqrt(x * x + y * y) * 6371000 * 1.3;
    return new double[] {metres / (4.8 / 3.6), metres, fromLat, fromLon, toLat, toLon};
  }

  private Result calculate(String from, String to, double[] endpoints, long departure, boolean live,
                           BooleanSupplier cancelled) throws Exception
  {
    RigaJourneyPlanner.Timetable data = mDatabase.load(departure, cancelled);
    Map<Integer, RigaJourneyPlanner.Prediction> predictions = Collections.emptyMap();
    List<RigaVehicle> snapshot = Collections.emptyList();
    boolean failed = false;
    long received = 0;
    if (live)
    {
      try
      {
        List<RigaVehicle> vehicles = mVehicles.fetch();
        snapshot = vehicles;
        RigaJourneyPlanner.checkCancelled(cancelled);
        long now = System.currentTimeMillis();
        // A slow network request must not leave a newly calculated route boarding in the past.
        departure = Math.max(departure, now);
        received = now;
        predictions = RigaJourneyMatcher.match(data, vehicles, now, mDatabase::shape, cancelled);
      }
      catch (java.util.concurrent.CancellationException e)
      {
        throw e;
      }
      catch (Exception e)
      {
        RigaJourneyPlanner.checkCancelled(cancelled);
        departure = Math.max(departure, System.currentTimeMillis());
        failed = true;
      }
    }
    RigaJourneyPlanner.checkCancelled(cancelled);
    Map<Long, double[]> paths = Collections.emptyMap();
    RigaJourneyPlanner.Journey journey;
    if (endpoints == null)
    {
      mRouter = null;
      journey = RigaJourneyPlanner.plan(data, from, to, departure, predictions, cancelled);
      // The departures listed where this journey boards: a vehicle running late there can make an earlier
      // departure (already past on the timetable) the one to take, so the journey is planned again with them.
      Map<Integer, RigaJourneyPlanner.Prediction> listed = live && !failed
          ? withStops(data, predictions, boardingStops(data, journey, System.currentTimeMillis()), cancelled)
          : predictions;
      if (listed.size() > predictions.size())
      {
        predictions = listed;
        journey = RigaJourneyPlanner.plan(data, from, to, departure, predictions, cancelled);
      }
    }
    else
    {
      // Loading the country pedestrian graph can take 6-20 s on slow devices; it is a one-off
      // cost and must not eat the refinement budget meant for routing walks.
      loadWalkingGraph(endpoints[0], endpoints[1]);
      RigaJourneyPlanner.checkCancelled(cancelled);
      mRouter = new RigaLocationRouter(new RigaLocationRouter.Walker() {
        @Override public double[] route(double a, double b, double c, double d) { return walk(a, b, c, d); }
        @Override public double[] known(double a, double b, double c, double d) { return knownWalk(a, b, c, d); }
      }, RigaJourneyService::earliest);
      RigaLocationRouter.Result result = mRouter.plan(data,
          endpoints[0], endpoints[1], endpoints[2], endpoints[3], departure, predictions, cancelled);
      // As above: what is listed where the journey boards, and the journey again when that changes anything.
      Map<Integer, RigaJourneyPlanner.Prediction> listed = live && !failed
          ? withStops(data, predictions, boardingStops(result.data, result.journey, System.currentTimeMillis()), cancelled)
          : predictions;
      if (listed.size() > predictions.size())
      {
        predictions = listed;
        result = mRouter.plan(data, endpoints[0], endpoints[1], endpoints[2], endpoints[3], departure,
                              predictions, cancelled);
      }
      data = result.data;
      journey = result.journey;
      paths = result.paths;
      mRouterPaths = paths;
    }
    double[][][] geometry = geometry(data, journey, paths, endpoints != null, cancelled);
    double[][] lines = geometry[0];
    boolean[] walking = new boolean[lines.length];
    for (int i = 0; i < lines.length; ++i) walking[i] = journey.legs.get(i).trip == null;
    Result result = new Result(data, journey, predictions.size(), received, failed, lines, walking);
    result.vehicles = RigaJourneyVehicles.select(journey, data.stops, snapshot, System.currentTimeMillis());
    mLastPredictions = predictions;
    remember(snapshot);
    mLastDeparture = departure;
    return result;
  }

  /**
   * The whole way on foot, as a journey of one walk from the start to the destination (the planner's stop list
   * with the two points appended, as a planned journey has them), for a part of a multi-stop route that needs no
   * ride. {@code null} when no walk can be routed.
   */
  Result walkOnly(double fromLat, double fromLon, double toLat, double toLon, long departure,
                  BooleanSupplier cancelled) throws Exception
  {
    RigaJourneyPlanner.Timetable source = mDatabase.load(departure, cancelled);
    loadWalkingGraph(fromLat, fromLon);
    double[] path = walk(fromLat, fromLon, toLat, toLon);
    if (path.length < 2) return null;
    List<RigaJourneyPlanner.Stop> stops = new java.util.ArrayList<>(source.stops);
    int origin = stops.size();
    stops.add(new RigaJourneyPlanner.Stop("riga:map-origin", "Start", "", fromLat, fromLon));
    stops.add(new RigaJourneyPlanner.Stop("riga:map-destination", "Destination", "", toLat, toLon));
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(stops, source.trips, source.footpaths);
    long arrival = departure + Math.round(path[0]) * 1000;
    RigaJourneyPlanner.Leg leg = new RigaJourneyPlanner.Leg(origin, origin + 1, departure, arrival, null, -1, -1,
        (int) Math.round(path[1]), null);
    RigaJourneyPlanner.Journey journey = new RigaJourneyPlanner.Journey(departure,
        Collections.singletonList(leg), 0);
    double[] line = path.length >= 6 ? java.util.Arrays.copyOfRange(path, 2, path.length)
        : new double[] {fromLat, fromLon, toLat, toLon};
    return new Result(data, journey, 0, 0, false, new double[][] {line}, new boolean[] {true});
  }

  /** Rider this close to the destination is there: nothing left to re-route. */
  static final double REWALK_DONE_METRES = 30;
  /** Rider this close to the last ride's line may still be on board (a late vehicle). */
  static final double REWALK_ON_RIDE_METRES = 40;

  /**
   * During a walk of the trip (to the first stop, a change, or to the destination): that walk re-routed from where
   * the rider is, so its line and its arrival follow a rider who walks another way or slower than planned. The
   * walk keeps its start; its end becomes now plus the routed walk, and a re-time then shows a missed connection.
   * Before the trip begins, its first walk is routed from where the rider is too (they may not be at the planned
   * start): it then ends when the first ride leaves, starting that much earlier ("Leave in"), or, when there is no
   * longer time for it, starts now and marks that ride missed.
   * Returns {@code trip} unchanged when the rider is not walking or no walk can be routed.
   */
  Result rewalk(Result trip, double lat, double lon, long now)
  {
    if (trip.journey == null || !Double.isFinite(lat) || !Double.isFinite(lon)) return trip;
    List<RigaJourneyPlanner.Leg> legs = trip.journey.legs;
    if (legs.isEmpty() || trip.lines.length != legs.size()) return trip;
    int current = walking(legs, now);
    boolean ahead = current < 0 && legs.get(0).trip == null && legs.get(0).departure > now;
    if (ahead) current = 0;
    if (current < 0) return trip;
    RigaJourneyPlanner.Leg walk = legs.get(current);
    double[] line = trip.lines[current];
    if (line.length < 2) return trip;
    double toLat = line[line.length - 2], toLon = line[line.length - 1];
    if (RigaJourneyDatabase.distance(lat, lon, toLat, toLon) < REWALK_DONE_METRES) return trip;
    // Still on the ride before (a late vehicle: next to its line, away from where the walk starts), or already
    // on the ride after (next to its line, away from where it is boarded): keep the plan.
    boolean nearStart = RigaJourneyDatabase.distance(lat, lon, line[0], line[1]) <= 2 * REWALK_ON_RIDE_METRES;
    if (current > 0 && legs.get(current - 1).trip != null && !nearStart
        && polylineMetres(trip.lines[current - 1], lat, lon) < REWALK_ON_RIDE_METRES)
      return trip;
    if (!ahead && current + 1 < legs.size() && legs.get(current + 1).trip != null
        && RigaJourneyDatabase.distance(lat, lon, toLat, toLon) > 2 * REWALK_ON_RIDE_METRES
        && polylineMetres(trip.lines[current + 1], lat, lon) < REWALK_ON_RIDE_METRES)
      return trip;
    double[] path;
    try
    {
      loadWalkingGraph(lat, lon);
      path = walk(lat, lon, toLat, toLon);
    }
    catch (RuntimeException e)
    {
      return trip;
    }
    if (path == null || path.length < 6 || path.length % 2 != 0 || !Double.isFinite(path[0]) || path[0] < 0
        || path[0] > 3 * 3600)
      return trip;
    List<RigaJourneyPlanner.Leg> changed = new java.util.ArrayList<>(legs);
    long seconds = (long) Math.ceil(path[0]) * 1000;
    long departure = walk.departure;
    long arrival = now + seconds;
    int missed = trip.missedLeg;
    if (ahead)
    {
      departure = leaveFor(legs, current, seconds, now);
      arrival = departure + seconds;
    }
    changed.set(current, new RigaJourneyPlanner.Leg(walk.from, walk.to, departure, arrival, null,
        walk.firstEvent, walk.lastEvent, (int) Math.ceil(path[1]), null));
    // Walks straight after it (a change of stop before the first ride) follow it.
    int ride = current + 1;
    long reach = arrival;
    for (; ride < legs.size() && legs.get(ride).trip == null; ++ride)
    {
      RigaJourneyPlanner.Leg next = legs.get(ride);
      long end = reach + (next.arrival - next.departure);
      changed.set(ride, new RigaJourneyPlanner.Leg(next.from, next.to, reach, end, null, next.firstEvent,
          next.lastEvent, next.walkingMetres, null));
      reach = end;
    }
    // Before the first ride nothing else re-times the walk: a ride it no longer reaches is missed here.
    if (ride < legs.size() && reach > legs.get(ride).departure && missed < 0 && !ridden(legs, current))
      missed = ride;
    double[][] lines = trip.lines.clone();
    double[] points = new double[path.length - 2];
    System.arraycopy(path, 2, points, 0, points.length);
    lines[current] = points;
    Result result = new Result(trip.data,
        new RigaJourneyPlanner.Journey(trip.journey.departure, changed, trip.journey.boardings), trip.matched,
        trip.received, trip.liveFailed, lines, trip.walking);
    result.vehicles = trip.vehicles;
    result.missedLeg = missed;
    return result;
  }

  /**
   * When to set off on the not yet begun walk {@code walk} of {@code walkMillis}: just in time for the ride after
   * it, or now when that is already too late or no ride follows.
   */
  static long leaveFor(List<RigaJourneyPlanner.Leg> legs, int walk, long walkMillis, long now)
  {
    long between = 0;
    for (int i = walk + 1; i < legs.size(); ++i)
    {
      RigaJourneyPlanner.Leg leg = legs.get(i);
      if (leg.trip != null) return Math.max(now, leg.departure - walkMillis - between);
      between += leg.arrival - leg.departure;
    }
    // No ride follows (a part on foot, maybe after a stay): it keeps its start unless that has passed.
    return Math.max(now, legs.get(walk).departure);
  }

  /** Whether a ride comes before leg {@code index}. */
  private static boolean ridden(List<RigaJourneyPlanner.Leg> legs, int index)
  {
    for (int i = 0; i < index; ++i) if (legs.get(i).trip != null) return true;
    return false;
  }

  /** The walk the rider should be on at {@code now}: the last walk already begun, unless a ride began after it. */
  static int walking(List<RigaJourneyPlanner.Leg> legs, long now)
  {
    int current = -1;
    for (int i = 0; i < legs.size(); ++i)
    {
      RigaJourneyPlanner.Leg leg = legs.get(i);
      if (leg.departure > now) break;
      current = leg.trip == null ? i : -1;
    }
    return current;
  }

  /** Metres from a point to a polyline of lat,lon pairs. */
  static double polylineMetres(double[] line, double lat, double lon)
  {
    if (line == null || line.length < 2) return Double.MAX_VALUE;
    double best = RigaJourneyDatabase.distance(lat, lon, line[0], line[1]);
    double k = Math.cos(Math.toRadians(lat)) ;
    for (int i = 2; i + 1 < line.length; i += 2)
    {
      double ax = line[i - 1] * k, ay = line[i - 2], bx = line[i + 1] * k, by = line[i];
      double px = lon * k, py = lat;
      double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
      double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
      best = Math.min(best, RigaJourneyDatabase.distance(lat, lon, ay + t * dy, (ax + t * dx) / k));
    }
    return best;
  }

  private RigaLocationRouter mRouter;
  /** See {@link RigaNextVehicle}; null when unknown. */
  String nextVehicle(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Trip trip, int board, long departure,
                     long now)
  {
    return RigaNextVehicle.forLeg(mDatabase, data, trip, board, mLastSnapshot, seen(), departure, now);
  }

  /** Repeats the last live match only to log it for the lines of the journey just shown. */
  void traceLastMatch(RigaJourneyPlanner.Timetable data)
  {
    List<RigaVehicle> snapshot = mLastSnapshot;
    if (snapshot.isEmpty() || RigaJourneyMatcher.sTrace == null) return;
    long received = 0;
    for (RigaVehicle vehicle : snapshot) received = Math.max(received, vehicle.receivedAt);
    try
    {
      RigaJourneyMatcher.match(data, snapshot, received, mDatabase::shape, () -> false);
    }
    catch (java.io.IOException | RuntimeException e)
    {
      RigaJourneyMatcher.sTrace.line("trace failed: " + e);
    }
  }

  private Map<Integer, RigaJourneyPlanner.Prediction> mLastPredictions = Collections.emptyMap();
  private List<RigaVehicle> mLastSnapshot = Collections.emptyList();
  /** Each vehicle as last seen: buses switch off while waiting at a terminus and drop out of the feed. */
  private final Map<String, RigaVehicle> mSeen = new java.util.HashMap<>();

  private synchronized void remember(List<RigaVehicle> snapshot)
  {
    mLastSnapshot = snapshot;
    for (RigaVehicle vehicle : snapshot) mSeen.put(vehicle.id, vehicle);
    long now = System.currentTimeMillis();
    mSeen.values().removeIf(vehicle -> now - vehicle.receivedAt > RigaNextVehicle.GONE);
  }

  private synchronized List<RigaVehicle> seen() { return new java.util.ArrayList<>(mSeen.values()); }
  private long mLastDeparture;
  /** Walking time for one alternative, and for all of them; past that, only routed walks are used. */
  static final long ALTERNATIVE_BUDGET = 4000;
  static final long ALTERNATIVES_BUDGET = 15000;

  /**
   * Varied journeys around {@code best} (which stays in the list), reusing the walks, live data and
   * points of the last calculateLocations(). {@code count} includes {@code best}.
   */
  List<Result> alternatives(Result best, int count, boolean routeWalks, BooleanSupplier cancelled) throws Exception
  {
    if (mRouter == null || best.journey == null) return Collections.singletonList(best);
    List<RigaJourneyPlanner.Journey> journeys = new java.util.ArrayList<>(RigaJourneyAlternatives.choose(
        best.journey, mLastDeparture, count, planner(routeWalks ? ALTERNATIVE_BUDGET : 0, cancelled)));
    if (best.received > 0 && !best.liveFailed)
    {
      // Each option's boarding stop is asked about once its option is known: its cards then show the live times.
      long now = System.currentTimeMillis();
      List<String> stops = new java.util.ArrayList<>();
      for (RigaJourneyPlanner.Journey journey : journeys)
        if (journey != best.journey) boardingStops(best.data, journey, stops, now);
      Map<Integer, RigaJourneyPlanner.Prediction> listed = withStops(best.data, mLastPredictions, stops, cancelled);
      if (listed.size() > mLastPredictions.size())
      {
        mLastPredictions = listed;
        for (int i = 0; i < journeys.size(); ++i)
          if (journeys.get(i) != best.journey)
            journeys.set(i, RigaJourneyRetimer.retime(best.data.trips, journeys.get(i), listed, now).journey);
      }
    }
    return results(best, journeys, cancelled);
  }

  /**
   * Journeys arriving by {@code arriveBy}, the one leaving last first, between the points of the last
   * calculateLocations() (which planned from the start of the search). Empty when none arrives in time.
   */
  List<Result> arriveBy(Result first, long arriveBy, int count, boolean routeWalks, BooleanSupplier cancelled)
      throws Exception
  {
    if (mRouter == null || first.journey == null) return Collections.emptyList();
    List<RigaJourneyPlanner.Journey> journeys = RigaJourneyAlternatives.chooseArriveBy(mLastDeparture, arriveBy,
        count, planner(routeWalks ? ALTERNATIVE_BUDGET : 0, cancelled));
    return results(first, journeys, cancelled);
  }

  private List<Result> results(Result best, List<RigaJourneyPlanner.Journey> journeys, BooleanSupplier cancelled)
  {
    List<Result> results = new java.util.ArrayList<>();
    for (RigaJourneyPlanner.Journey journey : journeys)
    {
      RigaJourneyPlanner.checkCancelled(cancelled);
      // The same rides boarded elsewhere may have taken the place of best: only best itself is reused.
      if (journey == best.journey) { results.add(best); continue; }
      double[][] lines = geometry(best.data, journey, mRouterPaths, true, cancelled)[0];
      if (lines.length != journey.legs.size()) continue; // Never offer a journey that cannot be drawn.
      boolean[] walking = new boolean[lines.length];
      for (int i = 0; i < lines.length; ++i) walking[i] = journey.legs.get(i).trip == null;
      Result result = new Result(best.data, journey, best.matched, best.received, best.liveFailed, lines, walking);
      result.vehicles = RigaJourneyVehicles.select(journey, best.data.stops, mLastSnapshot, System.currentTimeMillis());
      results.add(result);
    }
    return results;
  }

  private Map<Long, double[]> mRouterPaths = Collections.emptyMap();

  /**
   * The started journey {@code trip} with the same vehicles, moved to the latest live estimates. Without
   * live data (or when it fails) the times stay as they were.
   */
  /** {@code journey} re-timed from the live data of the last calculation (no new download). */
  Result retime(Result journey)
  {
    long now = System.currentTimeMillis();
    RigaJourneyRetimer.Retimed retimed = RigaJourneyRetimer.retime(journey.data.trips, journey.journey,
        mLastPredictions, now);
    Result result = new Result(journey.data, retimed.journey, mLastPredictions.size(), journey.received,
        journey.liveFailed, journey.lines, journey.walking);
    result.missedLeg = retimed.missedLeg;
    result.vehicles = RigaJourneyVehicles.select(retimed.journey, journey.data.stops, mLastSnapshot, now);
    return result;
  }

  Result retime(Result trip, boolean live, BooleanSupplier cancelled) throws Exception
  {
    Map<Integer, RigaJourneyPlanner.Prediction> predictions = Collections.emptyMap();
    List<RigaVehicle> snapshot = Collections.emptyList();
    long received = trip.received;
    boolean failed = false;
    if (live)
    {
      try
      {
        snapshot = mVehicles.fetch();
        RigaJourneyPlanner.checkCancelled(cancelled);
        received = System.currentTimeMillis();
        predictions = RigaJourneyMatcher.match(trip.data, snapshot, received, mDatabase::shape, cancelled);
        predictions = withStops(trip.data, predictions,
            boardingStops(trip.data, trip.journey, received), cancelled);
      }
      catch (java.util.concurrent.CancellationException e)
      {
        throw e;
      }
      catch (Exception e)
      {
        RigaJourneyPlanner.checkCancelled(cancelled);
        failed = true;
      }
    }
    long now = System.currentTimeMillis();
    RigaJourneyRetimer.Retimed retimed = RigaJourneyRetimer.retime(trip.data.trips, trip.journey, predictions, now);
    Result result = new Result(trip.data, retimed.journey, predictions.size(), received, failed, trip.lines,
        trip.walking);
    result.missedLeg = retimed.missedLeg;
    result.vehicles = RigaJourneyVehicles.select(retimed.journey, trip.data.stops, snapshot, now);
    return result;
  }

  /** The parts of a multi-stop trip re-timed from one live download (or from the last one, without live). */
  List<Result> retime(List<Result> parts, boolean live, BooleanSupplier cancelled) throws Exception
  {
    if (parts.size() == 1) return Collections.singletonList(live ? retime(parts.get(0), true, cancelled)
                                                                 : retime(parts.get(0)));
    Map<Integer, RigaJourneyPlanner.Prediction> predictions = mLastPredictions;
    List<RigaVehicle> snapshot = mLastSnapshot;
    long received = 0;
    boolean failed = false;
    if (live)
    {
      try
      {
        snapshot = mVehicles.fetch();
        RigaJourneyPlanner.checkCancelled(cancelled);
        received = System.currentTimeMillis();
        // Every part shares the timetable's trips; the appended start and destination points differ only.
        predictions = RigaJourneyMatcher.match(parts.get(0).data, snapshot, received, mDatabase::shape, cancelled);
        List<String> boarding = new java.util.ArrayList<>();
        for (Result part : parts) boardingStops(part.data, part.journey, boarding, received);
        predictions = withStops(parts.get(0).data, predictions, boarding, cancelled);
        mLastPredictions = predictions;
        remember(snapshot);
      }
      catch (java.util.concurrent.CancellationException e)
      {
        throw e;
      }
      catch (Exception e)
      {
        RigaJourneyPlanner.checkCancelled(cancelled);
        failed = true;
      }
    }
    long now = System.currentTimeMillis();
    List<Result> retimed = new java.util.ArrayList<>();
    for (Result part : parts)
    {
      RigaJourneyRetimer.Retimed times = RigaJourneyRetimer.retime(part.data.trips, part.journey, predictions, now);
      Result result = new Result(part.data, times.journey, predictions.size(), received > 0 ? received : part.received,
          failed, part.lines, part.walking);
      result.missedLeg = times.missedLeg;
      result.vehicles = RigaJourneyVehicles.select(times.journey, part.data.stops, snapshot, now);
      retimed.add(result);
    }
    return retimed;
  }

  private RigaJourneyAlternatives.Planner planner(long each, BooleanSupplier cancelled)
  {
    long end = System.nanoTime() / 1000000 + (each == 0 ? 0 : ALTERNATIVES_BUDGET);
    return (departure, options) -> {
      long started = System.nanoTime();
      int misses = walkCacheMisses;
      long budget = Math.max(0, Math.min(each, end - started / 1000000));
      RigaLocationRouter.Result result = mRouter.alternative(departure, mLastPredictions, options,
          budget, cancelled);
      app.organicmaps.sdk.util.log.Logger.d("RigaJourneyService", "Alternative "
          + options + " from " + departure + ": "
          + (result == null || result.journey == null ? "none" : "found") + " in "
          + (System.nanoTime() - started) / 1000000 + " ms, walks routed " + (walkCacheMisses - misses));
      return result == null ? null : result.journey;
    };
  }

  /** lines[i] per leg, or no lines at all when any leg cannot be drawn. */
  private double[][][] geometry(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Journey journey,
                                Map<Long, double[]> paths, boolean routedWalks, BooleanSupplier cancelled)
  {
    double[][] lines = new double[0][];
    if (journey != null && journey.legs.size() <= 16)
    {
      try
      {
        lines = new double[journey.legs.size()][];
        int total = 0;
        for (int i = 0; i < lines.length; ++i)
        {
          RigaJourneyPlanner.checkCancelled(cancelled);
          RigaJourneyPlanner.Leg leg = journey.legs.get(i);
          List<RigaJourneyGeometry.Point> points;
          if (routedWalks && leg.trip == null)
          {
            double[] path = paths.get(RigaLocationRouter.key(leg.from, leg.to));
            if (path == null) throw new java.io.IOException("Missing routed walking geometry");
            points = new java.util.ArrayList<>();
            for (int p = 2; p < path.length; p += 2)
              points.add(new RigaJourneyGeometry.Point(path[p], path[p + 1]));
          }
          else points = mDatabase.legPoints(data, leg);
          total += points.size();
          if (total > 100000)
            throw new java.io.IOException("Journey geometry too large");
          lines[i] = new double[points.size() * 2];
          for (int p = 0; p < points.size(); ++p)
          {
            lines[i][p * 2] = points.get(p).lat;
            lines[i][p * 2 + 1] = points.get(p).lon;
          }
        }
      }
      catch (java.io.IOException e)
      {
        // A geometry problem must not discard a usable textual itinerary.
        lines = new double[0][];
      }
    }
    return new double[][][] {lines};
  }
}
