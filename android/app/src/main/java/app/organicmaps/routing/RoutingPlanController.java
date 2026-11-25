package app.organicmaps.routing;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.DrawableRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.routing.RoutingOptions;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import app.organicmaps.sdk.settings.RoadType;
import app.organicmaps.settings.RoutingOptionsActivity;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.WindowInsetUtils.PaddingInsetsListener;
import app.organicmaps.widget.RoutingToolbarButton;
import app.organicmaps.widget.ToolbarController;
import app.organicmaps.widget.WheelProgressView;
import com.google.android.material.button.MaterialButton;

public class RoutingPlanController extends ToolbarController
{
  private static final String BUNDLE_HAS_DRIVING_OPTIONS_VIEW = "has_driving_options_view";

  private final View mFrame;
  @NonNull
  private final RoutingPlanInplaceController.RoutingPlanListener mRoutingPlanListener;
  private final RadioGroup mRouterTypes;
  @NonNull
  private final WheelProgressView mProgressVehicle;
  @NonNull
  private final WheelProgressView mProgressPedestrian;
  @NonNull
  private final WheelProgressView mProgressTransit;
  @NonNull
  private final WheelProgressView mProgressBicycle;
  @NonNull
  private final WheelProgressView mProgressRuler;

  @NonNull
  private final RoutingBottomMenuController mRoutingBottomMenuController;

  int mFrameHeight;
  final int mAnimToggle;
  private boolean mIsPreviewingTurn;

  @NonNull
  private final FrameLayout mRoutingOptionsBanner;

  @NonNull
  private final View.OnLayoutChangeListener mRoutingOptionsLayoutListener;

  private void setupRouterButton(@IdRes int buttonId, final @DrawableRes int iconRes,
                                 View.OnClickListener clickListener)
  {
    CompoundButton.OnCheckedChangeListener listener = (buttonView, isChecked) ->
    {
      RoutingToolbarButton button = (RoutingToolbarButton) buttonView;
      button.setIcon(iconRes);
      if (isChecked)
        button.activate();
      else
        button.deactivate();
    };

    RoutingToolbarButton rb = mRouterTypes.findViewById(buttonId);
    listener.onCheckedChanged(rb, false);
    rb.setOnCheckedChangeListener(listener);
    rb.setOnClickListener(clickListener);
  }

  RoutingPlanController(View root, Activity activity, ActivityResultLauncher<Intent> startRoutingOptionsForResult,
                        @NonNull RoutingPlanInplaceController.RoutingPlanListener routingPlanListener,
                        @NonNull RoutingBottomMenuListener listener)
  {
    super(root, activity);
    mFrame = root;
    mRoutingPlanListener = routingPlanListener;

    mRouterTypes = getToolbar().findViewById(R.id.route_type);
    android.widget.FrameLayout modeTimes = root.findViewById(R.id.route_mode_times);
    mModeTimes = modeTimes == null ? null : new RouteModeTimes(modeTimes, getToolbar());

    setupRouterButtons();

    View progressFrame = getToolbar().findViewById(R.id.progress_frame);
    mProgressVehicle = progressFrame.findViewById(R.id.progress_vehicle);
    mProgressPedestrian = progressFrame.findViewById(R.id.progress_pedestrian);
    mProgressTransit = progressFrame.findViewById(R.id.progress_transit);
    mProgressBicycle = progressFrame.findViewById(R.id.progress_bicycle);
    mProgressRuler = progressFrame.findViewById(R.id.progress_ruler);
    //    mProgressTaxi = (WheelProgressView) progressFrame.findViewById(R.id.progress_taxi);

    mRoutingBottomMenuController = RoutingBottomMenuController.newInstance(requireActivity(), mFrame, listener);

    mRoutingOptionsBanner = mFrame.findViewById(R.id.routing_options_banner);
    mFrame.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutWatcher);
    View btn = mFrame.findViewById(R.id.routing_options_btn);
    btn.setOnClickListener(v -> {
      if (RoutingController.get().isRigaTransit())
        requireActivity().startActivity(new android.content.Intent(requireActivity(), app.organicmaps.settings.LiveFeedsSettingsActivity.class));
      else RoutingOptionsActivity.start(requireActivity(), startRoutingOptionsForResult);
    });

