package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;

import app.organicmaps.sdk.Router;
import org.junit.Test;

public class RouteModeTimesTest
{
  private static final double TOLERANCE = 0.10;

  private static void assertClose(double builtMinutes, double estimatedSeconds)
  {
    assertEquals(builtMinutes, estimatedSeconds / 60, builtMinutes * TOLERANCE);
  }

  @Test
  public void carEstimateMatchesBuiltRoutes()
  {
    assertClose(19, RouteModeTimes.driveSeconds(7100));
    assertClose(37, RouteModeTimes.driveSeconds(24000));
    assertClose(53, RouteModeTimes.driveSeconds(47000));
  }

  @Test
  public void walkAndBikeEstimatesMatchBuiltRoutes()
  {
    assertClose(110, RouteModeTimes.estimateSeconds(Router.Pedestrian, 7100, 0));
    assertClose(37, RouteModeTimes.estimateSeconds(Router.Bicycle, 7100, 0));
    assertClose(214, RouteModeTimes.estimateSeconds(Router.Bicycle, 47000, 0));
  }

  @Test
  public void tooFarToWalkOrRide()
  {
    assertEquals(RouteModeTimes.UNAVAILABLE, RouteModeTimes.estimateSeconds(Router.Pedestrian, 40001, 0));
    assertEquals(RouteModeTimes.UNAVAILABLE, RouteModeTimes.estimateSeconds(Router.Bicycle, 150001, 0));
  }
}
