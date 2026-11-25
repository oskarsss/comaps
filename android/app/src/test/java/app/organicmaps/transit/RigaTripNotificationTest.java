package app.organicmaps.transit;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitStepType;
import java.time.ZonedDateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** What the ongoing trip notification and the get-off alert say. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class RigaTripNotificationTest
{
  private static final long MIN = 60000;
  private static final long T = ZonedDateTime.parse("2026-11-23T08:30:00+02:00[Europe/Riga]").toInstant().toEpochMilli();

  private static TransitJourneyLeg.Stop stop(String name, long time, double lat)
  {
    return new TransitJourneyLeg.Stop(name, time, time, lat, 24.10);
  }

  private static TransitJourneyLeg[] legs()
  {
    return new TransitJourneyLeg[] {
        new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 300, false,
            new TransitJourneyLeg.Stop[] {stop("Home", T - 5 * MIN, 56.900), stop("Merķeļa iela", T, 56.903)}),
        new TransitJourneyLeg(TransitStepType.TROLLEYBUS, "22", "Lubāna iela", 0xff0098d8, 0, true,
            new TransitJourneyLeg.Stop[] {stop("Merķeļa iela", T, 56.903), stop("B", T + 4 * MIN, 56.915),
                stop("C", T + 8 * MIN, 56.927), stop("Lubānas iela", T + 12 * MIN, 56.940)}),
        new TransitJourneyLeg(TransitStepType.PEDESTRIAN, "", "", 0, 200, false,
            new TransitJourneyLeg.Stop[] {stop("Lubānas iela", T + 12 * MIN, 56.940), stop("Work", T + 15 * MIN, 56.942)})};
  }

  private static Context context() { return RuntimeEnvironment.getApplication(); }

  @Test public void ridingShowsWhatToDoAndWhichRide()
  {
    RigaTripGuide.State riding = RigaTripGuide.of(legs(), T + 2 * MIN, 56.914, 24.10);
    assertEquals("Get off in 3 stops: Lubānas iela (08:42)",
        RigaTripGuide.headline(context(), legs(), riding, T + 2 * MIN));
    assertEquals("Trolleybus 22 → Lubāna iela, arrive 08:45", RigaTripGuide.context(context(), legs(), riding));
  }

  @Test public void beforeSettingOffTheNextRideIsNamed()
  {
    RigaTripGuide.State leave = RigaTripGuide.of(legs(), T - 10 * MIN, Double.NaN, Double.NaN);
    assertEquals("Trolleybus 22 → Lubāna iela, arrive 08:45", RigaTripGuide.context(context(), legs(), leave));
  }

  @Test public void theFinalWalkShowsOnlyTheArrival()
  {
    RigaTripGuide.State walk = RigaTripGuide.of(legs(), T + 13 * MIN, 56.941, 24.10);
    assertEquals(RigaTripGuide.Phase.WALK, walk.phase);
    assertEquals("Arrive 08:45", RigaTripGuide.context(context(), legs(), walk));
  }

  @Test public void theGetOffAlertNamesTheStopAndTime()
  {
    RigaTripGuide.State next = RigaTripGuide.of(legs(), T + 9 * MIN, Double.NaN, Double.NaN);
    assertEquals("off:1", next.alert);
    assertEquals("Get off at the next stop", RigaTripGuide.alertTitle(context(), legs(), next));
    assertEquals("Next stop: Lubānas iela, 08:42", RigaTripGuide.alertText(context(), legs(), next, "ignored"));
  }
  @Test public void theArrivalAlertDoesNotRepeatItsTitle()
  {
    RigaTripGuide.State arrived = RigaTripGuide.of(legs(), T + 15 * MIN, 56.942, 24.10);
    assertEquals("arrived", arrived.alert);
    assertEquals("You have arrived", RigaTripGuide.alertTitle(context(), legs(), arrived));
    assertTrue(RigaTripGuide.alertText(context(), legs(), arrived, "You have arrived").startsWith("Work at "));
  }

  @Test public void liveUpdateTitlesFollowTheSteps()
  {
    RigaTripGuide.State leave = RigaTripGuide.of(legs(), T - 9 * MIN, Double.NaN, Double.NaN);
    assertEquals("Leave in 4 min", RigaTripGuide.liveTitle(context(), legs(), leave, T - 9 * MIN));
    assertEquals("Merķeļa iela · trolleybus 22 at 08:30", RigaTripGuide.liveText(context(), legs(), leave, T - 9 * MIN));
    assertEquals("4 min", RigaTripGuide.chip(context(), leave, T - 9 * MIN));
    RigaTripGuide.State waiting = RigaTripGuide.of(legs(), T - 3 * MIN, 56.9031, 24.10);
    assertEquals("Take trolleybus 22", RigaTripGuide.liveTitle(context(), legs(), waiting, T - 3 * MIN));
    assertEquals("Lubāna iela · departs 08:30", RigaTripGuide.liveText(context(), legs(), waiting, T - 3 * MIN));
    RigaTripGuide.State riding = RigaTripGuide.of(legs(), T + 4 * MIN, 56.915, 24.10);
    assertEquals("Get off in 2 stops", RigaTripGuide.liveTitle(context(), legs(), riding, T + 4 * MIN));
    assertEquals("Lubānas iela · 08:42", RigaTripGuide.liveText(context(), legs(), riding, T + 4 * MIN));
    assertEquals("2 stops", RigaTripGuide.chip(context(), riding, T + 4 * MIN));
  }
}
