package app.organicmaps.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Metre-based projection onto the operator's ordered GTFS shape. */
final class RigaJourneyGeometry
{
  static final class Point
  {
    final double lat;
    final double lon;
    Point(double lat, double lon) { this.lat = lat; this.lon = lon; }
  }

  static final class Projection
  {
    final double distance;
    final double along;
    Projection(double distance, double along) { this.distance = distance; this.along = along; }
  }

  final List<Point> points;
  final double[] lengths;

  RigaJourneyGeometry(List<Point> points)
  {
    this.points = Collections.unmodifiableList(new ArrayList<>(points));
    lengths = new double[points.size()];
    for (int i = 1; i < points.size(); ++i)
      lengths[i] = lengths[i - 1] + distance(points.get(i - 1), points.get(i));
  }

  static double distance(Point a, Point b)
  {
    double y = Math.toRadians(b.lat - a.lat);
    double x = Math.toRadians(b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2));
    return Math.sqrt(x * x + y * y) * 6371000;
  }

  Projection project(Point point, double minimum, double maximum)
  {
    double best = Double.POSITIVE_INFINITY;
    double along = 0;
    for (int i = 1; i < points.size(); ++i)
    {
      double start = lengths[i - 1];
      double span = lengths[i] - start;
      if (span <= 0 || lengths[i] < minimum || start > maximum)
        continue;
      Point a = points.get(i - 1);
      Point b = points.get(i);
      double scale = Math.cos(Math.toRadians(point.lat));
      double dx = (b.lon - a.lon) * scale;
      double dy = b.lat - a.lat;
      double px = (point.lon - a.lon) * scale;
      double py = point.lat - a.lat;
      double fraction = (px * dx + py * dy) / (dx * dx + dy * dy);
      fraction = Math.max(Math.max(0, (minimum - start) / span),
                          Math.min(Math.min(1, (maximum - start) / span), fraction));
      Point projected = new Point(a.lat + fraction * (b.lat - a.lat), a.lon + fraction * (b.lon - a.lon));
      double distance = distance(point, projected);
      if (distance < best)
      {
        best = distance;
        along = start + fraction * span;
      }
    }
    return new Projection(best, along);
  }

  double[] anchors(RigaJourneyPlanner.Timetable data, RigaJourneyPlanner.Trip trip)
  {
    if (points.size() < 2)
      return null;
    double[] anchors = new double[trip.events.size()];
    double minimum = 0;
    for (int i = 0; i < anchors.length; ++i)
    {
      RigaJourneyPlanner.Stop stop = data.stops.get(trip.events.get(i).stop);
      Projection projection = project(new Point(stop.lat, stop.lon), minimum, lengths[lengths.length - 1]);
      if (projection.distance > 150)
        return null;
      anchors[i] = projection.along;
      minimum = projection.along;
    }
    return anchors;
  }

  List<Point> clip(double from, double to)
  {
    List<Point> result = new ArrayList<>();
    result.add(at(from));
    for (int i = 1; i + 1 < points.size(); ++i)
      if (lengths[i] > from && lengths[i] < to)
        result.add(points.get(i));
    result.add(at(to));
    return result;
  }

  private Point at(double along)
  {
    for (int i = 1; i < points.size(); ++i)
    {
      if (lengths[i] < along || lengths[i] == lengths[i - 1])
        continue;
      double f = Math.max(0, (along - lengths[i - 1]) / (lengths[i] - lengths[i - 1]));
      Point a = points.get(i - 1);
      Point b = points.get(i);
      return new Point(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon));
    }
    return points.get(points.size() - 1);
  }
}
