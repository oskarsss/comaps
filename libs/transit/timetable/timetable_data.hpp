#pragma once

#include "transit/timetable/timetable.hpp"

#include "geometry/latlon.hpp"

#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <vector>

class Reader;
class Writer;

namespace transit::timetable::detail
{
using Index = uint32_t;
inline constexpr Index kNoIndex = std::numeric_limits<Index>::max();
inline constexpr int64_t kSecondsInDay = 24 * 60 * 60;
inline constexpr uint32_t kMaxDayCount = 3660;
inline constexpr int32_t kMaxTripTime = 7 * kSecondsInDay;

struct Zone
{
  std::string m_name;
  std::vector<int64_t> m_dayStarts;
};

struct Agency
{
  std::string m_id;
  std::string m_name;
  std::string m_url;
  std::string m_fareUrl;
  Index m_zone = kNoIndex;
};

struct Stop
{
  std::string m_id;
  std::string m_name;
  std::string m_platform;
  ms::LatLon m_point;
  Index m_parent = kNoIndex;
};

struct Route
{
  std::string m_id;
  std::string m_shortName;
  std::string m_longName;
  Index m_agency = kNoIndex;
  Mode m_mode = Mode::Other;
  std::optional<uint32_t> m_color;
  std::optional<uint32_t> m_textColor;
};

inline constexpr uint8_t kNoPickup = 1 << 0;
inline constexpr uint8_t kNoDropOff = 1 << 1;

struct Pattern
{
  Index m_route = kNoIndex;
  std::vector<Index> m_stops;
  std::vector<uint8_t> m_rules;
};

struct StopTime
{
  int32_t m_arrival = 0;
  int32_t m_departure = 0;
};

struct Trip
{
  std::string m_id;
  std::string m_headsign;
  Index m_pattern = kNoIndex;
  Index m_service = kNoIndex;
  Index m_firstStopTime = 0;
};

struct Data
{
  Day m_firstDay = 0;
  uint32_t m_dayCount = 0;
  std::vector<Zone> m_zones;
  std::vector<Agency> m_agencies;
  std::vector<Stop> m_stops;
  std::vector<Route> m_routes;
  std::vector<Pattern> m_patterns;
  std::vector<Trip> m_trips;
  std::vector<StopTime> m_stopTimes;
  std::vector<std::vector<bool>> m_services;
};

void Serialize(Data const & data, Writer & writer);
Data Deserialize(Reader const & reader);
}  // namespace transit::timetable::detail
