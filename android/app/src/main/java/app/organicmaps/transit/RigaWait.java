package app.organicmaps.transit;

/**
 * Whether the shown journey sets off long after the rider wants to travel (at night nothing may run for
 * hours), so the route panel leads with the first departure instead of the ride duration.
 */
public final class RigaWait
{
  /** A wait longer than this is called out; shorter ones read fine from the times alone. */
  static final long NOTABLE = 30 * 60000L;

  private RigaWait() {}

  /**
   * When the rider wants to set off, or 0 when that is not a moment in time to wait from (arrive-by plans).
   * @param mode the travel time choice; @param chosen its time; @param now the current time.
   */
  public static long from(RigaTimeChoice.Mode mode, long chosen, long now)
  {
    switch (mode)
    {
    case NOW: return now;
    case DEPART: return chosen >= RigaTimeChoice.startOfDay(now) ? chosen : now;
    default: return 0;
    }
  }

  /** The wait (ms) before having to leave at {@code leave}, or 0 when it is not worth calling out. */
  public static long notable(long from, long leave)
  {
    return from > 0 && leave - from > NOTABLE ? leave - from : 0;
  }
}
