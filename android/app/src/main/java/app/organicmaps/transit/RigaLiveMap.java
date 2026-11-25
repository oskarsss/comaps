package app.organicmaps.transit;

import android.os.Handler;
import android.os.Looper;
import app.organicmaps.sdk.Framework;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

/** Opt-in, foreground-only positions. No location permission or background service is needed. */
public final class RigaLiveMap
{
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static boolean sEnabled;
  private static String sRoute = "";
  interface Fetcher { List<RigaVehicle> fetch() throws Exception; }
  interface MarkerSink { void replace(double[] coordinates, String[] labels); }
  private final Handler mHandler;
  private final Executor mWorker;
  private final Fetcher mFetcher;
  private final MarkerSink mSink;
  private final Runnable mRefresh = this::refresh;
  private final Runnable mExpire = this::clear;
  private boolean mVisible;
  private boolean mLoading;
  private int mGeneration;

  public RigaLiveMap()
  {
    this(new Handler(Looper.getMainLooper()), WORKER, RigaTransitNetwork::vehicles, Framework::nativeSetLiveVehicles);
  }

  RigaLiveMap(Handler handler, Executor worker, Fetcher fetcher, MarkerSink sink)
  {
    mHandler = handler;
    mWorker = worker;
    mFetcher = fetcher;
    mSink = sink;
  }

  public static boolean isEnabled() { return sEnabled; }

  static void enable(String route)
  {
    sRoute = route;
    sEnabled = true;
  }

  static void disable()
  {
    sEnabled = false;
    Framework.nativeSetLiveVehicles(new double[0], new String[0]);
  }

  private void clear()
  {
    mSink.replace(new double[0], new String[0]);
  }

  public void resume()
  {
    mVisible = true;
    refresh();
  }

  public void pause()
  {
    mVisible = false;
    ++mGeneration;
    mHandler.removeCallbacks(mRefresh);
    mHandler.removeCallbacks(mExpire);
    clear();
  }

  private void refresh()
  {
    mHandler.removeCallbacks(mRefresh);
    if (!mVisible || !sEnabled)
      return;
    if (mLoading)
    {
      mHandler.postDelayed(mRefresh, 1000);
      return;
    }
    mLoading = true;
    int generation = mGeneration;
    String filter = sRoute;
    mWorker.execute(() -> {
      List<RigaVehicle> vehicles;
      try
      {
        vehicles = mFetcher.fetch();
      }
      catch (Exception e)
      {
        vehicles = java.util.Collections.emptyList();
      }
      List<RigaVehicle> result = vehicles;
      mHandler.post(() -> {
        // Always release the request, including after pause; never remove this callback.
        mLoading = false;
        if (!mVisible || !sEnabled || generation != mGeneration)
          return;
        int count = 0;
        for (RigaVehicle vehicle : result)
          if (filter.isEmpty() || vehicle.route.equalsIgnoreCase(filter))
            ++count;
        double[] coordinates = new double[count * 2];
        String[] labels = new String[count];
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        time.setTimeZone(RigaTransitStore.ZONE);
        String received = time.format(new Date());
        int i = 0;
        for (RigaVehicle vehicle : result)
        {
          if (!filter.isEmpty() && !vehicle.route.equalsIgnoreCase(filter))
            continue;
          coordinates[i * 2] = vehicle.lat;
          coordinates[i * 2 + 1] = vehicle.lon;
          // The source supplies no observation time, so explicitly label receipt time.
          labels[i] = vehicle.route + " · " + vehicle.id + " · " + received + " (saraksti.lv)";
          ++i;
        }
        mSink.replace(coordinates, labels);
        mHandler.removeCallbacks(mExpire);
        mHandler.postDelayed(mExpire, 45000);
        mHandler.postDelayed(mRefresh, 15000);
      });
    });
  }
}
