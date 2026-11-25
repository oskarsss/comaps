package app.organicmaps.routing;

import static app.organicmaps.sdk.util.Utils.dimen;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.bookmarks.data.DistanceAndAzimut;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import android.widget.TextView;
import app.organicmaps.sdk.location.LocationState;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import app.organicmaps.sdk.routing.TransitStepInfo;
import app.organicmaps.sdk.routing.TransitStepType;
import app.organicmaps.sdk.util.Distance;
import app.organicmaps.util.Graphics;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.util.UiUtils;
import app.organicmaps.util.Utils;
import app.organicmaps.widget.recycler.DotDividerItemDecoration;
import app.organicmaps.widget.recycler.MultilineLayoutManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.textview.MaterialTextView;
import java.util.LinkedList;
import java.util.List;

final class RoutingBottomMenuController implements View.OnClickListener
{
  private static final String STATE_ALTITUDE_CHART_SHOWN = "altitude_chart_shown";
  private static final String STATE_ERROR = "error";

  @NonNull
  private final Activity mContext;
  @NonNull
  private final View mTimeElevationLine;
  @NonNull
  private final View mAltitudeChartFrame;
  @NonNull
  private final View mTransitFrame;
  /** The transit panel opens into the journey's steps in place. */
  private final TransitSheet mSheet;
  private boolean mWasCompact;
  @NonNull
  private final MaterialTextView mError;
  @NonNull
  private final MaterialButton mStart;
  @NonNull
  private final ShapeableImageView mAltitudeChart;
  @NonNull
  private final MaterialTextView mTime;
  @NonNull
  private final MaterialTextView mAltitudeDifference;
  @NonNull
  private final MaterialTextView mTimeVehicle;
  @Nullable
  private final MaterialTextView mArrival;
  @NonNull
  private final View mActionFrame;
  @NonNull
  private final MaterialTextView mActionMessage;
  @NonNull
  private final View mActionButton;
  @NonNull
  private final ShapeableImageView mActionIcon;
  @NonNull
  private final DotDividerItemDecoration mTransitViewDecorator;

  @Nullable
  private final RoutingBottomMenuListener mListener;

  @NonNull
  static RoutingBottomMenuController newInstance(@NonNull Activity activity, @NonNull View frame,
                                                 @NonNull RoutingBottomMenuListener listener)
  {
    View altitudeChartFrame = getViewById(activity, frame, R.id.altitude_chart_panel);
    View timeElevationLine = getViewById(activity, frame, R.id.time_elevation_line);
    View transitFrame = getViewById(activity, frame, R.id.transit_panel);
    MaterialTextView error = (MaterialTextView) getViewById(activity, frame, R.id.error);
    MaterialButton start = (MaterialButton) getViewById(activity, frame, R.id.start);
    ShapeableImageView altitudeChart = (ShapeableImageView) getViewById(activity, frame, R.id.altitude_chart);
    MaterialTextView time = (MaterialTextView) getViewById(activity, frame, R.id.time);
    MaterialTextView timeVehicle = (MaterialTextView) getViewById(activity, frame, R.id.time_vehicle);
    MaterialTextView altitudeDifference = (MaterialTextView) getViewById(activity, frame, R.id.altitude_difference);
    MaterialTextView arrival = (MaterialTextView) getViewById(activity, frame, R.id.arrival);
    View actionFrame = getViewById(activity, frame, R.id.routing_action_frame);

    return new RoutingBottomMenuController(activity, altitudeChartFrame, timeElevationLine, transitFrame, error, start,
                                           altitudeChart, time, altitudeDifference, timeVehicle, arrival, actionFrame,
                                           listener);
  }

  @NonNull
  private static View getViewById(@NonNull Activity activity, @NonNull View frame, @IdRes int resourceId)
  {
    View view = frame.findViewById(resourceId);
    return view == null ? activity.findViewById(resourceId) : view;
  }

  private RoutingBottomMenuController(@NonNull Activity context, @NonNull View altitudeChartFrame,
                                      @NonNull View timeElevationLine, @NonNull View transitFrame,
                                      @NonNull MaterialTextView error, @NonNull MaterialButton start,
                                      @NonNull ShapeableImageView altitudeChart, @NonNull MaterialTextView time,
                                      @NonNull MaterialTextView altitudeDifference,
                                      @NonNull MaterialTextView timeVehicle, @Nullable MaterialTextView arrival,
                                      @NonNull View actionFrame, @Nullable RoutingBottomMenuListener listener)
  {
    mContext = context;
    mAltitudeChartFrame = altitudeChartFrame;
    mTimeElevationLine = timeElevationLine;
    mTransitFrame = transitFrame;
    mSheet = new TransitSheet(transitFrame);
    mSheet.setOnStateChange(() -> {
      if (mSheet.isCompact() != mWasCompact)
      {
        mWasCompact = mSheet.isCompact();
        // Folded or unfolded: rebind, so what the folded panel hides comes back and its trip line changes.
        TransitRouteInfo shown = RoutingController.get().getCachedTransitInfo();
        if (shown != null && mTransitFrame.getVisibility() == View.VISIBLE) showTransitInfo(shown);
        return;
      }
      bindDescription();
      describeToggles();
      // The open steps have a validate button on each ride: the foot's one would repeat the first of them.
      bindValidate(mTransitFrame.findViewById(R.id.transit_mobilly), mSheet.isExpanded() ? null : mValidateRide);
    });
    mError = error;
    mStart = start;
    mAltitudeChart = altitudeChart;
    mTime = time;
    mAltitudeDifference = altitudeDifference;
    mTimeVehicle = timeVehicle;
    mArrival = arrival;
    mActionFrame = actionFrame;
    mActionMessage = actionFrame.findViewById(R.id.tv__message);
    mActionButton = actionFrame.findViewById(R.id.btn__my_position_use);
    mActionButton.setOnClickListener(this);
    View actionSearchButton = actionFrame.findViewById(R.id.btn__search_point);
    actionSearchButton.setOnClickListener(this);
    mActionIcon = mActionButton.findViewById(R.id.iv__icon);
    UiUtils.hide(mAltitudeChartFrame, mActionFrame);
    mListener = listener;
    // "Then" between the chips, as in the route cards: reads as a sequence where dots read as a list.
    Drawable dividerDrawable = ContextCompat.getDrawable(mContext, R.drawable.transit_chevron_divider).mutate();
    dividerDrawable.setTint(ThemeUtils.getColor(mContext, android.R.attr.textColorSecondary));
    dividerDrawable.setAlpha(160);
    Resources res = mContext.getResources();
    dividerDrawable.setLayoutDirection(res.getConfiguration().getLayoutDirection());
    mTransitViewDecorator =
        new DotDividerItemDecoration(dividerDrawable, res.getDimensionPixelSize(R.dimen.margin_base),
                                     res.getDimensionPixelSize(R.dimen.margin_half));
    View chipsMore = transitFrame.findViewById(R.id.transit_chips_more);
    chipsMore.setOnClickListener(v -> {
      mChipsExpanded = !mChipsExpanded;
      fitChips();
    });
    // The chips are shown, hidden and rebound from many places; their row count is checked after each layout.
    View chipsScroll = transitFrame.findViewById(R.id.transit_chips_scroll);
    chipsScroll.getViewTreeObserver().addOnGlobalLayoutListener(this::fitChips);
    // Folded, the rows do not scroll (easy to do by accident while dragging the sheet): the button opens them.
    chipsScroll.setOnTouchListener(
        (v, event) -> !mChipsExpanded && event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE);
    MaterialButton manageRouteButton = altitudeChartFrame.findViewById(R.id.btn__manage_route);
    manageRouteButton.setOnClickListener(this);

    MaterialButton directionsPreviewButton = altitudeChartFrame.findViewById(R.id.btn__directions_preview);
    directionsPreviewButton.setOnClickListener(this);

    MaterialButton saveButton = altitudeChartFrame.findViewById(R.id.btn__save);
    saveButton.setOnClickListener(this);
  }

