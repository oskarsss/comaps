package app.organicmaps.transit;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Other vehicles that make the same ride: from the same boarding stop to the same exit stop, in the same mode
 * (tram 7 and tram 14 between Mazā Kalna iela and Dienvidu tilts). The rider can take whichever comes first, so
 * the lines are shown together ("7/14") and the other departures as "Also in 2 min, 15 min". A scan of the
 * departure-sorted connections already built for planning; no walk is routed.
 */
final class RigaSameRides
{
  /** Departures this long after the planned one are still offered. */
  static final long WINDOW = 20 * 60000L;
  /** How many other departures are listed. */
  static final int TIMES = 3;
  /** Stops scanned after the boarding stop for the exit stop. */
  private static final int REACH = 80;

  /** One ride's group: its line first, then the other lines; the other departures, earliest first. */
  static final class Group
  {
    static final Group NONE = new Group(new ArrayList<>(), new long[0], new String[0]);
    /** Line numbers serving the ride, the planned one first. */
    @NonNull final List<String> lines;
    /** Other departures from the boarding stop (epoch ms), and the line of each. */
    @NonNull final long[] also;
    @NonNull final String[] alsoLines;
    Group(@NonNull List<String> lines, @NonNull long[] also, @NonNull String[] alsoLines)
    {
      this.lines = lines;
      this.also = also;
      this.alsoLines = alsoLines;
    }
  }

  private RigaSameRides() {}

  /**
   * One group per leg of {@code journey} (walks get {@link Group#NONE}).
   * @param now the current time: a departure the rider can no longer reach is not offered.
   */
  @NonNull
  static Group[] of(@NonNull RigaJourneyPlanner.Timetable data, @NonNull RigaJourneyPlanner.Journey journey, long now)
  {
    Group[] groups = new Group[journey.legs.size()];
    java.util.Arrays.fill(groups, Group.NONE);
    RigaJourneyPlanner.ConnectionIndex index;
    try
    {
      // The planner's own index (cached for this trip list); 2 extra stops for the journey's end points.
      index = RigaJourneyPlanner.ConnectionIndex.of(data.trips, data.stops.size() + 2,
                                                     RigaJourneyPlanner.DEFAULT_LIMITS, () -> false);
    }
    catch (RuntimeException e)
    {
      return groups;
    }
    long walked = 0;
    boolean ridden = false;
    for (int i = 0; i < groups.length; ++i)
    {
      RigaJourneyPlanner.Leg leg = journey.legs.get(i);
      if (leg.trip == null)
      {
        if (!ridden) walked += leg.arrival - leg.departure;
        continue;
      }
      // The rider reaches the stop: the first walk from now (or the chosen departure), later ones as planned.
      long earliest = ridden ? journey.legs.get(i - 1).arrival : Math.max(now, journey.departure) + walked;
      ridden = true;
      groups[i] = group(data, index, leg, earliest, leg.departure + WINDOW);
    }
    return groups;
  }

  @NonNull
  static Group group(@NonNull RigaJourneyPlanner.Timetable data, @NonNull RigaJourneyPlanner.ConnectionIndex index,
                     @NonNull RigaJourneyPlanner.Leg ride, long earliest, long latest)
  {
    List<String> lines = new ArrayList<>();
    lines.add(ride.trip.routeName);
    List<long[]> found = new ArrayList<>(); // {departure, line index}
    for (int c = index.lowerBound(earliest); c < index.size && index.departure[c] <= latest; ++c)
    {
      RigaJourneyPlanner.Trip trip = data.trips.get(index.trip[c]);
      if (trip.type != ride.trip.type) continue;
      int e = index.event[c];
      RigaJourneyPlanner.Event board = trip.events.get(e);
      if (!board.pickup || !same(data, board.stop, ride.from)) continue;
      // The planned vehicle itself (its times may carry a live delay, so compare by trip).
      if (trip.id.equals(ride.trip.id) && trip.serviceOrigin == ride.trip.serviceOrigin) continue;
      if (!reaches(data, trip, e, ride.to)) continue;
      int line = lines.indexOf(trip.routeName);
      if (line < 0) { line = lines.size(); lines.add(trip.routeName); }
      found.add(new long[] {board.departure, line});
    }
    found.sort((a, b) -> Long.compare(a[0], b[0]));
    int count = Math.min(TIMES, found.size());
    long[] also = new long[count];
    String[] alsoLines = new String[count];
    for (int k = 0; k < count; ++k)
    {
      also[k] = found.get(k)[0];
      alsoLines[k] = lines.get((int) found.get(k)[1]);
    }
    return new Group(lines, also, alsoLines);
  }

  private static boolean reaches(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Trip trip, int from, int stop)
  {
    for (int k = from + 1; k < trip.events.size() && k <= from + REACH; ++k)
    {
      RigaJourneyPlanner.Event event = trip.events.get(k);
      if (same(data, event.stop, stop)) return event.dropOff;
    }
    return false;
  }

  /** The same stop, or a platform of the same station. */
  private static boolean same(RigaJourneyPlanner.Timetable data, int a, int b)
  {
    if (a == b) return true;
    if (a < 0 || b < 0 || a >= data.stops.size() || b >= data.stops.size()) return false;
    String parent = data.stops.get(a).parent;
    return parent != null && !parent.isEmpty() && parent.equals(data.stops.get(b).parent);
  }

  /** "7/14": the lines of a group as one badge, at most {@code max} of them. */
  @NonNull
  static String badge(@NonNull Group group, @NonNull String fallback, int max)
  {
    if (group.lines.size() <= 1) return fallback;
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < group.lines.size() && i < max; ++i)
    {
      if (i > 0) text.append('/');
      text.append(group.lines.get(i));
    }
    if (group.lines.size() > max) text.append("/…");
    return text.toString();
  }
}
