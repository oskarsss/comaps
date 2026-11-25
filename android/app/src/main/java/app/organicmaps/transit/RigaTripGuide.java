package app.organicmaps.transit;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitStepType;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Where the rider is in a started journey and what to do next, from the clock and (when known) the
 * rider's position. Times are the published ones, already moved to live estimates by the provider.
 */
public final class RigaTripGuide
{
  public enum Phase { LEAVE, WALK, WAIT, RIDE, STAY, ARRIVED }

  public static final class State
  {
    @NonNull public final Phase phase;
    /** The leg the phase is about: the walk, or the ride to wait for or sit on; -1 when arrived. */
    public final int leg;
    /** RIDE: stops still to pass before getting off, the exit included. */
    public final int stopsLeft;
    /** LEAVE: when to set off; WALK/WAIT: when the next ride leaves; RIDE: when to get off; STAY: when to go on. */
    public final long at;
    /** The rider is still at the boarding stop after the ride left. */
    public final boolean missed;
    /** Stable key of the alert this state deserves (fired once per trip), or null. */
    @Nullable public final String alert;

    State(@NonNull Phase phase, int leg, int stopsLeft, long at, boolean missed, @Nullable String alert)
    {
      this.phase = phase;
      this.leg = leg;
      this.stopsLeft = stopsLeft;
      this.at = at;
      this.missed = missed;
      this.alert = alert;
    }
  }

  static final double ARRIVED_METRES = 60;
  static final double AT_STOP_METRES = 80;
  static final double ON_ROUTE_METRES = 300;
  /** How far from the straight line between stops a position still caps the clock's count of stops passed. */
  static final double LOOSE_ROUTE_METRES = 1500;
  static final long LEAVE_ALERT = 60000;
  static final long BOARD_ALERT = 120000;
  static final long MISSED_AFTER = 90000;
  /** A rider this close to the boarding stop this long after the departure is not on the vehicle. */
  static final double NOT_BOARDED_METRES = 400;
  static final long NOT_BOARDED_AFTER = 180000;
  /** The get-off alert comes this long before the exit, or as the vehicle leaves the stop before it if sooner. */
  static final long OFF_ALERT = 300000;
  /** The reminder to go on from a stop of the route comes this long before the stay ends. */
  static final long STAY_ALERT = 300000;
  /** How much earlier than planned a vehicle of the same line can be that the rider boards instead. */
  static final long EARLY_RIDE = 20 * 60000;
  /** A ride's times follow the rider's position only when it is this far off the timetable. */
  static final long SHIFT = 120000;

  private RigaTripGuide() {}

  /** @param lat,lon the rider's position, or NaN when unknown. */
  @NonNull
  public static State of(@NonNull TransitJourneyLeg[] legs, long now, double lat, double lon)
  {
    return of(legs, now, lat, lon, 0);
  }

  /**
   * @param reached the furthest leg the rider was already guided on (e.g. by an earlier tick): positions never
   *                take the rider back to a ride before it.
   */
  @NonNull
  public static State of(@NonNull TransitJourneyLeg[] legs, long now, double lat, double lon, int reached)
  {
    return of(legs, now, lat, lon, reached, false);
  }

  /**
   * @param moving the rider has lately been faster than on foot: past the boarding stop on the line, that is
   *               aboard, even on a vehicle earlier than planned (Google's "Departed early").
   */
  @NonNull
  public static State of(@NonNull TransitJourneyLeg[] legs, long now, double lat, double lon, int reached,
                         boolean moving)
  {
    return of(legs, now, lat, lon, reached, moving, -1);
  }

