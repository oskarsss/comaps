package app.organicmaps.transit;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class RigaJourneyAlternativesTest
{
  private static final long MIN = 60000;
  private static final long T10 = 10 * 60 * MIN;

  private static RigaJourneyPlanner.Trip trip(String id, String route, int type, long leave, long arrive)
  {
    return new RigaJourneyPlanner.Trip(id + "@day", route, route, "C", type, "", 0, Arrays.asList(
        new RigaJourneyPlanner.Event(0, 1, leave, leave, true, true),
        new RigaJourneyPlanner.Event(2, 2, arrive, arrive, true, true)));
  }

  private static RigaJourneyPlanner.Timetable table(RigaJourneyPlanner.Trip... trips)
  {
    List<RigaJourneyPlanner.Stop> stops = Arrays.asList(
        new RigaJourneyPlanner.Stop("a", "A", "", 56.95, 24.10),
        new RigaJourneyPlanner.Stop("b", "B", "", 56.95, 24.11),
        new RigaJourneyPlanner.Stop("c", "C", "", 56.95, 24.12));
    return new RigaJourneyPlanner.Timetable(stops, Arrays.asList(trips), Collections.emptyList());
  }

  private static RigaJourneyAlternatives.Planner planner(RigaJourneyPlanner.Timetable data)
  {
    return (departure, options) -> RigaJourneyPlanner.plan(data, "a", "c", departure, Collections.emptyMap(),
        () -> false, RigaJourneyPlanner.DEFAULT_LIMITS, true, options);
  }

  @Test public void anEarlierStartArrivingAtTheSameTimeGivesWayToTheLaterOne()
  {
    // Both arrive at 10:20: the 10:00 tram only makes the rider leave earlier.
    RigaJourneyPlanner.Timetable data = table(trip("early", "tram1", 0, T10, T10 + 20 * MIN),
        trip("late", "trol2", 11, T10 + 5 * MIN, T10 + 20 * MIN));
    RigaJourneyAlternatives.Planner planner = planner(data);
    RigaJourneyPlanner.Journey early = planner.plan(T10 - MIN, RigaJourneyPlanner.Options.NONE);
    RigaJourneyPlanner.Journey late = planner.plan(T10 + MIN, RigaJourneyPlanner.Options.NONE);
    List<RigaJourneyPlanner.Journey> kept = RigaJourneyAlternatives.undominated(Arrays.asList(early, late));
    assertEquals(1, kept.size());
    assertSame(late, kept.get(0));
    List<RigaJourneyPlanner.Journey> sorted = new ArrayList<>(Arrays.asList(early, late));
    sorted.sort(RigaJourneyAlternatives.ORDER);
    assertSame(late, sorted.get(0));
  }

  @Test public void secondsEarlierInTheSameMinuteDoNotOutrankLeavingLater()
  {
    RigaJourneyPlanner.Timetable data = table(trip("early", "tram1", 0, T10, T10 + 20 * MIN - 30000),
        trip("late", "trol2", 11, T10 + 5 * MIN, T10 + 20 * MIN));
    RigaJourneyAlternatives.Planner planner = planner(data);
    RigaJourneyPlanner.Journey early = planner.plan(T10 - MIN, RigaJourneyPlanner.Options.NONE);
    RigaJourneyPlanner.Journey late = planner.plan(T10 + MIN, RigaJourneyPlanner.Options.NONE);
    List<RigaJourneyPlanner.Journey> sorted = new ArrayList<>(Arrays.asList(early, late));
    sorted.sort(RigaJourneyAlternatives.ORDER);
    assertSame(late, sorted.get(0));
  }

  @Test public void excludedLinesAndBoardingCapRestrictThePlanner()
  {
    RigaJourneyPlanner.Timetable data = table(trip("tram", "tram1", 0, T10, T10 + 10 * MIN),
        trip("trol", "trol2", 11, T10 + 2 * MIN, T10 + 17 * MIN));
    RigaJourneyPlanner.Journey best = planner(data).plan(T10 - MIN, RigaJourneyPlanner.Options.NONE);
    assertEquals("tram1", best.legs.get(0).trip.routeId);
    RigaJourneyPlanner.Journey other = planner(data).plan(T10 - MIN,
        new RigaJourneyPlanner.Options(Collections.singleton("tram1"), 4));
    assertEquals("trol2", other.legs.get(0).trip.routeId);
    assertNull(planner(data).plan(T10 - MIN,
        new RigaJourneyPlanner.Options(new java.util.HashSet<>(Arrays.asList("tram1", "trol2")), 4)));
  }

  @Test public void offersOtherLinesWithinSlackThenNextDepartures()
  {
    RigaJourneyPlanner.Timetable data = table(
        trip("tram-a", "tram1", 0, T10, T10 + 10 * MIN),
        trip("tram-b", "tram1", 0, T10 + 10 * MIN, T10 + 20 * MIN),
        trip("tram-c", "tram1", 0, T10 + 20 * MIN, T10 + 30 * MIN),
        trip("trol", "trol2", 11, T10 + 2 * MIN, T10 + 17 * MIN),
        // Far slower than the tram: not worth offering as an "other line".
        trip("bus", "bus3", 3, T10, T10 + 50 * MIN));
    RigaJourneyAlternatives.Planner planner = planner(data);
    RigaJourneyPlanner.Journey best = planner.plan(T10 - MIN, RigaJourneyPlanner.Options.NONE);
    List<RigaJourneyPlanner.Journey> chosen = RigaJourneyAlternatives.choose(best, T10 - MIN, 5, planner);
    List<String> trips = new ArrayList<>();
    for (RigaJourneyPlanner.Journey journey : chosen) trips.add(journey.legs.get(0).trip.id);
    assertEquals(Arrays.asList("tram-a@day", "trol@day", "tram-b@day", "tram-c@day"), trips);
    // A larger count (the "Later" button) only appends; it never drops what was offered.
    assertEquals(4, RigaJourneyAlternatives.choose(best, T10 - MIN, 8, planner).size());
    assertEquals(T10, RigaJourneyAlternatives.leave(best));
  }

  @Test public void sameRidesAreOfferedOnce()
  {
    RigaJourneyPlanner.Timetable data = table(trip("tram-a", "tram1", 0, T10, T10 + 10 * MIN));
    RigaJourneyAlternatives.Planner planner = planner(data);
    RigaJourneyPlanner.Journey best = planner.plan(T10 - MIN, RigaJourneyPlanner.Options.NONE);
    assertEquals(1, RigaJourneyAlternatives.choose(best, T10 - MIN, 5, planner).size());
  }

  @Test public void arriveByFindsTheLastDepartureThatStillMakesIt()
  {
    // Trams every 10 min taking 10 min; must arrive by 10:45. The 10:30 tram (arrives 10:40) is the last.
    List<RigaJourneyPlanner.Trip> trips = new ArrayList<>();
    for (int i = 0; i < 12; ++i)
      trips.add(trip("tram-" + i, "tram1", 0, T10 - 60 * MIN + i * 10 * MIN, T10 - 50 * MIN + i * 10 * MIN));
    RigaJourneyPlanner.Timetable data = table(trips.toArray(new RigaJourneyPlanner.Trip[0]));
    int[] calls = {0};
    RigaJourneyAlternatives.Planner counting = (departure, options) -> { ++calls[0]; return planner(data).plan(departure, options); };
    long arriveBy = T10 + 45 * MIN;
    RigaJourneyPlanner.Journey latest = RigaJourneyAlternatives.latest(counting, T10 - 120 * MIN, arriveBy, arriveBy,
        RigaJourneyPlanner.Options.NONE);
    assertEquals(T10 + 30 * MIN, RigaJourneyAlternatives.leave(latest));
    assertTrue("halving, not stepping: " + calls[0], calls[0] <= 9);

    List<RigaJourneyPlanner.Journey> chosen = RigaJourneyAlternatives.chooseArriveBy(T10 - 120 * MIN, arriveBy, 3,
        planner(data));
    List<Long> leaves = new ArrayList<>();
    for (RigaJourneyPlanner.Journey journey : chosen) leaves.add(RigaJourneyAlternatives.leave(journey));
    assertEquals(Arrays.asList(T10 + 30 * MIN, T10 + 20 * MIN, T10 + 10 * MIN), leaves);
  }

  @Test public void arriveByWithNothingInTimeIsEmpty()
  {
    RigaJourneyPlanner.Timetable data = table(trip("tram-a", "tram1", 0, T10, T10 + 10 * MIN));
    assertTrue(RigaJourneyAlternatives.chooseArriveBy(T10 - 60 * MIN, T10 + 5 * MIN, 3, planner(data)).isEmpty());
  }

  @Test public void offersLessWalkingWhenTheFastestWalksALot()
  {
    // Fastest: walk 900 m from A to B, then a quick tram B→C. Gentler: a slower bus straight from A.
    List<RigaJourneyPlanner.Stop> stops = Arrays.asList(
        new RigaJourneyPlanner.Stop("a", "A", "", 56.95, 24.10),
        new RigaJourneyPlanner.Stop("b", "B", "", 56.95, 24.11),
        new RigaJourneyPlanner.Stop("c", "C", "", 56.95, 24.12));
    RigaJourneyPlanner.Trip tram = new RigaJourneyPlanner.Trip("tram@day", "tram1", "1", "C", 0, "", 0, Arrays.asList(
        new RigaJourneyPlanner.Event(1, 1, T10 + 12 * MIN, T10 + 12 * MIN, true, true),
        new RigaJourneyPlanner.Event(2, 2, T10 + 16 * MIN, T10 + 16 * MIN, true, true)));
    RigaJourneyPlanner.Trip bus = new RigaJourneyPlanner.Trip("bus@day", "bus2", "2", "C", 3, "", 0, Arrays.asList(
        new RigaJourneyPlanner.Event(0, 1, T10 + 5 * MIN, T10 + 5 * MIN, true, true),
        new RigaJourneyPlanner.Event(2, 2, T10 + 25 * MIN, T10 + 25 * MIN, true, true)));
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(stops, Arrays.asList(tram, bus),
        Collections.singletonList(new RigaJourneyPlanner.Footpath(0, 1, 600, 900)));
    RigaJourneyAlternatives.Planner planner = planner(data);
    RigaJourneyPlanner.Journey best = planner.plan(T10, RigaJourneyPlanner.Options.NONE);
    assertEquals("tram1", best.legs.get(best.legs.size() - 1).trip.routeId);
    assertEquals(900, RigaJourneyAlternatives.walking(best));
    List<RigaJourneyPlanner.Journey> chosen = RigaJourneyAlternatives.choose(best, T10, 2, planner);
    assertEquals(2, chosen.size());
    assertEquals(0, RigaJourneyAlternatives.walking(chosen.get(1)));
  }

  private static RigaJourneyPlanner.Leg ride(RigaJourneyPlanner.Trip trip, int first, int last)
  {
    return new RigaJourneyPlanner.Leg(trip.events.get(first).stop, trip.events.get(last).stop,
        trip.events.get(first).departure, trip.events.get(last).arrival, trip, first, last, 0, null);
  }

  private static RigaJourneyPlanner.Leg walk(int from, int to, long leave, long arrive)
  {
    return new RigaJourneyPlanner.Leg(from, to, leave, arrive, null, -1, -1, (int) ((arrive - leave) / 1000), null);
  }

  @Test public void aDirectRideBeatsAChangeArrivingAMinuteOrTwoEarlier()
  {
    RigaJourneyPlanner.Trip direct = trip("direct", "bus34", 3, T10, T10 + 22 * MIN);
    RigaJourneyPlanner.Trip first = trip("first", "bus7", 3, T10, T10 + 8 * MIN);
    RigaJourneyPlanner.Trip second = trip("second", "tram1", 0, T10 + 10 * MIN, T10 + 20 * MIN);
    RigaJourneyPlanner.Journey one = new RigaJourneyPlanner.Journey(T10, Collections.singletonList(ride(direct, 0, 1)), 1);
    RigaJourneyPlanner.Journey change = new RigaJourneyPlanner.Journey(T10,
        Arrays.asList(ride(first, 0, 1), ride(second, 0, 1)), 2);
    List<RigaJourneyPlanner.Journey> sorted = new ArrayList<>(Arrays.asList(change, one));
    sorted.sort(RigaJourneyAlternatives.ORDER);
    assertSame(one, sorted.get(0));
    // Five minutes saved is worth the change.
    RigaJourneyPlanner.Trip slow = trip("slow", "bus34", 3, T10, T10 + 25 * MIN);
    RigaJourneyPlanner.Journey slower = new RigaJourneyPlanner.Journey(T10, Collections.singletonList(ride(slow, 0, 1)), 1);
    sorted = new ArrayList<>(Arrays.asList(slower, change));
    sorted.sort(RigaJourneyAlternatives.ORDER);
    assertSame(change, sorted.get(0));
  }

  @Test public void theSameVehiclesBoardedAtAnotherStopAreOneJourneyLeavingLater()
  {
    // Bus 34 stops at A (10:00), B (10:05) and C (10:20); B is closer to the start, so leaving at 10:02 works.
    RigaJourneyPlanner.Trip bus = new RigaJourneyPlanner.Trip("bus@day", "bus34", "34", "C", 3, "", 0, Arrays.asList(
        new RigaJourneyPlanner.Event(0, 1, T10, T10, true, true),
        new RigaJourneyPlanner.Event(1, 2, T10 + 5 * MIN, T10 + 5 * MIN, true, true),
        new RigaJourneyPlanner.Event(2, 3, T10 + 20 * MIN, T10 + 20 * MIN, true, true)));
    RigaJourneyPlanner.Journey early = new RigaJourneyPlanner.Journey(T10 - 10 * MIN, Arrays.asList(
        walk(3, 0, T10 - 10 * MIN, T10 - MIN), ride(bus, 0, 2)), 1);
    RigaJourneyPlanner.Journey late = new RigaJourneyPlanner.Journey(T10 - 10 * MIN, Arrays.asList(
        walk(3, 1, T10 + 2 * MIN, T10 + 5 * MIN), ride(bus, 1, 2)), 1);
    assertEquals(RigaJourneyAlternatives.signature(early), RigaJourneyAlternatives.signature(late));
    RigaJourneyAlternatives.Planner planner = (departure, options) ->
        options.excludedRoutes.isEmpty() && departure <= T10 + 2 * MIN ? late : null;
    List<RigaJourneyPlanner.Journey> chosen = RigaJourneyAlternatives.choose(early, T10 - 10 * MIN, 5, planner);
    assertEquals(1, chosen.size());
    assertSame(late, chosen.get(0));
  }
}