  void showAltitudeChartAndRoutingDetails()
  {
    UiUtils.hide(mError, mActionFrame, mAltitudeChart, mTimeElevationLine, mTransitFrame);
    mSheet.reset();

    if (!RoutingController.get().isVehicleRouterType() && !RoutingController.get().isRulerRouterType())
      showRouteAltitudeChart();
    showRoutingDetails();
    UiUtils.show(mAltitudeChartFrame);
    MaterialButton saveButton = mAltitudeChartFrame.findViewById(R.id.btn__save);
    saveButton.setContentDescription(mContext.getString(R.string.save));
    saveButton.setIcon(AppCompatResources.getDrawable(mContext, R.drawable.ic_bookmark_add));
    saveButton.setEnabled(true);
  }

  void hideAltitudeChartAndRoutingDetails()
  {
    mSheet.reset();
    UiUtils.hide(mAltitudeChartFrame, mTransitFrame);
  }

  @SuppressLint("SetTextI18n")
  void showTransitInfo(@NonNull TransitRouteInfo info)
  {
    UiUtils.hide(mError, mAltitudeChartFrame, mActionFrame);
    showStartButton(false);
    UiUtils.show(mTransitFrame);
    RecyclerView rv = mTransitFrame.findViewById(R.id.transit_recycler_view);
    UiUtils.show(rv); // A two-point ruler hides it.
    TransitStepAdapter adapter;
    if (rv.getAdapter() instanceof TransitStepAdapter existing)
      adapter = existing;
    else
    {
      adapter = new TransitStepAdapter();
      rv.setLayoutManager(new MultilineLayoutManager(mTransitFrame.getLayoutDirection()));
      rv.setNestedScrollingEnabled(false);
      rv.removeItemDecoration(mTransitViewDecorator);
      rv.addItemDecoration(mTransitViewDecorator);
      rv.setAdapter(adapter);
    }
    adapter.setItems(chips(info));
    adapter.setDone(0);
    // Before the trip, a stop's chip (its number and stay) changes how long the rider stays there.
    app.organicmaps.sdk.routing.TransitJourneyLeg[] chipSource = info.journeyLegs;
    int[] chipLegs = mChipLegs;
    adapter.setOnStayClick(info.trip || chipLegs.length == 0 ? null : position -> {
      if (position < chipLegs.length && chipLegs[position] < chipSource.length
          && chipSource[chipLegs[position]].isStay())
        StayPicker.show(mContext, chipSource[chipLegs[position]]);
    });
    mTransitFrame.findViewById(R.id.transit_details).setVisibility(
        info.description.isEmpty() && info.journeyLegs.length == 0 ? View.GONE : View.VISIBLE);

    // During a trip the "now" card and the steps tell the journey; the plan's row of line chips would repeat it.
    mTransitFrame.findViewById(R.id.transit_chips_scroll).setVisibility(info.trip ? View.GONE : View.VISIBLE);
    TransitAlternativesView.bind(mTransitFrame.findViewById(R.id.transit_alternatives), info);
    if (info.trip) UiUtils.hide(mTransitFrame.findViewById(R.id.transit_alternatives));
    // With a route shown, when to travel sits in its header row beside Manage Route.
    bindTime(false);
    bindHeaderTime(!info.trip && (info.startTrip != null || info.alternatives.length > 0 || info.alternativesPending));
    com.google.android.material.button.MaterialButton mobilly = mTransitFrame.findViewById(R.id.transit_mobilly);
    // During a trip the validate button lives in the "now" card, for the ride it belongs to.
    mValidateRide = info.trip ? null : MobillyShortcut.nextKnownVehicle(info.journeyLegs);
    bindValidate(mobilly, mSheet.isExpanded() ? null : mValidateRide);
    android.widget.TextView description = mTransitFrame.findViewById(R.id.transit_description);
    mDescription = styleDescription(info);
    mDescriptionToggles = TransitSheet.hasSteps(info);
    bindDescription();
    // The plan's departure line is history once the rider is under way; the "now" card replaces it.
    description.setVisibility(info.trip || info.description.isEmpty() ? View.GONE : View.VISIBLE);
    android.widget.Button action = mTransitFrame.findViewById(R.id.transit_action);
    // The first route that could use live times asks once; the answer is kept and can be changed in Settings.
    boolean ask = info.liveConsent && info.action != null;
    // No empty action row: an action without a label (or no action) shows nothing.
    action.setVisibility(info.action == null || ask || TextUtils.isEmpty(info.actionLabel) ? View.GONE : View.VISIBLE);
    action.setText(info.actionLabel);
    action.setOnClickListener(v -> info.action.run());
    if (ask)
    {
      Runnable answered = info.action;
      info.action = null;
      app.organicmaps.transit.RigaLiveSettings.ask(mContext, answered);
    }
    // A tap on the summary opens the steps, and closes them again: the plan's description (which stays below the
    // open steps and says which way it goes), the trip's current and next step.
    View.OnClickListener toggle = !TransitSheet.hasSteps(info) ? null : v -> mSheet.toggle();
    for (int id : new int[] {R.id.transit_description, R.id.transit_now, R.id.transit_next})
    {
      View view = mTransitFrame.findViewById(id);
      view.setOnClickListener(toggle);
      view.setClickable(toggle != null);
    }
    describeToggles();
    showManage(true);
    bindShare(info);

    MaterialTextView totalTimeView = mTransitFrame.findViewById(R.id.total_time);
    totalTimeView.setVisibility(View.VISIBLE);
    totalTimeView.setText(Utils.formatRoutingTime(mContext, info.getTotalTime(), R.dimen.text_size_routing_number));
    View dotView = mTransitFrame.findViewById(R.id.dot);
    View pedestrianIcon = mTransitFrame.findViewById(R.id.pedestrian_icon);
    MaterialTextView distanceView = mTransitFrame.findViewById(R.id.total_distance);
    if (info.arrival > 0)
    {
      // Timetabled journeys: when to leave and when to arrive, beside the duration. The walks are in the legs.
      UiUtils.hide(pedestrianIcon);
      UiUtils.show(dotView, distanceView);
      distanceView.setText(span(mContext, info));
      distanceView.setContentDescription(spanDescription(mContext, info));
    }
    else
    {
      UiUtils.showIf(info.getTotalPedestrianTimeInSec() > 0, dotView, pedestrianIcon, distanceView);
      distanceView.setText(info.getTotalPedestrianDistance() + " " + info.getTotalPedestrianDistanceUnits());
      distanceView.setContentDescription(null);
    }
    bindWait(info);
    // Last: during a trip it replaces the plan's header with time left and fades the legs behind.
    bindTrip(info);
    if (!info.trip) mSheet.bind(info, null);
    mSheet.applyCompact();
  }

  /** When the journey leaves (its first walk included), or 0 for plans without times. */
  private static long leave(@NonNull TransitRouteInfo info)
  {
    return info.arrival > 0 ? info.arrival - info.getTotalTime() * 1000L : 0;
  }

  /** "04:46–05:25": when the shown journey leaves and arrives. */
  @NonNull
  static String span(@NonNull Context context, @NonNull TransitRouteInfo info)
  {
    java.text.SimpleDateFormat clock = clock();
    return context.getString(R.string.riga_route_span, clock.format(new java.util.Date(leave(info))),
                             clock.format(new java.util.Date(info.arrival)));
  }

  @NonNull
  static String spanDescription(@NonNull Context context, @NonNull TransitRouteInfo info)
  {
    java.text.SimpleDateFormat clock = clock();
    return context.getString(R.string.riga_route_span_description, clock.format(new java.util.Date(leave(info))),
                             clock.format(new java.util.Date(info.arrival)));
  }

  @NonNull
  private static java.text.SimpleDateFormat clock()
  {
    return app.organicmaps.transit.RigaDelayText.clock();
  }