  /**
   * @param rode the furthest ride the rider was already seen aboard, or -1: on an earlier vehicle than planned,
   *             its exit stop ends the ride before the planned one has even left, and the rider is not sent back
   *             to wait for the planned one.
   */
  @NonNull
  public static State of(@NonNull TransitJourneyLeg[] legs, long now, double lat, double lon, int reached,
                         boolean moving, int rode)
  {
    if (legs.length == 0) return new State(Phase.ARRIVED, -1, 0, now, false, null);
    boolean located = Double.isFinite(lat) && Double.isFinite(lon);
    TransitJourneyLeg.Stop destination = last(legs[legs.length - 1]);
    // A stop of the route still ahead: a round trip starts where it ends, and the rider has not arrived there yet.
    int stayAhead = -1;
    for (int i = Math.max(0, reached); i < legs.length && stayAhead < 0; ++i)
      if (legs[i].isStay() && last(legs[i]).departure > now) stayAhead = i;
    boolean staysLeft = false;
    for (int i = Math.max(0, reached); i < legs.length && !staysLeft; ++i) staysLeft = legs[i].isStay();
    if (located && !staysLeft && metres(lat, lon, destination) < ARRIVED_METRES)
      return new State(Phase.ARRIVED, -1, 0, now, false, "arrived");
    int firstRide = -1;
    for (int i = 0; i < legs.length && firstRide < 0; ++i) if (legs[i].isRide()) firstRide = i;

    // A ride the rider demonstrably sits on wins over the clock: vehicles run early or late, and the rider may
    // take an earlier vehicle of the same line than planned. Lines often share a road (tram 7 and bus 49 at
    // Dienvidu tilts): where a position fits several rides, the one whose timetable has a vehicle there closest
    // to now wins, and the rider never goes back to a ride before {@code reached}.
    if (located)
    {
      int bestLeg = -1, bestPassed = 0;
      double bestFraction = 0;
      long bestScore = Long.MAX_VALUE;
      for (int i = Math.max(0, reached); i < legs.length; ++i)
      {
        TransitJourneyLeg leg = legs[i];
        long departure = leg.stops[0].departure;
        // Rides after a stay that has not ended are not taken yet, even where the stop lies on their line.
        if (stayAhead >= 0 && i > stayAhead) break;
        if (!leg.isRide() || now < departure - EARLY_RIDE || now > last(leg).arrival + 600000) continue;
        boolean early = now < departure - 30000;
        double[] place = place(leg, lat, lon);
        if (place == null) continue;
        int passed = (int) place[0];
        int end = leg.stops.length - 1;
        long expected;
        if (passed == end)
        {
          // At the exit stop the ride is done whatever the clock says (the vehicle ran early).
          if ((early && i > rode) || i + 1 >= legs.length) continue;
          expected = last(leg).arrival;
        }
        else
        {
          // At or near the boarding stop is waiting; an earlier vehicle only counts once past the first stop.
          boolean onBoard = passed >= 1
              || ((!early || moving) && place[1] > 0 && metres(lat, lon, leg.stops[0]) > 2 * AT_STOP_METRES);
          if (!onBoard) continue;
          expected = expected(leg, passed, place[1]);
        }
        long score = Math.abs(now - expected);
        if (score < bestScore) { bestScore = score; bestLeg = i; bestPassed = passed; bestFraction = place[1]; }
      }
      if (bestLeg >= 0)
      {
        TransitJourneyLeg leg = legs[bestLeg];
        if (bestPassed < leg.stops.length - 1)
          return ride(legs, bestLeg, bestPassed, now, expected(leg, bestPassed, bestFraction));
        int following = bestLeg + 1;
        if (legs[following].isStay()) return stay(legs, following, now);
        if (legs[following].isRide()) return wait(legs, following, now, lat, lon, true);
        // Off the ride and already at the stop of the route the walk leads to: the stay has begun.
        if (following + 1 < legs.length && legs[following + 1].isStay()
            && metres(lat, lon, legs[following + 1].stops[0]) < ARRIVED_METRES)
          return stay(legs, following + 1, now);
        int next = following + 1 < legs.length && legs[following + 1].isRide() ? following + 1 : -1;
        // Off the ride and already at the next ride's stop (a short change): waiting, not walking.
        if (next >= 0 && metres(lat, lon, legs[next].stops[0]) < AT_STOP_METRES)
          return wait(legs, next, now, lat, lon, true);
        return new State(Phase.WALK, following, 0,
            next >= 0 ? legs[next].stops[0].departure : last(legs[following]).arrival, false, null);
      }
    }

    int current = -1;
    for (int i = Math.max(0, Math.min(reached, legs.length - 1)); i < legs.length && current < 0; ++i)
      if (last(legs[i]).arrival > now) current = i;
    if (current < 0)
    {
      // Past the planned arrival: keep guiding the final walk until the rider is there.
      TransitJourneyLeg final_ = legs[legs.length - 1];
      if (located && final_.isWalking() && now <= destination.arrival + 1800000)
        return new State(Phase.WALK, legs.length - 1, 0, destination.arrival, false, null);
      return new State(Phase.ARRIVED, -1, 0, now, false, "arrived");
    }
    // Aboard an earlier vehicle than planned, yet no longer placed on its line (off it, or past its exit
    // between two ticks): the planned vehicle has not even left, so the clock's "wait for it" is wrong. Loosely
    // on the line the ride goes on; otherwise it is over and the rider goes on to the next leg.
    if (current == rode && current + 1 < legs.length && legs[current].isRide()
        && now < legs[current].stops[0].departure)
    {
      double[] loose = located ? place(legs[current], lat, lon, LOOSE_ROUTE_METRES) : null;
      if (loose == null || (int) loose[0] >= legs[current].stops.length - 1) ++current;
      else return ride(legs, current, (int) loose[0], now, expected(legs[current], (int) loose[0], loose[1]));
    }
    TransitJourneyLeg leg = legs[current];
    if (leg.isStay())
    {
      // The stay began by the clock, but the rider is not there yet (a slow walk): still walking to it.
      TransitJourneyLeg before = current > 0 ? legs[current - 1] : null;
      if (located && reached < current && before != null && before.isWalking()
          && metres(lat, lon, leg.stops[0]) > 2 * AT_STOP_METRES)
        return new State(Phase.WALK, current - 1, 0, last(before).arrival, false, null);
      return stay(legs, current, now);
    }
    if (leg.isWalking())
    {
      // Already at a stop of the route, early: the stay has begun.
      if (current + 1 < legs.length && legs[current + 1].isStay() && located
          && metres(lat, lon, legs[current + 1].stops[0]) < ARRIVED_METRES)
        return stay(legs, current + 1, now);
      int next = current + 1 < legs.length && legs[current + 1].isRide() ? current + 1 : -1;
      if (next >= 0 && located && metres(lat, lon, legs[next].stops[0]) < AT_STOP_METRES)
        return wait(legs, next, now, lat, lon, located);
      if (current < firstRide && now < leg.stops[0].departure)
      {
        boolean soon = leg.stops[0].departure - now <= LEAVE_ALERT;
        return new State(Phase.LEAVE, current, 0, leg.stops[0].departure, false, soon ? "leave" : null);
      }
      boolean leaving = current < firstRide && now - leg.stops[0].departure <= 2 * LEAVE_ALERT;
      return new State(Phase.WALK, current, 0, next >= 0 ? legs[next].stops[0].departure : last(leg).arrival,
          false, leaving ? "leave" : null);
    }
    if (now < leg.stops[0].departure) return wait(legs, current, now, lat, lon, located);
    // Still at the stop well after the vehicle left, or still near it minutes later (a vehicle would be far by
    // then): the rider missed it, whatever the clock says about the ride.
    double fromStop = located ? metres(lat, lon, leg.stops[0]) : Double.MAX_VALUE;
    long sinceDeparture = now - leg.stops[0].departure;
    // The live feed still has the vehicle stops before the boarding stop: it has not come yet, whatever the time.
    boolean coming = leg.vehicleStopsAway >= 1;
    // Still at the stop as the departure passes: the vehicle is due, not ridden (aboard, the rider leaves the stop
    // with it and the position above says so). wait() tells the rider it was missed once it should be well gone.
    if (fromStop < AT_STOP_METRES || (coming && fromStop < NOT_BOARDED_METRES))
      return wait(legs, current, now, lat, lon, located);
    if (fromStop < NOT_BOARDED_METRES && sinceDeparture > NOT_BOARDED_AFTER)
      return new State(Phase.WAIT, current, 0, leg.stops[0].departure, true, "missed:" + current);
    int passed = passedByTime(leg, now);
    if (located)
    {
      // The line between stops is straight while the road curves, so a rider on board can be further from it
      // than ON_ROUTE_METRES. Loosely placed, the position still caps how far the clock may count the ride:
      // a late vehicle must not announce "next stop" while the rider is two stops away.
      double[] loose = place(leg, lat, lon, LOOSE_ROUTE_METRES);
      if (loose != null) passed = Math.min(passed, (int) loose[0]);
    }
    return ride(legs, current, passed, now, 0);
  }

