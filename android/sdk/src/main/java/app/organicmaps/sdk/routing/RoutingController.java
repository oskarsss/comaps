package app.organicmaps.sdk.routing;

import android.text.TextUtils;
import androidx.annotation.IntRange;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.sdk.util.concurrency.UiThread;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.sdk.widget.placepage.CoordinatesFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@androidx.annotation.UiThread
public class RoutingController
{
  private static final String TAG = RoutingController.class.getSimpleName();

  // dist > POSITIVE_INFINITY is false for every finite distance
  private static final double NO_JUNCTION_INTERPOLATION = Double.POSITIVE_INFINITY;

  private enum State
  {
    NONE,
    PREPARE,
    NAVIGATION
  }

  public enum BuildState
  {
    NONE,
    BUILDING,
    BUILT,
    ERROR
  }

  public interface Container
  {
    default void showRoutePlan(boolean show, @Nullable Runnable completionListener) {}
    default void showNavigation(boolean show) {}
    default void updateMenu() {}
    default void onNavigationCancelled() {}
    default void onNavigationStarted() {}
    default void onPlanningCancelled() {}
    default void onPlanningStarted() {}
    default void onAddedStop() {}
    default void onRemovedStop() {}
    default void onResetToPlanningState() {}
    default void onBuiltRoute() {}
    default void onDrivingOptionsWarning() {}

    default void onCommonBuildError(int lastResultCode, @NonNull String[] lastMissingMaps) {}
    default void onDrivingOptionsBuildError() {}

    /**
     * @param progress progress to be displayed.
     * */
    default void updateBuildProgress(@IntRange(from = 0, to = 100) int progress, Router router) {}
    default void refreshNavigationController() {}
    default void onStartRouteBuilding() {}
    default void onTurnPreviewChanged(boolean isPreviewing, int segmentIndex) {}
  }

  private static final RoutingController sInstance = new RoutingController();

  @Nullable
  private Container mContainer;

  private BuildState mBuildState = BuildState.NONE;
  private State mState = State.NONE;
  private boolean mIsPreviewingTurn;
  private int mPreviewedTurnSegmentIndex = -1;
  @Nullable
  private RouteMarkType mWaitingPoiPickType = null;
  private int mLastBuildProgress;
  private Router mLastRouterType;

  private LocationHelper mLocationHelper;
  private boolean mHasContainerSavedState;
  private boolean mContainsCachedResult;
  private int mLastResultCode;
  private String[] mLastMissingMaps;
  @Nullable
  private RoutingInfo mCachedRoutingInfo;
  @Nullable
  private TransitRouteInfo mCachedTransitRouteInfo;

  @NonNull
  private volatile RouteGeometry mRouteGeometry = RouteGeometry.EMPTY;

  @NonNull
  private final List<RouteChangedListener> mRouteChangedListeners = new CopyOnWriteArrayList<>();

  private int mInvalidRoutePointsTransactionId;
  private int mRemovingIntermediatePointsTransactionId;

  @SuppressWarnings("FieldCanBeLocal")
  private final RoutingListener mRoutingListener = new RoutingListener() {
    @MainThread
    @Override
    public void onRoutingEvent(final int resultCode, @Nullable final String[] missingMaps)
    {
      if (mExternalTransit) return;
      Logger.d(TAG, "onRoutingEvent(resultCode: " + resultCode + ")");
      mLastResultCode = resultCode;
      mLastMissingMaps = missingMaps;
      mContainsCachedResult = true;

      // Refresh navigation controller to update intermediate stops
      // of progress bar in navigation panel.
      if (mContainer != null)
        mContainer.refreshNavigationController();

      if (mLastResultCode == ResultCodes.NO_ERROR || resultCode == ResultCodes.NEED_MORE_MAPS)
      {
        onBuiltRoute();
      }
      else if (mLastResultCode == ResultCodes.HAS_WARNINGS)
      {
        onBuiltRoute();
        if (mContainer != null)
          mContainer.onDrivingOptionsWarning();
      }

      processRoutingEvent();
    }

    @MainThread
    @Override
    public void onTurnPreviewChanged(boolean isPreviewing, int segmentIndex)
    {
      UiThread.run(() -> {
        mIsPreviewingTurn = isPreviewing;
        mPreviewedTurnSegmentIndex = segmentIndex;
        if (mContainer != null)
          mContainer.onTurnPreviewChanged(mIsPreviewingTurn, mPreviewedTurnSegmentIndex);
      });
    }
  };

  private void onBuiltRoute()
  {
    mCachedRoutingInfo = Framework.nativeGetRouteFollowingInfo();
    if (mLastRouterType == Router.Transit)
      mCachedTransitRouteInfo = Framework.nativeGetTransitRouteInfo();
    updateRouteGeometry();
    setBuildState(BuildState.BUILT);
    mLastBuildProgress = 100;
    if (mContainer != null)
      mContainer.onBuiltRoute();
  }

