package app.organicmaps.transit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class,shadows=RigaRoutingDispatchTest.ShadowFramework.class)
public class RigaRoutingDispatchTest
{
  @Implements(Framework.class)
  public static class ShadowFramework
  {
    static int nativeBuilds, draws;
    static boolean fit;
    static int vehicleCount,removedStops;
    static RouteMarkData[] points=new RouteMarkData[0];
    @Implementation protected static void nativeRemoveRoute() {}
    @Implementation protected static void nativeDrawRigaJourney(double[][] lines,boolean[] walking,boolean fit) {}
    @Implementation protected static void nativeCancelRouteBuild() {}
    @Implementation protected static void nativeBuildRoute() { ++nativeBuilds; }
    @Implementation protected static void nativeDisableFollowing() {}
    @Implementation protected static void nativeAddRoutePoint(String title,String subtitle,RouteMarkType type,
        int index,boolean myPosition,double lat,double lon,boolean reorder) {}
    @Implementation protected static void nativeRemoveIntermediateRoutePoints()
    { ++removedStops;points=new RouteMarkData[0]; }
    @Implementation protected static RouteMarkData[] nativeGetRoutePoints() { return points; }
    @Implementation protected static void nativeSetJourneyVehicles(double[] coordinates,String[] numbers,int[] colors,String[] titles) { vehicleCount=numbers.length; }
    @Implementation protected static void nativeDrawColoredRigaJourney(double[][] lines,boolean[] walking,int[] colors,boolean value)
    { if (lines.length > 0) ++draws; fit=value; }
  }
  private static class Controller extends RoutingController
  {
    MapObject from=MapObject.createMapObject(FeatureId.EMPTY,MapObject.POI,"From","",56.9334,24.2046);
    MapObject to=MapObject.createMapObject(FeatureId.EMPTY,MapObject.POI,"To","",56.940276,24.148686);
    @Override public MapObject getStartPoint() { return from; }
    @Override public MapObject getEndPoint() { return to; }
  }
  private static class Provider implements RoutingController.TransitProvider
  {
    boolean supported=true;
    int cancellations;
    MapObject source;
    final List<Consumer<RoutingController.TransitResult>> requests=new ArrayList<>();
    @Override public boolean supports(MapObject from,MapObject to) { return supported; }
    @Override public void build(MapObject from,MapObject to,Consumer<RoutingController.TransitResult> callback)
    { source=from;requests.add(callback); }
    @Override public void cancel() { ++cancellations; }
  }
  private Controller controller;
  private Provider provider;
  @Before public void setup()
  {
    ShadowFramework.nativeBuilds=ShadowFramework.draws=ShadowFramework.vehicleCount=ShadowFramework.removedStops=0;
    ShadowFramework.points=new RouteMarkData[0];
    controller=new Controller();provider=new Provider();controller.setTransitProvider(provider);
    ReflectionHelpers.setField(controller,"mLastRouterType",Router.Transit);
  }
  private void build() { ReflectionHelpers.callInstanceMethod(controller,"build"); }
  private static RoutingController.TransitResult result()
  {
    TransitRouteInfo info=new TransitRouteInfo("","",900,"150","m",120,new TransitStepInfo[0]);
    return new RoutingController.TransitResult(info,new double[][]{{56.93,24.20,56.94,24.14}},new boolean[]{false});
  }
  @Test public void rigaTransportDispatchesThroughProviderAndPublishesGeometry()
  {
    build();assertEquals(0,ShadowFramework.nativeBuilds);assertEquals(1,provider.requests.size());
    RoutingController.TransitResult value=result();provider.requests.get(0).accept(value);
    assertEquals(RoutingController.BuildState.BUILT,controller.getBuildState());
    assertSame(value.info,controller.getCachedTransitInfo());assertEquals(2,controller.getRouteGeometry().getPointCount());
    assertEquals(1,ShadowFramework.draws);
  }
  @Test public void unsupportedPointsAndOtherModesKeepNativeDispatch()
  {
    provider.supported=false;build();assertEquals(1,ShadowFramework.nativeBuilds);assertTrue(provider.requests.isEmpty());
    provider.supported=true;
    for (Router mode : new Router[]{Router.Vehicle,Router.Pedestrian,Router.Bicycle,Router.Ruler})
    {
      int previous=ShadowFramework.nativeBuilds;
      ReflectionHelpers.setField(controller,"mLastRouterType",mode);build();
      assertEquals(previous+1,ShadowFramework.nativeBuilds);assertTrue(provider.requests.isEmpty());
    }
  }
  @Test public void oldCalculationCannotReplaceANewerRoute()
  {
    build();Consumer<RoutingController.TransitResult> old=provider.requests.get(0);
    build();old.accept(result());assertEquals(0,ShadowFramework.draws);
    assertEquals(RoutingController.BuildState.BUILDING,controller.getBuildState());
    provider.requests.get(1).accept(result());assertEquals(1,ShadowFramework.draws);
    assertEquals(2,provider.cancellations);
  }
  @Test public void downloadAndFailureStatesDoNotClaimABuiltRoute()
  {
    build();provider.requests.get(0).accept(new RoutingController.TransitResult("Installing timetable",null,null));
    assertEquals(RoutingController.BuildState.BUILDING,controller.getBuildState());
    assertEquals("Installing timetable",controller.getTransitStatus().message);assertEquals(0,ShadowFramework.draws);
  }
  @Test public void liveRefreshPreservesCameraFitFlag()
  {
    build();provider.requests.get(0).accept(result());assertTrue(ShadowFramework.fit);
    RoutingController.TransitResult refresh=result();refresh.fit=false;refresh.kind=RoutingController.TransitResult.Kind.REFRESH;provider.requests.get(0).accept(refresh);
    assertFalse(ShadowFramework.fit);assertEquals(2,ShadowFramework.draws);
  }
  @Test public void refreshDoesNotRepeatBuiltCallbackAndTerminalStopsProgress()
  {
    final int[] built={0},errors={0};
    controller.attach(new RoutingController.Container() {
      @Override public void onBuiltRoute() { ++built[0]; }
      @Override public void onCommonBuildError(int code,String[] maps) { ++errors[0]; }
    });
    build();provider.requests.get(0).accept(result());assertEquals(1,built[0]);
    RoutingController.TransitResult refresh=result();refresh.kind=RoutingController.TransitResult.Kind.REFRESH;
    provider.requests.get(0).accept(refresh);assertEquals(1,built[0]);
    provider.requests.get(0).accept(new RoutingController.TransitResult("No service","Try again",()->{}));
    assertEquals(RoutingController.BuildState.ERROR,controller.getBuildState());assertEquals(0,errors[0]);
  }