  /** One line telling the rider what to do now. */
  @NonNull
  public static String headline(@NonNull Context context, @NonNull TransitJourneyLeg[] legs, @NonNull State state,
                                long now)
  {
    if (state.phase == Phase.ARRIVED) return context.getString(R.string.riga_trip_arrived);
    TransitJourneyLeg leg = legs[state.leg];
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(RigaTransitStore.ZONE);
    String at = clock.format(new Date(state.at));
    switch (state.phase)
    {
    case LEAVE:
    {
      long minutes = Math.max(1, (state.at - now + 59999) / 60000);
      String stop = last(leg).name;
      return minutes <= 1 ? context.getString(R.string.riga_trip_leave_now, stop)
          : context.getResources().getQuantityString(R.plurals.riga_trip_leave_in, (int) minutes, (int) minutes, stop);
    }
    case WALK:
    {
      TransitJourneyLeg next = state.leg + 1 < legs.length && legs[state.leg + 1].isRide() ? legs[state.leg + 1] : null;
      if (next == null) return context.getString(R.string.riga_trip_walk_to, last(leg).name);
      return context.getString(R.string.riga_trip_walk_to_ride, next.stops[0].name, mode(context, next.type),
          next.number, at);
    }
    case WAIT:
      if (state.missed) return context.getString(R.string.riga_trip_missed, mode(context, leg.type), leg.number);
      return context.getString(R.string.riga_trip_board, mode(context, leg.type), leg.number, leg.headsign, at);
    case STAY:
    {
      TransitJourneyLeg ride = rideAfterStay(legs, state.leg);
      if (ride == null) return context.getString(R.string.riga_trip_stay, leg.headsign, at);
      return context.getString(R.string.riga_trip_stay_ride, leg.headsign, at, lower(mode(context, ride.type)),
          ride.number);
    }
    default:
      return context.getResources().getQuantityString(R.plurals.riga_trip_ride, state.stopsLeft, state.stopsLeft,
          last(leg).name, at);
    }
  }

