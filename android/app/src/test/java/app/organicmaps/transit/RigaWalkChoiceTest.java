package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class RigaWalkChoiceTest
{
  private static final long MIN = 60000L;

  /** A journey as {leave, arrival} in minutes. */
  private static long[] j(long leave, long arrival) { return new long[] {leave * MIN, arrival * MIN}; }

  private static List<long[]> merge(List<long[]> rides, long[] walk, boolean arriveBy)
  {
    return RigaWalkChoice.merge(rides, walk, x -> x[0], x -> x[1], arriveBy);
  }

  @Test public void aShortWalkThatGetsThereFirstIsTheOnlyAnswer()
  {
    // The screenshot: 1 min on foot against 7 min to the stop, one stop of bus 48 and 5 min back.
    long[] walk = j(0, 1);
    List<long[]> merged = merge(Arrays.asList(j(4, 16), j(7, 24)), walk, false);
    assertEquals(Collections.singletonList(walk), merged);
  }

  @Test public void aLongerWalkThatGetsThereFirstLeadsTheRides()
  {
    long[] walk = j(0, 25), bus = j(10, 30);
    assertEquals(Arrays.asList(walk, bus), merge(Collections.singletonList(bus), walk, false));
  }

  @Test public void aWalkArrivingWithTheBestRideStillWins()
  {
    long[] walk = j(0, 20), bus = j(5, 20);
    assertEquals(walk, merge(Collections.singletonList(bus), walk, false).get(0));
  }

  @Test public void aSlowerWalkGoesBeforeTheFirstRideItBeatsUnlessItIsLong()
  {
    long[] walk = j(0, 22), bus = j(3, 20), later = j(10, 30);
    assertEquals(Arrays.asList(bus, walk, later), merge(Arrays.asList(bus, later), walk, false));
    long[] slowest = j(0, 35);
    assertEquals(Arrays.asList(bus, later, slowest), merge(Arrays.asList(bus, later), slowest, false));
    long[] long_ = j(0, 50);
    assertEquals(Arrays.asList(bus, later), merge(Arrays.asList(bus, later), long_, false));
  }

  @Test public void laterRidesDoNotPushBackAWalkThatArrivesFirst()
  {
    // The screenshot: walking 19:12-19:55 against rides arriving 19:36, 19:43, 20:08 and 20:15 ("Later").
    long[] a = j(0, 24), b = j(6, 31), walk = j(0, 43), c = j(32, 56), d = j(38, 63);
    assertEquals(Arrays.asList(a, b, walk, c, d), merge(Arrays.asList(a, b, c, d), walk, false));
  }

  @Test public void withoutRidesTheWalkIsTheAnswer()
  {
    long[] walk = j(0, 70);
    assertEquals(Collections.singletonList(walk), merge(Collections.emptyList(), walk, false));
  }

  @Test public void noWalkLeavesTheRides()
  {
    List<long[]> rides = Collections.singletonList(j(3, 20));
    assertEquals(rides, merge(rides, null, false));
  }

  @Test public void arrivingByTheWalkWinsWhenItLeavesNoEarlier()
  {
    // Arrive by 60: the bus leaves at 45, walking 12 min leaves at 48.
    long[] walk = j(48, 60), bus = j(45, 58);
    assertEquals(Collections.singletonList(walk), merge(Collections.singletonList(bus), walk, true));
    // Walking 20 min leaves at 40, before the bus: the bus leads.
    long[] slow = j(40, 60);
    assertEquals(Arrays.asList(bus, slow), merge(Collections.singletonList(bus), slow, true));
  }
}
