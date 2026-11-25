package app.organicmaps.transit;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import app.organicmaps.sdk.routing.TransitStepInfo;
import app.organicmaps.sdk.routing.TransitStepType;
import java.util.ArrayList;
import java.util.List;

/**
 * A public transport journey through stops where the rider spends time: each part (start to the first stop, stop
 * to stop, last stop to the destination) is planned on its own, leaving when the stay before it ends, and the
 * parts are joined with a stay leg ({@link TransitJourneyLeg#isStay()}) between them.
 */
final class RigaMultiStop
{
  static final int STAY_COLOR = 0xff777777;

  /** A stop of the journey: where it is, its name, and how long the rider stays. */
  static final class Via
  {
    final String title;
    final double lat, lon;
    final long stay;
    Via(String title, double lat, double lon, long stay)
    {
      this.title = title;
      this.lat = lat;
      this.lon = lon;
      this.stay = stay;
    }
  }

  private RigaMultiStop() {}

  /** When the part after {@code via} may leave: the arrival of the part before it plus the stay. */
  static long leaveAfter(long arrival, @NonNull Via via) { return arrival + via.stay; }

  /**
   * One route made of {@code parts} (each a planned route with info and lines) with a stay at {@code vias[i]}
   * between parts i and i+1. The first part's alternatives, trip and start callbacks are not carried over: a
   * multi-stop journey is a single plan.
   */
  @NonNull
  static RoutingController.TransitResult merge(@NonNull List<RoutingController.TransitResult> parts,
                                               @NonNull List<Via> vias)
  {
    if (parts.size() != vias.size() + 1) throw new IllegalArgumentException("A part between every two stops");
    List<TransitJourneyLeg> legs = new ArrayList<>();
    List<TransitStepInfo> steps = new ArrayList<>();
    List<double[]> lines = new ArrayList<>();
    List<Boolean> walking = new ArrayList<>();
    List<Integer> colors = new ArrayList<>();
    List<double[]> coordinates = new ArrayList<>();
    List<String> numbers = new ArrayList<>(), titles = new ArrayList<>();
    List<Integer> vehicleColors = new ArrayList<>();
    StringBuilder instructions = new StringBuilder();
    int walkMetres = 0, walkSeconds = 0;
    long expires = Long.MAX_VALUE;
    for (int p = 0; p < parts.size(); ++p)
    {
      RoutingController.TransitResult part = parts.get(p);
      TransitRouteInfo info = part.info;
      if (p > 0)
      {
        // The stay: from the arrival of the part before to the first departure of this part.
        Via via = vias.get(p - 1);
        long arrive = parts.get(p - 1).info.arrival;
        long leave = info.journeyLegs.length == 0 ? arrive + via.stay : info.journeyLegs[0].stops[0].departure;
        legs.add(new TransitJourneyLeg(TransitStepType.INTERMEDIATE_POINT, "", via.title, STAY_COLOR, 0, false,
            new TransitJourneyLeg.Stop[] {new TransitJourneyLeg.Stop(via.title, arrive, arrive, via.lat, via.lon),
                new TransitJourneyLeg.Stop(via.title, leave, leave, via.lat, via.lon)}));
        steps.add(new TransitStepInfo(TransitStepType.INTERMEDIATE_POINT.ordinal(), "", "",
            (int) Math.max(0, (leave - arrive) / 1000), "", STAY_COLOR, p - 1));
        lines.add(new double[] {via.lat, via.lon, via.lat, via.lon});
        walking.add(true);
        colors.add(STAY_COLOR);
      }
      for (TransitJourneyLeg leg : info.journeyLegs) legs.add(leg);
      for (TransitStepInfo step : info.getTransitSteps()) steps.add(step);
      for (int i = 0; i < part.lines.length; ++i)
      {
        lines.add(part.lines[i]);
        walking.add(part.walking[i]);
        colors.add(i < part.colors.length ? part.colors[i] : STAY_COLOR);
      }
      walkMetres += parse(info.getTotalPedestrianDistance());
      walkSeconds += info.getTotalPedestrianTimeInSec();
      if (instructions.length() > 0) instructions.append("\n");
      instructions.append(info.instructions);
      for (int v = 0; v < part.vehicleNumbers.length; ++v)
      {
        coordinates.add(new double[] {part.vehicleCoordinates[2 * v], part.vehicleCoordinates[2 * v + 1]});
        numbers.add(part.vehicleNumbers[v]);
        titles.add(part.vehicleTitles[v]);
        vehicleColors.add(part.vehicleColors[v]);
      }
      if (part.vehicleNumbers.length > 0) expires = Math.min(expires, part.vehicleExpires);
    }
    TransitRouteInfo first = parts.get(0).info, last = parts.get(parts.size() - 1).info;
    long leave = legs.isEmpty() ? first.firstDeparture : legs.get(0).stops[0].departure;
    TransitRouteInfo info = new TransitRouteInfo("", "", (int) (Math.round((last.arrival - leave) / 60000.0) * 60),
        Integer.toString(walkMetres), "m", walkSeconds, steps.toArray(new TransitStepInfo[0]));
    info.journeyLegs = legs.toArray(new TransitJourneyLeg[0]);
    info.journeyStatus = first.journeyStatus;
    info.timetableValidity = first.timetableValidity;
    // The first ride of the whole journey (a first part on foot has none); the arrival when no part rides.
    info.firstDeparture = last.arrival;
    for (TransitJourneyLeg leg : legs)
      if (leg.isRide()) { info.firstDeparture = leg.stops[0].departure; break; }
    info.arrival = last.arrival;
    info.description = first.description;
    info.instructions = instructions.toString();
    double[][] lineArray = lines.toArray(new double[0][]);
    boolean[] walkingArray = new boolean[walking.size()];
    for (int i = 0; i < walkingArray.length; ++i) walkingArray[i] = walking.get(i);
    RoutingController.TransitResult result = new RoutingController.TransitResult(info, lineArray, walkingArray);
    result.colors = new int[colors.size()];
    for (int i = 0; i < result.colors.length; ++i) result.colors[i] = colors.get(i);
    if (coordinates.size() > RigaJourneyVehicles.MAX)
    {
      // The native marker cap: the first parts' vehicles matter first.
      coordinates = coordinates.subList(0, RigaJourneyVehicles.MAX);
      numbers = numbers.subList(0, RigaJourneyVehicles.MAX);
      titles = titles.subList(0, RigaJourneyVehicles.MAX);
      vehicleColors = vehicleColors.subList(0, RigaJourneyVehicles.MAX);
    }
    result.vehicleCoordinates = new double[coordinates.size() * 2];
    result.vehicleNumbers = numbers.toArray(new String[0]);
    result.vehicleTitles = titles.toArray(new String[0]);
    result.vehicleColors = new int[vehicleColors.size()];
    for (int i = 0; i < coordinates.size(); ++i)
    {
      result.vehicleCoordinates[2 * i] = coordinates.get(i)[0];
      result.vehicleCoordinates[2 * i + 1] = coordinates.get(i)[1];
      result.vehicleColors[i] = vehicleColors.get(i);
    }
    result.vehicleExpires = expires == Long.MAX_VALUE ? 0 : expires;
    return result;
  }

  private static int parse(String metres)
  {
    try { return Integer.parseInt(metres); }
    catch (NumberFormatException e) { return 0; }
  }
}