  private void updateRouteGeometry()
  {
    mRouteGeometry = RouteGeometry.from(mRouteGeometry.mRevision + 1,
                                        Framework.nativeGetRouteJunctionPoints(NO_JUNCTION_INTERPOLATION),
                                        Framework.nativeGetRoutePoints());
    notifyRouteChanged();
  }

  private void notifyRouteChanged()
  {
    for (final RouteChangedListener listener : mRouteChangedListeners)
      listener.onRouteChanged();
  }

  @NonNull
  public RouteGeometry getRouteGeometry()
  {
    return mRouteGeometry;
  }

  public void addRouteChangedListener(@NonNull RouteChangedListener listener)
  {
    mRouteChangedListeners.add(listener);
  }

  public void removeRouteChangedListener(@NonNull RouteChangedListener listener)
  {
    mRouteChangedListeners.remove(listener);
  }

  public interface RouteChangedListener
  {
    void onRouteChanged();
  }

  private final RoutingProgressListener mRoutingProgressListener = progress ->
  {
    mLastBuildProgress = (int) progress;
    updateProgress();
  };

  private final RoutingLoadPointsListener mRoutingLoadPointsListener = success ->
  {
    if (success)
      prepare(getStartPoint(), getEndPoint());
  };

  public static RoutingController get()
  {
    return sInstance;
  }

  private void processRoutingEvent()
  {
    if (!mContainsCachedResult || mContainer == null || mHasContainerSavedState)
      return;

    mContainsCachedResult = false;

    if (isDrivingOptionsBuildError())
      mContainer.onDrivingOptionsWarning();

    if (mLastResultCode == ResultCodes.NO_ERROR || mLastResultCode == ResultCodes.HAS_WARNINGS)
    {
      updatePlan();
      return;
    }

    if (mLastResultCode == ResultCodes.CANCELLED)
    {
      setBuildState(BuildState.NONE);
      updatePlan();
      return;
    }

    if (mLastResultCode != ResultCodes.NEED_MORE_MAPS)
    {
      setBuildState(BuildState.ERROR);
      mLastBuildProgress = 0;
      updateProgress();
    }

    if (isDrivingOptionsBuildError())
      mContainer.onDrivingOptionsBuildError();
    else
      mContainer.onCommonBuildError(mLastResultCode, mLastMissingMaps);
  }

  private boolean isDrivingOptionsBuildError()
  {
    return !mExternalTransit && mLastResultCode != ResultCodes.NEED_MORE_MAPS && !isRulerRouterType() && RoutingOptions.hasAnyOptions(mLastRouterType);
  }

  private void setState(State newState)
  {
    Logger.d(TAG, "[S] State: " + mState + " -> " + newState + ", BuildState: " + mBuildState);
    mState = newState;

    if (mContainer != null)
      mContainer.updateMenu();
  }

  private void setBuildState(BuildState newState)
  {
    Logger.d(TAG, "[B] State: " + mState + ", BuildState: " + mBuildState + " -> " + newState);
    mBuildState = newState;

    final MapObject startPoint = getStartPoint();
    if (mBuildState == BuildState.BUILT && (startPoint == null || !startPoint.isMyPosition()))
      Framework.nativeDisableFollowing();

    if (mContainer != null)
      mContainer.updateMenu();
  }

  private void updateProgress()
  {
    if (mContainer != null)
      mContainer.updateBuildProgress(mLastBuildProgress, mLastRouterType);
  }

  private void showRoutePlan()
  {
    showRoutePlan(null, null);
  }

  private void showRoutePlan(final @Nullable MapObject startPoint, final @Nullable MapObject endPoint)
  {
    if (mContainer != null)
    {
      mContainer.showRoutePlan(true, () -> {
        if (startPoint == null || endPoint == null)
          updatePlan();
        else
          build();
      });
    }
  }

  public void attach(@NonNull Container container)
  {
    mContainer = container;
    setTransitVisible(true);
    mContainer.onTurnPreviewChanged(mIsPreviewingTurn, mPreviewedTurnSegmentIndex);
    // A stop picked in the search (a screen of its own) is added while no screen is attached: tell the map now.
    if (mAddedStopUnseen)
    {
      mAddedStopUnseen = false;
      mContainer.onAddedStop();
    }
  }

  public boolean isPreviewingTurn()
  {
    return mIsPreviewingTurn;
  }

