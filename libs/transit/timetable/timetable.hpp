#pragma once

#include "geometry/latlon.hpp"

#include <cstdint>
#include <ctime>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace transit::timetable
{
namespace detail
{
struct Data;
class StopIndex;
}  // namespace detail

enum class Mode : uint8_t
{
  Tram,
  Subway,
  Rail,
  Bus,
  Ferry,
  CableTram,
  AerialLift,
  Funicular,
  Trolleybus,
  Monorail,
  Other,
};

using StopId = uint32_t;

using Day = int32_t;

struct DayRange
{
  Day m_first = 0;
  Day m_last = -1;

  bool IsEmpty() const { return m_last < m_first; }
};

struct Departure
{
  std::time_t m_time = 0;
  Mode m_mode = Mode::Other;
  std::string_view m_line;
  std::string_view m_headsign;
  std::optional<uint32_t> m_color;
};

class Timetable
{
public:
  static std::unique_ptr<Timetable> Load(std::string const & path);
  static std::unique_ptr<Timetable> Load(std::vector<uint8_t> const & bytes);

  ~Timetable();

  DayRange GetServiceDays() const;

  std::vector<StopId> FindStops(ms::LatLon const & point, double radiusM) const;
  std::string_view GetStopName(StopId stop) const;
  ms::LatLon GetStopPoint(StopId stop) const;

  std::vector<Departure> GetDepartures(StopId stop, std::time_t from, size_t maxCount) const;

private:
  explicit Timetable(detail::Data && data);

  std::unique_ptr<detail::Data const> m_data;
  std::unique_ptr<detail::StopIndex const> m_stops;
};
}  // namespace transit::timetable
