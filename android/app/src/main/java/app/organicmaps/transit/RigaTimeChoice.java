package app.organicmaps.transit;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.R;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * When the rider wants to travel: now, departing at a time or arriving by a time. One choice for the
 * route being planned; a new destination starts again from "now".
 */
public final class RigaTimeChoice
{
  public enum Mode { NOW, DEPART, ARRIVE }

  private static volatile Mode sMode = Mode.NOW;
  private static volatile long sTime;

  private RigaTimeChoice() {}

  @NonNull public static Mode mode() { return sMode; }
  public static long time() { return sTime; }

  public static synchronized void set(@NonNull Mode mode, long time)
  {
    sMode = mode;
    sTime = mode == Mode.NOW ? 0 : time;
  }

  public static void reset() { set(Mode.NOW, 0); }

  // A time given by a route link, kept for the destination it came with so planning there does not reset it.
  private static double sPinLat = Double.NaN, sPinLon = Double.NaN;

  /**
   * Takes the time from a route link: {@code depart} or {@code arrive}, as {@code 2026-11-24T08:30} (local
   * time) or {@code 08:30} (its next occurrence). A link with neither plans from now.
   * @return false if a time was given but could not be read; the choice is then "now".
   */
  public static synchronized boolean fromLink(@androidx.annotation.Nullable String depart,
                                              @androidx.annotation.Nullable String arrive,
                                              double toLat, double toLon, long now)
  {
    sPinLat = toLat;
    sPinLon = toLon;
    String text = depart != null ? depart : arrive;
    if (text == null)
    {
      reset();
      return true;
    }
    long time = parse(text.trim(), now);
    if (time <= 0)
    {
      reset();
      return false;
    }
    set(depart != null ? Mode.DEPART : Mode.ARRIVE, time);
    return true;
  }

  /**
   * @return true once if the link's time belongs to this destination (planning there keeps it). A link time
   * not claimed by its own destination (another mode, or outside the timetables) is dropped, back to "now".
   */
  public static synchronized boolean claimLink(double lat, double lon)
  {
    boolean pinned = !Double.isNaN(sPinLat);
    boolean ours = pinned && Math.abs(lat - sPinLat) <= 0.0005 && Math.abs(lon - sPinLon) <= 0.0005;
    sPinLat = sPinLon = Double.NaN;
    if (pinned && !ours) reset();
    return ours;
  }

  /** "2026-11-24T08:30" (seconds and a "Z" or offset are not accepted) or "08:30"; 0 if unreadable. */
  static long parse(@NonNull String text, long now)
  {
    try
    {
      if (text.matches("\\d{1,2}:\\d{2}"))
      {
        String[] hm = text.split(":");
        int hour = Integer.parseInt(hm[0]), minute = Integer.parseInt(hm[1]);
        if (hour > 23 || minute > 59) return 0;
        Calendar at = Calendar.getInstance(RigaTransitStore.ZONE);
        at.setTimeInMillis(now);
        at.set(Calendar.HOUR_OF_DAY, hour);
        at.set(Calendar.MINUTE, minute);
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() < now - 60000) at.add(Calendar.DAY_OF_MONTH, 1);
        return at.getTimeInMillis();
      }
      if (!text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{1,2}:\\d{2}")) return 0;
      SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
      format.setLenient(false);
      format.setTimeZone(RigaTransitStore.ZONE); // timetables and the time picker are in Riga time
      Date date = format.parse(text);
      return date == null ? 0 : date.getTime();
    }
    catch (java.text.ParseException | NumberFormatException e)
    {
      return 0;
    }
  }

  /**
   * The departure to plan from. Arrive-by searches from {@link #ARRIVE_SPAN} before. A time earlier today is
   * planned as chosen (to look again at a route seen a moment ago); one from an earlier day means now.
   */
  static long searchStart(long now)
  {
    long today = startOfDay(now);
    if (sMode == Mode.NOW || sTime < today) return now;
    return sMode == Mode.DEPART ? sTime : Math.max(today, sTime - ARRIVE_SPAN);
  }

  /** The earliest moment a plan may start: now, or the chosen time earlier today. */
  static long earliest(long now)
  {
    return Math.min(now, searchStart(now));
  }