  public void initialize(@NonNull LocationHelper locationHelper)
  {
    mLocationHelper = locationHelper;
    mLastRouterType = Router.getLastUsed();
    mInvalidRoutePointsTransactionId = Framework.nativeInvalidRoutePointsTransactionId();
    mRemovingIntermediatePointsTransactionId = mInvalidRoutePointsTransactionId;

    Framework.nativeSetRoutingListener(mRoutingListener);
    Framework.nativeSetRouteProgressListener(mRoutingProgressListener);
    Framework.nativeSetRoutingRecommendationListener(recommendation -> UiThread.run(() -> {
      if (recommendation == RouteRecommendationType.RebuildAfterPointsLoading)
        setStartPoint(locationHelper.getMyPosition());
    }));
    Framework.nativeSetRoutingLoadPointsListener(mRoutingLoadPointsListener);
  }

  public void detach()
  {
    setTransitVisible(false);
    mContainer = null;
  }

  @MainThread
  public void restore()
  {
    mHasContainerSavedState = false;
    if (isPlanning())
      showRoutePlan();

    if (mContainer != null)
    {
      mContainer.showNavigation(isNavigating());
      mContainer.updateMenu();
    }
    processRoutingEvent();
  }

  public void onSaveState()
  {
    mHasContainerSavedState = true;
  }

  /** App-supplied transit engine; other modes and unsupported coordinates use core routing. */
  @Nullable
  public TransitProvider getTransitProvider()
  {
    return mTransitProvider;
  }

  public interface TransitProvider
  {
    boolean supports(MapObject start, MapObject finish);
    void build(MapObject start, MapObject finish, java.util.function.Consumer<TransitResult> callback);
    void cancel();
    default void setVisible(boolean visible) {}
    /** Whether {@link #setStops} is honoured: a journey through stops where the rider spends time. */
    default boolean supportsStops() { return false; }
    /** The route's intermediate stops, in order, for the next {@link #build}. */
    default void setStops(java.util.List<RouteMarkData> stops) {}
    /** Background estimate for the mode chips: {first departure, arrival} in epoch ms, or {@code null}. */
    default void estimate(MapObject start, MapObject finish, java.util.function.Consumer<long[]> done)
    {
      done.accept(null);
    }
  }

  public static final class TransitResult
  {
    public enum Kind { PENDING, TERMINAL, ROUTE, REFRESH }
    public Kind kind;
    public boolean fit = true;
    public String[] missingMaps = new String[0];
    public int[] colors = new int[0];
    public double[] vehicleCoordinates = new double[0];
    public String[] vehicleNumbers = new String[0];
    public String[] vehicleTitles = new String[0];
    public int[] vehicleColors = new int[0];
    public long vehicleExpires;
    public final TransitRouteInfo info;
    public final double[][] lines;
    public final boolean[] walking;
    public final String message;
    public final String actionLabel;
    public final Runnable action;
    public TransitResult(String message, String actionLabel, Runnable action)
    { this.kind = action == null ? Kind.PENDING : Kind.TERMINAL;
      this.info = null; this.lines = null; this.walking = null;
      this.message = message; this.actionLabel = actionLabel; this.action = action; }
    public TransitResult(TransitRouteInfo info, double[][] lines, boolean[] walking)
    { this.kind = Kind.ROUTE; this.info = info; this.lines = lines; this.walking = walking;
      this.message = null; this.actionLabel = null; this.action = null; }
  }

  private TransitProvider mTransitProvider;
  private double[][] mTransitLines;
  private boolean[] mTransitWalking;
  private int[] mTransitColors;
  private int mTransitFitRevision;
  /** Increments whenever a newly planned transport journey asks for the camera to frame it. */
  public int getTransitFitRevision() { return mTransitFitRevision; }
  /** Frames the current transport journey again, e.g. after the visible map area changed. */
  public void refitTransit()
  {
    if (mExternalTransit && mTransitLines != null)
      Framework.nativeDrawColoredRigaJourney(mTransitLines, mTransitWalking, mTransitColors, true);
  }
  /**
   * Draws the current transport journey again where it is, e.g. after the map style changed between day and
   * night: its colours are chosen for the style at draw time.
   */
  public void redrawTransit()
  {
    if (mExternalTransit && mTransitLines != null)
      Framework.nativeDrawColoredRigaJourney(mTransitLines, mTransitWalking, mTransitColors, false);
  }
  private TransitResult mTransitStatus;
  public TransitResult getTransitStatus() { return mTransitStatus; }
  public boolean isRigaTransit()
  {
    MapObject from = getStartPoint(), to = getEndPoint();
    return mLastRouterType == Router.Transit && mTransitProvider != null && from != null && to != null
        && mTransitProvider.supports(from,to);
  }
  private void transitTerminal(String message, String label, Runnable action)
  {
    mTransitStatus = new TransitResult(message,label,action);
    mTransitStatus.kind = TransitResult.Kind.TERMINAL;
    setBuildState(BuildState.ERROR);
    mLastBuildProgress = 0;
    updateProgress();
  }
  private boolean mExternalTransit;
  private boolean mTransitVisible;
  private final Runnable mClearTransitVehicles = () -> Framework.nativeSetJourneyVehicles(
      new double[0],new String[0],new int[0],new String[0]);
  private void clearTransitVehicles()
  {
    UiThread.cancelDelayedTasks(mClearTransitVehicles);
    mClearTransitVehicles.run();
  }
  private int mTransitGeneration;
  public void setTransitVisible(boolean visible)
  {
    mTransitVisible=visible;
    if (!visible && mExternalTransit) clearTransitVehicles();
    if (mTransitProvider != null) mTransitProvider.setVisible(visible);
  }
  public void setTransitProvider(TransitProvider provider) { mTransitProvider = provider; }

