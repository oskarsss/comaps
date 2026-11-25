package app.organicmaps.transit;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * What the route panel shows while the rider follows a journey: the step to do now, the one after it, which
 * legs are behind and which ride's ticket to validate. Built from {@link RigaTripGuide.State}, the same "now"
 * the trip notification uses, so the panel, the notification and the Steps sheet never disagree.
 */
public final class RigaTripPanel
{
  /** What a step asks of the rider. */
  public enum Kind { WALK, BOARD, RIDE, STAY, ARRIVE }

  public static final class Step
  {
    @NonNull public final Kind kind;
    /** The leg the step is about (for BOARD and RIDE, the ride); -1 for ARRIVE. */
    public final int leg;
    Step(@NonNull Kind kind, int leg)
    {
      this.kind = kind;
      this.leg = leg;
    }
  }

  @NonNull public final RigaTripGuide.State state;
  @NonNull public final Step now;
  /** The step after {@link #now}; ARRIVE when only the destination is left, null once arrived. */
  @Nullable public final Step next;
  /** Legs before this index are behind the rider. */
  public final int done;
  /** The ride whose ticket the rider validates now or next (the current or the next ride), or -1. */
  public final int validate;

  private RigaTripPanel(@NonNull RigaTripGuide.State state, @NonNull Step now, @Nullable Step next, int done,
                        int validate)
  {
    this.state = state;
    this.now = now;
    this.next = next;
    this.done = done;
    this.validate = validate;
  }

  private static String sJourney = "";
  private static int sReached;
  /** The furthest ride the rider was seen aboard in this journey, or -1. */
  private static int sRode = -1;
  /** While riding: the way the vehicle goes, radians clockwise from north, and when it was last worked out. */
  private static double sHeading = Double.NaN;
  private static long sHeadingAt;
  private static int sHeadingLeg = -1;
  /** How far back along the track the vehicle's direction is taken from, so GPS jitter does not turn it. */
  static final double HEADING_METRES = 40;
  /** Recent positions (time, lat, lon), to tell riding from walking. */
  private static final java.util.ArrayDeque<double[]> sTrack = new java.util.ArrayDeque<>();
  /** Faster than walking or jogging, sustained for {@link #MOVING_SPAN}: in a vehicle. */
  static final double MOVING_SPEED = 3;
  static final long MOVING_SPAN = 20000;

  /** Whether the rider moved faster than {@link #MOVING_SPEED} over the last 20 to 60 seconds. */
  static boolean moving(java.util.ArrayDeque<double[]> track, long now, double lat, double lon)
  {
    if (!Double.isFinite(lat) || !Double.isFinite(lon)) return false;
    double[] last = track.peekLast();
    if (last == null || now - last[0] >= 3000) track.addLast(new double[] {now, lat, lon});
    while (!track.isEmpty() && now - track.peekFirst()[0] > 60000) track.removeFirst();
    double[] oldest = track.peekFirst();
    if (oldest == null || now - oldest[0] < MOVING_SPAN) return false;
    double metres = RigaJourneyGeometry.distance(new RigaJourneyGeometry.Point(oldest[1], oldest[2]),
                                                 new RigaJourneyGeometry.Point(lat, lon));
    return metres / ((now - oldest[0]) / 1000.0) >= MOVING_SPEED;
  }

  /**
   * Where the rider is now, for every view of the trip (route panel, Steps sheet, notification): the rider only
   * moves forward through the journey, so the furthest leg reached is remembered per journey and a road shared
   * by two rides cannot send any of them back to the earlier one.
   */
  @NonNull
  public static synchronized RigaTripGuide.State now(@NonNull TransitJourneyLeg[] legs, long now, double lat,
                                                      double lon)
  {
    String journey = structure(legs);
    if (!journey.equals(sJourney)) { sJourney = journey; sReached = 0; sRode = -1; }
    boolean moving = moving(sTrack, now, lat, lon);
    RigaTripGuide.State state = RigaTripGuide.of(legs, now, lat, lon, sReached, moving, sRode);
    if (state.leg > sReached && state.phase != RigaTripGuide.Phase.LEAVE) sReached = state.leg;
    if (state.phase == RigaTripGuide.Phase.RIDE && state.leg > sRode) sRode = state.leg;
    updateHeading(legs, state, now, lat, lon);
    return state;
  }

