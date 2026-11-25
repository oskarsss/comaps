package app.organicmaps.transit;

import java.util.ArrayList;
import java.util.List;

/** The operator's GPS format has no observation timestamp: received time is not vehicle age. */
final class RigaVehicle
{
  final String id;
  final String route;
  final int type;
  final double lat;
  final double lon;
  final String direction;
  final int startMinutes;
  final int delaySeconds;
  final long receivedAt;
  final boolean hasTripUpdate;

  private RigaVehicle(String id, String route, int type, double lat, double lon, String direction,
                      int startMinutes, int delaySeconds, long receivedAt, boolean hasTripUpdate)
  {
    this.id = id;
    this.route = route;
    this.type = type;
    this.lat = lat;
    this.lon = lon;
    this.direction = direction;
    this.startMinutes = startMinutes;
    this.delaySeconds = delaySeconds;
    this.receivedAt = receivedAt;
    this.hasTripUpdate = hasTripUpdate;
  }

  /** One trip record: the feed briefly lists a vehicle twice, on the trip it finished and the one it starts. */
  String key()
  {
    return id + "|" + direction + "|" + startMinutes;
  }

  static List<RigaVehicle> parse(String feed)
  {
    return parse(feed, System.currentTimeMillis());
  }

  static List<RigaVehicle> parse(String feed, long receivedAt)
  {
    List<RigaVehicle> vehicles = new ArrayList<>();
    for (String line : feed.split("\n"))
    {
      String[] fields = line.trim().split(",", -1);
      if (fields.length < 8 || fields[0].length() != 1 || fields[1].isEmpty() || fields[1].equals("-"))
        continue;
      try
      {
        int type = Integer.parseInt(fields[0]);
        double lon = Double.parseDouble(fields[2]) / 1000000.0;
        double lat = Double.parseDouble(fields[3]) / 1000000.0;
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < 56 || lat > 58 || lon < 23 || lon > 25.5
            || fields[7].isEmpty() || type < 1 || type > 7)
          continue;
        String direction = "";
        int startMinutes = -1;
        int delaySeconds = 0;
        boolean hasTripUpdate = false;
        if (fields.length >= 11)
        {
          try
          {
            direction = fields[8];
            startMinutes = Integer.parseInt(fields[9]);
            delaySeconds = Integer.parseInt(fields[10]);
            hasTripUpdate = !direction.isEmpty() && direction.length() <= 40 && startMinutes >= 0
                            && startMinutes < 72 * 60 && delaySeconds >= -300 && delaySeconds <= 7200;
          }
          catch (NumberFormatException ignored)
          {}
        }
        vehicles.add(new RigaVehicle(fields[7], fields[1], type, lat, lon, direction, startMinutes, delaySeconds,
                                     receivedAt, hasTripUpdate));
      }
      catch (NumberFormatException ignored)
      {}
    }
    return vehicles;
  }
}