    mRoutingOptionsLayoutListener = new SelfTerminatedRoutingOptionsLayoutListener();
    mAnimToggle =
        MwmApplication.from(activity.getApplicationContext()).getResources().getInteger(R.integer.anim_default);

    final View menuFrame = activity.findViewById(R.id.menu_frame);
    final PaddingInsetsListener insetsListener =
        new PaddingInsetsListener.Builder()
            .setInsetsTypeMask(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout())
            .setExcludeTop()
            .build();
    ViewCompat.setOnApplyWindowInsetsListener(menuFrame, insetsListener);

    onTurnPreviewChanged(RoutingController.get().isPreviewingTurn());
  }

  @NonNull
  protected View getFrame()
  {
    return mFrame;
  }

  @NonNull
  private View getRoutingOptionsBtnContainer()
  {
    return mRoutingOptionsBanner;
  }

  private void setupRouterButtons()
  {
    setupRouterButton(R.id.vehicle, R.drawable.ic_car, this::onVehicleModeSelected);
    setupRouterButton(R.id.pedestrian, R.drawable.ic_pedestrian, this::onPedestrianModeSelected);
    //    setupRouterButton(R.id.taxi, R.drawable.ic_taxi, this::onTaxiModeSelected);
    setupRouterButton(R.id.transit, app.organicmaps.sdk.R.drawable.ic_route_planning_bus,
                      this::onTransitModeSelected);
    setupRouterButton(R.id.bicycle, R.drawable.ic_bike, this::onBicycleModeSelected);
    setupRouterButton(R.id.ruler, app.organicmaps.sdk.R.drawable.ic_ruler_route, this::onRulerModeSelected);
  }

  private void onTransitModeSelected(@NonNull View v)
  {
    RoutingController.get().setRouterType(Router.Transit);
  }

  private void onBicycleModeSelected(@NonNull View v)
  {
    RoutingController.get().setRouterType(Router.Bicycle);
  }

  private void onRulerModeSelected(@NonNull View v)
  {
    RoutingController.get().setRouterType(Router.Ruler);
  }

  private void onPedestrianModeSelected(@NonNull View v)
  {
    RoutingController.get().setRouterType(Router.Pedestrian);
  }

  private void onVehicleModeSelected(@NonNull View v)
  {
    RoutingController.get().setRouterType(Router.Vehicle);
  }

  @Override
  public void onUpClick()
  {
    // Ignore the event if the back and start buttons are pressed at the same time.
    // See {@link #RoutingBottomMenuController.setStartButton()}.
    if (RoutingController.get().isNavigating())
      return;
    RoutingController.get().cancel();
  }

  boolean checkFrameHeight()
  {
    if (mFrameHeight > 0)
      return true;

    mFrameHeight = mFrame.getHeight();
    return (mFrameHeight > 0);
  }

  private int mFittedTransitRevision = -1;
  /** The journey revision a fit has been scheduled for, so each one is scheduled once. */
  private int mFittingTransitRevision = -1;
  private static boolean sVisibleRectNarrowed;

  /** Restores the full map viewport after the route plan that narrowed it closes. */
  public static void restoreVisibleMap(@NonNull View root)
  {
    if (!sVisibleRectNarrowed || root.getWidth() == 0) return;
    sVisibleRectNarrowed = false;
    if (app.organicmaps.MwmApplication.from(root.getContext()).getDisplayManager().isCarDisplayUsed()) return;
    Framework.nativeSetVisibleRect(0, 0, root.getWidth(), root.getHeight());
  }

  /** How often, and how far apart, a fit waits for the route panel to be laid out (a cold start lays it out late). */
  private static final int FIT_ATTEMPTS = 20;
  private static final long FIT_RETRY_MS = 100;

  /**
   * The route panel grows once a journey arrives; frame the journey in the map area left visible: below the toolbar,
   * above the panel and between the map's button columns. Until the panel is laid out (on a cold start the journey
   * can arrive first) the fit is tried again; it is counted as done only once it has been made.
   */
  private void fitTransitToVisibleMap(int revision, int attempt)
  {
    mFrame.postDelayed(() -> {
      if (RoutingController.get().getTransitFitRevision() != revision || mFittedTransitRevision == revision) return;
      if (!isTransitType()) return;
      if (!fitTransitNow())
      {
        if (attempt + 1 < FIT_ATTEMPTS) fitTransitToVisibleMap(revision, attempt + 1);
        else mFittingTransitRevision = -1; // the next panel update tries again
        return;
      }
      mFittedTransitRevision = revision;
    }, attempt == 0 ? 0 : FIT_RETRY_MS);
  }

  /** Narrows the map's visible area to what the plan leaves uncovered and frames the journey in it. */
  private boolean fitTransitNow()
  {
    return narrowVisibleMap(true);
  }

  /** The last visible area set, to notice when the panel or the toolbar changed size. */
  private final android.graphics.Rect mVisibleMap = new android.graphics.Rect();

  /**
   * The panel grows and shrinks (a trip's card, the folded panel, the open steps) and the toolbar hides during a
   * trip: keep the map's visible area to what they leave, so a followed position stays in the middle of it.
   */
  private final android.view.ViewTreeObserver.OnGlobalLayoutListener mLayoutWatcher = () -> {
    if (sVisibleRectNarrowed && isTransitType() && mFittedTransitRevision == RoutingController.get().getTransitFitRevision())
      narrowVisibleMap(false);
  };

  /** Shows or hides the plan's toolbar: a started trip needs no mode choice, and the map gets the room. */
  private void showTripChrome(boolean trip)
  {
    UiUtils.showIf(!trip, getToolbar());
    if (trip)
    {
      UiUtils.hide(mFrame.findViewById(R.id.route_mode_times));
      UiUtils.hide(mRoutingOptionsBanner);
    }
  }

  private boolean narrowVisibleMap(boolean refit)
  {
    if (mFrame.getHeight() == 0 || !mFrame.isAttachedToWindow()) return false;
    View panel = mFrame.findViewById(R.id.transit_panel);
    if (panel == null) panel = requireActivity().findViewById(R.id.transit_panel);
    if (panel == null || !panel.isShown() || panel.getHeight() == 0) return false;
    // Use the outermost panel card below the map, not the inner transit row.
    View root = mFrame.getRootView();
    while (panel.getParent() instanceof View parent && parent != root && parent.getHeight() < root.getHeight() * 0.9)
      panel = parent;
    int[] location = new int[2];
    panel.getLocationOnScreen(location);
    int panelTop = location[1];
    int toolbarBottom = 0;
    if (getToolbar().getVisibility() == View.VISIBLE)
    {
      // Below the mode times too, when they show.
      View top = mFrame.findViewById(R.id.route_mode_times);
      if (top == null || !top.isShown() || top.getHeight() == 0) top = getToolbar();
      top.getLocationOnScreen(location);
      toolbarBottom = location[1] + top.getHeight();
      if (getToolbar().getHeight() == 0) return false;
    }
    int width = root.getWidth();
    if (panelTop - toolbarBottom < (int) (120 * mFrame.getResources().getDisplayMetrics().density)) return false;
    if (app.organicmaps.MwmApplication.from(mFrame.getContext()).getDisplayManager().isCarDisplayUsed()) return true;
    // Keep the journey's ends clear of the map buttons on both sides (search, bookmarks; zoom, my position).
    int left = 0, right = width;
    View leftButtons = root.findViewById(R.id.map_buttons_inner_left);
    if (leftButtons != null && leftButtons.isShown() && leftButtons.getWidth() > 0)
    {
      leftButtons.getLocationOnScreen(location);
      left = Math.max(left, location[0] + leftButtons.getWidth());
    }
    View rightButtons = root.findViewById(R.id.map_buttons_inner_right);
    if (rightButtons != null && rightButtons.isShown() && rightButtons.getWidth() > 0)
    {
      rightButtons.getLocationOnScreen(location);
      right = Math.min(right, location[0]);
    }
    // Narrow screens keep at least half their width for the map.
    if (right - left < width / 2) { left = 0; right = width; }
    if (!refit && mVisibleMap.equals(new android.graphics.Rect(left, toolbarBottom, right, panelTop))) return true;
    mVisibleMap.set(left, toolbarBottom, right, panelTop);
    Framework.nativeSetVisibleRect(left, toolbarBottom, right, panelTop);
    sVisibleRectNarrowed = true;
    if (refit) RoutingController.get().refitTransit();
    return true;
  }

  @androidx.annotation.Nullable
  private final RouteModeTimes mModeTimes;

  private void updateProgressLabels()
  {
    if (mModeTimes != null)
      mModeTimes.update();
    TransitRouteInfo shown = isTransitType() ? RoutingController.get().getCachedTransitInfo() : null;
    showTripChrome(shown != null && shown.trip && RoutingController.get().getTransitStatus() == null);
    if (mIsPreviewingTurn)
    {
      mRoutingBottomMenuController.hideAltitudeChartAndRoutingDetails();
      return;
    }

    RoutingController.TransitResult status = RoutingController.get().getTransitStatus();
    if (isTransitType() && status != null)
    {
      mRoutingBottomMenuController.showTransitStatus(status);
      return;
    }
    RoutingController.BuildState buildState = RoutingController.get().getBuildState();

    final boolean ready = (buildState == RoutingController.BuildState.BUILT);

    if (!ready)
    {
      mRoutingBottomMenuController.hideAltitudeChartAndRoutingDetails();
      return;
    }

    if (isTransitType())
    {
      TransitRouteInfo info = RoutingController.get().getCachedTransitInfo();
      if (info != null)
      {
        mRoutingBottomMenuController.showTransitInfo(info);
        int revision = RoutingController.get().getTransitFitRevision();
        if (revision != mFittedTransitRevision && revision != mFittingTransitRevision)
        {
          mFittingTransitRevision = revision;
          fitTransitToVisibleMap(revision, 0);
        }
      }
      return;
    }

    if (isRulerType())
    {
      RoutingInfo routingInfo = RoutingController.get().getCachedRoutingInfo();
      if (routingInfo != null)
        mRoutingBottomMenuController.showRulerInfo(Framework.nativeGetRoutePoints(), routingInfo.distToTarget);
      return;
    }

    final boolean showStartButton = !RoutingController.get().isRulerRouterType();
    mRoutingBottomMenuController.setStartButton(showStartButton);
    mRoutingBottomMenuController.showAltitudeChartAndRoutingDetails();
  }

  public void updateBuildProgress(int progress, @NonNull Router router)
  {
    if (mModeTimes != null)
      mModeTimes.update();
    UiUtils.invisible(mProgressVehicle, mProgressPedestrian, mProgressTransit, mProgressBicycle, mProgressRuler);
    WheelProgressView progressView = switch (router)
    {
      case Vehicle ->
      {
        mRouterTypes.check(R.id.vehicle);
        yield mProgressVehicle;
      }
      case Pedestrian ->
      {
        mRouterTypes.check(R.id.pedestrian);
        yield mProgressPedestrian;
      }
      case Transit ->
      {
        mRouterTypes.check(R.id.transit);
        yield mProgressTransit;
      }
      case Bicycle ->
      {
        mRouterTypes.check(R.id.bicycle);
        yield mProgressBicycle;
      }
      case Ruler ->
      {
        mRouterTypes.check(R.id.ruler);
        yield mProgressRuler;
      }
      default -> throw new IllegalArgumentException("unknown router: " + router);
    };

    RoutingToolbarButton button = mRouterTypes.findViewById(mRouterTypes.getCheckedRadioButtonId());
    button.progress();

    updateProgressLabels();

    if (!RoutingController.get().isBuilding())
    {
      button.complete();
      return;
    }

    UiUtils.show(progressView);
    progressView.setPending(progress == 0);
    if (progress != 0)
      progressView.setProgress(progress);
  }

  public void onTurnPreviewChanged(boolean isPreviewing)
  {
    boolean wasPreviewing = mIsPreviewingTurn;
    mIsPreviewingTurn = isPreviewing;

    if (isPreviewing)
      mRoutingBottomMenuController.hideAltitudeChartAndRoutingDetails();
    else if (wasPreviewing)
      updateProgressLabels();
  }

  private boolean isTransitType()
  {
    return RoutingController.get().isTransitType();
  }

  private boolean isRulerType()
  {
    return RoutingController.get().isRulerRouterType();
  }

  /** @return true if the back press closed the transit panel's open steps. */
  public boolean collapseTransitSheet()
  {
    return mRoutingBottomMenuController.collapseTransitSheet();
  }

  void saveRoutingPanelState(@NonNull Bundle outState)
  {
    mRoutingBottomMenuController.saveRoutingPanelState(outState);
    outState.putBoolean(BUNDLE_HAS_DRIVING_OPTIONS_VIEW, UiUtils.isVisible(mRoutingOptionsBanner));
  }

  void restoreRoutingPanelState(@NonNull Bundle state)
  {
    if (mIsPreviewingTurn)
      mRoutingBottomMenuController.hideAltitudeChartAndRoutingDetails();
    else
      mRoutingBottomMenuController.restoreRoutingPanelState(state);

    boolean hasView = state.getBoolean(BUNDLE_HAS_DRIVING_OPTIONS_VIEW);
    if (hasView)
      showRoutingOptionsView();
  }

  public void showAddStartFrame()
  {
    mRoutingBottomMenuController.showAddStartFrame();
  }

  public void showAddFinishFrame()
  {
    mRoutingBottomMenuController.showAddFinishFrame();
  }

  private void addRoutingOptionButton(RoadType type, int titleId, LinearLayout container)
  {
    Router rt = RoutingController.get().getLastRouterType();
    if (!RoutingOptions.hasOption(type, rt))
      return;

    MaterialButton btn = (MaterialButton) LayoutInflater.from(container.getContext())
                             .inflate(R.layout.routing_option_button, container, false);
    btn.setText(titleId);
    btn.setOnClickListener(v -> {
      RoutingOptions.removeOption(type, rt);
      //RoutingController.get().rebuildLastRoute();
      container.removeView(v);
      if (!RoutingOptions.hasAnyOptions(rt))
        UiUtils.hide(mRoutingOptionsBanner);

      container.post(() -> {
          RoutingController.get().rebuildLastRoute();
      });
    });
    container.addView(btn);
  }

  public void showRoutingOptionsView()
  {
    mRoutingOptionsBanner.addOnLayoutChangeListener(mRoutingOptionsLayoutListener);
    UiUtils.show(mRoutingOptionsBanner);
    boolean hasAnyOptions = !isRulerType() && RoutingOptions.hasAnyOptions(RoutingController.get().getLastRouterType());
    if (hasAnyOptions)
    {
      LinearLayout container = mRoutingOptionsBanner.findViewById(R.id.routing_options_buttons_container);
      container.removeAllViews();
      addRoutingOptionButton(RoadType.Ferry, R.string.avoid_ferry, container);
      addRoutingOptionButton(RoadType.Motorway, R.string.avoid_motorways, container);
      addRoutingOptionButton(RoadType.Paved, R.string.avoid_paved, container);
      addRoutingOptionButton(RoadType.Dirty, R.string.avoid_unpaved, container);
      addRoutingOptionButton(RoadType.Steps, R.string.avoid_steps, container);
      addRoutingOptionButton(RoadType.Toll, R.string.avoid_tolls, container);
    }
    else
    {
      UiUtils.hide(mRoutingOptionsBanner);
    }
  }

  public void hideRoutingOptionsView()
  {
    UiUtils.hide(mRoutingOptionsBanner);
    mRoutingPlanListener.onRoutingPlanStartAnimate(UiUtils.isVisible(getFrame()));
  }

  public int calcHeight()
  {
    int frameHeight = getFrame().getHeight();
    if (frameHeight == 0)
      return 0;

    View driverOptionsView = getRoutingOptionsBtnContainer();
    int extraOppositeOffset = UiUtils.isVisible(driverOptionsView) ? 0 : driverOptionsView.getHeight();

    return frameHeight - extraOppositeOffset;
  }

  private class SelfTerminatedRoutingOptionsLayoutListener implements View.OnLayoutChangeListener
  {
    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight,
                               int oldBottom)
    {
      mRoutingPlanListener.onRoutingPlanStartAnimate(UiUtils.isVisible(getFrame()));
      mRoutingOptionsBanner.removeOnLayoutChangeListener(this);
    }
  }
}
