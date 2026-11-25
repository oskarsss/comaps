package app.organicmaps.transit;

import static org.junit.Assert.*;

import app.organicmaps.sdk.routing.TransitAlternative;
import app.organicmaps.sdk.routing.TransitStepType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** "7/14 › 49/31" and "Also in …": other vehicles making the same ride, found in the timetable only. */
public class RigaSameRidesTest
{
  private static final long MIN = 60000;
  private static final long T = 1_795_000_000_000L; // 07:20
  private static final int TRAM = 0, BUS = 3;
  // Lido, Dienvidu tilts, Mazā Kalna iela (another way), Dienvidu tilts bus stop, Pavasara gatve.
  private static final int LIDO = 0, BRIDGE = 1, OTHER = 2, BUS_STOP = 3, PAVASARA = 4;
  private static final int START = 5, END = 6;

  private static RigaJourneyPlanner.Trip trip(String id, String line, int type, long departure, int... stops)
  {
    List<RigaJourneyPlanner.Event> events = new ArrayList<>();
    for (int i = 0; i < stops.length; ++i)
    {
      long time = departure + i * 4 * MIN;
      events.add(new RigaJourneyPlanner.Event(stops[i], i + 1, time, time, true, true));
    }
    return new RigaJourneyPlanner.Trip(id, "r" + line, line, "Towards", type, "", 0, events);
  }

  private final RigaJourneyPlanner.Trip mPlannedTram = trip("7a", "7", TRAM, T + 13 * MIN, LIDO, BRIDGE);
  private final RigaJourneyPlanner.Trip mPlannedBus = trip("49a", "49", BUS, T + 20 * MIN, BUS_STOP, PAVASARA);

  private RigaJourneyPlanner.Timetable timetable()
  {
    List<RigaJourneyPlanner.Stop> stops = Arrays.asList(
        new RigaJourneyPlanner.Stop("lido", "Lido", "", 56.92, 24.10),
        new RigaJourneyPlanner.Stop("bridge", "Dienvidu tilts", "", 56.93, 24.10),
        new RigaJourneyPlanner.Stop("other", "Mazā Kalna iela", "", 56.925, 24.11),
        new RigaJourneyPlanner.Stop("bus", "Dienvidu tilts", "", 56.9305, 24.10),
        new RigaJourneyPlanner.Stop("pavasara", "Pavasara gatve", "", 56.96, 24.10));
    List<RigaJourneyPlanner.Trip> trips = Arrays.asList(
        mPlannedTram,
        trip("14a", "14", TRAM, T + 8 * MIN, LIDO, BRIDGE),         // 07:28, same ride
        trip("7b", "7", TRAM, T + 28 * MIN, LIDO, BRIDGE),          // 07:48, same ride
        trip("5a", "5", TRAM, T + 15 * MIN, LIDO, OTHER),           // does not reach the bridge
        trip("13a", "13", BUS, T + 16 * MIN, LIDO, BRIDGE),         // a bus, not a tram
        trip("11a", "11", TRAM, T + 4 * MIN, LIDO, BRIDGE),         // gone before the rider gets there
        trip("14b", "14", TRAM, T + 38 * MIN, LIDO, BRIDGE),        // past the 20 min window
        trip("6a", "6", TRAM, T + 18 * MIN, BRIDGE, LIDO),          // the other direction
        mPlannedBus,
        trip("49b", "49", BUS, T + 25 * MIN, BUS_STOP, PAVASARA),   // 07:45
        trip("31a", "31", BUS, T + 35 * MIN, BUS_STOP, PAVASARA));  // 07:55
    return new RigaJourneyPlanner.Timetable(stops, trips, Collections.emptyList());
  }

  /** Walk 1 min to Lido, tram 7 07:33, 1 min change, bus 49 07:40, walk 6 min. */
  private RigaJourneyPlanner.Journey journey()
  {
    return new RigaJourneyPlanner.Journey(T + 5 * MIN, Arrays.asList(
        new RigaJourneyPlanner.Leg(START, LIDO, T + 5 * MIN, T + 6 * MIN, null, -1, -1, 80, null),
        new RigaJourneyPlanner.Leg(LIDO, BRIDGE, T + 13 * MIN, T + 17 * MIN, mPlannedTram, 0, 1, 0, null),
        new RigaJourneyPlanner.Leg(BRIDGE, BUS_STOP, T + 17 * MIN, T + 18 * MIN, null, -1, -1, 60, null),
        new RigaJourneyPlanner.Leg(BUS_STOP, PAVASARA, T + 20 * MIN, T + 24 * MIN, mPlannedBus, 0, 1, 0, null),
        new RigaJourneyPlanner.Leg(PAVASARA, END, T + 24 * MIN, T + 30 * MIN, null, -1, -1, 450, null)), 2);
  }

