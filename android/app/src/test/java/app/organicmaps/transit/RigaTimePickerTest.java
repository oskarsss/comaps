package app.organicmaps.transit;

import static org.junit.Assert.*;

import java.time.ZonedDateTime;
import org.junit.Test;

public class RigaTimePickerTest
{
  private static long riga(String time) { return ZonedDateTime.parse(time + "+02:00[Europe/Riga]").toInstant().toEpochMilli(); }

  @Test public void aLaterTimeIsToday()
  {
    long now = riga("2026-11-22T16:57:00");
    assertEquals(riga("2026-11-22T18:30:00"), RigaTimePicker.at(now, 18, 30, now));
  }

  @Test public void aTimeAlreadyPastIsTomorrow()
  {
    long now = riga("2026-11-22T16:57:00");
    assertEquals(riga("2026-11-23T08:00:00"), RigaTimePicker.at(now, 8, 0, now));
  }

  @Test public void aChosenDayKeepsItsDate()
  {
    long now = riga("2026-11-22T16:57:00");
    assertEquals(riga("2026-11-25T07:15:00"), RigaTimePicker.at(riga("2026-11-25T12:00:00"), 7, 15, now));
  }
}