  /**
   * At night (or for a far-off train) the ride itself may be short while nothing leaves for hours: then the
   * panel leads with when the journey sets off. Walking there sooner is not said here: it is a journey of its own
   * among the alternatives, chosen when it gets there first.
   */
  private void bindWait(@NonNull TransitRouteInfo info)
  {
    View banner = mTransitFrame.findViewById(R.id.transit_wait);
    long now = System.currentTimeMillis();
    long from = app.organicmaps.transit.RigaWait.from(app.organicmaps.transit.RigaTimeChoice.mode(),
                                                      app.organicmaps.transit.RigaTimeChoice.time(), now);
    long leave = leave(info);
    long wait = info.trip ? 0 : app.organicmaps.transit.RigaWait.notable(from, leave);
    banner.setVisibility(wait > 0 ? View.VISIBLE : View.GONE);
    if (wait == 0) return;
    TextView title = banner.findViewById(R.id.transit_wait_title);
    title.setText(mContext.getString(R.string.riga_wait_first_departure,
        app.organicmaps.transit.RigaTimeChoice.when(mContext, leave, now), RouteModeTimes.duration(mContext, wait)));
  }

  /** The plan's description as styled. */
  @NonNull
  private CharSequence mDescription = "";
  private boolean mDescriptionToggles;

  /** The description; a tap on it opens or closes the steps, as dragging the sheet does. */
  private void bindDescription()
  {
    TextView view = mTransitFrame.findViewById(R.id.transit_description);
    view.setText(mDescription);
    setRipple(view, mDescriptionToggles && mDescription.length() > 0);
  }

  /** A pressed state for the summary text, which looks like text but is a button. */
  private void setRipple(@NonNull View view, boolean on)
  {
    if (!on) { view.setBackground(null); return; }
    android.util.TypedValue ripple = new android.util.TypedValue();
    mContext.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    view.setBackgroundResource(ripple.resourceId);
  }

  /** Spoken action names of the summary's toggles: "Show all steps" or "Hide steps". */
  private void describeToggles()
  {
    String action = mContext.getString(mSheet.isExpanded() ? R.string.riga_sheet_hide : R.string.riga_sheet_show);
    for (int id : new int[] {R.id.transit_description, R.id.transit_now, R.id.transit_next})
    {
      View view = mTransitFrame.findViewById(id);
      if (view.isClickable())
        androidx.core.view.ViewCompat.replaceAccessibilityAction(view,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, action,
            null);
    }
  }

  /**
   * The live line ("Tram 7 is 3 stops away…") is the one thing that changes while the rider waits: it gets the
   * vehicle's colour as a dot and stronger weight; the schedule below stays quiet.
   */
  @NonNull
  private CharSequence styleDescription(@NonNull TransitRouteInfo info)
  {
    app.organicmaps.sdk.routing.TransitJourneyLeg ride = null;
    for (app.organicmaps.sdk.routing.TransitJourneyLeg leg : info.journeyLegs)
      if (leg.isRide()) { ride = leg; break; }
    SpannableStringBuilder text = new SpannableStringBuilder(info.description);
    if (ride != null && !ride.progress.isEmpty() && info.description.startsWith(ride.progress))
    {
      text.insert(0, "● ");
      text.setSpan(new ForegroundColorSpan(ride.color), 0, 1, 0);
      text.setSpan(new StyleSpan(Typeface.BOLD), 2, 2 + ride.progress.length(), 0);
    }
    // Where the times come from closes the text as its quietest line.
    String status = info.journeyStatus;
    if (status != null && !status.isEmpty() && info.description.endsWith(status))
    {
      int start = text.length() - status.length();
      text.setSpan(new ForegroundColorSpan(com.google.android.material.color.MaterialColors.getColor(mTransitFrame,
                   android.R.attr.textColorSecondary)), start,
                   text.length(), 0);
      text.setSpan(new android.text.style.RelativeSizeSpan(0.9f), start, text.length(), 0);
    }
    return app.organicmaps.transit.RigaDelayText.rides(text, info.journeyLegs, clock());
  }

  /** Leave now / Depart at / Arrive by, for providers that plan by time (Riga). */
  private void bindTime(boolean show)
  {
    View row = mTransitFrame.findViewById(R.id.transit_time);
    row.setVisibility(show ? View.VISIBLE : View.GONE);
    if (!show) return;
    app.organicmaps.transit.RigaTimePicker.bind(mTransitFrame.findViewById(R.id.transit_time_choice),
        mTransitFrame.findViewById(R.id.transit_time_reset), mTransitFrame.findViewById(R.id.transit_time_refresh),
        false, () -> RoutingController.get().checkAndBuildRoute());
  }

  /** The same choice, compact, in the route's header row; leaving now adds a refresh beside it. */
  private void bindHeaderTime(boolean show)
  {
    View choice = mTransitFrame.findViewById(R.id.transit_header_time);
    View refresh = mTransitFrame.findViewById(R.id.transit_header_refresh);
    choice.setVisibility(show ? View.VISIBLE : View.GONE);
    if (!show)
    {
      refresh.setVisibility(View.GONE);
      return;
    }
    app.organicmaps.transit.RigaTimePicker.bind((MaterialButton) choice, null, refresh, true,
        () -> RoutingController.get().checkAndBuildRoute());
  }

  private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable mGuidanceTick = this::updateGuidance;

  /** Start/End button and, during a trip, the step to do now (re-evaluated every few seconds). */
  private void bindTrip(@NonNull TransitRouteInfo info)
  {
    // Guidance is for setting off soon; a plan for tomorrow morning has nothing to follow yet. A plan looked up
    // for earlier today can still be followed (the ride may be late, or the rider is already on it). Setting off
    // counts, not the first ride: a long walk first (to a stop with a stay) is followed from now.
    boolean soon = info.leaves() - System.currentTimeMillis() < 2 * 3600000L;
    MaterialButton start = mTransitFrame.findViewById(R.id.transit_start);
    start.setVisibility(info.trip || (info.startTrip != null && soon) ? View.VISIBLE : View.GONE);
    bindStart(start, info);
    updateGuidance();
  }

