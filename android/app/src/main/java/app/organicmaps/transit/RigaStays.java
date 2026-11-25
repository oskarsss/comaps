package app.organicmaps.transit;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import java.util.Locale;

/**
 * How long the rider spends at each stop of a multi-stop public transport journey. Kept per place (rounded to
 * about 10 m), so a stop moved or re-added keeps its time while the route is edited.
 */
public final class RigaStays
{
  public static final long DEFAULT = 30 * 60000L;
  public static final long MIN = 5 * 60000L;
  public static final long MAX = 4 * 3600000L;
  /** The choices offered, in minutes. */
  public static final int[] CHOICES = {5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240};

  private RigaStays() {}

  @NonNull
  static String key(double lat, double lon)
  {
    return String.format(Locale.ROOT, "stay-%.4f,%.4f", lat, lon);
  }

  public static long get(@NonNull Context context, double lat, double lon)
  {
    return clamp(preferences(context).getLong(key(lat, lon), DEFAULT));
  }

  public static void set(@NonNull Context context, double lat, double lon, long millis)
  {
    preferences(context).edit().putLong(key(lat, lon), clamp(millis)).apply();
  }

  static long clamp(long millis) { return Math.max(MIN, Math.min(MAX, millis)); }

  private static SharedPreferences preferences(Context context)
  {
    return context.getSharedPreferences("riga_stays", Context.MODE_PRIVATE);
  }
}
