#include "transit/timetable/timetable.hpp"

#include "transit/timetable/timetable_data.hpp"

#include "coding/file_reader.hpp"
#include "coding/reader.hpp"

#include "geometry/distance_on_sphere.hpp"

#include "base/assert.hpp"
#include "base/logging.hpp"

#include <algorithm>
#include <exception>

namespace transit::timetable
{
namespace detail
{
struct StopVisit
{
  Index m_pattern = kNoIndex;
  Index m_position = 0;
};

class StopIndex
{
public:
  explicit StopIndex(Data const & data) : m_visits(data.m_stops.size()), m_patternTrips(data.m_patterns.size())
  {
    for (Index p = 0; p < data.m_patterns.size(); ++p)
    {
      auto const & stops = data.m_patterns[p].m_stops;
      for (Index i = 0; i < stops.size(); ++i)
        m_visits[stops[i]].push_back({p, i});
    }
    for (Index t = 0; t < data.m_trips.size(); ++t)
      m_patternTrips[data.m_trips[t].m_pattern].push_back(t);
  }

  std::vector<StopVisit> const & Visits(StopId stop) const { return m_visits[stop]; }
  std::vector<Index> const & Trips(Index pattern) const { return m_patternTrips[pattern]; }

private:
  std::vector<std::vector<StopVisit>> m_visits;
  std::vector<std::vector<Index>> m_patternTrips;
};
}  // namespace detail

namespace
{
using namespace detail;

int64_t constexpr kHorizon = kSecondsInDay;
int32_t constexpr kLookBackDays = kMaxTripTime / kSecondsInDay + 1;

Zone const & ZoneOf(Data const & data, Route const & route)
{
  return data.m_zones[data.m_agencies[route.m_agency].m_zone];
}

bool IsActive(Data const & data, Index service, int64_t day)
{
  return day >= 0 && day < data.m_dayCount && data.m_services[service][day];
}

int64_t FirstDayBefore(Zone const & zone, std::time_t time)
{
  auto const it = std::upper_bound(zone.m_dayStarts.begin(), zone.m_dayStarts.end(), time);
  return static_cast<int64_t>(it - zone.m_dayStarts.begin()) - 1;
}
}  // namespace

std::unique_ptr<Timetable> Timetable::Load(std::string const & path)
{
  std::vector<uint8_t> bytes;
  try
  {
    FileReader reader(path);
    bytes.resize(reader.Size());
    reader.Read(0, bytes.data(), bytes.size());
  }
  catch (std::exception const & e)
  {
    LOG(LWARNING, ("Cannot read timetable", path, e.what()));
    return nullptr;
  }
  return Load(bytes);
}

std::unique_ptr<Timetable> Timetable::Load(std::vector<uint8_t> const & bytes)
{
  try
  {
    MemReaderWithExceptions reader(bytes.data(), bytes.size());
    return std::unique_ptr<Timetable>(new Timetable(Deserialize(reader)));
  }
  catch (std::exception const & e)
  {
    LOG(LWARNING, ("Corrupt timetable", e.what()));
    return nullptr;
  }
}

Timetable::Timetable(Data && data)
  : m_data(std::make_unique<Data const>(std::move(data)))
  , m_stops(std::make_unique<StopIndex const>(*m_data))
{}

Timetable::~Timetable() = default;

DayRange Timetable::GetServiceDays() const
{
  DayRange range;
  for (uint32_t day = 0; day < m_data->m_dayCount; ++day)
  {
    bool const active = std::any_of(m_data->m_services.begin(), m_data->m_services.end(),
                                    [day](auto const & days) { return days[day]; });
    if (!active)
      continue;
    if (range.IsEmpty())
      range.m_first = m_data->m_firstDay + static_cast<Day>(day);
    range.m_last = m_data->m_firstDay + static_cast<Day>(day);
  }
  return range;
}

std::vector<StopId> Timetable::FindStops(ms::LatLon const & point, double radiusM) const
{
  std::vector<std::pair<double, StopId>> found;
  for (StopId i = 0; i < m_data->m_stops.size(); ++i)
  {
    double const distance = ms::DistanceOnEarth(point, m_data->m_stops[i].m_point);
    if (distance <= radiusM && !m_stops->Visits(i).empty())
      found.emplace_back(distance, i);
  }
  std::sort(found.begin(), found.end());

  std::vector<StopId> stops;
  stops.reserve(found.size());
  for (auto const & [distance, stop] : found)
    stops.push_back(stop);
  return stops;
}

std::string_view Timetable::GetStopName(StopId stop) const
{
  CHECK_LESS(stop, m_data->m_stops.size(), ());
  return m_data->m_stops[stop].m_name;
}

ms::LatLon Timetable::GetStopPoint(StopId stop) const
{
  CHECK_LESS(stop, m_data->m_stops.size(), ());
  return m_data->m_stops[stop].m_point;
}

std::vector<Departure> Timetable::GetDepartures(StopId stop, std::time_t from, size_t maxCount) const
{
  CHECK_LESS(stop, m_data->m_stops.size(), ());
  auto const & data = *m_data;
  std::vector<Departure> departures;
  for (auto const & visit : m_stops->Visits(stop))
  {
    auto const & pattern = data.m_patterns[visit.m_pattern];
    if (visit.m_position + 1 == pattern.m_stops.size() || (pattern.m_rules[visit.m_position] & kNoPickup))
      continue;

    auto const & route = data.m_routes[pattern.m_route];
    auto const & zone = ZoneOf(data, route);
    int64_t const lastDay = FirstDayBefore(zone, from + kHorizon);
    for (Index tripIndex : m_stops->Trips(visit.m_pattern))
    {
      auto const & trip = data.m_trips[tripIndex];
      int32_t const offset = data.m_stopTimes[trip.m_firstStopTime + visit.m_position].m_departure;
      for (int64_t day = FirstDayBefore(zone, from) - kLookBackDays; day <= lastDay; ++day)
      {
        if (!IsActive(data, trip.m_service, day))
          continue;
        std::time_t const time = zone.m_dayStarts[day] + offset;
        if (time < from || time >= from + kHorizon)
          continue;
        departures.push_back({time, route.m_mode, route.m_shortName.empty() ? route.m_longName : route.m_shortName,
                              trip.m_headsign, route.m_color});
      }
    }
  }

  auto const byTime = [](Departure const & a, Departure const & b) { return a.m_time < b.m_time; };
  if (departures.size() > maxCount)
  {
    std::partial_sort(departures.begin(), departures.begin() + maxCount, departures.end(), byTime);
    departures.resize(maxCount);
  }
  else
  {
    std::sort(departures.begin(), departures.end(), byTime);
  }
  return departures;
}
}  // namespace transit::timetable
