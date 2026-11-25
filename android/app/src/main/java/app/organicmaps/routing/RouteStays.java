package app.organicmaps.routing;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.util.StringUtils;

final class RouteStays
{
  private RouteStays() {}

  static int getMinutes(@NonNull Context context, double lat, double lon)
  {
    return preferences(context).getInt(key(lat, lon), 0);
  }

  static void setMinutes(@NonNull Context context, double lat, double lon, int minutes)
  {
    final SharedPreferences.Editor editor = preferences(context).edit();
    if (minutes == 0)
      editor.remove(key(lat, lon));
    else
      editor.putInt(key(lat, lon), minutes);
    editor.apply();
  }

  static int totalSeconds(@NonNull Context context, @NonNull RouteMarkData[] points)
  {
    int minutes = 0;
    for (RouteMarkData point : points)
      if (point.mPointType == RouteMarkType.Intermediate)
        minutes += getMinutes(context, point.mLat, point.mLon);
    return minutes * 60;
  }

  @NonNull
  private static String key(double lat, double lon)
  {
    return StringUtils.formatUsingUsLocale("%.4f,%.4f", lat, lon);
  }

  @NonNull
  private static SharedPreferences preferences(@NonNull Context context)
  {
    return context.getSharedPreferences("route_stays", Context.MODE_PRIVATE);
  }
}
