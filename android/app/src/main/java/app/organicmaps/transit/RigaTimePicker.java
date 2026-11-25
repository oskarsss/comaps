package app.organicmaps.transit;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import app.organicmaps.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.Calendar;

/** Leave now / Leave at / Arrive by, with a day and a time: one control in the route panel, one dialog to choose. */
public final class RigaTimePicker
{
  private RigaTimePicker() {}

  /**
   * The route panel's one control for when to travel: {@code choice} reads what is chosen ("Leave now",
   * "Leave 15:30", "Arrive by tomorrow 08:00", or {@code compact}: "Now", "Arrive 18:45") and opens the choice;
   * {@code reset}, if any, shown only for a chosen time, goes back to leaving now; {@code refresh}, shown only
   * when leaving now, plans again from where the rider is at the current time.
   */
  public static void bind(@NonNull MaterialButton choice, @androidx.annotation.Nullable View reset,
                          @NonNull View refresh, boolean compact, @NonNull Runnable chosen)
  {
    Context context = choice.getContext();
    boolean timed = RigaTimeChoice.mode() != RigaTimeChoice.Mode.NOW;
    String label = compact ? RigaTimeChoice.shortLabel(context) : RigaTimeChoice.label(context);
    choice.setText(label);
    choice.setContentDescription(context.getString(R.string.riga_time_title) + ": " + RigaTimeChoice.label(context));
    choice.setOnClickListener(v -> show(context, chosen));
    if (reset != null)
    {
      reset.setVisibility(timed ? View.VISIBLE : View.GONE);
      reset.setOnClickListener(v -> {
        RigaTimeChoice.reset();
        chosen.run();
      });
    }
    refresh.setVisibility(timed ? View.GONE : View.VISIBLE);
    refresh.setOnClickListener(v -> chosen.run());
  }

  /** {@code hour:minute} on the day of {@code day}; a time already past today means tomorrow. */
  static long at(long day, int hour, int minute, long now)
  {
    Calendar result = Calendar.getInstance(RigaTransitStore.ZONE);
    result.setTimeInMillis(day);
    result.set(Calendar.HOUR_OF_DAY, hour);
    result.set(Calendar.MINUTE, minute);
    result.set(Calendar.SECOND, 0);
    result.set(Calendar.MILLISECOND, 0);
    if (result.getTimeInMillis() < now - 60000) result.add(Calendar.DAY_OF_MONTH, 1);
    return result.getTimeInMillis();
  }

  /**
   * Shows the choice: leave now, leave at or arrive by, and for a time its day (today, tomorrow or another) and
   * clock time. {@code chosen} runs after Done; Leave now and Done also plans again from where the rider is.
   */
  public static void show(@NonNull Context context, @NonNull Runnable chosen)
  {
    float density = context.getResources().getDisplayMetrics().density;
    int pad = Math.round(24 * density);
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(pad, Math.round(8 * density), pad, 0);

    int[] ids = {View.generateViewId(), View.generateViewId(), View.generateViewId()};
    MaterialButtonToggleGroup modes = segments(context, ids,
        new String[] {context.getString(R.string.riga_time_now), context.getString(R.string.riga_time_leave_at),
                      context.getString(R.string.riga_time_arrive)});
    content.addView(modes, new LinearLayout.LayoutParams(-1, -2));

    Calendar when = Calendar.getInstance(RigaTransitStore.ZONE);
    long now = System.currentTimeMillis();
    RigaTimeChoice.Mode current = RigaTimeChoice.mode();
    when.setTimeInMillis(current == RigaTimeChoice.Mode.NOW || RigaTimeChoice.time() < RigaTimeChoice.startOfDay(now)
                         ? now : RigaTimeChoice.time());
    // Until the rider sets a day or time: a fresh time starts at the next quarter of an hour, an hour later
    // to arrive by (one now could not be made).
    boolean[] touched = {current != RigaTimeChoice.Mode.NOW};
    java.util.function.IntConsumer fresh = later -> {
      when.setTimeInMillis(System.currentTimeMillis() + later * 60000L);
      when.set(Calendar.SECOND, 0);
      when.set(Calendar.MILLISECOND, 0);
      when.add(Calendar.MINUTE, 15 - when.get(Calendar.MINUTE) % 15);
    };
    if (!touched[0]) fresh.accept(0);

    // The day and the time, only for a chosen time: leaving now has nothing to set.
    LinearLayout timed = new LinearLayout(context);
    timed.setOrientation(LinearLayout.VERTICAL);
    int[] dayIds = {View.generateViewId(), View.generateViewId(), View.generateViewId()};
    MaterialButtonToggleGroup days = segments(context, dayIds,
        new String[] {context.getString(R.string.riga_time_today), context.getString(R.string.riga_time_tomorrow_day),
                      context.getString(R.string.riga_time_other_day)});
    MaterialButton otherDay = days.findViewById(dayIds[2]);
    timed.addView(days, new LinearLayout.LayoutParams(-1, -2));
    MaterialButton time = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    time.setIconResource(R.drawable.ic_clock);
    time.setTextSize(20);
    time.setFontFeatureSettings("tnum");
    LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-1, Math.round(56 * density));
    timeParams.topMargin = Math.round(8 * density);
    timed.addView(time, timeParams);
    LinearLayout.LayoutParams timedParams = new LinearLayout.LayoutParams(-1, -2);
    timedParams.topMargin = Math.round(16 * density);
    content.addView(timed, timedParams);

