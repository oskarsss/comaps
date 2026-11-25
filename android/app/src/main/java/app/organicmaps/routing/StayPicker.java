package app.organicmaps.routing;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.R;
import app.organicmaps.util.Utils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class StayPicker
{
  private static final int[] CHOICES_MINUTES = {0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240};

  private StayPicker() {}

  @NonNull
  static String rowLabel(@NonNull Context context, int minutes)
  {
    if (minutes == 0)
      return context.getString(R.string.route_stay_add);
    return context.getString(R.string.route_stay_row, duration(context, minutes));
  }

  static void pick(@NonNull Context context, @NonNull String name, double lat, double lon, @NonNull Runnable changed)
  {
    final int current = RouteStays.getMinutes(context, lat, lon);
    final String[] labels = new String[CHOICES_MINUTES.length];
    int checked = -1;
    for (int i = 0; i < CHOICES_MINUTES.length; ++i)
    {
      labels[i] =
          CHOICES_MINUTES[i] == 0 ? context.getString(R.string.route_stay_none) : duration(context, CHOICES_MINUTES[i]);
      if (CHOICES_MINUTES[i] == current)
        checked = i;
    }
    new MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.route_stay_pick_title, name))
        .setSingleChoiceItems(labels, checked,
                              (dialog, which) -> {
                                dialog.dismiss();
                                if (CHOICES_MINUTES[which] == current)
                                  return;
                                RouteStays.setMinutes(context, lat, lon, CHOICES_MINUTES[which]);
                                changed.run();
                              })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  @NonNull
  private static String duration(@NonNull Context context, int minutes)
  {
    return Utils.formatRoutingTime(context, minutes * 60, R.dimen.text_size_routing_number).toString();
  }
}
