package app.organicmaps.routing;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitAlternative;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import com.google.android.material.color.MaterialColors;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Swipeable cards for the transport journeys the rider can switch between. */
final class TransitAlternativesView
{
  private static final TimeZone RIGA = TimeZone.getTimeZone("Europe/Riga");

  private TransitAlternativesView() {}

  /** Fills {@code scroll} (whose only child is a horizontal LinearLayout) from {@code info}. */
  static void bind(@NonNull HorizontalScrollView scroll, @NonNull TransitRouteInfo info)
  {
    LinearLayout row = (LinearLayout) scroll.getChildAt(0);
    // A refresh, or more journeys from "Later", rebuilds the cards of the same plan: keep the rider where they
    // scrolled. Only a new plan or another chosen card brings the chosen card into view.
    String key = info.alternatives.length == 0 ? "" : info.selectedAlternative + "|" + info.alternatives[0].leave
        + "|" + info.alternatives[0].arrival;
    boolean samePlan = key.equals(scroll.getTag(R.id.transit_alternatives));
    scroll.setTag(R.id.transit_alternatives, key);
    final int keptScroll = scroll.getScrollX();
    row.removeAllViews();
    boolean show = info.alternativesPending || info.alternatives.length > 1;
    scroll.setVisibility(show ? View.VISIBLE : View.GONE);
    if (!show) return;
    Context context = scroll.getContext();
    long now = System.currentTimeMillis();
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(RIGA);
    int accent = MaterialColors.getColor(scroll, androidx.appcompat.R.attr.colorPrimary);
    int outline = MaterialColors.getColor(scroll, com.google.android.material.R.attr.colorOutlineVariant, 0x33000000);
    View selectedCard = null;
    for (int i = 0; i < info.alternatives.length; ++i)
    {
      TransitAlternative alternative = info.alternatives[i];
      boolean selected = i == info.selectedAlternative;
      LinearLayout card = card(context, selected ? accent : outline, selected);
      // On foot the whole way: rounded up, as its walk badge and the panel's header are.
      long minutes = Math.max(1, alternative.boardings == 0
          ? (alternative.arrival - alternative.leave + 59999) / 60000
          : Math.round((alternative.arrival - alternative.leave) / 60000.0));
      // An overnight option reads "5 h 52 min", not "352 min".
      card.addView(text(context, RouteModeTimes.duration(context, alternative.arrival - alternative.leave), 16, true, 0));
      LinearLayout badges = new LinearLayout(context);
      badges.setGravity(Gravity.CENTER_VERTICAL);
      for (int b = 0; b < alternative.numbers.length; ++b)
      {
        if (b > 0)
        {
          TextView then = text(context, "›", 13, false, 0);
          then.setPadding(dp(context, 3), 0, dp(context, 3), 0);
          then.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
          badges.addView(then);
        }
        if (alternative.modes[b] == app.organicmaps.sdk.routing.TransitStepType.PEDESTRIAN)
        {
          badges.addView(walk(context, alternative.numbers[b]));
          continue;
        }
        // Only the line this option boards: the other lines making the same ride ("7/14") belong to the
        // chosen route's own summary above, not to every card.
        badges.addView(badge(context, boarded(alternative.numbers[b]), alternative.colors[b]));
      }
      LinearLayout.LayoutParams spaced = new LinearLayout.LayoutParams(-2, -2);
      spaced.topMargin = dp(context, 3);
      spaced.bottomMargin = dp(context, 3);
      card.addView(badges, spaced);
      card.addView(text(context, new android.text.SpannableStringBuilder(
          app.organicmaps.transit.RigaDelayText.time(alternative.leave, alternative.delay, clock)).append("–")
          .append(clock.format(new Date(alternative.arrival))), 13, false, 0));
      long wait = Math.round((alternative.leave - now) / 60000.0);
      // Countdowns help for the next hour; a journey for later is shown by its day.
      // A route looked up for earlier today left a while ago.
      String when = wait < -60 ? app.organicmaps.transit.RigaTimeChoice.dayOf(context, alternative.leave)
          : wait < 0 ? context.getResources().getQuantityString(R.plurals.riga_live_minutes_ago, (int) -wait, (int) -wait)
          : wait == 0 ? context.getString(R.string.riga_live_now)
          : wait <= 60 ? context.getResources().getQuantityString(R.plurals.riga_live_in_minutes, (int) wait, (int) wait)
          : app.organicmaps.transit.RigaTimeChoice.dayOf(context, alternative.leave);
      if (alternative.live) when += " · " + context.getString(R.string.riga_alternative_live);
      card.addView(text(context, when, 13, alternative.live, alternative.live ? accent : 0));
      String also = also(context, alternative.also, now, clock);
      if (!also.isEmpty()) card.addView(text(context, also, 12, false, 0));
      final int index = i;
      card.setContentDescription(context.getString(R.string.riga_alternative_description, minutes,
          clock.format(new Date(alternative.leave)), clock.format(new Date(alternative.arrival))));
      card.setOnClickListener(v -> { if (info.selectAlternative != null) info.selectAlternative.accept(index); });
      row.addView(card, cardParams(context));
      if (selected) selectedCard = card;
    }
    if (info.alternativesPending)
    {
      // A small spinner beside the text, which wraps rather than being cut to the card's width.
      LinearLayout card = card(context, outline, false);
      card.setOrientation(LinearLayout.HORIZONTAL);
      card.setGravity(Gravity.CENTER_VERTICAL);
      card.setClickable(false);
      card.setFocusable(false);
      ProgressBar progress = new ProgressBar(context);
      progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams spinner = new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18));
      spinner.setMarginEnd(dp(context, 8));
      card.addView(progress, spinner);
      TextView finding = text(context, context.getString(R.string.riga_alternatives_finding), 13, false, 0);
      finding.setSingleLine(false);
      finding.setMaxLines(2);
      finding.setMaxWidth(dp(context, 120));
      card.addView(finding, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                                                          LinearLayout.LayoutParams.WRAP_CONTENT));
      row.addView(card, cardParams(context));
    }
    else if (info.laterAlternatives != null)
    {
      LinearLayout card = card(context, outline, false);
      card.setGravity(Gravity.CENTER);
      boolean earlier = app.organicmaps.transit.RigaTimeChoice.mode() == app.organicmaps.transit.RigaTimeChoice.Mode.ARRIVE;
      card.addView(text(context, context.getString(earlier ? R.string.riga_alternatives_earlier
          : R.string.riga_alternatives_later) + " →", 15, true, accent));
      Runnable later = info.laterAlternatives;
      card.setOnClickListener(v -> {
        v.setEnabled(false);
        ((TextView) ((LinearLayout) v).getChildAt(0)).setText(R.string.riga_alternatives_finding);
        later.run();
      });
      row.addView(card, cardParams(context));
    }
    final View target = selectedCard;
    if (samePlan)
    {
      // After the new cards are laid out: before that the row is narrower and the position would be clamped.
      row.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob)
        {
          row.removeOnLayoutChangeListener(this);
          scroll.scrollTo(keptScroll, 0);
        }
      });
      scroll.scrollTo(keptScroll, 0);
    }
    else if (target != null)
      scroll.post(() -> {
        int left = target.getLeft(), right = target.getRight();
        if (left < scroll.getScrollX() || right > scroll.getScrollX() + scroll.getWidth())
          scroll.smoothScrollTo(Math.max(0, left - dp(context, 8)), 0);
      });
  }

  /**
   * "Also in 2 min, 15 min": the first ride's other departures (any line making the same ride), as a
   * countdown within the hour, otherwise by the clock. Empty when there are none still to come.
   */
  @NonNull
  static String also(@NonNull Context context, @NonNull long[] departures, long now, @NonNull SimpleDateFormat clock)
  {
    StringBuilder minutes = new StringBuilder(), times = new StringBuilder();
    int listed = 0;
    for (long departure : departures)
    {
      // Two are enough on a narrow card.
      if (departure < now || listed == 2) continue;
      ++listed;
      long wait = (departure - now + 59999) / 60000;
      if (wait <= 60) minutes.append(minutes.length() > 0 ? ", " : "").append(context.getString(R.string.riga_trip_chip_minutes, (int) wait));
      else times.append(times.length() > 0 ? ", " : "").append(clock.format(new Date(departure)));
    }
    if (minutes.length() > 0) return context.getString(R.string.riga_also_in, minutes);
    return times.length() > 0 ? context.getString(R.string.riga_also_at, times) : "";
  }

  private static LinearLayout card(Context context, int stroke, boolean selected)
  {
    LinearLayout card = new LinearLayout(context);
    card.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(context, 10);
    card.setPadding(pad, dp(context, 8), pad, dp(context, 8));
    card.setMinimumWidth(dp(context, 112));
    GradientDrawable background = new GradientDrawable();
    background.setCornerRadius(dp(context, 12));
    background.setStroke(dp(context, selected ? 2 : 1), stroke);
    background.setColor(selected ? ColorUtils.setAlphaComponent(stroke, 0x1a) : Color.TRANSPARENT);
    card.setBackground(background);
    card.setClickable(true);
    card.setFocusable(true);
    return card;
  }

  private static LinearLayout.LayoutParams cardParams(Context context)
  {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -1);
    params.setMarginEnd(dp(context, 8));
    return params;
  }

  /** "7" of "7/14": a grouped badge lists the boarded line first. */
  @NonNull
  static String boarded(@NonNull String number)
  {
    int slash = number.indexOf('/');
    return slash > 0 ? number.substring(0, slash) : number;
  }

  private static TextView badge(Context context, String number, int color)
  {
    TextView badge = text(context, number, 13, true, ColorUtils.calculateLuminance(color) > 0.4 ? 0xff1a1a1a : Color.WHITE);
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setCornerRadius(dp(context, 4));
    background.setStroke(Math.max(1, dp(context, 1)), ColorUtils.blendARGB(color, Color.BLACK, 0.3f));
    badge.setBackground(background);
    badge.setPadding(dp(context, 6), dp(context, 1), dp(context, 6), dp(context, 1));
    return badge;
  }

  /** A walking icon with its minutes, quieter than the coloured line badges. */
  private static TextView walk(Context context, String minutes)
  {
    TextView walk = text(context, minutes, 13, false, 0);
    android.graphics.drawable.Drawable icon = androidx.core.content.ContextCompat.getDrawable(context,
        app.organicmaps.sdk.routing.TransitStepType.PEDESTRIAN.getDrawable());
    if (icon != null)
    {
      icon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate());
      androidx.core.graphics.drawable.DrawableCompat.setTint(icon, walk.getCurrentTextColor());
      int size = dp(context, 16);
      icon.setBounds(0, 0, size, size);
      walk.setCompoundDrawables(icon, null, null, null);
    }
    walk.setContentDescription(context.getResources().getQuantityString(R.plurals.riga_walk_minutes,
        Integer.parseInt(minutes), Integer.parseInt(minutes)));
    return walk;
  }

  private static TextView text(Context context, CharSequence value, int sp, boolean bold, int color)
  {
    TextView text = new TextView(context);
    text.setText(value);
    text.setTextSize(sp);
    text.setSingleLine(true);
    text.setFontFeatureSettings("tnum"); // Times line up across cards and do not jitter on live updates.
    if (bold) text.setTypeface(text.getTypeface(), Typeface.BOLD);
    if (color != 0) text.setTextColor(color);
    return text;
  }

  private static int dp(Context context, int value)
  {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
