package app.organicmaps.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Earliest-arrival connection scan. The timetable and all calculations work without a network. */
final class RigaJourneyPlanner
{
  static final int MAX_BOARDINGS = 4;
  static final long WINDOW = 24 * 3600000L;
  static final long TRANSFER_TIME = 120000L;
  /** Metres of consecutive walks a journey with rides may have (to, between or from them). */
  static final int MAX_WALK_STRETCH = 1800;

  static final class Limits
  {
    final int stops;
    final int trips;
    final int connections;
    final int footpaths;
    Limits(int stops, int trips, int connections, int footpaths)
    {
      this.stops = stops;
      this.trips = trips;
      this.connections = connections;
      this.footpaths = footpaths;
    }
  }
  // Sized for the merged Latvian feeds (about 13k stops, 20k trips, 420k stop times) with room to grow.
  static final Limits DEFAULT_LIMITS = new Limits(40000, 100000, 1500000, 200000);

  static final class Stop
  {
    final String id;
    final String name;
    final String parent;
    final double lat;
    final double lon;
    Stop(String id, String name, String parent, double lat, double lon)
    {
      this.id = id;
      this.name = name;
      this.parent = parent;
      this.lat = lat;
      this.lon = lon;
    }
  }

  static final class Event
  {
    final int stop;
    final int sequence;
    final long arrival;
    final long departure;
    final boolean pickup;
    final boolean dropOff;
    Event(int stop, int sequence, long arrival, long departure, boolean pickup, boolean dropOff)
    {
      this.stop = stop;
      this.sequence = sequence;
      this.arrival = arrival;
      this.departure = departure;
      this.pickup = pickup;
      this.dropOff = dropOff;
    }
  }

  static final class Trip
  {
    final String id;
    final String routeId;
    final String routeName;
    final String headsign;
    final int type;
    final String shapeId;
    final long serviceOrigin;
    final List<Event> events;
    Trip(String id, String routeId, String routeName, String headsign, int type, String shapeId,
         long serviceOrigin, List<Event> events)
    {
      this.id = id;
      this.routeId = routeId;
      this.routeName = routeName;
      this.headsign = headsign;
      this.type = type;
      this.shapeId = shapeId;
      this.serviceOrigin = serviceOrigin;
      this.events = events;
    }
  }

  static final class Footpath
  {
    final int from;
    final int to;
    final int seconds;
    final int metres;
    Footpath(int from, int to, int seconds, int metres)
    {
      this.from = from;
      this.to = to;
      this.seconds = seconds;
      this.metres = metres;
    }
  }

  static final class Timetable
  {
    final List<Stop> stops;
    final List<Trip> trips;
    final List<Footpath> footpaths;
    Timetable(List<Stop> stops, List<Trip> trips, List<Footpath> footpaths)
    {
      this.stops = stops;
      this.trips = trips;
      this.footpaths = footpaths;
    }
  }

  /** Restrictions used to find alternatives to the fastest journey. */
  static final class Options
  {
    static final Options NONE = new Options(Collections.emptySet(), MAX_BOARDINGS);
    final java.util.Set<String> excludedRoutes;
    final int maxBoardings;
    /** Longest single walk allowed (access, transfer or exit), in metres. */
    final int maxWalkMetres;
    Options(java.util.Set<String> excludedRoutes, int maxBoardings)
    {
      this(excludedRoutes, maxBoardings, Integer.MAX_VALUE);
    }
    Options(java.util.Set<String> excludedRoutes, int maxBoardings, int maxWalkMetres)
    {
      this.excludedRoutes = excludedRoutes;
      this.maxBoardings = Math.max(1, Math.min(MAX_BOARDINGS, maxBoardings));
      this.maxWalkMetres = maxWalkMetres;
    }
    @Override public String toString()
    {
      return excludedRoutes + " max " + maxBoardings + (maxWalkMetres == Integer.MAX_VALUE ? "" : " walk " + maxWalkMetres);
    }
  }

