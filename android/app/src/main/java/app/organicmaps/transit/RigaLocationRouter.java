package app.organicmaps.transit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Coordinate routing with street-routed access, exit and selected platform transfers. */
final class RigaLocationRouter
{
  interface Walker
  {
    double[] route(double fromLat, double fromLon, double toLat, double toLon);
    /** A walk already routed earlier, or null; never routes. */
    default double[] known(double fromLat, double fromLon, double toLat, double toLon) { return null; }
  }
  static final class Result
  {
    final RigaJourneyPlanner.Timetable data;
    final RigaJourneyPlanner.Journey journey;
    final Map<Long, double[]> paths;
    Result(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Journey journey, Map<Long, double[]> paths)
    { this.data = data; this.journey = journey; this.paths = paths; }
  }
  private final Walker mWalker;
  private final java.util.function.LongSupplier mClock;
  RigaLocationRouter(Walker walker) { this(walker, () -> 0); }
  private final java.util.function.LongSupplier mNanoClock;
  private static final class RefinementDeadline extends IllegalStateException
  { RefinementDeadline() { super("Walking route planning timed out"); } }
  RigaLocationRouter(Walker walker, java.util.function.LongSupplier clock)
  { this(walker, clock, System::nanoTime); }
  RigaLocationRouter(Walker walker, java.util.function.LongSupplier clock, java.util.function.LongSupplier nanoClock)
  { mWalker = walker; mClock = clock; mNanoClock = nanoClock; }
  // Native pedestrian ETAs use at most 5 km/h; 1.5 m/s keeps straight-line estimates optimistic.
  static final double OPTIMISTIC_SPEED = 1.5;
  static int optimisticSeconds(double metres) { return Math.max(1, (int) Math.ceil(metres / OPTIMISTIC_SPEED)); }
  /** No routed walk between the two points can take less than this. */
  static int optimisticSeconds(double fromLat, double fromLon, double toLat, double toLon)
  {
    return optimisticSeconds(distance(new RigaJourneyPlanner.Stop("", "", "", fromLat, fromLon),
                                      new RigaJourneyPlanner.Stop("", "", "", toLat, toLon)));
  }
  /**
   * A walk's key in the path maps. Packed (from, to) multiplied by an odd constant: still one key per walk, but
   * spread over the hash table ({@link Long#hashCode()} of the packed pair is just {@code from ^ to}).
   */
  static long key(int from, int to) { return (((long) from << 32) | (to & 0xffffffffL)) * 0x9E3779B97F4A7C15L; }

  // Kept after plan() so alternatives reuse the walks already routed for the fastest journey.
  private RigaJourneyPlanner.Timetable mSource;
  private List<RigaJourneyPlanner.Stop> mStops;
  private List<RigaJourneyPlanner.Footpath> mWalks;
  private Map<Long, double[]> mPaths;
  private int mOrigin;
  private int mDestination;

  Result plan(RigaJourneyPlanner.Timetable source, double fromLat, double fromLon, double toLat, double toLon,
              long departure, Map<Integer, RigaJourneyPlanner.Prediction> predictions, BooleanSupplier cancelled)
  {
    final BooleanSupplier originalCancellation = cancelled;
    final long deadline = mNanoClock.getAsLong() + 20000000000L;
    cancelled = () -> {
      if (originalCancellation.getAsBoolean()) return true;
      if (mNanoClock.getAsLong() > deadline) throw new RefinementDeadline();
      return false;
    };
    mSource = source;
    mStops = new ArrayList<>(source.stops);
    mOrigin = mStops.size();
    mDestination = mOrigin + 1;
    mStops.add(new RigaJourneyPlanner.Stop("riga:map-origin", "Start", "", fromLat, fromLon));
    mStops.add(new RigaJourneyPlanner.Stop("riga:map-destination", "Destination", "", toLat, toLon));
    mPaths = new HashMap<>();
    mWalks = new ArrayList<>(source.footpaths);
    nearbyTransfers(source.stops, mWalks, cancelled);
    connect(mStops, mOrigin, source.stops.size(), true, mWalks, mPaths, cancelled);
    connect(mStops, mDestination, source.stops.size(), false, mWalks, mPaths, cancelled);
    seedKnownWalks(source.stops.size());
    try
    {
      return refine(departure, predictions, cancelled, RigaJourneyPlanner.Options.NONE);
    }
    catch (RefinementDeadline exhausted)
    {
      // Optimistic edges are search bounds, never a usable walking connection. At the
      // refinement limit, find a feasible journey using only completed native walks.
      RigaJourneyPlanner.checkCancelled(originalCancellation);
      List<RigaJourneyPlanner.Footpath> verified = new ArrayList<>();
      for (RigaJourneyPlanner.Footpath edge : mWalks)
        if (mPaths.containsKey(key(edge.from,edge.to))) verified.add(edge);
      RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(mStops,source.trips,verified);
      departure = Math.max(departure,mClock.getAsLong());
      RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(data,mStops.get(mOrigin).id,
          mStops.get(mDestination).id,departure,fresh(predictions,departure),originalCancellation,
          RigaJourneyPlanner.DEFAULT_LIMITS,true);
      if (journey == null) throw exhausted;
      return new Result(data,journey,mPaths);
    }
  }