  /**
   * The direction the map turns to while riding (in follow-and-rotate), instead of the phone's compass: the way
   * the vehicle last moved, kept while it stands at a stop or in traffic, or towards the next stop before it
   * has moved. A compass inside a bus or tram points anywhere, and the rider's phone is not the vehicle.
   * @return radians clockwise from north, or NaN when not riding (then the compass is used).
   */
  public static synchronized double rideHeading(long now)
  {
    return now - sHeadingAt < 30000 ? sHeading : Double.NaN;
  }

  private static void updateHeading(@NonNull TransitJourneyLeg[] legs, @NonNull RigaTripGuide.State state, long now,
                                    double lat, double lon)
  {
    if (state.phase != RigaTripGuide.Phase.RIDE || !Double.isFinite(lat) || !Double.isFinite(lon))
    {
      sHeading = Double.NaN;
      sHeadingLeg = -1;
      return;
    }
    if (state.leg != sHeadingLeg)
    {
      sHeadingLeg = state.leg;
      sHeading = Double.NaN;
    }
    double moved = movedHeading(sTrack, lat, lon);
    if (!Double.isNaN(moved))
      sHeading = moved;
    else if (Double.isNaN(sHeading))
    {
      TransitJourneyLeg.Stop[] stops = legs[state.leg].stops;
      int next = Math.max(1, Math.min(stops.length - 1, stops.length - state.stopsLeft));
      if (RigaJourneyGeometry.distance(new RigaJourneyGeometry.Point(lat, lon),
                                       new RigaJourneyGeometry.Point(stops[next].lat, stops[next].lon)) > 15)
        sHeading = bearing(lat, lon, stops[next].lat, stops[next].lon);
      else
        sHeading = bearing(stops[next - 1].lat, stops[next - 1].lon, stops[next].lat, stops[next].lon);
    }
    sHeadingAt = now;
  }

  /** The direction from the latest track point at least {@link #HEADING_METRES} back to here, or NaN. */
  static double movedHeading(@NonNull java.util.ArrayDeque<double[]> track, double lat, double lon)
  {
    RigaJourneyGeometry.Point here = new RigaJourneyGeometry.Point(lat, lon);
    for (java.util.Iterator<double[]> it = track.descendingIterator(); it.hasNext();)
    {
      double[] p = it.next();
      if (RigaJourneyGeometry.distance(new RigaJourneyGeometry.Point(p[1], p[2]), here) >= HEADING_METRES)
        return bearing(p[1], p[2], lat, lon);
    }
    return Double.NaN;
  }