  private void cancelTransit()
  {
    ++mTransitGeneration;
    mTransitStatus = null;
    if (mTransitProvider != null) mTransitProvider.cancel();
    // The journey is no longer drawn: nothing to draw again when the style changes.
    mTransitLines = null;
    mTransitWalking = null;
    mTransitColors = null;
    if (mExternalTransit)
    {
      Framework.nativeSetRigaJourney(new double[0][], new boolean[0]);
      clearTransitVehicles();
    }
    mExternalTransit = false;
  }

  private void build()
  {
    cancelTransit();
    Framework.nativeRemoveRoute();

    Logger.d(TAG, "build");
    mLastBuildProgress = 0;

    setBuildState(BuildState.BUILDING);
    if (mContainer != null)
      mContainer.onStartRouteBuilding();

    updatePlan();

    MapObject from = getStartPoint();
    MapObject to = getEndPoint();
    if (mLastRouterType == Router.Transit && mTransitProvider != null && from != null && to != null
        && (from.isMyPosition() || to.isMyPosition()))
    {
      MapObject position = mLocationHelper == null ? null : mLocationHelper.getMyPosition();
      if (position != null)
      {
        if (from.isMyPosition()) from = position;
        if (to.isMyPosition()) to = position;
        setPointsInternal(from,to);
      }
    }
    if (mLastRouterType == Router.Transit && mTransitProvider != null && from != null && to != null
        && mTransitProvider.supports(from, to))
    {
      mExternalTransit = true;
      Framework.nativeCancelRouteBuild();
      RouteMarkData[] routePoints = Framework.nativeGetRoutePoints();
      java.util.List<RouteMarkData> stops = new java.util.ArrayList<>();
      for (RouteMarkData point : routePoints)
        if (point.mPointType == RouteMarkType.Intermediate) stops.add(point);
      stops.sort(java.util.Comparator.comparingInt(point -> point.mIntermediateIndex));
      if (!stops.isEmpty() && !mTransitProvider.supportsStops())
      {
        transitTerminal("Public transport routes can't include stops.", "Remove stops", () -> {
          Framework.nativeRemoveIntermediateRoutePoints(); checkAndBuildRoute();
        });
        return;
      }
      mTransitProvider.setStops(stops);
      if (from.isMyPosition() || to.isMyPosition())
      {
        MapObject position = mLocationHelper == null ? null : mLocationHelper.getMyPosition();
        if (position == null)
        {
          transitTerminal("Waiting for your location…", "Try again", this::checkAndBuildRoute);
          return;
        }
        if (from.isMyPosition()) from = position;
        if (to.isMyPosition()) to = position;
        setPointsInternal(from,to);
        if (!mTransitProvider.supports(from,to))
        {
          mExternalTransit=false;Framework.nativeBuildRoute();return;
        }
      }
      if (from.getLat() == to.getLat() && from.getLon() == to.getLon())
      {
        transitTerminal("Choose different start and destination points.", "Manage route", this::checkAndBuildRoute);
        return;
      }
      final int generation = mTransitGeneration;
      mTransitProvider.build(from, to, result -> UiThread.run(() -> {
        if (generation != mTransitGeneration || !mExternalTransit) return;
        if (result != null && result.info == null)
        {
          clearTransitVehicles();
          mTransitStatus = result;
          if (result.kind == TransitResult.Kind.TERMINAL)
          {
            setBuildState(BuildState.ERROR);
            mLastBuildProgress = 0;
          }
          updateProgress();
          if (result.missingMaps.length > 0)
          {
            mLastResultCode=ResultCodes.NEED_MORE_MAPS;mLastMissingMaps=result.missingMaps;
            mContainsCachedResult=true;processRoutingEvent();
          }
          return;
        }
        mTransitStatus = null;
        mLastResultCode = result == null ? ResultCodes.ROUTE_NOT_FOUND : ResultCodes.NO_ERROR;
        mLastMissingMaps = null;
        mContainsCachedResult = true;
        if (result != null)
        {
          mCachedRoutingInfo = null;
          mCachedTransitRouteInfo = result.info;
          Framework.nativeDrawColoredRigaJourney(result.lines, result.walking, result.colors,
              result.kind != TransitResult.Kind.REFRESH && result.fit);
          mTransitLines = result.lines; mTransitWalking = result.walking; mTransitColors = result.colors;
          if (result.kind != TransitResult.Kind.REFRESH && result.fit) ++mTransitFitRevision;
          clearTransitVehicles();
          long remaining = result.vehicleExpires - System.currentTimeMillis();
          if (remaining > 0 && mContainer != null && mTransitVisible)
          {
            Framework.nativeSetJourneyVehicles(result.vehicleCoordinates,result.vehicleNumbers,
                result.vehicleColors,result.vehicleTitles);
            UiThread.runLater(mClearTransitVehicles,remaining);
          }
          java.util.List<JunctionInfo> points = new java.util.ArrayList<>();
          for (double[] line : result.lines)
            for (int i = 0; i + 1 < line.length; i += 2) points.add(new JunctionInfo(line[i], line[i + 1]));
          mRouteGeometry = RouteGeometry.from(mRouteGeometry.mRevision + 1,
              points.toArray(new JunctionInfo[0]), Framework.nativeGetRoutePoints());
          notifyRouteChanged();
          if (result.kind != TransitResult.Kind.REFRESH)
          {
            setBuildState(BuildState.BUILT);
            mLastBuildProgress = 100;
            if (mContainer != null) mContainer.onBuiltRoute();
          }
        }
        processRoutingEvent();
      }));
      return;
    }
    Framework.nativeBuildRoute();
  }

