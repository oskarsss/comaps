package app.organicmaps.transit;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RigaJourneyRetimerTest
{
  private static final long MIN = 60000;
  private static final long T = 10 * 60 * MIN;

  private static RigaJourneyPlanner.Trip trip(String id, long leave, long arrive)
  {
    return new RigaJourneyPlanner.Trip(id, id, id, "End", 3, "", 0, Arrays.asList(
        new RigaJourneyPlanner.Event(0, 1, leave, leave, true, true),
        new RigaJourneyPlanner.Event(1, 2, (leave + arrive) / 2, (leave + arrive) / 2, true, true),
        new RigaJourneyPlanner.Event(2, 3, arrive, arrive, true, true)));
  }

  private static RigaJourneyPlanner.Leg walk(long from, long to)
  {
    return new RigaJourneyPlanner.Leg(0, 0, from, to, null, 0, 0, 100, null);
  }

  private static RigaJourneyPlanner.Leg ride(RigaJourneyPlanner.Trip trip)
  {
    return new RigaJourneyPlanner.Leg(0, 2, trip.events.get(0).departure, trip.events.get(2).arrival, trip, 0, 2,
        0, null);
  }

  /** walk 3 min → bus A 10 min → walk 2 min → bus B (leaves 3 min after A arrives) → walk 1 min. */
  private static RigaJourneyPlanner.Journey journey(RigaJourneyPlanner.Trip a, RigaJourneyPlanner.Trip b)
  {
    long aArrive = a.events.get(2).arrival;
    return new RigaJourneyPlanner.Journey(T - 5 * MIN, Arrays.asList(walk(T - 3 * MIN, T), ride(a),
        walk(aArrive, aArrive + 2 * MIN), ride(b), walk(b.events.get(2).arrival, b.events.get(2).arrival + MIN)), 2);
  }

  @Test public void liveDelayMovesTheRideAndTheWalksAroundItWithoutChangingVehicles()
  {
    RigaJourneyPlanner.Trip a = trip("a", T, T + 10 * MIN), b = trip("b", T + 13 * MIN, T + 20 * MIN);
    List<RigaJourneyPlanner.Trip> trips = Arrays.asList(b, a);
    Map<Integer, RigaJourneyPlanner.Prediction> live = Collections.singletonMap(1,
        new RigaJourneyPlanner.Prediction(60, 0, "1234", T));
    RigaJourneyRetimer.Retimed retimed = RigaJourneyRetimer.retime(trips, journey(a, b), live, T);
    assertEquals(-1, retimed.missedLeg);
    List<RigaJourneyPlanner.Leg> legs = retimed.journey.legs;
    assertEquals(T - 2 * MIN, legs.get(0).departure); // leave a minute later
    assertEquals(T + MIN, legs.get(1).departure);
    assertSame(a, legs.get(1).trip);
    assertEquals("1234", legs.get(1).prediction.vehicleId);
    assertEquals(T + 11 * MIN, legs.get(2).departure);
    assertEquals(T + 13 * MIN, legs.get(3).departure); // B has no estimate: timetable
    assertNull(legs.get(3).prediction);
    assertEquals(T + 21 * MIN, retimed.journey.arrival);
  }

  @Test public void lateFirstRideReportsTheTransferThatCanNoLongerBeMade()
  {
    RigaJourneyPlanner.Trip a = trip("a", T, T + 10 * MIN), b = trip("b", T + 13 * MIN, T + 20 * MIN);
    Map<Integer, RigaJourneyPlanner.Prediction> live = Collections.singletonMap(0,
        new RigaJourneyPlanner.Prediction(120, 0, "1234", T));
    RigaJourneyRetimer.Retimed retimed = RigaJourneyRetimer.retime(Arrays.asList(a, b), journey(a, b), live, T);
    assertEquals(3, retimed.missedLeg);
  }

  @Test public void staleEstimatesAreIgnoredButARecentOneOnTheLegIsKept()
  {
    RigaJourneyPlanner.Trip a = trip("a", T, T + 10 * MIN), b = trip("b", T + 13 * MIN, T + 20 * MIN);
    Map<Integer, RigaJourneyPlanner.Prediction> stale = Collections.singletonMap(0,
        new RigaJourneyPlanner.Prediction(60, 0, "1234", T - 5 * MIN));
    RigaJourneyRetimer.Retimed retimed = RigaJourneyRetimer.retime(Arrays.asList(a, b), journey(a, b), stale, T);
    assertEquals(T, retimed.journey.legs.get(1).departure);
    // Next refresh, the matcher lost the vehicle: the estimate from a minute ago still applies.
    RigaJourneyRetimer.Retimed live = RigaJourneyRetimer.retime(Arrays.asList(a, b), journey(a, b),
        Collections.singletonMap(0, new RigaJourneyPlanner.Prediction(60, 0, "1234", T)), T);
    RigaJourneyRetimer.Retimed kept = RigaJourneyRetimer.retime(Arrays.asList(a, b), live.journey,
        Collections.emptyMap(), T + MIN);
    assertEquals(T + MIN, kept.journey.legs.get(1).departure);
  }
}
