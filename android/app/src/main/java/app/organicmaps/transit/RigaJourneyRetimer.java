package app.organicmaps.transit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the rider's chosen journey (the same vehicles and stops) and moves its times to the latest live
 * estimates. A trip is never re-planned behind the rider's back: a transfer that can no longer be made
 * is reported instead.
 */
final class RigaJourneyRetimer
{
  /** Live estimates older than this are not trusted, as in planning. */
  static final long FRESH = 120000;

  static final class Retimed
  {
    final RigaJourneyPlanner.Journey journey;
    /** Index of the first ride that can no longer be reached in time, or -1. */
    final int missedLeg;
    Retimed(RigaJourneyPlanner.Journey journey, int missedLeg)
    {
      this.journey = journey;
      this.missedLeg = missedLeg;
    }
  }

  private RigaJourneyRetimer() {}

  /**
   * @param predictions live estimates keyed by index in {@code trips}; a ride without a fresh one keeps
   *                    its timetable times.
   */
  static Retimed retime(List<RigaJourneyPlanner.Trip> trips, RigaJourneyPlanner.Journey journey,
                        Map<Integer, RigaJourneyPlanner.Prediction> predictions, long now)
  {
    Map<RigaJourneyPlanner.Trip, RigaJourneyPlanner.Prediction> live = new IdentityHashMap<>();
    if (!predictions.isEmpty())
    {
      Map<RigaJourneyPlanner.Trip, Boolean> used = new IdentityHashMap<>();
      for (RigaJourneyPlanner.Leg leg : journey.legs) if (leg.trip != null) used.put(leg.trip, true);
      for (int t = 0; t < trips.size() && live.size() < used.size(); ++t)
      {
        RigaJourneyPlanner.Prediction prediction = predictions.get(t);
        if (prediction != null && used.containsKey(trips.get(t)) && now - prediction.receivedAt <= FRESH)
          live.put(trips.get(t), prediction);
      }
    }
    int count = journey.legs.size();
    long[] departure = new long[count];
    long[] arrival = new long[count];
    RigaJourneyPlanner.Prediction[] estimate = new RigaJourneyPlanner.Prediction[count];
    int firstRide = -1;
    for (int i = 0; i < count; ++i)
    {
      RigaJourneyPlanner.Leg leg = journey.legs.get(i);
      if (leg.trip == null) continue;
      if (firstRide < 0) firstRide = i;
      estimate[i] = live.get(leg.trip);
      // The matcher fails closed (e.g. a vehicle standing at a terminus); a recent estimate still holds.
      if (estimate[i] == null && leg.prediction != null && now - leg.prediction.receivedAt <= FRESH)
        estimate[i] = leg.prediction;
      long delay = estimate[i] == null ? 0 : estimate[i].delayMillis();
      departure[i] = leg.trip.events.get(leg.firstEvent).departure + delay;
      arrival[i] = leg.trip.events.get(leg.lastEvent).arrival + delay;
    }
    if (firstRide < 0) return new Retimed(journey, -1);
    // Walks before the first ride end when it leaves; later walks start when the rider gets off.
    long cursor = departure[firstRide];
    for (int i = firstRide - 1; i >= 0; --i)
    {
      RigaJourneyPlanner.Leg leg = journey.legs.get(i);
      arrival[i] = cursor;
      departure[i] = cursor - (leg.arrival - leg.departure);
      cursor = departure[i];
    }
    int missed = -1;
    cursor = arrival[firstRide];
    for (int i = firstRide + 1; i < count; ++i)
    {
      RigaJourneyPlanner.Leg leg = journey.legs.get(i);
      if (leg.trip == null)
      {
        departure[i] = cursor;
        arrival[i] = cursor + (leg.arrival - leg.departure);
      }
      else if (cursor > departure[i] && missed < 0)
        missed = i;
      cursor = arrival[i];
    }
    List<RigaJourneyPlanner.Leg> legs = new ArrayList<>(count);
    for (int i = 0; i < count; ++i)
    {
      RigaJourneyPlanner.Leg leg = journey.legs.get(i);
      legs.add(new RigaJourneyPlanner.Leg(leg.from, leg.to, departure[i], arrival[i], leg.trip, leg.firstEvent,
          leg.lastEvent, leg.walkingMetres, leg.trip == null ? null : estimate[i]));
    }
    return new Retimed(new RigaJourneyPlanner.Journey(journey.departure, legs, journey.boardings), missed);
  }
}
