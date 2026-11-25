package app.organicmaps.transit;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RigaJourneyPlannerTest
{
  private static long minute(long value) { return value * 60000; }
  private static RigaJourneyPlanner.Event event(int stop, int sequence, long time, boolean pickup, boolean drop)
  {
    return new RigaJourneyPlanner.Event(stop, sequence, minute(time), minute(time), pickup, drop);
  }
  private static RigaJourneyPlanner.Trip trip(String id, int[] stops, long... times)
  {
    List<RigaJourneyPlanner.Event> events = new ArrayList<>();
    for (int i = 0; i < stops.length; ++i)
      events.add(event(stops[i], i + 1, times[i], true, true));
    return new RigaJourneyPlanner.Trip(id, "riga_bus_" + id, "22", "Airport", 3, "riga_bus_22_a-b", 0, events);
  }
  private static RigaJourneyPlanner.Timetable table(int count, List<RigaJourneyPlanner.Trip> trips,
                                                   RigaJourneyPlanner.Footpath... walks)
  {
    List<RigaJourneyPlanner.Stop> stops = new ArrayList<>();
    for (int i = 0; i < count; ++i)
      stops.add(new RigaJourneyPlanner.Stop("s" + i, "Stop " + i, "", 56.95 + i * 0.001, 24.11));
    return new RigaJourneyPlanner.Timetable(stops, trips, Arrays.asList(walks));
  }
  private static RigaJourneyPlanner.Journey plan(RigaJourneyPlanner.Timetable data, int from, int to)
  {
    return RigaJourneyPlanner.plan(data, "s" + from, "s" + to, 0, Collections.emptyMap(), () -> false);
  }

  /** The search would walk 10 min back to the stop before the near one, to catch the same tram there. */
  @Test public void boardsAtTheStopThatLetsTheRiderLeaveLast()
  {
    RigaJourneyPlanner.Trip tram = trip("tram", new int[] {1, 2, 3}, 12, 14, 30);
    RigaJourneyPlanner.Journey journey = plan(table(4, Arrays.asList(tram),
        new RigaJourneyPlanner.Footpath(0, 1, 600, 700), new RigaJourneyPlanner.Footpath(0, 2, 300, 350)), 0, 3);
    assertEquals(2, journey.legs.size());
    assertEquals(2, journey.legs.get(0).to);
    assertEquals(350, journey.legs.get(0).walkingMetres);
    assertEquals(2, journey.legs.get(1).from);
    assertEquals(minute(14), journey.legs.get(1).departure);
    assertEquals(minute(9), RigaJourneyAlternatives.leave(journey));
    assertEquals(minute(30), journey.arrival);
  }

  /** The search would ride the tram past the stop it shares with the bus, to meet the bus earlier on its way back. */
  @Test public void changesWhereTheTwoRoutesMeetFirst()
  {
    RigaJourneyPlanner.Trip tram = trip("tram", new int[] {0, 1, 2}, 0, 5, 10);
    RigaJourneyPlanner.Trip bus = trip("bus", new int[] {2, 1, 3}, 15, 20, 30);
    RigaJourneyPlanner.Journey journey = plan(table(4, Arrays.asList(tram, bus)), 0, 3);
    assertEquals(2, journey.legs.size());
    assertEquals(1, journey.legs.get(0).to);
    assertEquals(1, journey.legs.get(0).stopCount());
    assertEquals(1, journey.legs.get(1).from);
    assertEquals(minute(20), journey.legs.get(1).departure);
    assertEquals(1, journey.legs.get(1).stopCount());
    assertEquals(minute(30), journey.arrival);
  }

  @Test public void rideStopCountUsesEventOrderRatherThanSequenceNumberGaps()
  {
    List<RigaJourneyPlanner.Event> events=Arrays.asList(event(0,1,1,true,true),
        event(1,10,2,true,true),event(2,30,3,true,true),event(3,90,4,true,true));
    RigaJourneyPlanner.Trip bus=new RigaJourneyPlanner.Trip("ride","r","45","End",3,"",0,events);
    assertEquals(3,plan(table(4,Arrays.asList(bus)),0,3).legs.get(0).stopCount());
    assertEquals(2,plan(table(4,Arrays.asList(bus)),1,3).legs.get(0).stopCount());
    assertEquals(1,plan(table(4,Arrays.asList(bus)),0,1).legs.get(0).stopCount());
    assertEquals(0,plan(table(2,Collections.emptyList(),new RigaJourneyPlanner.Footpath(0,1,60,60)),0,1)
        .legs.get(0).stopCount());
  }

  @Test public void targetArrivalPrunesIrrelevantLaterConnections()
  {
    List<RigaJourneyPlanner.Event> events=new ArrayList<>();
    events.add(new RigaJourneyPlanner.Event(0,0,0,0,true,true));
    events.add(new RigaJourneyPlanner.Event(1,1,60000,60000,true,true));
    for(int i=2;i<20002;++i)
      events.add(new RigaJourneyPlanner.Event(2+i%2,i,60000+i*1000L,60000+i*1000L,true,true));
    RigaJourneyPlanner.Trip trip=new RigaJourneyPlanner.Trip("long","r","22","End",3,"",0,events);
    AtomicInteger checks=new AtomicInteger();
    RigaJourneyPlanner.Journey result=RigaJourneyPlanner.plan(table(4,Arrays.asList(trip)),"s0","s1",0,
        Collections.emptyMap(),()->{checks.incrementAndGet();return false;});
    assertEquals(60000,result.arrival);
    assertTrue("Already reached target, but scanned the rest of the day: "+checks.get(),checks.get()<10);
  }
  @Test public void targetPruningPreservesEqualArrivalFewerBoardingsAndRequiredRide()
  {
    RigaJourneyPlanner.Timetable data=table(3,Arrays.asList(
        trip("first",new int[]{0,1},1,2),trip("second",new int[]{1,2},4,10),
        trip("equalDirect",new int[]{0,2},10,10)));
    RigaJourneyPlanner.Journey result=plan(data,0,2);
    assertEquals(minute(10),result.arrival);assertEquals(1,result.boardings);
    RigaJourneyPlanner.Timetable shortWalk=table(2,Arrays.asList(trip("bus",new int[]{0,1},10,20)),
        new RigaJourneyPlanner.Footpath(0,1,60,60));
    result=RigaJourneyPlanner.plan(shortWalk,"s0","s1",0,Collections.emptyMap(),()->false,
        RigaJourneyPlanner.DEFAULT_LIMITS,true);
    assertNotNull(result);assertTrue(result.boardings>0);assertEquals(minute(20),result.arrival);
  }

  @Test public void directAndTransferJourneysRespectTheConnectionBuffer()
  {
    RigaJourneyPlanner.Trip first = trip("first", new int[]{0, 1}, 10, 20);
    RigaJourneyPlanner.Trip tooTight = trip("tight", new int[]{1, 2}, 21, 25);
    RigaJourneyPlanner.Trip onward = trip("onward", new int[]{1, 2}, 22, 30);
    RigaJourneyPlanner.Timetable data = table(3, Arrays.asList(first, tooTight, onward));
    RigaJourneyPlanner.Journey result = plan(data, 0, 2);
    assertEquals(minute(30), result.arrival);
    assertEquals(2, result.boardings);
    assertEquals("onward", result.legs.get(1).trip.id);
    assertEquals(minute(20), plan(data, 0, 1).arrival);
    assertNull(plan(data, 2, 0));
    assertEquals(0, plan(data, 0, 0).legs.size());
  }

  @Test public void liveDelayChangesTheChosenTransfer()
  {
    RigaJourneyPlanner.Timetable data = table(3, Arrays.asList(
        trip("first", new int[]{0, 1}, 10, 20), trip("onward", new int[]{1, 2}, 22, 30),
        trip("later", new int[]{1, 2}, 30, 40)));
    assertEquals(minute(30), plan(data, 0, 2).arrival);
    Map<Integer, RigaJourneyPlanner.Prediction> updates = new HashMap<>();
    updates.put(0, new RigaJourneyPlanner.Prediction(300, 0, "vehicle", 0));
    RigaJourneyPlanner.Journey live = RigaJourneyPlanner.plan(data, "s0", "s2", 0, updates, () -> false);
    assertEquals(minute(40), live.arrival);
    assertEquals("later", live.legs.get(1).trip.id);
    assertNotNull(live.legs.get(0).prediction);
    assertNull(live.legs.get(1).prediction);
    // The cached connection index is shared, so estimates must not leak into a later scheduled plan.
    assertEquals(minute(30), plan(data, 0, 2).arrival);
  }

  @Test public void estimatesAreSortedBeforeScanningConnections()
  {
    RigaJourneyPlanner.Timetable data = table(3, Arrays.asList(
        trip("delayed", new int[]{0, 1}, 10, 20), trip("connection", new int[]{1, 2}, 25, 35),
        trip("alternative", new int[]{0, 1}, 15, 22)));
    Map<Integer, RigaJourneyPlanner.Prediction> updates = new HashMap<>();
    updates.put(0, new RigaJourneyPlanner.Prediction(1200, 0, "vehicle", 0));
    RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(data, "s0", "s2", 0, updates, () -> false);
    assertEquals(minute(35), journey.arrival);
    assertEquals("alternative", journey.legs.get(0).trip.id);
  }

  @Test public void passengersCanStayAboardButCannotUseForbiddenStops()
  {
    RigaJourneyPlanner.Trip through = new RigaJourneyPlanner.Trip("through", "r", "1", "C", 3, "", 0,
        Arrays.asList(event(0, 1, 10, true, true), event(1, 2, 20, false, false), event(2, 3, 30, true, true)));
    RigaJourneyPlanner.Timetable data = table(3, Collections.singletonList(through));
    assertEquals(1, plan(data, 0, 2).legs.size());
    assertEquals(minute(30), plan(data, 0, 2).arrival);
    assertNull(plan(data, 0, 1));
    assertNull(plan(data, 1, 2));
  }

  @Test public void platformWalkingIsIncludedAndFewerChangesWinTies()
  {
    RigaJourneyPlanner.Timetable data = table(4, Arrays.asList(
        trip("first", new int[]{0, 1}, 10, 20), trip("second", new int[]{2, 3}, 23, 30)),
        new RigaJourneyPlanner.Footpath(1, 2, 60, 50));
    RigaJourneyPlanner.Journey journey = plan(data, 0, 3);
    assertEquals(3, journey.legs.size());
    assertNull(journey.legs.get(1).trip);
    assertEquals(50, journey.legs.get(1).walkingMetres);
    assertEquals(minute(30), journey.arrival);
    List<RigaJourneyPlanner.Trip> alternatives = new ArrayList<>(data.trips);
    alternatives.add(trip("direct", new int[]{0, 3}, 25, 30));
    RigaJourneyPlanner.Journey direct = plan(table(4, alternatives, data.footpaths.get(0)), 0, 3);
    assertEquals(1, direct.boardings);
    assertEquals("direct", direct.legs.get(0).trip.id);
  }

  @Test public void aLineIsNotRiddenTwiceInOneJourney()
  {
    // Line 15 one way, across the street, line 15 back: earliest, but no one would ride it like that.
    List<RigaJourneyPlanner.Event> out = Arrays.asList(event(0, 1, 10, true, true), event(1, 2, 12, true, true));
    List<RigaJourneyPlanner.Event> back = Arrays.asList(event(2, 1, 17, true, true), event(3, 2, 19, true, true));
    RigaJourneyPlanner.Timetable data = table(4, Arrays.asList(
        new RigaJourneyPlanner.Trip("out", "riga_trol_15", "15", "Ķengarags", 11, "", 0, out),
        new RigaJourneyPlanner.Trip("back", "riga_trol_15", "15", "Universitāte", 11, "", 0, back),
        trip("other", new int[]{0, 3}, 12, 20)), new RigaJourneyPlanner.Footpath(1, 2, 120, 150));
    RigaJourneyPlanner.Journey journey = plan(data, 0, 3);
    assertEquals(1, journey.boardings);
    assertEquals("other", journey.legs.get(0).trip.id);
  }

  @Test public void alreadyPassedBoardingStopsAreNotOffered()
  {
    RigaJourneyPlanner.Timetable data = table(3, Collections.singletonList(trip("bus", new int[]{0, 1, 2}, 10, 20, 30)));
    Map<Integer, RigaJourneyPlanner.Prediction> updates = new HashMap<>();
    updates.put(0, new RigaJourneyPlanner.Prediction(0, 1, "vehicle", 0));
    assertNull(RigaJourneyPlanner.plan(data, "s0", "s2", 0, updates, () -> false));
    assertEquals(minute(30), RigaJourneyPlanner.plan(data, "s1", "s2", 0, updates, () -> false).arrival);
  }

  @Test public void theBoardingAndTimeHorizonsAreBounded()
  {
    List<RigaJourneyPlanner.Trip> trips = new ArrayList<>();
    for (int i = 0; i < 5; ++i)
      trips.add(trip("t" + i, new int[]{i, i + 1}, 10 + i * 10, 15 + i * 10));
    RigaJourneyPlanner.Timetable data = table(6, trips);
    assertEquals(4, plan(data, 0, 4).boardings);
    assertNull(plan(data, 0, 5));
    assertEquals(RigaJourneyPlanner.WINDOW,
                 plan(table(2, Collections.singletonList(trip("edge", new int[]{0, 1}, 1439, 1440))), 0, 1).arrival);
    assertNull(plan(table(2, Collections.singletonList(trip("outside", new int[]{0, 1}, 1439, 1441))), 0, 1));
    assertNull(plan(table(2, Collections.singletonList(trip("gone", new int[]{0, 1}, -1, 10))), 0, 1));
  }

  @Test public void resourceLimitsAndCancellationUseSmallSyntheticInputs()
  {
    RigaJourneyPlanner.Timetable data = table(3, Arrays.asList(trip("a", new int[]{0, 1, 2}, 1, 2, 3),
        trip("b", new int[]{0, 2}, 4, 5)), new RigaJourneyPlanner.Footpath(0, 1, 1, 1));
    RigaJourneyPlanner.Limits[] limits = {new RigaJourneyPlanner.Limits(2, 10, 10, 10),
        new RigaJourneyPlanner.Limits(10, 1, 10, 10), new RigaJourneyPlanner.Limits(10, 10, 1, 10),
        new RigaJourneyPlanner.Limits(10, 10, 10, 0)};
    for (RigaJourneyPlanner.Limits limit : limits)
    {
      try
      {
        RigaJourneyPlanner.plan(data, "s0", "s2", 0, Collections.emptyMap(), () -> false, limit);
        fail("Planning ceiling ignored");
      }
      catch (IllegalArgumentException expected) {}
    }
    AtomicInteger checks = new AtomicInteger();
    try
    {
      RigaJourneyPlanner.plan(data, "s0", "s2", 0, Collections.emptyMap(), () -> checks.incrementAndGet() >= 3);
      fail("Cancelled search completed");
    }
    catch (CancellationException expected) {}
  }

  private static long[] exhaustive(RigaJourneyPlanner.Timetable data, int from, int to,
                                    Map<Integer, RigaJourneyPlanner.Prediction> predictions)
  {
    java.util.PriorityQueue<long[]> queue = new java.util.PriorityQueue<>(
        java.util.Comparator.comparingLong((long[] s) -> s[0]).thenComparingLong(s -> s[2]));
    long[][] best = new long[5][data.stops.size()];
    for (long[] row : best)
      Arrays.fill(row, Long.MAX_VALUE);
    best[0][from] = 0;
    queue.add(new long[]{0, from, 0});
    while (!queue.isEmpty())
    {
      long[] state = queue.remove();
      long time = state[0];
      int stop = (int) state[1];
      int rides = (int) state[2];
      if (best[rides][stop] != time)
        continue;
      if (stop == to)
        return new long[]{time, rides};
      for (RigaJourneyPlanner.Footpath walk : data.footpaths)
      {
        long arrive = time + walk.seconds * 1000L;
        if (walk.from == stop && arrive < best[rides][walk.to])
        {
          best[rides][walk.to] = arrive;
          queue.add(new long[]{arrive, walk.to, rides});
        }
      }
      if (rides == 4)
        continue;
      for (int t = 0; t < data.trips.size(); ++t)
      {
        RigaJourneyPlanner.Trip trip = data.trips.get(t);
        RigaJourneyPlanner.Prediction prediction = predictions.get(t);
        long delay = prediction == null ? 0 : prediction.delaySeconds * 1000L;
        for (int i = 0; i + 1 < trip.events.size(); ++i)
        {
          RigaJourneyPlanner.Event board = trip.events.get(i);
          if (board.stop != stop || !board.pickup || board.departure + delay < time + (rides == 0 ? 0 : 120000)
              || (prediction != null && i < prediction.firstBoardableEvent))
            continue;
          for (int j = i + 1; j < trip.events.size(); ++j)
          {
            RigaJourneyPlanner.Event alight = trip.events.get(j);
            long arrive = alight.arrival + delay;
            if (alight.dropOff && arrive < best[rides + 1][alight.stop])
            {
              best[rides + 1][alight.stop] = arrive;
              queue.add(new long[]{arrive, alight.stop, rides + 1});
            }
          }
        }
      }
    }
    return null;
  }

  @Test public void connectionScanMatchesAnExhaustiveOracleOnSmallTimetables()
  {
    java.util.Random random = new java.util.Random(20261120);
    for (int example = 0; example < 250; ++example)
    {
      List<RigaJourneyPlanner.Trip> trips = new ArrayList<>();
      Map<Integer, RigaJourneyPlanner.Prediction> predictions = new HashMap<>();
      for (int t = 0; t < 8; ++t)
      {
        List<RigaJourneyPlanner.Event> events = new ArrayList<>();
        long time = random.nextInt(30);
        for (int i = 0; i < 4; ++i)
        {
          events.add(event(random.nextInt(5), i + 1, time, random.nextInt(5) != 0, random.nextInt(5) != 0));
          time += 1 + random.nextInt(10);
        }
        trips.add(new RigaJourneyPlanner.Trip("t" + t, "r" + t, "22", "End", 3, "", 0, events));
        if (random.nextBoolean())
          predictions.put(t, new RigaJourneyPlanner.Prediction((random.nextInt(16) - 5) * 60,
                                                              random.nextInt(3), "v" + t, 0));
      }
      List<RigaJourneyPlanner.Footpath> walks = new ArrayList<>();
      if (example >= 125)
        for (int i = 0; i < 3; ++i)
          walks.add(new RigaJourneyPlanner.Footpath(random.nextInt(5), random.nextInt(5),
                                                    300 + random.nextInt(180), 100));
      RigaJourneyPlanner.Timetable data = table(5, trips, walks.toArray(new RigaJourneyPlanner.Footpath[0]));
      int from = random.nextInt(5);
      int to = random.nextInt(5);
      long[] expected = exhaustive(data, from, to, predictions);
      RigaJourneyPlanner.Journey actual = RigaJourneyPlanner.plan(data, "s" + from, "s" + to, 0,
                                                                  predictions, () -> false);
      assertEquals("reachability, example " + example, expected == null, actual == null);
      if (expected != null)
      {
        assertEquals("arrival, example " + example, expected[0], actual.arrival);
        assertEquals("boardings, example " + example, expected[1], actual.boardings);
      }
    }
  }

  @Test public void mapEndpointsChooseStopsUsingWalkingAndTransitArrivalTogether()
  {
    RigaJourneyPlanner.Timetable data = table(4, Arrays.asList(
        trip("near-but-late", new int[]{0, 3}, 5, 30),
        trip("farther-but-faster", new int[]{1, 2}, 8, 15)));
    List<RigaJourneyPlanner.Access> access = Arrays.asList(
        new RigaJourneyPlanner.Access("s0", 60, 50), new RigaJourneyPlanner.Access("s1", 300, 400));
    List<RigaJourneyPlanner.Access> egress = Arrays.asList(
        new RigaJourneyPlanner.Access("s3", 60, 50), new RigaJourneyPlanner.Access("s2", 120, 150));
    RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.planLocations(data, 56.95, 24.1, 56.96, 24.12,
        access, egress, 0, Collections.emptyMap(), () -> false);
    assertNotNull(journey);
    assertEquals(minute(17), journey.arrival);
    assertEquals(3, journey.legs.size());
    assertNull(journey.legs.get(0).trip);
    assertEquals(400, journey.legs.get(0).walkingMetres);
    assertEquals("farther-but-faster", journey.legs.get(1).trip.id);
    assertNull(journey.legs.get(2).trip);
    assertEquals(150, journey.legs.get(2).walkingMetres);
    assertEquals(4, data.stops.size()); // Do not alter the shared timetable cache.
    assertTrue(data.footpaths.isEmpty());
  }

  @Test public void mapEndpointsCannotBoardBeforeTheAccessWalkCompletes()
  {
    RigaJourneyPlanner.Timetable data = table(2, Collections.singletonList(trip("gone", new int[]{0, 1}, 2, 10)));
    assertNull(RigaJourneyPlanner.planLocations(data, 56.95, 24.1, 56.96, 24.12,
        Collections.singletonList(new RigaJourneyPlanner.Access("s0", 180, 200)),
        Collections.singletonList(new RigaJourneyPlanner.Access("s1", 60, 50)), 0, Collections.emptyMap(), () -> false));
    assertNull(RigaJourneyPlanner.planLocations(data, 56.95, 24.1, 56.96, 24.12,
        Collections.emptyList(), Collections.singletonList(new RigaJourneyPlanner.Access("s1", 60, 50)),
        0, Collections.emptyMap(), () -> false));
  }

}