  /**
   * Another journey between the points of the last plan(), with its own walking budget (0 = only walks
   * routed so far). Every walk of a returned journey is routed: an alternative is never half-verified.
   */
  Result alternative(long departure, Map<Integer, RigaJourneyPlanner.Prediction> predictions,
                     RigaJourneyPlanner.Options options, long budgetMillis, BooleanSupplier cancelled)
  {
    if (mStops == null) throw new IllegalStateException("No planned journey");
    final BooleanSupplier originalCancellation = cancelled;
    final long deadline = mNanoClock.getAsLong() + budgetMillis * 1000000L;
    try
    {
      return refine(departure, predictions, () -> {
        if (originalCancellation.getAsBoolean()) return true;
        if (mNanoClock.getAsLong() > deadline) throw new RefinementDeadline();
        return false;
      }, options);
    }
    catch (RefinementDeadline exhausted)
    {
      // Out of walking time: plan with the walks already routed only. The result is a real,
      // fully verified journey, possibly not the very best one for these options.
      RigaJourneyPlanner.checkCancelled(originalCancellation);
      List<RigaJourneyPlanner.Footpath> verified = new ArrayList<>();
      for (RigaJourneyPlanner.Footpath edge : mWalks)
        if (mPaths.containsKey(key(edge.from, edge.to))) verified.add(edge);
      RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(mStops, mSource.trips, verified);
      departure = Math.max(departure, mClock.getAsLong());
      RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(data, mStops.get(mOrigin).id,
          mStops.get(mDestination).id, departure, fresh(predictions, departure), originalCancellation,
          RigaJourneyPlanner.DEFAULT_LIMITS, true, options);
      return journey == null ? null : new Result(data, journey, mPaths);
    }
  }

  private Map<Integer, RigaJourneyPlanner.Prediction> fresh(Map<Integer, RigaJourneyPlanner.Prediction> predictions,
                                                            long departure)
  {
    Map<Integer, RigaJourneyPlanner.Prediction> fresh = new HashMap<>();
    for (Map.Entry<Integer, RigaJourneyPlanner.Prediction> entry : predictions.entrySet())
      if (departure - entry.getValue().receivedAt <= 120000) fresh.put(entry.getKey(), entry.getValue());
    return fresh;
  }

  // A provisional platform edge is used only to discover a candidate. Before returning,
  // every selected walk is replaced with its real pedestrian cost and the planner runs again.
  private Result refine(long departure, Map<Integer, RigaJourneyPlanner.Prediction> predictions,
                        BooleanSupplier cancelled, RigaJourneyPlanner.Options options)
  {
    final int sourceStops = mSource.stops.size();
    for (int iteration = 0; iteration < 64; ++iteration)
    {
      RigaJourneyPlanner.checkCancelled(cancelled);
      departure = Math.max(departure, mClock.getAsLong());
      RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(mStops, mSource.trips, mWalks);
      RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(data, mStops.get(mOrigin).id,
          mStops.get(mDestination).id, departure, fresh(predictions, departure), cancelled,
          RigaJourneyPlanner.DEFAULT_LIMITS, true, options);
      if (journey == null) return new Result(data, null, mPaths);
      boolean changed = false;
      List<int[]> endpoints = new ArrayList<>();
      for (RigaJourneyPlanner.Leg leg : journey.legs)
      {
        if (leg.trip != null || mPaths.containsKey(key(leg.from, leg.to))) continue;
        RigaJourneyPlanner.checkCancelled(cancelled);
        double[] path = verify(leg.from, leg.to, sourceStops);
        changed = true;
        if (path != null && (leg.from == mOrigin || leg.to == mDestination))
          endpoints.add(new int[] {leg.to == mDestination ? -1 : leg.from, Math.max(1, (int) Math.ceil(path[0]))});
      }
      if (!changed) return new Result(data, journey, mPaths);
      // The search with the guessed walks is a lower bound on the arrival. When the same rides still work with
      // the walks now routed and arrive in the same minute, no other journey can do better: searching again
      // would only try other walks to the same vehicles (at night, with a long wait, dozens of them).
      RigaJourneyPlanner.Journey routed = routed(journey, options);
      if (routed != null
          && RigaJourneyAlternatives.minute(routed.arrival) <= RigaJourneyAlternatives.minute(journey.arrival))
        return new Result(new RigaJourneyPlanner.Timetable(mStops, mSource.trips, mWalks), routed, mPaths);
      // A walk from the start or to the destination longer than guessed lets each other one guessed shorter
      // win the next search, one search each: route those (at most 16) together now. Not done for changes on
      // foot: there are many more of them, and routing them all costs more than the searches it saves.
      for (int[] endpoint : endpoints) verifyShorter(endpoint[0], endpoint[1], sourceStops, cancelled);
    }
    throw new RefinementDeadline();
  }

