package app.organicmaps.transit;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import app.organicmaps.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** The Rīgas satiksme live feed (saraksti.lv): off until the rider agrees once; changed later in Settings. */
public final class RigaLiveSettings
{
  private static final String LIVE = "live";
  private static final String ASKED = "asked";

  /**
   * Rīgas satiksme riders validate in these apps; the QR sticker by every door carries rsat://a/<fleet number>,
   * and the fleet number comes from this feed.
   */
  static final TransitTicketing TICKETING =
      new TransitTicketing("rigas-satiksme", "rsat://a/{vehicle}", "lv.mobilly.android", "com.flutter.rspassenger",
                           "com.ntacticsoftware.rigapublictransport");

  private RigaLiveSettings() {}
  public static SharedPreferences preferences(Context context) { return context.getSharedPreferences("riga-routing",0); }

  /** The feed covers Rīga, so it is offered with the Latvia map. */
  public static boolean available()
  {
    return LatviaTimetables.mapDownloaded();
  }

  /** Whether the feed follows a trip: it tracks Rīgas satiksme vehicles, whose timetable alone has no id prefix. */
  static boolean tracks(String tripId)
  {
    for (RigaTransitFeeds.Source source : RigaTransitFeeds.SOURCES)
      if (!source.prefix.isEmpty() && tripId.startsWith(source.prefix))
        return false;
    return !tripId.isEmpty();
  }

  public static boolean live(Context context)
  {
    return preferences(context).getBoolean(LIVE, false);
  }

  /** A choice in Settings is the rider's answer too, so the first-use question is not asked afterwards. */
  public static void setLive(Context context, boolean live)
  {
    preferences(context).edit().putBoolean(LIVE, live).putBoolean(ASKED, true).apply();
  }

  public static boolean asked(Context context)
  {
    return preferences(context).getBoolean(ASKED, false);
  }

  /**
   * Asks once whether to use live times; either answer is remembered and the question never returns.
   * {@code answered} runs after the choice (to rebuild the route with or without live data).
   */
  public static void ask(Activity activity, Runnable answered)
  {
    if (asked(activity))
      return;
    preferences(activity).edit().putBoolean(ASKED, true).apply();
    new MaterialAlertDialogBuilder(activity).setTitle(R.string.riga_live_consent_title)
        .setMessage(activity.getString(R.string.riga_live_consent_body) + "\n\n"
                    + activity.getString(R.string.live_feeds_change_later))
        .setCancelable(false)
        .setPositiveButton(R.string.riga_live_agree, (dialog, which) -> {
          setLive(activity, true);
          answered.run();
        })
        .setNegativeButton(R.string.riga_live_disagree, (dialog, which) -> {
          setLive(activity, false);
          answered.run();
        })
        .show();
  }
}
