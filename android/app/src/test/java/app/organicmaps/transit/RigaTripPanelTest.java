package app.organicmaps.transit;

import static org.junit.Assert.*;

import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitStepType;
import org.junit.Test;

/** The trip panel's "now / then" from the trip guide's state: the user's 07:20 Ludzas iela → Lubānas iela trip. */
public class RigaTripPanelTest
{
  private static final long MIN = 60000;
  private static final long T = 1_795_000_000_000L; // 07:20

  private static TransitJourneyLeg.Stop stop(String name, long time, double lat)
  {
    return new TransitJourneyLeg.Stop(name, time, time, lat, 24.10);
  }

  /** Walk to Lido, tram 7 to Dienvidu tilts, 1 min change, bus 49 (validatable) to Pavasara gatve, walk. */
  private static TransitJourneyLeg[] legs()
  {
    TransitJourneyLeg tram = new TransitJourneyLeg(TransitStepType.TRAM, "7", "Ķengarags", 0xfff2000b, 0, false,
        new TransitJourneyLeg.Stop[] {stop("Lido", T + 2 * MIN, 56.920), stop("Mazā Kalna iela", T + 3 * MIN, 56.925),
            stop("Dienvidu tilts", T + 4 * MIN, 56.930)});
    TransitJourneyLeg bus = new TransitJourneyLeg(TransitStepType.BUS, "49", "Sarkandaugava", 0xfff2b327, 0, false,
        new TransitJourneyLeg.Stop[] {stop("Dienvidu tilts bus", T + 10 * MIN, 56.9305),
            stop("A", T + 13 * MIN, 56.940), stop("B", T + 16 * MIN, 56.950), stop("Pavasara gatve", T + 19 * MIN, 56.960)});
    bus.validatable = true;
    return new TransitJourneyLeg[] {
        new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 150, false,
            new TransitJourneyLeg.Stop[] {stop("Ludzas iela", T, 56.918), stop("Lido", T + 2 * MIN, 56.920)}),
        tram,
        new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 60, false,
            new TransitJourneyLeg.Stop[] {stop("Dienvidu tilts", T + 4 * MIN, 56.930),
                stop("Dienvidu tilts bus", T + 5 * MIN, 56.9305)}),
        bus,
        new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 510, false,
            new TransitJourneyLeg.Stop[] {stop("Pavasara gatve", T + 19 * MIN, 56.960),
                stop("Lubānas iela 125A", T + 27 * MIN, 56.965)})};
  }

  private static RigaTripGuide.State state(RigaTripGuide.Phase phase, int leg)
  {
    return new RigaTripGuide.State(phase, leg, 2, T, false, null);
  }

  @Test public void walkingToTheFirstStopTheTramIsNext()
  {
    RigaTripPanel panel = RigaTripPanel.of(legs(), state(RigaTripGuide.Phase.WALK, 0));
    assertEquals(RigaTripPanel.Kind.WALK, panel.now.kind);
    assertEquals(0, panel.now.leg);
    assertEquals(RigaTripPanel.Kind.BOARD, panel.next.kind);
    assertEquals(1, panel.next.leg);
    assertEquals(0, panel.done);
    assertEquals(-1, panel.validate); // the tram is not validated in a ticket app
  }

  @Test public void waitingTheRideIsNowAndGettingOffIsNext()
  {
    RigaTripPanel panel = RigaTripPanel.of(legs(), state(RigaTripGuide.Phase.WAIT, 1));
    assertEquals(RigaTripPanel.Kind.BOARD, panel.now.kind);
    assertEquals(RigaTripPanel.Kind.RIDE, panel.next.kind);
    assertEquals(1, panel.next.leg);
    assertEquals(1, panel.done); // the walk to the stop is behind
  }

  @Test public void onTheTramTheBusAfterTheShortChangeIsNext()
  {
    RigaTripPanel panel = RigaTripPanel.of(legs(), state(RigaTripGuide.Phase.RIDE, 1));
    assertEquals(RigaTripPanel.Kind.RIDE, panel.now.kind);
    assertEquals(RigaTripPanel.Kind.BOARD, panel.next.kind);
    assertEquals(3, panel.next.leg);
    assertEquals(3, panel.nextBadgeLeg());
    // The bus's ticket is not offered while riding the tram: the button belongs to the ride it validates.
    assertEquals(-1, panel.validate);
  }

  @Test public void walkingToTheBusItsTicketIsOfferedAndTheTramIsDone()
  {
    RigaTripPanel panel = RigaTripPanel.of(legs(), state(RigaTripGuide.Phase.WALK, 2));
    assertEquals(RigaTripPanel.Kind.WALK, panel.now.kind);
    assertEquals(3, panel.next.leg);
    assertEquals(2, panel.done);
    assertEquals(3, panel.validate);
  }

  @Test public void onTheBusTheFinalWalkIsNext()
  {
    RigaTripPanel panel = RigaTripPanel.of(legs(), state(RigaTripGuide.Phase.RIDE, 3));
    assertEquals(RigaTripPanel.Kind.WALK, panel.next.kind);
    assertEquals(4, panel.next.leg);
    assertEquals(-1, panel.nextBadgeLeg());
    assertEquals(3, panel.validate);
  }

  @Test public void onTheFinalWalkOnlyTheArrivalIsLeft()
  {
    RigaTripPanel panel = RigaTripPanel.of(legs(), state(RigaTripGuide.Phase.WALK, 4));
    assertEquals(RigaTripPanel.Kind.ARRIVE, panel.next.kind);
    assertEquals(-1, panel.validate);
  }

  @Test public void arrivedEverythingIsDone()
  {
    RigaTripPanel panel = RigaTripPanel.of(legs(), new RigaTripGuide.State(RigaTripGuide.Phase.ARRIVED, -1, 0, T,
        false, "arrived"));
    assertEquals(RigaTripPanel.Kind.ARRIVE, panel.now.kind);
    assertNull(panel.next);
    assertEquals(5, panel.done);
  }

  /** The user's 07:27: off the tram, standing at the bus stop. The panel is about bus 49, the tram is history. */
  @Test public void atTheBusStopAfterTheTramThePanelIsAboutTheBus()
  {
    TransitJourneyLeg[] legs = legs();
    RigaTripGuide.State state = RigaTripGuide.of(legs, T + 7 * MIN, 56.9305, 24.10);
    RigaTripPanel panel = RigaTripPanel.of(legs, state);
    assertEquals(RigaTripPanel.Kind.BOARD, panel.now.kind);
    assertEquals(3, panel.now.leg);
    assertEquals(3, panel.validate);
    assertTrue(panel.done >= 2);
    assertEquals(T + 10 * MIN, state.at);
    assertEquals(3, RigaTripPanel.minutes(state.at, T + 7 * MIN));
  }

  @Test public void theSharedStateNeverGoesBackToAnEarlierRide()
  {
    TransitJourneyLeg[] legs = legs();
    assertEquals(3, RigaTripPanel.now(legs, T + 12 * MIN, 56.945, 24.10).leg); // on the bus
    // A later fix that would read as the tram's road keeps the bus.
    assertTrue(RigaTripPanel.now(legs, T + 12 * MIN + 5000, 56.927, 24.10).leg >= 3);
  }

  @Test public void countdownRoundsUpAndStopsAtZero()
  {
    assertEquals(1, RigaTripPanel.minutes(T + 1000, T));
    assertEquals(0, RigaTripPanel.minutes(T - 1000, T));
    assertEquals(4, RigaTripPanel.minutes(T + 4 * MIN, T));
  }
}
