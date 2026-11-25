package app.organicmaps.routing;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.transit.RigaStays;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Lets the rider choose how long they spend at a stop of a public transport route; the route is then re-planned. */
public final class StayPicker
{
  private StayPicker() {}

  /** How long the rider chose to stay at {@code stay} (a stay leg). */
  static long chosen(@NonNull Context context, @NonNull TransitJourneyLeg stay)
  {
    return RigaStays.get(context, stay.stops[0].lat, stay.stops[0].lon);
  }

  /** A short duration: "45 min", "1 h", "1 h 30 min". */
  @NonNull
  static String duration(@NonNull Context context, long millis)
  {
    int minutes = (int) Math.round(millis / 60000.0);
    if (minutes < 60) return context.getString(R.string.riga_stay_minutes, minutes);
    if (minutes % 60 == 0) return context.getString(R.string.riga_stay_hours, minutes / 60);
    return context.getString(R.string.riga_stay_hours_minutes, minutes / 60, minutes % 60);
  }

  static void show(@NonNull Context context, @NonNull TransitJourneyLeg stay)
  {
    double lat = stay.stops[0].lat, lon = stay.stops[0].lon;
    // Where the stop is decided too: no trip to Manage Route to take it out again.
    pick(context, stay.headsign, lat, lon, () -> remove(lat, lon), () -> RoutingController.get().checkAndBuildRoute());
  }

  /**
   * Asks how long to stay at the stop {@code name} at {@code lat, lon}; {@code changed} runs once a different time is
   * kept. With {@code remove}, the dialog also offers to take the stop out of the route.
   */
  static void pick(@NonNull Context context, @NonNull String name, double lat, double lon,
                   @androidx.annotation.Nullable Runnable remove, @NonNull Runnable changed)
  {
    long current = RigaStays.get(context, lat, lon);
    String[] labels = new String[RigaStays.CHOICES.length];
    int checked = -1;
    for (int i = 0; i < labels.length; ++i)
    {
      labels[i] = duration(context, RigaStays.CHOICES[i] * 60000L);
      if (RigaStays.CHOICES[i] * 60000L == current) checked = i;
    }
    MaterialAlertDialogBuilder builder =
        new MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.riga_stay_pick_title, name))
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
              dialog.dismiss();
              long chosen = RigaStays.CHOICES[which] * 60000L;
              if (chosen == current) return;
              RigaStays.set(context, lat, lon, chosen);
              changed.run();
            })
            .setNegativeButton(android.R.string.cancel, null);
    if (remove != null) builder.setNeutralButton(R.string.riga_stay_remove, (dialog, which) -> remove.run());
    builder.show();
  }

  /**
   * Tells the rider, as a stop is added to a public transport route, how long the plan stays there (the time
   * delays the rest of the trip), with a way to change it. Shown above {@code anchor} when given.
   */
  public static void announce(@NonNull android.view.View host, @androidx.annotation.Nullable android.view.View anchor,
                              @NonNull String name, double lat, double lon)
  {
    Context context = host.getContext();
    String stay = duration(context, RigaStays.get(context, lat, lon));
    com.google.android.material.snackbar.Snackbar bar = com.google.android.material.snackbar.Snackbar.make(
        host, context.getString(R.string.riga_stay_added, stay, name),
        8000);
    bar.setAction(R.string.riga_stay_change, v -> pick(context, name, lat, lon, null,
                                                       () -> RoutingController.get().checkAndBuildRoute()));
    if (anchor != null)
      bar.setAnchorView(anchor);
    bar.show();
  }

  /** Takes the stop of the route at {@code lat, lon} out and plans the route again. */
  private static void remove(double lat, double lon)
  {
    app.organicmaps.sdk.routing.RouteMarkData stop = closestStop(app.organicmaps.sdk.Framework.nativeGetRoutePoints(),
                                                                 lat, lon);
    if (stop == null) return;
    RoutingController.get().removeStop(stop.mIntermediateIndex);
  }

  /** The route's stop (intermediate point) nearest to {@code lat, lon}, or null when the route has none. */
  @androidx.annotation.Nullable
  static app.organicmaps.sdk.routing.RouteMarkData closestStop(@NonNull app.organicmaps.sdk.routing.RouteMarkData[] points,
                                                               double lat, double lon)
  {
    app.organicmaps.sdk.routing.RouteMarkData best = null;
    double bestDistance = Double.MAX_VALUE;
    for (app.organicmaps.sdk.routing.RouteMarkData point : points)
    {
      if (point.mPointType != app.organicmaps.sdk.routing.RouteMarkType.Intermediate) continue;
      double distance = Math.hypot(point.mLat - lat, (point.mLon - lon) * Math.cos(Math.toRadians(lat)));
      if (distance < bestDistance) { bestDistance = distance; best = point; }
    }
    return best;
  }
}