  /**
   * The line under the headline: the ride the step is about ("Trolleybus 22 → Lubāna, arrive 05:25"), or
   * only the arrival when no ride is ahead.
   */
  @NonNull
  public static String context(@NonNull Context context, @NonNull TransitJourneyLeg[] legs, @NonNull State state)
  {
    if (legs.length == 0 || state.phase == Phase.ARRIVED) return "";
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(RigaTransitStore.ZONE);
    String arrive = clock.format(new Date(last(legs[legs.length - 1]).arrival));
    TransitJourneyLeg ride = null;
    for (int i = Math.max(0, state.leg); i < legs.length && ride == null; ++i) if (legs[i].isRide()) ride = legs[i];
    if (ride == null) return context.getString(R.string.riga_live_arrive, arrive);
    return context.getString(R.string.riga_trip_context, mode(context, ride.type), ride.number, ride.headsign, arrive);
  }

  /** Live Update title: the one thing to do now ("Leave in 4 min", "Take tram 7", "Get off in 2 stops"). */
  @NonNull
  public static String liveTitle(@NonNull Context context, @NonNull TransitJourneyLeg[] legs, @NonNull State state,
                                 long now)
  {
    if (state.phase == Phase.ARRIVED) return context.getString(R.string.riga_trip_arrived);
    TransitJourneyLeg leg = legs[state.leg];
    switch (state.phase)
    {
    case LEAVE:
    {
      long minutes = Math.max(1, (state.at - now + 59999) / 60000);
      return minutes <= 1 ? context.getString(R.string.riga_trip_live_leave_now)
          : context.getResources().getQuantityString(R.plurals.riga_trip_live_leave_in, (int) minutes, (int) minutes);
    }
    case WALK: return context.getString(R.string.riga_trip_live_walk, last(leg).name);
    case WAIT:
      if (state.missed) return context.getString(R.string.riga_trip_missed, mode(context, leg.type), leg.number);
      return context.getString(R.string.riga_trip_live_take, lower(mode(context, leg.type)), leg.number);
    case STAY: return context.getString(R.string.riga_trip_live_stay, leg.headsign);
    default:
      return context.getResources().getQuantityString(R.plurals.riga_trip_live_get_off, state.stopsLeft,
          state.stopsLeft);
    }
  }