  /** A uniquely matched, time-bounded provider estimate for one service-day trip instance. */
  static final class Prediction
  {
    final int delaySeconds;
    final int firstBoardableEvent;
    final String vehicleId;
    /** {@link RigaVehicle#key()} of the matched record. */
    final String vehicleKey;
    final long receivedAt;
    Prediction(int delaySeconds, int firstBoardableEvent, String vehicleId, long receivedAt)
    {
      this(delaySeconds, firstBoardableEvent, vehicleId, vehicleId, receivedAt);
    }
    Prediction(int delaySeconds, int firstBoardableEvent, String vehicleId, String vehicleKey, long receivedAt)
    {
      this.delaySeconds = delaySeconds;
      this.firstBoardableEvent = firstBoardableEvent;
      this.vehicleId = vehicleId;
      this.vehicleKey = vehicleKey;
      this.receivedAt = receivedAt;
    }
    long delayMillis() { return delaySeconds * 1000L; }

    boolean matches(RigaVehicle vehicle)
    {
      return vehicleKey.equals(vehicle.key()) || vehicleKey.equals(vehicle.id);
    }
  }

  static final class Leg
  {
    final int from;
    final int to;
    final long departure;
    final long arrival;
    final Trip trip; // null for a platform transfer on foot.
    final int firstEvent;
    final int lastEvent;
    final int walkingMetres;
    final Prediction prediction;
    int stopCount() { return trip == null ? 0 : Math.max(0,lastEvent-firstEvent); }
    Leg(int from, int to, long departure, long arrival, Trip trip, int firstEvent, int lastEvent,
        int walkingMetres, Prediction prediction)
    {
      this.from = from;
      this.to = to;
      this.departure = departure;
      this.arrival = arrival;
      this.trip = trip;
      this.firstEvent = firstEvent;
      this.lastEvent = lastEvent;
      this.walkingMetres = walkingMetres;
      this.prediction = prediction;
    }
  }

  static final class Journey
  {
    final long departure;
    final long arrival;
    final List<Leg> legs;
    final int boardings;
    Journey(long departure, Path path)
    {
      this.departure = departure;
      arrival = path.time;
      boardings = path.boardings;
      List<Leg> reversed = new ArrayList<>();
      for (Path at = path; at != null; at = at.previous)
        if (at.leg != null)
          reversed.add(at.leg);
      Collections.reverse(reversed);
      legs = Collections.unmodifiableList(reversed);
    }
    /** The same legs with new times, e.g. re-timed from live data during a trip. */
    Journey(long departure, List<Leg> legs, int boardings)
    {
      this.departure = departure;
      this.legs = Collections.unmodifiableList(new ArrayList<>(legs));
      this.arrival = legs.isEmpty() ? departure : legs.get(legs.size() - 1).arrival;
      this.boardings = boardings;
    }
  }

  /** Cost from a real pedestrian route to/from a timetable stop, supplied by the map router. */
  static final class Access
  {
    final String stopId;
    final int seconds;
    final int metres;
    Access(String stopId, int seconds, int metres)
    {
      this.stopId = stopId;
      this.seconds = seconds;
      this.metres = metres;
    }
  }

  /** Coordinate endpoints become virtual stops; candidate walking costs participate in route choice. */
  static Journey planLocations(Timetable data, double fromLat, double fromLon, double toLat, double toLon,
                               List<Access> access, List<Access> egress, long departure,
                               Map<Integer, Prediction> predictions, BooleanSupplier cancelled)
  {
    checkCancelled(cancelled);
    if (!Double.isFinite(fromLat) || !Double.isFinite(fromLon) || !Double.isFinite(toLat) || !Double.isFinite(toLon)
        || Math.abs(fromLat) > 90 || Math.abs(toLat) > 90 || Math.abs(fromLon) > 180 || Math.abs(toLon) > 180)
      throw new IllegalArgumentException("Invalid journey endpoint");
    if (access.size() > 32 || egress.size() > 32)
      throw new IllegalArgumentException("Too many walking candidates");
    List<Stop> stops = new ArrayList<>(data.stops);
    Map<String, Integer> ids = new HashMap<>();
    for (int i = 0; i < stops.size(); ++i)
      ids.put(stops.get(i).id, i);
    String originId = "riga:map-origin";
    String destinationId = "riga:map-destination";
    if (ids.containsKey(originId) || ids.containsKey(destinationId))
      throw new IllegalArgumentException("Reserved journey endpoint identifier");
    int origin = stops.size();
    int destination = origin + 1;
    stops.add(new Stop(originId, "", "", fromLat, fromLon));
    stops.add(new Stop(destinationId, "", "", toLat, toLon));
    List<Footpath> walks = new ArrayList<>(data.footpaths);
    addAccess(walks, access, ids, origin, true);
    addAccess(walks, egress, ids, destination, false);
    return plan(new Timetable(stops, data.trips, walks), originId, destinationId, departure, predictions, cancelled);
  }