  /** Initial great-circle bearing, radians clockwise from north in [0, 2π). */
  static double bearing(double lat1, double lon1, double lat2, double lon2)
  {
    double f1 = Math.toRadians(lat1), f2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
    double b = Math.atan2(Math.sin(dl) * Math.cos(f2),
                          Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl));
    return (b + 2 * Math.PI) % (2 * Math.PI);
  }

  /** The journey's shape (lines and boarding stops), unchanged when only its times are updated. */
  @NonNull
  public static String structure(@NonNull TransitJourneyLeg[] legs)
  {
    StringBuilder key = new StringBuilder();
    for (TransitJourneyLeg leg : legs)
      key.append(leg.type).append(leg.number).append('@').append(leg.stops.length == 0 ? "" : leg.stops[0].name).append('|');
    return key.toString();
  }

  @NonNull
  public static RigaTripPanel of(@NonNull TransitJourneyLeg[] legs, @NonNull RigaTripGuide.State state)
  {
    if (state.phase == RigaTripGuide.Phase.ARRIVED || state.leg < 0 || state.leg >= legs.length)
      return new RigaTripPanel(state, new Step(Kind.ARRIVE, -1), null, legs.length, -1);
    int leg = state.leg;
    switch (state.phase)
    {
    case LEAVE:
    case WALK:
    {
      int ride = rideAfter(legs, leg);
      Step next = ride < 0 ? new Step(Kind.ARRIVE, -1) : new Step(Kind.BOARD, ride);
      int stay = stayAfter(legs, leg);
      if (stay >= 0 && (ride < 0 || stay < ride)) { next = new Step(Kind.STAY, stay); ride = -1; }
      return new RigaTripPanel(state, new Step(Kind.WALK, leg), next, leg, validatable(legs, ride));
    }
    case STAY:
    {
      // Then: the ride the next part starts with, or its walk.
      int ride = rideAfter(legs, leg);
      int stay = stayAfter(legs, leg);
      Step next;
      if (ride >= 0 && (stay < 0 || ride < stay)) next = new Step(Kind.BOARD, ride);
      else if (leg + 1 < legs.length) next = new Step(Kind.WALK, leg + 1);
      else next = new Step(Kind.ARRIVE, -1);
      return new RigaTripPanel(state, new Step(Kind.STAY, leg), next, leg, -1);
    }
    case WAIT:
      return new RigaTripPanel(state, new Step(Kind.BOARD, leg), new Step(Kind.RIDE, leg), leg,
                               validatable(legs, leg));
    default:
    {
      // Riding: next is the following ride (a short change on foot is part of it) or the walk to the end.
      int after = leg + 1;
      Step next;
      if (after >= legs.length) next = new Step(Kind.ARRIVE, -1);
      else if (legs[after].isRide()) next = new Step(Kind.BOARD, after);
      else
      {
        int ride = rideAfter(legs, after);
        int stay = stayAfter(legs, leg);
        if (stay >= 0 && (ride < 0 || stay < ride)) next = new Step(Kind.WALK, after);
        else next = ride >= 0 ? new Step(Kind.BOARD, ride) : new Step(Kind.WALK, after);
      }
      return new RigaTripPanel(state, new Step(Kind.RIDE, leg), next, leg, validatable(legs, leg));
    }
    }
  }

  /** The first ride after leg {@code from}, walks skipped; -1 when only walking is left. */
  private static int rideAfter(TransitJourneyLeg[] legs, int from)
  {
    for (int i = from + 1; i < legs.length; ++i) if (legs[i].isRide()) return i;
    return -1;
  }

  /** The first stop of the route after leg {@code from}; -1 when none is left. */
  private static int stayAfter(TransitJourneyLeg[] legs, int from)
  {
    for (int i = from + 1; i < legs.length; ++i) if (legs[i].isStay()) return i;
    return -1;
  }

  private static int validatable(TransitJourneyLeg[] legs, int ride)
  {
    if (ride < 0 || !legs[ride].isRide()) return -1;
    return legs[ride].validatable ? ride : -1; // Rīgas satiksme rides only
  }

  /** Whole minutes until {@code at}, rounded up; 0 when it is now or past. */
  public static long minutes(long at, long now)
  {
    return Math.max(0, (at - now + 59999) / 60000);
  }

  /** The label above the countdown: what happens when it reaches zero. Empty when there is no countdown. */
  @NonNull
  public String countdownLabel(@NonNull Context context, @NonNull TransitJourneyLeg[] legs)
  {
    if (state.missed) return "";
    switch (state.phase)
    {
    case LEAVE: return context.getString(R.string.riga_trip_label_leave);
    case WALK: return context.getString(next != null && next.kind == Kind.BOARD ? R.string.riga_trip_label_departs
                                                                                : R.string.riga_trip_label_arrive);
    case WAIT: return context.getString(R.string.riga_trip_label_departs);
    case RIDE: return context.getString(R.string.riga_trip_label_off);
    case STAY: return context.getString(R.string.riga_trip_label_leave);
    default: return "";
    }
  }

  /** The action now, in a few words: "Walk to Dienvidu tilts", "Take bus 49", "Get off in 2 stops". */
  @NonNull
  public String title(@NonNull Context context, @NonNull TransitJourneyLeg[] legs, long now)
  {
    if (state.phase == RigaTripGuide.Phase.LEAVE)
      return context.getString(R.string.riga_trip_live_walk, last(legs[state.leg]).name);
    return RigaTripGuide.liveTitle(context, legs, state, now);
  }

  /** Where and when, under the title. */
  @NonNull
  public CharSequence detail(@NonNull Context context, @NonNull TransitJourneyLeg[] legs)
  {
    if (now.kind == Kind.ARRIVE) return "";
    TransitJourneyLeg leg = legs[now.leg];
    SimpleDateFormat clock = clock();
    switch (now.kind)
    {
    case WALK:
    {
      TransitJourneyLeg.Stop end = last(leg);
      return context.getString(next != null && next.kind == Kind.BOARD ? R.string.riga_trip_walk_detail
                                                                       : R.string.riga_trip_walk_final_detail,
                               leg.walkingMetres, clock.format(new Date(end.arrival)));
    }
    case STAY:
      return RigaTripGuide.liveText(context, legs, state, 0);
    case BOARD:
      if (!state.missed && !leg.progress.isEmpty()) return leg.progress;
      return RigaDelayText.mark(context.getString(R.string.riga_trip_board_detail, leg.headsign, leg.stops[0].name,
                               clock.format(new Date(leg.stops[0].departure))), leg.stops[0].departure, leg.delay, clock);
    default:
      // The title counts the stops ("Get off in 2 stops"); this names the exit and its time.
      return RigaDelayText.mark(context.getString(R.string.riga_trip_live_exit, last(leg).name,
                               clock.format(new Date(state.at))), state.at, leg.delay, clock);
    }
  }

  /** The step after this one, quieter: "Bus 49 → Sarkandaugava at 07:31", "Get off at Pavasara gatve · 07:39". */
  @NonNull
  public CharSequence nextText(@NonNull Context context, @NonNull TransitJourneyLeg[] legs)
  {
    if (next == null) return "";
    SimpleDateFormat clock = clock();
    TransitJourneyLeg final_ = legs[legs.length - 1];
    switch (next.kind)
    {
    case ARRIVE:
      return context.getString(R.string.riga_trip_next_arrive, last(final_).name,
                               clock.format(new Date(last(final_).arrival)));
    case WALK:
    {
      TransitJourneyLeg walk = legs[next.leg];
      // The walk to the destination: its arrival is already the panel's "Arrive" in the header.
      if (next.leg == legs.length - 1)
        return context.getString(R.string.riga_trip_next_walk_final, walkMinutes(walk), last(walk).name);
      return context.getString(R.string.riga_trip_next_walk, walkMinutes(walk), last(walk).name,
                               clock.format(new Date(last(walk).arrival)));
    }
    case STAY:
    {
      TransitJourneyLeg stay = legs[next.leg];
      return context.getString(R.string.riga_trip_next_stay, stay.headsign,
                               clock.format(new Date(last(stay).departure)));
    }
    case RIDE:
    {
      TransitJourneyLeg ride = legs[next.leg];
      return RigaDelayText.mark(context.getResources().getQuantityString(R.plurals.riga_trip_next_off,
          ride.stopCount(), last(ride).name, clock.format(new Date(last(ride).arrival)), ride.stopCount()),
          last(ride).arrival, ride.delay, clock);
    }
    default:
    {
      TransitJourneyLeg ride = legs[next.leg];
      String mode = RigaTripGuide.mode(context, ride.type);
      String at = clock.format(new Date(ride.stops[0].departure));
      TransitJourneyLeg walk = next.leg > 0 && legs[next.leg - 1].isWalking() ? legs[next.leg - 1] : null;
      // From a ride: say the change on foot first, unless the walk is the current step.
      if (walk != null && now.kind == Kind.RIDE)
        return RigaDelayText.mark(context.getString(R.string.riga_trip_next_change, walkMinutes(walk), mode,
                                                    ride.number, at), ride.stops[0].departure, ride.delay, clock);
      return RigaDelayText.mark(context.getString(R.string.riga_trip_next_board, mode, ride.number, ride.headsign, at),
                                ride.stops[0].departure, ride.delay, clock);
    }
    }
  }

  /** The leg the "then" row's badge shows, or -1 for a walking icon / no badge. */
  public int nextBadgeLeg()
  {
    return next == null || (next.kind != Kind.BOARD && next.kind != Kind.RIDE) ? -1 : next.leg;
  }

  private static int walkMinutes(TransitJourneyLeg walk)
  {
    return (int) Math.max(1, minutes(last(walk).arrival, walk.stops[0].departure));
  }

  @NonNull
  static SimpleDateFormat clock()
  {
    return RigaDelayText.clock();
  }

  private static TransitJourneyLeg.Stop last(TransitJourneyLeg leg) { return leg.stops[leg.stops.length - 1]; }
}
