package app.organicmaps.transit;

import androidx.annotation.NonNull;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole journey as one progress bar for the trip notification: a segment per leg, sized by its time (waits
 * belong to the leg before them), coloured by its line (walks grey), with points where rides start and end.
 * Values are seconds since the first leg starts.
 */
final class RigaTripProgress
{
  static final int WALK_COLOR = 0xff9e9e9e;

  static final class Segment
  {
    final int length;
    final int color;
    Segment(int length, int color) { this.length = length; this.color = color; }
  }

  static final class Point
  {
    final int position;
    final int color;
    Point(int position, int color) { this.position = position; this.color = color; }
  }

  final List<Segment> segments = new ArrayList<>();
  final List<Point> points = new ArrayList<>();
  final int max;
  final int progress;

  private RigaTripProgress(int max, int progress)
  {
    this.max = max;
    this.progress = progress;
  }

  @NonNull
  static RigaTripProgress of(@NonNull TransitJourneyLeg[] legs, @NonNull RigaTripGuide.State state, long now)
  {
    if (legs.length == 0) return new RigaTripProgress(1, 1);
    long start = legs[0].stops[0].departure;
    int[] offsets = new int[legs.length + 1];
    for (int i = 0; i < legs.length; ++i)
    {
      long end = i + 1 < legs.length ? legs[i + 1].stops[0].departure : last(legs[i]).arrival;
      // At least one second, so every leg stays visible even when the data has it take no time.
      offsets[i + 1] = Math.max(offsets[i] + 1, seconds(end - start));
    }
    int max = offsets[legs.length];
    RigaTripProgress result = new RigaTripProgress(max, position(legs, offsets, state, now, start));
    for (int i = 0; i < legs.length; ++i)
    {
      TransitJourneyLeg leg = legs[i];
      int color = !leg.isRide() || leg.color == 0 ? WALK_COLOR : leg.color;
      result.segments.add(new Segment(offsets[i + 1] - offsets[i], color));
      if (!leg.isRide()) continue;
      int alight = Math.min(offsets[i + 1], Math.max(offsets[i], seconds(last(leg).arrival - start)));
      addPoint(result.points, offsets[i], color);
      if (alight < max) addPoint(result.points, alight, color);
    }
    return result;
  }

  private static int position(TransitJourneyLeg[] legs, int[] offsets, RigaTripGuide.State state, long now,
                              long start)
  {
    int max = offsets[legs.length];
    if (state.phase == RigaTripGuide.Phase.ARRIVED || state.leg < 0) return max;
    int i = state.leg;
    int from = offsets[i], to = offsets[i + 1];
    switch (state.phase)
    {
    case LEAVE: return 0;
    case WAIT: return from;
    case WALK:
    case STAY: return clamp(seconds(now - start), from, to);
    default:
    {
      // On a ride: from the last stop passed, by the clock, so a late vehicle never shows ahead of where it is.
      TransitJourneyLeg leg = legs[i];
      int passed = Math.max(0, Math.min(leg.stops.length - 2, leg.stops.length - 1 - state.stopsLeft));
      long stopStart = leg.stops[passed].departure;
      long stopEnd = leg.stops[passed + 1].arrival;
      int base = clamp(seconds(stopStart - start), from, to);
      int next = clamp(seconds(stopEnd - start), base, to);
      return clamp(base + seconds(now - stopStart), base, next);
    }
    }
  }

  private static void addPoint(List<Point> points, int position, int color)
  {
    for (Point point : points) if (point.position == position) return;
    points.add(new Point(position, color));
  }

  private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }

  private static int seconds(long millis) { return (int) Math.max(0, Math.min(Integer.MAX_VALUE / 2, millis / 1000)); }

  private static TransitJourneyLeg.Stop last(TransitJourneyLeg leg) { return leg.stops[leg.stops.length - 1]; }
}
