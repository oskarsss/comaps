package app.organicmaps.transit;

import static org.junit.Assert.*;

import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitStepType;
import org.junit.Test;

public class RigaTripGuideTest
{
  private static final long MIN = 60000;
  private static final long T = 1_800_000_000_000L;

  private static TransitJourneyLeg.Stop stop(String name, long time, double lat)
  {
    return new TransitJourneyLeg.Stop(name, time, time, lat, 24.10);
  }

  /** Walk 5 min (lat 56.900→56.903), bus over 4 stops 56.903→56.940 (T..T+12), walk 3 min to 56.942. */
  private static TransitJourneyLeg[] legs()
  {
    return new TransitJourneyLeg[] {
        new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 300, false,
            new TransitJourneyLeg.Stop[] {stop("Home", T - 5 * MIN, 56.900), stop("A", T, 56.903)}),
        new TransitJourneyLeg(TransitStepType.BUS, "45", "Centre", 0, 0, true,
            new TransitJourneyLeg.Stop[] {stop("A", T, 56.903), stop("B", T + 4 * MIN, 56.915),
                stop("C", T + 8 * MIN, 56.927), stop("D", T + 12 * MIN, 56.940)}),
        new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 200, false,
            new TransitJourneyLeg.Stop[] {stop("D", T + 12 * MIN, 56.940), stop("Work", T + 15 * MIN, 56.942)})};
  }

  @Test public void leaveAlertComesAMinuteBeforeSettingOff()
  {
    RigaTripGuide.State early = RigaTripGuide.of(legs(), T - 10 * MIN, Double.NaN, Double.NaN);
    assertEquals(RigaTripGuide.Phase.LEAVE, early.phase);
    assertNull(early.alert);
    RigaTripGuide.State due = RigaTripGuide.of(legs(), T - 5 * MIN - 30000, Double.NaN, Double.NaN);
    assertEquals("leave", due.alert);
    RigaTripGuide.State walking = RigaTripGuide.of(legs(), T - 3 * MIN, 56.901, 24.10);
    assertEquals(RigaTripGuide.Phase.WALK, walking.phase);
    assertEquals(T, walking.at);
  }

  @Test public void atTheStopTheRiderWaitsAndIsToldWhenTheBusIsClose()
  {
    RigaTripGuide.State waiting = RigaTripGuide.of(legs(), T - 4 * MIN, 56.9031, 24.10);
    assertEquals(RigaTripGuide.Phase.WAIT, waiting.phase);
    assertEquals(1, waiting.leg);
    assertNull(waiting.alert);
    assertEquals("board:1", RigaTripGuide.of(legs(), T - MIN, 56.9031, 24.10).alert);
  }

  @Test public void ridingCountsStopsByPositionAndAlertsBeforeTheExit()
  {
    RigaTripGuide.State riding = RigaTripGuide.of(legs(), T + 2 * MIN, 56.914, 24.10);
    assertEquals(RigaTripGuide.Phase.RIDE, riding.phase);
    assertEquals(3, riding.stopsLeft); // 110 m before B: B, C, then D
    assertNull(riding.alert);
    // The bus runs early: the clock says before C, the position says at C.
    RigaTripGuide.State next = RigaTripGuide.of(legs(), T + 6 * MIN, 56.927, 24.10);
    assertEquals(1, next.stopsLeft);
    assertEquals("off:1", next.alert);
  }

  @Test public void atTheExitStopAheadOfTheClockTheRiderWalksOn()
  {
    // The bus ran early: the clock says three stops to go, the position says the rider is at D.
    RigaTripGuide.State off = RigaTripGuide.of(legs(), T + 3 * MIN, 56.9401, 24.10);
    assertEquals(RigaTripGuide.Phase.WALK, off.phase);
    assertEquals(2, off.leg);
    assertEquals(T + 15 * MIN, off.at);
  }

  @Test public void stillAtTheStopAfterTheBusLeftIsAMissedBus()
  {
    RigaTripGuide.State missed = RigaTripGuide.of(legs(), T + 2 * MIN, 56.9031, 24.10);
    assertTrue(missed.missed);
    assertEquals("missed:1", missed.alert);
  }

  @Test public void atTheStopAsTheDepartureTimePassesTheRiderStillWaits()
  {
    // The bus is due but not there yet: still waiting for it, not riding.
    RigaTripGuide.State due = RigaTripGuide.of(legs(), T + 30000, 56.9031, 24.10);
    assertEquals(RigaTripGuide.Phase.WAIT, due.phase);
    assertEquals(1, due.leg);
    assertFalse(due.missed);
    assertEquals("board:1", due.alert);
    // The live feed still has the bus two stops away: not missed, however late it is.
    TransitJourneyLeg[] late = legs();
    late[1].vehicleStopsAway = 2;
    RigaTripGuide.State coming = RigaTripGuide.of(late, T + 4 * MIN, 56.9031, 24.10);
    assertEquals(RigaTripGuide.Phase.WAIT, coming.phase);
    assertFalse(coming.missed);
  }

  @Test public void arrivalByPositionOrLongAfterTheTimetable()
  {
    assertEquals(RigaTripGuide.Phase.ARRIVED, RigaTripGuide.of(legs(), T + 14 * MIN, 56.9419, 24.10).phase);
    RigaTripGuide.State late = RigaTripGuide.of(legs(), T + 20 * MIN, 56.941, 24.10);
    assertEquals(RigaTripGuide.Phase.WALK, late.phase);
    assertEquals(RigaTripGuide.Phase.ARRIVED, RigaTripGuide.of(legs(), T + 20 * MIN, Double.NaN, Double.NaN).phase);
  }

  /** Train from Riga (T) via Olaine (T+20) to Jelgava (T+45): the last stretch takes 25 minutes. */
  private static TransitJourneyLeg[] train()
  {
    return new TransitJourneyLeg[] {
        new TransitJourneyLeg(TransitStepType.TRAIN, "6213", "Jelgava", 0, 0, false,
            new TransitJourneyLeg.Stop[] {stop("Rīga", T, 56.947), stop("Olaine", T + 20 * MIN, 56.785),
                stop("Jelgava", T + 45 * MIN, 56.651)})};
  }

  @Test public void onATrainTheGetOffAlertComesShortlyBeforeTheExitNotAtTheStationBefore()
  {
    RigaTripGuide.State leftOlaine = RigaTripGuide.of(train(), T + 22 * MIN, Double.NaN, Double.NaN);
    assertEquals(RigaTripGuide.Phase.RIDE, leftOlaine.phase);
    assertEquals(1, leftOlaine.stopsLeft);
    assertNull(leftOlaine.alert);
    RigaTripGuide.State close = RigaTripGuide.of(train(), T + 41 * MIN, Double.NaN, Double.NaN);
    assertEquals("off:0", close.alert);
  }

  @Test public void aOneStopRideDoesNotWarnOnBoarding()
  {
    TransitJourneyLeg[] express = {new TransitJourneyLeg(TransitStepType.TRAIN, "6201", "Jelgava", 0, 0, false,
        new TransitJourneyLeg.Stop[] {stop("Rīga", T, 56.947), stop("Jelgava", T + 40 * MIN, 56.651)})};
    assertNull(RigaTripGuide.of(express, T + MIN, Double.NaN, Double.NaN).alert);
    assertEquals("off:0", RigaTripGuide.of(express, T + 36 * MIN, Double.NaN, Double.NaN).alert);
  }

  @Test public void theAlertKeyStaysTheSameSoItFiresOnce()
  {
    // The service fires each key once: successive ticks near the exit must produce the same key.
    assertEquals(RigaTripGuide.of(legs(), T + 9 * MIN, Double.NaN, Double.NaN).alert,
        RigaTripGuide.of(legs(), T + 11 * MIN, Double.NaN, Double.NaN).alert);
  }
  @Test public void anEarlierVehicleOfTheSameLineIsFollowedOncePastTheFirstStop()
  {
    // Planned bus leaves A at T; the rider took the one before and is between B and C eight minutes early.
    RigaTripGuide.State riding = RigaTripGuide.of(legs(), T - 8 * MIN, 56.921, 24.10);
    assertEquals(RigaTripGuide.Phase.RIDE, riding.phase);
    assertEquals(1, riding.leg);
    assertEquals(2, riding.stopsLeft);
    assertEquals(T - 2 * MIN, riding.at); // half way B-C is T+6 by the timetable: 14 min early
    // Still walking towards the boarding stop near the line is not riding.
    assertEquals(RigaTripGuide.Phase.LEAVE, RigaTripGuide.of(legs(), T - 8 * MIN, 56.900, 24.10).phase);
  }

  @Test public void anEarlierVehicleIsFollowedRightAfterBoardingWhenTheRiderMovesAtVehicleSpeed()
  {
    // Planned bus leaves A at T; the rider took one 15 min earlier and is 400 m past A, before B.
    double lat = 56.9066;
    // On foot (or not known to be moving), that is someone walking along the line, not riding yet.
    assertNotEquals(RigaTripGuide.Phase.RIDE, RigaTripGuide.of(legs(), T - 15 * MIN, lat, 24.10, 0, false).phase);
    RigaTripGuide.State riding = RigaTripGuide.of(legs(), T - 15 * MIN, lat, 24.10, 0, true);
    assertEquals(RigaTripGuide.Phase.RIDE, riding.phase);
    assertEquals(1, riding.leg);
    assertTrue(riding.at < T + 12 * MIN - 10 * MIN); // arrives by its own clock, about 15 min early
  }

  @Test public void offAnEarlierVehicleAtTheExitTheRiderWalksOnInsteadOfWaitingForThePlannedOne()
  {
    // Planned bus leaves A at T; the rider took the one before, was followed aboard (rode = 1) and is at D
    // three minutes before the planned bus even leaves A.
    RigaTripGuide.State off = RigaTripGuide.of(legs(), T - 3 * MIN, 56.9401, 24.10, 1, false, 1);
    assertEquals(RigaTripGuide.Phase.WALK, off.phase);
    assertEquals(2, off.leg);
    // Not seen aboard, being near D before the bus leaves is not a ride done (someone else's route there).
    assertEquals(RigaTripGuide.Phase.WAIT, RigaTripGuide.of(legs(), T - 3 * MIN, 56.9401, 24.10, 1, false, -1).phase);
  }

  @Test public void walkingAwayFromTheExitOfAnEarlierVehicleBetweenTicksStillGoesOn()
  {
    // The tick at D was missed: the rider is already 600 m off the line, still before the planned departure.
    RigaTripGuide.State on = RigaTripGuide.of(legs(), T - 2 * MIN, 56.9401, 24.11, 1, false, 1);
    assertNotEquals(RigaTripGuide.Phase.WAIT, on.phase);
    assertEquals(2, on.leg);
    // Loosely on the line between B and C, the earlier vehicle is still being ridden.
    RigaTripGuide.State riding = RigaTripGuide.of(legs(), T - 8 * MIN, 56.921, 24.112, 1, false, 1);
    assertEquals(RigaTripGuide.Phase.RIDE, riding.phase);
    assertEquals(1, riding.leg);
  }

  @Test public void theRideHeadingIsTheWayTheVehicleLastMovedNotWhereThePhoneFaces()
  {
    java.util.ArrayDeque<double[]> track = new java.util.ArrayDeque<>();
    // Northwards 100 m, then standing at a stop: the heading stays north (about 0 rad).
    track.add(new double[] {0, 56.9000, 24.10});
    track.add(new double[] {3000, 56.9009, 24.10});
    double north = RigaTripPanel.movedHeading(track, 56.9009, 24.10);
    assertEquals(0, north, 0.01);
    // Only a few metres of GPS jitter: no direction from it.
    java.util.ArrayDeque<double[]> still = new java.util.ArrayDeque<>();
    still.add(new double[] {0, 56.9000, 24.10});
    assertTrue(Double.isNaN(RigaTripPanel.movedHeading(still, 56.90002, 24.10002)));
    // East is a quarter turn.
    assertEquals(Math.PI / 2, RigaTripPanel.bearing(56.9, 24.10, 56.9, 24.11), 0.01);
  }

  @Test public void movingMeansFasterThanJoggingForTwentySeconds()
  {
    java.util.ArrayDeque<double[]> track = new java.util.ArrayDeque<>();
    assertFalse(RigaTripPanel.moving(track, 0, 56.90, 24.10));
    assertFalse(RigaTripPanel.moving(track, 10000, 56.9005, 24.10)); // 56 m in 10 s, too short to tell
    assertTrue(RigaTripPanel.moving(track, 20000, 56.9010, 24.10));  // 111 m in 20 s: 5.6 m/s
    track.clear();
    RigaTripPanel.moving(track, 0, 56.90, 24.10);
    assertFalse(RigaTripPanel.moving(track, 30000, 56.9003, 24.10)); // 33 m in 30 s: walking
  }

  @Test public void theGetOffAlertWaitsUntilTheRiderReachesTheStopBeforeTheExit()
  {
    // Half way between B and C: two stops to go, whatever the clock.
    RigaTripGuide.State half = RigaTripGuide.of(legs(), T + 5 * MIN, 56.921, 24.10);
    assertEquals(2, half.stopsLeft);
    assertNull(half.alert);
    // 220 m before C while the clock says C was passed (the bus runs late): still two stops, no alert.
    RigaTripGuide.State late = RigaTripGuide.of(legs(), T + 9 * MIN, 56.925, 24.10);
    assertEquals(2, late.stopsLeft);
    assertNull(late.alert);
    assertEquals("off:1", RigaTripGuide.of(legs(), T + 10 * MIN, 56.9268, 24.10).alert);
  }

  @Test public void tripProgressSpansTheWholeJourneyWithPointsAtBoardingAndExit()
  {
    RigaTripProgress leave = RigaTripProgress.of(legs(), RigaTripGuide.of(legs(), T - 10 * MIN, Double.NaN, Double.NaN),
        T - 10 * MIN);
    assertEquals(1200, leave.max);
    assertEquals(0, leave.progress);
    assertEquals(3, leave.segments.size());
    assertEquals(300, leave.segments.get(0).length);
    assertEquals(2, leave.points.size());
    assertEquals(300, leave.points.get(0).position);
    assertEquals(1020, leave.points.get(1).position);
    RigaTripGuide.State waiting = RigaTripGuide.of(legs(), T - MIN, 56.9031, 24.10);
    assertEquals(300, RigaTripProgress.of(legs(), waiting, T - MIN).progress);
    RigaTripGuide.State atC = RigaTripGuide.of(legs(), T + 8 * MIN, 56.927, 24.10);
    assertEquals(780, RigaTripProgress.of(legs(), atC, T + 8 * MIN).progress);
    RigaTripGuide.State arrived = RigaTripGuide.of(legs(), T + 14 * MIN, 56.9419, 24.10);
    assertEquals(1200, RigaTripProgress.of(legs(), arrived, T + 14 * MIN).progress);
  }
  @Test public void stillShortOfTheStopMinutesAfterTheDepartureIsAMissedBusNotARide()
  {
    // 330 m before the stop at T+3.5: the bus has left, the rider is not on it.
    RigaTripGuide.State missed = RigaTripGuide.of(legs(), T + 3 * MIN + 30000, 56.900, 24.10);
    assertEquals(RigaTripGuide.Phase.WAIT, missed.phase);
    assertTrue(missed.missed);
    // Shortly after the departure the clock is still trusted.
    assertEquals(RigaTripGuide.Phase.RIDE, RigaTripGuide.of(legs(), T + MIN, 56.900, 24.10).phase);
  }

  @Test public void offTheStraightLineBetweenStopsThePositionStillHoldsBackALateClock()
  {
    // The road bends ~500 m away from the line B-C; the clock says C is passed, the rider is half way to C.
    RigaTripGuide.State bend = RigaTripGuide.of(legs(), T + 9 * MIN, 56.921, 24.108);
    assertEquals(RigaTripGuide.Phase.RIDE, bend.phase);
    assertEquals(2, bend.stopsLeft);
    assertNull(bend.alert);
  }

  /** Tram A→B→C (T..T+8), walk to the bus stop, then a bus back along the same road C→B→E (T+10..T+18). */
  private static TransitJourneyLeg[] sharedRoad()
  {
    return new TransitJourneyLeg[] {
        new TransitJourneyLeg(TransitStepType.TRAM, "7", "Ķengarags", 0, 0, true,
            new TransitJourneyLeg.Stop[] {stop("A", T, 56.900), stop("B", T + 4 * MIN, 56.910),
                stop("C", T + 8 * MIN, 56.920)}),
        new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 60, false,
            new TransitJourneyLeg.Stop[] {stop("C", T + 8 * MIN, 56.920), stop("C bus", T + 9 * MIN, 56.9202)}),
        new TransitJourneyLeg(TransitStepType.BUS, "49", "Daudersala", 0, 0, true,
            new TransitJourneyLeg.Stop[] {stop("C bus", T + 10 * MIN, 56.9202), stop("B bus", T + 14 * MIN, 56.9101),
                stop("E", T + 18 * MIN, 56.890)})};
  }

  @Test public void onARoadSharedWithAnEarlierRideTheLaterRideIsFollowed()
  {
    // Between C and B at T+12: the tram passed here around T+6, the bus is due here at T+12.
    RigaTripGuide.State bus = RigaTripGuide.of(sharedRoad(), T + 12 * MIN, 56.915, 24.10);
    assertEquals(RigaTripGuide.Phase.RIDE, bus.phase);
    assertEquals(2, bus.leg);
    assertNull(bus.alert); // not the tram's "get off at the next stop"
    // On the tram at T+6 the same spot is the tram.
    assertEquals(0, RigaTripGuide.of(sharedRoad(), T + 6 * MIN, 56.915, 24.10).leg);
    // Once on the bus, a later tick cannot go back to the tram.
    assertEquals(2, RigaTripGuide.of(sharedRoad(), T + 7 * MIN, 56.915, 24.10, 2).leg);
  }
}
