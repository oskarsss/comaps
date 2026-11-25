#include "transit/timetable/builder/gtfs_builder.hpp"

#include "transit/timetable/calendar.hpp"
#include "transit/timetable/timetable_data.hpp"

#include "coding/file_writer.hpp"

#include "base/logging.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <exception>
#include <fstream>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <tuple>
#include <vector>

#include "3party/ankerl/unordered_dense.h"
#include "3party/just_gtfs/just_gtfs.h"

namespace transit::timetable
{
namespace builder_detail
{
using namespace detail;

struct ServiceRule
{
  uint8_t m_weekdays = 0;
  Day m_start = 0;
  Day m_end = -1;
  std::vector<Day> m_added;
  std::vector<Day> m_removed;
};

struct Part
{
  Data m_data;
  std::vector<ServiceRule> m_rules;
};

Mode ToMode(gtfs::RouteType type)
{
  auto const code = static_cast<int>(type);
  switch (code)
  {
  case 0: return Mode::Tram;
  case 1: return Mode::Subway;
  case 2: return Mode::Rail;
  case 3: return Mode::Bus;
  case 4: return Mode::Ferry;
  case 5: return Mode::CableTram;
  case 6: return Mode::AerialLift;
  case 7: return Mode::Funicular;
  case 11: return Mode::Trolleybus;
  case 12: return Mode::Monorail;
  case 405: return Mode::Monorail;
  case 800: return Mode::Trolleybus;
  case 1200: return Mode::Ferry;
  default: break;
  }
  switch (code / 100)
  {
  case 1: return Mode::Rail;
  case 2: return Mode::Bus;
  case 4: return Mode::Subway;
  case 7: return Mode::Bus;
  case 9: return Mode::Tram;
  case 10: return Mode::Ferry;
  case 13: return Mode::AerialLift;
  case 14: return Mode::Funicular;
  default: return Mode::Other;
  }
}

std::optional<uint32_t> ParseColor(std::string const & text)
{
  if (text.size() != 6)
    return {};
  char * end = nullptr;
  auto const value = std::strtoul(text.c_str(), &end, 16);
  if (end != text.c_str() + text.size())
    return {};
  return static_cast<uint32_t>(value);
}

Day ToDay(gtfs::Date const & date)
{
  auto const [year, month, day] = date.get_yyyy_mm_dd();
  return DayFromCivil(year, month, day);
}

bool IsKnownZone(std::string const & zone)
{
  if (zone.empty() || zone.front() == '/' || zone.find("..") != std::string::npos)
    return false;
  char const * dir = std::getenv("TZDIR");
  std::string const path = std::string(dir ? dir : "/usr/share/zoneinfo") + "/" + zone;
  std::ifstream file(path, std::ios::binary);
  char magic[4] = {};
  return file.read(magic, sizeof(magic)) && std::string_view(magic, sizeof(magic)) == "TZif";
}

class ScopedZone
{
public:
  explicit ScopedZone(std::string const & zone) : m_lock(Mutex())
  {
    char const * previous = std::getenv("TZ");
    if (previous)
      m_saved = previous;
    setenv("TZ", zone.c_str(), 1);
    tzset();
  }

  ~ScopedZone()
  {
    if (m_saved)
      setenv("TZ", m_saved->c_str(), 1);
    else
      unsetenv("TZ");
    tzset();
  }

private:
  static std::mutex & Mutex()
  {
    static std::mutex mutex;
    return mutex;
  }

