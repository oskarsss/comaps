package app.organicmaps.transit;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class RigaWalksTest
{
  private static final long MIN = 60000;
  private static final long T = 1_795_000_000_000L;
  private static final RigaJourneyPlanner.Trip TROLLEYBUS =
      new RigaJourneyPlanner.Trip("22@day", "r22", "22", "Lubāna iela", 11, "", 0, Collections.emptyList());

  private static RigaJourneyPlanner.Leg walk(int from, int to, long departure, long arrival, int metres)
  {
    return new RigaJourneyPlanner.Leg(from, to, departure, arrival, null, -1, -1, metres, null);
  }

  private static RigaJourneyPlanner.Leg ride(int from, int to, long departure, long arrival)
  {
    return new RigaJourneyPlanner.Leg(from, to, departure, arrival, TROLLEYBUS, 0, 10, 0, null);
  }

  private static RigaJourneyService.Result route(List<RigaJourneyPlanner.Leg> legs)
  {
    double[][] lines = new double[legs.size()][];
    boolean[] walking = new boolean[legs.size()];
    for (int i = 0; i < lines.length; ++i)
    {
      lines[i] = new double[] {i, i, i + 1, i + 1};
      walking[i] = legs.get(i).trip == null;
    }
    return new RigaJourneyService.Result(null, new RigaJourneyPlanner.Journey(T, legs, 1), 0, 0, false, lines,
        walking);
  }

  /** Centrs → Inženieru iela → Merķeļa iela → E.Birznieka-Upīša iela at night, searched at 00:46. */
  @Test public void chainedFootpathsBeforeARideAreOneWalkEndingAtTheBoarding()
  {
    long ride = T + 260 * MIN;
    RigaJourneyService.Result tidied = RigaWalks.tidy(route(Arrays.asList(
        walk(0, 1, T, T + 10 * MIN, 747),
        walk(1, 2, T + 10 * MIN, T + 17 * MIN, 496),
        walk(2, 3, T + 17 * MIN, T + 22 * MIN, 340),
        ride(3, 4, ride, ride + 15 * MIN),
        walk(4, 5, ride + 15 * MIN, ride + 19 * MIN, 280))));
    List<RigaJourneyPlanner.Leg> legs = tidied.journey.legs;
    assertEquals(3, legs.size());
    RigaJourneyPlanner.Leg access = legs.get(0);
    assertNull(access.trip);
    assertEquals(747 + 496 + 340, access.walkingMetres);
    assertEquals(0, access.from);
    assertEquals(3, access.to);
    assertEquals(22 * MIN, access.arrival - access.departure);
    assertEquals(ride, access.arrival);
    assertEquals(RigaJourneyAlternatives.leave(tidied.journey), access.departure);
    assertEquals(ride + 19 * MIN, tidied.journey.arrival);
    assertEquals(ride + 15 * MIN, legs.get(2).departure); // the final walk keeps its times

    // The map line of the walk is the three paths joined, and the per-leg flags still line up.
    assertEquals(3, tidied.lines.length);
    assertArrayEquals(new double[] {0, 0, 1, 1, 2, 2, 3, 3}, tidied.lines[0], 0);
    assertArrayEquals(new boolean[] {true, false, true}, tidied.walking);
  }

  @Test public void transferWalksBetweenRidesAreJoinedButNotMoved()
  {
    RigaJourneyService.Result tidied = RigaWalks.tidy(route(Arrays.asList(
        ride(0, 1, T, T + 5 * MIN),
        walk(1, 2, T + 5 * MIN, T + 7 * MIN, 100),
        walk(2, 3, T + 7 * MIN, T + 8 * MIN, 50),
        ride(3, 4, T + 12 * MIN, T + 20 * MIN))));
    assertEquals(3, tidied.journey.legs.size());
    RigaJourneyPlanner.Leg transfer = tidied.journey.legs.get(1);
    assertEquals(150, transfer.walkingMetres);
    assertEquals(T + 5 * MIN, transfer.departure);
    assertEquals(T + 8 * MIN, transfer.arrival); // the 4 minutes' wait stays at the stop
  }

  @Test public void aWalkOnlyJourneyIsLeftAlone()
  {
    RigaJourneyService.Result route = route(Collections.singletonList(walk(0, 1, T, T + 10 * MIN, 800)));
    assertSame(route, RigaWalks.tidy(route));
  }

  @Test public void anUndrawnRouteStillHasItsWalksJoined()
  {
    RigaJourneyService.Result route = new RigaJourneyService.Result(null, new RigaJourneyPlanner.Journey(T,
        Arrays.asList(walk(0, 1, T, T + 3 * MIN, 200), walk(1, 2, T + 3 * MIN, T + 5 * MIN, 100),
            ride(2, 3, T + 9 * MIN, T + 20 * MIN)), 1), 0, 0, false, new double[0][], new boolean[0]);
    RigaJourneyService.Result tidied = RigaWalks.tidy(route);
    assertEquals(2, tidied.journey.legs.size());
    assertEquals(T + 4 * MIN, tidied.journey.legs.get(0).departure);
    assertEquals(0, tidied.lines.length);
  }

  private static app.organicmaps.sdk.routing.TransitJourneyLeg leg(boolean walk, long departure, long arrival)
  {
    return new app.organicmaps.sdk.routing.TransitJourneyLeg(
        walk ? app.organicmaps.sdk.routing.TransitStepType.PEDESTRIAN : app.organicmaps.sdk.routing.TransitStepType.TRAM,
        walk ? "" : "7", "", 0, walk ? 60 : 0, false, new app.organicmaps.sdk.routing.TransitJourneyLeg.Stop[] {
            new app.organicmaps.sdk.routing.TransitJourneyLeg.Stop("a", departure, departure, 56.9, 24.1),
            new app.organicmaps.sdk.routing.TransitJourneyLeg.Stop("b", arrival, arrival, 56.91, 24.1)});
  }

  /** Under 2 min on foot between two rides is part of the change ("7 › 49"), not a step of its own. */
  @Test public void shortChangeIsAWalkUnderTwoMinutesBetweenTwoRides()
  {
    app.organicmaps.sdk.routing.TransitJourneyLeg[] legs = {
        leg(true, T, T + MIN), leg(false, T + MIN, T + 5 * MIN), leg(true, T + 5 * MIN, T + 6 * MIN),
        leg(false, T + 8 * MIN, T + 12 * MIN), leg(true, T + 12 * MIN, T + 13 * MIN)};
    assertFalse(RigaWalks.shortChange(legs, 0)); // the walk to the first stop
    assertFalse(RigaWalks.shortChange(legs, 1)); // a ride
    assertTrue(RigaWalks.shortChange(legs, 2));
    assertFalse(RigaWalks.shortChange(legs, 4)); // the walk to the destination
    legs[2] = leg(true, T + 5 * MIN, T + 7 * MIN);
    assertFalse(RigaWalks.shortChange(legs, 2)); // 2 min is shown
    legs[2] = leg(true, T + 5 * MIN, T + 8 * MIN);
    assertFalse(RigaWalks.shortChange(legs, 2));
  }
}