  @Test public void currentGpsFixReplacesTheStoredMyPositionAndMissingFixIsTerminal()
  {
    controller.from=MapObject.createMapObject(FeatureId.EMPTY,MapObject.MY_POSITION,"My position","",56.93,24.2);
    app.organicmaps.sdk.location.LocationHelper location=mock(app.organicmaps.sdk.location.LocationHelper.class);
    MapObject current=MapObject.createMapObject(FeatureId.EMPTY,MapObject.MY_POSITION,"My position","",56.94,24.19);
    when(location.getMyPosition()).thenReturn(current);ReflectionHelpers.setField(controller,"mLocationHelper",location);
    build();assertSame(current,provider.source);
    provider.requests.clear();when(location.getMyPosition()).thenReturn(null);build();
    assertTrue(provider.requests.isEmpty());assertEquals(RoutingController.BuildState.ERROR,controller.getBuildState());
    assertTrue(controller.getTransitStatus().message.contains("location"));
  }
  @Test public void freshRigaGpsFixOverridesStoredOutsideRigaBeforeProviderDispatch()
  {
    controller.from=MapObject.createMapObject(FeatureId.EMPTY,MapObject.MY_POSITION,"My position","",57.5,25.5);
    provider=new Provider() {
      @Override public boolean supports(MapObject from,MapObject to)
      { return from.getLat() >= 56.82 && from.getLat() <= 57.10; }
    };
    controller.setTransitProvider(provider);
    app.organicmaps.sdk.location.LocationHelper location=mock(app.organicmaps.sdk.location.LocationHelper.class);
    MapObject current=MapObject.createMapObject(FeatureId.EMPTY,MapObject.MY_POSITION,"My position","",56.94,24.19);
    when(location.getMyPosition()).thenReturn(current);ReflectionHelpers.setField(controller,"mLocationHelper",location);
    build();assertEquals(0,ShadowFramework.nativeBuilds);assertSame(current,provider.source);
  }

  @Test public void intermediatePointsAreExplicitAndRemoveStopsRebuildsThroughController()
  {
    ShadowFramework.points=new RouteMarkData[]{new RouteMarkData("Via","",RouteMarkType.Intermediate,0,
        true,false,false,56.94,24.15)};
    build();assertTrue(provider.requests.isEmpty());assertEquals(RoutingController.BuildState.ERROR,controller.getBuildState());
    controller.getTransitStatus().action.run();assertEquals(1,ShadowFramework.removedStops);
    assertEquals(1,provider.requests.size());
  }
  @Test public void missingPedestrianMapUsesExistingDownloadFlowAndPreservesEndpoints()
  {
    MapObject start=controller.from,finish=controller.to;
    final List<String> maps=new ArrayList<>();final int[] code={-1};
    controller.attach(new RoutingController.Container() {
      @Override public void onCommonBuildError(int value,String[] missing)
      { code[0]=value;java.util.Collections.addAll(maps,missing); }
    });
    build();
    RoutingController.TransitResult missing=new RoutingController.TransitResult("Download Latvia",null,null);
    missing.kind=RoutingController.TransitResult.Kind.TERMINAL;
    missing.missingMaps=new String[]{"Latvia"};provider.requests.get(0).accept(missing);
    assertEquals(9,code[0]);assertEquals(java.util.Collections.singletonList("Latvia"),maps);
    assertEquals(RoutingController.BuildState.ERROR,controller.getBuildState());
    assertSame(start,controller.from);assertSame(finish,controller.to);assertEquals(0,ShadowFramework.draws);
  }

  @Test public void vehicleMarkersExpireAndHiddenCompletionsCannotRepopulateThem()
  {
    controller.attach(new RoutingController.Container() {});build();
    RoutingController.TransitResult route=result();route.vehicleCoordinates=new double[]{56.94,24.15};
    route.vehicleNumbers=new String[]{"15"};route.vehicleTitles=new String[]{"15 live"};route.vehicleColors=new int[]{0xff009de0};
    route.vehicleExpires=System.currentTimeMillis()+45000;
    provider.requests.get(0).accept(route);assertEquals(1,ShadowFramework.vehicleCount);
    controller.setTransitVisible(false);assertEquals(0,ShadowFramework.vehicleCount);
    route.kind=RoutingController.TransitResult.Kind.REFRESH;
    provider.requests.get(0).accept(route);assertEquals(0,ShadowFramework.vehicleCount);
    controller.setTransitVisible(true);provider.requests.get(0).accept(route);assertEquals(1,ShadowFramework.vehicleCount);
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(46));
    assertEquals(0,ShadowFramework.vehicleCount);
  }

}