  std::lock_guard<std::mutex> m_lock;
  std::optional<std::string> m_saved;
};

bool ComputeDayStarts(std::string const & zone, Day firstDay, uint32_t dayCount, std::vector<int64_t> & starts)
{
  ScopedZone const scoped(zone);
  starts.resize(dayCount);
  for (uint32_t day = 0; day < dayCount; ++day)
  {
    std::tm local = {};
    local.tm_year = 70;
    local.tm_mday = 1 + firstDay + static_cast<Day>(day);
    local.tm_hour = 12;
    local.tm_isdst = -1;
    std::time_t const noon = std::mktime(&local);
    if (noon == static_cast<std::time_t>(-1))
      return false;
    starts[day] = static_cast<int64_t>(noon) - kSecondsInDay / 2;
  }
  return true;
}

bool IsServiceActive(ServiceRule const & rule, Day day)
{
  if (std::binary_search(rule.m_removed.begin(), rule.m_removed.end(), day))
    return false;
  if (std::binary_search(rule.m_added.begin(), rule.m_added.end(), day))
    return true;
  return day >= rule.m_start && day <= rule.m_end && ((rule.m_weekdays >> Weekday(day)) & 1);
}

uint8_t ToRules(gtfs::StopTime const & stopTime)
{
  uint8_t rules = 0;
  if (stopTime.pickup_type == gtfs::StopTimeBoarding::No)
    rules |= kNoPickup;
  if (stopTime.drop_off_type == gtfs::StopTimeBoarding::No)
    rules |= kNoDropOff;
  return rules;
}

bool FillTimes(std::vector<gtfs::StopTime const *> const & stopTimes, std::vector<StopTime> & times)
{
  times.assign(stopTimes.size(), {});
  std::vector<bool> known(stopTimes.size(), false);
  for (size_t i = 0; i < stopTimes.size(); ++i)
  {
    auto const & arrival = stopTimes[i]->arrival_time;
    auto const & departure = stopTimes[i]->departure_time;
    if (!arrival.is_provided() && !departure.is_provided())
      continue;
    auto const & a = arrival.is_provided() ? arrival : departure;
    auto const & d = departure.is_provided() ? departure : arrival;
    times[i] = {static_cast<int32_t>(a.get_total_seconds()), static_cast<int32_t>(d.get_total_seconds())};
    known[i] = true;
  }
  if (!known.front() || !known.back())
    return false;

  size_t previous = 0;
  for (size_t i = 1; i < times.size(); ++i)
  {
    if (!known[i])
      continue;
    for (size_t j = previous + 1; j < i; ++j)
    {
      int32_t const from = times[previous].m_departure;
      int64_t const span = times[i].m_arrival - from;
      auto const step = static_cast<int64_t>(j - previous);
      auto const steps = static_cast<int64_t>(i - previous);
      auto const time = from + static_cast<int32_t>(span * step / steps);
      times[j] = {time, time};
    }
    previous = i;
  }

  for (size_t i = 0; i < times.size(); ++i)
  {
    if (times[i].m_departure < times[i].m_arrival || times[i].m_departure > kMaxTripTime)
      return false;
    if (i > 0 && times[i].m_arrival < times[i - 1].m_departure)
      return false;
  }
  return true;
}

class FeedReader
{
public:
  FeedReader(gtfs::Feed const & feed, std::string const & prefix) : m_feed(feed), m_prefix(prefix) {}

  std::optional<Part> Read()
  {
    if (!ReadAgencies())
      return {};
    ReadStops();
    ReadRoutes();
    ReadServices();
    ReadTrips();
    return std::move(m_part);
  }

private:
  std::string Id(std::string const & id) const { return m_prefix + id; }

  bool ReadAgencies()
  {
    std::map<std::string, Index> zones;
    for (auto const & source : m_feed.get_agencies())
    {
      if (!IsKnownZone(source.agency_timezone))
      {
        LOG(LWARNING, ("Unknown time zone", source.agency_timezone, "of agency", source.agency_id));
        return false;
      }
      auto const [it, inserted] = zones.emplace(source.agency_timezone, static_cast<Index>(zones.size()));
      if (inserted)
        m_part.m_data.m_zones.push_back({source.agency_timezone, {}});

      m_agencies.emplace(source.agency_id, static_cast<Index>(m_part.m_data.m_agencies.size()));
      m_part.m_data.m_agencies.push_back(
          {Id(source.agency_id), source.agency_name, source.agency_url, source.agency_fare_url, it->second});
    }
    return true;
  }

  void ReadStops()
  {
    auto & stops = m_part.m_data.m_stops;
    std::vector<std::pair<gtfs::Stop const *, Index>> added;
    for (auto const & source : m_feed.get_stops())
    {
      if (std::abs(source.stop_lat) > 90 || std::abs(source.stop_lon) > 180)
        continue;
      auto const index = static_cast<Index>(stops.size());
      m_stops.emplace(source.stop_id, index);
      added.emplace_back(&source, index);
      stops.push_back({Id(source.stop_id), source.stop_name, source.platform_code,
                       ms::LatLon(source.stop_lat, source.stop_lon), kNoIndex});
    }
    for (auto const & [source, index] : added)
      if (auto const it = m_stops.find(source->parent_station); it != m_stops.end())
        stops[index].m_parent = it->second;
  }

