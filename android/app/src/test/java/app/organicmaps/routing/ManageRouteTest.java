package app.organicmaps.routing;

import static org.junit.Assert.*;

import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitStepType;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ManageRouteTest
{
  private static RouteMarkData point(RouteMarkType type, boolean me, double lat, double lon)
  {
    return new RouteMarkData("", "", type, 0, true, me, false, lat, lon);
  }

  @Test public void bareCoordinatesAreRecognised()
  {
    assertTrue(ManageRouteAdapter.isCoordinates("56.932043, 24.207299"));
    assertTrue(ManageRouteAdapter.isCoordinates("-33.9, 151.2"));
    assertFalse(ManageRouteAdapter.isCoordinates("Ludzas iela 12"));
    assertFalse(ManageRouteAdapter.isCoordinates("Bus 15, stop 3"));
  }

  @Test public void aPositionWithinFiftyMetresIsStillHere()
  {
    assertTrue(ManageRouteAdapter.isNear(56.932043, 24.207299, 56.932200, 24.207500));
    assertFalse(ManageRouteAdapter.isNear(56.932043, 24.207299, 56.933043, 24.207299)); // ~110 m north
  }

  @Test public void anUnchangedListIsTheRoute()
  {
    RouteMarkData[] route = {point(RouteMarkType.Start, true, 56.93, 24.20),
                             point(RouteMarkType.Intermediate, false, 56.95, 24.10),
                             point(RouteMarkType.Finish, false, 56.94, 24.18)};
    assertTrue(ManageRouteBottomSheet.sameAsRoute(Arrays.asList(route), route));
    List<RouteMarkData> swapped = Arrays.asList(route[0], route[2], route[1]);
    assertFalse(ManageRouteBottomSheet.sameAsRoute(swapped, route));
    assertFalse(ManageRouteBottomSheet.sameAsRoute(Arrays.asList(route[0], route[2]), route));
    List<RouteMarkData> notMe = Arrays.asList(point(RouteMarkType.Start, false, 56.93, 24.20), route[1], route[2]);
    assertFalse(ManageRouteBottomSheet.sameAsRoute(notMe, route));
  }

  private static TransitJourneyLeg leg(TransitStepType type, boolean validatable)
  {
    TransitJourneyLeg leg = new TransitJourneyLeg(type, "15", "Mežciems", 0, 0, false,
        new TransitJourneyLeg.Stop[] {new TransitJourneyLeg.Stop("A", 0, 0, 56.95, 24.10),
                                      new TransitJourneyLeg.Stop("B", Long.MAX_VALUE, Long.MAX_VALUE, 56.96, 24.11)});
    leg.validatable = validatable;
    return leg;
  }

  @Test public void onlyRigasSatiksmeRidesAreValidated()
  {
    TransitJourneyLeg train = leg(TransitStepType.TRAIN, false);
    TransitJourneyLeg bus = leg(TransitStepType.BUS, true);
    train.vehicleNumber = "8001"; // a live vehicle alone does not make a ride validated in Mobilly
    assertFalse(MobillyShortcut.validates(train));
    assertTrue(MobillyShortcut.validates(bus));
    assertSame(bus, MobillyShortcut.nextKnownVehicle(new TransitJourneyLeg[] {train, bus}));
    assertNull(MobillyShortcut.nextKnownVehicle(new TransitJourneyLeg[] {train}));
  }
}
