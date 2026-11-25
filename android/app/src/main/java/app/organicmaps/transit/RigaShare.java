package app.organicmaps.transit;

import androidx.annotation.NonNull;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The text a transit route is shared as: where from, where to and when, then a link that opens the same plan
 * (https://comaps.at/route?sll=..&saddr=..&dll=..&daddr=..&type=transit&depart=2026-11-24T08:30, Riga time).
 */
public final class RigaShare
{
  private RigaShare() {}

  @NonNull
  public static String text(@NonNull String from, double fromLat, double fromLon, @NonNull String to, double toLat,
                            double toLon, long leave, long arrival, long now)
  {
    SimpleDateFormat clock = format("HH:mm");
    SimpleDateFormat day = format("yyyy-MM-dd");
    StringBuilder text = new StringBuilder();
    text.append(from).append(" → ").append(to).append(", ");
    // Another day than today leads with the date, so a plan for tomorrow is not read as today's.
    if (!day.format(new Date(leave)).equals(day.format(new Date(now))))
      text.append(format("d.MM.").format(new Date(leave))).append(' ');
    text.append(clock.format(new Date(leave)));
    if (arrival > leave) text.append('–').append(clock.format(new Date(arrival)));
    text.append('\n').append(link(from, fromLat, fromLon, to, toLat, toLon, leave));
    return text.toString();
  }

  @NonNull
  public static String link(@NonNull String from, double fromLat, double fromLon, @NonNull String to, double toLat,
                            double toLon, long leave)
  {
    return "https://comaps.at/route?sll=" + coordinates(fromLat, fromLon) + "&saddr=" + encode(from)
        + "&dll=" + coordinates(toLat, toLon) + "&daddr=" + encode(to) + "&type=transit&depart="
        + format("yyyy-MM-dd'T'HH:mm").format(new Date(leave));
  }

  private static String coordinates(double lat, double lon)
  {
    return String.format(Locale.US, "%.6f,%.6f", lat, lon);
  }

  private static String encode(String text)
  {
    try
    {
      return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
    }
    catch (java.io.UnsupportedEncodingException e)
    {
      throw new AssertionError(e);
    }
  }

  private static SimpleDateFormat format(String pattern)
  {
    SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
    format.setTimeZone(RigaTransitStore.ZONE);
    return format;
  }
}