  void ReadRoutes()
  {
    auto & routes = m_part.m_data.m_routes;
    for (auto const & source : m_feed.get_routes())
    {
      Index agency = 0;
      if (auto const it = m_agencies.find(source.agency_id); it != m_agencies.end())
        agency = it->second;
      else if (m_agencies.size() != 1)
        continue;

      m_routes.emplace(source.route_id, static_cast<Index>(routes.size()));
      routes.push_back({Id(source.route_id), source.route_short_name, source.route_long_name, agency,
                        ToMode(source.route_type), ParseColor(source.route_color),
                        ParseColor(source.route_text_color)});
    }
  }

  Index Service(std::string const & id)
  {
    auto const [it, inserted] = m_services.emplace(id, static_cast<Index>(m_part.m_rules.size()));
    if (inserted)
      m_part.m_rules.emplace_back();
    return it->second;
  }

  void ReadServices()
  {
    for (auto const & item : m_feed.get_calendar())
    {
      auto & rule = m_part.m_rules[Service(item.service_id)];
      gtfs::CalendarAvailability const week[] = {item.monday, item.tuesday,  item.wednesday, item.thursday,
                                                 item.friday, item.saturday, item.sunday};
      for (unsigned day = 0; day < 7; ++day)
        if (week[day] == gtfs::CalendarAvailability::Available)
          rule.m_weekdays |= 1 << day;
      rule.m_start = ToDay(item.start_date);
      rule.m_end = ToDay(item.end_date);
    }
    for (auto const & item : m_feed.get_calendar_dates())
    {
      auto & rule = m_part.m_rules[Service(item.service_id)];
      if (item.exception_type == gtfs::CalendarDateException::Added)
        rule.m_added.push_back(ToDay(item.date));
      else
        rule.m_removed.push_back(ToDay(item.date));
    }
    for (auto & rule : m_part.m_rules)
    {
      std::sort(rule.m_added.begin(), rule.m_added.end());
      std::sort(rule.m_removed.begin(), rule.m_removed.end());
    }
    m_part.m_data.m_services.resize(m_part.m_rules.size());
  }

  void ReadTrips()
  {
    ankerl::unordered_dense::map<std::string, std::vector<gtfs::StopTime const *>> byTrip;
    for (auto const & stopTime : m_feed.get_stop_times())
      byTrip[stopTime.trip_id].push_back(&stopTime);
    for (auto const & frequency : m_feed.get_frequencies())
      m_frequencies[frequency.trip_id].push_back(&frequency);

    size_t skipped = 0;
    for (auto const & source : m_feed.get_trips())
    {
      auto const it = byTrip.find(source.trip_id);
      if (it == byTrip.end() || !AddTrip(source, it->second))
        ++skipped;
    }
    if (skipped != 0)
      LOG(LWARNING, ("Skipped", skipped, "trips of", m_feed.get_trips().size(), "with prefix", m_prefix));
  }

  std::optional<Pattern> MakePattern(Index route, std::vector<gtfs::StopTime const *> const & stopTimes) const
  {
    Pattern pattern;
    pattern.m_route = route;
    for (size_t i = 0; i < stopTimes.size(); ++i)
    {
      if (i > 0 && stopTimes[i]->stop_sequence == stopTimes[i - 1]->stop_sequence)
        return {};
      auto const stop = m_stops.find(stopTimes[i]->stop_id);
      if (stop == m_stops.end())
        return {};
      pattern.m_stops.push_back(stop->second);
      pattern.m_rules.push_back(ToRules(*stopTimes[i]));
    }
    return pattern;
  }

  bool AddTrip(gtfs::Trip const & source, std::vector<gtfs::StopTime const *> & stopTimes)
  {
    auto const route = m_routes.find(source.route_id);
    auto const service = m_services.find(source.service_id);
    if (route == m_routes.end() || service == m_services.end() || stopTimes.size() < 2)
      return false;

    std::sort(stopTimes.begin(), stopTimes.end(),
              [](auto const * a, auto const * b) { return a->stop_sequence < b->stop_sequence; });
    auto pattern = MakePattern(route->second, stopTimes);
    std::vector<StopTime> times;
    if (!pattern || !FillTimes(stopTimes, times))
      return false;

    auto const shifts = RunShifts(source.trip_id, times);
    if (shifts.empty())
      return false;

    Trip const trip{Id(source.trip_id), source.trip_headsign, AddPattern(std::move(*pattern)), service->second, 0};
    for (int64_t const shift : shifts)
      AddRun(trip, times, shift);
    return true;
  }