  /** Midnight starting the Riga day {@code now} is in. */
  public static long startOfDay(long now)
  {
    Calendar day = Calendar.getInstance(RigaTransitStore.ZONE);
    day.setTimeInMillis(now);
    day.set(Calendar.HOUR_OF_DAY, 0);
    day.set(Calendar.MINUTE, 0);
    day.set(Calendar.SECOND, 0);
    day.set(Calendar.MILLISECOND, 0);
    return day.getTimeInMillis();
  }

  /** Whether live positions can tell anything about a plan starting at {@code start}: soon, and not past. */
  static boolean liveUseful(long start, long now)
  {
    return start - now > -60000 && start - now < 30 * 60000L;
  }

  /** How far before the arrival time journeys are looked for. */
  static final long ARRIVE_SPAN = 3 * 3600000L;

  /** Compact button text: "Now", "Leave 15:30", "Arrive 18:45", "Arrive Tue 18:45" (another day). */
  @NonNull public static String shortLabel(@NonNull Context context)
  {
    Mode mode = sMode;
    if (mode == Mode.NOW) return context.getString(R.string.riga_time_short_now);
    long now = System.currentTimeMillis();
    SimpleDateFormat format = new SimpleDateFormat(daysBetween(now, sTime) == 0 ? "HH:mm" : "EEE HH:mm",
                                                   Locale.getDefault());
    format.setTimeZone(RigaTransitStore.ZONE);
    return context.getString(mode == Mode.DEPART ? R.string.riga_time_short_leave : R.string.riga_time_short_arrive,
                             format.format(new Date(sTime)));
  }

  /** Button text: "Leave now", "Leave 15:30", "Leave tomorrow 08:00", "Arrive by Tue 25 Nov 16:00". */
  @NonNull public static String label(@NonNull Context context)
  {
    Mode mode = sMode;
    if (mode == Mode.NOW) return context.getString(R.string.riga_time_now);
    long now = System.currentTimeMillis();
    if (daysBetween(now, sTime) == 1)
    {
      SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
      clock.setTimeZone(RigaTransitStore.ZONE);
      return context.getString(mode == Mode.DEPART ? R.string.riga_time_depart_tomorrow
                                                   : R.string.riga_time_arrive_tomorrow, clock.format(new Date(sTime)));
    }
    String when = when(context, sTime, now);
    return context.getString(mode == Mode.DEPART ? R.string.riga_time_depart_at : R.string.riga_time_arrive_by, when);
  }

  /** "15:30" today, "Tomorrow 15:30", otherwise "Tue 25 Nov 15:30". */
  @NonNull public static String when(@NonNull Context context, long time, long now)
  {
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(RigaTransitStore.ZONE);
    String at = clock.format(new Date(time));
    int days = daysBetween(now, time);
    if (days == 0) return at;
    if (days == 1) return context.getString(R.string.riga_time_tomorrow, at);
    SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
    day.setTimeZone(RigaTransitStore.ZONE);
    return day.format(new Date(time)) + " " + at;
  }

  /** "Today", "Tomorrow" or "Tue 25 Nov". */
  @NonNull static String day(@NonNull Context context, long time, long now)
  {
    int days = daysBetween(now, time);
    if (days == 0) return context.getString(R.string.riga_time_today);
    if (days == 1) return context.getString(R.string.riga_time_tomorrow_day);
    SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
    day.setTimeZone(RigaTransitStore.ZONE);
    return day.format(new Date(time));
  }

  /** {@link #day} for {@code time} from now. */
  @NonNull public static String dayOf(@NonNull Context context, long time)
  { return day(context, time, System.currentTimeMillis()); }

  static int daysBetween(long from, long to)
  {
    Calendar a = Calendar.getInstance(RigaTransitStore.ZONE);
    a.setTimeInMillis(from);
    Calendar b = Calendar.getInstance(RigaTransitStore.ZONE);
    b.setTimeInMillis(to);
    int days = 0;
    while (a.get(Calendar.YEAR) != b.get(Calendar.YEAR) || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR))
    {
      if (Math.abs(days) > 366) break;
      int step = a.before(b) ? 1 : -1;
      a.add(Calendar.DAY_OF_MONTH, step);
      days += step;
    }
    return days;
  }
}