  private static void addAccess(List<Footpath> walks, List<Access> candidates, Map<String, Integer> ids,
                                int endpoint, boolean boarding)
  {
    for (Access candidate : candidates)
    {
      Integer stop = ids.get(candidate.stopId);
      if (stop == null || candidate.seconds < 0 || candidate.metres < 0 || candidate.seconds > 3600
          || candidate.metres > 5000)
        throw new IllegalArgumentException("Invalid walking connection");
      // Zero-length paths (an endpoint exactly on the platform) still need a positive graph edge.
      walks.add(new Footpath(boarding ? endpoint : stop, boarding ? stop : endpoint,
                             Math.max(1, candidate.seconds), candidate.metres));
    }
  }

  private static final class Path
  {
    final int stop;
    final long time;
    final int boardings;
    final long walked;
    final Path previous;
    final Leg leg;
    /**
     * The lines ridden to here, one per boarding ({@link ConnectionIndex#route}; -1 past the last), kept in the label
     * itself: checking them is on the search's hottest path, where walking the chain or another array costs most.
     */
    final int route0, route1, route2, route3;
    /** Metres of the walks straight before this point, since the last ride or the start. */
    final long stretch;
    /** The start. */
    Path(int stop, long time)
    {
      this(stop, time, 0, 0, null, null, -1, -1, -1, -1, 0);
    }
    /** A walk from {@code previous}. */
    Path(int stop, long time, long walked, Path previous, Leg leg, long stretch)
    {
      this(stop, time, previous.boardings, walked, previous, leg, previous.route0, previous.route1, previous.route2,
           previous.route3, stretch);
    }
    /** A ride on line {@code route} after {@code previous}. */
    Path(int stop, long time, Path previous, Leg leg, int route)
    {
      this(stop, time, previous.boardings + 1, previous.walked, previous, leg,
           previous.boardings == 0 ? route : previous.route0, previous.boardings == 1 ? route : previous.route1,
           previous.boardings == 2 ? route : previous.route2, previous.boardings == 3 ? route : previous.route3, 0);
    }
    private Path(int stop, long time, int boardings, long walked, Path previous, Leg leg, int route0, int route1,
                 int route2, int route3, long stretch)
    {
      this.stop = stop;
      this.time = time;
      this.boardings = boardings;
      this.walked = walked;
      this.previous = previous;
      this.leg = leg;
      this.route0 = route0;
      this.route1 = route1;
      this.route2 = route2;
      this.route3 = route3;
      this.stretch = stretch;
    }
  }

  private static final class Connection
  {
    final int trip;
    final int event;
    final long departure;
    final long arrival;
    final Prediction prediction;
    Connection(int trip, int event, long departure, long arrival, Prediction prediction)
    {
      this.trip = trip;
      this.event = event;
      this.departure = departure;
      this.arrival = arrival;
      this.prediction = prediction;
    }
  }

  private static final class Boarding
  {
    final Path previous;
    final Connection connection;
    Boarding(Path previous, Connection connection)
    {
      this.previous = previous;
      this.connection = connection;
    }
  }

  private static boolean better(Path next, Path old)
  {
    return better(next.time, next.boardings, next.walked, old);
  }

  /** {@link #better(Path, Path)} for a label not yet made: most candidates lose, so none is allocated for them. */
  private static boolean better(long time, int boardings, long walked, Path old)
  {
    return old == null || time < old.time || (time == old.time
        && (boardings < old.boardings || (boardings == old.boardings && walked < old.walked)));
  }

