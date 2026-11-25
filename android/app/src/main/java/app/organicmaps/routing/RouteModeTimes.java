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
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.Utils;
import com.google.android.material.color.MaterialColors;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class RouteModeTimes
{
  private static final Router[] MODES = {Router.Vehicle, Router.Pedestrian, Router.Bicycle};
  private static final double WALK_LIMIT_M = 40000, BIKE_LIMIT_M = 150000;
  private static final double WALK_SPEED_MPS = 4.8 / 3.6, BIKE_SPEED_MPS = 15 / 3.6;
  private static final double CAR_ENDS_S = 180;
  private static final double CAR_TOWN_KMH = 22, CAR_OPEN_ROAD_EXTRA_KMH = 48, CAR_SPEED_RAMP_M = 30000;
  private static final double DETOUR_FACTOR = 1.15, DETOUR_SHORT_EXTRA = 0.2, DETOUR_SHORT_M = 15000;
  static final int UNAVAILABLE = -1;

  private final FrameLayout mFrame;
  @NonNull
  private final Runnable mShownChanged;
  private final Map<Router, View> mButtons = new EnumMap<>(Router.class);
  private final Map<Router, TextView> mLabels = new EnumMap<>(Router.class);

  RouteModeTimes(@NonNull FrameLayout frame, @NonNull View toolbar, @NonNull Runnable shownChanged)
  {
    mFrame = frame;
    mShownChanged = shownChanged;
    final Context context = frame.getContext();
    final int color = MaterialColors.getColor(frame, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
    for (Router mode : MODES)
    {
      final View button = toolbar.findViewById(buttonId(mode));
      final TextView label = new TextView(context);
      label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
      label.setTextColor(color);
      label.setFontFeatureSettings("tnum");
      label.setGravity(Gravity.CENTER);
      label.setMaxLines(2);
      label.setOnClickListener(v -> button.performClick());
      frame.addView(label, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                                                        FrameLayout.LayoutParams.WRAP_CONTENT));
      mButtons.put(mode, button);
      mLabels.put(mode, label);
    }
    final View.OnLayoutChangeListener align = (v, l, t, r, b, ol, ot, or, ob) -> align();
    frame.addOnLayoutChangeListener(align);
    for (View button : mButtons.values())
      button.addOnLayoutChangeListener(align);
  }

  void update()
  {
    final RoutingController controller = RoutingController.get();
    final MapObject from = controller.getStartPoint();
    final MapObject to = controller.getEndPoint();
    final boolean visible = from != null && to != null && !controller.isNavigating();
    if (visible != UiUtils.isVisible(mFrame))
    {
      UiUtils.showIf(visible, mFrame);
      final View parent = (View) mFrame.getParent();
      parent.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob)
        {
          parent.removeOnLayoutChangeListener(this);
          mShownChanged.run();
        }
      });
    }
    if (!visible)
      return;

    final Context context = mFrame.getContext();
    final RouteMarkData[] points = Framework.nativeGetRoutePoints();
    final int stays = RouteStays.totalSeconds(context, points);
    final List<double[]> path = path(from, to, points);
    double distance = 0, drive = 0;
    for (int i = 1; i < path.size(); ++i)
    {
      final double piece = distance(path.get(i - 1), path.get(i));
      distance += piece;
      drive += driveSeconds(piece);
    }

    final Router shown = controller.getLastRouterType();
    final RoutingInfo built = controller.isBuilt() ? controller.getCachedRoutingInfo() : null;
    for (Router mode : MODES)
    {
      final boolean exact = mode == shown && built != null;
      final int seconds = exact ? built.totalTimeInSeconds : estimateSeconds(mode, distance, drive);
      render(context, mode, mode == shown, exact, seconds == UNAVAILABLE ? UNAVAILABLE : seconds + stays);
    }
    mFrame.post(this::align);
  }

  static int estimateSeconds(@NonNull Router mode, double distance, double drive)
  {
    return switch (mode)
    {
      case Vehicle -> (int) Math.round(drive);
      case Pedestrian -> distance > WALK_LIMIT_M ? UNAVAILABLE : (int) Math.round(road(distance) / WALK_SPEED_MPS);
      case Bicycle -> distance > BIKE_LIMIT_M ? UNAVAILABLE : (int) Math.round(road(distance) / BIKE_SPEED_MPS);
      default -> UNAVAILABLE;
    };
  }

  private static double road(double straight)
  {
    return straight * (DETOUR_FACTOR + DETOUR_SHORT_EXTRA * Math.exp(-straight / DETOUR_SHORT_M));
  }

  static double driveSeconds(double straight)
  {
    final double road = road(straight);
    final double kmh = CAR_TOWN_KMH + CAR_OPEN_ROAD_EXTRA_KMH * (1 - Math.exp(-road / CAR_SPEED_RAMP_M));
    return CAR_ENDS_S + road / (kmh / 3.6);
  }

  private void render(@NonNull Context context, @NonNull Router mode, boolean shown, boolean exact, int seconds)
  {
    final TextView label = mLabels.get(mode);
    final String name = context.getString(nameId(mode));
    if (seconds == UNAVAILABLE)
    {
      label.setText(R.string.mode_time_unknown);
      label.setContentDescription(name);
    }
    else
    {
      final String arrival = Utils.formatArrivalTime(context, seconds);
      final String duration = Utils.formatRoutingTime(context, seconds, R.dimen.text_size_routing_number).toString();
      label.setText(arrival.replaceFirst(" ", "\n"));
      label.setContentDescription(
          context.getString(exact ? R.string.mode_time_description : R.string.mode_time_description_approximate, name,
                            arrival, duration));
    }
    label.setTypeface(null, shown ? Typeface.BOLD : Typeface.NORMAL);
    label.setAlpha(shown ? 1f : seconds == UNAVAILABLE ? 0.5f : 0.8f);
  }

  private void align()
  {
    final int[] origin = new int[2], at = new int[2];
    mFrame.getLocationInWindow(origin);
    for (Router mode : MODES)
    {
      final View button = mButtons.get(mode);
      final TextView label = mLabels.get(mode);
      button.getLocationInWindow(at);
      label.setX(at[0] - origin[0] + (button.getWidth() - label.getWidth()) / 2f);
    }
  }

  @NonNull
  private static List<double[]> path(@NonNull MapObject from, @NonNull MapObject to, @NonNull RouteMarkData[] points)
  {
    final List<RouteMarkData> stops = new ArrayList<>();
    for (RouteMarkData point : points)
      if (point.mPointType == RouteMarkType.Intermediate)
        stops.add(point);
    stops.sort((a, b) -> Integer.compare(a.mIntermediateIndex, b.mIntermediateIndex));
    final List<double[]> path = new ArrayList<>();
    path.add(new double[] {from.getLat(), from.getLon()});
    for (RouteMarkData stop : stops)
      path.add(new double[] {stop.mLat, stop.mLon});
    path.add(new double[] {to.getLat(), to.getLon()});
    return path;
  }

  private static double distance(@NonNull double[] a, @NonNull double[] b)
  {
    final double x = Math.toRadians(b[1] - a[1]) * Math.cos(Math.toRadians((a[0] + b[0]) / 2));
    final double y = Math.toRadians(b[0] - a[0]);
    return Math.sqrt(x * x + y * y) * 6371000;
  }

  private static int buttonId(@NonNull Router mode)
  {
    return switch (mode)
    {
      case Vehicle -> R.id.vehicle;
      case Pedestrian -> R.id.pedestrian;
      default -> R.id.bicycle;
    };
  }

  private static int nameId(@NonNull Router mode)
  {
    return switch (mode)
    {
      case Vehicle -> R.string.vehicle;
      case Pedestrian -> R.string.pedestrian;
      default -> R.string.bicycle;
    };
  }
}
