package app.organicmaps.transit;

import static org.junit.Assert.*;

import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import app.organicmaps.sdk.routing.TransitStepInfo;
import app.organicmaps.sdk.routing.TransitStepType;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class RigaMultiStopTest
{
  private static final long MIN = 60000;
  private static final long T = 1_800_000_000_000L;

  private static TransitJourneyLeg.Stop stop(String name, long time, double lat)
  {
    return new TransitJourneyLeg.Stop(name, time, time, lat, 24.10);
  }

  private static TransitJourneyLeg walk(String from, long start, double fromLat, String to, long end, double toLat)
  {
    return new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 200, false,
        new TransitJourneyLeg.Stop[] {stop(from, start, fromLat), stop(to, end, toLat)});
  }

  private static TransitJourneyLeg bus(long start, double fromLat, long end, double toLat)
  {
    return new TransitJourneyLeg(TransitStepType.BUS, "45", "Centre", 0xff0000ff, 0, false,
        new TransitJourneyLeg.Stop[] {stop("A", start, fromLat), stop("B", (start + end) / 2, (fromLat + toLat) / 2),
            stop("C", end, toLat)});
  }

  /**
   * Home → (walk, bus 45, walk) → Rimi, stay 30 min, → (walk 10 min) → Work.
   * Part 1: T-5..T+15; stay T+15..T+45; part 2: T+45..T+55.
   */
  private static TransitJourneyLeg[] journey()
  {
    RoutingController.TransitResult first = part(T + 15 * MIN,
        walk("Home", T - 5 * MIN, 56.900, "A", T, 56.903), bus(T, 56.903, T + 12 * MIN, 56.940),
        walk("C", T + 12 * MIN, 56.940, "Rimi", T + 15 * MIN, 56.942));
    RoutingController.TransitResult second = part(T + 55 * MIN,
        walk("Rimi", T + 45 * MIN, 56.942, "Work", T + 55 * MIN, 56.950));
    RoutingController.TransitResult merged = RigaMultiStop.merge(Arrays.asList(first, second),
        Collections.singletonList(new RigaMultiStop.Via("Rimi", 56.942, 24.10, 30 * MIN)));
    return merged.info.journeyLegs;
  }

  private static RoutingController.TransitResult part(long arrival, TransitJourneyLeg... legs)
  {
    TransitStepInfo[] steps = new TransitStepInfo[legs.length];
    double[][] lines = new double[legs.length][];
    boolean[] walking = new boolean[legs.length];
    for (int i = 0; i < legs.length; ++i)
    {
      steps[i] = new TransitStepInfo(legs[i].type.ordinal(), "", "", 60, legs[i].number, legs[i].color, -1);
      lines[i] = new double[] {legs[i].stops[0].lat, 24.10, legs[i].stops[legs[i].stops.length - 1].lat, 24.10};
      walking[i] = legs[i].isWalking();
    }
    TransitRouteInfo info = new TransitRouteInfo("", "", 0, "200", "m", 300, steps);
    info.journeyLegs = legs;
    info.arrival = arrival;
    info.firstDeparture = legs[0].stops[0].departure;
    RoutingController.TransitResult result = new RoutingController.TransitResult(info, lines, walking);
    result.colors = new int[legs.length];
    return result;
  }

  @Test public void partsAreJoinedWithAStayFromArrivalToTheNextDeparture()
  {
    RoutingController.TransitResult first = part(T + 15 * MIN,
        walk("Home", T - 5 * MIN, 56.900, "Rimi", T + 15 * MIN, 56.942));
    RoutingController.TransitResult second = part(T + 55 * MIN,
        walk("Rimi", T + 45 * MIN, 56.942, "Work", T + 55 * MIN, 56.950));
    RoutingController.TransitResult merged = RigaMultiStop.merge(Arrays.asList(first, second),
        Collections.singletonList(new RigaMultiStop.Via("Rimi", 56.942, 24.10, 30 * MIN)));
    TransitJourneyLeg[] legs = merged.info.journeyLegs;
    assertEquals(3, legs.length);
    assertTrue(legs[1].isStay());
    assertFalse(legs[1].isRide());
    assertEquals("Rimi", legs[1].headsign);
    assertEquals(T + 15 * MIN, legs[1].stops[0].arrival);
    assertEquals(T + 45 * MIN, legs[1].stops[1].departure);
    assertEquals(3, merged.lines.length);
    assertEquals(3, merged.walking.length);
    assertEquals(3, merged.colors.length);
    assertEquals(3, merged.info.getTransitSteps().size());
    assertEquals(T + 55 * MIN, merged.info.arrival);
    // Door to door: from leaving home to arriving at work, the stay included.
    assertEquals(60 * 60, merged.info.getTotalTime());
    assertEquals(600, merged.info.getTotalPedestrianTimeInSec());
  }

  @Test public void partsMustMatchTheStops()
  {
    RoutingController.TransitResult only = part(T, walk("Home", T - 5 * MIN, 56.900, "Work", T, 56.950));
    assertThrows(IllegalArgumentException.class, () -> RigaMultiStop.merge(Collections.singletonList(only),
        Collections.singletonList(new RigaMultiStop.Via("Rimi", 56.942, 24.10, 30 * MIN))));
  }

  @Test public void atTheStopTheRiderStaysAndIsRemindedBeforeLeaving()
  {
    TransitJourneyLeg[] legs = journey();
    assertEquals(5, legs.length);
    RigaTripGuide.State staying = RigaTripGuide.of(legs, T + 25 * MIN, 56.942, 24.10, 3);
    assertEquals(RigaTripGuide.Phase.STAY, staying.phase);
    assertEquals(3, staying.leg);
    assertEquals(T + 45 * MIN, staying.at);
    assertNull(staying.alert);
    RigaTripGuide.State soon = RigaTripGuide.of(legs, T + 41 * MIN, 56.942, 24.10, 3);
    assertEquals(RigaTripGuide.Phase.STAY, soon.phase);
    assertEquals("stay:3", soon.alert);
    // Without a position the clock alone keeps the rider at the stop.
    assertEquals(RigaTripGuide.Phase.STAY, RigaTripGuide.of(legs, T + 30 * MIN, Double.NaN, Double.NaN, 0).phase);
  }

  @Test public void theStayBeginsOnArrivalEvenAheadOfTheClock()
  {
    RigaTripGuide.State early = RigaTripGuide.of(journey(), T + 13 * MIN, 56.942, 24.10, 2);
    assertEquals(RigaTripGuide.Phase.STAY, early.phase);
    assertEquals(3, early.leg);
  }

  @Test public void stillWalkingToTheStopWhenTheStayShouldHaveBegun()
  {
    // Slower than planned (a detour off the bus line): 900 m short of Rimi when the stay was due to begin.
    RigaTripGuide.State late = RigaTripGuide.of(journey(), T + 17 * MIN, 56.941, 24.115, 2);
    assertEquals(RigaTripGuide.Phase.WALK, late.phase);
    assertEquals(2, late.leg);
  }

  @Test public void afterTheStayTheRiderWalksOn()
  {
    RigaTripGuide.State on = RigaTripGuide.of(journey(), T + 47 * MIN, 56.944, 24.10, 3);
    assertEquals(RigaTripGuide.Phase.WALK, on.phase);
    assertEquals(4, on.leg);
    assertEquals(T + 55 * MIN, on.at);
  }

  @Test public void aStayIsNeverARideOnTheProgressBar()
  {
    TransitJourneyLeg[] legs = journey();
    RigaTripGuide.State staying = RigaTripGuide.of(legs, T + 25 * MIN, 56.942, 24.10, 3);
    RigaTripProgress progress = RigaTripProgress.of(legs, staying, T + 25 * MIN);
    // The bus is the only ride: boarding and alighting points.
    assertEquals(2, progress.points.size());
  }

  @Test public void aChosenStayIsKeptWithinItsLimits()
  {
    assertEquals(RigaStays.MIN, RigaStays.clamp(MIN));
    assertEquals(RigaStays.MAX, RigaStays.clamp(10 * 3600000L));
    assertEquals(45 * MIN, RigaStays.clamp(45 * MIN));
    assertEquals(45 * MIN, RigaMultiStop.leaveAfter(15 * MIN, new RigaMultiStop.Via("Rimi", 0, 0, 30 * MIN)));
  }

  @Test public void theFirstDepartureIsTheFirstRideOfAnyPart()
  {
    RoutingController.TransitResult first = part(T + 15 * MIN,
        walk("Home", T - 5 * MIN, 56.900, "Rimi", T + 15 * MIN, 56.942));
    RoutingController.TransitResult second = part(T + 70 * MIN,
        walk("Rimi", T + 45 * MIN, 56.942, "A", T + 50 * MIN, 56.943), bus(T + 52 * MIN, 56.943, T + 70 * MIN, 56.99));
    RoutingController.TransitResult merged = RigaMultiStop.merge(Arrays.asList(first, second),
        Collections.singletonList(new RigaMultiStop.Via("Rimi", 56.942, 24.10, 30 * MIN)));
    assertEquals(T + 52 * MIN, merged.info.firstDeparture);
    // The trip is followed from setting off on foot, not from that ride (Start is offered by when one sets off).
    assertEquals(T - 5 * MIN, merged.info.leaves());
  }

  @Test public void aRoundTripDoesNotEndWhereItStarts()
  {
    // Home → Rimi → Home: at home before setting off is not having arrived.
    RoutingController.TransitResult out = part(T + 15 * MIN,
        walk("Home", T, 56.900, "Rimi", T + 15 * MIN, 56.942));
    RoutingController.TransitResult back = part(T + 60 * MIN,
        walk("Rimi", T + 45 * MIN, 56.942, "Home", T + 60 * MIN, 56.900));
    TransitJourneyLeg[] legs = RigaMultiStop.merge(Arrays.asList(out, back),
        Collections.singletonList(new RigaMultiStop.Via("Rimi", 56.942, 24.10, 30 * MIN))).info.journeyLegs;
    assertNotEquals(RigaTripGuide.Phase.ARRIVED, RigaTripGuide.of(legs, T - MIN, 56.900, 24.10, 0).phase);
    // Back home after the stay: arrived.
    assertEquals(RigaTripGuide.Phase.ARRIVED, RigaTripGuide.of(legs, T + 58 * MIN, 56.900, 24.10, 2).phase);
  }

  @Test public void theNextRideIsNotTakenWhileTheStayLasts()
  {
    // The stop lies on the next part's bus line, past its boarding stop.
    RoutingController.TransitResult first = part(T + 15 * MIN,
        walk("Home", T - 5 * MIN, 56.900, "Rimi", T + 15 * MIN, 56.942));
    RoutingController.TransitResult second = part(T + 70 * MIN,
        walk("Rimi", T + 45 * MIN, 56.942, "A", T + 50 * MIN, 56.930), bus(T + 50 * MIN, 56.930, T + 70 * MIN, 56.99));
    TransitJourneyLeg[] legs = RigaMultiStop.merge(Arrays.asList(first, second),
        Collections.singletonList(new RigaMultiStop.Via("Rimi", 56.942, 24.10, 30 * MIN))).info.journeyLegs;
    RigaTripGuide.State state = RigaTripGuide.of(legs, T + 35 * MIN, 56.942, 24.10, 1);
    assertEquals(RigaTripGuide.Phase.STAY, state.phase);
  }

  private static RigaJourneyService.Result walkPart(long departure, long arrival)
  {
    RigaJourneyPlanner.Leg leg = new RigaJourneyPlanner.Leg(0, 1, departure, arrival, null, -1, -1, 100, null);
    return new RigaJourneyService.Result(null,
        new RigaJourneyPlanner.Journey(departure, Collections.singletonList(leg), 0), 0, 0, false,
        new double[][] {{56.9, 24.1, 56.91, 24.1}}, new boolean[] {true});
  }

  @Test public void aDelayThatUsesUpTheStayIsFoundBeforeTheNextPart()
  {
    // Part 1 now arrives at 10:45 (late); part 2 was to leave at 10:40.
    java.util.List<RigaJourneyService.Result> late = Arrays.asList(walkPart(T, T + 45 * MIN),
        walkPart(T + 40 * MIN, T + 50 * MIN));
    assertEquals(0, RigaRouteProvider.lateStop(late, 0, T + 10 * MIN));
    // A shorter stay that still fits is the rider's to spend.
    java.util.List<RigaJourneyService.Result> tight = Arrays.asList(walkPart(T, T + 35 * MIN),
        walkPart(T + 40 * MIN, T + 50 * MIN));
    assertEquals(-1, RigaRouteProvider.lateStop(tight, 0, T + 10 * MIN));
    // Once the next part has begun there is nothing left to warn about.
    assertEquals(-1, RigaRouteProvider.lateStop(late, 0, T + 41 * MIN));
  }
}
