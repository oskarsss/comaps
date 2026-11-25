package app.organicmaps.transit;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = RigaTransitActivityTest.TestApplication.class,
        shadows = {RigaTransitActivityTest.ShadowCore.class, RigaJourneyActivityTest.ShadowFramework.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class RigaJourneyActivityTest
{
  @Implements(Framework.class)
  public static class ShadowFramework
  {
    static double[][] lines;
    static boolean[] walking;
    static int manualCenters;
    @Implementation protected static void nativeSetRigaJourney(double[][] value, boolean[] onFoot)
    { lines = value; walking = onFoot; }
    @Implementation protected static void nativeSetViewportCenter(double lat, double lon, int zoom)
    { ++manualCenters; }
  }
  private static class Queue extends AbstractExecutorService
  {
    final ArrayDeque<Runnable> requests = new ArrayDeque<>();
    @Override public void execute(Runnable task) { requests.add(task); }
    @Override public void shutdown() {}
    @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
    @Override public boolean isShutdown() { return false; }
    @Override public boolean isTerminated() { return false; }
    @Override public boolean awaitTermination(long value, TimeUnit unit) { return true; }
    void finish()
    {
      while (!requests.isEmpty())
      {
        requests.remove().run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
      }
    }
  }

  private ActivityController<RigaJourneyActivity> screen(Queue queue, RigaJourneyActivity.Calculator calculator,
                                                         boolean live) throws Exception
  {
    RigaJourneyDatabaseTest.fixture();
    Bundle saved = new Bundle();
    saved.putString("from", "a"); saved.putString("fromName", "A");
    saved.putString("to", "c"); saved.putString("toName", "C");
    saved.putBoolean("live", live);
    ActivityController<RigaJourneyActivity> controller = Robolectric.buildActivity(RigaJourneyActivity.class);
    controller.get().setTheme(R.style.MwmTheme);
    controller.get().mWorker = queue;
    controller.get().mCalculator = calculator;
    return controller.create(saved).start().resume().visible();
  }

  private RigaJourneyService.Result result(boolean failed) throws Exception
  {
    RigaJourneyDatabase database = new RigaJourneyDatabase(new RigaTransitStore(org.robolectric.RuntimeEnvironment.getApplication()));
    long departure = Instant.parse("2026-11-20T05:55:00Z").toEpochMilli();
    RigaJourneyPlanner.Timetable data = database.load(departure, () -> false);
    RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(data, "a", "c", departure,
                                                                Collections.emptyMap(), () -> false);
    return new RigaJourneyService.Result(data, journey, 0, departure, failed, new double[0][], new boolean[0]);
  }

  @Test public void liveFailureShowsScheduledFallbackAndPauseCancelsPolling() throws Exception
  {
    Queue queue = new Queue();
    AtomicInteger calls = new AtomicInteger();
    ActivityController<RigaJourneyActivity> screen = screen(queue, (from, to, departure, live, cancelled) -> {
      calls.incrementAndGet(); assertTrue(live); return result(true);
    }, true);
    queue.finish();
    assertEquals(1, calls.get());
    String status = ((TextView) screen.get().findViewById(R.id.riga_journey_status)).getText().toString();
    assertTrue(status.contains("Showing scheduled times"));
    assertTrue(status.contains("Arrive"));
    assertEquals(View.GONE, screen.get().findViewById(R.id.riga_journey_map).getVisibility());
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS);
    assertEquals(1, queue.requests.size());
    screen.pause().stop();
    queue.finish(); // Cancelled future must not call the calculator.
    assertEquals(1, calls.get());
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS);
    assertTrue(queue.requests.isEmpty());
    screen.start().resume();
    queue.finish();
    assertEquals(2, calls.get());
    screen.pause().stop().destroy();
  }

  @Test public void completionWhileStoppedCannotRenderAndResumeRecalculates() throws Exception
  {
    Queue queue = new Queue();
    ActivityController<RigaJourneyActivity> screen = screen(queue, (from, to, departure, live, cancelled) -> result(false), false);
    queue.requests.remove().run(); // Posted completion, not dispatched yet.
    screen.pause().stop();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, ((android.widget.LinearLayout) screen.get().findViewById(R.id.riga_legs)).getChildCount());
    screen.start().resume();
    queue.finish();
    assertTrue(((TextView) screen.get().findViewById(R.id.riga_journey_status)).getText().toString().contains("Scheduled times"));
    assertTrue(((android.widget.LinearLayout) screen.get().findViewById(R.id.riga_legs)).getChildCount() > 0);
    screen.pause().stop().destroy();
  }

  @Test public void switchingOffLiveCancelsQueuedOnlineWorkAndErrorsAreVisible() throws Exception
  {
    Queue queue = new Queue();
    AtomicInteger online = new AtomicInteger();
    ActivityController<RigaJourneyActivity> screen = screen(queue, (from, to, departure, live, cancelled) -> {
      if (live) online.incrementAndGet();
      throw new java.io.IOException("fake database failure");
    }, true);
    ((MaterialSwitch) screen.get().findViewById(R.id.riga_estimates)).setChecked(false);
    queue.finish();
    assertEquals(0, online.get());
    assertTrue(((TextView) screen.get().findViewById(R.id.riga_journey_status)).getText().toString().contains("fake database failure"));
    screen.pause().stop().destroy();
  }

  @Test public void supersededCompletionCannotReplaceTheCurrentRequest() throws Exception
  {
    Queue queue = new Queue();
    ActivityController<RigaJourneyActivity> screen = screen(queue,
        (from, to, departure, live, cancelled) -> result(live), true);
    queue.requests.remove().run();
    ((MaterialSwitch) screen.get().findViewById(R.id.riga_estimates)).setChecked(false);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals(screen.get().getString(R.string.riga_planning),
                 ((TextView) screen.get().findViewById(R.id.riga_journey_status)).getText().toString());
    queue.finish();
    assertTrue(((TextView) screen.get().findViewById(R.id.riga_journey_status)).getText().toString().contains("Scheduled times"));
    screen.pause().stop().destroy();
  }

  @Test public void mapPreviewPassesGeometryAndKeepsTheNativeViewportFit() throws Exception
  {
    Queue queue = new Queue();
    double[][] lines = {{56.95, 24.10, 56.95, 24.12}};
    ShadowFramework.manualCenters = 0;
    ActivityController<RigaJourneyActivity> screen = screen(queue, (from, to, departure, live, cancelled) -> {
      RigaJourneyService.Result base = result(false);
      return new RigaJourneyService.Result(base.data, base.journey, 0, 0, false, lines, new boolean[]{false});
    }, false);
    queue.finish();
    screen.get().findViewById(R.id.riga_journey_map).performClick();
    assertArrayEquals(lines[0], ShadowFramework.lines[0], 0);
    assertArrayEquals(new boolean[]{false}, ShadowFramework.walking);
    assertEquals("A fixed centre/zoom must not override the viewport fitted by nativeSetRigaJourney", 0,
                 ShadowFramework.manualCenters);
    assertEquals(app.organicmaps.MwmActivity.class.getName(),
                 Shadows.shadowOf(screen.get()).getNextStartedActivity().getComponent().getClassName());
    assertTrue(screen.get().isFinishing());
    screen.pause().stop().destroy();
  }
}
