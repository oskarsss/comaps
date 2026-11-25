package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** The first walk of a started trip, routed from where the rider is before it begins. */
public class RigaTripStartTest
{
  private static final long MIN = 60000;
  private static final long T = 17 * 60 * MIN;

  private static List<RigaJourneyPlanner.Leg> walkThenBus(long busLeaves)
  {
    RigaJourneyPlanner.Trip bus = new RigaJourneyPlanner.Trip("13@day", "bus13", "13", "Babītes", 3, "", 0,
        Arrays.asList(new RigaJourneyPlanner.Event(0, 1, busLeaves, busLeaves, true, true),
                      new RigaJourneyPlanner.Event(1, 2, busLeaves + 40 * MIN, busLeaves + 40 * MIN, true, true)));
    return Arrays.asList(
        new RigaJourneyPlanner.Leg(2, 0, busLeaves - MIN, busLeaves, null, -1, -1, 9, null),
        new RigaJourneyPlanner.Leg(0, 1, busLeaves, busLeaves + 40 * MIN, bus, 0, 1, 0, null));
  }

  @Test public void aLaterBusIsLeftForJustInTimeFromWhereTheRiderIs()
  {
    // Planned from a point 9 m from the stop; the rider is 20 min away on foot: leave 20 min before the bus.
    long busLeaves = T + 49 * MIN;
    assertEquals(busLeaves - 20 * MIN, RigaJourneyService.leaveFor(walkThenBus(busLeaves), 0, 20 * MIN, T));
  }

  @Test public void aBusThereIsNoLongerTimeForMeansLeavingNow()
  {
    long busLeaves = T + 5 * MIN;
    assertEquals(T, RigaJourneyService.leaveFor(walkThenBus(busLeaves), 0, 20 * MIN, T));
  }

  @Test public void aChangeOfStopOnFootBeforeTheBusIsAlsoAllowedFor()
  {
    // Walk to stop A, then 5 min across to stop B where the bus leaves.
    long busLeaves = T + 49 * MIN;
    List<RigaJourneyPlanner.Leg> bus = walkThenBus(busLeaves);
    List<RigaJourneyPlanner.Leg> legs = Arrays.asList(
        new RigaJourneyPlanner.Leg(3, 2, busLeaves - 10 * MIN, busLeaves - 5 * MIN, null, -1, -1, 300, null),
        new RigaJourneyPlanner.Leg(2, 0, busLeaves - 5 * MIN, busLeaves, null, -1, -1, 350, null), bus.get(1));
    assertEquals(busLeaves - 25 * MIN, RigaJourneyService.leaveFor(legs, 0, 20 * MIN, T));
  }

  @Test public void aPartOnFootAfterAStayKeepsItsStart()
  {
    List<RigaJourneyPlanner.Leg> legs = Arrays.asList(
        new RigaJourneyPlanner.Leg(2, 0, T + 30 * MIN, T + 45 * MIN, null, -1, -1, 1000, null));
    assertEquals(T + 30 * MIN, RigaJourneyService.leaveFor(legs, 0, 15 * MIN, T));
  }
}
