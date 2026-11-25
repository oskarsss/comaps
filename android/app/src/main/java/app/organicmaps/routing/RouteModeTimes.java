package app.organicmaps.routing;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import app.organicmaps.transit.RigaStays;
import com.google.android.material.color.MaterialColors;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Under the mode buttons: when the trip would end by car, on foot, by public transport and by bike. The shown
 * mode uses its built route. The others are approximate and load no routing graph: car, foot and bike from the
 * straight-line distance, public transport from the timetable with straight-line walks.
 */
final class RouteModeTimes
{
  private static final Router[] MODES = {Router.Vehicle, Router.Pedestrian, Router.Transit, Router.Bicycle};
  /** Straight-line limits past which a mode is not worth showing. */
  private static final double WALK_LIMIT = 40000, BIKE_LIMIT = 150000;
  /** Metres per second on foot and by bike, as the app's own routes come out. */
  private static final double WALK_SPEED = 4.8 / 3.6, BIKE_SPEED = 15 / 3.6;
  /** Seconds every car trip spends getting out of and into streets, whatever its length. */
  private static final double CAR_ENDS = 180;
  /** The public transport estimate is refreshed after this even for the same points, as "now" moves on. */
  private static final long FRESH = 120000;

  private final FrameLayout mFrame;
  private final Map<Router, View> mButtons = new EnumMap<>(Router.class);
  private final Map<Router, TextView> mLabels = new EnumMap<>(Router.class);
  /** Per mode: {departure ms, arrival ms, 1 if approximate}, an empty array when unavailable, null while unknown. */
  private final Map<Router, long[]> mTimes = new EnumMap<>(Router.class);
  private String mKey;
  private long mComputedAt;
  private int mGeneration;

  /** @param frame the strip under the toolbar; @param toolbar holds the mode buttons the labels sit under. */
  RouteModeTimes(@NonNull FrameLayout frame, @NonNull View toolbar)
  {
    mFrame = frame;
    Context context = frame.getContext();
    int color = MaterialColors.getColor(frame, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
    for (Router mode : MODES)
    {
      View button = toolbar.findViewById(button(mode));
      TextView label = new TextView(context);
      label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
      label.setTextColor(color);
      label.setFontFeatureSettings("tnum");
      label.setGravity(Gravity.CENTER);
      label.setMaxLines(1);
      label.setOnClickListener(v -> RoutingController.get().setRouterType(mode));
      frame.addView(label, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                                                        FrameLayout.LayoutParams.WRAP_CONTENT));
      mButtons.put(mode, button);
      mLabels.put(mode, label);
    }
    // Labels are centred under their buttons, wherever the toolbar lays them out.
    View.OnLayoutChangeListener align = (v, l, t, r, b, ol, ot, or, ob) -> align();
    frame.addOnLayoutChangeListener(align);
    for (View button : mButtons.values())
      if (button != null)
        button.addOnLayoutChangeListener(align);
  }

  private void align()
  {
    int[] origin = new int[2], at = new int[2];
    mFrame.getLocationInWindow(origin);
    for (Router mode : MODES)
    {
      View button = mButtons.get(mode);
      TextView label = mLabels.get(mode);
      if (button == null || button.getVisibility() != View.VISIBLE)
      {
        label.setVisibility(View.GONE);
        continue;
      }
      button.getLocationInWindow(at);
      label.setTranslationX(at[0] - origin[0] + (button.getWidth() - label.getWidth()) / 2f);
    }
  }

  private static int button(Router mode)
  {
    switch (mode)
    {
    case Vehicle: return R.id.vehicle;
    case Pedestrian: return R.id.pedestrian;
    case Transit: return R.id.transit;
    default: return R.id.bicycle;
    }
  }

  private static int name(Router mode)
  {
    switch (mode)
    {
    case Vehicle: return R.string.vehicle;
    case Pedestrian: return R.string.pedestrian;
    case Transit: return R.string.riga_public_transport;
    default: return R.string.bicycle;
    }
  }