  /**
   * Whether {@code path} already rode line {@code routeId}. Getting off a line and back on it later (across
   * the street, or the next vehicle after a short-turn one) is never offered: staying aboard or waiting
   * for the right vehicle is what riders do, and it reads as a mistake in the itinerary.
   */
  private static boolean rode(Path path, int route)
  {
    return path.route0 == route || path.route1 == route || path.route2 == route || path.route3 == route;
  }

  static void checkCancelled(BooleanSupplier cancelled)
  {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new CancellationException();
  }

  static Journey plan(Timetable data, String fromId, String toId, long departure,
                      Map<Integer, Prediction> predictions, BooleanSupplier cancelled)
  {
    return plan(data, fromId, toId, departure, predictions, cancelled, DEFAULT_LIMITS);
  }

  static Journey plan(Timetable data, String fromId, String toId, long departure,
                      Map<Integer, Prediction> predictions, BooleanSupplier cancelled, Limits limits)
  {
    return plan(data, fromId, toId, departure, predictions, cancelled, limits, false);
  }

  static Journey plan(Timetable data, String fromId, String toId, long departure,
                      Map<Integer, Prediction> predictions, BooleanSupplier cancelled, Limits limits,
                      boolean requireRide)
  {
    return plan(data, fromId, toId, departure, predictions, cancelled, limits, requireRide, Options.NONE);
  }