  public void restoreRoute()
  {
    Framework.nativeLoadRoutePoints();
  }

  public boolean hasSavedRoute()
  {
    return Framework.nativeHasSavedRoutePoints();
  }

  public void saveRoute()
  {
    if (isNavigating() || (isPlanning() && isBuilt()))
      Framework.nativeSaveRoutePoints();
  }

  public void deleteSavedRoute()
  {
    Framework.nativeDeleteSavedRoutePoints();
  }

  public void rebuildLastRoute()
  {
    setState(State.NONE);
    setBuildState(BuildState.NONE);
    prepare(getStartPoint(), getEndPoint());
  }

  public void prepare(@Nullable MapObject startPoint, @Nullable MapObject endPoint)
  {
    Logger.d(TAG, "prepare (" + (endPoint == null ? "route)" : "p2p)"));
    initLastRouteType(startPoint, endPoint);
    prepare(startPoint, endPoint, mLastRouterType);
  }

  private void initLastRouteType(@Nullable MapObject startPoint, @Nullable MapObject endPoint)
  {
    if (startPoint != null && endPoint != null)
      mLastRouterType = Router.getBest(startPoint.getLat(), startPoint.getLon(), endPoint.getLat(), endPoint.getLon());
  }

  public void prepare(final @Nullable MapObject startPoint, final @Nullable MapObject endPoint, Router routerType)
  {
    cancel();
    setState(State.PREPARE);

    mLastRouterType = routerType;
    Router.set(mLastRouterType);

    if (startPoint != null || endPoint != null)
      setPointsInternal(startPoint, endPoint);

    startPlanning(startPoint, endPoint);
  }

  public void start()
  {
    Logger.d(TAG, "start");

    // This saving is needed just for situation when the user starts navigation
    // and then app crashes. So, the previous route will be restored on the next app launch.
    saveRoute();

    setState(State.NAVIGATION);

    cancelPlanning(false);
    startNavigation();

    Framework.nativeFollowRoute();
  }

  @Nullable
  private MapObject mLastAddedStop;
  /** A stop was added while no screen was attached to hear of it. */
  private boolean mAddedStopUnseen;

  /** The stop added last, for telling the rider how long the plan stays there. */
  @Nullable
  public MapObject getLastAddedStop()
  {
    return mLastAddedStop;
  }

  public void addStop(@NonNull MapObject mapObject)
  {
    mLastAddedStop = mapObject;
    addRoutePoint(RouteMarkType.Intermediate, mapObject);
    build();
    mAddedStopUnseen = mContainer == null;
    if (mContainer != null)
      mContainer.onAddedStop();
    resetToPlanningStateIfNavigating();
  }

  public void removeStop(@NonNull MapObject mapObject)
  {
    RoutePointInfo info = mapObject.getRoutePointInfo();
    if (info == null)
      throw new AssertionError("A stop point must have the route point info!");
    removeRoutePoint(info.mMarkType, info.mIntermediateIndex);
  }

