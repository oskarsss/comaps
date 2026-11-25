package app.organicmaps.transit;

import static org.junit.Assert.*;
import android.app.Application;
import app.organicmaps.sdk.bookmarks.data.*;
import app.organicmaps.sdk.routing.*;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public class RigaItineraryPresentationTest
{
  @Test public void scheduledAndLiveRideIncludeAllOrderedStopsAndCorrectCounts() throws Exception
  {
    RigaTransitStore store=RigaJourneyDatabaseTest.fixture();
    long departure=Instant.parse("2026-11-20T05:55:00Z").toEpochMilli();
    RigaJourneyPlanner.Timetable data=new RigaJourneyDatabase(store).load(departure,()->false);
    int direct=-1;
    for(int i=0;i<data.trips.size();++i)if(data.trips.get(i).id.equals("direct@20261120"))direct=i;
    assertTrue(direct>=0);
    MapObject start=MapObject.createMapObject(FeatureId.EMPTY,MapObject.POI,"Start","",56.95,24.10);
    MapObject finish=MapObject.createMapObject(FeatureId.EMPTY,MapObject.POI,"End","",56.95,24.12);
    RigaRouteProvider provider=new RigaRouteProvider(RuntimeEnvironment.getApplication());
    java.lang.reflect.Method present=RigaRouteProvider.class.getDeclaredMethod("present",RigaJourneyService.Result.class,MapObject.class,MapObject.class);
    present.setAccessible(true);
    for(boolean live:new boolean[]{false,true})
    {
      Map<Integer,RigaJourneyPlanner.Prediction> predictions=live?Collections.singletonMap(direct,
          new RigaJourneyPlanner.Prediction(300,0,"bus",departure)):Collections.emptyMap();
      RigaJourneyPlanner.Journey journey=RigaJourneyPlanner.plan(data,"a","c",departure,predictions,()->false);
      RigaJourneyService.Result result=new RigaJourneyService.Result(data,journey,live?1:0,live?departure:0,false,
          new double[][]{{56.95,24.1,56.95,24.12}},new boolean[]{false});
      RoutingController.TransitResult shown=(RoutingController.TransitResult)present.invoke(provider,result,start,finish);
      TransitJourneyLeg leg=shown.info.journeyLegs[0];
      assertEquals(2,leg.stopCount());assertEquals(2,shown.info.getTransitSteps().get(0).getStopCount());
      assertArrayEquals(new String[]{"A","B","C"},Arrays.stream(leg.stops).map(s->s.name).toArray(String[]::new));
      assertEquals(live,leg.live);
      assertEquals(Instant.parse("2026-11-20T06:20:00Z").toEpochMilli()+(live?300000:0),leg.stops[2].arrival);
      assertTrue(shown.info.instructions.contains("Ride 2 stops"));
    }
    TransitStepInfo nativeStep=new TransitStepInfo(TransitStepType.BUS.ordinal(),"","",60,"22",0,-1);
    assertEquals(-1,nativeStep.getStopCount());
  }
}