  /** Live Update detail line: where and when the step happens, or how much of the ride is left. */
  @NonNull
  public static String liveText(@NonNull Context context, @NonNull TransitJourneyLeg[] legs, @NonNull State state,
                                long now)
  {
    if (state.phase == Phase.ARRIVED) return "";
    TransitJourneyLeg leg = legs[state.leg];
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(RigaTransitStore.ZONE);
    switch (state.phase)
    {
    case LEAVE:
    case WALK:
    {
      TransitJourneyLeg next = state.leg + 1 < legs.length && legs[state.leg + 1].isRide() ? legs[state.leg + 1] : null;
      if (next == null) return context.getString(R.string.riga_live_arrive, clock.format(new Date(last(leg).arrival)));
      return context.getString(R.string.riga_trip_live_next_ride, next.stops[0].name, lower(mode(context, next.type)),
          next.number, clock.format(new Date(next.stops[0].departure)));
    }
    case WAIT:
      // Where the vehicle is now says most; otherwise its direction and departure.
      if (!state.missed && !leg.progress.isEmpty()) return leg.progress;
      return context.getString(R.string.riga_trip_live_departs, leg.headsign, clock.format(new Date(leg.stops[0].departure)));
    case STAY:
    {
      TransitJourneyLeg ride = rideAfterStay(legs, state.leg);
      String at = clock.format(new Date(state.at));
      if (ride == null) return context.getString(R.string.riga_trip_live_stay_leave, at);
      return context.getString(R.string.riga_trip_live_stay_ride, at, lower(mode(context, ride.type)), ride.number);
    }
    default:
    {
      return context.getString(R.string.riga_trip_live_exit, last(leg).name, clock.format(new Date(state.at)));
    }
    }
  }

  /** Status bar chip: minutes to the next departure, or stops left on a ride. Empty when arrived. */
  @NonNull
  public static String chip(@NonNull Context context, @NonNull State state, long now)
  {
    if (state.phase == Phase.ARRIVED || state.missed) return "";
    if (state.phase == Phase.RIDE)
      return context.getResources().getQuantityString(R.plurals.riga_trip_chip_stops, state.stopsLeft, state.stopsLeft);
    long minutes = (state.at - now) / 60000;
    return minutes <= 0 ? context.getString(R.string.riga_trip_chip_now)
        : context.getString(R.string.riga_trip_chip_minutes, (int) minutes);
  }

  private static String lower(String text) { return text.toLowerCase(Locale.getDefault()); }

  /** Text of the alert for {@code state.alert}: for getting off, where and when. */
  @NonNull
  public static String alertText(@NonNull Context context, @NonNull TransitJourneyLeg[] legs, @NonNull State state,
                                 @NonNull String headline)
  {
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(RigaTransitStore.ZONE);
    // The title already says "You have arrived": the text says where and when, not the same words again.
    if (state.alert != null && state.alert.startsWith("arrived") && legs.length > 0)
      return context.getString(R.string.riga_trip_alert_arrived_text, last(legs[legs.length - 1]).name,
          clock.format(new Date(System.currentTimeMillis())));
    if (state.alert == null || !state.alert.startsWith("off")) return headline;
    TransitJourneyLeg.Stop exit = last(legs[state.leg]);
    return context.getString(R.string.riga_trip_alert_off_text, exit.name, clock.format(new Date(exit.arrival)));
  }