  static Journey plan(Timetable data, String fromId, String toId, long departure,
                      Map<Integer, Prediction> predictions, BooleanSupplier cancelled, Limits limits,
                      boolean requireRide, Options options)
  {
    checkCancelled(cancelled);
    final int maxBoardings = options.maxBoardings;
    java.util.Set<String> excludedRoute = null;
    if (!options.excludedRoutes.isEmpty())
    {
      excludedRoute = options.excludedRoutes;
    }
    int n = data.stops.size();
    if (n > limits.stops || data.trips.size() > limits.trips || data.footpaths.size() > limits.footpaths)
      throw new IllegalArgumentException("Timetable exceeds journey planning limits");
    // The last stop with each id, as a map of them would give; map endpoints are the last two stops.
    int from = -1, to = -1;
    for (int i = n - 1; i >= 0 && (from < 0 || to < 0); --i)
    {
      String id = data.stops.get(i).id;
      if (from < 0 && java.util.Objects.equals(id, fromId)) from = i;
      if (to < 0 && java.util.Objects.equals(id, toId)) to = i;
    }
    if (from < 0 || to < 0)
      return null;
    long end = departure + WINDOW;
    List<List<Footpath>> footpaths = new ArrayList<>(n);
    for (int i = 0; i < n; ++i)
      footpaths.add(new ArrayList<>());
    for (Footpath path : data.footpaths)
    {
      if (path.from < 0 || path.from >= n || path.to < 0 || path.to >= n || path.seconds <= 0 || path.metres < 0)
        throw new IllegalArgumentException("Invalid platform transfer");
      if (path.metres > options.maxWalkMetres) continue;
      footpaths.get(path.from).add(path);
    }

    ConnectionIndex index = ConnectionIndex.of(data.trips, n, limits, cancelled);
    boolean[] excluded = null;
    if (excludedRoute != null)
    {
      excluded = new boolean[index.routeIds.length];
      for (int r = 0; r < excluded.length; ++r)
        excluded[r] = excludedRoute.contains(index.routeIds[r]);
    }
    // Trips with an estimate leave the cached order (they are scanned re-timed instead): a flag per trip, as a
    // lookup in the map for each connection scanned costs more than the rest of its scan.
    boolean[] predicted = null;
    for (Integer t : predictions.keySet())
      if (t != null && t >= 0 && t < data.trips.size())
      {
        if (predicted == null) predicted = new boolean[data.trips.size()];
        predicted[t] = true;
      }
    // Estimated trips are re-timed in a small separate list and merged into the cached base order.
    List<Connection> delayed = new ArrayList<>();
    for (Map.Entry<Integer, Prediction> entry : predictions.entrySet())
    {
      int t = entry.getKey();
      if (t < 0 || t >= data.trips.size() || !index.valid[t]) continue;
      Trip trip = data.trips.get(t);
      long delay = entry.getValue().delayMillis();
      for (int i = 0; i + 1 < trip.events.size(); ++i)
      {
        long leave = trip.events.get(i).departure + delay;
        long arrive = trip.events.get(i + 1).arrival + delay;
        if (leave >= departure && arrive <= end)
          delayed.add(new Connection(t, i, leave, arrive, entry.getValue()));
      }
    }
    Collections.sort(delayed, Comparator.comparingLong((Connection c) -> c.departure)
        .thenComparingInt(c -> c.trip).thenComparingInt(c -> c.event));
    Path[][] arrivals = new Path[MAX_BOARDINGS + 1][n];
    Boarding[][] boarded = new Boarding[MAX_BOARDINGS + 1][data.trips.size()];
    Path origin = new Path(from, departure);
    arrivals[0][from] = origin;
    walk(arrivals[0], origin, footpaths, end, cancelled, requireRide ? MAX_WALK_STRETCH : Long.MAX_VALUE);

    long targetArrival = end;
    if (!requireRide && arrivals[0][to] != null)
      targetArrival = Math.min(targetArrival, arrivals[0][to].time);
    int scanned = 0;
    int base = index.lowerBound(departure);
    int extra = 0;
    while (true)
    {
      Connection connection;
      while (base < index.size && ((predicted != null && predicted[index.trip[base]]) || index.arrival[base] > end)) ++base;
      boolean useBase = base < index.size;
      if (extra < delayed.size())
      {
        Connection d = delayed.get(extra);
        if (!useBase || d.departure < index.departure[base] || (d.departure == index.departure[base]
            && (d.trip < index.trip[base] || (d.trip == index.trip[base] && d.event < index.event[base]))))
          useBase = false;
      }
      else if (!useBase) break;
      if (useBase)
      {
        connection = new Connection(index.trip[base], index.event[base], index.departure[base], index.arrival[base], null);
        ++base;
      }
      else connection = delayed.get(extra++);
      // CSA stopping criterion: later departures cannot improve a reached target.
      // Keep equal-time connections to preserve the fewer-boardings tie break.
      if (connection.departure > targetArrival) break;
      if ((++scanned & 1023) == 0)
        checkCancelled(cancelled);
      if (excluded != null && excluded[index.route[connection.trip]]) continue;
      Trip trip = data.trips.get(connection.trip);
      Event start = trip.events.get(connection.event);
      Event finish = trip.events.get(connection.event + 1);
      for (int round = 1; round <= maxBoardings; ++round)
      {
        Boarding boarding = boarded[round][connection.trip];
        if (boarding == null && start.pickup && (connection.prediction == null
            || connection.event >= connection.prediction.firstBoardableEvent))
        {
          Path at = arrivals[round - 1][start.stop];
          long buffer = at == null || at.boardings == 0 ? 0 : TRANSFER_TIME;
          if (at != null && at.time + buffer <= connection.departure && !rode(at, index.route[connection.trip]))
          {
            boarding = new Boarding(at, connection);
            boarded[round][connection.trip] = boarding;
          }
        }
        if (boarding == null || !finish.dropOff)
          continue;
        Path previous = boarding.previous;
        if (!better(connection.arrival, previous.boardings + 1, previous.walked, arrivals[round][finish.stop]))
          continue;
        Event first = trip.events.get(boarding.connection.event);
        Leg leg = new Leg(first.stop, finish.stop, boarding.connection.departure, connection.arrival, trip,
                          boarding.connection.event, connection.event + 1, 0, connection.prediction);
        Path next = new Path(finish.stop, connection.arrival, previous, leg, index.route[connection.trip]);
        arrivals[round][finish.stop] = next;
        walk(arrivals[round], next, footpaths, targetArrival, cancelled, requireRide ? MAX_WALK_STRETCH : Long.MAX_VALUE);
        if (arrivals[round][to] != null)
          targetArrival = Math.min(targetArrival, arrivals[round][to].time);
      }
    }
    Path best = null;
    for (int round = requireRide ? 1 : 0; round <= maxBoardings; ++round)
      if (arrivals[round][to] != null && better(arrivals[round][to], best))
        best = arrivals[round][to];
    return best == null ? null : tighten(new Journey(departure, best), data, from, footpaths);
  }

  /** A walk counts this many times its minutes when choosing where to change: riding on is easier. */
  private static final int CHANGE_WALK_WEIGHT = 2;