    boolean[] updating = {false};
    Runnable refresh = () -> {
      updating[0] = true;
      int day = RigaTimeChoice.daysBetween(System.currentTimeMillis(), when.getTimeInMillis());
      days.check(dayIds[day == 0 ? 0 : day == 1 ? 1 : 2]);
      otherDay.setText(day > 1 ? RigaTimeChoice.day(context, when.getTimeInMillis(), System.currentTimeMillis())
                               : context.getString(R.string.riga_time_other_day));
      time.setText(String.format(java.util.Locale.ROOT, "%02d:%02d", when.get(Calendar.HOUR_OF_DAY),
          when.get(Calendar.MINUTE)));
      timed.setVisibility(modes.getCheckedButtonId() == ids[0] ? View.GONE : View.VISIBLE);
      updating[0] = false;
    };
    modes.check(ids[current.ordinal()]);
    modes.addOnButtonCheckedListener((group, id, checked) -> {
      if (!checked) return;
      if (!touched[0]) fresh.accept(id == ids[2] ? 60 : 0);
      refresh.run();
    });
    refresh.run();

    days.addOnButtonCheckedListener((group, id, checked) -> {
      if (!checked || updating[0]) return;
      if (id == dayIds[2]) return; // Its tap opens the calendar, below.
      Calendar day = Calendar.getInstance(RigaTransitStore.ZONE);
      day.setTimeInMillis(System.currentTimeMillis());
      if (id == dayIds[1]) day.add(Calendar.DAY_OF_MONTH, 1);
      when.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH));
      touched[0] = true;
      refresh.run();
    });
    // "Other day" opens a calendar, also when it is already chosen; cancelled, the day stays as it was.
    otherDay.setOnClickListener(v -> {
      DatePickerDialog picker = new DatePickerDialog(context, (view, year, month, day) -> {
        when.set(year, month, day);
        touched[0] = true;
        refresh.run();
      }, when.get(Calendar.YEAR), when.get(Calendar.MONTH), when.get(Calendar.DAY_OF_MONTH));
      picker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
      picker.setOnCancelListener(d -> refresh.run());
      picker.show();
    });

    time.setOnClickListener(v -> new TimePickerDialog(context, (view, hour, minute) -> {
      when.set(Calendar.HOUR_OF_DAY, hour);
      when.set(Calendar.MINUTE, minute);
      touched[0] = true;
      refresh.run();
    }, when.get(Calendar.HOUR_OF_DAY), when.get(Calendar.MINUTE),
        true).show());

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.riga_time_title)
        .setView(content)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.done, (dialog, which) -> {
          int checked = modes.getCheckedButtonId();
          RigaTimeChoice.Mode mode = checked == ids[1] ? RigaTimeChoice.Mode.DEPART
              : checked == ids[2] ? RigaTimeChoice.Mode.ARRIVE : RigaTimeChoice.Mode.NOW;
          // Leaving at about this minute is leaving now. A time earlier today is kept: the rider looks again
          // at a route seen a moment ago (the day buttons say which day is meant).
          long chosenTime = when.getTimeInMillis();
          if (mode == RigaTimeChoice.Mode.DEPART && Math.abs(chosenTime - System.currentTimeMillis()) <= 60000)
            mode = RigaTimeChoice.Mode.NOW;
          RigaTimeChoice.set(mode, chosenTime);
          chosen.run();
        })
        .show();
  }

  /** A row of equal, single-choice outlined buttons. */
  @NonNull
  private static MaterialButtonToggleGroup segments(@NonNull Context context, @NonNull int[] ids,
                                                    @NonNull String[] labels)
  {
    MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(context);
    group.setSingleSelection(true);
    group.setSelectionRequired(true);
    for (int i = 0; i < ids.length; ++i)
    {
      MaterialButton button = new MaterialButton(context, null,
          com.google.android.material.R.attr.materialButtonOutlinedStyle);
      button.setId(ids[i]);
      button.setText(labels[i]);
      button.setMaxLines(1);
      button.setEllipsize(android.text.TextUtils.TruncateAt.END);
      button.setPaddingRelative(0, button.getPaddingTop(), 0, button.getPaddingBottom());
      group.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    return group;
  }
}
