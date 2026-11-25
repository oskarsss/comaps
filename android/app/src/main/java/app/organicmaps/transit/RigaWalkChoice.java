package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Where walking the whole way goes among the planned journeys. It leads (and is chosen) when it arrives no later
 * than any ride (leaving at, or arriving by, the chosen time); a short walk that leads leaves no ride worth
 * offering. Otherwise, when it is not too long, it goes before the first ride it beats (one arriving later, or
 * when arriving by a time, one leaving earlier), so later buses that walking would beat never push it back.
 */
public final class RigaWalkChoice
{
  /** A walk at most this long that gets there first is the only answer. */
  static final long SHORT_WALK = 15 * 60000L;
  /** A walk longer than this is offered only when it gets there first. */
  static final long LONG_WALK = 45 * 60000L;
  /** The key of the walking journey among the offered ones (rides are keyed by their trips). */
  public static final String KEY = "walk";

  private RigaWalkChoice() {}

  /** {@code rides} in their order with {@code walk} placed among them; {@code walk} may be null. */
  @NonNull
  static <T> List<T> merge(@NonNull List<T> rides, @Nullable T walk, @NonNull ToLongFunction<T> leave,
                           @NonNull ToLongFunction<T> arrival, boolean arriveBy)
  {
    if (walk == null) return rides;
    List<T> merged = new ArrayList<>();
    long walking = arrival.applyAsLong(walk) - leave.applyAsLong(walk);
    if (wins(rides, walk, leave, arrival, arriveBy))
    {
      merged.add(walk);
      if (walking > SHORT_WALK) merged.addAll(rides);
      return merged;
    }
    merged.addAll(rides);
    if (walking > LONG_WALK) return merged;
    int at = merged.size();
    for (int i = 0; i < rides.size(); ++i)
    {
      T ride = rides.get(i);
      if (arriveBy ? leave.applyAsLong(walk) > leave.applyAsLong(ride)
                   : arrival.applyAsLong(walk) < arrival.applyAsLong(ride))
      {
        at = i;
        break;
      }
    }
    merged.add(at, walk);
    return merged;
  }

  /**
   * Leaving at a time: walking arrives no later than every ride. Arriving by a time (the walk is timed to arrive
   * then): walking leaves no earlier than every ride.
   */
  static <T> boolean wins(@NonNull List<T> rides, @NonNull T walk, @NonNull ToLongFunction<T> leave,
                          @NonNull ToLongFunction<T> arrival, boolean arriveBy)
  {
    for (T ride : rides)
    {
      if (arriveBy ? leave.applyAsLong(walk) < leave.applyAsLong(ride)
                   : arrival.applyAsLong(walk) > arrival.applyAsLong(ride))
        return false;
    }
    return true;
  }
}
