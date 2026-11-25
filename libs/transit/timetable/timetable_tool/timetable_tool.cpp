#include "transit/timetable/builder/gtfs_builder.hpp"
#include "transit/timetable/calendar.hpp"
#include "transit/timetable/timetable.hpp"

#include "base/logging.hpp"
#include "base/string_utils.hpp"
#include "base/timer.hpp"

#include <gflags/gflags.h>

#include <ctime>
#include <iostream>

DEFINE_string(feeds, "", "Comma-separated GTFS feed directories, each as prefix=directory.");
DEFINE_string(out, "", "Timetable file to write.");
DEFINE_string(timetable, "", "Timetable file to read.");
DEFINE_int32(days, 370, "Number of service days to write, starting yesterday.");
DEFINE_double(lat, 0, "Latitude for departures.");
DEFINE_double(lon, 0, "Longitude for departures.");
DEFINE_int32(count, 10, "Number of departures.");

namespace
{
using namespace transit::timetable;

int Build()
{
  if (FLAGS_days <= 0)
  {
    LOG(LWARNING, ("--days must be positive"));
    return 1;
  }

  GtfsBuilder builder;
  base::Timer timer;
  for (auto const & feed : strings::Tokenize(FLAGS_feeds, ","))
  {
    auto const separator = feed.find('=');
    std::string const prefix(separator == std::string_view::npos ? "" : feed.substr(0, separator));
    std::string const directory(separator == std::string_view::npos ? feed : feed.substr(separator + 1));
    if (!builder.AddFeed(directory, prefix))
    {
      LOG(LWARNING, ("Cannot read feed", directory));
      return 1;
    }
  }
  LOG(LINFO, ("Feeds read in", timer.ElapsedSeconds(), "s"));

  timer.Reset();
  Day const yesterday = static_cast<Day>(std::time(nullptr) / (24 * 60 * 60)) - 1;
  if (!builder.Write(yesterday, static_cast<uint32_t>(FLAGS_days), FLAGS_out))
    return 1;
  LOG(LINFO, ("Timetable written in", timer.ElapsedSeconds(), "s"));
  return 0;
}

int Show()
{
  base::Timer timer;
  auto const timetable = Timetable::Load(FLAGS_timetable);
  if (!timetable)
    return 1;
  LOG(LINFO, ("Timetable loaded in", timer.ElapsedMilliseconds(), "ms"));

  auto const days = timetable->GetServiceDays();
  std::cout << "Service days: " << days.m_first << " - " << days.m_last << "\n";
  if (FLAGS_lat == 0 && FLAGS_lon == 0)
    return 0;

  auto const stops = timetable->FindStops(ms::LatLon(FLAGS_lat, FLAGS_lon), 300);
  std::time_t const now = std::time(nullptr);
  for (StopId stop : stops)
  {
    timer.Reset();
    auto const departures = timetable->GetDepartures(stop, now, static_cast<size_t>(FLAGS_count));
    std::cout << timetable->GetStopName(stop) << " (" << timer.ElapsedMilliseconds() << " ms)\n";
    for (auto const & departure : departures)
    {
      std::cout << "  +" << (departure.m_time - now) / 60 << " min  " << departure.m_line << "  "
                << departure.m_headsign << "\n";
    }
  }
  return 0;
}
}  // namespace

int main(int argc, char ** argv)
{
  gflags::SetUsageMessage("Builds and inspects public transport timetable files.");
  gflags::ParseCommandLineFlags(&argc, &argv, true);
  if (!FLAGS_feeds.empty() && !FLAGS_out.empty())
    return Build();
  if (!FLAGS_timetable.empty())
    return Show();
  gflags::ShowUsageWithFlags(argv[0]);
  return 1;
}
