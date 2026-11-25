package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Calendar;
import org.junit.After;
import org.junit.Test;

public class RigaTimeLinkTest
{
  private static long at(int year, int month, int day, int hour, int minute)
  {
    Calendar c = Calendar.getInstance(RigaTransitStore.ZONE);
    c.clear();
    c.set(year, month - 1, day, hour, minute);
    return c.getTimeInMillis();
  }

  @After public void reset() { RigaTimeChoice.reset(); RigaTimeChoice.claimLink(0, 0); }

  @Test public void fullDateTime()
  {
    long now = at(2026, 11, 23, 12, 0);
    assertEquals(at(2026, 11, 24, 8, 30), RigaTimeChoice.parse("2026-11-24T08:30", now));
  }

  @Test public void clockTimeIsNextOccurrence()
  {
    long now = at(2026, 11, 23, 12, 0);
    assertEquals(at(2026, 11, 23, 15, 5), RigaTimeChoice.parse("15:05", now));
    assertEquals(at(2026, 11, 24, 8, 30), RigaTimeChoice.parse("8:30", now));
  }

  @Test public void unreadable()
  {
    long now = at(2026, 11, 23, 12, 0);
    assertEquals(0, RigaTimeChoice.parse("tomorrow", now));
    assertEquals(0, RigaTimeChoice.parse("25:00", now));
    assertEquals(0, RigaTimeChoice.parse("2026-13-40T08:30", now));
    assertEquals(0, RigaTimeChoice.parse("2026-11-24T08:30Z", now));
  }

  @Test public void linkSetsModeForItsDestinationOnce()
  {
    long now = at(2026, 11, 23, 12, 0);
    assertTrue(RigaTimeChoice.fromLink(null, "2026-11-24T09:00", 56.95, 24.1, now));
    assertEquals(RigaTimeChoice.Mode.ARRIVE, RigaTimeChoice.mode());
    assertEquals(at(2026, 11, 24, 9, 0), RigaTimeChoice.time());
    assertFalse(RigaTimeChoice.claimLink(57.0, 24.1));
    assertFalse(RigaTimeChoice.claimLink(56.95, 24.1));
    assertTrue(RigaTimeChoice.fromLink("10:00", null, 56.95, 24.1, now));
    assertEquals(RigaTimeChoice.Mode.DEPART, RigaTimeChoice.mode());
    assertTrue(RigaTimeChoice.claimLink(56.9502, 24.1001));
  }

  @Test public void unclaimedLinkTimeIsDropped()
  {
    long now = at(2026, 11, 23, 12, 0);
    assertTrue(RigaTimeChoice.fromLink("10:00", null, 56.95, 24.1, now));
    assertFalse(RigaTimeChoice.claimLink(56.5, 21.0));
    assertEquals(RigaTimeChoice.Mode.NOW, RigaTimeChoice.mode());
  }

  @Test public void badTimeFallsBackToNow()
  {
    long now = at(2026, 11, 23, 12, 0);
    assertFalse(RigaTimeChoice.fromLink("soon", null, 56.95, 24.1, now));
    assertEquals(RigaTimeChoice.Mode.NOW, RigaTimeChoice.mode());
  }
}
