package app.organicmaps.transit;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RigaJourneyMatcherTest
{
  private static final long NOW = 6120000; // 102 minutes after service origin.
  private static RigaJourneyPlanner.Timetable table(String shape, String secondShape)
  {
    List<RigaJourneyPlanner.Stop> stops = Arrays.asList(
        new RigaJourneyPlanner.Stop("a", "A", "", 56.95, 24.10),
        new RigaJourneyPlanner.Stop("b", "B", "", 56.95, 24.11),
        new RigaJourneyPlanner.Stop("c", "C", "", 56.95, 24.12));
    List<RigaJourneyPlanner.Event> events = Arrays.asList(
        new RigaJourneyPlanner.Event(0, 1, 6000000, 6000000, true, true),
        new RigaJourneyPlanner.Event(1, 2, 6600000, 6600000, true, true),
        new RigaJourneyPlanner.Event(2, 3, 7200000, 7200000, true, true));
    RigaJourneyPlanner.Trip first = new RigaJourneyPlanner.Trip("trip@day", "r", "22", "C", 3, shape, 0, events);
    List<RigaJourneyPlanner.Trip> trips = secondShape == null ? Collections.singletonList(first)
        : Arrays.asList(first, new RigaJourneyPlanner.Trip("other@day", "r", "22", "C", 3, secondShape, 0, events));
    return new RigaJourneyPlanner.Timetable(stops, trips, Collections.emptyList());
  }
  private static RigaJourneyGeometry shape()
  {
    return new RigaJourneyGeometry(Arrays.asList(new RigaJourneyGeometry.Point(56.95, 24.10),
        new RigaJourneyGeometry.Point(56.95, 24.11), new RigaJourneyGeometry.Point(56.95, 24.12)));
  }
  private static List<RigaVehicle> vehicle(String direction, String start, String delay, String lon, long received)
  {
    return RigaVehicle.parse("2,22," + lon + ",56950000,10,90,L,123," + direction + "," + start + "," + delay, received);
  }
  private static Map<Integer, RigaJourneyPlanner.Prediction> match(RigaJourneyPlanner.Timetable data,
                                                                  List<RigaVehicle> vehicles) throws Exception
  {
    return RigaJourneyMatcher.match(data, vehicles, NOW, ignored -> shape(), () -> false);
  }

  @Test public void uniquePublicTripUsesDelayAndSkipsPassedStops() throws Exception
  {
    RigaJourneyPlanner.Timetable data = table("riga_bus_22_a-b", null);
    Map<Integer, RigaJourneyPlanner.Prediction> result = match(data, vehicle("a-b", "100", "60", "24103000", NOW));
    assertEquals(1, result.size());
    assertEquals(60, result.get(0).delaySeconds);
    assertEquals(1, result.get(0).firstBoardableEvent);
    assertEquals("123", result.get(0).vehicleId);
    assertNull(RigaJourneyPlanner.plan(data, "a", "c", NOW, result, () -> false));
    assertEquals(7260000, RigaJourneyPlanner.plan(data, "b", "c", NOW, result, () -> false).arrival);
    // Even if the reported delay places the first departure in the future, GPS progress
    // beyond its platform must prevent boarding there.
    assertEquals(1, match(data, vehicle("a-b", "100", "180", "24103000", NOW)).get(0).firstBoardableEvent);
  }

  @Test public void startMinuteOneOffFromTimetableStillMatchesUniqueTrip() throws Exception
  {
    // saraksti.lv reports bus 3 b-a as 677 while GTFS starts it at 11:18 (678).
    RigaJourneyPlanner.Timetable data = table("riga_bus_22_a-b", null);
    for (String start : Arrays.asList("99", "101"))
    {
      Map<Integer, RigaJourneyPlanner.Prediction> result = match(data, vehicle("a-b", start, "60", "24103000", NOW));
      assertEquals(1, result.size());
      assertEquals("123", result.get(0).vehicleId);
    }
  }

  @Test public void staleAmbiguousWrongDirectionAndOffRouteVehiclesAreScheduledOnly() throws Exception
  {
    RigaJourneyPlanner.Timetable data = table("riga_bus_22_a-b", null);
    assertTrue(match(data, vehicle("a-b", "100", "0", "24103000", NOW - 30001)).isEmpty());
    assertTrue(match(data, vehicle("a-b", "100", "0", "24103000", NOW + 1)).isEmpty());
    assertTrue(match(data, vehicle("b-a", "100", "0", "24103000", NOW)).isEmpty());
    assertTrue(match(data, vehicle("a-b-depot", "100", "0", "24103000", NOW)).isEmpty());
    assertTrue(match(data, vehicle("a-b", "102", "0", "24103000", NOW)).isEmpty());
    assertTrue(match(data, vehicle("a-b", "100", "0", "24500000", NOW)).isEmpty());
    assertTrue(match(data, RigaVehicle.parse("2,22,24103000,56960000,0,0,L,123,a-b,100,0", NOW)).isEmpty());
    assertTrue(match(table("riga_bus_22_a-b", "riga_bus_22_a-b"),
                     vehicle("a-b", "100", "0", "24103000", NOW)).isEmpty());
    List<RigaVehicle> duplicates = RigaVehicle.parse(
        "2,22,24103000,56950000,0,0,L,123,a-b,100,0\n2,22,24103000,56950000,0,0,L,456,a-b,100,0", NOW);
    assertTrue(match(data, duplicates).isEmpty());
    duplicates = RigaVehicle.parse(
        "2,22,24103000,56950000,0,0,L,123,a-b,100,0\n2,22,24103000,56950000,0,0,L,123,a-b,100,0", NOW);
    assertTrue(match(data, duplicates).isEmpty());
    assertTrue(RigaJourneyMatcher.match(data, vehicle("a-b", "100", "0", "24103000", NOW),
                                       NOW, ignored -> null, () -> false).isEmpty());
  }

  @Test public void aVehicleListedForItsFinishedAndItsNextTripMatchesTheNextOne() throws Exception
  {
    // saraksti.lv at 23:16: tram 35032 on both its finished b-a trip and the a-b trip it just started.
    RigaJourneyPlanner.Timetable data = table("riga_bus_22_a-b", null);
    List<RigaVehicle> handover = RigaVehicle.parse(
        "2,22,24100500,56950000,0,0,L,123,b-a,40,-17\n2,22,24103000,56950000,0,0,L,123,a-b,100,54", NOW);
    Map<Integer, RigaJourneyPlanner.Prediction> result = match(data, handover);
    assertEquals(1, result.size());
    assertEquals(54, result.get(0).delaySeconds);
    assertTrue(result.get(0).matches(handover.get(1)));
    assertFalse(result.get(0).matches(handover.get(0)));
    // The same vehicle fitting two trips at once is not trusted for either.
    assertTrue(match(table("riga_bus_22_a-b", "riga_bus_22_c-d"), RigaVehicle.parse(
        "2,22,24103000,56950000,0,0,L,123,a-b,100,0\n2,22,24103000,56950000,0,0,L,123,c-d,100,0", NOW)).isEmpty());
  }

  @Test public void malformedMetadataRetainsMapPositionButCannotPredict() throws Exception
  {
    for (String delay : Arrays.asList("oops", "-301", "7201"))
    {
      List<RigaVehicle> rows = vehicle("a-b", "100", delay, "24103000", NOW);
      assertEquals(1, rows.size());
      assertFalse(rows.get(0).hasTripUpdate);
      assertTrue(match(table("riga_bus_22_a-b", null), rows).isEmpty());
    }
    assertFalse(vehicle("a-b", "4320", "0", "24103000", NOW).get(0).hasTripUpdate);
    assertFalse(RigaVehicle.parse("2,22,24103000,56950000,0,0,L,123", NOW).get(0).hasTripUpdate);
  }

  @Test public void geometryClipsAndConstrainsProgressOnLoops()
  {
    RigaJourneyGeometry geometry = new RigaJourneyGeometry(Arrays.asList(
        new RigaJourneyGeometry.Point(56.95, 24.10), new RigaJourneyGeometry.Point(56.95, 24.11),
        new RigaJourneyGeometry.Point(56.951, 24.11), new RigaJourneyGeometry.Point(56.951, 24.10),
        new RigaJourneyGeometry.Point(56.95, 24.10)));
    double end = geometry.lengths[4];
    RigaJourneyGeometry.Projection first = geometry.project(new RigaJourneyGeometry.Point(56.95, 24.10), 0, 10);
    RigaJourneyGeometry.Projection last = geometry.project(new RigaJourneyGeometry.Point(56.95, 24.10), end - 10, end);
    assertEquals(0, first.along, 0.01);
    assertEquals(end, last.along, 0.01);
    List<RigaJourneyGeometry.Point> clip = geometry.clip(10, end - 10);
    assertEquals(5, clip.size());
    assertTrue(RigaJourneyGeometry.distance(clip.get(0), geometry.points.get(0)) > 9);
  }
}