  std::vector<int64_t> RunShifts(std::string const & tripId, std::vector<StopTime> const & times) const
  {
    std::vector<int64_t> shifts;
    auto const fits = [&times](int64_t shift)
    { return times.front().m_arrival + shift >= 0 && times.back().m_departure + shift <= kMaxTripTime; };

    auto const frequencies = m_frequencies.find(tripId);
    if (frequencies == m_frequencies.end())
    {
      shifts.push_back(0);
      return shifts;
    }
    for (auto const * frequency : frequencies->second)
    {
      auto const start = static_cast<int64_t>(frequency->start_time.get_total_seconds());
      auto const end = static_cast<int64_t>(frequency->end_time.get_total_seconds());
      auto const headway = static_cast<int64_t>(frequency->headway_secs);
      for (int64_t first = start; headway > 0 && first < end; first += headway)
        if (auto const shift = first - times.front().m_departure; fits(shift))
          shifts.push_back(shift);
    }
    return shifts;
  }

  Index AddPattern(Pattern && pattern)
  {
    auto & data = m_part.m_data;
    auto const key = std::make_tuple(pattern.m_route, pattern.m_stops, pattern.m_rules);
    auto const [it, inserted] = m_patterns.emplace(key, static_cast<Index>(data.m_patterns.size()));
    if (inserted)
      data.m_patterns.push_back(std::move(pattern));
    return it->second;
  }

  void AddRun(Trip trip, std::vector<StopTime> const & times, int64_t shift)
  {
    auto & data = m_part.m_data;
    trip.m_firstStopTime = static_cast<Index>(data.m_stopTimes.size());
    data.m_trips.push_back(std::move(trip));
    for (auto const & time : times)
    {
      data.m_stopTimes.push_back(
          {static_cast<int32_t>(time.m_arrival + shift), static_cast<int32_t>(time.m_departure + shift)});
    }
  }

  gtfs::Feed const & m_feed;
  std::string m_prefix;
  Part m_part;
  ankerl::unordered_dense::map<std::string, Index> m_agencies;
  ankerl::unordered_dense::map<std::string, Index> m_stops;
  ankerl::unordered_dense::map<std::string, Index> m_routes;
  ankerl::unordered_dense::map<std::string, Index> m_services;
  ankerl::unordered_dense::map<std::string, std::vector<gtfs::Frequency const *>> m_frequencies;
  std::map<std::tuple<Index, std::vector<Index>, std::vector<uint8_t>>, Index> m_patterns;
};

template <typename T>
void Append(std::vector<T> & to, std::vector<T> && from)
{
  to.insert(to.end(), std::make_move_iterator(from.begin()), std::make_move_iterator(from.end()));
}

Index Shift(Index index, size_t offset)
{
  return index == kNoIndex ? kNoIndex : static_cast<Index>(index + offset);
}
}  // namespace builder_detail

using namespace builder_detail;

class GtfsBuilder::Impl
{
public:
  bool AddFeed(std::string const & directory, std::string const & prefix)
  {
    try
    {
      gtfs::Feed feed(directory);
      for (auto const & result : {feed.read_agencies(), feed.read_stops(), feed.read_routes(), feed.read_trips(),
                                  feed.read_stop_times()})
      {
        if (result != gtfs::ResultCode::OK)
        {
          LOG(LWARNING, ("Cannot read GTFS feed", directory, result.message));
          return false;
        }
      }
      for (auto const & result : {feed.read_calendar(), feed.read_calendar_dates(), feed.read_frequencies()})
      {
        if (result != gtfs::ResultCode::OK && result != gtfs::ResultCode::ERROR_FILE_ABSENT)
        {
          LOG(LWARNING, ("Cannot read optional GTFS file", directory, result.message));
          return false;
        }
      }
      auto part = FeedReader(feed, prefix).Read();
      if (!part)
        return false;
      Merge(std::move(*part));
      return true;
    }
    catch (std::exception const & e)
    {
      LOG(LWARNING, ("Cannot read GTFS feed", directory, e.what()));
      return false;
    }
  }

