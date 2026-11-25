package app.organicmaps.transit;

/** Where the merged Latvian timetables apply. */
final class TransitArea
{
  private TransitArea() {}

  /** Trip ends and stop boards: Latvia, the Valga border station included. */
  static boolean covers(double lat, double lon)
  {
    return lat >= 55.6 && lat <= 58.15 && lon >= 20.8 && lon <= 28.3;
  }

  /** Stops, shapes and walking paths: a margin for border runs and slightly offset geometry. */
  static boolean plausible(double lat, double lon)
  {
    return Double.isFinite(lat) && Double.isFinite(lon) && lat >= 55.0 && lat <= 58.6 && lon >= 20.0 && lon <= 28.8;
  }
}