  /**
   * The search boards each vehicle at the first stop of its route the rider can reach in time, which is not where
   * a rider boards: it walked back to the stop before the nearest one to catch the same tram there, or rode a tram
   * past the stop where the bus to change to stops, to meet that bus earlier on its way back. With the same
   * vehicles and arrival, each ride is boarded as late as it still works, last ride first: the first ride where the
   * rider can leave last, a change where it walks least for the riding it saves. Journeys whose walks are not one
   * walk to each boarding are left as they are.
   */
  static Journey tighten(Journey journey, Timetable data, int origin, List<List<Footpath>> footpaths)
  {
    List<Leg> legs = journey.legs;
    List<Integer> rides = new ArrayList<>();
    for (int i = 0; i < legs.size(); ++i) if (legs.get(i).trip != null) rides.add(i);
    if (rides.isEmpty()) return journey;
    // One walk at most before each ride, and the first one from the start.
    int previousEnd = -1;
    for (int r : rides)
    {
      if (r - previousEnd > 2 || (previousEnd < 0 && legs.get(0).from != origin)) return journey;
      previousEnd = r;
    }
    int count = rides.size();
    int[] first = new int[count], last = new int[count];
    for (int k = 0; k < count; ++k)
    {
      first[k] = legs.get(rides.get(k)).firstEvent;
      last[k] = legs.get(rides.get(k)).lastEvent;
    }
    boolean changed = false;
    for (int k = count - 1; k >= 0; --k)
    {
      Leg ride = legs.get(rides.get(k));
      Trip trip = ride.trip;
      long delay = delay(ride);
      int boardable = ride.prediction == null ? 0 : ride.prediction.firstBoardableEvent;
      if (k == 0)
      {
        // Leave as late as possible; for the same, walk the least.
        long bestLeave = Long.MIN_VALUE;
        int bestWalk = Integer.MAX_VALUE, bestEvent = first[0];
        for (int e = Math.max(first[0], boardable); e < last[0]; ++e)
        {
          Event event = trip.events.get(e);
          if (!event.pickup) continue;
          Footpath walk = walk(footpaths, origin, event.stop);
          if (walk == null) continue;
          int metres = walk.metres;
          long leave = event.departure + delay - walk.seconds * 1000L;
          if (leave < journey.departure) continue;
          if (leave > bestLeave || (leave == bestLeave && metres < bestWalk))
          {
            bestLeave = leave;
            bestWalk = metres;
            bestEvent = e;
          }
        }
        if (bestEvent != first[0] && bestLeave != Long.MIN_VALUE) { first[0] = bestEvent; changed = true; }
        continue;
      }
      // A change from the ride before: the later boarding saving the most riding for its walk.
      Leg before = legs.get(rides.get(k - 1));
      long beforeDelay = delay(before);
      long bestScore = Long.MIN_VALUE;
      int bestBoard = -1, bestAlight = -1;
      for (int e = Math.max(first[k], boardable); e < last[k]; ++e)
      {
        Event board = trip.events.get(e);
        if (!board.pickup) continue;
        long leaves = board.departure + delay;
        for (int a = first[k - 1] + 1; a < before.trip.events.size(); ++a)
        {
          Event alight = before.trip.events.get(a);
          if (!alight.dropOff) continue;
          long arrives = alight.arrival + beforeDelay;
          if (arrives > leaves) break;
          Footpath walk = walk(footpaths, alight.stop, board.stop);
          if (walk == null) continue;
          long seconds = walk.seconds;
          if (arrives + seconds * 1000L + TRANSFER_TIME > leaves) continue;
          long score = leaves - CHANGE_WALK_WEIGHT * seconds * 1000L;
          if (score > bestScore || (score == bestScore && a < bestAlight))
          {
            bestScore = score;
            bestBoard = e;
            bestAlight = a;
          }
        }
      }
      if (bestBoard >= 0 && (bestBoard != first[k] || bestAlight != last[k - 1]))
      {
        first[k] = bestBoard;
        last[k - 1] = bestAlight;
        changed = true;
      }
    }
    if (!changed) return journey;
    List<Leg> tightened = new ArrayList<>();
    for (int k = 0; k < count; ++k)
    {
      Leg ride = legs.get(rides.get(k));
      Event board = ride.trip.events.get(first[k]);
      long delay = delay(ride);
      if (k == 0)
      {
        if (board.stop != origin)
        {
          Footpath walk = walk(footpaths, origin, board.stop);
          tightened.add(new Leg(origin, board.stop, journey.departure, journey.departure + walk.seconds * 1000L, null,
                                -1, -1, walk.metres, null));
        }
      }
      else
      {
        Leg before = legs.get(rides.get(k - 1));
        Event alight = before.trip.events.get(last[k - 1]);
        if (alight.stop != board.stop)
        {
          Footpath walk = walk(footpaths, alight.stop, board.stop);
          long arrived = alight.arrival + delay(before);
          tightened.add(new Leg(alight.stop, board.stop, arrived, arrived + walk.seconds * 1000L, null, -1, -1,
                                walk.metres, null));
        }
      }
      Event alight = ride.trip.events.get(last[k]);
      tightened.add(new Leg(board.stop, alight.stop, board.departure + delay, alight.arrival + delay, ride.trip,
                            first[k], last[k], 0, ride.prediction));
    }
    for (int i = rides.get(count - 1) + 1; i < legs.size(); ++i) tightened.add(legs.get(i));
    return new Journey(journey.departure, tightened, journey.boardings);
  }

