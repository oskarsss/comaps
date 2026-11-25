package app.organicmaps.transit;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RigaLocationRouterTest
{
  private static final double START = 56.949;
  private static final double END = 56.981;
  private static RigaJourneyPlanner.Trip trip(String id, int from, int to, int departure, int arrival)
  {
    return new RigaJourneyPlanner.Trip(id, "r" + id, "15", "End", 11, "", 0, Arrays.asList(
        new RigaJourneyPlanner.Event(from, 1, departure * 1000L, departure * 1000L, true, true),
        new RigaJourneyPlanner.Event(to, 2, arrival * 1000L, arrival * 1000L, true, true)));
  }
  private static RigaJourneyPlanner.Stop stop(String id, double lat)
  { return new RigaJourneyPlanner.Stop(id, id, "", lat, 24.11); }
  private static double[] path(double from, double to, int seconds)
  { return new double[]{seconds, seconds, from, 24.11, to, 24.11}; }
  private static RigaJourneyPlanner.Timetable table()
  {
    return new RigaJourneyPlanner.Timetable(Arrays.asList(stop("a", 56.950), stop("b", 56.980)),
        Arrays.asList(trip("early", 0, 1, 300, 900), trip("later", 0, 1, 900, 1500)), Collections.emptyList());
  }
  @Test public void streetDetourMissesEarlyVehicleAndGeometryEndsAtMapPoints()
  {
    RigaLocationRouter router = new RigaLocationRouter((a,b,c,d) -> path(a,c,a == START ? 600 : 90));
    RigaLocationRouter.Result result = router.plan(table(), START,24.11,END,24.11,0,Collections.emptyMap(),()->false);
    assertNotNull(result.journey);
    assertEquals("later", result.journey.legs.get(1).trip.id);
    assertEquals(1590000, result.journey.arrival);
    assertEquals(START, result.paths.get(RigaLocationRouter.key(2,0))[2], 0);
    assertEquals(END, result.paths.get(RigaLocationRouter.key(1,3))[4], 0);
    assertEquals(2, result.paths.size());
  }
  @Test public void refinementDeadlineReturnsOnlyFullyVerifiedWalkingConnections()
  {
    AtomicInteger calls=new AtomicInteger();
    RigaLocationRouter router=new RigaLocationRouter((a,b,c,d)->{
      calls.incrementAndGet();return path(a,c,90);
    },()->calls.get() >= 2 ? 600000 : 0,()->calls.get() >= 2 ? 21000000000L : 0L);
    RigaLocationRouter.Result result=router.plan(table(),START,24.11,END,24.11,0,Collections.emptyMap(),()->false);
    assertNotNull(result.journey);assertTrue(result.journey.boardings>0);
    assertEquals("later",result.journey.legs.get(1).trip.id);
    for(RigaJourneyPlanner.Leg leg:result.journey.legs)
      if(leg.trip==null) assertTrue(result.paths.containsKey(RigaLocationRouter.key(leg.from,leg.to)));
    assertEquals(2,calls.get());
  }
  @Test public void aLongWaitDoesNotTryEveryWalkToTheSameVehicle()
  {
    // At night: one bus in an hour, stopping at three stops near the start. Any of them catches it, so once the
    // walks of the first answer are routed and still catch the bus, the search stops.
    RigaJourneyPlanner.Trip bus = new RigaJourneyPlanner.Trip("night", "rnight", "N", "End", 3, "", 0, Arrays.asList(
        new RigaJourneyPlanner.Event(0, 1, 3600000L, 3600000L, true, true),
        new RigaJourneyPlanner.Event(1, 2, 3660000L, 3660000L, true, true),
        new RigaJourneyPlanner.Event(2, 3, 3720000L, 3720000L, true, true),
        new RigaJourneyPlanner.Event(3, 4, 5000000L, 5000000L, true, true)));
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(Arrays.asList(stop("a", 56.950),
        stop("b", 56.951), stop("c", 56.952), stop("d", 56.980)), Collections.singletonList(bus),
        Collections.emptyList());
    AtomicInteger routed = new AtomicInteger();
    RigaLocationRouter router = new RigaLocationRouter((a,b,c,d) -> { routed.incrementAndGet(); return path(a,c,400); },
                                                       () -> 0);
    RigaLocationRouter.Result result = router.plan(data,START,24.11,END,24.11,0,Collections.emptyMap(),()->false);
    assertNotNull(result.journey);
    assertEquals(1, result.journey.boardings);
    for (RigaJourneyPlanner.Leg leg : result.journey.legs) if (leg.trip != null) assertEquals("night", leg.trip.id);
    assertEquals(5400000, result.journey.arrival);
    // Each walk is routed at most once: three to the stops near the start, one to the destination.
    assertTrue("routed " + routed.get(), routed.get() <= 4);
    for(RigaJourneyPlanner.Leg leg:result.journey.legs)
      if(leg.trip==null) assertTrue(result.paths.containsKey(RigaLocationRouter.key(leg.from,leg.to)));
  }
  @Test public void deadlineWithAnUnverifiedExitStillFailsRatherThanInventingAWalk()
  {
    AtomicInteger calls=new AtomicInteger();
    RigaLocationRouter router=new RigaLocationRouter((a,b,c,d)->{
      calls.incrementAndGet();return path(a,c,90);
    },()->0,()->calls.get() >= 1 ? 21000000000L : 0L);
    assertThrows(IllegalStateException.class,()->router.plan(table(),START,24.11,END,24.11,0,
        Collections.emptyMap(),()->false));
    assertEquals(1,calls.get());
  }

  @Test public void inaccessibleOriginDoesNotInventStraightLineWalking()
  {
    RigaLocationRouter router = new RigaLocationRouter((a,b,c,d) -> a == START ? new double[0] : path(a,c,90));
    assertNull(router.plan(table(),START,24.11,END,24.11,0,Collections.emptyMap(),()->false).journey);
  }
  @Test public void transferMustBeReplannedUsingActualWalkingTime()
  {
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(Arrays.asList(
        stop("a",56.950),stop("b",56.965),stop("c",56.966),stop("d",56.980)),
        Arrays.asList(trip("first",0,1,300,600),trip("missed",2,3,780,1000),trip("later",2,3,1200,1500)),
        Arrays.asList(new RigaJourneyPlanner.Footpath(1,2,45,45)));
    RigaLocationRouter router = new RigaLocationRouter((a,b,c,d) -> path(a,c,a == 56.965 ? 480 : 90));
    RigaLocationRouter.Result result = router.plan(data,START,24.11,END,24.11,0,Collections.emptyMap(),()->false);
    assertNotNull(result.journey);
    assertEquals("later", result.journey.legs.get(3).trip.id);
    assertEquals(480000, result.journey.legs.get(2).arrival-result.journey.legs.get(2).departure);
  }
  @Test public void walksKnownFromEarlierSearchesAreNotRoutedAgain()
  {
    AtomicInteger routed = new AtomicInteger();
    RigaLocationRouter router = new RigaLocationRouter(new RigaLocationRouter.Walker() {
      @Override public double[] route(double a, double b, double c, double d)
      { routed.incrementAndGet(); return path(a, c, 90); }
      @Override public double[] known(double a, double b, double c, double d)
      { return path(a, c, a == START ? 600 : 90); }
    });
    RigaLocationRouter.Result result = router.plan(table(), START,24.11,END,24.11,0,Collections.emptyMap(),()->false);
    assertEquals(0, routed.get());
    // The known 600 s access walk misses the early vehicle without any refinement round.
    assertEquals("later", result.journey.legs.get(1).trip.id);
  }
  @Test public void cancellationStopsFurtherPedestrianQueries()
  {
    AtomicInteger calls = new AtomicInteger();
    RigaLocationRouter router = new RigaLocationRouter((a,b,c,d) -> {calls.incrementAndGet(); return path(a,c,90);});
    assertThrows(CancellationException.class, () -> router.plan(table(),START,24.11,END,24.11,0,
        Collections.emptyMap(),()->calls.get()>0));
    assertEquals(1,calls.get());
  }
  @Test public void oversizedAndMalformedWalkingResultsAreRejected()
  {
    for (double[] bad : Arrays.asList(new double[]{1,1},path(START,56.95,1801),path(Double.NaN,56.95,90)))
    {
      RigaLocationRouter router = new RigaLocationRouter((a,b,c,d)->bad);
      assertNull(router.plan(table(),START,24.11,END,24.11,0,Collections.emptyMap(),()->false).journey);
    }
  }
  @Test public void pedestrianQueriesHaveAFixedCandidateBound()
  {
    List<RigaJourneyPlanner.Stop> stops = new ArrayList<>();
    for (int i = 0; i < 40; ++i) stops.add(stop("s" + i,(i < 20 ? 56.950 : 56.980)+(i%20)*0.00001));
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(stops,Arrays.asList(trip("bus",0,20,300,900)),Collections.emptyList());
    AtomicInteger calls = new AtomicInteger();
    RigaLocationRouter router = new RigaLocationRouter((a,b,c,d)->{if(a==START || c==END) calls.incrementAndGet();return path(a,c,90);});
    RigaLocationRouter.Result result=router.plan(data,START,24.11,END,24.11,0,Collections.emptyMap(),()->false);
    assertNotNull(result.journey);
    assertTrue(calls.get()>0 && calls.get()<=32);
    assertEquals(32,result.data.footpaths.stream().filter(edge -> edge.from==40 || edge.to==41).count());
  }
  @Test public void timeSpentFindingWalkingPathsCannotBoardAPastVehicle()
  {
    RigaLocationRouter router = new RigaLocationRouter((a,b,c,d)->path(a,c,90),()->600000);
    RigaLocationRouter.Result result = router.plan(table(),START,24.11,END,24.11,0,Collections.emptyMap(),()->false);
    assertEquals("later",result.journey.legs.get(1).trip.id);
    assertEquals(600000,result.journey.departure);
  }

  @Test public void transportRequestRequiresARideEvenWhenWalkingOnlyIsFaster()
  {
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(
        Arrays.asList(stop("a",56.950),stop("b",56.960)),
        Arrays.asList(trip("bus",0,1,300,900)),
        Arrays.asList(new RigaJourneyPlanner.Footpath(0,1,60,1000)));
    RigaLocationRouter router = new RigaLocationRouter((a,b,c,d)->path(a,c,90));
    RigaLocationRouter.Result result = router.plan(data,START,24.11,56.961,24.11,0,Collections.emptyMap(),()->false);
    assertNotNull(result.journey);
    assertEquals(1,result.journey.boardings);
    assertTrue(result.journey.legs.stream().anyMatch(leg -> leg.trip != null));
  }
  @Test public void chainedNearbyTransfersCannotBypassWalkingDistanceLimit()
  {
    RigaJourneyPlanner.Timetable data = new RigaJourneyPlanner.Timetable(
        Arrays.asList(stop("a",56.950),stop("b",56.965),stop("c",56.980)),
        Arrays.asList(trip("bus",1,2,600,900)),
        Arrays.asList(new RigaJourneyPlanner.Footpath(0,1,60,2000)));
    assertNull(RigaJourneyPlanner.plan(data,"a","c",0,Collections.emptyMap(),()->false,
        RigaJourneyPlanner.DEFAULT_LIMITS,true));
    assertNotNull(RigaJourneyPlanner.plan(data,"a","c",0,Collections.emptyMap(),()->false));
  }

}
