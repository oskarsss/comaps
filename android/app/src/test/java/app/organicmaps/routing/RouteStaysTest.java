package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.Context;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class RouteStaysTest
{
  private static RouteMarkData point(RouteMarkType type, double lat, double lon)
  {
    return new RouteMarkData("", "", type, 0, true, false, false, lat, lon);
  }

  @Test
  public void stopWithoutStayCountsZero()
  {
    final Context context = RuntimeEnvironment.getApplication();
    assertEquals(0, RouteStays.getMinutes(context, 10.0, 20.0));
  }

  @Test
  public void totalCountsOnlyIntermediatePoints()
  {
    final Context context = RuntimeEnvironment.getApplication();
    RouteStays.setMinutes(context, 1.0, 1.0, 45);
    RouteStays.setMinutes(context, 2.0, 2.0, 15);
    RouteStays.setMinutes(context, 3.0, 3.0, 60);

    final RouteMarkData[] points = {point(RouteMarkType.Start, 1.0, 1.0), point(RouteMarkType.Intermediate, 2.0, 2.0),
                                    point(RouteMarkType.Intermediate, 4.0, 4.0), point(RouteMarkType.Finish, 3.0, 3.0)};
    assertEquals(15 * 60, RouteStays.totalSeconds(context, points));
  }

  @Test
  public void stayIsKeptForTheSamePlace()
  {
    final Context context = RuntimeEnvironment.getApplication();
    RouteStays.setMinutes(context, 56.95, 24.1, 30);
    assertEquals(30, RouteStays.getMinutes(context, 56.950001, 24.100001));
  }
}