  /** Call when the points, the mode or the shown route change. */
  void update()
  {
    RoutingController controller = RoutingController.get();
    MapObject from = controller.getStartPoint(), to = controller.getEndPoint();
    if (from == null || to == null || controller.isNavigating()
        || (from.getLat() == to.getLat() && from.getLon() == to.getLon()))
    {
      mFrame.setVisibility(View.GONE);
      mKey = null;
      ++mGeneration;
      return;
    }
    mFrame.setVisibility(View.VISIBLE);
    Router shown = controller.getLastRouterType();
    List<double[]> path = path(from, to);
    StringBuilder key = new StringBuilder();
    for (double[] point : path)
      key.append(String.format(Locale.ROOT, "%.5f,%.5f>", point[0], point[1]));
    long now = System.currentTimeMillis();
    // With stops the timetable estimate cannot help (it plans start to finish): a shown plan for these
    // points stays until they change.
    long[] planned = mTimes.get(Router.Transit);
    boolean keepPlan = path.size() > 2 && key.toString().equals(mKey) && planned != null && planned.length == 3
                       && planned[2] == 0;
    // The shown public transport plan is the one to trust, with live times and the chosen departure, but only
    // once it is built: while re-planning (or after a failed plan) the cached one is for other points.
    boolean planReady = controller.isBuilt() && controller.getTransitStatus() == null;
    if (shown == Router.Transit && !planReady)
      keepPlan = false;
    if (!key.toString().equals(mKey) || (now - mComputedAt > FRESH && !keepPlan)
        || (shown == Router.Transit && !planReady && planned != null && planned.length == 3 && planned[2] == 0))
      estimate(key.toString(), from, to, now);
    TransitRouteInfo transit = shown == Router.Transit && planReady ? controller.getCachedTransitInfo() : null;
    if (transit != null && transit.arrival > 0)
      mTimes.put(Router.Transit, new long[] {transit.firstDeparture, transit.arrival, 0});
    long stays = stays(mFrame.getContext(), path);
    approximate(path, stays, now);
    // A built car, foot or bike route already goes through the stops; the time spent at them is added, as the
    // public transport plan includes it, so every mode says when the trip really ends.
    RoutingInfo built = shown != Router.Transit && controller.isBuilt() ? controller.getCachedRoutingInfo() : null;
    if (built != null && mTimes.containsKey(shown))
      mTimes.put(shown, new long[] {now, now + built.totalTimeInSeconds * 1000L + stays, 0});
    render(shown);
  }

  private void estimate(String key, MapObject from, MapObject to, long now)
  {
    mKey = key;
    mComputedAt = now;
    int generation = ++mGeneration;
    mTimes.remove(Router.Transit);
    RoutingController.TransitProvider provider = RoutingController.get().getTransitProvider();
    if (provider == null || !provider.supports(from, to))
      mTimes.put(Router.Transit, new long[0]);
    else
      provider.estimate(from, to, times -> deliver(generation, Router.Transit, times == null || times.length < 2
          ? new long[0] : new long[] {times[0], times[1], 1}));
  }

  /**
   * Car, foot and bike from straight lines through the stops: instant, and close enough to compare modes.
   * Each piece between stops is a trip of its own (city driving for short ones), and the stays are added.
   */
  private void approximate(List<double[]> path, long stays, long now)
  {
    double distance = 0, drive = 0;
    for (int i = 1; i < path.size(); ++i)
    {
      double piece = distance(path.get(i - 1)[0], path.get(i - 1)[1], path.get(i)[0], path.get(i)[1]);
      distance += piece;
      drive += driveSeconds(piece);
    }
    mTimes.put(Router.Vehicle, approximation(now, drive, stays));
    mTimes.put(Router.Pedestrian, distance > WALK_LIMIT ? new long[0]
        : approximation(now, road(distance) / WALK_SPEED, stays));
    mTimes.put(Router.Bicycle, distance > BIKE_LIMIT ? new long[0]
        : approximation(now, road(distance) / BIKE_SPEED, stays));
  }

  /**
   * Travelled metres for a straight-line distance. Roads are rarely straight, least so in town: about 30 % more
   * for a few kilometres, 15 % between towns (fitted to the app's own car, foot and bike routes around Riga).
   */
  static double road(double straight)
  {
    return straight * (1.15 + 0.2 * Math.exp(-straight / 15000));
  }

  /** Seconds by car for a straight-line distance: city speeds for short trips, main roads for longer ones. */
  static double driveSeconds(double straight)
  {
    double road = road(straight);
    return CAR_ENDS + road / ((22 + 48 * (1 - Math.exp(-road / 30000))) / 3.6);
  }