  /** Routes the walk {@code from}→{@code to}, replacing its guess; null (and the walk dropped) when it has no route. */
  private double[] verify(int from, int to, int sourceStops)
  {
    double[] path = walk(mStops.get(from), mStops.get(to));
    mWalks.removeIf(edge -> edge.from == from && edge.to == to);
    if (!valid(path, from >= sourceStops || to >= sourceStops ? 1800 : 1200)) return null;
    mPaths.put(key(from, to), path);
    mWalks.add(edge(from, to, path));
    return path;
  }

  /**
   * Routes the walks still guessed from stop {@code from} (the start) or, for -1, to the destination whose guess
   * is under {@code seconds}.
   */
  private void verifyShorter(int from, int seconds, int sourceStops, BooleanSupplier cancelled)
  {
    List<RigaJourneyPlanner.Footpath> shorter = new ArrayList<>();
    for (RigaJourneyPlanner.Footpath edge : mWalks)
      if ((from < 0 ? edge.to == mDestination : edge.from == from) && edge.seconds < seconds
          && !mPaths.containsKey(key(edge.from, edge.to)))
        shorter.add(edge);
    for (RigaJourneyPlanner.Footpath edge : shorter)
    {
      RigaJourneyPlanner.checkCancelled(cancelled);
      verify(edge.from, edge.to, sourceStops);
    }
  }

  /**
   * {@code journey} with every walk at its routed time and the same rides, or null when a walk is not routed, is
   * over the walking limits of the search or makes the rider (leaving now at the earliest) miss a ride.
   */
  private RigaJourneyPlanner.Journey routed(RigaJourneyPlanner.Journey journey, RigaJourneyPlanner.Options options)
  {
    List<RigaJourneyPlanner.Leg> legs = new ArrayList<>();
    // Routing the walks takes time: the rider sets off no earlier than now.
    final long start = Math.max(journey.departure, mClock.getAsLong());
    long time = start;
    int boardings = 0;
    int onFoot = 0; // Metres of the current stretch of walks, as the search limits it.
    for (RigaJourneyPlanner.Leg leg : journey.legs)
    {
      if (leg.trip == null)
      {
        double[] path = mPaths.get(key(leg.from, leg.to));
        if (path == null) return null;
        RigaJourneyPlanner.Footpath edge = edge(leg.from, leg.to, path);
        onFoot += edge.metres;
        if (edge.metres > options.maxWalkMetres || onFoot > RigaJourneyPlanner.MAX_WALK_STRETCH) return null;
        legs.add(new RigaJourneyPlanner.Leg(leg.from, leg.to, time, time + edge.seconds * 1000L, null, -1, -1,
                                            edge.metres, null));
        time += edge.seconds * 1000L;
        continue;
      }
      long buffer = boardings == 0 ? 0 : RigaJourneyPlanner.TRANSFER_TIME;
      if (time + buffer > leg.departure) return null;
      legs.add(leg);
      time = leg.arrival;
      onFoot = 0;
      ++boardings;
    }
    return new RigaJourneyPlanner.Journey(start, legs, boardings);
  }

