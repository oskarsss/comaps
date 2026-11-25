package app.organicmaps.transit;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fleet number to validate in Mobilly when no live trip was matched. The feed lists a vehicle on its trip only
 * once it leaves the first stop; until then it still reports the trip it came in on. So:
 * <ol>
 * <li>{@link #queue}: the vehicles reaching the trip's first stop (waiting there, or on a trip ending there), in
 * order of when they get there, take the line's departures from that stop in order;</li>
 * <li>else {@link #find}: the vehicle of the line expected at the boarding stop closest to the departure.</li>
 * </ol>
 * Replayed through the app's own matching (RigaNextVehicleReplayTest), with the number the rider sees
 * as it leaves / 2 / 15 minutes before. Tuned on 19:22-20:35 (~456 departures), boarding at the first stop:
 * 95%, 94%, 86%; 6 stops on: 97%, 96%, 92%. Checked on unseen 21:00-23:00 (~450): first stop 98%, 97%, 94%;
 * 6 stops on: 98%, 98%, 97%. Misses are mostly depot runs and vehicles the feed does not list.
 */
final class RigaNextVehicle
{
  /** Average speed of a city vehicle with its stops, metres a second (18 km/h). */
  static final double SPEED = 5;
  static final double OFF_ROUTE = 60;
  /** A vehicle this close to the first stop waits there until it leaves. */
  static final double AT_START = 200;
  static final long MAX_AGE = 120000;
  /** A first or last stop of another trip this close is the same terminus. */
  static final double SAME_TERMINUS = 300;
  /** A vehicle this close to the terminus is waiting there. */
  static final double WAITING = 400;
  /** A vehicle due this much after a departure can still take it, running late. */
  static final long LATE = 300000;
  /** A vehicle that dropped out of the feed by the terminus is taken as still waiting there for this long. */
  static final long GONE = 450000;

  private RigaNextVehicle() {}

  static String line(RigaVehicle vehicle)
  {
    String mode = vehicle.type == 1 ? "trol" : vehicle.type == 2 ? "bus" : vehicle.type == 3 ? "tram"
                  : vehicle.type == 7 ? "nightbus" : "";
    return mode.isEmpty() ? "" : "riga_" + mode + "_" + vehicle.route;
  }

  /** "riga_trol_15_a-b" -> "riga_trol_15_b-a"; null when the direction has no other way. */
  @Nullable
  static String reversed(String shape)
  {
    int cut = shape.lastIndexOf('_'), dash = shape.indexOf('-', cut);
    if (cut < 0 || dash < 0) return null;
    return shape.substring(0, cut + 1) + shape.substring(dash + 1) + "-" + shape.substring(cut + 1, dash);
  }

  private static boolean usable(RigaVehicle vehicle, long now)
  {
    return vehicle.id.matches("\\d{3,6}") && now - vehicle.receivedAt <= MAX_AGE;
  }

  /** On its way to (or in) the depot: e.g. direction "b-d1". Done for the day, so it takes no departure. */
  static boolean depotBound(RigaVehicle vehicle)
  {
    int dash = vehicle.direction.indexOf('-');
    return dash >= 0 && vehicle.direction.startsWith("d", dash + 1);
  }

  private static long start(RigaJourneyPlanner.Trip trip) { return trip.events.get(0).departure; }
  private static long end(RigaJourneyPlanner.Trip trip) { return trip.events.get(trip.events.size() - 1).arrival; }
  private static RigaJourneyGeometry.Point at(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Event event)
  {
    RigaJourneyPlanner.Stop stop = data.stops.get(event.stop);
    return new RigaJourneyGeometry.Point(stop.lat, stop.lon);
  }

  /** The trip of the line each vehicle reports (direction and start minute, give or take one), by vehicle id. */
  static Map<String, RigaJourneyPlanner.Trip> listed(List<RigaJourneyPlanner.Trip> trips, List<RigaVehicle> vehicles,
                                                     long now)
  {
    Map<String, RigaJourneyPlanner.Trip> result = new HashMap<>();
    for (RigaVehicle vehicle : vehicles)
    {
      if (!usable(vehicle, now)) continue;
      String shape = line(vehicle) + "_" + vehicle.direction;
      RigaJourneyPlanner.Trip best = null;
      long bestOff = 2;
      for (RigaJourneyPlanner.Trip trip : trips)
      {
        if (!trip.shapeId.equals(shape)) continue;
        long off = Math.abs((start(trip) - trip.serviceOrigin) / 60000 - vehicle.startMinutes);
        // Several service days: the one running around now.
        if (off < bestOff || (off == bestOff && best != null
                              && Math.abs(start(trip) - now) < Math.abs(start(best) - now)))
        {
          bestOff = off;
          best = trip;
        }
      }
      if (best != null && Math.abs(start(best) - now) < 6 * 3600000L) result.put(vehicle.id, best);
    }
    return result;
  }

  private static boolean finished(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Trip trip, RigaVehicle vehicle,
                                  long now)
  {
    return end(trip) + vehicle.delaySeconds * 1000L - 60000 <= now
        || RigaJourneyGeometry.distance(new RigaJourneyGeometry.Point(vehicle.lat, vehicle.lon),
                                        at(data, trip.events.get(trip.events.size() - 1))) < SAME_TERMINUS;
  }

  /** The line's trips of the day, e.g. riga_trol_15: all its directions and depot runs. */
  static List<RigaJourneyPlanner.Trip> lineTrips(RigaJourneyPlanner.Timetable data, String line, long now)
  {
    List<RigaJourneyPlanner.Trip> result = new ArrayList<>();
    for (RigaJourneyPlanner.Trip trip : data.trips)
      if (RigaJourneyMatcher.lineOf(trip.shapeId).equals(line) && !trip.events.isEmpty()
          && Math.abs(start(trip) - now) < 6 * 3600000L)
        result.add(trip);
    return result;
  }

  /** Step 1 above; null when no vehicle is due at the first stop for this trip. */
  @Nullable
  static String queue(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Trip trip, List<RigaJourneyPlanner.Trip> lineTrips,
                      Map<String, RigaJourneyPlanner.Trip> listed, List<RigaVehicle> vehicles,
                      List<RigaVehicle> seen, long now)
  {
    String line = RigaJourneyMatcher.lineOf(trip.shapeId);
    RigaJourneyGeometry.Point terminus = at(data, trip.events.get(0));
    Set<RigaJourneyPlanner.Trip> running = new HashSet<>(listed.values());
    List<RigaJourneyPlanner.Trip> departures = new ArrayList<>();
    for (RigaJourneyPlanner.Trip other : lineTrips)
      if (start(other) >= now - 60000 && (other == trip || !running.contains(other))
          && RigaJourneyGeometry.distance(at(data, other.events.get(0)), terminus) < SAME_TERMINUS)
        departures.add(other);
    Collections.sort(departures, (a, b) -> Long.compare(start(a), start(b)));
    Map<String, Long> due = new HashMap<>();
    Set<String> present = new HashSet<>();
    for (RigaVehicle vehicle : vehicles)
      if (usable(vehicle, now)) present.add(vehicle.id);
    for (RigaVehicle vehicle : seen)
    {
      if (present.contains(vehicle.id) || depotBound(vehicle) || !vehicle.id.matches("\\d{3,6}")
          || now - vehicle.receivedAt > GONE || !line.equals(line(vehicle)))
        continue;
      // Gone from the feed (switched off) by the terminus: still waiting there, in since the trip it came in on
      // ended. (Also counting those that vanished on their way there was worse in replays.)
      RigaJourneyPlanner.Trip on = null;
      for (RigaJourneyPlanner.Trip other : lineTrips)
        if (other.shapeId.equals(line(vehicle) + "_" + vehicle.direction)
            && Math.abs((start(other) - other.serviceOrigin) / 60000 - vehicle.startMinutes) <= 1
            && Math.abs(start(other) - now) < 6 * 3600000L)
          on = other;
      boolean there = RigaJourneyGeometry.distance(new RigaJourneyGeometry.Point(vehicle.lat, vehicle.lon), terminus)
                      < WAITING;
      if (there)
        due.put(vehicle.id, on == null ? now : Math.min(now, end(on) + vehicle.delaySeconds * 1000L));
    }
    for (RigaVehicle vehicle : vehicles)
    {
      if (!usable(vehicle, now) || !line.equals(line(vehicle)) || depotBound(vehicle)) continue;
      RigaJourneyPlanner.Trip on = listed.get(vehicle.id);
      if (on == trip) continue;
      long when;
      if (on != null && !finished(data, on, vehicle, now))
      {
        if (RigaJourneyGeometry.distance(at(data, on.events.get(on.events.size() - 1)), terminus) >= SAME_TERMINUS)
          continue;
        when = end(on) + vehicle.delaySeconds * 1000L;
      }
      else if (RigaJourneyGeometry.distance(new RigaJourneyGeometry.Point(vehicle.lat, vehicle.lon), terminus) < WAITING)
        when = on == null ? now : Math.min(now, end(on) + vehicle.delaySeconds * 1000L);
      else
        continue;
      Long known = due.get(vehicle.id);
      due.put(vehicle.id, known == null ? when : Math.min(known, when));
    }
    List<String> order = new ArrayList<>(due.keySet());
    Collections.sort(order, (a, b) -> Long.compare(due.get(a), due.get(b)));
    for (RigaJourneyPlanner.Trip departure : departures)
    {
      String pick = null;
      for (String id : order)
        if (due.get(id) <= start(departure) + LATE)
        {
          pick = id;
          break;
        }
      if (pick == null) continue;
      order.remove(pick);
      if (departure == trip) return pick;
    }
    return null;
  }

  /**
   * Step 2 above.
   * @param taken vehicles listed on other unfinished trips of this direction
   * @param geometry the shape of the ride's trip
   * @param back the shape of the way back, or null
   * @param boardAlong metres along geometry to the boarding stop
   * @param departure when the ride is planned to leave the boarding stop
   */
  @Nullable
  static String find(String line, RigaJourneyGeometry geometry, @Nullable RigaJourneyGeometry back, double boardAlong,
                     List<RigaVehicle> vehicles, Set<String> taken, long departure, long now)
  {
    double wait = Math.max(0, departure - now) / 1000.0;
    String best = null;
    double bestScore = Double.POSITIVE_INFINITY;
    for (RigaVehicle vehicle : vehicles)
    {
      if (!usable(vehicle, now) || !line.equals(line(vehicle)) || taken.contains(vehicle.id) || depotBound(vehicle))
        continue;
      RigaJourneyGeometry.Point at = new RigaJourneyGeometry.Point(vehicle.lat, vehicle.lon);
      double seconds;
      if (boardAlong <= AT_START && RigaJourneyGeometry.distance(at, geometry.points.get(0)) <= AT_START)
        seconds = wait; // waiting at the terminus: there whenever the ride leaves
      else
      {
        RigaJourneyGeometry.Projection on = geometry.project(at, 0, boardAlong);
        if (on.distance <= OFF_ROUTE)
          seconds = (boardAlong - on.along) / SPEED;
        else if (back != null && (on = back.project(at, 0, back.lengths[back.lengths.length - 1])).distance <= OFF_ROUTE)
          seconds = (back.lengths[back.lengths.length - 1] - on.along + boardAlong) / SPEED;
        else
          continue;
      }
      double score = Math.abs(seconds - wait);
      if (score < bestScore)
      {
        bestScore = score;
        best = vehicle.id;
      }
    }
    return best;
  }

  /** Steps 1 and 2 for boarding trip at its event board; see {@link #find} for the geometry. */
  @Nullable
  static String guess(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Trip trip, RigaJourneyGeometry geometry,
                      @Nullable RigaJourneyGeometry back, double boardAlong, List<RigaVehicle> vehicles,
                      List<RigaVehicle> seen, long departure, long now)
  {
    String line = RigaJourneyMatcher.lineOf(trip.shapeId);
    List<RigaJourneyPlanner.Trip> trips = lineTrips(data, line, now);
    Map<String, RigaJourneyPlanner.Trip> listed = listed(trips, vehicles, now);
    String queued = queue(data, trip, trips, listed, vehicles, seen, now);
    if (queued != null) return queued;
    Set<String> taken = new HashSet<>();
    for (RigaVehicle vehicle : vehicles)
    {
      RigaJourneyPlanner.Trip on = listed.get(vehicle.id);
      if (on != null && on != trip && on.shapeId.equals(trip.shapeId) && !finished(data, on, vehicle, now))
        taken.add(vehicle.id);
    }
    return find(line, geometry, back, boardAlong, vehicles, taken, departure, now);
  }

  /** {@link #guess} for boarding trip at its event board; null when unknown. */
  @Nullable
  static String forLeg(RigaJourneyDatabase database, RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Trip trip,
                       int board, List<RigaVehicle> vehicles, List<RigaVehicle> seen, long departure, long now)
  {
    try
    {
      RigaJourneyGeometry geometry = database.shape(trip.shapeId);
      if (geometry == null) return null;
      String backId = reversed(trip.shapeId);
      RigaJourneyGeometry back = backId == null ? null : database.shape(backId);
      double[] anchors = geometry.anchors(data, trip);
      RigaJourneyPlanner.Stop stop = data.stops.get(trip.events.get(board).stop);
      double boardAlong = anchors != null ? anchors[board]
          : geometry.project(new RigaJourneyGeometry.Point(stop.lat, stop.lon), 0,
                             geometry.lengths[geometry.lengths.length - 1]).along;
      return guess(data, trip, geometry, back, boardAlong, vehicles, seen, departure, now);
    }
    catch (java.io.IOException | RuntimeException e)
    {
      app.organicmaps.sdk.util.log.Logger.w("RigaMatch", "next vehicle for trip " + trip.id + " failed", e);
      return null;
    }
  }
}
