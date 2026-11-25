package app.organicmaps.transit;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks as the rider experiences them. The planner chains footpaths stop by stop, and its earliest-arrival
 * search starts the walk to the first ride at the search time; shown as is, that reads as several walks in a
 * row followed by a wait (at night, of hours) at the stop. Here consecutive walks become one, from where the
 * first starts to where the last ends, with their map lines joined, and the walk to the first ride ends as
 * that ride is due, so the journey starts when the rider has to leave ({@link RigaJourneyAlternatives#leave}).
 */
public final class RigaWalks
{
  /** A walk between two rides shorter than this is a change of vehicle, not a step of its own in summaries. */
  static final long SHORT_CHANGE = 120000L;

  private RigaWalks() {}

  /** True for a walk between two rides shorter than {@link #SHORT_CHANGE}: shown as "7 > 49", not as a step. */
  public static boolean shortChange(@NonNull app.organicmaps.sdk.routing.TransitJourneyLeg[] legs, int i)
  {
    if (i <= 0 || i + 1 >= legs.length || !legs[i].isWalking()) return false;
    if (!legs[i - 1].isRide() || !legs[i + 1].isRide()) return false;
    app.organicmaps.sdk.routing.TransitJourneyLeg.Stop[] stops = legs[i].stops;
    return stops.length >= 2 && stops[stops.length - 1].arrival - stops[0].departure < SHORT_CHANGE;
  }

  /** The route as presented: legs, map lines and walking flags tidied together, so their indices still agree. */
  @NonNull
  static RigaJourneyService.Result tidy(@NonNull RigaJourneyService.Result route)
  {
    List<RigaJourneyPlanner.Leg> source = route.journey.legs;
    boolean drawn = route.lines.length == source.size();
    List<RigaJourneyPlanner.Leg> legs = new ArrayList<>();
    List<double[]> lines = new ArrayList<>();
    int firstRide = -1;
    for (int i = 0; i < source.size(); ++i)
    {
      RigaJourneyPlanner.Leg leg = source.get(i);
      int last = legs.size() - 1;
      if (leg.trip == null && last >= 0 && legs.get(last).trip == null)
      {
        RigaJourneyPlanner.Leg previous = legs.get(last);
        legs.set(last, new RigaJourneyPlanner.Leg(previous.from, leg.to, previous.departure, leg.arrival, null, -1, -1,
            previous.walkingMetres + leg.walkingMetres, null));
        if (drawn) lines.set(last, join(lines.get(last), route.lines[i]));
        continue;
      }
      if (leg.trip != null && firstRide < 0) firstRide = legs.size();
      legs.add(leg);
      if (drawn) lines.add(route.lines[i]);
    }
    if (firstRide == 1)
    {
      RigaJourneyPlanner.Leg access = legs.get(0);
      long shift = legs.get(1).departure - access.arrival;
      if (shift > 0)
        legs.set(0, new RigaJourneyPlanner.Leg(access.from, access.to, access.departure + shift, access.arrival + shift,
            null, -1, -1, access.walkingMetres, null));
    }
    if (legs.size() == source.size() && firstRide != 1) return route;
    boolean[] walking = new boolean[drawn ? legs.size() : 0];
    for (int i = 0; i < walking.length; ++i) walking[i] = legs.get(i).trip == null;
    return route.reshaped(new RigaJourneyPlanner.Journey(route.journey.departure, legs, route.journey.boardings),
                          drawn ? lines.toArray(new double[0][]) : route.lines, walking);
  }

  /** One polyline (lat, lon pairs) from two, without repeating the point where they meet. */
  @NonNull
  static double[] join(@NonNull double[] a, @NonNull double[] b)
  {
    int skip = a.length >= 2 && b.length >= 2 && a[a.length - 2] == b[0] && a[a.length - 1] == b[1] ? 2 : 0;
    double[] joined = new double[a.length + b.length - skip];
    System.arraycopy(a, 0, joined, 0, a.length);
    System.arraycopy(b, skip, joined, a.length, b.length - skip);
    return joined;
  }
}
