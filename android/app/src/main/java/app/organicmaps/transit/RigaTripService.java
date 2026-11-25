package app.organicmaps.transit;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.sdk.location.LocationListener;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import app.organicmaps.sdk.util.LocationUtils;
import app.organicmaps.sdk.util.log.Logger;
import androidx.core.graphics.drawable.IconCompat;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitStepType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs only while the rider follows a Riga journey: keeps the process (and so the 15 s live re-timing)
 * and the location alive with the screen off, shows the current step and fires leave/board/get-off alerts.
 */
public final class RigaTripService extends Service implements LocationListener
{
  private static final String TAG = "RigaTripService";
  private static final String CHANNEL = "RIGA_TRIP";
  private static final String ALERTS = "RIGA_TRIP_ALERTS";
  private static final String QUIET_ALERTS = "RIGA_TRIP_QUIET_ALERTS";
  private static final int NOTIFICATION = 31415;
  private static final int ALERT = 31416;
  private static final String END = "END";
  private static final long TICK = 5000;

  private static boolean sRunning;
  /** True while a trip is followed; location then stays on in the background. */
  public static boolean isRunning() { return sRunning; }

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final Runnable mTick = this::tick;
  private final Set<String> mFired = new HashSet<>();
  private String mShown = "";
  /** The journey being followed, by its rides and stops (not times, which live data moves). */
  private int mMissing;

  static void start(@NonNull Context context)
  {
    try
    {
      ContextCompat.startForegroundService(context, new Intent(context, RigaTripService.class));
    }
    catch (Exception e)
    {
      // Started from a visible screen this cannot fail; guidance on screen still works without it.
      Logger.e(TAG, "Cannot start trip service", e);
    }
  }

  static void stop(@NonNull Context context)
  {
    context.stopService(new Intent(context, RigaTripService.class));
  }

