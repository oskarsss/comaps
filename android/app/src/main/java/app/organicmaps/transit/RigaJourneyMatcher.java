package app.organicmaps.transit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Fails closed: public pattern, start minute (±1 when no exact one), active service instance and GPS progress must agree. */
final class RigaJourneyMatcher
{
  static final long MAX_RECEIPT_AGE = 30000;
  /** How long before its trip starts a vehicle at the terminus can be matched to that trip. */
  static final long LAYOVER = 15 * 60000;
  interface Shapes { RigaJourneyGeometry get(String id) throws IOException; }
  interface Trace { void line(String text); }
  /** Diagnostics sink (the app log); null in tests. */
  static volatile Trace sTrace;
  /** Lines ("riga_trol_15") whose vehicles are traced one by one: those of the journey on screen. */
  static volatile Set<String> sWatched = new HashSet<>();

  static String lineOf(String shape)
  {
    int cut = shape.lastIndexOf('_');
    return cut < 0 ? shape : shape.substring(0, cut);
  }

  private static String clock(long millis)
  {
    return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(millis));
  }

  static Map<Integer, RigaJourneyPlanner.Prediction> match(RigaJourneyPlanner.Timetable data,
      List<RigaVehicle> vehicles, long now, Shapes shapes, BooleanSupplier cancelled) throws IOException
  {
    Map<String, List<Integer>> keys = new HashMap<>();
    for (int t = 0; t < data.trips.size(); ++t)
    {
      RigaJourneyPlanner.Trip trip = data.trips.get(t);
      if (trip.events.size() < 2 || trip.shapeId.isEmpty())
        continue;
      long minutes = (trip.events.get(0).departure - trip.serviceOrigin) / 60000;
      keys.computeIfAbsent(trip.shapeId + "@" + minutes, ignored -> new ArrayList<>()).add(t);
    }
    Map<Integer, RigaJourneyPlanner.Prediction> result = new HashMap<>();
    Set<Integer> ambiguous = new HashSet<>();
    // A vehicle id can appear once per trip record (the trip it just finished and the next one). Each record is
    // matched on its own; an id that still fits more than one trip is dropped below.
    Set<String> records = new HashSet<>();
    Set<String> duplicateRecords = new HashSet<>();
    for (RigaVehicle vehicle : vehicles)
      if (!records.add(vehicle.key()))
        duplicateRecords.add(vehicle.key());
    Map<String, Integer> tripOfVehicle = new HashMap<>();
    Set<String> ambiguousVehicles = new HashSet<>();
    Trace trace = sTrace;
    Set<String> watched = sWatched;
    Map<String, Integer> outcomes = new java.util.TreeMap<>();
    // Per traced vehicle: what it reported and why it did or did not get a trip.
    Map<String, String> traced = new java.util.LinkedHashMap<>();
    Map<Integer, String> tracedTrips = new HashMap<>();
    for (RigaVehicle vehicle : vehicles)
    {
      RigaJourneyPlanner.checkCancelled(cancelled);
      String mode = vehicle.type == 1 ? "trol" : vehicle.type == 2 ? "bus" : vehicle.type == 3 ? "tram"
                    : vehicle.type == 7 ? "nightbus" : "";
      String shape = "riga_" + mode + "_" + vehicle.route + "_" + vehicle.direction;
      String label = trace != null && watched.contains("riga_" + mode + "_" + vehicle.route)
                         ? "vehicle " + vehicle.id + " " + shape + " start " + vehicle.startMinutes / 60 + ":"
                               + String.format(java.util.Locale.US, "%02d", vehicle.startMinutes % 60)
                               + " (" + vehicle.startMinutes + ") delay " + vehicle.delaySeconds + "s at "
                               + String.format(java.util.Locale.US, "%.5f,%.5f", vehicle.lat, vehicle.lon)
                               + " age " + (now - vehicle.receivedAt) / 1000 + "s: "
                         : null;
      String why;
      if (!vehicle.hasTripUpdate)
        why = "no trip update";
      else if (duplicateRecords.contains(vehicle.key()))
        why = "duplicate record";
      else if (now < vehicle.receivedAt || now - vehicle.receivedAt > MAX_RECEIPT_AGE)
        why = "stale";
      else if (mode.isEmpty())
        why = "unknown type " + vehicle.type;
      else
        why = null;
      if (why != null)
      {
        count(outcomes, why.startsWith("unknown") ? "unknown type" : why);
        if (label != null) traced.put(vehicle.key() + "#" + traced.size(), label + why);
        continue;
      }
      List<Integer> candidates = keys.get(shape + "@" + vehicle.startMinutes);
      boolean exact = candidates != null;
      if (candidates == null)
      {
        // About 10% of the operator's start minutes are one minute earlier or later than GTFS (e.g. bus 3 b-a).
        // Headways are longer than that, and the unique-active-trip and GPS checks below still have to pass.
        candidates = new ArrayList<>();
        for (int offset : new int[] {-1, 1})
        {
          List<Integer> near = keys.get(shape + "@" + (vehicle.startMinutes + offset));
          if (near != null)
            candidates.addAll(near);
        }
      }
      List<Integer> active = new ArrayList<>();
      for (int t : candidates)
      {
        RigaJourneyPlanner.Trip trip = data.trips.get(t);
        long delay = vehicle.delaySeconds * 1000L;
        // A vehicle waiting at the terminus already reports its next trip (Mobilly shows its number then too);
        // the projection below still requires it to be at the start of that trip's route.
        if (now >= trip.events.get(0).departure + delay - LAYOVER
            && now <= trip.events.get(trip.events.size() - 1).arrival + delay + 60000)
          active.add(t);
      }
      if (active.size() != 1)
      {
        why = candidates.isEmpty() ? "no timetable trip with this shape and start (±1 min)"
              : active.isEmpty() ? "none of " + candidates.size() + " trips active now (finished or > "
                    + LAYOVER / 60000 + " min before start)"
              : active.size() + " trips active";
        count(outcomes, candidates.isEmpty() ? "no trip" : active.isEmpty() ? "not active" : "several active");
        if (label != null)
        {
          StringBuilder text = new StringBuilder(label).append(why);
          for (int t : candidates)
          {
            RigaJourneyPlanner.Trip trip = data.trips.get(t);
            text.append(" [").append(trip.id).append(' ').append(clock(trip.events.get(0).departure)).append('-')
                .append(clock(trip.events.get(trip.events.size() - 1).arrival)).append(']');
          }
          traced.put(vehicle.key() + "#" + traced.size(), text.toString());
        }
        continue;
      }
      int t = active.get(0);
      RigaJourneyPlanner.Trip trip = data.trips.get(t);
      String tripText = label == null ? "" : "trip " + trip.id + (exact ? "" : " (start ±1)") + " "
          + clock(trip.events.get(0).departure) + "-" + clock(trip.events.get(trip.events.size() - 1).arrival) + " ";
      RigaJourneyGeometry geometry = shapes.get(shape);
      double[] anchors = geometry == null ? null : geometry.anchors(data, trip);
      if (anchors == null)
      {
        count(outcomes, "no geometry");
        if (label != null) traced.put(vehicle.key() + "#" + traced.size(), label + tripText + "no shape geometry");
        continue;
      }
      // Restrict projection to the time-adjacent stops to disambiguate loops/crossings.
      int expected = 0;
      long delay = vehicle.delaySeconds * 1000L;
      while (expected + 1 < trip.events.size() && trip.events.get(expected + 1).arrival + delay < now)
        ++expected;
      int low = Math.max(0, expected - 1);
      int high = Math.min(anchors.length - 1, expected + 2);
      RigaJourneyGeometry.Projection position = geometry.project(
          new RigaJourneyGeometry.Point(vehicle.lat, vehicle.lon), anchors[low], anchors[high]);
      String where = label == null ? "" : "near stop " + expected + "/" + (trip.events.size() - 1) + " ("
          + Math.round(position.distance) + " m off route, " + Math.round(position.along) + " m along) ";
      if (position.distance > 150)
      {
        count(outcomes, "off route");
        if (label != null)
          traced.put(vehicle.key() + "#" + traced.size(), label + tripText + where + "too far from the expected stops");
        continue;
      }
      int first = 0;
      while (first < anchors.length && (anchors[first] + 25 < position.along
          || trip.events.get(first).departure + delay < now - 15000))
        ++first;
      if (first >= anchors.length - 1)
      {
        count(outcomes, "at end");
        if (label != null)
          traced.put(vehicle.key() + "#" + traced.size(), label + tripText + where + "already at the last stop");
        continue;
      }
      count(outcomes, "matched");
      if (label != null)
      {
        traced.put(vehicle.key() + "#" + traced.size(), label + tripText + where + "matched, next stop " + first);
        tracedTrips.put(t, vehicle.id);
      }
      if (result.containsKey(t))
        ambiguous.add(t);
      Integer other = tripOfVehicle.put(vehicle.id, t);
      if (other != null && other != t)
      {
        ambiguousVehicles.add(vehicle.id);
        ambiguous.add(other);
      }
      result.put(t, new RigaJourneyPlanner.Prediction(vehicle.delaySeconds, first, vehicle.id, vehicle.key(),
                                                      vehicle.receivedAt));
    }
    for (String id : ambiguousVehicles)
      ambiguous.add(tripOfVehicle.get(id));
    for (int t : ambiguous)
      result.remove(t);
    if (trace != null)
    {
      for (String text : traced.values())
        trace.line(text);
      for (int t : ambiguous)
        if (tracedTrips.containsKey(t))
          trace.line("trip " + data.trips.get(t).id + " dropped: claimed by more than one vehicle"
                     + " or its vehicle fits another trip too");
      for (String id : ambiguousVehicles)
        trace.line("vehicle " + id + " dropped: fits more than one trip");
      outcomes.put("ambiguous trips", ambiguous.size());
      trace.line("match at " + clock(now) + ": " + vehicles.size() + " records, " + result.size() + " trips matched, "
                 + outcomes + ", traced lines " + watched);
    }
    return result;
  }

  private static void count(Map<String, Integer> outcomes, String reason)
  {
    Integer n = outcomes.get(reason);
    outcomes.put(reason, n == null ? 1 : n + 1);
  }
}
