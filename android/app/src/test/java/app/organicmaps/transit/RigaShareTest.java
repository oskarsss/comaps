package app.organicmaps.transit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RigaShareTest
{
  // 2026-11-24 19:57 and 20:14 in Riga (UTC+2).
  private static final long LEAVE = 1795543020000L, ARRIVE = LEAVE + 17 * 60000L;

  @Test public void todaysPlanIsFromToAndTimesThenTheLink()
  {
    assertEquals("Tērbatas iela → Gaujas iela, 19:57–20:14\n"
                     + "https://comaps.at/route?sll=56.955000,24.120000&saddr=T%C4%93rbatas%20iela"
                     + "&dll=56.980000,24.200000&daddr=Gaujas%20iela&type=transit&depart=2026-11-24T19:57",
                 RigaShare.text("Tērbatas iela", 56.955, 24.12, "Gaujas iela", 56.98, 24.2, LEAVE, ARRIVE,
                                LEAVE - 3600000L));
  }

  @Test public void anotherDayLeadsWithTheDate()
  {
    assertEquals("A → B, 24.11. 19:57–20:14",
                 RigaShare.text("A", 1, 2, "B", 3, 4, LEAVE, ARRIVE, LEAVE - 24 * 3600000L).split("\n")[0]);
  }
}