  /** Takes the route's stop (intermediate point) {@code index} out and plans again. */
  public void removeStop(int index)
  {
    removeRoutePoint(RouteMarkType.Intermediate, index);
  }

  private void removeRoutePoint(@NonNull RouteMarkType type, int index)
  {
    applyRemovingIntermediatePointsTransaction();
    Framework.nativeRemoveRoutePoint(type, index);
    build();
    if (mContainer != null)
      mContainer.onRemovedStop();
    resetToPlanningStateIfNavigating();
  }

  public void launchPlanning()
  {
    build();
    setState(State.PREPARE);
    startPlanning();
    if (mContainer != null)
      mContainer.updateMenu();
    if (mContainer != null)
      mContainer.onResetToPlanningState();
  }

  /**
   * @return False if not navigating, true otherwise
   */
  public boolean resetToPlanningStateIfNavigating()
  {
    if (isNavigating())
    {
      build();
      setState(State.PREPARE);
      cancelNavigation(false);
      startPlanning();
      if (mContainer != null)
        mContainer.updateMenu();
      if (mContainer != null)
        mContainer.onResetToPlanningState();
      return true;
    }
    return false;
  }

  @NonNull
  private MapObject toMapObject(@NonNull RouteMarkData point)
  {
    return MapObject.createMapObject(FeatureId.EMPTY, point.mIsMyPosition ? MapObject.MY_POSITION : MapObject.POI,
                                     point.mTitle == null ? "" : point.mTitle,
                                     point.mSubtitle == null ? "" : point.mSubtitle, point.mLat, point.mLon);
  }

  public boolean isStopPointAllowed()
  {
    // A public transport plan has no native route (it is closed before the provider plans), so the native check
    // would refuse stops that the provider plans with; within the same native limit, stops are welcome.
    if (isTransitPlanWithStops())
      return Framework.nativeGetRoutePoints().length < MAX_ROUTE_POINTS;
    return Framework.nativeCouldAddIntermediatePoint();
  }

  /** RoutePointsLayout::kMaxIntermediatePointsCount + start and finish. */
  private static final int MAX_ROUTE_POINTS = 102;

  /** A public transport plan by a provider that plans through stops (multi-stop journeys). */
  public boolean isTransitPlanWithStops()
  {
    return mExternalTransit && mTransitProvider != null && mTransitProvider.supportsStops() && isPlanning();
  }

  public boolean isRoutePoint(@NonNull MapObject mapObject)
  {
    return mapObject.getRoutePointInfo() != null;
  }

  private void updatePlan()
  {
    updateProgress();
  }

  private void cancelInternal()
  {
    Logger.d(TAG, "cancelInternal");
    cancelTransit();

    mWaitingPoiPickType = null;

    setBuildState(BuildState.NONE);
    setState(State.NONE);

    applyRemovingIntermediatePointsTransaction();
    Framework.nativeDeleteSavedRoutePoints();
    Framework.nativeCloseRouting();

    mRouteGeometry = RouteGeometry.empty(mRouteGeometry.mRevision + 1);
    notifyRouteChanged();
  }

  public boolean cancel()
  {
    if (isPlanning())
    {
      Logger.d(TAG, "cancel: planning");

      cancelInternal();
      cancelPlanning(true);
      return true;
    }

    if (isNavigating())
    {
      Logger.d(TAG, "cancel: navigating");

      cancelInternal();
      cancelNavigation(true);
      if (mContainer != null)
      {
        mContainer.updateMenu();
      }
      return true;
    }

    Logger.d(TAG, "cancel: none");
    return false;
  }

  private void startPlanning()
  {
    if (mContainer != null)
    {
      showRoutePlan();
    }
  }

  private void startPlanning(final @Nullable MapObject startPoint, final @Nullable MapObject endPoint)
  {
    if (mContainer != null)
    {
      showRoutePlan(startPoint, endPoint);
      mContainer.onPlanningStarted();
    }
  }

  private void cancelPlanning(boolean fireEvent)
  {
    if (mContainer != null)
    {
      mContainer.showRoutePlan(false, null);
      if (fireEvent)
        mContainer.onPlanningCancelled();
    }
  }

  private void startNavigation()
  {
    if (mContainer != null)
    {
      mContainer.showNavigation(true);
      mContainer.onNavigationStarted();
    }
  }

  private void cancelNavigation(boolean fireEvent)
  {
    if (mContainer != null)
    {
      mContainer.showNavigation(false);
      if (fireEvent)
        mContainer.onNavigationCancelled();
    }
  }

  public boolean isPlanning()
  {
    return mState == State.PREPARE;
  }

  public boolean isTransitType()
  {
    return mLastRouterType == Router.Transit;
  }

  public boolean isVehicleRouterType()
  {
    return mLastRouterType == Router.Vehicle;
  }

