package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The quick car, foot and bike estimates against routes the app built from Dārzciems, Riga (2026-11-25). */
public class RouteModeTimesTest
{
  /** Within this share of the built route's time. */
  private static final double CLOSE = 0.10;

  private static void close(double builtMinutes, double estimatedSeconds)
  {
    assertEquals(builtMinutes, estimatedSeconds / 60, builtMinutes * CLOSE);
  }

  @Test public void carTimesHoldInTownAndBetweenTowns()
  {
    close(19, RouteModeTimes.driveSeconds(7100));   // a field past Rumbula
    close(37, RouteModeTimes.driveSeconds(24000));  // Majori, Jūrmala
    close(53, RouteModeTimes.driveSeconds(47000));  // Sigulda
  }

  @Test public void walksAndRidesFollowStraighterRoadsWhenLonger()
  {
    double walk = 4.8 / 3.6, bike = 15 / 3.6;
    close(110, RouteModeTimes.road(7100) / walk);
    close(708, RouteModeTimes.road(47000) / walk);
    close(37, RouteModeTimes.road(7100) / bike);
    close(214, RouteModeTimes.road(47000) / bike);
  }
}