  /**
   * Replaces optimistic edges with walks routed in earlier searches before the first plan, so the
   * refinement loop does not have to rediscover them one search round at a time.
   */
  private void seedKnownWalks(int sourceStops)
  {
    for (int i = 0; i < mWalks.size(); ++i)
    {
      RigaJourneyPlanner.Footpath edge = mWalks.get(i);
      long key = key(edge.from, edge.to);
      if (mPaths.containsKey(key)) continue;
      RigaJourneyPlanner.Stop from = mStops.get(edge.from), to = mStops.get(edge.to);
      double[] path = mWalker.known(from.lat, from.lon, to.lat, to.lon);
      if (path == null || !valid(path, edge.from >= sourceStops || edge.to >= sourceStops ? 1800 : 1200)) continue;
      mPaths.put(key, path);
      mWalks.set(i, edge(edge.from, edge.to, path));
    }
  }

  private static void nearbyTransfers(List<RigaJourneyPlanner.Stop> stops,
      List<RigaJourneyPlanner.Footpath> walks, BooleanSupplier cancelled)
  {
    java.util.Set<Long> existing = new java.util.HashSet<>();
    for (RigaJourneyPlanner.Footpath edge : walks) existing.add(key(edge.from, edge.to));
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < stops.size(); ++i) order.add(i);
    order.sort(Comparator.comparingDouble(i -> stops.get(i).lat));
    for (int a = 0; a < order.size(); ++a)
    {
      RigaJourneyPlanner.checkCancelled(cancelled);
      int from = order.get(a);
      for (int b = a + 1; b < order.size(); ++b)
      {
        int to = order.get(b);
        if (stops.get(to).lat - stops.get(from).lat > 0.0036) break;
        double metres = distance(stops.get(from), stops.get(to));
        if (metres > 400) continue;
        for (int direction = 0; direction < 2; ++direction)
        {
          int u = direction == 0 ? from : to;
          int v = direction == 0 ? to : from;
          if (!existing.add(key(u,v))) continue;
          if (walks.size() >= RigaJourneyPlanner.DEFAULT_LIMITS.footpaths)
            throw new IllegalStateException("Too many walking transfer candidates");
          walks.add(new RigaJourneyPlanner.Footpath(u,v,optimisticSeconds(metres),(int)Math.ceil(metres)));
        }
      }
    }
  }

  private void connect(List<RigaJourneyPlanner.Stop> stops, int endpoint, int count, boolean boarding,
                       List<RigaJourneyPlanner.Footpath> walks, Map<Long, double[]> paths, BooleanSupplier cancelled)
  {
    RigaJourneyPlanner.Stop point = stops.get(endpoint);
    List<Integer> candidates = new ArrayList<>();
    for (int i = 0; i < count; ++i)
      if (distance(point, stops.get(i)) <= 1200) candidates.add(i);
    candidates.sort(Comparator.comparingDouble(i -> distance(point, stops.get(i))));
    for (int i : candidates.subList(0, Math.min(16, candidates.size())))
    {
      RigaJourneyPlanner.checkCancelled(cancelled);
      int from = boarding ? endpoint : i;
      int to = boarding ? i : endpoint;
      int metres = (int) Math.ceil(distance(stops.get(from), stops.get(to)));
      // An optimistic bound only. No itinerary is returned until selected edges are routed.
      walks.add(new RigaJourneyPlanner.Footpath(from,to,optimisticSeconds(metres),metres));
    }
  }
  private double[] walk(RigaJourneyPlanner.Stop from, RigaJourneyPlanner.Stop to)
  { return mWalker.route(from.lat, from.lon, to.lat, to.lon); }
  private static double distance(RigaJourneyPlanner.Stop a, RigaJourneyPlanner.Stop b)
  {
    double y = Math.toRadians(b.lat - a.lat);
    double x = Math.toRadians(b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2));
    return Math.hypot(x, y) * 6371000;
  }
  private static boolean valid(double[] path, int limit)
  {
    if (path == null || path.length < 6 || path.length > 40002 || path.length % 2 != 0
        || !Double.isFinite(path[0]) || !Double.isFinite(path[1])
        || path[0] < 0 || path[0] > 1800 || path[1] < 0 || path[1] > limit) return false;
    for (int i = 2; i < path.length; i += 2)
      if (!TransitArea.plausible(path[i], path[i + 1])) return false;
    return true;
  }
  private static RigaJourneyPlanner.Footpath edge(int from, int to, double[] path)
  { return new RigaJourneyPlanner.Footpath(from, to, Math.max(1, (int) Math.ceil(path[0])), (int) Math.ceil(path[1])); }
}
