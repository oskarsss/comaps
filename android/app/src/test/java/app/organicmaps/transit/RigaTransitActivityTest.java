package app.organicmaps.transit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.OrganicMaps;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = RigaTransitActivityTest.TestApplication.class,
        shadows = RigaTransitActivityTest.ShadowCore.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RigaTransitActivityTest
{
  public static class TestApplication extends MwmApplication
  {
    private final OrganicMaps mCore = mock(OrganicMaps.class);
    @Override public void onCreate()
    {
      when(mCore.arePlatformAndCoreInitialized()).thenReturn(true);
    }
    @Override public OrganicMaps getOrganicMaps() { return mCore; }
  }

  @Implements(OrganicMaps.class)
  public static class ShadowCore
  {
    @Implementation protected static void nativeSetContext(Context context) {}
  }

  private final ArrayDeque<Runnable> mRequests = new ArrayDeque<>();

  private ActivityController<RigaTransitActivity> screen()
  {
    ActivityController<RigaTransitActivity> controller = Robolectric.buildActivity(RigaTransitActivity.class);
    controller.get().setTheme(R.style.MwmTheme);
    controller.get().mWorker = mRequests::add;
    controller.get().mVehicleFetcher = () -> RigaVehicle.parse("1,22,24197320,56930187,0,214,L,16351,a-b,1335,0,\n");
    return controller.create().start().resume().visible();
  }

  private void finishRequests()
  {
    while (!mRequests.isEmpty())
    {
      mRequests.remove().run();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }

  private void layout(RigaTransitActivity activity)
  {
    View root = activity.getWindow().getDecorView();
    root.forceLayout();
    root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                 View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, 360, 800);
  }

  @Test
  public void filteringKeepsPollingAndPausedCompletionsReleaseTheRequest()
  {
    ActivityController<RigaTransitActivity> controller = screen();
    RigaTransitActivity activity = controller.get();
    finishRequests();
    MaterialSwitch live = activity.findViewById(R.id.riga_live);
    live.setChecked(true);
    layout(activity);
    int top = activity.findViewById(R.id.riga_map).getTop();
    finishRequests();
    layout(activity);
    assertEquals("Controls must not move when the response arrives", top, activity.findViewById(R.id.riga_map).getTop());
    ((EditText) activity.findViewById(R.id.riga_search)).setText("22");
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS);
    assertEquals("Filtering must not cancel the next poll", 1, mRequests.size());
    mRequests.remove().run();
    controller.pause().stop(); // Response posted, but not yet dispatched.
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    controller.start().resume();
    assertEquals("The old completion must release loading", 1, mRequests.size());
    finishRequests();
    assertEquals(1, ((ListView) activity.findViewById(R.id.riga_list)).getCount());
    activity.mVehicleFetcher = () -> { throw new java.net.SocketTimeoutException("no answer"); };
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(15, TimeUnit.SECONDS);
    assertEquals(0, ((ListView) activity.findViewById(R.id.riga_list)).getCount());
    finishRequests();
    assertEquals(activity.getString(R.string.riga_live_error),
                 ((TextView) activity.findViewById(R.id.riga_status)).getText().toString());
    controller.pause().stop().destroy();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS);
    assertTrue(mRequests.isEmpty());
  }

  private File fixture(Context context) throws Exception
  {
    File archive = File.createTempFile("riga-recreate", ".zip", context.getCacheDir());
    String[][] entries = {
        {"stops", "stop_id,stop_name,stop_lat,stop_lon\ns,Centrs,56.95,24.1\n"},
        {"routes", "route_id,route_short_name,route_type\nr,22,3\n"},
        {"trips", "trip_id,route_id,service_id,trip_headsign\nt,r,s,Airport\n"},
        {"calendar_dates", "service_id,date,exception_type\ns,20261119,1\n"},
        {"stop_times", "trip_id,stop_id,departure_time,pickup_type\nt,s,12:00:00,0\n"}
    };
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive)))
    {
      for (String[] entry : entries)
      {
        zip.putNextEntry(new ZipEntry(entry[0] + ".txt"));
        zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return archive;
  }

  @Test
  public void recreationDuringImportKeepsOneDownloadAndReenablesTheNewScreen() throws Exception
  {
    ActivityController<RigaTransitActivity> old = screen();
    RigaTransitStore store = new RigaTransitStore(old.get());
    File archive = fixture(old.get());
    store.install(archive);
    AtomicInteger calls = new AtomicInteger();
    old.get().mTimetableFetcher = () -> { calls.incrementAndGet(); return archive; };
    ((Button) old.get().findViewById(R.id.riga_download)).performClick();
    assertFalse(old.get().findViewById(R.id.riga_download).isEnabled());
    old.pause().stop().destroy();
    ActivityController<RigaTransitActivity> recreated = screen();
    assertFalse(recreated.get().findViewById(R.id.riga_download).isEnabled());
    finishRequests();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
    finishRequests();
    assertEquals(1, calls.get());
    assertEquals(1, ((ListView) recreated.get().findViewById(R.id.riga_list)).getCount());
    assertTrue("The new Activity must observe completion", recreated.get().findViewById(R.id.riga_download).isEnabled());
    recreated.pause().stop().destroy();
  }

  @Test
  public void stoppedLiveFailureCanResumeAndDestroyedImportFailureReleasesNewScreen()
  {
    ActivityController<RigaTransitActivity> first = screen();
    finishRequests();
    first.get().mVehicleFetcher = () -> { throw new java.net.SocketTimeoutException("no answer"); };
    ((MaterialSwitch) first.get().findViewById(R.id.riga_live)).setChecked(true);
    first.pause().stop();
    finishRequests();
    assertTrue(mRequests.isEmpty());
    first.get().mVehicleFetcher = () -> RigaVehicle.parse("1,22,24197320,56930187,0,214,L,16351,a-b,1335,0,\n");
    first.start().resume();
    finishRequests();
    assertEquals(1, ((ListView) first.get().findViewById(R.id.riga_list)).getCount());
    first.get().mTimetableFetcher = () -> { throw new java.net.SocketTimeoutException("archive never answers"); };
    ((Button) first.get().findViewById(R.id.riga_download)).performClick();
    first.pause().stop().destroy();
    ActivityController<RigaTransitActivity> next = screen();
    finishRequests();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
    finishRequests();
    assertTrue(next.get().findViewById(R.id.riga_download).isEnabled());
    next.pause().stop().destroy();
  }
}