  std::optional<Data> Build(Day firstDay, uint32_t dayCount) const
  {
    if (dayCount == 0 || dayCount > kMaxDayCount)
    {
      LOG(LWARNING, ("Day count", dayCount, "must be between 1 and", kMaxDayCount));
      return {};
    }
    Data data = m_data;
    data.m_firstDay = firstDay;
    data.m_dayCount = dayCount;
    for (auto & zone : data.m_zones)
    {
      if (!ComputeDayStarts(zone.m_name, firstDay, dayCount, zone.m_dayStarts))
      {
        LOG(LWARNING, ("Cannot compute service days in", zone.m_name));
        return {};
      }
    }
    for (size_t service = 0; service < m_rules.size(); ++service)
    {
      auto & days = data.m_services[service];
      days.assign(dayCount, false);
      for (uint32_t day = 0; day < dayCount; ++day)
        days[day] = IsServiceActive(m_rules[service], firstDay + static_cast<Day>(day));
    }
    return data;
  }

private:
  void Merge(Part && part)
  {
    auto & from = part.m_data;
    size_t const agencies = m_data.m_agencies.size();
    size_t const stops = m_data.m_stops.size();
    size_t const routes = m_data.m_routes.size();
    size_t const patterns = m_data.m_patterns.size();
    size_t const services = m_data.m_services.size();
    size_t const stopTimes = m_data.m_stopTimes.size();

    for (auto & agency : from.m_agencies)
      agency.m_zone = ZoneIndex(from.m_zones[agency.m_zone].m_name);
    for (auto & stop : from.m_stops)
      stop.m_parent = Shift(stop.m_parent, stops);
    for (auto & route : from.m_routes)
      route.m_agency = Shift(route.m_agency, agencies);
    for (auto & pattern : from.m_patterns)
    {
      pattern.m_route = Shift(pattern.m_route, routes);
      for (auto & stop : pattern.m_stops)
        stop = Shift(stop, stops);
    }
    for (auto & trip : from.m_trips)
    {
      trip.m_pattern = Shift(trip.m_pattern, patterns);
      trip.m_service = Shift(trip.m_service, services);
      trip.m_firstStopTime = Shift(trip.m_firstStopTime, stopTimes);
    }

    Append(m_data.m_agencies, std::move(from.m_agencies));
    Append(m_data.m_stops, std::move(from.m_stops));
    Append(m_data.m_routes, std::move(from.m_routes));
    Append(m_data.m_patterns, std::move(from.m_patterns));
    Append(m_data.m_trips, std::move(from.m_trips));
    Append(m_data.m_stopTimes, std::move(from.m_stopTimes));
    Append(m_data.m_services, std::move(from.m_services));
    Append(m_rules, std::move(part.m_rules));
  }

  Index ZoneIndex(std::string const & name)
  {
    for (Index i = 0; i < m_data.m_zones.size(); ++i)
      if (m_data.m_zones[i].m_name == name)
        return i;
    m_data.m_zones.push_back({name, {}});
    return static_cast<Index>(m_data.m_zones.size() - 1);
  }

  Data m_data;
  std::vector<ServiceRule> m_rules;
};

GtfsBuilder::GtfsBuilder() : m_impl(std::make_unique<Impl>()) {}

GtfsBuilder::~GtfsBuilder() = default;

bool GtfsBuilder::AddFeed(std::string const & directory, std::string const & idPrefix)
{
  return m_impl->AddFeed(directory, idPrefix);
}

bool GtfsBuilder::Write(Day firstDay, uint32_t dayCount, ::Writer & writer) const
{
  auto const data = m_impl->Build(firstDay, dayCount);
  if (!data)
    return false;
  Serialize(*data, writer);
  return true;
}

bool GtfsBuilder::Write(Day firstDay, uint32_t dayCount, std::string const & path) const
{
  std::string const temporary = path + ".tmp";
  try
  {
    bool written = false;
    {
      FileWriter writer(temporary);
      written = Write(firstDay, dayCount, writer);
    }
    if (written && std::rename(temporary.c_str(), path.c_str()) == 0)
      return true;
    LOG(LWARNING, ("Cannot write timetable", path));
  }
  catch (std::exception const & e)
  {
    LOG(LWARNING, ("Cannot write timetable", path, e.what()));
  }
  std::remove(temporary.c_str());
  return false;
}
}  // namespace transit::timetable
