package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RigaStopDeparturesTest
{
  /** 2026-11-24 00:00 in Riga. */
  private static long midnight()
  {
    Calendar day = Calendar.getInstance(RigaTransitStore.ZONE);
    day.clear();
    day.set(2026, Calendar.NOVEMBER, 24, 0, 0, 0);
    return day.getTimeInMillis();
  }

  private static long at(int hour, int minute) { return midnight() + (hour * 60L + minute) * 60000; }

  private static RigaJourneyPlanner.Trip trip(String id, String shape, long first)
  {
    return new RigaJourneyPlanner.Trip(id, "15", "15", "Ķengarags", 800, shape, midnight(), Arrays.asList(
        new RigaJourneyPlanner.Event(0, 1, first, first, true, true),
        new RigaJourneyPlanner.Event(1, 2, first + 3 * 60000, first + 3 * 60000, true, true),
        new RigaJourneyPlanner.Event(2, 3, first + 9 * 60000, first + 9 * 60000, true, true)));
  }

  private static RigaJourneyPlanner.Timetable data(RigaJourneyPlanner.Trip... trips)
  {
    return new RigaJourneyPlanner.Timetable(Arrays.asList(
        new RigaJourneyPlanner.Stop("0075", "Centrālā stacija", "", 56.9460, 24.1170),
        new RigaJourneyPlanner.Stop("0123", "Elizabetes iela", "", 56.9480, 24.1150),
        new RigaJourneyPlanner.Stop("0456", "Ķengarags", "", 56.9000, 24.1900)),
        Arrays.asList(trips), Collections.emptyList());
  }

  @Test public void readsTheStopListOnTheRigaClock()
  {
    List<RigaStopDepartures.Row> rows = RigaStopDepartures.parse(
        "stop,0075\ntrol,15,b-a,79140,17671,Ķengarags\nbus,3,a-b,79500,,Dreiliņi\nbad line\n", at(21, 55));
    assertEquals(2, rows.size());
    assertEquals("0075", rows.get(0).stop);
    assertEquals("riga_trol_15_b-a", rows.get(0).shape);
    assertEquals(at(21, 59), rows.get(0).time);
    assertEquals("17671", rows.get(0).vehicle);
    assertEquals("", rows.get(1).vehicle);
  }

  @Test public void justAfterMidnightTheListCanStillBeOnYesterdaysClock()
  {
    // 00:05 on the 25th; the list still counts from the 24th: 24:10 is 00:10 on the 25th.
    long now = at(24, 5);
    List<RigaStopDepartures.Row> rows = RigaStopDepartures.parse("stop,0075\ntram,1,a-b,87000,1234,X\n", now);
    assertEquals(at(24, 10), rows.get(0).time);
    // And a list on today's clock: 00:20.
    rows = RigaStopDepartures.parse("stop,0075\ntram,1,a-b,1200,1234,X\n", now);
    assertEquals(at(24, 20), rows.get(0).time);
  }

  /**
   * The case from the street: 15 due 21:52 came at 21:59 with 17671, and the 22:05 left on time. The late vehicle
   * keeps its own departure; it does not take the 22:05 one.
   */
  @Test public void aLateVehicleKeepsItsOwnDeparture()
  {
    String shape = "riga_trol_15_b-a";
    RigaJourneyPlanner.Timetable data = data(trip("a", shape, at(21, 52)), trip("b", shape, at(22, 5)),
                                             trip("c", shape, at(22, 19)));
    long now = at(21, 55);
    List<RigaStopDepartures.Row> rows = RigaStopDepartures.parse(
        "stop,0075\ntrol,15,b-a,79140,17671,Ķengarags\ntrol,15,b-a,79500,17700,Ķengarags\n", now);
    Map<Integer, RigaJourneyPlanner.Prediction> found = RigaStopDepartures.predict(data, rows,
        Collections.emptyMap(), now);
    assertEquals(420, found.get(0).delaySeconds);
    assertEquals("17671", found.get(0).vehicleId);
    assertEquals(0, found.get(0).firstBoardableEvent);
    assertEquals(0, found.get(1).delaySeconds);
    assertEquals("17700", found.get(1).vehicleId);
    assertNull(found.get(2));
  }

  @Test public void aTripTheVehicleFeedPlacedIsLeftToIt()
  {
    String shape = "riga_trol_15_b-a";
    RigaJourneyPlanner.Timetable data = data(trip("a", shape, at(21, 52)), trip("b", shape, at(22, 5)));
    long now = at(21, 55);
    Map<Integer, RigaJourneyPlanner.Prediction> known = new HashMap<>();
    known.put(0, new RigaJourneyPlanner.Prediction(400, 0, "17671", now));
    List<RigaStopDepartures.Row> rows = RigaStopDepartures.parse(
        "stop,0075\ntrol,15,b-a,79140,17671,Ķengarags\ntrol,15,b-a,79560,17700,Ķengarags\n", now);
    Map<Integer, RigaJourneyPlanner.Prediction> found = RigaStopDepartures.predict(data, rows, known, now);
    assertFalse(found.containsKey(0));
    assertEquals(60, found.get(1).delaySeconds);
    Map<Integer, RigaJourneyPlanner.Prediction> merged = RigaStopDepartures.merge(known, found);
    assertEquals(400, merged.get(0).delaySeconds);
    assertEquals(60, merged.get(1).delaySeconds);
  }

  @Test public void otherLinesAndDirectionsAreNotMixedUp()
  {
    RigaJourneyPlanner.Timetable data = data(trip("a", "riga_trol_15_a-b", at(21, 52)));
    long now = at(21, 55);
    List<RigaStopDepartures.Row> rows = RigaStopDepartures.parse(
        "stop,0075\ntrol,15,b-a,79140,17671,Ķengarags\ntrol,17,a-b,79140,17672,X\n", now);
    assertTrue(RigaStopDepartures.predict(data, rows, Collections.emptyMap(), now).isEmpty());
  }

  @Test public void farOffTheTimetableIsAnotherTrip()
  {
    String shape = "riga_trol_15_b-a";
    RigaJourneyPlanner.Timetable data = data(trip("a", shape, at(21, 0)));
    long now = at(21, 55);
    // 21:59 is 59 min after the only departure: beyond what a late vehicle is taken for.
    List<RigaStopDepartures.Row> rows = RigaStopDepartures.parse("stop,0075\ntrol,15,b-a,79140,17671,K\n", now);
    assertTrue(RigaStopDepartures.predict(data, rows, Collections.emptyMap(), now).isEmpty());
  }

  @Test public void alignsInOrderWithTheLeastDelay()
  {
    long now = at(12, 0);
    List<RigaStopDepartures.Row> listed = new ArrayList<>();
    listed.add(new RigaStopDepartures.Row("s", "x", at(12, 1), "1"));
    listed.add(new RigaStopDepartures.Row("s", "x", at(12, 12), "2"));
    List<long[]> trips = new ArrayList<>();
    trips.add(new long[] {at(11, 58), 0, 0});
    trips.add(new long[] {at(12, 10), 1, 0});
    trips.add(new long[] {at(12, 20), 2, 0});
    int[] pairs = RigaStopDepartures.align(listed, trips, now);
    assertEquals(0, pairs[0]);
    assertEquals(1, pairs[1]);
  }
}
