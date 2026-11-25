package app.organicmaps.transit;

import static org.junit.Assert.*;

import java.time.ZonedDateTime;
import org.junit.Test;

public class RigaWaitTest
{
  private static long riga(String time) { return ZonedDateTime.parse(time + "+02:00[Europe/Riga]").toInstant().toEpochMilli(); }

  private static final long NOW = riga("2026-11-23T00:13:00");

  @Test public void leavingNowWaitsFromNow()
  {
    assertEquals(NOW, RigaWait.from(RigaTimeChoice.Mode.NOW, 0, NOW));
  }

  @Test public void aChosenDepartureWaitsFromThatTimeEarlierTodayIncludedButNotFromAnEarlierDay()
  {
    long later = riga("2026-11-23T04:00:00");
    assertEquals(later, RigaWait.from(RigaTimeChoice.Mode.DEPART, later, NOW));
    // 00:03 today: a route looked up again for a moment ago.
    assertEquals(NOW - 600000, RigaWait.from(RigaTimeChoice.Mode.DEPART, NOW - 600000, NOW));
    // 23:53 yesterday: a stale choice, planned from now.
    assertEquals(NOW, RigaWait.from(RigaTimeChoice.Mode.DEPART, NOW - 1200000, NOW));
  }

  @Test public void aSearchCanStartEarlierTodayWithoutLiveData()
  {
    long now = riga("2026-11-24T07:30:00");
    try
    {
      RigaTimeChoice.set(RigaTimeChoice.Mode.DEPART, riga("2026-11-24T07:20:00"));
      assertEquals(riga("2026-11-24T07:20:00"), RigaTimeChoice.searchStart(now));
      assertEquals(riga("2026-11-24T07:20:00"), RigaTimeChoice.earliest(now));
      assertFalse(RigaTimeChoice.liveUseful(RigaTimeChoice.searchStart(now), now));
      // Arriving by 01:00 today: searched from midnight, not from three hours before (yesterday).
      RigaTimeChoice.set(RigaTimeChoice.Mode.ARRIVE, riga("2026-11-24T01:00:00"));
      assertEquals(riga("2026-11-24T00:00:00"), RigaTimeChoice.searchStart(now));
      // Yesterday's choice is stale: now.
      RigaTimeChoice.set(RigaTimeChoice.Mode.DEPART, riga("2026-11-23T23:00:00"));
      assertEquals(now, RigaTimeChoice.searchStart(now));
      assertTrue(RigaTimeChoice.liveUseful(now + 10 * 60000, now));
    }
    finally
    {
      RigaTimeChoice.reset();
    }
  }

  @Test public void anArriveByPlanHasNoWait()
  {
    assertEquals(0, RigaWait.from(RigaTimeChoice.Mode.ARRIVE, riga("2026-11-23T08:00:00"), NOW));
    assertEquals(0, RigaWait.notable(0, riga("2026-11-23T04:46:00")));
  }

  @Test public void theNightGapIsCalledOut()
  {
    long leave = riga("2026-11-23T04:46:00");
    assertEquals(leave - NOW, RigaWait.notable(NOW, leave));
  }

  @Test public void anOrdinaryWaitIsNot()
  {
    assertEquals(0, RigaWait.notable(NOW, NOW + 12 * 60000));
    assertEquals(0, RigaWait.notable(NOW, NOW + 30 * 60000));
    assertTrue(RigaWait.notable(NOW, NOW + 31 * 60000) > 0);
  }
}
