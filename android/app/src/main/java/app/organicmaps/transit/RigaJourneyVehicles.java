package app.organicmaps.transit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Vehicles shown with a chosen journey: for every ride still ahead, the vehicle matched to that ride (if any)
 * first, then other fresh vehicles of the same line running in the same direction, nearest to the boarding stop.
 */
final class RigaJourneyVehicles
{
  /** Vehicles of a line shown per ride besides the matched one, so a busy line does not hide the route. */
  static final int OTHERS_PER_RIDE = 4;
  /** Must not exceed the native marker cap. */
  static final int MAX = 16;

  static List<RigaVehicle> select(RigaJourneyPlanner.Journey journey, List<RigaVehicle> fleet, long now)
  {
    return select(journey, null, fleet, now);
  }

  /** @param stops the journey's stops, for ordering other vehicles by distance to the boarding stop; may be null. */
  static List<RigaVehicle> select(RigaJourneyPlanner.Journey journey, List<RigaJourneyPlanner.Stop> stops,
                                  List<RigaVehicle> fleet, long now)
  {
    List<RigaVehicle> selected = new ArrayList<>();
    if (journey == null) return selected;
    Set<String> seen = new HashSet<>();
    Set<String> duplicates = new HashSet<>();
    for (RigaVehicle vehicle : fleet)
      if (!seen.add(vehicle.key())) duplicates.add(vehicle.key());
    seen.clear();
    // Matched vehicles first: they are the ones the rider takes, and must survive the cap.
    for (RigaJourneyPlanner.Leg leg : journey.legs)
    {
      if (leg.trip == null || leg.prediction == null || leg.arrival <= now) continue;
      for (RigaVehicle vehicle : fleet)
        if (leg.prediction.matches(vehicle) && usable(vehicle, leg, duplicates, now) && seen.add(vehicle.id))
          selected.add(vehicle);
    }
    for (RigaJourneyPlanner.Leg leg : journey.legs)
    {
      if (leg.trip == null || leg.arrival <= now || leg.trip.shapeId.isEmpty()) continue;
      List<RigaVehicle> line = new ArrayList<>();
      for (RigaVehicle vehicle : fleet)
        if (vehicle.hasTripUpdate && leg.trip.shapeId.equals(shape(vehicle)) && usable(vehicle, leg, duplicates, now)
            && !seen.contains(vehicle.id))
          line.add(vehicle);
      if (stops != null && leg.from >= 0 && leg.from < stops.size())
      {
        RigaJourneyPlanner.Stop board = stops.get(leg.from);
        line.sort(java.util.Comparator.comparingDouble(
            v -> RigaJourneyDatabase.distance(v.lat, v.lon, board.lat, board.lon)));
      }
      int added = 0;
      for (RigaVehicle vehicle : line)
      {
        if (added >= OTHERS_PER_RIDE || selected.size() >= MAX) break;
        if (seen.add(vehicle.id)) { selected.add(vehicle); ++added; }
      }
    }
    return selected.size() > MAX ? new ArrayList<>(selected.subList(0, MAX)) : selected;
  }

  /** Whether {@code vehicle} is the one matched to a ride of {@code journey}. */
  static boolean matched(RigaJourneyPlanner.Journey journey, RigaVehicle vehicle)
  {
    if (journey == null) return false;
    for (RigaJourneyPlanner.Leg leg : journey.legs)
      if (leg.prediction != null && leg.prediction.matches(vehicle)) return true;
    return false;
  }

  /** The timetable shape id of the line and direction the vehicle reports, as used by the matcher. */
  static String shape(RigaVehicle vehicle)
  {
    String mode = vehicle.type == 1 ? "trol" : vehicle.type == 2 ? "bus" : vehicle.type == 3 ? "tram"
                  : vehicle.type == 7 ? "nightbus" : "";
    return mode.isEmpty() ? "" : "riga_" + mode + "_" + vehicle.route + "_" + vehicle.direction;
  }

  private static boolean usable(RigaVehicle vehicle, RigaJourneyPlanner.Leg leg, Set<String> duplicates, long now)
  {
    if (duplicates.contains(vehicle.key()) || now < vehicle.receivedAt || now - vehicle.receivedAt > 45000
        || !vehicle.route.equals(leg.trip.routeName)) return false;
    int gtfsType = vehicle.type == 1 ? 11 : vehicle.type == 3 ? 0
        : vehicle.type == 2 || vehicle.type == 7 ? 3 : -1;
    return gtfsType == leg.trip.type;
  }
}