  public boolean isRulerRouterType()
  {
    return mLastRouterType == Router.Ruler;
  }

  public boolean isNavigating()
  {
    return mState == State.NAVIGATION;
  }

  public boolean isVehicleNavigation()
  {
    return isNavigating() && isVehicleRouterType();
  }

  public boolean isBuilding()
  {
    return mState == State.PREPARE && mBuildState == BuildState.BUILDING;
  }

  public boolean isErrorEncountered()
  {
    return mBuildState == BuildState.ERROR;
  }

  public boolean isBuilt()
  {
    return mBuildState == BuildState.BUILT;
  }

  public void waitForPoiPick(@NonNull RouteMarkType pointType)
  {
    mWaitingPoiPickType = pointType;
  }

  public boolean isWaitingPoiPick()
  {
    return mWaitingPoiPickType != null;
  }

  /** Which route point a search or map tap is picking now, or null when none is. */
  @Nullable
  public RouteMarkType getWaitingPoiPickType()
  {
    return mWaitingPoiPickType;
  }

  public BuildState getBuildState()
  {
    return mBuildState;
  }

  @Nullable
  public MapObject getStartPoint()
  {
    return getStartOrEndPointByType(RouteMarkType.Start);
  }

  @Nullable
  public MapObject getEndPoint()
  {
    return getStartOrEndPointByType(RouteMarkType.Finish);
  }

  @Nullable
  private MapObject getStartOrEndPointByType(@NonNull RouteMarkType type)
  {
    RouteMarkData[] points = Framework.nativeGetRoutePoints();
    int size = points.length;

    if (size == 0)
      return null;

    if (size == 1)
    {
      RouteMarkData point = points[0];
      return point.mPointType == type ? toMapObject(point) : null;
    }

    if (type == RouteMarkType.Start)
      return toMapObject(points[0]);
    if (type == RouteMarkType.Finish)
      return toMapObject(points[size - 1]);

    return null;
  }

  @Nullable
  public RoutingInfo getCachedRoutingInfo()
  {
    return mCachedRoutingInfo;
  }

  @Nullable
  public TransitRouteInfo getCachedTransitInfo()
  {
    return mCachedTransitRouteInfo;
  }

  private void setPointsInternal(@Nullable MapObject startPoint, @Nullable MapObject endPoint)
  {
    final boolean hasStart = startPoint != null;
    final boolean hasEnd = endPoint != null;
    final boolean hasOnePointAtLeast = hasStart || hasEnd;

    if (hasOnePointAtLeast)
      applyRemovingIntermediatePointsTransaction();

    if (hasStart)
      addRoutePoint(RouteMarkType.Start, startPoint);

    if (hasEnd)
      addRoutePoint(RouteMarkType.Finish, endPoint);

    if (hasOnePointAtLeast && mContainer != null)
      mContainer.updateMenu();
  }

  public void checkAndBuildRoute()
  {
    if (isWaitingPoiPick())
      showRoutePlan();

    if (getStartPoint() != null && getEndPoint() != null)
      build();
  }

  /**
   * Sets starting point.
   * <ul>
   *   <li>If {@code point} matches ending one and the starting point was set &mdash; swap points.
   *   <li>The same as the currently set starting point is skipped.
   * </ul>
   * Route starts to build if both points were set.
   *
   * @return {@code true} if the point was set.
   */
  @SuppressWarnings("Duplicates")
  public boolean setStartPoint(@Nullable MapObject point)
  {
    Logger.d(TAG, "setStartPoint");
    MapObject startPoint = getStartPoint();
    MapObject endPoint = getEndPoint();
    boolean isSamePoint = MapObject.same(startPoint, point);
    if (point != null)
    {
      applyRemovingIntermediatePointsTransaction();
      addRoutePoint(RouteMarkType.Start, point);
      startPoint = getStartPoint();
    }

    if (isSamePoint)
    {
      Logger.d(TAG, "setStartPoint: skip the same starting point");
      return false;
    }

    if (point != null && point.sameAs(endPoint))
    {
      if (startPoint == null)
      {
        Logger.d(TAG, "setStartPoint: skip because starting point is empty");
        return false;
      }

      Logger.d(TAG, "setStartPoint: swap with end point");
      endPoint = startPoint;
    }

    startPoint = point;
    setPointsInternal(startPoint, endPoint);
    checkAndBuildRoute();
    return true;
  }