  @Test public void linesMakingTheSameRideAreGroupedWithTheirDepartures()
  {
    RigaSameRides.Group[] groups = RigaSameRides.of(timetable(), journey(), T + 5 * MIN);
    assertEquals(5, groups.length);
    assertSame(RigaSameRides.Group.NONE, groups[0]);
    RigaSameRides.Group tram = groups[1];
    assertEquals(Arrays.asList("7", "14"), tram.lines);
    assertArrayEquals(new long[] {T + 8 * MIN, T + 28 * MIN}, tram.also);
    assertArrayEquals(new String[] {"14", "7"}, tram.alsoLines);
    // The bus after the change: from the tram's arrival on, not from now.
    RigaSameRides.Group bus = groups[3];
    assertEquals(Arrays.asList("49", "31"), bus.lines);
    assertArrayEquals(new long[] {T + 25 * MIN, T + 35 * MIN}, bus.also);
  }

  @Test public void aLaterSearchNoLongerOffersDeparturesTheRiderCannotReach()
  {
    // At 07:30 with a 1 min walk, tram 14 at 07:28 is gone.
    RigaSameRides.Group tram = RigaSameRides.of(timetable(), journey(), T + 10 * MIN)[1];
    assertArrayEquals(new long[] {T + 28 * MIN}, tram.also);
    assertEquals(Arrays.asList("7"), tram.lines);
  }

  @Test public void badgeJoinsTheLinesUpToALimit()
  {
    assertEquals("7", RigaSameRides.badge(RigaSameRides.Group.NONE, "7", 3));
    RigaSameRides.Group many = new RigaSameRides.Group(Arrays.asList("7", "14", "3", "9"), new long[0], new String[0]);
    assertEquals("7/14/3/…", RigaSameRides.badge(many, "7", 3));
    assertEquals("7/14", RigaSameRides.badge(
        new RigaSameRides.Group(Arrays.asList("7", "14"), new long[0], new String[0]), "7", 3));
  }

  /** The card: "walk 1 › 7/14 › 49/31 › walk 6", no "walk 1" for the change, and the first ride's other times. */
  @Test public void cardGroupsLinesAndLeavesOutTheShortChange()
  {
    RigaJourneyService.Result route = new RigaJourneyService.Result(timetable(), journey(), 0, 0, false,
        new double[0][], new boolean[0]);
    TransitAlternative card = RigaRouteProvider.summary(route, T + 5 * MIN);
    assertArrayEquals(new TransitStepType[] {TransitStepType.PEDESTRIAN, TransitStepType.TRAM, TransitStepType.BUS,
        TransitStepType.PEDESTRIAN}, card.modes);
    assertArrayEquals(new String[] {"1", "7/14", "49/31", "6"}, card.numbers);
    assertArrayEquals(new long[] {T + 8 * MIN, T + 28 * MIN}, card.also);
  }

  @Test public void aLongerChangeOnFootStaysOnTheCard()
  {
    RigaJourneyPlanner.Journey planned = journey();
    List<RigaJourneyPlanner.Leg> legs = new ArrayList<>(planned.legs);
    legs.set(2, new RigaJourneyPlanner.Leg(BRIDGE, BUS_STOP, T + 17 * MIN, T + 20 * MIN, null, -1, -1, 200, null));
    RigaJourneyService.Result route = new RigaJourneyService.Result(timetable(),
        new RigaJourneyPlanner.Journey(planned.departure, legs, 2), 0, 0, false, new double[0][], new boolean[0]);
    assertArrayEquals(new String[] {"1", "7/14", "3", "49/31", "6"},
                      RigaRouteProvider.summary(route, T + 5 * MIN).numbers);
  }
}