  /**
   * The approximate walk to the finish (ms) through the route's stops, with the time spent at them, as the
   * public transport plan counts it; 0 when too far to walk.
   */
  static long walkMillis(@NonNull Context context, @NonNull MapObject from, @NonNull MapObject to)
  {
    List<double[]> path = path(from, to);
    double distance = 0;
    for (int i = 1; i < path.size(); ++i)
      distance += distance(path.get(i - 1)[0], path.get(i - 1)[1], path.get(i)[0], path.get(i)[1]);
    return distance > WALK_LIMIT ? 0 : Math.round(road(distance) / WALK_SPEED * 1000) + stays(context, path);
  }

  /** The start, the route's stops in order, then the finish, each as {lat, lon}. */
  @NonNull
  static List<double[]> path(@NonNull MapObject from, @NonNull MapObject to)
  {
    List<RouteMarkData> stops = new ArrayList<>();
    for (RouteMarkData point : Framework.nativeGetRoutePoints())
      if (point.mPointType == RouteMarkType.Intermediate)
        stops.add(point);
    Collections.sort(stops, (a, b) -> Integer.compare(a.mIntermediateIndex, b.mIntermediateIndex));
    List<double[]> path = new ArrayList<>();
    path.add(new double[] {from.getLat(), from.getLon()});
    for (RouteMarkData stop : stops)
      path.add(new double[] {stop.mLat, stop.mLon});
    path.add(new double[] {to.getLat(), to.getLon()});
    return path;
  }

  /** The time to be spent at the stops between the start and the finish of {@code path}. */
  static long stays(@NonNull Context context, @NonNull List<double[]> path)
  {
    long total = 0;
    for (int i = 1; i < path.size() - 1; ++i)
      total += RigaStays.get(context, path.get(i)[0], path.get(i)[1]);
    return total;
  }

  private static long[] approximation(long now, double seconds, long stays)
  {
    return new long[] {now, now + Math.round(seconds * 1000) + stays, 1};
  }

  private void deliver(int generation, Router mode, long[] times)
  {
    if (generation != mGeneration)
      return;
    // A plan shown meanwhile is kept over the background estimate.
    long[] shown = mTimes.get(mode);
    if (mode == Router.Transit && shown != null && shown.length == 3 && shown[2] == 0)
      return;
    mTimes.put(mode, times);
    render(RoutingController.get().getLastRouterType());
  }

  private void render(@Nullable Router shown)
  {
    Context context = mFrame.getContext();
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    for (Router mode : MODES)
    {
      TextView label = mLabels.get(mode);
      View button = mButtons.get(mode);
      long[] times = mTimes.get(mode);
      boolean none = times != null && times.length < 3;
      label.setVisibility(button == null || button.getVisibility() != View.VISIBLE ? View.GONE : View.VISIBLE);
      if (times == null)
        label.setText("…");
      else if (none)
        label.setText(R.string.mode_time_unknown);
      else
        label.setText(clock.format(new Date(times[1])));
      label.setContentDescription(times == null || none ? context.getString(name(mode))
          : context.getString(times[2] != 0 ? R.string.mode_time_description_approximate
                                            : R.string.mode_time_description, context.getString(name(mode)),
                              clock.format(new Date(times[1])), duration(context, times[1] - times[0])));
      // The shown mode reads strongest; unavailable modes recede.
      label.setTypeface(null, mode == shown ? Typeface.BOLD : Typeface.NORMAL);
      label.setAlpha(mode == shown ? 1f : none ? 0.5f : 0.8f);
    }
    mFrame.post(this::align);
  }

  static String duration(Context context, long millis)
  {
    long minutes = Math.max(1, Math.round(millis / 60000.0));
    if (minutes < 60)
      return minutes + " " + context.getString(R.string.minute);
    long hours = minutes / 60, rest = minutes % 60;
    return hours + " " + context.getString(R.string.hour) + (rest == 0 ? "" : " " + rest + " "
        + context.getString(R.string.minute));
  }

  private static double distance(double lat1, double lon1, double lat2, double lon2)
  {
    double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
    double y = Math.toRadians(lat2 - lat1);
    return Math.sqrt(x * x + y * y) * 6371000;
  }
}