  /** Title of the alert for {@code state.alert}. */
  @NonNull
  public static String alertTitle(@NonNull Context context, @NonNull TransitJourneyLeg[] legs, @NonNull State state)
  {
    String key = state.alert == null ? "" : state.alert;
    if (key.startsWith("leave")) return context.getString(R.string.riga_trip_alert_leave);
    if (key.startsWith("board"))
      return context.getString(R.string.riga_trip_alert_board, mode(context, legs[state.leg].type), legs[state.leg].number);
    if (key.startsWith("off")) return context.getString(R.string.riga_trip_alert_off);
    if (key.startsWith("arrived")) return context.getString(R.string.riga_trip_arrived);
    if (key.startsWith("stay")) return context.getString(R.string.riga_trip_alert_stay, legs[state.leg].headsign);
    return context.getString(R.string.riga_trip_replan);
  }

  static String mode(Context context, TransitStepType type)
  {
    return RigaModeStyle.name(context, type);
  }

  /** At a stop of the route until its stay ends; reminded {@link #STAY_ALERT} before it does. */
  private static State stay(TransitJourneyLeg[] legs, int stay, long now)
  {
    long leave = last(legs[stay]).departure;
    return new State(Phase.STAY, stay, 0, leave, false, leave - now <= STAY_ALERT ? "stay:" + stay : null);
  }

  /** The first ride after the stay {@code stay}, before any further stay; null when the next part is on foot. */
  @Nullable
  static TransitJourneyLeg rideAfterStay(TransitJourneyLeg[] legs, int stay)
  {
    for (int i = stay + 1; i < legs.length; ++i)
    {
      if (legs[i].isStay()) return null;
      if (legs[i].isRide()) return legs[i];
    }
    return null;
  }

  private static State wait(TransitJourneyLeg[] legs, int ride, long now, double lat, double lon, boolean located)
  {
    TransitJourneyLeg leg = legs[ride];
    long departure = leg.stops[0].departure;
    boolean missed = located && now - departure > MISSED_AFTER && metres(lat, lon, leg.stops[0]) < AT_STOP_METRES
        && leg.vehicleStopsAway < 1;
    boolean soon = departure - now <= BOARD_ALERT || (leg.vehicleStopsAway >= 0 && leg.vehicleStopsAway <= 1);
    return new State(Phase.WAIT, ride, 0, departure, missed,
        missed ? "missed:" + ride : soon ? "board:" + ride : null);
  }

  /**
   * @param passed the last stop left behind (or stood at)
   * @param expected when the timetable has the vehicle where the rider is (0 when the position is unknown): the
   *                 exit time moves with how early or late the rider's vehicle actually runs.
   */
  private static State ride(TransitJourneyLeg[] legs, int ride, int passed, long now, long expected)
  {
    TransitJourneyLeg leg = legs[ride];
    int left = Math.max(1, leg.stops.length - 1 - passed);
    long exit = last(leg).arrival;
    // Small differences are GPS noise or already in the live times; a vehicle minutes off is another one.
    if (expected > 0 && Math.abs(now - expected) > SHIFT) exit = Math.max(now, exit + now - expected);
    // On a train the last stretch can take many minutes: warn shortly before the exit, not on leaving the stop
    // before it (for a one-stop ride, that would be on boarding).
    long lastStretch = last(leg).arrival - leg.stops[Math.max(0, leg.stops.length - 2)].departure;
    boolean due = left <= 1 && (exit - now <= OFF_ALERT || lastStretch <= OFF_ALERT);
    return new State(Phase.RIDE, ride, left, exit, false, due ? "off:" + ride : null);
  }

  /**
   * Where the rider is along a ride: {last stop behind or at, fraction of the way to the next stop (0 at a stop)},
   * or null when farther than
   * {@link #ON_ROUTE_METRES} from the line between consecutive stops. A stop ahead counts only once the rider is
   * at it, so "get off at the next stop" is never said a stop early.
   */
  @Nullable
  static double[] place(TransitJourneyLeg leg, double lat, double lon)
  {
    return place(leg, lat, lon, ON_ROUTE_METRES);
  }

