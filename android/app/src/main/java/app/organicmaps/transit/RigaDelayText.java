package app.organicmaps.transit;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A live time next to the timetable one it replaces, as Mobilly and the stop boards show a late departure:
 * "21:59 <s>21:52</s>". Only when the two read differently on the clock.
 */
public final class RigaDelayText
{
  private RigaDelayText() {}

  /** "HH:mm" on Riga's clock. Not thread-safe: one per caller. */
  @NonNull
  public static SimpleDateFormat clock()
  {
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(RigaTransitStore.ZONE);
    return clock;
  }

  /** The timetable time to strike after {@code instant}, or null when the delay does not change the minute. */
  @Nullable
  static String scheduled(long instant, long delay, @NonNull SimpleDateFormat clock)
  {
    return delay == 0 ? null : scheduled(clock.format(new Date(instant)), instant, delay, clock);
  }

  @Nullable
  private static String scheduled(@NonNull String shown, long instant, long delay, @NonNull SimpleDateFormat clock)
  {
    String timetable = clock.format(new Date(instant - delay));
    return shown.equals(timetable) ? null : timetable;
  }

  /** {@code instant} on the clock, then the timetable time struck through when the delay moves it. */
  @NonNull
  public static CharSequence time(long instant, long delay, @NonNull SimpleDateFormat clock)
  {
    return mark(clock.format(new Date(instant)), instant, delay, clock);
  }

  /**
   * {@code text} with the timetable time struck through after the first place it says {@code instant}; the text
   * as it is when the delay does not change the minute or the time is not in it.
   */
  @NonNull
  public static CharSequence mark(@NonNull CharSequence text, long instant, long delay, @NonNull SimpleDateFormat clock)
  {
    if (delay == 0) return text;
    String shown = clock.format(new Date(instant));
    String timetable = scheduled(shown, instant, delay, clock);
    int at = timetable == null ? -1 : text.toString().indexOf(shown);
    if (at < 0) return text;
    SpannableStringBuilder marked = new SpannableStringBuilder(text);
    int end = at + shown.length();
    marked.insert(end, " " + timetable);
    marked.setSpan(new StrikethroughSpan(), end + 1, end + 1 + timetable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return marked;
  }

  /** {@code text} with the timetable time struck through after each late or early ride's departure in it. */
  @NonNull
  public static CharSequence rides(@NonNull CharSequence text, @NonNull TransitJourneyLeg[] legs,
                                   @NonNull SimpleDateFormat clock)
  {
    for (TransitJourneyLeg leg : legs)
      if (leg.isRide() && leg.stops.length > 0) text = mark(text, leg.stops[0].departure, leg.delay, clock);
    return text;
  }
}