  private static long delay(Leg ride) { return ride.prediction == null ? 0 : ride.prediction.delayMillis(); }

  /** The shortest footpath from {@code from} to {@code to}, a zero one at the same stop, or null. */
  private static Footpath walk(List<List<Footpath>> footpaths, int from, int to)
  {
    return from == to ? new Footpath(from, to, 0, 0) : footpath(footpaths, from, to);
  }

  private static Footpath footpath(List<List<Footpath>> footpaths, int from, int to)
  {
    Footpath best = null;
    for (Footpath path : footpaths.get(from))
      if (path.to == to && (best == null || path.seconds < best.seconds)) best = path;
    return best;
  }

  /** Departure-sorted connections of one immutable trip list, shared by refinement iterations. */
  static final class ConnectionIndex
  {
    /**
     * Indexes of the latest trip lists, newest first. A journey with stops plans each part on its own hour's
     * trips and shows them again part by part: one slot made every part rebuild its index twice.
     */
    private static final java.util.ArrayDeque<CachedIndex> sIndexes = new java.util.ArrayDeque<>();
    /** One per part of a journey with 5 stops. */
    private static final int CACHED_INDEXES = 6;

    private static final class CachedIndex
    {
      final java.lang.ref.WeakReference<List<Trip>> trips;
      final ConnectionIndex index;
      CachedIndex(List<Trip> trips, ConnectionIndex index)
      {
        this.trips = new java.lang.ref.WeakReference<>(trips);
        this.index = index;
      }
    }
    final boolean[] valid;
    /** Each trip's line as a small number, the same for trips of one route id. */
    final int[] route;
    /** The route id of each {@link #route} number. */
    final String[] routeIds;
    final int size;
    final int[] trip;
    final int[] event;
    final long[] departure;
    final long[] arrival;
    private final int mStops;
    private final int mLimit;

    static synchronized ConnectionIndex of(List<Trip> trips, int stops, Limits limits, BooleanSupplier cancelled)
    {
      for (java.util.Iterator<CachedIndex> it = sIndexes.iterator(); it.hasNext(); )
      {
        CachedIndex cached = it.next();
        List<Trip> cachedTrips = cached.trips.get();
        if (cachedTrips == trips && cached.index.mStops <= stops && cached.index.mLimit <= limits.connections)
        {
          it.remove();
          sIndexes.addFirst(cached);
          return cached.index;
        }
        // Gone, or too small for this search: replaced below.
        if (cachedTrips == null || cachedTrips == trips)
          it.remove();
      }
      ConnectionIndex index = new ConnectionIndex(trips, stops, limits.connections, cancelled);
      sIndexes.addFirst(new CachedIndex(trips, index));
      while (sIndexes.size() > CACHED_INDEXES)
        sIndexes.removeLast();
      return index;
    }

