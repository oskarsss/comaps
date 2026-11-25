package app.organicmaps.transit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks a short, varied list of journeys around the fastest one: the fastest itself, one with fewer
 * boardings, ones on other lines (e.g. a trolleybus instead of a tram) when they arrive within
 * {@link #SLACK} of it, and then the next departures. Rides are compared by trip, so the same
 * vehicles reached by a different walk count as one journey.
 */
final class RigaJourneyAlternatives
{
  static final long SLACK = 15 * 60000L;
  static final int DEFAULT_COUNT = 5;

  interface Planner
  {
    /** Returns null when nothing (or nothing verifiable in time) is found. */
    RigaJourneyPlanner.Journey plan(long departure, RigaJourneyPlanner.Options options);
  }

  static List<RigaJourneyPlanner.Journey> choose(RigaJourneyPlanner.Journey best, long departure, int count,
                                                 Planner planner)
  {
    List<RigaJourneyPlanner.Journey> chosen = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    add(chosen, seen, best, Long.MAX_VALUE);
    long latest = best.arrival + SLACK;
    if (best.boardings > 1 && chosen.size() < count)
      add(chosen, seen, planner.plan(departure, new RigaJourneyPlanner.Options(
          java.util.Collections.emptySet(), best.boardings - 1)), latest);
    // Less walking: no single walk longer than half the longest one of the fastest journey.
    int longest = longestWalk(best);
    if (walking(best) > LOT_OF_WALKING && chosen.size() < count)
    {
      RigaJourneyPlanner.Journey gentle = planner.plan(departure, new RigaJourneyPlanner.Options(
          java.util.Collections.emptySet(), RigaJourneyPlanner.MAX_BOARDINGS, Math.max(MIN_WALK, longest / 2)));
      if (gentle != null && walking(gentle) < walking(best)) add(chosen, seen, gentle, latest);
    }
    // Other lines: exclude every line already offered, twice, so a tram trip can yield a trolleybus
    // and then a bus alternative.
    Set<String> excluded = new LinkedHashSet<>(routes(best));
    for (int i = 0; i < 2 && chosen.size() < count; ++i)
    {
      RigaJourneyPlanner.Journey other = planner.plan(departure,
          new RigaJourneyPlanner.Options(new HashSet<>(excluded), RigaJourneyPlanner.MAX_BOARDINGS));
      if (other == null) break;
      add(chosen, seen, other, latest);
      excluded.addAll(routes(other));
    }
    later(chosen, seen, best, count, planner);
    chosen = undominated(chosen);
    chosen.sort(ORDER);
    return chosen;
  }

  /** Each boarding counts as this much later arrival: a change is worth it only when it saves more. */
  static final long BOARDING_COST = 3 * 60000L;
  /** Each minute on foot counts as a third of a minute later arrival. */
  static final int WALK_COST_DIVISOR = 3;

  /**
   * How good a journey is, as an arrival time: the arrival (to the minute) plus {@link #BOARDING_COST} per
   * boarding and a share of the time on foot. A direct ride arriving a minute or two after a journey with a
   * change ranks first, as does one walking far less for a little later arrival.
   */
  static long cost(RigaJourneyPlanner.Journey journey)
  {
    return minute(journey.arrival) * 60000L + journey.boardings * BOARDING_COST
        + walkingMillis(journey) / WALK_COST_DIVISOR;
  }

  static long walkingMillis(RigaJourneyPlanner.Journey journey)
  {
    long millis = 0;
    for (RigaJourneyPlanner.Leg leg : journey.legs) if (leg.trip == null) millis += leg.arrival - leg.departure;
    return millis;
  }

  /** Recommended first: the lowest {@link #cost}; for the same, leaving last, then fewer boardings. */
  static final Comparator<RigaJourneyPlanner.Journey> ORDER =
      Comparator.comparingLong(RigaJourneyAlternatives::cost)
          .thenComparingLong(j -> -leave(j)).thenComparingInt(j -> j.boardings);

  /**
   * Drops a journey when another one leaves no earlier, arrives no later, boards no more often and walks
   * no further: the earliest-arrival search can return an early start that only waits or rides around.
   */
  static List<RigaJourneyPlanner.Journey> undominated(List<RigaJourneyPlanner.Journey> journeys)
  {
    List<RigaJourneyPlanner.Journey> kept = new ArrayList<>();
    for (RigaJourneyPlanner.Journey j : journeys)
    {
      boolean dominated = false;
      for (RigaJourneyPlanner.Journey k : journeys)
      {
        if (k == j || minute(k.arrival) > minute(j.arrival) || leave(k) < leave(j) || k.boardings > j.boardings
            || walking(k) > walking(j)) continue;
        if (minute(k.arrival) < minute(j.arrival) || leave(k) > leave(j) || k.boardings < j.boardings
            || walking(k) < walking(j))
        {
          dominated = true;
          break;
        }
      }
      if (!dominated) kept.add(j);
    }
    return kept;
  }

  /** Walking worth offering an alternative to, and the shortest walk the alternative may still need. */
  static final int LOT_OF_WALKING = 500;
  static final int MIN_WALK = 200;

  static int walking(RigaJourneyPlanner.Journey journey)
  {
    int metres = 0;
    for (RigaJourneyPlanner.Leg leg : journey.legs) if (leg.trip == null) metres += leg.walkingMetres;
    return metres;
  }

  private static int longestWalk(RigaJourneyPlanner.Journey journey)
  {
    int longest = 0;
    for (RigaJourneyPlanner.Leg leg : journey.legs) if (leg.trip == null) longest = Math.max(longest, leg.walkingMetres);
    return longest;
  }

  /** Departures closer than this are not told apart by the arrive-by search. */
  static final long RESOLUTION = 2 * 60000L;

  /**
   * The journey that leaves last, between {@code low} and {@code high}, and still arrives by {@code arriveBy};
   * null when none does. A later departure never arrives earlier, so the search halves the interval.
   */
  static RigaJourneyPlanner.Journey latest(Planner planner, long low, long high, long arriveBy,
                                           RigaJourneyPlanner.Options options)
  {
    RigaJourneyPlanner.Journey best = planner.plan(low, options);
    if (!arrivesBy(best, high, arriveBy)) return null;
    long from = Math.max(low, leave(best));
    long to = Math.min(high, arriveBy);
    while (to - from > RESOLUTION)
    {
      long middle = from + (to - from) / 2;
      RigaJourneyPlanner.Journey journey = planner.plan(middle, options);
      if (arrivesBy(journey, high, arriveBy))
      {
        best = journey;
        from = Math.max(middle, leave(journey));
      }
      else to = middle;
    }
    return best;
  }

  private static boolean arrivesBy(RigaJourneyPlanner.Journey journey, long high, long arriveBy)
  {
    return journey != null && journey.boardings > 0 && journey.arrival <= arriveBy && leave(journey) <= high;
  }

  /**
   * Arrive-by choices: the journey leaving last that arrives by {@code arriveBy}, one with fewer boardings
   * and ones on other lines when they leave at most {@link #SLACK} earlier, then earlier departures. The
   * latest departure comes first.
   */
  static List<RigaJourneyPlanner.Journey> chooseArriveBy(long low, long arriveBy, int count, Planner planner)
  {
    List<RigaJourneyPlanner.Journey> chosen = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    RigaJourneyPlanner.Journey best = latest(planner, low, arriveBy, arriveBy, RigaJourneyPlanner.Options.NONE);
    if (best == null) return chosen;
    add(chosen, seen, best, arriveBy);
    long from = Math.max(low, leave(best) - SLACK);
    if (best.boardings > 1 && chosen.size() < count)
      add(chosen, seen, latest(planner, from, arriveBy, arriveBy, new RigaJourneyPlanner.Options(
          java.util.Collections.emptySet(), best.boardings - 1)), arriveBy);
    if (walking(best) > LOT_OF_WALKING && chosen.size() < count)
    {
      RigaJourneyPlanner.Journey gentle = latest(planner, from, arriveBy, arriveBy, new RigaJourneyPlanner.Options(
          java.util.Collections.emptySet(), RigaJourneyPlanner.MAX_BOARDINGS, Math.max(MIN_WALK, longestWalk(best) / 2)));
      if (gentle != null && walking(gentle) < walking(best)) add(chosen, seen, gentle, arriveBy);
    }
    Set<String> excluded = new LinkedHashSet<>(routes(best));
    for (int i = 0; i < 2 && chosen.size() < count; ++i)
    {
      RigaJourneyPlanner.Journey other = latest(planner, from, arriveBy, arriveBy,
          new RigaJourneyPlanner.Options(new HashSet<>(excluded), RigaJourneyPlanner.MAX_BOARDINGS));
      if (other == null) break;
      add(chosen, seen, other, arriveBy);
      excluded.addAll(routes(other));
    }
    earlier(chosen, seen, low, arriveBy, count, planner);
    chosen.sort(ARRIVE_ORDER);
    return chosen;
  }

  /** Arrive-by order: leaving last first, a change or a long walk counting as leaving that much earlier. */
  static final Comparator<RigaJourneyPlanner.Journey> ARRIVE_ORDER = Comparator.comparingLong(
          (RigaJourneyPlanner.Journey j) -> -leave(j) + j.boardings * BOARDING_COST + walkingMillis(j) / WALK_COST_DIVISOR)
      .thenComparingLong(j -> -leave(j)).thenComparingLong(j -> j.arrival).thenComparingInt(j -> j.boardings);

  /** Departures before the earliest listed one that still arrive by {@code arriveBy}. */
  static void earlier(List<RigaJourneyPlanner.Journey> chosen, Set<String> seen, long low, long arriveBy, int count,
                      Planner planner)
  {
    for (int tries = 0; chosen.size() < count && tries < count + 2; ++tries)
    {
      long first = Long.MAX_VALUE;
      for (RigaJourneyPlanner.Journey journey : chosen) first = Math.min(first, leave(journey));
      long high = first - 60000;
      if (high < low) break;
      RigaJourneyPlanner.Journey journey = latest(planner, low, high, arriveBy, RigaJourneyPlanner.Options.NONE);
      if (journey == null || !seen.add(signature(journey))) break;
      chosen.add(journey);
    }
  }

  /** Next departures after {@code after}, until {@code count} journeys are listed. */
  static void later(List<RigaJourneyPlanner.Journey> chosen, Set<String> seen, RigaJourneyPlanner.Journey after,
                    int count, Planner planner)
  {
    long next = leave(after) + 60000;
    for (int tries = 0; chosen.size() < count && tries < count + 2; ++tries)
    {
      RigaJourneyPlanner.Journey journey = planner.plan(next, RigaJourneyPlanner.Options.NONE);
      if (journey == null) break;
      add(chosen, seen, journey, Long.MAX_VALUE);
      next = Math.max(next, leave(journey)) + 60000;
    }
  }

  /**
   * Adds {@code journey} unless it arrives after {@code latest}. The same rides reached from another stop or by
   * another walk are one journey: the better one ({@link #ORDER}) is kept, in the place of the first.
   */
  private static void add(List<RigaJourneyPlanner.Journey> chosen, Set<String> seen,
                          RigaJourneyPlanner.Journey journey, long latest)
  {
    if (journey == null || journey.boardings == 0 || journey.arrival > latest) return;
    String key = signature(journey);
    if (seen.add(key)) { chosen.add(journey); return; }
    for (int i = 0; i < chosen.size(); ++i)
      if (signature(chosen.get(i)).equals(key) && ORDER.compare(journey, chosen.get(i)) < 0)
        chosen.set(i, journey);
  }

  /**
   * The vehicles a journey rides, by trip: boarding the same vehicles at another stop is the same journey for
   * the rider ("bus 34 then 24"), so where each ride is boarded or left is not part of it.
   */
  static String signature(RigaJourneyPlanner.Journey journey)
  {
    StringBuilder key = new StringBuilder();
    for (RigaJourneyPlanner.Leg leg : journey.legs)
      if (leg.trip != null) key.append(leg.trip.id).append(';');
    return key.toString();
  }

  private static Set<String> routes(RigaJourneyPlanner.Journey journey)
  {
    Set<String> routes = new HashSet<>();
    for (RigaJourneyPlanner.Leg leg : journey.legs)
      if (leg.trip != null) routes.add(leg.trip.routeId);
    return routes;
  }

  /** Arrivals are shown, and so compared, to the minute. */
  static long minute(long time) { return Math.floorDiv(time + 59999, 60000L); }

  /** When the rider has to set off: the first ride's departure minus the walk to it. */
  static long leave(RigaJourneyPlanner.Journey journey)
  {
    long walk = 0;
    for (RigaJourneyPlanner.Leg leg : journey.legs)
    {
      if (leg.trip != null) return leg.departure - walk;
      walk += leg.arrival - leg.departure;
    }
    return journey.arrival - walk;
  }
}
