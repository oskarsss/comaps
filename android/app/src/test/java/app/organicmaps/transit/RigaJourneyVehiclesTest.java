package app.organicmaps.transit;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class RigaJourneyVehiclesTest
{
  private static RigaJourneyPlanner.Journey journey()
  {
    List<RigaJourneyPlanner.Stop> stops=Arrays.asList(
        new RigaJourneyPlanner.Stop("a","A","",56.95,24.10),new RigaJourneyPlanner.Stop("b","B","",56.96,24.11));
    RigaJourneyPlanner.Trip trip=new RigaJourneyPlanner.Trip("trip","bus_22","22","B",3,"",0,Arrays.asList(
        new RigaJourneyPlanner.Event(0,1,120000,120000,true,true),
        new RigaJourneyPlanner.Event(1,2,600000,600000,true,true)));
    return RigaJourneyPlanner.plan(new RigaJourneyPlanner.Timetable(stops,Arrays.asList(trip),Collections.emptyList()),
        "a","b",0,Collections.singletonMap(0,new RigaJourneyPlanner.Prediction(0,0,"chosen",10000)),()->false);
  }
  private static RigaVehicle vehicle(String route,String id,int type,long received)
  { return RigaVehicle.parse(type+","+route+",24110000,56950000,0,0,L,"+id+",a-b,0,0,\n",received).get(0); }
  @Test public void onlyTheMatchedJourneyVehicleIsRelevant()
  {
    List<RigaVehicle> result=RigaJourneyVehicles.select(journey(),Arrays.asList(
        vehicle("22","otherBusOnSameRoute",2,10000),vehicle("15","unrelated",1,10000),
        vehicle("22","chosen",2,10000)),10000);
    assertEquals(1,result.size());assertEquals("chosen",result.get(0).id);
  }
  @Test public void wrongModeNumberDuplicateStaleAndFutureObservationsAreHidden()
  {
    for (List<RigaVehicle> vehicles:Arrays.asList(
        Arrays.asList(vehicle("22","chosen",1,10000)),
        Arrays.asList(vehicle("23","chosen",2,10000)),
        Arrays.asList(vehicle("22","chosen",2,10000),vehicle("22","chosen",2,10000)),
        Arrays.asList(vehicle("22","chosen",2,0)),
        Arrays.asList(vehicle("22","chosen",2,100000))))
      assertTrue(RigaJourneyVehicles.select(journey(),vehicles,60000).isEmpty());
  }
  @Test public void completedRidesAndCancelledJourneysHaveNoMarkers()
  {
    List<RigaVehicle> vehicles=Arrays.asList(vehicle("22","chosen",2,600001));
    assertTrue(RigaJourneyVehicles.select(journey(),vehicles,600001).isEmpty());
    assertTrue(RigaJourneyVehicles.select(null,vehicles,600001).isEmpty());
  }
  private static RigaJourneyPlanner.Journey journeyOnShape(String shape)
  {
    List<RigaJourneyPlanner.Stop> stops=Arrays.asList(
        new RigaJourneyPlanner.Stop("a","A","",56.95,24.10),new RigaJourneyPlanner.Stop("b","B","",56.96,24.11));
    RigaJourneyPlanner.Trip trip=new RigaJourneyPlanner.Trip("trip","bus_22","22","B",3,shape,0,Arrays.asList(
        new RigaJourneyPlanner.Event(0,1,120000,120000,true,true),
        new RigaJourneyPlanner.Event(1,2,600000,600000,true,true)));
    return RigaJourneyPlanner.plan(new RigaJourneyPlanner.Timetable(stops,Arrays.asList(trip),Collections.emptyList()),
        "a","b",0,Collections.singletonMap(0,new RigaJourneyPlanner.Prediction(0,0,"chosen",10000)),()->false);
  }
  private static RigaVehicle at(String id,String direction,double lat)
  { return RigaVehicle.parse("2,22,24100000,"+(int)(lat*1000000)+",0,0,L,"+id+","+direction+",0,0,\n",10000).get(0); }
  @Test public void otherVehiclesOfTheChosenLineAndDirectionAreShownAfterTheMatchedOne()
  {
    RigaJourneyPlanner.Journey journey=journeyOnShape("riga_bus_22_a-b");
    List<RigaJourneyPlanner.Stop> stops=Arrays.asList(
        new RigaJourneyPlanner.Stop("a","A","",56.95,24.10),new RigaJourneyPlanner.Stop("b","B","",56.96,24.11));
    List<RigaVehicle> result=RigaJourneyVehicles.select(journey,stops,Arrays.asList(
        at("far","a-b",56.99),at("opposite","b-a",56.95),at("near","a-b",56.951),at("chosen","a-b",56.94)),10000);
    List<String> ids=new ArrayList<>();
    for (RigaVehicle v:result) ids.add(v.id);
    assertEquals(Arrays.asList("chosen","near","far"),ids);
    assertTrue(RigaJourneyVehicles.matched(journey,result.get(0)));
    assertFalse(RigaJourneyVehicles.matched(journey,result.get(1)));
  }
  @Test public void sameLineVehiclesAreCappedPerRide()
  {
    List<RigaVehicle> fleet=new ArrayList<>();
    for (int i=0;i<10;++i) fleet.add(at("v"+i,"a-b",56.95+i*0.001));
    List<RigaVehicle> result=RigaJourneyVehicles.select(journeyOnShape("riga_bus_22_a-b"),null,fleet,10000);
    assertEquals(RigaJourneyVehicles.OTHERS_PER_RIDE,result.size());
  }
}