  private static void bindStart(@NonNull com.google.android.material.button.MaterialButton start,
                                @NonNull TransitRouteInfo info)
  {
    start.setText(info.trip ? R.string.riga_trip_end_short : R.string.riga_trip_start);
    start.setContentDescription(info.trip ? start.getContext().getString(R.string.riga_trip_end) : null);
    // During a trip, a small red "End" beside the guidance; Start keeps the full primary size and colour.
    float dp = start.getResources().getDisplayMetrics().density;
    if (info.trip)
      start.setIcon(null);
    else
      start.setIconResource(R.drawable.ic_navigation);
    // The style's own colours, kept from the first bind, for Start after a trip.
    if (!(start.getTag(R.id.transit_start) instanceof android.content.res.ColorStateList[]))
      start.setTag(R.id.transit_start,
                   new android.content.res.ColorStateList[] {start.getBackgroundTintList(), start.getTextColors()});
    android.content.res.ColorStateList[] style = (android.content.res.ColorStateList[]) start.getTag(R.id.transit_start);
    start.setBackgroundTintList(info.trip ? android.content.res.ColorStateList.valueOf(
        com.google.android.material.color.MaterialColors.getColor(start, androidx.appcompat.R.attr.colorError))
        : style[0]);
    start.setTextColor(info.trip ? android.content.res.ColorStateList.valueOf(com.google.android.material.color
        .MaterialColors.getColor(start, com.google.android.material.R.attr.colorOnError)) : style[1]);
    start.setMinHeight(Math.round((info.trip ? 32 : 48) * dp));
    start.setMinimumHeight(Math.round((info.trip ? 32 : 48) * dp));
    start.setMinWidth(0);
    start.setMinimumWidth(0);
    start.setInsetTop(info.trip ? 0 : Math.round(6 * dp));
    start.setInsetBottom(info.trip ? 0 : Math.round(6 * dp));
    int side = Math.round((info.trip ? 14 : 24) * dp);
    start.setPaddingRelative(side, start.getPaddingTop(), side, start.getPaddingBottom());
    start.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, info.trip ? 14 : 16);
    start.setOnClickListener(v -> {
      if (info.trip)
      {
        if (info.endTrip != null) info.endTrip.run();
        return;
      }
      if (info.startTrip == null) return;
      v.setEnabled(false);
      info.startTrip.run();
      // Like navigation: the map follows the rider and turns with them. Without a position yet, the route stays
      // framed.
      followAndRotate(start, 3);
    });
    start.setEnabled(true);
  }

  /**
   * Steps the position mode on to follow-and-rotate: on foot the map turns with the phone, on a ride with the
   * vehicle. The mode changes on the render thread, so each step is checked a moment later; without a compass
   * it stays in follow.
   */
  private static void followAndRotate(@NonNull View view, int attempts)
  {
    int mode = LocationState.getMode();
    if (attempts <= 0 || mode < LocationState.NOT_FOLLOW || mode == LocationState.FOLLOW_AND_ROTATE) return;
    LocationState.nativeSwitchToNextMode();
    view.postDelayed(() -> {
      if (mode == LocationState.FOLLOW && LocationState.getMode() == LocationState.FOLLOW) return;
      followAndRotate(view, attempts - 1);
    }, 300);
  }


  /** Street level, where the stop, the vehicle's door side and the next turn can be told apart. */
  private static final int STEP_ZOOM = 17;
  private String mStepJourney = "";
  private int mStepKey = -1;

  /**
   * As the trip moves on to its next step (off on foot, at the stop, aboard, off, arrived), the map zooms in on
   * the rider, as long as it follows them. Only forward steps count, so a position flickering between two
   * steps does not keep undoing the rider's own zoom.
   */
  private void zoomOnStep(@NonNull app.organicmaps.sdk.routing.TransitJourneyLeg[] legs,
                          @NonNull app.organicmaps.transit.RigaTripGuide.State state)
  {
    String journey = app.organicmaps.transit.RigaTripPanel.structure(legs);
    int key = state.phase == app.organicmaps.transit.RigaTripGuide.Phase.ARRIVED ? Integer.MAX_VALUE
        : state.leg * 16 + state.phase.ordinal();
    if (journey.equals(mStepJourney) && key <= mStepKey) return;
    mStepJourney = journey;
    mStepKey = key;
    int mode = LocationState.getMode();
    if (mode != LocationState.FOLLOW && mode != LocationState.FOLLOW_AND_ROTATE) return;
    int zoom = app.organicmaps.sdk.Framework.nativeGetDrawScale();
    if (zoom > 0 && zoom < STEP_ZOOM) app.organicmaps.sdk.Map.onScale(Math.pow(2, STEP_ZOOM - zoom), 0, 0, true);
  }

  private void updateGuidance()
  {
    mHandler.removeCallbacks(mGuidanceTick);
    View trip = mTransitFrame.findViewById(R.id.transit_trip);
    TransitRouteInfo info = RoutingController.get().getCachedTransitInfo();
    if (info == null || !info.trip || mTransitFrame.getVisibility() != View.VISIBLE || info.journeyLegs.length == 0)
    {
      trip.setVisibility(View.GONE);
      bindPlanFoot(info);
      return;
    }
    android.location.Location location = app.organicmaps.MwmApplication.from(mContext).getLocationHelper()
        .getSavedLocation();
    long now = System.currentTimeMillis();
    app.organicmaps.sdk.routing.TransitJourneyLeg[] legs = info.journeyLegs;
    // The same "now" as the trip notification.
    app.organicmaps.transit.RigaTripGuide.State state = app.organicmaps.transit.RigaTripPanel.now(legs, now,
        location == null ? Double.NaN : location.getLatitude(), location == null ? Double.NaN : location.getLongitude());
    app.organicmaps.transit.RigaTripPanel panel = app.organicmaps.transit.RigaTripPanel.of(legs, state);
    zoomOnStep(legs, state);
    trip.setVisibility(View.VISIBLE);
    mSheet.bind(info, panel);
    bindNow(panel, legs, now);
    bindNext(panel, legs);
    bindOff(panel, legs, now);
    bindProgress(info, panel, now);
    android.widget.Button action = mTransitFrame.findViewById(R.id.transit_action);
    if (info.action == null) action.setVisibility(View.GONE);
    mSheet.applyCompact();
    mHandler.postDelayed(mGuidanceTick, 5000);
  }

  /**
   * Where to get off the coming ride and how soon, always in sight during a trip, beside End trip. Unfolded, unless
   * the step now or the one after it already says where to get off. Folded, the "now" card's step, detail and
   * countdown lead it.
   */
  private void bindOff(@NonNull app.organicmaps.transit.RigaTripPanel panel,
                       @NonNull app.organicmaps.sdk.routing.TransitJourneyLeg[] legs, long now)
  {
    int ride = -1;
    if (panel.now.leg >= 0)
      for (int i = panel.now.leg; i < legs.length; ++i)
        if (legs[i].isRide() && legs[i].stops.length > 0) { ride = i; break; }
    boolean onRide = panel.now.kind == app.organicmaps.transit.RigaTripPanel.Kind.RIDE;
    boolean thenOff = panel.next != null && panel.next.kind == app.organicmaps.transit.RigaTripPanel.Kind.RIDE
        && panel.next.leg == ride;
    app.organicmaps.sdk.routing.TransitJourneyLeg.Stop off = ride < 0 ? null
        : legs[ride].stops[legs[ride].stops.length - 1];
    long offMinutes = off == null ? 0 : app.organicmaps.transit.RigaTripPanel.minutes(off.arrival, now);
    String offTime = off == null ? "" : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(new java.util.Date(off.arrival));

    String offLine = off == null ? "" : offMinutes <= 0
        ? mContext.getString(R.string.riga_trip_off_line_now, off.name)
        : mContext.getString(R.string.riga_trip_off_line, off.name, offTime, (int) offMinutes);
    // The stop to get off at stands out.
    SpannableStringBuilder offText = new SpannableStringBuilder(offLine);
    int at = off == null ? -1 : offLine.indexOf(off.name);
    if (at >= 0)
      offText.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + off.name.length(),
                      android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    // The line sits beside End trip, in the foot row, so it takes no row of its own.
    mTransitFrame.findViewById(R.id.transit_off).setVisibility(View.GONE);
    TextView foot = mTransitFrame.findViewById(R.id.transit_trip_off);
    if (!mSheet.isCompact())
    {
      // Unfolded: where to get off, unless the step now or the one after it already says so.
      boolean show = off != null && !onRide && !thenOff;
      foot.setText(show ? offText : "");
      foot.setVisibility(show ? View.VISIBLE : View.GONE);
      return;
    }
    // Folded: what the "now" card says (its ticket button aside), then where to get off when that is a later step.
    SpannableStringBuilder text = new SpannableStringBuilder();
    CharSequence title = ((TextView) mTransitFrame.findViewById(R.id.transit_now_title)).getText();
    text.append(title);
    text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, text.length(),
                 android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    text.setSpan(new android.text.style.RelativeSizeSpan(1.1f), 0, text.length(),
                 android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    View countdown = mTransitFrame.findViewById(R.id.transit_now_countdown);
    CharSequence label = ((TextView) mTransitFrame.findViewById(R.id.transit_now_label)).getText();
    CharSequence minutes = ((TextView) mTransitFrame.findViewById(R.id.transit_now_minutes)).getText();
    if (countdown.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(minutes))
    {
      String when = TextUtils.isEmpty(label) ? minutes.toString()
          : label.toString().toLowerCase(java.util.Locale.getDefault()) + " " + minutes;
      // One unit: "departs in 3 min" moves to the next line whole rather than breaking before "min".
      text.append(" · ").append(when.trim().replaceAll("\\s+", "\u00a0"));
    }
    TextView detail = mTransitFrame.findViewById(R.id.transit_now_detail);
    if (detail.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(detail.getText()))
    {
      int start = text.length() + 1;
      text.append('\n').append(detail.getText());
      text.setSpan(new ForegroundColorSpan(com.google.android.material.color.MaterialColors.getColor(foot,
                   android.R.attr.textColorSecondary)), start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (off != null && !onRide) text.append('\n').append(offText);
    foot.setText(text);
    foot.setVisibility(View.VISIBLE);
  }

  /**
   * Folded, a plan keeps what to do first beside Start: "Leave 19:44. Trolleybus 15 at 19:50 from Mazā Kalna
   * iela", with when to leave in bold. Unfolded, the plan's description says it.
   */
  private void bindPlanFoot(@Nullable TransitRouteInfo info)
  {
    TextView foot = mTransitFrame.findViewById(R.id.transit_trip_off);
    String line = "";
    if (info != null && !info.trip && mSheet.isCompact() && mTransitFrame.getVisibility() == View.VISIBLE)
    {
      String progress = "";
      for (app.organicmaps.sdk.routing.TransitJourneyLeg leg : info.journeyLegs)
        if (leg.isRide()) { progress = leg.progress; break; }
      for (String part : info.description.split("\n"))
      {
        String trimmed = part.trim();
        if (trimmed.isEmpty() || trimmed.equals(progress) || trimmed.equals(info.journeyStatus)) continue;
        line = trimmed;
        break;
      }
    }
    if (line.isEmpty())
    {
      foot.setVisibility(View.GONE);
      return;
    }
    SpannableStringBuilder text = new SpannableStringBuilder(line);
    int end = line.length();
    for (String stop : new String[] {". ", ", "})
    {
      int at = line.indexOf(stop);
      if (at > 0) end = Math.min(end, at);
    }
    text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, end,
                 android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    foot.setText(app.organicmaps.transit.RigaDelayText.rides(text, info.journeyLegs, clock()));
    foot.setVisibility(View.VISIBLE);
  }

  /** The step to do now: what, where and when, a countdown, other departures, and the ride's ticket button. */
  private void bindNow(@NonNull app.organicmaps.transit.RigaTripPanel panel,
                       @NonNull app.organicmaps.sdk.routing.TransitJourneyLeg[] legs, long now)
  {
    app.organicmaps.transit.RigaTripPanel.Step step = panel.now;
    app.organicmaps.sdk.routing.TransitJourneyLeg leg = step.leg >= 0 ? legs[step.leg] : null;
    TextView badge = mTransitFrame.findViewById(R.id.transit_now_badge);
    boolean board = step.kind == app.organicmaps.transit.RigaTripPanel.Kind.BOARD;
    badge.setVisibility(leg == null ? View.GONE : View.VISIBLE);
    // The planned line, as the title names it; the other lines making the same ride are in the "Also at" line.
    if (leg != null && leg.isStay()) TransitBadge.stop(badge, TransitBadge.stopNumber(legs, step.leg), 32);
    else if (leg != null) TransitBadge.bind(badge, leg, leg.number, !leg.isRide() ? 28 : 18);
    ((TextView) mTransitFrame.findViewById(R.id.transit_now_title)).setText(panel.title(mContext, legs, now));
    TextView detail = mTransitFrame.findViewById(R.id.transit_now_detail);
    CharSequence where = panel.detail(mContext, legs);
    detail.setText(where);
    detail.setVisibility(where.length() == 0 ? View.GONE : View.VISIBLE);
    String label = panel.countdownLabel(mContext, legs);
    mTransitFrame.findViewById(R.id.transit_now_countdown).setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
    ((TextView) mTransitFrame.findViewById(R.id.transit_now_label)).setText(label);
    long minutes = app.organicmaps.transit.RigaTripPanel.minutes(panel.state.at, now);
    TextView count = mTransitFrame.findViewById(R.id.transit_now_minutes);
    if (minutes == 0) count.setText(R.string.riga_trip_chip_now);
    else
    {
      SpannableStringBuilder value = new SpannableStringBuilder(Long.toString(minutes)).append(' ');
      int unit = value.length();
      value.append(mContext.getString(R.string.minute));
      value.setSpan(new android.text.style.RelativeSizeSpan(0.6f), unit, value.length(), 0);
      count.setText(value);
    }
    count.setContentDescription(label + " " + mContext.getString(R.string.riga_trip_chip_minutes, (int) minutes));
    TextView also = mTransitFrame.findViewById(R.id.transit_now_also);
    String others = board && leg != null && !panel.state.missed ? alsoAt(leg) : "";
    also.setText(others);
    also.setVisibility(others.isEmpty() ? View.GONE : View.VISIBLE);
    com.google.android.material.button.MaterialButton validate = mTransitFrame.findViewById(R.id.transit_now_validate);
    app.organicmaps.sdk.routing.TransitJourneyLeg ticket = panel.validate >= 0 ? legs[panel.validate] : null;
    // Validating belongs to boarding: a stop or two into the ride the button goes (the steps keep the number).
    if (ticket != null && step.kind == app.organicmaps.transit.RigaTripPanel.Kind.RIDE && ticket == leg
        && panel.state.stopsLeft < leg.stopCount() - 1)
      ticket = null;
    bindValidate(validate, ticket);
    // In the card, short and on one line; the line's badge only when the card is about another step.
    if (ticket != null && validate.getVisibility() == View.VISIBLE && !ticket.vehicleNumber.isEmpty()
        && validate.getIcon() != null)
    {
      CharSequence text = mContext.getString(R.string.riga_validate_short, ticket.vehicleNumber);
      validate.setText(ticket == leg ? text
          : TransitBadge.prefix(ticket, text, mContext.getResources().getDisplayMetrics().density));
      validate.setIconSize(Math.round(20 * mContext.getResources().getDisplayMetrics().density));
    }
  }

  /** The step after this one, smaller, with the badge of the ride it is about. */
  private void bindNext(@NonNull app.organicmaps.transit.RigaTripPanel panel,
                        @NonNull app.organicmaps.sdk.routing.TransitJourneyLeg[] legs)
  {
    View row = mTransitFrame.findViewById(R.id.transit_next);
    row.setVisibility(panel.next == null ? View.GONE : View.VISIBLE);
    if (panel.next == null) return;
    TextView badge = mTransitFrame.findViewById(R.id.transit_next_badge);
    int ride = panel.nextBadgeLeg();
    app.organicmaps.sdk.routing.TransitJourneyLeg leg = ride >= 0 ? legs[ride] : null;
    boolean arrive = panel.next.kind == app.organicmaps.transit.RigaTripPanel.Kind.ARRIVE;
    // Waiting, "then" is getting off the same ride: its badge is already in the card above.
    boolean same = panel.next.kind == app.organicmaps.transit.RigaTripPanel.Kind.RIDE && panel.next.leg == panel.now.leg;
    badge.setVisibility(arrive || same ? View.GONE : View.VISIBLE);
    if (panel.next.kind == app.organicmaps.transit.RigaTripPanel.Kind.STAY)
      TransitBadge.stop(badge, TransitBadge.stopNumber(legs, panel.next.leg), 22);
    else if (!arrive) TransitBadge.bind(badge, leg, leg == null ? "" : group(leg, 3), leg == null ? 20 : 14);
    ((TextView) mTransitFrame.findViewById(R.id.transit_next_text)).setText(panel.nextText(mContext, legs));
  }

  /** Header and chips during a trip: time left and arrival; legs already behind the rider faded. */
  private void bindProgress(@NonNull TransitRouteInfo info, @NonNull app.organicmaps.transit.RigaTripPanel panel,
                            long now)
  {
    RecyclerView rv = mTransitFrame.findViewById(R.id.transit_recycler_view);
    if (rv.getAdapter() instanceof TransitStepAdapter adapter)
    {
      int done = 0;
      while (done < mChipLegs.length && mChipLegs[done] < panel.done) ++done;
      adapter.setDone(done);
    }
    if (info.arrival <= 0) return;
    MaterialTextView total = mTransitFrame.findViewById(R.id.total_time);
    long left = Math.max(0, info.arrival - now);
    total.setText(Utils.formatRoutingTime(mContext, (int) ((left + 59999) / 60000 * 60), R.dimen.text_size_routing_number));
    MaterialTextView arrive = mTransitFrame.findViewById(R.id.total_distance);
    String at = clock().format(new java.util.Date(info.arrival));
    arrive.setText(mContext.getString(R.string.riga_live_arrive, at));
    arrive.setContentDescription(mContext.getString(R.string.riga_trip_remaining,
        RouteModeTimes.duration(mContext, left), at));
  }

  /** "7/14": the lines making the same ride as {@code leg}, at most {@code max}. */
  @NonNull
  static String group(@NonNull app.organicmaps.sdk.routing.TransitJourneyLeg leg, int max)
  {
    if (leg.lines.length <= 1) return leg.number;
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < leg.lines.length && i < max; ++i) text.append(i > 0 ? "/" : "").append(leg.lines[i]);
    if (leg.lines.length > max) text.append("/…");
    return text.toString();
  }

  /** "Also at 07:28, 07:45 (31)": other departures of the same ride, with the line when it is another one. */
  @NonNull
  String alsoAt(@NonNull app.organicmaps.sdk.routing.TransitJourneyLeg leg)
  {
    return alsoAt(mContext, leg, clock());
  }

  @NonNull
  static String alsoAt(@NonNull Context context, @NonNull app.organicmaps.sdk.routing.TransitJourneyLeg leg,
                       @NonNull java.text.SimpleDateFormat clock)
  {
    if (leg.also.length == 0) return "";
    StringBuilder times = new StringBuilder();
    for (int i = 0; i < leg.also.length; ++i)
    {
      String time = clock.format(new java.util.Date(leg.also[i]));
      String line = i < leg.alsoLines.length ? leg.alsoLines[i] : leg.number;
      times.append(i > 0 ? ", " : "")
          .append(line.equals(leg.number) ? time : context.getString(R.string.riga_also_line, time, line));
    }
    return context.getString(R.string.riga_also_at, times);
  }

  /**
   * Manage Route is a compact pencil: at the end of the header row once a route is shown, or beside the time
   * while there is no header yet. Never a row of its own.
   */
  private void showManage(boolean header)
  {
    View inHeader = mTransitFrame.findViewById(R.id.transit_manage);
    View inline = mTransitFrame.findViewById(R.id.transit_manage_inline);
    inHeader.setVisibility(header ? View.VISIBLE : View.GONE);
    if (!header) bindHeaderTime(false);
    inline.setVisibility(header ? View.GONE : View.VISIBLE);
    inHeader.setOnClickListener(v -> mListener.onManageRouteOpen());
    inline.setOnClickListener(v -> mListener.onManageRouteOpen());
  }

  /** Share the plan: "From → To, 19:57–20:14" and a link that opens it (not during a trip, nothing to plan). */
  private void bindShare(@NonNull TransitRouteInfo info)
  {
    View share = mTransitFrame.findViewById(R.id.transit_share);
    app.organicmaps.sdk.bookmarks.data.MapObject start = RoutingController.get().getStartPoint();
    app.organicmaps.sdk.bookmarks.data.MapObject end = RoutingController.get().getEndPoint();
    long leave = leave(info);
    boolean can = !info.trip && start != null && end != null && leave > 0;
    share.setVisibility(can ? View.VISIBLE : View.GONE);
    if (!can) return;
    share.setOnClickListener(v -> {
      String text = app.organicmaps.transit.RigaShare.text(placeName(start, info, true), start.getLat(),
          start.getLon(), placeName(end, info, false), end.getLat(), end.getLon(), leave, info.arrival,
          System.currentTimeMillis());
      android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
          .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text);
      mContext.startActivity(android.content.Intent.createChooser(send, mContext.getString(R.string.share)));
    });
  }

  /** A point's name for the shared text; "my position" means nothing to the reader, so the stop nearest it. */
  @NonNull
  private static String placeName(@NonNull app.organicmaps.sdk.bookmarks.data.MapObject point,
                                  @NonNull TransitRouteInfo info, boolean start)
  {
    String title = point.isMyPosition() ? "" : point.getTitle();
    if (!TextUtils.isEmpty(title)) return title;
    app.organicmaps.sdk.routing.TransitJourneyLeg[] legs = info.journeyLegs;
    for (int i = 0; i < legs.length; ++i)
    {
      app.organicmaps.sdk.routing.TransitJourneyLeg leg = legs[start ? i : legs.length - 1 - i];
      if (leg.stops.length == 0 || leg.type == app.organicmaps.sdk.routing.TransitStepType.PEDESTRIAN) continue;
      String name = leg.stops[start ? 0 : leg.stops.length - 1].name;
      if (!TextUtils.isEmpty(name)) return name;
    }
    return String.format(java.util.Locale.US, "%.5f, %.5f", point.getLat(), point.getLon());
  }

  /** @return true if the back press closed the open transit steps. */
  boolean collapseTransitSheet()
  {
    return mSheet.collapse();
  }

  void showTransitStatus(RoutingController.TransitResult status)
  {
    mSheet.reset();
    UiUtils.hide(mTransitFrame, R.id.transit_trip_off);
    UiUtils.hide(mTransitFrame, R.id.transit_start);
    mTransitFrame.findViewById(R.id.transit_details).setVisibility(View.VISIBLE);
    UiUtils.hide(mError, mAltitudeChartFrame, mActionFrame);
    showStartButton(false);
    UiUtils.show(mTransitFrame);
    UiUtils.hide(mTransitFrame.findViewById(R.id.total_time), mTransitFrame.findViewById(R.id.dot),
        mTransitFrame.findViewById(R.id.pedestrian_icon), mTransitFrame.findViewById(R.id.total_distance),
        mTransitFrame.findViewById(R.id.transit_chips_scroll));
    UiUtils.hide(mTransitFrame.findViewById(R.id.transit_alternatives), mTransitFrame.findViewById(R.id.transit_mobilly),
        mTransitFrame.findViewById(R.id.transit_trip),
        mTransitFrame.findViewById(R.id.transit_wait));
    mHandler.removeCallbacks(mGuidanceTick);
    bindTime(true);
    android.widget.TextView message = mTransitFrame.findViewById(R.id.transit_description);
    mDescription = TextUtils.isEmpty(status.message) ? "" : status.message;
    mDescriptionToggles = false;
    bindDescription();
    message.setClickable(false);
    message.setVisibility(TextUtils.isEmpty(status.message) ? View.GONE : View.VISIBLE);
    android.widget.Button action = mTransitFrame.findViewById(R.id.transit_action);
    action.setVisibility(status.action == null || TextUtils.isEmpty(status.actionLabel) ? View.GONE : View.VISIBLE);
    action.setText(status.actionLabel);
    action.setOnClickListener(v -> status.action.run());
    showManage(false);
    UiUtils.hide(mTransitFrame, R.id.transit_share);
  }

  @SuppressLint("SetTextI18n")
  void showRulerInfo(@NonNull RouteMarkData[] points, Distance totalLength)
  {
    mSheet.reset();
    UiUtils.hide(mTransitFrame, R.id.transit_start);
    // Invisible, not gone: the distance line and the steps below are laid out against the pencil's row.
    mTransitFrame.findViewById(R.id.transit_manage).setVisibility(View.INVISIBLE);
    bindHeaderTime(false);
    UiUtils.hide(mTransitFrame, R.id.transit_share);
    mTransitFrame.findViewById(R.id.transit_details).setVisibility(View.GONE);
    mTransitFrame.findViewById(R.id.transit_chips_scroll).setVisibility(View.VISIBLE);
    // A transit status ("Finding route…") hid the distance line.
    UiUtils.show(mTransitFrame.findViewById(R.id.total_time));
    UiUtils.hide(mTransitFrame, R.id.transit_wait);
    UiUtils.hide(mError, mAltitudeChartFrame, mActionFrame, mAltitudeChartFrame);
    showStartButton(false);
    UiUtils.show(mTransitFrame);
    final RecyclerView rv = mTransitFrame.findViewById(R.id.transit_recycler_view);
    if (points.length > 2)
    {
      UiUtils.show(rv);
      final TransitStepAdapter adapter = new TransitStepAdapter();
      rv.setLayoutManager(new MultilineLayoutManager(mTransitFrame.getLayoutDirection()));
      rv.setNestedScrollingEnabled(false);
      rv.removeItemDecoration(mTransitViewDecorator);
      rv.addItemDecoration(mTransitViewDecorator);
      rv.setAdapter(adapter);
      adapter.setItems(pointsToRulerSteps(points));

      scrollToBottom(rv);
    }
    else
      UiUtils.hide(rv); // Show only distance between start and finish

    MaterialTextView totalTimeView = mTransitFrame.findViewById(R.id.total_time);
    totalTimeView.setText(mContext.getString(R.string.placepage_distance) + ": " + totalLength.mDistanceStr + " "
                          + totalLength.getUnitsStr(mContext));

    UiUtils.hide(mTransitFrame, R.id.dot);
    UiUtils.hide(mTransitFrame, R.id.pedestrian_icon);
    UiUtils.hide(mTransitFrame, R.id.total_distance);
  }

  /** Leg index of each chip shown for a timetabled journey (empty for other routes). */
  private int[] mChipLegs = new int[0];

  /**
   * The chips: one per leg, except a short change on foot between two rides ("7 › 49", as the alternative
   * cards show it). Routes without legs keep one chip per walk.
   */
  @NonNull
  private java.util.List<TransitStepInfo> chips(@NonNull TransitRouteInfo info)
  {
    java.util.List<TransitStepInfo> steps = info.getTransitSteps();
    app.organicmaps.sdk.routing.TransitJourneyLeg[] legs = info.journeyLegs;
    if (legs.length != steps.size())
    {
      mChipLegs = new int[0];
      return mergeWalks(steps);
    }
    java.util.List<TransitStepInfo> shown = new java.util.ArrayList<>();
    int[] map = new int[steps.size()];
    for (int i = 0; i < steps.size(); ++i)
    {
      if (app.organicmaps.transit.RigaWalks.shortChange(legs, i)) continue;
      map[shown.size()] = i;
      TransitStepInfo step = steps.get(i);
      // A stop of the route shows the stay the rider chose (what its chip changes); the arrival and departure,
      // which may leave a little longer for the next ride, are in the steps.
      if (legs[i].isStay())
        step = new TransitStepInfo(TransitStepType.INTERMEDIATE_POINT.ordinal(), "", "",
            (int) (StayPicker.chosen(mContext, legs[i]) / 1000), "", step.getColor(), step.getIntermediateIndex());
      shown.add(step);
    }
    mChipLegs = java.util.Arrays.copyOf(map, shown.size());
    return shown;
  }

  /** The ride the foot's validate button is for while the steps are closed: the plan's next validated ride. */
  @Nullable
  private app.organicmaps.sdk.routing.TransitJourneyLeg mValidateRide;

  /** The ticket app button for {@code ride}, led by its line badge so it is clear which ride it validates. */
  private void bindValidate(@NonNull com.google.android.material.button.MaterialButton button,
                            @Nullable app.organicmaps.sdk.routing.TransitJourneyLeg ride)
  {
    button.setVisibility(ride == null ? View.GONE : View.VISIBLE);
    if (ride == null) return;
    // style() hides the button when the vehicle is unknown and no ticket app is installed.
    MobillyShortcut.style(button, ride.vehicleNumber);
    button.setText(TransitBadge.prefix(ride, button.getText(), mContext.getResources().getDisplayMetrics().density));
    button.setContentDescription(app.organicmaps.transit.RigaModeStyle.name(mContext, ride.type) + " " + ride.number
                                 + ". " + button.getText().toString().substring(ride.number.length()).trim());
  }

  /** Whether a long route's chips show all their rows (the rider asked for them) or the first two. */
  private boolean mChipsExpanded;

  /**
   * Shows two rows of chips with an expander beside them when the route needs more, so the plan's summary stays
   * short and the line under it in view; the expander shows the rest.
   */
  private void fitChips()
  {
    ScrollView scroll = mTransitFrame.findViewById(R.id.transit_chips_scroll);
    RecyclerView rv = mTransitFrame.findViewById(R.id.transit_recycler_view);
    android.widget.ImageButton more = mTransitFrame.findViewById(R.id.transit_chips_more);
    // Where the third row starts: the height of two rows with the space under them.
    int third = -1;
    if (scroll.getVisibility() == View.VISIBLE && rv.getVisibility() == View.VISIBLE)
    {
      java.util.TreeSet<Integer> rows = new java.util.TreeSet<>();
      for (int i = 0; i < rv.getChildCount(); ++i) rows.add(rv.getChildAt(i).getTop());
      if (rows.size() > 2) third = new java.util.ArrayList<>(rows).get(2) + rv.getPaddingTop();
    }
    int visibility = third > 0 ? View.VISIBLE : View.GONE;
    if (more.getVisibility() != visibility) more.setVisibility(visibility);
    int height = third > 0 && !mChipsExpanded ? third : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
    android.view.ViewGroup.LayoutParams params = scroll.getLayoutParams();
    if (params.height != height)
    {
      params.height = height;
      scroll.setLayoutParams(params);
      if (height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) scroll.scrollTo(0, 0);
    }
    int icon = mChipsExpanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more;
    if (!Integer.valueOf(icon).equals(more.getTag()))
    {
      more.setTag(icon);
      more.setImageResource(icon);
      more.setContentDescription(mContext.getString(mChipsExpanded ? R.string.riga_chips_less : R.string.riga_chips_more));
    }
  }

  /** One chip per walk: an exit walk routed in pieces between stops reads as a single walk. */
  @NonNull
  private static java.util.List<TransitStepInfo> mergeWalks(@NonNull java.util.List<TransitStepInfo> steps)
  {
    java.util.List<TransitStepInfo> merged = new java.util.ArrayList<>();
    for (TransitStepInfo step : steps)
    {
      int last = merged.size() - 1;
      if (last >= 0 && step.getType() == TransitStepType.PEDESTRIAN
          && merged.get(last).getType() == TransitStepType.PEDESTRIAN)
      {
        TransitStepInfo previous = merged.get(last);
        merged.set(last, new TransitStepInfo(TransitStepType.PEDESTRIAN.ordinal(), previous.getDistance(),
            previous.getDistanceUnits(), previous.getTimeInSec() + step.getTimeInSec(), previous.getNumber(),
            previous.getColor(), previous.getIntermediateIndex()));
      }
      else merged.add(step);
    }
    return merged;
  }

  // Create steps info to use in TransitStepAdapter.
  private List<TransitStepInfo> pointsToRulerSteps(RouteMarkData[] points)
  {
    List<TransitStepInfo> transitSteps = new LinkedList<>();
    for (int i = 1; i < points.length; i++)
    {
      RouteMarkData segmentStart = points[i - 1];
      RouteMarkData segmentEnd = points[i];
      DistanceAndAzimut dist = Framework.nativeGetDistanceAndAzimuthFromLatLon(segmentStart.mLat, segmentStart.mLon,
                                                                               segmentEnd.mLat, segmentEnd.mLon, 0);
      if (i > 1)
        transitSteps.add(TransitStepInfo.intermediatePoint(i - 2));
      transitSteps.add(
          TransitStepInfo.ruler(dist.getDistance().mDistanceStr, dist.getDistance().getUnitsStr(mContext)));
    }

    return transitSteps;
  }

  void showAddStartFrame()
  {
    UiUtils.hide(mError, mTransitFrame);
    UiUtils.show(mActionFrame);
    mActionMessage.setText(R.string.routing_add_start_point);
    mActionMessage.setTag(RouteMarkType.Start);
    if (MwmApplication.from(mContext).getLocationHelper().getMyPosition() != null)
    {
      UiUtils.show(mActionButton);
      Drawable icon = ContextCompat.getDrawable(mContext, R.drawable.ic_location_crosshair);
      int colorSecondary = ContextCompat.getColor(
          mContext, UiUtils.getStyledResourceId(mContext, com.google.android.material.R.attr.colorSecondary));
      mActionIcon.setImageDrawable(Graphics.tint(icon, colorSecondary));
    }
    else
    {
      UiUtils.hide(mActionButton);
    }
  }

  void showAddFinishFrame()
  {
    UiUtils.hide(mError, mTransitFrame);
    UiUtils.show(mActionFrame);
    mActionMessage.setText(R.string.routing_add_finish_point);
    mActionMessage.setTag(RouteMarkType.Finish);
    UiUtils.hide(mActionButton);
  }

  void hideActionFrame()
  {
    UiUtils.hide(mActionFrame);
  }

  void setStartButton(boolean show)
  {
    if (show)
    {
      mStart.setText(mContext.getText(R.string.p2p_start));
      mStart.setOnClickListener(v -> {
        // Ignore the event if the back and start buttons are pressed at the same time.
        // See {@link #RoutingPlanController.onUpClick()}.
        // https://github.com/organicmaps/organicmaps/issues/6628
        if (mListener != null && RoutingController.get().isPlanning())
          mListener.onRoutingStart();
      });
    }

    showStartButton(show);
  }

  private void showError(@NonNull String message)
  {
    UiUtils.hide(mAltitudeChartFrame, mActionFrame, mTransitFrame);
    mError.setText(message);
    mError.setVisibility(View.VISIBLE);
    showStartButton(false);
  }

  void showStartButton(boolean show)
  {
    boolean result = show && RoutingController.get().isBuilt();
    UiUtils.showIf(result, mStart);
  }

  void saveRoutingPanelState(@NonNull Bundle outState)
  {
    outState.putBoolean(STATE_ALTITUDE_CHART_SHOWN, UiUtils.isVisible(mAltitudeChartFrame));
    if (UiUtils.isVisible(mError))
      outState.putString(STATE_ERROR, mError.getText().toString());
  }

  void restoreRoutingPanelState(@NonNull Bundle state)
  {
    if (state.getBoolean(STATE_ALTITUDE_CHART_SHOWN))
      showAltitudeChartAndRoutingDetails();

    String error = state.getString(STATE_ERROR);
    if (!TextUtils.isEmpty(error))
      showError(error);
  }

  private void showRouteAltitudeChart()
  {
    if (RoutingController.get().isVehicleRouterType())
    {
      UiUtils.hide(mTimeElevationLine, mAltitudeChart);
      return;
    }

    UiUtils.hide(mTimeVehicle);

    int chartWidth = dimen(mContext, R.dimen.altitude_chart_image_width);
    int chartHeight = dimen(mContext, R.dimen.altitude_chart_image_height);
    Framework.RouteAltitudeLimits limits = new Framework.RouteAltitudeLimits();
    Bitmap bm = Framework.generateRouteAltitudeChart(chartWidth, chartHeight, limits);
    if (bm != null)
    {
      mAltitudeChart.setImageBitmap(bm);
      UiUtils.show(mAltitudeChart);
      final String unit = limits.isMetricUnits
                            ? mAltitudeDifference.getResources().getString(app.organicmaps.sdk.R.string.m)
                            : mAltitudeDifference.getResources().getString(app.organicmaps.sdk.R.string.ft);
      mAltitudeDifference.setText("↗ " + limits.totalAscentString + " " + unit + " ↘ " + limits.totalDescentString + " "
                                  + unit);
      UiUtils.show(mAltitudeDifference);
    }
  }

  private void showRoutingDetails()
  {
    final RoutingInfo rinfo = RoutingController.get().getCachedRoutingInfo();
    if (rinfo == null)
    {
      UiUtils.hide(mTimeElevationLine, mTimeVehicle);
      return;
    }

    Spanned spanned = makeSpannedRoutingDetails(mContext, rinfo);
    if (RoutingController.get().isVehicleRouterType())
    {
      UiUtils.show(mTimeVehicle);
      mTimeVehicle.setText(spanned);
    }
    else
    {
      UiUtils.show(mTimeElevationLine);
      mTime.setText(spanned);
    }

    if (mArrival != null)
    {
      String arrivalTime = Utils.formatArrivalTime(rinfo.totalTimeInSeconds);
      mArrival.setText(arrivalTime);
    }
  }

  // Scroll RecyclerView to bottom using parent ScrollView.
  private static void scrollToBottom(RecyclerView rv)
  {
    final ScrollView parentScroll = (ScrollView) rv.getParent();
    if (parentScroll != null)
      parentScroll.postDelayed(() -> parentScroll.fullScroll(ScrollView.FOCUS_DOWN), 100);
  }

  @NonNull
  private static Spanned makeSpannedRoutingDetails(@NonNull Context context, @NonNull RoutingInfo routingInfo)

  {
    CharSequence time =
        Utils.formatRoutingTime(context, routingInfo.totalTimeInSeconds, R.dimen.text_size_routing_number);

    SpannableStringBuilder builder = new SpannableStringBuilder();
    initTimeBuilderSequence(context, time, builder);

    String dot = "\u00A0• ";
    initDotBuilderSequence(context, dot, builder);

    initDistanceBuilderSequence(context, routingInfo.distToTarget.toString(context), builder);

    return builder;
  }

  private static void initTimeBuilderSequence(@NonNull Context context, @NonNull CharSequence time,
                                              @NonNull SpannableStringBuilder builder)
  {
    builder.append(time);

    builder.setSpan(new TypefaceSpan(context.getResources().getString(R.string.robotoMedium)), 0, builder.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(
        new AbsoluteSizeSpan(context.getResources().getDimensionPixelSize(R.dimen.text_size_routing_number)), 0,
        builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new StyleSpan(Typeface.BOLD), 0, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new ForegroundColorSpan(ThemeUtils.getColor(context, android.R.attr.textColorPrimary)), 0,
                    builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private static void initDotBuilderSequence(@NonNull Context context, @NonNull String dot,
                                             @NonNull SpannableStringBuilder builder)
  {
    builder.append(dot);
    builder.setSpan(new TypefaceSpan(context.getResources().getString(R.string.robotoMedium)),
                    builder.length() - dot.length(), builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(
        new AbsoluteSizeSpan(context.getResources().getDimensionPixelSize(R.dimen.text_size_routing_number)),
        builder.length() - dot.length(), builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new ForegroundColorSpan(ThemeUtils.getColor(context, R.attr.secondary)),
                    builder.length() - dot.length(), builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private static void initDistanceBuilderSequence(@NonNull Context context, @NonNull String arrivalTime,
                                                  @NonNull SpannableStringBuilder builder)
  {
    builder.append(arrivalTime);
    builder.setSpan(new TypefaceSpan(context.getResources().getString(R.string.robotoMedium)),
                    builder.length() - arrivalTime.length(), builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(
        new AbsoluteSizeSpan(context.getResources().getDimensionPixelSize(R.dimen.text_size_routing_number)),
        builder.length() - arrivalTime.length(), builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new StyleSpan(Typeface.NORMAL), builder.length() - arrivalTime.length(), builder.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    builder.setSpan(new ForegroundColorSpan(ThemeUtils.getColor(context, android.R.attr.textColorPrimary)),
                    builder.length() - arrivalTime.length(), builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  @Override
  public void onClick(View v)
  {
    final int id = v.getId();
    if (id == R.id.btn__my_position_use)
      mListener.onUseMyPositionAsStart();
    else if (id == R.id.btn__search_point)
    {
      final RouteMarkType pointType = (RouteMarkType) mActionMessage.getTag();
      mListener.onSearchRoutePoint(pointType);
    }
    else if (id == R.id.btn__manage_route)
      mListener.onManageRouteOpen();
    else if (id == R.id.btn__directions_preview)
      mListener.onDirectionsPreviewOpen();
    else if (id == R.id.btn__save)
    {
      Framework.nativeSaveRoute();
      MaterialButton saveButton = (MaterialButton) v;
      saveButton.setEnabled(false);
      saveButton.setIcon(AppCompatResources.getDrawable(mContext, R.drawable.ic_bookmark_checked));
      saveButton.setContentDescription(mContext.getString(R.string.saved));
    }
  }
}