  public static void createNotificationChannels(@NonNull Context context)
  {
    NotificationManagerCompat manager = NotificationManagerCompat.from(context);
    manager.createNotificationChannel(new NotificationChannelCompat.Builder(CHANNEL,
        NotificationManagerCompat.IMPORTANCE_LOW).setName(context.getString(R.string.riga_trip_channel))
        .setVibrationEnabled(false).build());
    manager.createNotificationChannel(new NotificationChannelCompat.Builder(ALERTS,
        NotificationManagerCompat.IMPORTANCE_HIGH).setName(context.getString(R.string.riga_trip_alerts_channel))
        .setVibrationEnabled(true).setVibrationPattern(new long[] {0, 400, 200, 400}).build());
    // Time to leave and the vehicle coming: shown on top, but silent and without vibrating.
    manager.createNotificationChannel(new NotificationChannelCompat.Builder(QUIET_ALERTS,
        NotificationManagerCompat.IMPORTANCE_HIGH).setName(context.getString(R.string.riga_trip_quiet_alerts_channel))
        .setVibrationEnabled(false).setSound(null, null).build());
  }

  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId)
  {
    if (intent != null && END.equals(intent.getAction()))
    {
      TransitRouteInfo info = RoutingController.get().getCachedTransitInfo();
      if (info != null && info.trip && info.endTrip != null) info.endTrip.run();
      stopSelf();
      return START_NOT_STICKY;
    }
    createNotificationChannels(this);
    boolean located = LocationUtils.checkFineLocationPermission(this);
    try
    {
      int type = 0;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        type = located ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
      ServiceCompat.startForeground(this, NOTIFICATION, notification(new Live()), type);
    }
    catch (Exception e)
    {
      Logger.e(TAG, "Cannot promote the trip service to foreground", e);
    }
    sRunning = true;
    if (located)
    {
      LocationHelper location = MwmApplication.from(this).getLocationHelper();
      location.addListener(this);
      location.restartWithNewMode();
    }
    mHandler.removeCallbacks(mTick);
    mHandler.post(mTick);
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy()
  {
    sRunning = false;
    mHandler.removeCallbacks(mTick);
    MwmApplication.from(this).getLocationHelper().removeListener(this);
    super.onDestroy();
  }

  @Override
  public void onLocationUpdated(@NonNull Location location)
  {
    // Alerts such as "get off at the next stop" must not wait for the next clock tick.
    mHandler.removeCallbacks(mTick);
    mHandler.post(mTick);
  }

  private void tick()
  {
    mHandler.removeCallbacks(mTick);
    TransitRouteInfo info = RoutingController.get().getCachedTransitInfo();
    if (info == null || !info.trip)
    {
      // A route being re-published can briefly lack the trip; only a lasting absence ends the service.
      if (++mMissing < 3) { mHandler.postDelayed(mTick, TICK); return; }
      stopSelf();
      return;
    }
    mMissing = 0;
    long now = System.currentTimeMillis();
    Location location = MwmApplication.from(this).getLocationHelper().getSavedLocation();
    double lat = location == null ? Double.NaN : location.getLatitude();
    double lon = location == null ? Double.NaN : location.getLongitude();
    // The same "now" as the route panel and the Steps sheet; it only moves forward through the journey.
    RigaTripGuide.State state = RigaTripPanel.now(info.journeyLegs, now, lat, lon);
    String headline = RigaTripGuide.headline(this, info.journeyLegs, state, now);
    Live live = new Live();
    live.title = RigaTripGuide.liveTitle(this, info.journeyLegs, state, now);
    live.text = RigaTripGuide.liveText(this, info.journeyLegs, state, now);
    live.chip = RigaTripGuide.chip(this, state, now);
    live.arrive = state.phase == RigaTripGuide.Phase.ARRIVED ? ""
        : getString(R.string.riga_live_arrive, clock(lastArrival(info.journeyLegs)));
    live.leg = state.phase == RigaTripGuide.Phase.ARRIVED ? null : info.journeyLegs[state.leg];
    live.walking = state.phase == RigaTripGuide.Phase.LEAVE || state.phase == RigaTripGuide.Phase.WALK;
    live.progress = RigaTripProgress.of(info.journeyLegs, state, now);
    // A countdown for phones without Live Updates, which the shade keeps current between ticks.
    live.countdown = (state.phase == RigaTripGuide.Phase.LEAVE || state.phase == RigaTripGuide.Phase.WAIT
                      || state.phase == RigaTripGuide.Phase.STAY)
        && !state.missed && state.at > now ? state.at : 0;
    String shown = live.key();
    if (!shown.equals(mShown))
    {
      mShown = shown;
      NotificationManagerCompat manager = NotificationManagerCompat.from(this);
      if (manager.areNotificationsEnabled())
        try { manager.notify(NOTIFICATION, notification(live)); }
        catch (SecurityException ignored) {}
    }
    if (state.alert != null && mFired.add(state.alert))
      alert(RigaTripGuide.alertTitle(this, info.journeyLegs, state),
            RigaTripGuide.alertText(this, info.journeyLegs, state, headline),
            state.alert.startsWith("leave") || state.alert.startsWith("board"));
    if (state.phase == RigaTripGuide.Phase.ARRIVED)
    {
      // Like navigation on arrival: the route closes. The plan list would only offer stale departures.
      Logger.i(TAG, "Arrived, closing the route");
      if (!RoutingController.get().cancel() && info.endTrip != null) info.endTrip.run();
      stopSelf();
      return;
    }
    mHandler.postDelayed(mTick, TICK);
  }

  /** @param quiet getting on (time to leave, the vehicle coming): no sound or vibration; getting off vibrates. */
  private void alert(String title, String text, boolean quiet)
  {
    NotificationManagerCompat manager = NotificationManagerCompat.from(this);
    if (!manager.areNotificationsEnabled()) return;
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, quiet ? QUIET_ALERTS : ALERTS)
        .setSmallIcon(R.drawable.ic_logo_small)
        .setContentTitle(title)
        .setContentText(text)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)
        .setTimeoutAfter(120000)
        .setContentIntent(open());
    // The quiet channel has neither sound nor vibration; still a heads-up, which a silent notification is not.
    if (!quiet) builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE);
    Notification notification = builder.build();
    try { manager.notify(ALERT, notification); } catch (SecurityException ignored) {}
  }

  /** What the trip notification shows; its key decides whether the shade needs an update. */
  static final class Live
  {
    String title = "", text = "", chip = "", arrive = "";
    @Nullable TransitJourneyLeg leg;
    boolean walking;
    @Nullable RigaTripProgress progress;
    long countdown;

    String key()
    {
      // Progress in 15 s steps: smooth enough on the bar without re-posting on every tick.
      int step = progress == null ? -1 : progress.progress / 15;
      return title + '|' + text + '|' + chip + '|' + arrive + '|' + step + '|' + countdown + '|'
          + (leg == null ? "" : leg.number + leg.color) + walking;
    }
  }

  private Notification notification(Live live)
  {
    int immutable = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PendingIntent.FLAG_IMMUTABLE;
    PendingIntent end = PendingIntent.getService(this, 1,
        new Intent(this, RigaTripService.class).setAction(END), PendingIntent.FLAG_UPDATE_CURRENT | immutable);
    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_logo_small)
        .setContentTitle(live.title.isEmpty() ? getString(R.string.riga_trip_channel) : live.title)
        .setContentText(live.text)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(open())
        .addAction(0, getString(R.string.riga_trip_end), end)
        // Android 16 Live Update: pinned at the top of the shade, on the lock screen and as a status bar chip.
        .setRequestPromotedOngoing(true);
    if (!live.arrive.isEmpty()) builder.setSubText(live.arrive);
    if (!live.chip.isEmpty()) builder.setShortCriticalText(live.chip);
    TransitStepType mode = live.leg == null ? null : live.walking ? TransitStepType.PEDESTRIAN : live.leg.type;
    int color = live.leg == null || live.walking || live.leg.color == 0 ? RigaTripProgress.WALK_COLOR : live.leg.color;
    if (mode != null)
    {
      android.graphics.Bitmap icon = icon(mode, color, 48);
      if (icon != null) builder.setLargeIcon(icon);
    }
    if (live.leg != null && !live.walking && live.leg.color != 0) builder.setColor(live.leg.color);
    if (live.progress != null)
    {
      NotificationCompat.ProgressStyle style = new NotificationCompat.ProgressStyle().setStyledByProgress(true);
      List<NotificationCompat.ProgressStyle.Segment> segments = new ArrayList<>();
      for (RigaTripProgress.Segment segment : live.progress.segments)
        segments.add(new NotificationCompat.ProgressStyle.Segment(segment.length).setColor(segment.color));
      List<NotificationCompat.ProgressStyle.Point> points = new ArrayList<>();
      for (RigaTripProgress.Point point : live.progress.points)
        points.add(new NotificationCompat.ProgressStyle.Point(point.position).setColor(point.color));
      style.setProgressSegments(segments).setProgressPoints(points).setProgress(live.progress.progress);
      if (mode != null)
      {
        android.graphics.Bitmap tracker = icon(mode, color, 24);
        if (tracker != null) style.setProgressTrackerIcon(IconCompat.createWithBitmap(tracker));
      }
      builder.setStyle(style);
    }
    else
      builder.setStyle(new NotificationCompat.BigTextStyle().bigText(live.text));
    // Without Live Updates (before Android 16), a running countdown is the next best thing.
    builder.setShowWhen(live.countdown > 0 && Build.VERSION.SDK_INT < 36);
    if (live.countdown > 0 && Build.VERSION.SDK_INT < 36)
      builder.setWhen(live.countdown).setUsesChronometer(true).setChronometerCountDown(true);
    return builder.build();
  }

  /** The mode's icon, white on a circle of the line's colour, like the leg chips in the app. */
  @Nullable
  private android.graphics.Bitmap icon(TransitStepType mode, int color, int sizeDp)
  {
    // A stay at a stop of the route: a clock, not the walking person both types share.
    android.graphics.drawable.Drawable glyph = ContextCompat.getDrawable(this,
        mode == TransitStepType.INTERMEDIATE_POINT ? R.drawable.ic_clock : mode.getDrawable());
    if (glyph == null) return null;
    int size = Math.round(sizeDp * getResources().getDisplayMetrics().density);
    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
    android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    paint.setColor(color);
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
    glyph = glyph.mutate();
    glyph.setTint(0xffffffff);
    int inset = Math.round(size * 0.2f);
    glyph.setBounds(inset, inset, size - inset, size - inset);
    glyph.draw(canvas);
    return bitmap;
  }


  private static long lastArrival(TransitJourneyLeg[] legs)
  {
    TransitJourneyLeg last = legs[legs.length - 1];
    return last.stops[last.stops.length - 1].arrival;
  }

  private static String clock(long time)
  {
    java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
    format.setTimeZone(RigaTransitStore.ZONE);
    return format.format(new java.util.Date(time));
  }

  private PendingIntent open()
  {
    int immutable = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PendingIntent.FLAG_IMMUTABLE;
    return PendingIntent.getActivity(this, 0, new Intent(this, MwmActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | immutable);
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }
}