    private ConnectionIndex(List<Trip> trips, int stops, int limit, BooleanSupplier cancelled)
    {
      mStops = stops;
      mLimit = limit;
      valid = new boolean[trips.size()];
      route = new int[trips.size()];
      Map<String, Integer> routes = new HashMap<>();
      for (int t = 0; t < trips.size(); ++t)
      {
        Integer known = routes.putIfAbsent(trips.get(t).routeId, routes.size());
        route[t] = known == null ? routes.size() - 1 : known;
      }
      routeIds = new String[routes.size()];
      for (Map.Entry<String, Integer> entry : routes.entrySet()) routeIds[entry.getValue()] = entry.getKey();
      long count = 0;
      for (int t = 0; t < trips.size(); ++t)
      {
        checkCancelled(cancelled);
        Trip value = trips.get(t);
        valid[t] = valid(value, stops);
        if (!valid[t]) continue;
        count += value.events.size() - 1;
        if (count > limit)
          throw new IllegalArgumentException("Too many journey connections");
      }
      int total = (int) count;
      int[] tripOf = new int[total], eventOf = new int[total];
      long[] leave = new long[total], arrive = new long[total];
      int k = 0;
      for (int t = 0; t < trips.size(); ++t)
      {
        if (!valid[t]) continue;
        List<Event> events = trips.get(t).events;
        for (int i = 0; i + 1 < events.size(); ++i, ++k)
        {
          tripOf[k] = t; eventOf[k] = i;
          leave[k] = events.get(i).departure; arrive[k] = events.get(i + 1).arrival;
        }
      }
      checkCancelled(cancelled);
      // Built once per instantiated timetable; ties keep the previous trip/event order.
      Integer[] order = new Integer[total];
      for (int c = 0; c < total; ++c) order[c] = c;
      java.util.Arrays.sort(order, (x, y) -> leave[x] != leave[y] ? Long.compare(leave[x], leave[y])
          : tripOf[x] != tripOf[y] ? Integer.compare(tripOf[x], tripOf[y]) : Integer.compare(eventOf[x], eventOf[y]));
      size = total;
      trip = new int[size];
      event = new int[size];
      departure = new long[size];
      arrival = new long[size];
      for (int c = 0; c < size; ++c)
      {
        int o = order[c];
        trip[c] = tripOf[o]; event[c] = eventOf[o]; departure[c] = leave[o]; arrival[c] = arrive[o];
      }
    }

    int lowerBound(long time)
    {
      int low = 0, high = size;
      while (low < high)
      {
        int middle = (low + high) >>> 1;
        if (departure[middle] < time) low = middle + 1;
        else high = middle;
      }
      return low;
    }
  }

  private static boolean valid(Trip trip, int count)
  {
    Event previous = null;
    for (Event event : trip.events)
    {
      if (event.stop < 0 || event.stop >= count || event.arrival > event.departure
          || (previous != null && (event.sequence <= previous.sequence || event.arrival < previous.departure)))
        return false;
      previous = event;
    }
    return trip.events.size() >= 2;
  }

  private static void walk(Path[] labels, Path source, List<List<Footpath>> paths, long end,
                           BooleanSupplier cancelled, long maxWalking)
  {
    PriorityQueue<Path> queue = new PriorityQueue<>(Comparator.comparingLong(p -> p.time));
    queue.add(source);
    int visited = 0;
    while (!queue.isEmpty())
    {
      if ((++visited & 255) == 0)
        checkCancelled(cancelled);
      Path current = queue.remove();
      if (labels[current.stop] != current)
        continue;
      long walking = current.stretch;
      for (Footpath path : paths.get(current.stop))
      {
        if (walking + path.metres > maxWalking) continue;
        long arrive = current.time + path.seconds * 1000L;
        if (arrive > end)
          continue;
        if (better(arrive, current.boardings, current.walked + path.metres, labels[path.to]))
        {
          Leg leg = new Leg(path.from, path.to, current.time, arrive, null, -1, -1, path.metres, null);
          Path next = new Path(path.to, arrive, current.walked + path.metres, current, leg, walking + path.metres);
          labels[path.to] = next;
          queue.add(next);
        }
      }
    }
  }
}
