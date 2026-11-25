package app.organicmaps.transit;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import app.organicmaps.sdk.routing.TransitStepInfo;
import app.organicmaps.sdk.routing.TransitStepType;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Main-map transport provider. Owns only Riga calculations; route points remain in RoutingController. */
public final class RigaRouteProvider implements RoutingController.TransitProvider
{
  private final Context mContext;
  private final RigaTransitStore mStore;
  private final RigaJourneyService mService;
  private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private volatile boolean mLive;
  private boolean mVisible = true;
  private boolean mRebuildWhenVisible;
  private Runnable mRefresh;
  private RoutingController.TransitResult mLastResult;
  private static final long RELEASE_GRAPH_DELAY = 120000;
  private final Runnable mReleaseGraph = this::releaseGraphLater;
  private void releaseGraphLater()
  {
    mWorker.execute(() -> {
      mService.releaseWalkingGraph();
      mWarm = false;
    });
  }
  private volatile boolean mWarm;
  private volatile boolean mWarming;
  private volatile boolean mWarmingGraph;
  private volatile boolean mDataWarm;
  /** Starts timetable and pedestrian graph loading as soon as a Riga transit route is likely. */
  public void prewarm(MapObject near)
  {
    mHandler.removeCallbacks(mReleaseGraph);
    boolean graph = near != null;
    // A startup data prewarm in flight must not swallow the graph prewarm for chosen points:
    // the single worker runs them in order, and the second one finds the timetable ready.
    if ((graph ? mWarm || mWarmingGraph : mDataWarm || mWarming) || !mStore.exists()) return;
    mWarming = true;
    if (graph) mWarmingGraph = true;
    double lat = near == null ? Double.NaN : near.getLat(), lon = near == null ? Double.NaN : near.getLon();
    mWorker.execute(() -> {
      long started = android.os.SystemClock.elapsedRealtime();
      int priority = android.os.Process.getThreadPriority(android.os.Process.myTid());
      // Startup guesses yield to the UI; a prewarm for points the user just chose does not.
      if (!graph) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
      try
      {
        mService.prewarm(lat, lon, () -> false);
        mDataWarm = true;
        if (graph) mWarm = true;
        app.organicmaps.sdk.util.log.Logger.i("RigaRouteProvider", "Prewarmed in "
            + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
      }
      catch (Exception e) { app.organicmaps.sdk.util.log.Logger.w("RigaRouteProvider", "Prewarm failed", e); }
      finally
      {
        android.os.Process.setThreadPriority(priority);
        mWarming = false;
        if (graph) mWarmingGraph = false;
        mHandler.postDelayed(mReleaseGraph, RELEASE_GRAPH_DELAY);
      }
    });
  }
  /** Coming back to a "leave now" plan that already left, or after a long absence, needs a new plan. */
  static boolean outdatedOnReturn(long now, long hiddenAt, long firstDeparture, boolean leaveNow, boolean trip)
  {
    if (trip || !leaveNow) return false;
    return now > firstDeparture || (hiddenAt > 0 && now - hiddenAt > STALE_AFTER_HIDDEN);
  }
  static final long STALE_AFTER_HIDDEN = 5 * 60000L;
  private long mHiddenAt;
  @Override public void setVisible(boolean visible)
  {
    mVisible = visible;
    long now = System.currentTimeMillis();
    if (!visible) mHiddenAt = now;
    boolean live = RigaLiveSettings.live(mContext);
    boolean outdated = visible && mLastResult != null && mLastResult.info != null
        && outdatedOnReturn(now, mHiddenAt, mLastResult.info.firstDeparture,
                            RigaTimeChoice.mode() == RigaTimeChoice.Mode.NOW, mTrip != null);
    if (visible) mHiddenAt = 0;
    if (visible && (live != mLive || mRebuildWhenVisible || outdated))
    {
      mRebuildWhenVisible=false;
      mLive=live;RoutingController.get().checkAndBuildRoute();return;
    }
    if (mRefresh != null)
    {
      mHandler.removeCallbacks(mRefresh);
      if (visible) mHandler.post(mRefresh);
    }
  }
  private final AtomicInteger mGeneration = new AtomicInteger();
  public RigaRouteProvider(Context context)
  {
    mContext = context.getApplicationContext();
    mLive = RigaLiveSettings.live(mContext);
    mStore = new RigaTransitStore(mContext);
    mService = new RigaJourneyService(new RigaJourneyDatabase(mStore), RigaTransitNetwork::vehicles);
    mService.setStopDepartures(RigaStopDepartures::download);
    RigaJourneyMatcher.sTrace = text -> app.organicmaps.sdk.util.log.Logger.i("RigaMatch", text);
    // Guess likely use: someone who planned a Riga journey recently gets the timetable loaded
    // in the background shortly after launch, so the first route does not pay the cold read.
    long lastUse = RigaLiveSettings.preferences(mContext).getLong("last-route", 0);
    if (System.currentTimeMillis() - lastUse < 14L * 24 * 3600000)
      mHandler.postDelayed(() -> prewarm(null), 5000);
  }
  private final java.util.Map<String, String> mKnownVehicles = new java.util.concurrent.ConcurrentHashMap<>();

  private static boolean inside(MapObject point)
  { return TransitArea.covers(point.getLat(), point.getLon()); }
  // Main thread: the route's stops for the next build; each part between them is planned on its own.
  private List<RigaMultiStop.Via> mVias = Collections.emptyList();
  @Override public boolean supportsStops() { return true; }
  @Override public void setStops(List<app.organicmaps.sdk.routing.RouteMarkData> stops)
  {
    List<RigaMultiStop.Via> vias = new ArrayList<>();
    for (app.organicmaps.sdk.routing.RouteMarkData stop : stops)
    {
      String title = stop.mTitle == null || stop.mTitle.isEmpty()
          ? mContext.getString(app.organicmaps.R.string.riga_stay_unnamed, vias.size() + 1) : stop.mTitle;
      vias.add(new RigaMultiStop.Via(title, stop.mLat, stop.mLon, RigaStays.get(mContext, stop.mLat, stop.mLon)));
    }
    mVias = vias;
  }
  /** The route has stops now (set in any mode; {@link #mVias} only follows public transport builds). */
  private static boolean hasStops()
  {
    for (app.organicmaps.sdk.routing.RouteMarkData point : app.organicmaps.sdk.Framework.nativeGetRoutePoints())
      if (point.mPointType == app.organicmaps.sdk.routing.RouteMarkType.Intermediate) return true;
    return false;
  }
  @Override public boolean supports(MapObject start, MapObject finish)
  {
    boolean supported = inside(start) && inside(finish);
    if (supported) prewarm(start);
    return supported;
  }
  /**
   * Approximate arrival of the best journey for the mode chips while another mode is shown; {@code null}
   * without one. Runs on the planner's worker, so it never overlaps a shown transit plan, and does not touch
   * its state. Walks are straight-line estimates: no pedestrian graph is loaded for it.
   */
  @Override public void estimate(MapObject start, MapObject finish, Consumer<long[]> done)
  {
    if (!mStore.exists() || RigaTripService.isRunning() || !supports(start, finish) || hasStops())
    {
      done.accept(null);
      return;
    }
    double fromLat = start.getLat(), fromLon = start.getLon(), toLat = finish.getLat(), toLon = finish.getLon();
    long departure = RigaTimeChoice.searchStart(System.currentTimeMillis());
    mWorker.execute(() -> {
      long[] result = null;
      try
      {
        RigaJourneyService.Result route = mService.estimate(fromLat, fromLon, toLat, toLon, departure);
        if (route != null && route.rides())
          result = new long[] {route.startsAt(), route.endsAt()};
      }
      catch (Exception | OutOfMemoryError e)
      {
        android.util.Log.w("RigaRouteProvider", "Transit estimate failed", e);
      }
      long[] answer = result;
      mHandler.post(() -> done.accept(answer));
    });
  }

  @Override public void cancel()
  {
    stopTrip();
    mGeneration.incrementAndGet();
    if (mRefresh != null) mHandler.removeCallbacks(mRefresh);
    mRefresh = null;
    mRebuildWhenVisible=false;
    mLastResult = null;
    mSelectedKey = null;
    mRiderPicked = false;
    mWorker.execute(() -> mOffered = Collections.emptyMap());
    mAlternativeCount = RigaJourneyAlternatives.DEFAULT_COUNT;
    mHandler.removeCallbacks(mReleaseGraph);
    mHandler.postDelayed(mReleaseGraph, RELEASE_GRAPH_DELAY);
  }
  // Main thread only: the destination the time choice was made for.
  private double mChoiceLat = Double.NaN, mChoiceLon = Double.NaN;
  @Override public void build(MapObject start, MapObject finish, Consumer<RoutingController.TransitResult> callback)
  {
    stopTrip();
    // A time chosen for one destination does not carry over to another.
    if (RigaTimeChoice.claimLink(finish.getLat(), finish.getLon()))
    {
      // The time came with a route link to this destination.
      mChoiceLat = finish.getLat();
      mChoiceLon = finish.getLon();
    }
    else if (Math.abs(finish.getLat() - mChoiceLat) > 0.0005 || Math.abs(finish.getLon() - mChoiceLon) > 0.0005)
    {
      if (!Double.isNaN(mChoiceLat)) RigaTimeChoice.reset();
      mChoiceLat = finish.getLat();
      mChoiceLon = finish.getLon();
    }
    mLive = RigaLiveSettings.live(mContext);
    int generation = mGeneration.incrementAndGet();
    mAlternativeCount = RigaJourneyAlternatives.DEFAULT_COUNT;
    mSelectedKey = null;
    mRiderPicked = false;
    if (!mStore.exists())
    {
      download(start, finish, generation, callback);
      return;
    }
    if (!mVias.isEmpty()) { calculateStops(start, finish, mVias, generation, callback); return; }
    calculate(start, finish, generation, callback);
  }
  private void download(MapObject start, MapObject finish, int generation,
                        Consumer<RoutingController.TransitResult> callback)
  {
    if (generation != mGeneration.get()) return;
    callback.accept(new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_installing), null, null));
    if (!app.organicmaps.sdk.util.ConnectionState.INSTANCE.isConnected())
    {
      callback.accept(new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_connect),
          mContext.getString(app.organicmaps.R.string.riga_route_retry), () -> RoutingController.get().checkAndBuildRoute()));return;
    }
    if (app.organicmaps.sdk.util.ConnectionState.INSTANCE.isMobileConnected()
        && !app.organicmaps.sdk.downloader.MapManager.nativeIsDownloadOn3gEnabled())
    {
      callback.accept(new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_mobile),
          mContext.getString(app.organicmaps.R.string.riga_route_download), () -> {
            app.organicmaps.sdk.downloader.MapManager.nativeEnableDownloadOn3g();
            download(start,finish,generation,callback);
          }));return;
    }
    RigaTimetableInstaller.get(mContext).request(error -> {
      if (generation != mGeneration.get()) return;
      if (error == null)
      {
        if (mVisible) RoutingController.get().checkAndBuildRoute();
        else mRebuildWhenVisible=true;
      }
      else callback.accept(new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_install_failed),
          mContext.getString(app.organicmaps.R.string.riga_route_retry), () -> RoutingController.get().checkAndBuildRoute()));
    });
  }

  private void calculate(MapObject start, MapObject finish, int generation,
                         Consumer<RoutingController.TransitResult> callback)
  {
    calculate(start, finish, generation, callback, false);
  }
  private void calculate(MapObject start, MapObject finish, int generation,
                         Consumer<RoutingController.TransitResult> callback, boolean refresh)
  {
    if (generation != mGeneration.get()) return;
    if (mRefresh != null) mHandler.removeCallbacks(mRefresh);
    mRefresh = null;
    if (!refresh) callback.accept(new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_finding), null, null));
    if (!refresh) mUnframed = true;
    mHandler.removeCallbacks(mReleaseGraph);
    mWorker.execute(() -> {
      long started = android.os.SystemClock.elapsedRealtime();
      int hits = mService.walkCacheHits, misses = mService.walkCacheMisses;
      RoutingController.TransitResult result;
      boolean liveFailed = false;
      try
      {
        long departure = RigaTimeChoice.searchStart(System.currentTimeMillis());
        boolean arriveBy = RigaTimeChoice.mode() == RigaTimeChoice.Mode.ARRIVE;
        if (!refresh && !mStore.coversDate(departure))
        {
          SimpleDateFormat dayFormat=new SimpleDateFormat("yyyyMMdd",Locale.ROOT);dayFormat.setTimeZone(RigaTransitStore.ZONE);
          String day=dayFormat.format(new Date());
          if (!day.equals(RigaLiveSettings.preferences(mContext).getString("coverage-update-day","")))
          {
            RigaLiveSettings.preferences(mContext).edit().putString("coverage-update-day",day).apply();
            mHandler.post(()->download(start,finish,generation,callback));return;
          }
          RoutingController.TransitResult outdated=new RoutingController.TransitResult(
              mContext.getString(app.organicmaps.R.string.riga_route_outdated),
              mContext.getString(app.organicmaps.R.string.riga_route_update),()->download(start,finish,generation,callback));
          mHandler.post(()->{if(generation==mGeneration.get())callback.accept(outdated);});return;
        }
        RigaJourneyService.Result route = mService.calculateLocations(start.getLat(), start.getLon(),
            finish.getLat(), finish.getLon(), departure, mLive && liveUseful(),
            () -> generation != mGeneration.get());
        liveFailed = route.liveFailed;
        // Walking the whole way is one of the answers: chosen when it gets there first, offered after the rides.
        RigaJourneyService.Result walk = walkAll(start, finish, departure, arriveBy,
            () -> generation != mGeneration.get());
        boolean rides = route.rides();
        if (!rides && walk == null)
          result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_none),
              mContext.getString(app.organicmaps.R.string.riga_route_retry), () -> RoutingController.get().checkAndBuildRoute());
        else if (rides && !route.ridesDrawn())
          result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_geometry_failed),
              mContext.getString(app.organicmaps.R.string.riga_route_update), () -> download(start, finish, generation, callback));
        else
        {
          if (!refresh) RigaLiveSettings.preferences(mContext).edit().putLong("last-route", System.currentTimeMillis()).apply();
          // Arrive-by: the first journey only starts the search; it is not the answer to show.
          if (!refresh && !arriveBy)
          {
            // Show the fastest journey at once; alternatives follow without blocking it.
            RigaJourneyService.Result shown = !rides
                || (walk != null && walk.arrives() <= route.arrives()) ? walk : route;
            RoutingController.TransitResult first = decorate(present(shown, start, finish));
            first.info.alternativesPending = true;
            first.info.startTrip = () -> startTrip(shown, start, finish, generation, callback);
            String key = shown.key();
            mHandler.post(() -> {
              if (generation != mGeneration.get() || mTrip != null) return;
              mSelectedKey = key;
              mUnframed = false;
              mLastResult = first;
              callback.accept(first);
            });
          }
          mLastRoute = rides ? route : null;
          mLastWalk = walk;
          // A live refresh only re-times the choices with walks already routed; it must stay cheap.
          alternatives(mLastRoute, walk, start, finish, generation, callback, refresh, !refresh, liveFailed, started,
                       hits, misses);
          return;
        }
      }
      catch (java.util.concurrent.CancellationException e) { return; }
      catch (java.io.UncheckedIOException e)
      {
        result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_maps),null,null);
        result.kind=RoutingController.TransitResult.Kind.TERMINAL;
        result.missingMaps=new String[]{"Latvia"};
      }
      catch (java.io.IOException | android.database.sqlite.SQLiteException e)
      {
        result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_unreadable),mContext.getString(app.organicmaps.R.string.riga_route_update),
            () -> download(start,finish,generation,callback));
      }
      catch (Exception e)
      {
        app.organicmaps.sdk.util.log.Logger.e("RigaRouteProvider", "Planning failed", e);
        liveFailed = true;
        result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_failed),
            mContext.getString(app.organicmaps.R.string.riga_route_retry), () -> RoutingController.get().checkAndBuildRoute());
      }
      finished(refresh, started, hits, misses);
      publish(Collections.singletonList(result), Collections.singletonList(""), start, finish, generation, callback,
              refresh, liveFailed);
    });
  }

  // Worker thread only.
  private RigaJourneyService.Result mLastRoute;
  // Worker thread only: the whole way on foot for the last search, or null.
  private RigaJourneyService.Result mLastWalk;
  private volatile int mAlternativeCount = RigaJourneyAlternatives.DEFAULT_COUNT;
  // Main thread only: the rider's chosen journey, by its rides, kept across live refreshes.
  private volatile String mSelectedKey;
  // Main thread only: the rider picked a card; until then the first list may replace the journey shown first.
  private boolean mRiderPicked;
  // Worker thread only: the journeys last offered, by signature, to keep the rider's choice on refresh.
  private java.util.Map<String, RigaJourneyService.Result> mOffered = Collections.emptyMap();

  private void finished(boolean refresh, long started, int hits, int misses)
  {
    app.organicmaps.sdk.util.log.Logger.i("RigaRouteProvider", (refresh ? "Refreshed" : "Planned") + " in "
        + (android.os.SystemClock.elapsedRealtime() - started) + " ms; walks cached "
        + (mService.walkCacheHits - hits) + ", routed " + (mService.walkCacheMisses - misses));
    // After this search is published; the worker is serial.
    mWorker.execute(mService::saveWalks);
    mWarm = true;
    mHandler.removeCallbacks(mReleaseGraph);
    mHandler.postDelayed(mReleaseGraph, RELEASE_GRAPH_DELAY);
  }

  private RoutingController.TransitResult decorate(RoutingController.TransitResult result)
  {
    result.info.instructions += mContext.getString(app.organicmaps.R.string.riga_installed, mStore.validity());
    if (!mLive && !RigaLiveSettings.asked(mContext))
    {
      result.info.actionLabel = mContext.getString(app.organicmaps.R.string.riga_live_consent_title);
      result.info.liveConsent = true;
      result.info.action = () -> RoutingController.get().checkAndBuildRoute();
    }
    return result;
  }

  /** Worker thread: finds alternatives around {@code route} and publishes the list. */
  private void alternatives(@androidx.annotation.Nullable RigaJourneyService.Result route,
                            @androidx.annotation.Nullable RigaJourneyService.Result walk, MapObject start,
                            MapObject finish, int generation,
                            Consumer<RoutingController.TransitResult> callback, boolean refresh, boolean routeWalks,
                            boolean liveFailed, long started, int hits, int misses)
  {
    List<RigaJourneyService.Result> routes;
    boolean arriveBy = RigaTimeChoice.mode() == RigaTimeChoice.Mode.ARRIVE;
    try
    {
      routes = route == null ? Collections.emptyList() : arriveBy
          ? mService.arriveBy(route, RigaTimeChoice.time(), mAlternativeCount, routeWalks,
              () -> generation != mGeneration.get())
          : mService.alternatives(route, mAlternativeCount, routeWalks, () -> generation != mGeneration.get());
    }
    catch (java.util.concurrent.CancellationException e) { return; }
    catch (Exception e)
    {
      // Alternatives are optional: the fastest journey alone is still a complete answer.
      app.organicmaps.sdk.util.log.Logger.w("RigaRouteProvider", "Alternatives failed", e);
      routes = arriveBy || route == null ? Collections.emptyList() : Collections.singletonList(route);
    }
    // A refresh may not find the chosen journey again (it plans with routed walks only, from a later
    // time): keep the same vehicles with their new times while every connection still works.
    String selectedKey = mSelectedKey;
    RigaJourneyService.Result previous = selectedKey == null ? null : mOffered.get(selectedKey);
    boolean found = false;
    for (RigaJourneyService.Result offered : routes)
      found |= offered.key().equals(selectedKey);
    if (refresh && !found && previous != null && previous.rides())
    {
      RigaJourneyService.Result kept = mService.retime(previous);
      long now = System.currentTimeMillis();
      if (!kept.missed() && kept.leaves() >= now - 60000
          && kept.firstRideLeaves() > now)
      {
        routes = new ArrayList<>(routes);
        routes.add(kept);
        java.util.Comparator<RigaJourneyPlanner.Journey> order = arriveBy ? RigaJourneyAlternatives.ARRIVE_ORDER
            : RigaJourneyAlternatives.ORDER;
        routes.sort((a, b) -> order.compare(a.journey, b.journey));
      }
    }
    routes = RigaWalkChoice.merge(routes, walk, RigaJourneyService.Result::leaves,
                                  RigaJourneyService.Result::arrives, arriveBy);
    if (routes.isEmpty())
    {
      finished(refresh, started, hits, misses);
      RoutingController.TransitResult none = new RoutingController.TransitResult(
          mContext.getString(app.organicmaps.R.string.riga_time_none,
              RigaTimeChoice.when(mContext, RigaTimeChoice.time(), System.currentTimeMillis())),
          mContext.getString(app.organicmaps.R.string.riga_time_now),
          () -> { RigaTimeChoice.reset(); RoutingController.get().checkAndBuildRoute(); });
      publish(Collections.singletonList(none), Collections.singletonList(""), start, finish, generation, callback,
          refresh, liveFailed);
      return;
    }
    java.util.Map<String, RigaJourneyService.Result> offered = new java.util.HashMap<>();
    for (RigaJourneyService.Result r : routes) offered.put(r.key(), r);
    mOffered = offered;
    finished(refresh, started, hits, misses);
    List<RoutingController.TransitResult> results = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    app.organicmaps.sdk.routing.TransitAlternative[] summaries = new app.organicmaps.sdk.routing.TransitAlternative[routes.size()];
    for (int i = 0; i < routes.size(); ++i)
    {
      RigaJourneyService.Result alternative = routes.get(i);
      results.add(decorate(present(alternative, start, finish)));
      keys.add(alternative.key());
      summaries[i] = summary(alternative);
    }
    for (int i = 0; i < results.size(); ++i)
    {
      final int index = i;
      RoutingController.TransitResult result = results.get(i);
      result.info.alternatives = summaries;
      result.info.selectedAlternative = i;
      result.info.selectAlternative = choice -> {
        if (generation != mGeneration.get() || choice < 0 || choice >= results.size() || choice == index) return;
        mSelectedKey = keys.get(choice);
        mRiderPicked = true;
        RoutingController.TransitResult chosen = results.get(choice);
        chosen.kind = RoutingController.TransitResult.Kind.ROUTE;
        chosen.fit = true;
        mLastResult = chosen;
        callback.accept(chosen);
      };
      RigaJourneyService.Result journey = routes.get(i);
      result.info.startTrip = () -> startTrip(journey, start, finish, generation, callback);
      result.info.laterAlternatives = () -> mWorker.execute(() -> {
        if (generation != mGeneration.get() || mLastRoute == null) return;
        mAlternativeCount += 3;
        alternatives(mLastRoute, mLastWalk, start, finish, generation, callback, true, true, liveFailed,
                     android.os.SystemClock.elapsedRealtime(), mService.walkCacheHits, mService.walkCacheMisses);
      });
    }
    publish(results, keys, start, finish, generation, callback, refresh, liveFailed);
  }

  /**
   * Worker: the whole way on foot, leaving at {@code departure}, or arriving by the chosen time (leaving no earlier
   * than allowed); null when it cannot be routed.
   */
  @androidx.annotation.Nullable
  private RigaJourneyService.Result walkAll(MapObject start, MapObject finish, long departure, boolean arriveBy,
                                            java.util.function.BooleanSupplier cancelled)
  {
    try
    {
      RigaJourneyService.Result walk = mService.walkOnly(start.getLat(), start.getLon(), finish.getLat(),
          finish.getLon(), departure, cancelled);
      if (walk == null || !arriveBy) return walk;
      long leave = Math.max(RigaTimeChoice.earliest(System.currentTimeMillis()),
          RigaTimeChoice.time() - walk.takes());
      return mService.walkOnly(start.getLat(), start.getLon(), finish.getLat(), finish.getLon(), leave, cancelled);
    }
    catch (java.util.concurrent.CancellationException | java.io.UncheckedIOException e) { throw e; }
    catch (Exception e)
    {
      app.organicmaps.sdk.util.log.Logger.w("RigaRouteProvider", "No walk the whole way", e);
      return null;
    }
  }

  private static app.organicmaps.sdk.routing.TransitAlternative summary(RigaJourneyService.Result route)
  {
    return summary(route, System.currentTimeMillis());
  }

  /** One alternative card: its legs as badges ("7/14 › 49", short changes on foot left out) and times. */
  static app.organicmaps.sdk.routing.TransitAlternative summary(RigaJourneyService.Result route, long now)
  {
    RigaJourneyPlanner.Journey journey = route.journey;
    RigaSameRides.Group[] groups = RigaSameRides.of(route.data, journey, now);
    List<TransitStepType> modes = new ArrayList<>();
    List<String> numbers = new ArrayList<>();
    List<Integer> colors = new ArrayList<>();
    int walk = 0;
    long walking = 0;
    long[] also = new long[0];
    boolean live = false, first = true;
    long delay = 0;
    for (int i = 0; i < journey.legs.size(); ++i)
    {
      RigaJourneyPlanner.Leg leg = journey.legs.get(i);
      if (leg.trip == null)
      {
        // Consecutive walks are one stretch on foot; a walk shorter than half a minute is not shown, nor a
        // short change between two rides (as "7 > 49", not "7 > walk 1 > 49").
        walk += leg.walkingMetres;
        walking += leg.arrival - leg.departure;
        boolean last = i + 1 == journey.legs.size() || journey.legs.get(i + 1).trip != null;
        boolean change = !first && i + 1 < journey.legs.size();
        // Rounded up, as the route panel's walk chip is, so the card and chip agree.
        long minutes = (walking + 59999) / 60000;
        if (last && (walking >= 30000 || journey.boardings == 0) && !(change && walking < RigaWalks.SHORT_CHANGE))
        {
          modes.add(TransitStepType.PEDESTRIAN);
          numbers.add(Long.toString(minutes));
          colors.add(0);
        }
        if (last) walking = 0;
        continue;
      }
      if (first)
      {
        live = leg.prediction != null;
        delay = live ? leg.prediction.delayMillis() : 0;
        first = false;
        also = groups[i].also;
      }
      modes.add(RigaModeStyle.type(leg.trip.type));
      numbers.add(RigaSameRides.badge(groups[i], leg.trip.routeName, 3));
      colors.add(RigaModeStyle.color(leg.trip.type));
    }
    int[] colorArray = new int[colors.size()];
    for (int i = 0; i < colorArray.length; ++i) colorArray[i] = colors.get(i);
    app.organicmaps.sdk.routing.TransitAlternative summary = new app.organicmaps.sdk.routing.TransitAlternative(
        route.leaves(), route.arrives(), journey.boardings, walk, live,
        modes.toArray(new TransitStepType[0]), numbers.toArray(new String[0]), colorArray);
    summary.also = also;
    summary.delay = delay;
    return summary;
  }

  /** Main thread: shows the rider's selection from {@code results}, or explains why it is gone. */
  private void publish(List<RoutingController.TransitResult> results, List<String> keys, MapObject start,
                       MapObject finish, int generation, Consumer<RoutingController.TransitResult> callback,
                       boolean refresh, boolean failedLive)
  {
    mHandler.post(() -> {
      if (generation != mGeneration.get()) return;
      RoutingController.TransitResult completed = results.get(0);
      int selected = completed.info == null ? -1 : Math.max(0, keys.indexOf(mSelectedKey));
      // The journey shown at once is only the earliest arrival; the list's first one is the recommended one
      // (e.g. no change for a minute more), so it takes over unless the rider already chose.
      if (!refresh && !mRiderPicked && completed.info != null) selected = 0;
      // Until the rider picks, a refresh follows walking: chosen when it now gets there first, left when not.
      if (!mRiderPicked && completed.info != null
          && (RigaWalkChoice.KEY.equals(keys.get(0)) || RigaWalkChoice.KEY.equals(mSelectedKey)))
        selected = 0;
      RoutingController.TransitResult published = completed.info == null ? completed : results.get(selected);
      // The chosen journey gone from a refreshed list (it left, or a connection broke): the best one of the list
      // takes its place, so there is nothing for the rider to re-plan. Only a refresh that found nothing keeps the
      // last plan, without the vehicles it can no longer place.
      // A plan whose ride has left is not kept: the refresh's own answer (no journey, retry) says what is true now.
      boolean departed = mLastResult != null && mLastResult.info.firstDeparture < System.currentTimeMillis();
      if (refresh && mLastResult != null && completed.info == null && !departed)
      {
        published = mLastResult;
        published.vehicleCoordinates = new double[0]; published.vehicleNumbers = new String[0];
        published.vehicleTitles = new String[0]; published.vehicleColors = new int[0]; published.vehicleExpires = 0;
        published.info.alternativesPending = false;
        if (failedLive) published.info.description = mContext.getString(app.organicmaps.R.string.riga_route_live_failed);
      }
      else if (published.info != null)
      {
        mLastResult = published;
        mSelectedKey = keys.get(selected);
      }
      // A started trip keeps its own display and refresh; the plan stays for when the trip ends.
      if (mTrip != null) return;
      // The first answer already framed the map; the list arriving later must not move it again.
      published.fit = false;
      if (published.info != null) published.kind = RoutingController.TransitResult.Kind.REFRESH;
      if (published.info != null && mUnframed)
      {
        // Nothing was shown before this list (arrive-by): it is the built route.
        published.kind = RoutingController.TransitResult.Kind.ROUTE;
        published.fit = true;
      }
      if (published.info != null) mUnframed = false;
      if (!refresh && published.info == null) published.fit = true;
      callback.accept(published);
      if (published.info != null) scheduleRefresh(start, finish, generation, callback);
    });
  }
  // Main thread only: no journey of this build has been shown (and framed) yet.
  private boolean mUnframed;
  // Main thread only: the started journey; its vehicles never change, only their times.
  private List<RigaJourneyService.Result> mTrip;
  /** Where the walk being followed was last routed from; a new fix inside its accuracy reuses it. */
  private volatile double[] mWalkFrom;
  /** Fixes this close (or within their own accuracy, up to {@link #WALK_JITTER_MAX}) are the same spot. */
  static final double WALK_JITTER_MIN = 20, WALK_JITTER_MAX = 60;
  // Main thread only: the stops between the started trip's parts (empty for a journey without stops).
  private List<RigaMultiStop.Via> mTripVias = Collections.emptyList();
  /** Worker: the parts of the journey with stops shown last, and the plan they belong to. */
  private List<RigaJourneyService.Result> mStopParts;
  private int mStopPartsGeneration = -1;
  private Runnable mTripRefresh;
  static final long TRIP_REFRESH = 15000;

  /** Main thread: follows {@code journey} until the rider ends the trip or plans another route. */
  private void startTrip(RigaJourneyService.Result journey, MapObject start, MapObject finish, int generation,
                         Consumer<RoutingController.TransitResult> callback)
  {
    startTrip(Collections.singletonList(journey), Collections.emptyList(), start, finish, generation, callback);
  }
  private void startTrip(List<RigaJourneyService.Result> parts, List<RigaMultiStop.Via> vias, MapObject start,
                         MapObject finish, int generation, Consumer<RoutingController.TransitResult> callback)
  {
    if (generation != mGeneration.get() || mTrip != null) return;
    if (mRefresh != null) mHandler.removeCallbacks(mRefresh);
    mRefresh = null;
    mTrip = parts;
    mWalkFrom = null;
    mTripVias = vias;
    refreshTrip(start, finish, generation, callback, false);
  }

  private void refreshTrip(MapObject start, MapObject finish, int generation,
                           Consumer<RoutingController.TransitResult> callback, boolean fetch)
  {
    List<RigaJourneyService.Result> trip = mTrip;
    List<RigaMultiStop.Via> vias = mTripVias;
    if (trip == null || generation != mGeneration.get()) return;
    boolean live = mLive;
    mWorker.execute(() -> {
      // Re-timing also moves the first walk to end when the ride leaves (the plan starts it "now").
      List<RigaJourneyService.Result> next = trip;
      try
      {
        next = mService.retime(trip, fetch && live, () -> generation != mGeneration.get());
      }
      catch (java.util.concurrent.CancellationException e) { return; }
      catch (Exception e) { app.organicmaps.sdk.util.log.Logger.w("RigaRouteProvider", "Trip refresh failed", e); }
      long now = System.currentTimeMillis();
      int active = activePart(next, now);
      // The walk the rider is on follows them: re-routed from where they are, with a new arrival.
      android.location.Location here =
          app.organicmaps.MwmApplication.from(mContext).getLocationHelper().getSavedLocation();
      RigaJourneyService.Result part = next.get(active);
      double[] from = here == null ? null : walkOrigin(mWalkFrom, here.getLatitude(), here.getLongitude(),
          here.hasAccuracy() ? here.getAccuracy() : 0);
      mWalkFrom = from;
      RigaJourneyService.Result rewalked = from == null ? part : mService.rewalk(part, from[0], from[1], now);
      boolean walking = rewalked != part;
      List<RigaJourneyService.Result> current = new ArrayList<>(next);
      current.set(active, rewalked);
      RoutingController.TransitResult result = presentParts(current, vias, start, finish);
      result.info.trip = true;
      result.info.endTrip = () -> endTrip(start, finish, generation, callback);
      result.info.replanFromHere = this::replanFromHere;
      // A late part leaves no time at the next stop: its following part cannot be made as planned.
      int late = lateStop(current, active, now);
      if (late >= 0 && !rewalked.missed())
      {
        result.info.description = mContext.getString(app.organicmaps.R.string.riga_trip_stay_late,
            vias.get(late).title) + "\n" + result.info.description;
      }
      RigaJourneyPlanner.Trip missed = rewalked.missedTrip();
      if (missed != null)
      {
        result.info.description = mContext.getString(app.organicmaps.R.string.riga_trip_connection_missed,
            RigaModeStyle.name(mContext, missed.type), missed.routeName) + "\n" + result.info.description;
      }
      mHandler.post(() -> {
        if (generation != mGeneration.get() || mTrip != trip) return;
        // Keep the pedestrian graph loaded while a walk is re-routed every refresh.
        if (walking) { mHandler.removeCallbacks(mReleaseGraph); mHandler.postDelayed(mReleaseGraph, RELEASE_GRAPH_DELAY); }
        mTrip = current;
        result.kind = RoutingController.TransitResult.Kind.REFRESH;
        result.fit = false;
        callback.accept(result);
        // Only now is the trip what the service reads; started earlier it would see no trip and stop.
        if (!fetch) RigaTripService.start(mContext);
        if (mTripRefresh != null) mHandler.removeCallbacks(mTripRefresh);
        mTripRefresh = () -> refreshTrip(start, finish, generation, callback, true);
        mHandler.postDelayed(mTripRefresh, TRIP_REFRESH);
      });
    });
  }

  /**
   * Where to route the followed walk from: the previous origin while the fix stays within its accuracy of it, so
   * position noise (indoors it wanders tens of metres) does not redraw the walk and change its length every refresh.
   */
  static double[] walkOrigin(double[] previous, double lat, double lon, double accuracy)
  {
    double radius = Math.max(WALK_JITTER_MIN, Math.min(WALK_JITTER_MAX, accuracy));
    if (previous != null && RigaJourneyDatabase.distance(lat, lon, previous[0], previous[1]) < radius)
      return previous;
    return new double[] {lat, lon};
  }

  /**
   * The first stop (index into the stops) still ahead where the part before it now arrives after the part after it
   * was to leave, or -1: the stay there is used up.
   */
  static int lateStop(List<RigaJourneyService.Result> parts, int active, long now)
  {
    for (int p = Math.max(1, active + 1); p < parts.size(); ++p)
    {
      long arrival = parts.get(p - 1).endsAt(), leave = parts.get(p).startsAt();
      if (now < leave && arrival > leave) return p - 1;
    }
    return -1;
  }

  /**
   * Worker: on a live refresh, the shown journey with stops re-timed from one live download, as a trip is:
   * planning every part again routes dozens of walks each time. Null (plan again) when live times are off or of
   * no use, there is no shown journey, its first ride has left, a connection is missed, or a part now arrives
   * after the next one leaves.
   */
  @androidx.annotation.Nullable
  private List<RigaJourneyService.Result> keptStops(int generation, boolean live,
                                                    java.util.function.BooleanSupplier cancelled) throws Exception
  {
    List<RigaJourneyService.Result> shown = mStopParts;
    long now = System.currentTimeMillis();
    if (!live || !liveUseful() || shown == null || mStopPartsGeneration != generation
        || shown.get(0).firstRideLeaves() <= now)
      return null;
    List<RigaJourneyService.Result> parts = mService.retime(shown, true, cancelled);
    for (int i = 0; i < parts.size(); ++i)
    {
      RigaJourneyService.Result part = parts.get(i);
      String broken = part.missed() ? "misses " + part.missedTrip().routeName
          : i + 1 < parts.size() && part.arrives() > parts.get(i + 1).leaves()
          ? "arrives after the next part leaves" : null;
      if (broken != null)
      {
        app.organicmaps.sdk.util.log.Logger.i("RigaRouteProvider", "Planning again: part " + i + " " + broken);
        return null;
      }
    }
    return parts;
  }

  /** The part the rider is on: the first not yet arrived (the last once all are). */
  static int activePart(List<RigaJourneyService.Result> parts, long now)
  {
    for (int i = 0; i < parts.size(); ++i)
    {
      if (now < parts.get(i).endsAt()) return i;
    }
    return parts.size() - 1;
  }

  /** One shown route from the parts of a journey with stops; a journey without stops is shown as it is. */
  private RoutingController.TransitResult presentParts(List<RigaJourneyService.Result> parts,
                                                      List<RigaMultiStop.Via> vias, MapObject start, MapObject finish)
  {
    if (parts.size() == 1) return present(parts.get(0), start, finish);
    List<RoutingController.TransitResult> shown = new ArrayList<>();
    for (int i = 0; i < parts.size(); ++i)
      shown.add(present(parts.get(i), i == 0 ? start.getTitle() : vias.get(i - 1).title,
                        i == parts.size() - 1 ? finish.getTitle() : vias.get(i).title));
    return RigaMultiStop.merge(shown, vias);
  }

  /**
   * A journey through the route's stops: each part leaves when the stay at the stop before it ends, by public
   * transport or on foot when that is quicker (or no ride serves it). No alternatives: it is one plan.
   */
  private void calculateStops(MapObject start, MapObject finish, List<RigaMultiStop.Via> vias, int generation,
                              Consumer<RoutingController.TransitResult> callback)
  {
    calculateStops(start, finish, vias, generation, callback, false);
  }
  private void calculateStops(MapObject start, MapObject finish, List<RigaMultiStop.Via> vias, int generation,
                              Consumer<RoutingController.TransitResult> callback, boolean refresh)
  {
    if (generation != mGeneration.get()) return;
    if (mRefresh != null) mHandler.removeCallbacks(mRefresh);
    mRefresh = null;
    if (!refresh)
    {
      callback.accept(new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_finding), null, null));
      mUnframed = true;
    }
    mHandler.removeCallbacks(mReleaseGraph);
    boolean live = mLive;
    mWorker.execute(() -> {
      long started = android.os.SystemClock.elapsedRealtime();
      int hits = mService.walkCacheHits, misses = mService.walkCacheMisses;
      RoutingController.TransitResult result;
      boolean liveFailed = false;
      try
      {
        long now = System.currentTimeMillis();
        long departure = RigaTimeChoice.searchStart(now);
        java.util.function.BooleanSupplier cancelled = () -> generation != mGeneration.get();
        List<RigaJourneyService.Result> parts = new ArrayList<>();
        double fromLat = start.getLat(), fromLon = start.getLon();
        RigaMultiStop.Via missing = null;
        boolean arriveBy = RigaTimeChoice.mode() == RigaTimeChoice.Mode.ARRIVE;
        List<RigaJourneyService.Result> kept = refresh ? keptStops(generation, live, cancelled) : null;
        if (kept != null)
        {
          parts = kept;
          for (RigaJourneyService.Result part : kept) liveFailed |= part.liveFailed;
        }
        else if (arriveBy)
        {
          List<RigaJourneyService.Result> backwards = backwards(start, finish, vias, RigaTimeChoice.time(), live,
                                                                !refresh, cancelled);
          if (backwards != null) for (RigaJourneyService.Result part : backwards) liveFailed |= part.liveFailed;
          if (backwards != null) parts = backwards;
        }
        for (int i = 0; kept == null && !arriveBy && i <= vias.size(); ++i)
        {
          boolean last = i == vias.size();
          double toLat = last ? finish.getLat() : vias.get(i).lat, toLon = last ? finish.getLon() : vias.get(i).lon;
          RigaJourneyService.Result part = part(fromLat, fromLon, toLat, toLon, departure,
              live && RigaTimeChoice.liveUseful(departure, System.currentTimeMillis()), cancelled);
          if (part == null) { missing = last ? null : vias.get(i); break; }
          liveFailed |= part.liveFailed;
          parts.add(part);
          if (!last)
          {
            departure = RigaMultiStop.leaveAfter(part.endsAt(), vias.get(i));
            fromLat = toLat;
            fromLon = toLon;
          }
        }
        if (parts.size() != vias.size() + 1 && arriveBy)
        {
          result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_time_none,
                  RigaTimeChoice.when(mContext, RigaTimeChoice.time(), System.currentTimeMillis())),
              mContext.getString(app.organicmaps.R.string.riga_time_now),
              () -> { RigaTimeChoice.reset(); RoutingController.get().checkAndBuildRoute(); });
        }
        else if (parts.size() != vias.size() + 1)
          result = new RoutingController.TransitResult(missing == null
                  ? mContext.getString(app.organicmaps.R.string.riga_route_none)
                  : mContext.getString(app.organicmaps.R.string.riga_stay_unreachable, missing.title),
              mContext.getString(app.organicmaps.R.string.riga_route_retry), () -> RoutingController.get().checkAndBuildRoute());
        else
        {
          if (!refresh) RigaLiveSettings.preferences(mContext).edit().putLong("last-route", System.currentTimeMillis()).apply();
          result = decorate(presentParts(parts, vias, start, finish));
          List<RigaJourneyService.Result> chosen = parts;
          mStopParts = parts;
          mStopPartsGeneration = generation;
          result.info.startTrip = () -> startTrip(chosen, vias, start, finish, generation, callback);
        }
      }
      catch (java.util.concurrent.CancellationException e) { return; }
      catch (java.io.UncheckedIOException e)
      {
        result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_maps),null,null);
        result.kind=RoutingController.TransitResult.Kind.TERMINAL;
        result.missingMaps=new String[]{"Latvia"};
      }
      catch (java.io.IOException | android.database.sqlite.SQLiteException e)
      {
        result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_unreadable),mContext.getString(app.organicmaps.R.string.riga_route_update),
            () -> download(start,finish,generation,callback));
      }
      catch (Exception e)
      {
        app.organicmaps.sdk.util.log.Logger.e("RigaRouteProvider", "Planning with stops failed", e);
        liveFailed = true;
        result = new RoutingController.TransitResult(mContext.getString(app.organicmaps.R.string.riga_route_failed),
            mContext.getString(app.organicmaps.R.string.riga_route_retry), () -> RoutingController.get().checkAndBuildRoute());
      }
      finished(refresh, started, hits, misses);
      publish(Collections.singletonList(result), Collections.singletonList(""), start, finish, generation, callback,
              refresh, liveFailed);
    });
  }

  /**
   * Worker: an arrive-by journey with stops, planned from the end: the last part arrives by {@code arriveBy}, and
   * each part before it arrives by the time the stay after it must begin. {@code null} when a part cannot be made.
   */
  @androidx.annotation.Nullable
  private List<RigaJourneyService.Result> backwards(MapObject start, MapObject finish, List<RigaMultiStop.Via> vias,
                                                    long arriveBy, boolean live, boolean routeWalks,
                                                    java.util.function.BooleanSupplier cancelled) throws Exception
  {
    java.util.ArrayDeque<RigaJourneyService.Result> parts = new java.util.ArrayDeque<>();
    long by = arriveBy;
    for (int i = vias.size(); i >= 0; --i)
    {
      double fromLat = i == 0 ? start.getLat() : vias.get(i - 1).lat;
      double fromLon = i == 0 ? start.getLon() : vias.get(i - 1).lon;
      double toLat = i == vias.size() ? finish.getLat() : vias.get(i).lat;
      double toLon = i == vias.size() ? finish.getLon() : vias.get(i).lon;
      RigaJourneyService.Result part = partArrivingBy(fromLat, fromLon, toLat, toLon, by, live, routeWalks,
                                                      cancelled);
      if (part == null) return null;
      parts.addFirst(part);
      if (i > 0) by = part.leaves() - vias.get(i - 1).stay;
    }
    return new ArrayList<>(parts);
  }

  /** Worker: one part arriving by {@code by}, leaving as late as it can: on foot when that leaves no earlier. */
  @androidx.annotation.Nullable
  private RigaJourneyService.Result partArrivingBy(double fromLat, double fromLon, double toLat, double toLon,
                                                   long by, boolean live, boolean routeWalks,
                                                   java.util.function.BooleanSupplier cancelled) throws Exception
  {
    long now = System.currentTimeMillis();
    // Earlier today is allowed when the rider chose a time then; otherwise nothing can arrive in the past.
    long earliest = RigaTimeChoice.earliest(now);
    if (by <= earliest) return null;
    RigaJourneyService.Result ride = null;
    try
    {
      long low = Math.max(earliest, by - RigaTimeChoice.ARRIVE_SPAN);
      RigaJourneyService.Result first = mService.calculateLocations(fromLat, fromLon, toLat, toLon, low,
          live && RigaTimeChoice.liveUseful(low, now), cancelled);
      List<RigaJourneyService.Result> latest = mService.arriveBy(first, by, 1, routeWalks, cancelled);
      if (!latest.isEmpty()) ride = latest.get(0);
    }
    catch (java.util.concurrent.CancellationException | java.io.UncheckedIOException e) { throw e; }
    catch (Exception e) { app.organicmaps.sdk.util.log.Logger.w("RigaRouteProvider", "No ride for a part", e); }
    boolean rides = ride != null && ride.ridesDrawn();
    // As in part(): no walk is routed when even a straight line on foot is too long or must leave before the ride.
    long walking = RigaLocationRouter.optimisticSeconds(fromLat, fromLon, toLat, toLon) * 1000L;
    if (rides && (walking > RigaWalkChoice.LONG_WALK || by - walking < ride.leaves())) return ride;
    RigaJourneyService.Result walk = mService.walkOnly(fromLat, fromLon, toLat, toLon, by, cancelled);
    if (walk != null)
    {
      long leave = by - walk.takes();
      walk = leave < earliest ? null : mService.walkOnly(fromLat, fromLon, toLat, toLon, leave, cancelled);
    }
    if (!rides) return walk;
    if (walk != null && walk.takes() <= RigaWalkChoice.LONG_WALK && walk.leaves() >= ride.leaves())
      return walk;
    return ride;
  }

  /**
   * Worker: one part of a journey with stops; on foot when a walk of at most {@link RigaWalkChoice#LONG_WALK} arrives
   * no later, or when no ride serves it.
   */
  private RigaJourneyService.Result part(double fromLat, double fromLon, double toLat, double toLon, long departure,
                                         boolean live, java.util.function.BooleanSupplier cancelled) throws Exception
  {
    RigaJourneyService.Result ride = null;
    try
    {
      ride = mService.calculateLocations(fromLat, fromLon, toLat, toLon, departure, live, cancelled);
    }
    catch (java.util.concurrent.CancellationException | java.io.UncheckedIOException e) { throw e; }
    catch (Exception e) { app.organicmaps.sdk.util.log.Logger.w("RigaRouteProvider", "No ride for a part", e); }
    boolean rides = ride != null && ride.ridesDrawn();
    // A part has no alternatives to choose from: a long walk is the answer only when no ride serves it, even
    // arriving a few minutes sooner. Routing a walk takes seconds: not done when even a straight line on foot is
    // too long or arrives after the ride.
    long walking = RigaLocationRouter.optimisticSeconds(fromLat, fromLon, toLat, toLon) * 1000L;
    if (rides && (walking > RigaWalkChoice.LONG_WALK || departure + walking > ride.arrives())) return ride;
    RigaJourneyService.Result walk = mService.walkOnly(fromLat, fromLon, toLat, toLon, departure, cancelled);
    if (!rides) return walk;
    if (walk != null && walk.takes() <= RigaWalkChoice.LONG_WALK && walk.arrives() <= ride.arrives()) return walk;
    return ride;
  }

  /** Main thread: back to the planned list, refreshed as before the trip. */
  private void endTrip(MapObject start, MapObject finish, int generation,
                       Consumer<RoutingController.TransitResult> callback)
  {
    if (mTrip == null || generation != mGeneration.get()) return;
    stopTrip();
    if (mLastResult == null) { RoutingController.get().checkAndBuildRoute(); return; }
    mLastResult.kind = RoutingController.TransitResult.Kind.REFRESH;
    mLastResult.fit = false;
    callback.accept(mLastResult);
    scheduleRefresh(start, finish, generation, callback);
    mHandler.removeCallbacks(mRefresh);
    mHandler.post(mRefresh);
  }

  private void stopTrip()
  {
    if (mTripRefresh != null) mHandler.removeCallbacks(mTripRefresh);
    mTripRefresh = null;
    if (mTrip == null) return;
    mTrip = null;
    RigaTripService.stop(mContext);
  }

  /** Main thread: a new plan from the rider's position to the same destination. */
  private void replanFromHere()
  {
    MapObject here = app.organicmaps.MwmApplication.from(mContext).getLocationHelper().getMyPosition();
    // The stops already reached are behind the rider: the new plan goes on to the next one.
    int visited = mTrip == null || mTrip.size() < 2 ? 0 : activePart(mTrip, System.currentTimeMillis());
    stopTrip();
    // The rider is on the way now: a new plan starts from now, not from a time chosen earlier today.
    RigaTimeChoice.reset();
    if (here != null)
      for (int i = 0; i < visited; ++i)
        app.organicmaps.sdk.Framework.nativeRemoveRoutePoint(app.organicmaps.sdk.routing.RouteMarkType.Intermediate, 0);
    if (here == null || !RoutingController.get().setStartPoint(here)) RoutingController.get().checkAndBuildRoute();
  }

  private void scheduleRefresh(MapObject start,MapObject finish,int generation,
                               Consumer<RoutingController.TransitResult> callback)
  {
    if (mRefresh != null) mHandler.removeCallbacks(mRefresh);
    mRefresh=()->{
      if (generation!=mGeneration.get() || !mVisible || mLastResult==null) return;
      if (mLive && liveUseful())
      {
        if (!mVias.isEmpty()) calculateStops(start,finish,mVias,generation,callback,true);
        else calculate(start,finish,generation,callback,true);
        return;
      }
      // The planned ride has left: plan again on the timetable, quietly; the next journeys replace it.
      if (System.currentTimeMillis()>mLastResult.info.firstDeparture)
      {
        if (!mVias.isEmpty()) calculateStops(start,finish,mVias,generation,callback,true);
        else calculate(start,finish,generation,callback,true);
        return;
      }
      // No network or walking calculation is needed for the scheduled-only clock tick.
      mLastResult.kind=RoutingController.TransitResult.Kind.REFRESH;mLastResult.fit=false;
      callback.accept(mLastResult);scheduleRefresh(start,finish,generation,callback);
    };
    if (mVisible) mHandler.postDelayed(mRefresh,30000);
  }

  /** Live positions only help a journey that starts soon; a plan for tonight stays on the timetable. */
  private static boolean liveUseful()
  {
    long now = System.currentTimeMillis();
    return RigaTimeChoice.liveUseful(RigaTimeChoice.searchStart(now), now);
  }

  private String modeName(int gtfsType)
  {
    return RigaModeStyle.name(mContext, gtfsType);
  }

  /**
   * Once a trip has been matched to a fleet number, the number stays with that trip (the id carries its
   * service day) even when later live data is stale or missing, so the Mobilly shortcut does not flicker.
   */
  private void rememberVehicle(app.organicmaps.sdk.routing.TransitJourneyLeg target, RigaJourneyPlanner.Leg leg)
  {
    if (!target.vehicleNumber.isEmpty())
    {
      if (mKnownVehicles.size() > 500) mKnownVehicles.clear();
      mKnownVehicles.put(leg.trip.id, target.vehicleNumber);
    }
    else
    {
      String known = mKnownVehicles.get(leg.trip.id);
      if (known != null) target.vehicleNumber = known;
    }
  }

  /** Relates the matched vehicle to the nearest timetable stop so its approach is obvious. */
  private void progress(app.organicmaps.sdk.routing.TransitJourneyLeg target, RigaJourneyPlanner.Leg leg,
                        RigaJourneyService.Result route, SimpleDateFormat clock)
  {
    // The fleet number comes from the live match itself, not from the map's (freshness-filtered) vehicle list,
    // so a slow plan cannot hide the Mobilly shortcut while the ride is still shown as live.
    if (leg.prediction.vehicleId.matches("\\d{3,6}")) target.vehicleNumber = leg.prediction.vehicleId;
    RigaVehicle vehicle = null;
    for (RigaVehicle candidate : route.vehicles())
      if (leg.prediction.matches(candidate)) vehicle = candidate;
    int next = leg.prediction.firstBoardableEvent;
    int away = leg.firstEvent - next;
    if (vehicle == null || away < 0 || next < 0 || next >= leg.trip.events.size()) return;
    RigaJourneyPlanner.Stop nearest = route.stop(leg.trip.events.get(next).stop);
    double distance = RigaJourneyDatabase.distance(vehicle.lat, vehicle.lon, nearest.lat, nearest.lon);
    if (next > 0)
    {
      RigaJourneyPlanner.Stop previous = route.stop(leg.trip.events.get(next - 1).stop);
      double back = RigaJourneyDatabase.distance(vehicle.lat, vehicle.lon, previous.lat, previous.lon);
      if (back < distance) { nearest = previous; distance = back; }
    }
    String mode = modeName(leg.trip.type);
    target.vehicleStopsAway = away;
    // leg.departure already carries the live delay: it is the predicted time at the boarding stop.
    long minutes = Math.max(0, Math.round((leg.departure - System.currentTimeMillis()) / 60000.0));
    String in = minutes == 0 ? mContext.getString(app.organicmaps.R.string.riga_live_now)
        : mContext.getResources().getQuantityString(app.organicmaps.R.plurals.riga_live_in_minutes,
            (int) minutes, (int) minutes);
    String at = clock.format(new java.util.Date(leg.departure));
    if (away == 0)
      target.progress = distance < 60 && nearest == route.stop(leg.trip.events.get(next).stop)
          ? mContext.getString(app.organicmaps.R.string.riga_live_at_stop, mode, leg.trip.routeName, nearest.name)
          : mContext.getString(app.organicmaps.R.string.riga_live_arriving, mode, leg.trip.routeName, in, at);
    else
      target.progress = mContext.getResources().getQuantityString(app.organicmaps.R.plurals.riga_live_stops_away,
          away, mode, leg.trip.routeName, away, nearest.name, in, at);
  }

  private RoutingController.TransitResult present(RigaJourneyService.Result route,MapObject start,MapObject finish)
  {
    RoutingController.TransitResult result = present(route, start.getTitle(), finish.getTitle());
    if (!route.rides() && !route.journey.legs.isEmpty())
    {
      // On foot the whole way: there are no times to be live or scheduled, only when to set off.
      SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
      clock.setTimeZone(RigaTransitStore.ZONE);
      // The same set-off time as the header's span (the arrival less the whole minutes on foot).
      result.info.description = mContext.getString(app.organicmaps.R.string.riga_walk_all,
          clock.format(new Date(result.info.arrival - result.info.getTotalTime() * 1000L)));
    }
    return result;
  }

  private RoutingController.TransitResult present(RigaJourneyService.Result route, String startTitle,
                                                  String finishTitle)
  {
    // One walk per stretch on foot, the first ending at the first ride: steps, details, map and trip guide agree.
    route = RigaWalks.tidy(route);
    // Lines making the same ride ("7/14") and their other departures, for the chips and the Steps sheet.
    RigaSameRides.Group[] groups = RigaSameRides.of(route.data, route.journey, System.currentTimeMillis());
    int legIndex = -1;
    List<TransitStepInfo> steps = new ArrayList<>();
    List<app.organicmaps.sdk.routing.TransitJourneyLeg> legs = new ArrayList<>();
    StringBuilder details = new StringBuilder();
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(RigaTransitStore.ZONE);
    int walkMetres = 0, walkSeconds = 0, accessSeconds = 0;
    RigaJourneyPlanner.Leg firstRide = null;
    // The matcher traces each vehicle of these lines from now on, so a missing fleet number can be explained.
    java.util.Set<String> watched = new java.util.HashSet<>();
    for (RigaJourneyPlanner.Leg leg : route.journey.legs)
      if (leg.trip != null && !leg.trip.shapeId.isEmpty()) watched.add(RigaJourneyMatcher.lineOf(leg.trip.shapeId));
    if (!watched.equals(RigaJourneyMatcher.sWatched))
    {
      RigaJourneyMatcher.sWatched = watched;
      // Explain right away, not only from the next refresh: the app may be closed before that.
      mService.traceLastMatch(route.data);
    }
    for (RigaJourneyPlanner.Leg leg : route.journey.legs)
    {
      RigaSameRides.Group group = groups[++legIndex];
      int seconds = (int) ((leg.arrival - leg.departure) / 1000);
      if (firstRide == null)
      {
        if (leg.trip == null) accessSeconds += seconds;
        else firstRide = leg;
      }
      TransitStepType type = TransitStepType.PEDESTRIAN;
      String number = "";
      int color = 0xff777777;
      if (leg.trip == null)
      {
        walkMetres += leg.walkingMetres;
        walkSeconds += seconds;
        details.append(mContext.getString(app.organicmaps.R.string.riga_route_walk,(seconds+59)/60,leg.walkingMetres));
      }
      else
      {
        type = RigaModeStyle.type(leg.trip.type);
        number = leg.trip.routeName;
        color = RigaModeStyle.color(leg.trip.type);
        details.append(mContext.getString(type == TransitStepType.TRAM ? app.organicmaps.R.string.riga_tram : type == TransitStepType.TROLLEYBUS ? app.organicmaps.R.string.riga_trolleybus : app.organicmaps.R.string.riga_bus) + " ")
            .append(number).append(" → ").append(leg.trip.headsign)
            .append(leg.prediction == null ? " ("+mContext.getString(app.organicmaps.R.string.riga_route_scheduled)+")" : " ("+mContext.getString(app.organicmaps.R.string.riga_route_live_estimate)+")");
      }
      details.append("\n").append(clock.format(new Date(leg.departure))).append(" ")
          .append(route.stopName(leg.from, startTitle, finishTitle)).append(" → ")
          .append(clock.format(new Date(leg.arrival))).append(" ")
          .append(route.stopName(leg.to, startTitle, finishTitle)).append("\n\n");
      steps.add(new TransitStepInfo(type.ordinal(), "", "", seconds,
          leg.trip == null ? number : RigaSameRides.badge(group, number, 3), color, -1).withStopCount(leg.stopCount()));
      List<app.organicmaps.sdk.routing.TransitJourneyLeg.Stop> stops = new ArrayList<>();
      if (leg.trip == null)
      {
        RigaJourneyPlanner.Stop a=route.stop(leg.from),b=route.stop(leg.to);
        stops.add(new app.organicmaps.sdk.routing.TransitJourneyLeg.Stop(
            route.stopName(leg.from,startTitle,finishTitle),leg.departure,leg.departure,a.lat,a.lon));
        stops.add(new app.organicmaps.sdk.routing.TransitJourneyLeg.Stop(
            route.stopName(leg.to,startTitle,finishTitle),leg.arrival,leg.arrival,b.lat,b.lon));
      }
      else
      {
        long delay=leg.prediction==null?0:leg.prediction.delayMillis();
        for (int i=leg.firstEvent;i<=leg.lastEvent;++i)
        {
          RigaJourneyPlanner.Event event=leg.trip.events.get(i);
          RigaJourneyPlanner.Stop stop=route.stop(event.stop);
          stops.add(new app.organicmaps.sdk.routing.TransitJourneyLeg.Stop(stop.name,
              event.arrival+delay,event.departure+delay,stop.lat,stop.lon));
        }
        details.append(mContext.getResources().getQuantityString(app.organicmaps.R.plurals.riga_ride_stops,
            leg.stopCount(),leg.stopCount())).append("\n\n");
      }
      app.organicmaps.sdk.routing.TransitJourneyLeg journeyLeg=new app.organicmaps.sdk.routing.TransitJourneyLeg(
          type,number,leg.trip==null?"":leg.trip.headsign,color,leg.walkingMetres,leg.prediction!=null,
          stops.toArray(new app.organicmaps.sdk.routing.TransitJourneyLeg.Stop[0]));
      if (leg.trip != null && leg.prediction != null)
      {
        progress(journeyLeg, leg, route, clock);
        journeyLeg.delay = leg.prediction.delayMillis();
      }
      if (leg.trip != null)
      {
        String live = journeyLeg.vehicleNumber;
        rememberVehicle(journeyLeg, leg);
        String next = null;
        // Without a matched trip: the vehicle of the line expected at the stop closest to the departure. Not
        // remembered: it can change as vehicles move.
        if (journeyLeg.vehicleNumber.isEmpty() && leg.trip.shapeId.startsWith("riga_")
            && leg.departure - System.currentTimeMillis() < 45 * 60000
            && leg.departure - System.currentTimeMillis() > -10 * 60000)
        {
          next = mService.nextVehicle(route.data, leg.trip, leg.firstEvent, leg.departure, System.currentTimeMillis());
          if (next != null) journeyLeg.vehicleNumber = next;
        }
        RigaJourneyPlanner.Event board = leg.trip.events.get(leg.firstEvent);
        app.organicmaps.sdk.util.log.Logger.i("RigaMatch", "leg " + number + " trip " + leg.trip.id + " shape "
            + leg.trip.shapeId + " start " + clock.format(new java.util.Date(leg.trip.events.get(0).departure))
            + " (" + (leg.trip.events.get(0).departure - leg.trip.serviceOrigin) / 60000 + ") boards stop "
            + leg.firstEvent + " at " + clock.format(new java.util.Date(board.departure)) + ": "
            + (leg.prediction == null ? "no live match"
               : "matched vehicle " + leg.prediction.vehicleId + " delay " + leg.prediction.delaySeconds
                     + "s next stop " + leg.prediction.firstBoardableEvent)
            + ", number " + (!live.isEmpty() ? live : next != null ? "expected at the stop " + next
                : journeyLeg.vehicleNumber.isEmpty() ? "none" : "remembered " + journeyLeg.vehicleNumber));
      }
      // Only Rīgas satiksme lines (their shapes are "riga_...") are validated in Mobilly and similar apps.
      journeyLeg.validatable = leg.trip != null && leg.trip.shapeId.startsWith("riga_");
      journeyLeg.lines = group.lines.toArray(new String[0]);
      journeyLeg.also = group.also;
      journeyLeg.alsoLines = group.alsoLines;
      legs.add(journeyLeg);
    }
    TransitRouteInfo info = new TransitRouteInfo("", "", 
        // Door-to-door trip time from leaving, as on the alternative cards; waiting at home is not travel.
        // Whole minutes, rounded like the alternative cards, so header and selected card agree.
        // On foot the whole way the walk chip is the trip: rounded up as that chip is, so the two agree.
        (int)((route.rides() ? Math.round(route.takes() / 60000.0) : Math.ceil(route.takes() / 60000.0)) * 60),
        Integer.toString(walkMetres), "m", walkSeconds, steps.toArray(new TransitStepInfo[0]));
    String status = route.received > 0 ? mContext.getString(app.organicmaps.R.string.riga_live_received,clock.format(new Date(route.received)))
        : route.liveFailed ? mContext.getString(app.organicmaps.R.string.riga_route_live_scheduled) : mContext.getString(app.organicmaps.R.string.riga_route_scheduled_times);
    info.journeyLegs=legs.toArray(new app.organicmaps.sdk.routing.TransitJourneyLeg[0]);
    info.journeyStatus=status;
    info.timetableValidity=mStore.validity();
    info.firstDeparture=firstRide==null?route.arrives():firstRide.departure;
    info.arrival=route.endsAt();
    // Where the rider's vehicle is now leads (the panel styles that line); then what to do. The arrival is in the
    // panel's header.
    String departureLine = "";
    if (firstRide != null)
    {
      for (app.organicmaps.sdk.routing.TransitJourneyLeg leg : legs)
        if (leg.isRide()) { if (!leg.progress.isEmpty()) departureLine = leg.progress + "\n"; break; }
      if (!departureLine.isEmpty())
        departureLine += mContext.getString(app.organicmaps.R.string.riga_live_leave_short,
            clock.format(new Date(firstRide.departure-accessSeconds*1000L)), route.stop(firstRide.from).name)
            + "\n";
      else
      departureLine += mContext.getString(app.organicmaps.R.string.riga_live_leave,
          clock.format(new Date(firstRide.departure-accessSeconds*1000L)), modeName(firstRide.trip.type),
          firstRide.trip.routeName, clock.format(new Date(firstRide.departure)),
          route.stop(firstRide.from).name) + "\n";
    }
    // When the times were received says little (the cards mark live times); only their failing is news.
    info.description = route.liveFailed && route.received <= 0 ? departureLine + status : departureLine.trim();
    info.instructions = details.toString();
    RoutingController.TransitResult result = new RoutingController.TransitResult(info, route.lines, route.walking);
    result.colors = new int[route.journey.legs.size()];
    for (int i=0;i<result.colors.length;++i)
    {
      RigaJourneyPlanner.Leg leg = route.journey.legs.get(i);
      result.colors[i] = leg.trip == null ? 0xff777777 : RigaModeStyle.color(leg.trip.type);
    }
    List<RigaVehicle> vehicles = route.vehicles();
    int count = vehicles.size();
    result.vehicleCoordinates = new double[count*2]; result.vehicleNumbers = new String[count];
    result.vehicleTitles = new String[count]; result.vehicleColors = new int[count];
    result.vehicleExpires = route.received + 45000;
    for (int i=0;i<count;++i)
    {
      RigaVehicle vehicle = vehicles.get(i);
      result.vehicleCoordinates[2*i]=vehicle.lat; result.vehicleCoordinates[2*i+1]=vehicle.lon;
      result.vehicleNumbers[i]=vehicle.route;
      boolean yours = RigaJourneyVehicles.matched(route.journey, vehicle);
      int color = RigaModeStyle.color(RigaModeStyle.vehicleType(vehicle.type));
      // Other vehicles of the chosen lines are drawn translucent so the rider's own vehicle stands out.
      result.vehicleColors[i] = yours ? color : (color & 0x00ffffff) | 0x8c000000;
      result.vehicleTitles[i] = mContext.getString(yours ? app.organicmaps.R.string.riga_vehicle_yours
              : app.organicmaps.R.string.riga_vehicle_same_line, vehicle.route,
          clock.format(new Date(vehicle.receivedAt)));
    }
    return result;
  }
}