  static double[] place(TransitJourneyLeg leg, double lat, double lon, double tolerance)
  {
    int best = -1;
    double distance = Double.MAX_VALUE;
    for (int k = 0; k + 1 < leg.stops.length; ++k)
    {
      double d = segmentMetres(lat, lon, leg.stops[k], leg.stops[k + 1]);
      // Ties (the rider at a stop shared by two segments) go to the later segment.
      if (d <= distance) { distance = d; best = k; }
    }
    if (best < 0 || distance > tolerance) return null;
    // Beyond the exit stop (past the end of the last segment) the rider is off: the final walk has begun.
    if (best + 2 == leg.stops.length && along(lat, lon, leg.stops[best], leg.stops[best + 1]) >= 1)
      return new double[] {best + 1, 0};
    if (metres(lat, lon, leg.stops[best + 1]) < AT_STOP_METRES) return new double[] {best + 1, 0};
    if (metres(lat, lon, leg.stops[best]) < AT_STOP_METRES) return new double[] {best, 0};
    double before = metres(lat, lon, leg.stops[best]), after = metres(lat, lon, leg.stops[best + 1]);
    return new double[] {best, Math.max(0.01, Math.min(0.99, before / (before + after)))};
  }

  /** When the timetable has the vehicle at {@code fraction} of the way from stop {@code passed} to the next. */
  private static long expected(TransitJourneyLeg leg, int passed, double fraction)
  {
    long from = leg.stops[passed].departure;
    if (fraction <= 0 || passed + 1 >= leg.stops.length) return from;
    return from + Math.round(fraction * (leg.stops[passed + 1].arrival - from));
  }

  /** How far along a→b the rider's projection lies: 0 at a, 1 at b, beyond either end outside [0, 1]. */
  private static double along(double lat, double lon, TransitJourneyLeg.Stop a, TransitJourneyLeg.Stop b)
  {
    double scale = Math.cos(Math.toRadians(lat));
    double ax = Math.toRadians(a.lon - lon) * scale, ay = Math.toRadians(a.lat - lat);
    double dx = Math.toRadians(b.lon - a.lon) * scale, dy = Math.toRadians(b.lat - a.lat);
    double length = dx * dx + dy * dy;
    return length == 0 ? 1 : -(ax * dx + ay * dy) / length;
  }

  private static double segmentMetres(double lat, double lon, TransitJourneyLeg.Stop a, TransitJourneyLeg.Stop b)
  {
    double scale = Math.cos(Math.toRadians(lat));
    double ax = Math.toRadians(a.lon - lon) * scale, ay = Math.toRadians(a.lat - lat);
    double bx = Math.toRadians(b.lon - lon) * scale, by = Math.toRadians(b.lat - lat);
    double dx = bx - ax, dy = by - ay;
    double length = dx * dx + dy * dy;
    double t = length == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / length));
    double x = ax + t * dx, y = ay + t * dy;
    return Math.sqrt(x * x + y * y) * 6371000;
  }

  /** Index of the last stop the vehicle has left by the clock (0 = only the boarding stop). */
  private static int passedByTime(TransitJourneyLeg leg, long now)
  {
    int passed = 0;
    for (int i = 1; i < leg.stops.length - 1; ++i) if (leg.stops[i].departure <= now) passed = i;
    return passed;
  }

  private static int nearest(TransitJourneyLeg leg, double lat, double lon)
  {
    int best = -1;
    double distance = Double.MAX_VALUE;
    for (int i = 0; i < leg.stops.length; ++i)
    {
      double d = metres(lat, lon, leg.stops[i]);
      if (d < distance) { distance = d; best = i; }
    }
    return best;
  }

  private static TransitJourneyLeg.Stop last(TransitJourneyLeg leg) { return leg.stops[leg.stops.length - 1]; }

  static double metres(double lat, double lon, TransitJourneyLeg.Stop stop)
  {
    double x = Math.toRadians(stop.lon - lon) * Math.cos(Math.toRadians((lat + stop.lat) / 2));
    double y = Math.toRadians(stop.lat - lat);
    return Math.sqrt(x * x + y * y) * 6371000;
  }
}