  /**
   * Sets ending point.
   * <ul>
   *   <li>If {@code point} is the same as starting point &mdash; swap points if ending point is set, skip otherwise.
   *   <li>Set starting point to MyPosition if it was not set before.
   * </ul>
   * Route starts to build if both points were set.
   *
   * @return {@code true} if the point was set.
   */
  @SuppressWarnings("Duplicates")
  public boolean setEndPoint(@Nullable MapObject point)
  {
    Logger.d(TAG, "setEndPoint");
    MapObject startPoint = getStartPoint();
    MapObject endPoint = getEndPoint();
    boolean isSamePoint = MapObject.same(endPoint, point);
    if (point != null)
    {
      applyRemovingIntermediatePointsTransaction();

      addRoutePoint(RouteMarkType.Finish, point);
      endPoint = getEndPoint();
    }

    if (isSamePoint)
      return false;

    if (point != null && point.sameAs(startPoint))
    {
      if (endPoint == null)
        return false;

      startPoint = endPoint;
    }

    endPoint = point;
    setPointsInternal(startPoint, endPoint);
    checkAndBuildRoute();
    return true;
  }

  private static void addRoutePoint(@NonNull RouteMarkType type, @NonNull MapObject point)
  {
    Pair<String, String> description = getDescriptionForPoint(point);
    Framework.nativeAddRoutePoint(description.first /* title */, description.second /* subtitle */, type,
                                  0 /* intermediateIndex */, point.isMyPosition(), point.getLat(), point.getLon(),
                                  // Only a new stop is placed between the others; replacing the start or the
                                  // finish (a transit build does it for "my position") keeps their order.
                                  type == RouteMarkType.Intermediate /* reorderIntermediatePoints */);
  }

  @NonNull
  private static Pair<String, String> getDescriptionForPoint(@NonNull MapObject point)
  {
    String title, subtitle = "";
    if (!TextUtils.isEmpty(point.getTitle()))
    {
      title = point.getTitle();
      subtitle = point.getSubtitle();
    }
    else
    {
      if (!TextUtils.isEmpty(point.getSubtitle()))
      {
        title = point.getSubtitle();
      }
      else if (!TextUtils.isEmpty(point.getAddress()))
      {
        title = point.getAddress();
      }
      else
      {
        title = Framework.nativeFormatLatLon(point.getLat(), point.getLon(), CoordinatesFormat.LatLonDecimal.getId());
      }
    }
    return new Pair<>(title, subtitle);
  }

  public void swapPoints()
  {
    Logger.d(TAG, "swapPoints");

    MapObject startPoint = getStartPoint();
    MapObject endPoint = getEndPoint();
    MapObject point = startPoint;
    startPoint = endPoint;
    endPoint = point;

    setPointsInternal(startPoint, endPoint);
    checkAndBuildRoute();
    if (mContainer != null)
      mContainer.updateMenu();
  }

  public void setRouterType(Router router)
  {
    Logger.d(TAG, "setRouterType: " + mLastRouterType + " -> " + router);

    // Repeating tap on Taxi icon should trigger the route building always,
    // because it may be "No internet connection, try later" case
    if (router == mLastRouterType)
      return;

    mLastRouterType = router;
    Router.set(router);

    cancelRemovingIntermediatePointsTransaction();

    if (getStartPoint() != null && getEndPoint() != null)
      build();
  }

  public Router getLastRouterType()
  {
    return mLastRouterType;
  }

  private void cancelRemovingIntermediatePointsTransaction()
  {
    if (mRemovingIntermediatePointsTransactionId == mInvalidRoutePointsTransactionId)
      return;
    Framework.nativeCancelRoutePointsTransaction(mRemovingIntermediatePointsTransactionId);
    mRemovingIntermediatePointsTransactionId = mInvalidRoutePointsTransactionId;
  }

  private void applyRemovingIntermediatePointsTransaction()
  {
    // We have to apply removing intermediate points transaction each time
    // we add/remove route points in the taxi mode.
    if (mRemovingIntermediatePointsTransactionId == mInvalidRoutePointsTransactionId)
      return;
    Framework.nativeApplyRoutePointsTransaction(mRemovingIntermediatePointsTransactionId);
    mRemovingIntermediatePointsTransactionId = mInvalidRoutePointsTransactionId;
  }

  public void onPoiSelected(@Nullable MapObject point)
  {
    if (!isWaitingPoiPick())
      return;

    if (mWaitingPoiPickType == RouteMarkType.Intermediate)
    {
      mWaitingPoiPickType = null;
      if (point != null)
        addStop(point);
      if (mContainer != null)
      {
        mContainer.updateMenu();
        showRoutePlan();
      }
      return;
    }

    if (point != null)
    {
      if (mWaitingPoiPickType == RouteMarkType.Finish)
        setEndPoint(point);
      else
        setStartPoint(point);
    }

    if (mContainer != null)
    {
      mContainer.updateMenu();
      showRoutePlan();
    }

    mWaitingPoiPickType = null;
  }

  public void updateCachedRoutingInfo(@Nullable RoutingInfo info)
  {
    mCachedRoutingInfo = info;
  }
}
