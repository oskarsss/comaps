package app.organicmaps.transit;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RigaNextVehicleTest
{
  private static final long NOW = 6120000;
  // About 1.2 km of road from A (24.10) to B (24.12), and the same way back.
  private final RigaJourneyGeometry there = new RigaJourneyGeometry(Arrays.asList(
      new RigaJourneyGeometry.Point(56.95, 24.10), new RigaJourneyGeometry.Point(56.95, 24.12)));
  private final RigaJourneyGeometry back = new RigaJourneyGeometry(Arrays.asList(
      new RigaJourneyGeometry.Point(56.95, 24.12), new RigaJourneyGeometry.Point(56.95, 24.10)));

  private String next(double boardAlong, long departureIn, String feed)
  {
    return RigaNextVehicle.find("riga_bus_22", there, back, boardAlong, RigaVehicle.parse(feed, NOW),
                                java.util.Collections.emptySet(), NOW + departureIn, NOW);
  }

  @Test public void theVehicleExpectedAtTheStopAtDepartureIsChosen()
  {
    // 17529 is 120 s from the stop, 17600 about 360 s: for a ride in 6 minutes it is 17600.
    String feed = "2,22,24108000,56950000,0,0,L,17529,a-b,1100,0\n2,22,24101000,56950000,0,0,L,17600,a-b,1110,0";
    double stop = 900;
    assertEquals("17529", next(stop, 120000, feed));
    assertEquals("17600", next(stop, 360000, feed));
  }

  @Test public void aVehicleWaitingAtTheTerminusIsThereWhenTheRideLeaves()
  {
    // 17812 waits at A still reporting its previous trip; 17529 is on its way back, 10 minutes off.
    String feed = "2,22,24100500,56950000,0,0,L,17812,b-a,1080,0\n2,22,24118000,56950000,0,0,L,17529,b-a,1090,0";
    assertEquals("17812", next(0, 240000, feed));
  }

  @Test public void aVehicleComingBackServesALaterDeparture()
  {
    // On its way back, 17529 needs ~(1100 m back + 300 m) / 5 m/s = 280 s to reach the stop.
    String feed = "2,22,24118000,56950000,0,0,L,17529,b-a,1090,0";
    assertEquals("17529", next(300, 300000, feed));
  }

  @Test public void aVehicleOnItsWayToTheDepotTakesNoDeparture()
  {
    // Trolleybus 15 at 21:52 (2026-11-24): 17758 ran "b-d1" past the first stop to the depot, right by the
    // boarding stop; the ride goes with 17671, still coming back on "b-a".
    String feed = "2,22,24103500,56950000,0,0,L,17758,b-d1,1283,0\n2,22,24118000,56950000,0,0,L,17671,b-a,1276,600";
    assertEquals("17671", next(300, 0, feed));
  }

  @Test public void otherLinesAndVehiclesPastTheStopAreIgnored()
  {
    String feed = "2,23,24105000,56950000,0,0,L,17000,a-b,1100,0\n1,22,24105000,56950000,0,0,L,17001,a-b,1100,0";
    assertNull(next(900, 60000, feed));
    assertTrue(RigaNextVehicle.reversed("riga_trol_15_a-b").equals("riga_trol_15_b-a"));
  }

  // Line 22 between terminus A (24.10) and B (24.12): departures from A at 19:55, 20:05, 20:15 (service day from 0).
  private static RigaJourneyPlanner.Trip trip(String shape, int stop0, int stop1, int startMinutes)
  {
    long t = startMinutes * 60000L;
    return new RigaJourneyPlanner.Trip(shape + startMinutes, "r", "22", "", 3, "riga_bus_22_" + shape, 0,
        Arrays.asList(new RigaJourneyPlanner.Event(stop0, 1, t, t, true, true),
                      new RigaJourneyPlanner.Event(stop1, 2, t + 600000, t + 600000, true, true)));
  }

  @Test public void theTerminusQueueGivesEachDepartureTheNextVehicleToArrive()
  {
    List<RigaJourneyPlanner.Stop> stops = Arrays.asList(new RigaJourneyPlanner.Stop("a", "A", "", 56.95, 24.10),
                                                        new RigaJourneyPlanner.Stop("b", "B", "", 56.95, 24.12));
    List<RigaJourneyPlanner.Trip> trips = Arrays.asList(trip("a-b", 0, 1, 1195), trip("a-b", 0, 1, 1205),
        trip("a-b", 0, 1, 1215), trip("b-a", 1, 0, 1190), trip("b-a", 1, 0, 1198));
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(stops, trips, java.util.Collections.emptyList());
    long now = 1192 * 60000L;
    // 17812 waits at A, still listing the 19:30 trip it came in on (not in the timetable); 17529 runs the
    // 19:50 b-a and 17600 the 19:58 b-a, both on their way to A.
    String feed = "2,22,24100500,56950000,0,0,L,17812,b-a,1170,0\n"
                + "2,22,24110000,56950000,0,0,L,17529,b-a,1190,0\n"
                + "2,22,24119000,56950000,0,0,L,17600,b-a,1198,0";
    List<RigaVehicle> vehicles = RigaVehicle.parse(feed, now);
    java.util.Map<String, RigaJourneyPlanner.Trip> listed = RigaNextVehicle.listed(trips, vehicles, now);
    assertEquals("17812", RigaNextVehicle.queue(data, trips.get(0), trips, listed, vehicles, java.util.Collections.emptyList(), now));
    assertEquals("17529", RigaNextVehicle.queue(data, trips.get(1), trips, listed, vehicles, java.util.Collections.emptyList(), now));
    assertEquals("17600", RigaNextVehicle.queue(data, trips.get(2), trips, listed, vehicles, java.util.Collections.emptyList(), now));
  }

  @Test public void aBusThatSwitchedOffAtTheTerminusIsStillWaitingThere()
  {
    List<RigaJourneyPlanner.Stop> stops = Arrays.asList(new RigaJourneyPlanner.Stop("a", "A", "", 56.95, 24.10),
                                                        new RigaJourneyPlanner.Stop("b", "B", "", 56.95, 24.12));
    List<RigaJourneyPlanner.Trip> trips = Arrays.asList(trip("a-b", 0, 1, 1195), trip("b-a", 1, 0, 1180));
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(stops, trips, java.util.Collections.emptyList());
    long now = 1193 * 60000L;
    // 78539 came in on the 19:40 and dropped out of the feed 5 minutes ago; 78600 is 2 km off, the other way.
    List<RigaVehicle> seen = RigaVehicle.parse("2,22,24100500,56950000,0,0,L,78539,b-a,1180,0", now - 300000);
    List<RigaVehicle> vehicles = RigaVehicle.parse("2,22,24119000,56951000,0,0,L,78600,a-b,1150,0", now);
    java.util.Map<String, RigaJourneyPlanner.Trip> listed = RigaNextVehicle.listed(trips, vehicles, now);
    assertEquals("78539", RigaNextVehicle.queue(data, trips.get(0), trips, listed, vehicles, seen, now));
  }
}
