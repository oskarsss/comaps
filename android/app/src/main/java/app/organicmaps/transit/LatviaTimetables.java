package app.organicmaps.transit;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.downloader.CountryItem;
import app.organicmaps.sdk.downloader.MapManager;
import app.organicmaps.sdk.util.ConnectionState;
import app.organicmaps.sdk.util.log.Logger;
import java.io.File;
import java.util.List;

/**
 * The static Latvian timetables are part of the Latvia map: they download and update with it, follow the
 * map downloader's mobile data rule, and are removed with it. Live feeds are separate (see Live feeds settings).
 */
public final class LatviaTimetables
{
  private static final String TAG = LatviaTimetables.class.getSimpleName();
  static final String COUNTRY = "Latvia";
  /** A map status event repeats; one successful check per this interval is enough. */
  private static final long RECHECK = 3600000L;

  private LatviaTimetables() {}

  /** Call once the map engine is ready. */
  public static void attach(@NonNull Context context)
  {
    Context app = context.getApplicationContext();
    MapManager.nativeSubscribe(new MapManager.StorageCallback() {
      @Override
      public void onStatusChanged(List<MapManager.StorageCallbackData> data)
      {
        for (MapManager.StorageCallbackData item : data)
        {
          if (!COUNTRY.equals(item.countryId))
            continue;
          if (item.newStatus == CountryItem.STATUS_DONE)
            update(app, true);
          else if (item.newStatus == CountryItem.STATUS_DOWNLOADABLE)
            remove(app);
        }
      }

      @Override
      public void onProgress(String countryId, long localSize, long remoteSize) {}
    });
    update(app, false);
  }

  /** Called when the app comes to the foreground: refreshes old timetables like map updates would. */
  public static void onForeground(@NonNull Context context)
  {
    update(context.getApplicationContext(), false);
  }

  static boolean mapDownloaded()
  {
    int status = MapManager.nativeGetStatus(COUNTRY);
    return status == CountryItem.STATUS_DONE || status == CountryItem.STATUS_UPDATABLE;
  }

  /** {@code mapChanged}: the Latvia map was just downloaded or updated, so fetch even a recent timetable. */
  static void update(Context app, boolean mapChanged)
  {
    if (!mapDownloaded())
      return;
    RigaTransitStore store = new RigaTransitStore(app);
    long now = System.currentTimeMillis();
    boolean needed = !store.exists() || RigaTransitFeeds.stale(store, now)
                     || (mapChanged && now - store.updated() > RECHECK);
    if (!needed || RigaTimetableInstaller.get(app).isRunning() || !ConnectionState.INSTANCE.isConnected())
      return;
    if (ConnectionState.INSTANCE.isMobileConnected() && !MapManager.nativeIsDownloadOn3gEnabled())
      return;
    Logger.i(TAG, "Updating Latvian timetables with the map");
    RigaTimetableInstaller.get(app).request(error -> {
      if (error != null)
        Logger.w(TAG, "Timetable update failed", error);
    });
  }

  static void remove(Context app)
  {
    if (RigaTimetableInstaller.get(app).isRunning())
      return;
    Logger.i(TAG, "Latvia map removed, removing its timetables");
    new RigaTransitStore(app).delete();
    File feeds = new File(app.getFilesDir(), "transit-feeds");
    File[] files = feeds.listFiles();
    if (files != null)
      for (File file : files)
        //noinspection ResultOfMethodCallIgnored
        file.delete();
  }
}
