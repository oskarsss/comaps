#include "testing/testing.hpp"

#include "transit/timetable/builder/gtfs_builder.hpp"
#include "transit/timetable/calendar.hpp"
#include "transit/timetable/timetable.hpp"

#include "platform/platform_tests_support/scoped_dir.hpp"
#include "platform/platform_tests_support/scoped_file.hpp"

#include "coding/writer.hpp"

#include "base/file_name_utils.hpp"

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace timetable_tests
{
using namespace transit::timetable;
using platform::tests_support::ScopedDir;
using platform::tests_support::ScopedFile;

int64_t constexpr kHour = 3600;

class Feed
{
public:
  explicit Feed(std::string const & name) : m_dir(name) {}

  void Add(std::string const & file, std::string const & contents)
  {
    m_files.push_back(std::make_unique<ScopedFile>(base::JoinPath(m_dir.GetRelativePath(), file), contents));
  }

  std::string const & GetPath() const { return m_dir.GetFullPath(); }

private:
  ScopedDir m_dir;
  std::vector<std::unique_ptr<ScopedFile>> m_files;
};

void AddSampleFiles(Feed & feed, std::string const & zone = "Europe/Riga", std::string const & extraTrips = "",
                    std::string const & extraStopTimes = "")
{
  feed.Add("agency.txt",
           "agency_id,agency_name,agency_url,agency_timezone\n"
           "a1,Sample Transit,https://example.org," +
               zone + "\n");
  feed.Add("stops.txt",
           "stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station\n"
           "P,Central,56.9500,24.1000,1,\n"
           "S1,Central,56.9500,24.1000,0,P\n"
           "S2,Middle,56.9600,24.1100,0,\n"
           "S3,Last,56.9700,24.1200,0,\n");
  feed.Add("routes.txt",
           "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\n"
           "R1,a1,1,Central - Last,3,FF0000\n"
           "R2,a1,,Tram line,900,\n");
  feed.Add("calendar.txt",
           "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
           "WK,1,1,1,1,1,0,0,20260302,20260331\n"
           "SUN,0,0,0,0,0,0,1,20260302,20260331\n");
  feed.Add("calendar_dates.txt",
           "service_id,date,exception_type\n"
           "WK,20260305,2\n"
           "SUN,20260306,1\n");
  feed.Add("trips.txt",
           "route_id,service_id,trip_id,trip_headsign\n"
           "R1,WK,T1,North\n"
           "R1,WK,T2,Night\n"
           "R2,SUN,T3,Tram north\n" +
               extraTrips);
  feed.Add("stop_times.txt",
           "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n"
           "T1,08:00:00,08:00:00,S1,1,0,0\n"
           "T1,,,S2,2,0,0\n"
           "T1,08:20:00,08:20:00,S3,3,0,0\n"
           "T2,25:10:00,25:10:00,S1,1,0,0\n"
           "T2,25:30:00,25:30:00,S3,2,0,0\n"
           "T3,10:00:00,10:00:00,S1,1,0,0\n"
           "T3,10:05:00,10:05:00,S2,2,1,0\n"
           "T3,10:15:00,10:15:00,S3,3,0,0\n" +
               extraStopTimes);
}

std::unique_ptr<Timetable> Build(GtfsBuilder const & builder)
{
  std::vector<uint8_t> bytes;
  MemWriter<std::vector<uint8_t>> writer(bytes);
  TEST(builder.Write(DayFromCivil(2026, 3, 1), 40, writer), ());
  return Timetable::Load(bytes);
}

std::unique_ptr<Timetable> BuildSample()
{
  Feed feed("timetable_sample");
  AddSampleFiles(feed);
  GtfsBuilder builder;
  TEST(builder.AddFeed(feed.GetPath(), "x:"), ());
  return Build(builder);
}

std::time_t LocalTime(int month, unsigned day, int64_t secondsFromMidnight, int64_t utcOffsetHours)
{
  return DayFromCivil(2026, month, day) * 24 * kHour + secondsFromMidnight - utcOffsetHours * kHour;
}

StopId FindStop(Timetable const & timetable, std::string_view name)
{
  for (StopId stop : timetable.FindStops(ms::LatLon(56.96, 24.11), 5000))
    if (timetable.GetStopName(stop) == name)
      return stop;
  TEST(false, ("No stop", name));
  return 0;
}

UNIT_TEST(Timetable_ServiceDays)
{
  auto const timetable = BuildSample();
  TEST(timetable, ());
  auto const days = timetable->GetServiceDays();
  TEST_EQUAL(days.m_first, DayFromCivil(2026, 3, 2), ());
  TEST_EQUAL(days.m_last, DayFromCivil(2026, 3, 31), ());
}

UNIT_TEST(Timetable_FindStopsSkipsStations)
{
  auto const timetable = BuildSample();
  auto const stops = timetable->FindStops(ms::LatLon(56.95, 24.10), 2000);
  TEST_EQUAL(stops.size(), 2, ());
  TEST_EQUAL(timetable->GetStopName(stops[0]), "Central", ());
  TEST_EQUAL(timetable->GetStopName(stops[1]), "Middle", ());
}

UNIT_TEST(Timetable_DeparturesOnWeekday)
{
  auto const timetable = BuildSample();
  auto const central = FindStop(*timetable, "Central");
  auto const departures = timetable->GetDepartures(central, LocalTime(3, 2, 7 * kHour, 2), 10);
  TEST_EQUAL(departures.size(), 2, ());
  TEST_EQUAL(departures[0].m_time, LocalTime(3, 2, 8 * kHour, 2), ());
  TEST_EQUAL(departures[0].m_line, "1", ());
  TEST_EQUAL(departures[0].m_headsign, "North", ());
  TEST(departures[0].m_mode == Mode::Bus, ());
  TEST_EQUAL(departures[0].m_color, std::optional<uint32_t>(0xFF0000), ());
  TEST_EQUAL(departures[1].m_time, LocalTime(3, 3, 1 * kHour + 10 * 60, 2), ());
}

UNIT_TEST(Timetable_InterpolatesMissingTimes)
{
  auto const timetable = BuildSample();
  auto const middle = FindStop(*timetable, "Middle");
  auto const departures = timetable->GetDepartures(middle, LocalTime(3, 2, 7 * kHour, 2), 1);
  TEST_EQUAL(departures.size(), 1, ());
  TEST_EQUAL(departures[0].m_time, LocalTime(3, 2, 8 * kHour + 10 * 60, 2), ());
}

UNIT_TEST(Timetable_CalendarExceptions)
{
  auto const timetable = BuildSample();
  auto const central = FindStop(*timetable, "Central");

  TEST(timetable->GetDepartures(central, LocalTime(3, 5, 7 * kHour, 2), 10).empty(), ());

  auto const added = timetable->GetDepartures(central, LocalTime(3, 6, 9 * kHour, 2), 1);
  TEST_EQUAL(added.size(), 1, ());
  TEST_EQUAL(added[0].m_time, LocalTime(3, 6, 10 * kHour, 2), ());
  TEST(added[0].m_mode == Mode::Tram, ());
  TEST_EQUAL(added[0].m_line, "Tram line", ());
  TEST(!added[0].m_color, ());
}

UNIT_TEST(Timetable_SummerTime)
{
  auto const timetable = BuildSample();
  auto const central = FindStop(*timetable, "Central");
  auto const departures = timetable->GetDepartures(central, LocalTime(3, 30, 7 * kHour, 3), 1);
  TEST_EQUAL(departures.size(), 1, ());
  TEST_EQUAL(departures[0].m_time, LocalTime(3, 30, 8 * kHour, 3), ());
}

UNIT_TEST(Timetable_ServiceDayStartsAtNoonMinus12hOnDstDay)
{
  auto const timetable = BuildSample();
  auto const central = FindStop(*timetable, "Central");
  auto const departures = timetable->GetDepartures(central, LocalTime(3, 29, 5 * kHour, 3), 1);
  TEST_EQUAL(departures.size(), 1, ());
  TEST_EQUAL(departures[0].m_time, LocalTime(3, 29, 10 * kHour, 3), ());
}

UNIT_TEST(Timetable_ExpandsFrequencies)
{
  Feed feed("timetable_frequencies");
  AddSampleFiles(feed, "Europe/Riga", "R1,WK,T4,Shuttle\n",
                 "T4,06:00:00,06:00:00,S1,1,0,0\n"
                 "T4,06:10:00,06:10:00,S3,2,0,0\n");
  feed.Add("frequencies.txt",
           "trip_id,start_time,end_time,headway_secs\n"
           "T4,06:00:00,07:00:00,1200\n");
  GtfsBuilder builder;
  TEST(builder.AddFeed(feed.GetPath(), ""), ());
  auto const timetable = Build(builder);
  auto const central = FindStop(*timetable, "Central");
  auto const departures = timetable->GetDepartures(central, LocalTime(3, 2, 5 * kHour, 2), 4);
  TEST_EQUAL(departures.size(), 4, ());
  TEST_EQUAL(departures[0].m_time, LocalTime(3, 2, 6 * kHour, 2), ());
  TEST_EQUAL(departures[1].m_time, LocalTime(3, 2, 6 * kHour + 20 * 60, 2), ());
  TEST_EQUAL(departures[2].m_time, LocalTime(3, 2, 6 * kHour + 40 * 60, 2), ());
  TEST_EQUAL(departures[2].m_headsign, "Shuttle", ());
  TEST_EQUAL(departures[3].m_time, LocalTime(3, 2, 8 * kHour, 2), ());
}

UNIT_TEST(Timetable_FindsDeparturesLongAfterServiceDayStart)
{
  Feed feed("timetable_long_trip");
  AddSampleFiles(feed, "Europe/Riga", "R1,WK,T5,Overnight\n",
                 "T5,74:00:00,74:00:00,S1,1,0,0\n"
                 "T5,74:30:00,74:30:00,S3,2,0,0\n");
  GtfsBuilder builder;
  TEST(builder.AddFeed(feed.GetPath(), ""), ());
  auto const timetable = Build(builder);
  auto const central = FindStop(*timetable, "Central");
  auto const departures = timetable->GetDepartures(central, LocalTime(3, 5, 1 * kHour + 30 * 60, 2), 10);
  auto const overnight = std::find_if(departures.begin(), departures.end(),
                                      [](Departure const & d) { return d.m_headsign == "Overnight"; });
  TEST(overnight != departures.end(), ());
  TEST_EQUAL(overnight->m_time, LocalTime(3, 5, 2 * kHour, 2), ());
}

UNIT_TEST(Timetable_KeepsTimeZonesOfMergedFeeds)
{
  Feed riga("timetable_riga");
  AddSampleFiles(riga);
  Feed tokyo("timetable_tokyo");
  AddSampleFiles(tokyo, "Asia/Tokyo");

  GtfsBuilder builder;
  TEST(builder.AddFeed(riga.GetPath(), "r:"), ());
  TEST(builder.AddFeed(tokyo.GetPath(), "t:"), ());
  auto const timetable = Build(builder);
  std::vector<std::time_t> firstDepartures;
  for (StopId stop : timetable->FindStops(ms::LatLon(56.95, 24.10), 10))
    firstDepartures.push_back(timetable->GetDepartures(stop, LocalTime(3, 1, 20 * kHour, 0), 1).at(0).m_time);
  std::sort(firstDepartures.begin(), firstDepartures.end());
  TEST_EQUAL(firstDepartures, (std::vector<std::time_t>{LocalTime(3, 2, 8 * kHour, 9), LocalTime(3, 2, 8 * kHour, 2)}),
             ());
}

UNIT_TEST(Timetable_WritesFile)
{
  Feed feed("timetable_write");
  AddSampleFiles(feed);
  GtfsBuilder builder;
  TEST(builder.AddFeed(feed.GetPath(), ""), ());

  std::string const path = base::JoinPath(feed.GetPath(), "out.ttb");
  TEST(builder.Write(DayFromCivil(2026, 3, 1), 40, path), ());
  TEST(Timetable::Load(path), ());
  TEST(!std::ifstream(path + ".tmp").good(), ());
  TEST(std::remove(path.c_str()) == 0, ());

  std::string const missing = base::JoinPath(feed.GetPath(), "missing", "out.ttb");
  TEST(!builder.Write(DayFromCivil(2026, 3, 1), 40, missing), ());
  TEST(!builder.Write(DayFromCivil(2026, 3, 1), 0, path), ());
  TEST(!std::ifstream(path).good(), ());
}

UNIT_TEST(Timetable_RejectsUnknownTimeZone)
{
  Feed feed("timetable_bad_zone");
  AddSampleFiles(feed, "Europe/Rigaa");
  GtfsBuilder builder;
  TEST(!builder.AddFeed(feed.GetPath(), ""), ());
}

UNIT_TEST(Timetable_NoBoardingAtLastStopOrWithoutPickup)
{
  auto const timetable = BuildSample();
  auto const last = FindStop(*timetable, "Last");
  TEST(timetable->GetDepartures(last, LocalTime(3, 2, 0, 2), 10).empty(), ());

  auto const middle = FindStop(*timetable, "Middle");
  auto const sunday = timetable->GetDepartures(middle, LocalTime(3, 8, 9 * kHour, 2), 10);
  TEST_EQUAL(sunday.size(), 1, ());
  TEST_EQUAL(sunday[0].m_time, LocalTime(3, 9, 8 * kHour + 10 * 60, 2), ());
}

UNIT_TEST(Timetable_MergesFeedsWithPrefixes)
{
  Feed first("timetable_first");
  AddSampleFiles(first);
  Feed second("timetable_second");
  AddSampleFiles(second);

  GtfsBuilder builder;
  TEST(builder.AddFeed(first.GetPath(), "a:"), ());
  TEST(builder.AddFeed(second.GetPath(), "b:"), ());
  auto const timetable = Build(builder);
  TEST(timetable, ());
  auto const stops = timetable->FindStops(ms::LatLon(56.95, 24.10), 10);
  TEST_EQUAL(stops.size(), 2, ());
  for (StopId stop : stops)
  {
    TEST_EQUAL(timetable->GetStopName(stop), "Central", ());
    auto const departures = timetable->GetDepartures(stop, LocalTime(3, 2, 7 * kHour, 2), 10);
    TEST_EQUAL(departures.size(), 2, ());
    TEST_EQUAL(departures[0].m_time, LocalTime(3, 2, 8 * kHour, 2), ());
    TEST_EQUAL(departures[0].m_line, "1", ());
    TEST_EQUAL(departures[0].m_headsign, "North", ());
    TEST_EQUAL(departures[1].m_time, LocalTime(3, 3, 1 * kHour + 10 * 60, 2), ());
    TEST_EQUAL(departures[1].m_headsign, "Night", ());
  }
}

UNIT_TEST(Timetable_RejectsIncompleteFeed)
{
  Feed feed("timetable_incomplete");
  feed.Add("agency.txt",
           "agency_id,agency_name,agency_url,agency_timezone\n"
           "a1,Sample Transit,https://example.org,Europe/Riga\n");
  GtfsBuilder builder;
  TEST(!builder.AddFeed(feed.GetPath(), ""), ());
}

std::vector<uint8_t> SampleBytes()
{
  Feed feed("timetable_corrupt");
  AddSampleFiles(feed);
  GtfsBuilder builder;
  TEST(builder.AddFeed(feed.GetPath(), ""), ());

  std::vector<uint8_t> bytes;
  MemWriter<std::vector<uint8_t>> writer(bytes);
  TEST(builder.Write(DayFromCivil(2026, 3, 1), 40, writer), ());
  return bytes;
}

void UseEverything(Timetable const & timetable)
{
  timetable.GetServiceDays();
  for (StopId stop : timetable.FindStops(ms::LatLon(56.95, 24.10), 1e7))
  {
    timetable.GetStopName(stop);
    timetable.GetStopPoint(stop);
    timetable.GetDepartures(stop, LocalTime(3, 2, 0, 2), 10);
  }
}

UNIT_TEST(Timetable_RejectsTruncatedFile)
{
  auto const bytes = SampleBytes();
  TEST(Timetable::Load(bytes), ());
  for (size_t size = 0; size < bytes.size(); ++size)
  {
    std::vector<uint8_t> const truncated(bytes.begin(), bytes.begin() + size);
    TEST(!Timetable::Load(truncated), (size));
  }
}

UNIT_TEST(Timetable_SurvivesCorruptBytes)
{
  auto const bytes = SampleBytes();
  for (size_t i = 0; i < bytes.size(); ++i)
  {
    for (uint8_t mask : {0x01, 0x80, 0xFF})
    {
      auto corrupt = bytes;
      corrupt[i] ^= mask;
      if (auto const timetable = Timetable::Load(corrupt))
        UseEverything(*timetable);
    }
  }
}

UNIT_TEST(Timetable_RejectsHugeLengths)
{
  std::vector<uint8_t> const bytes = {0x54, 0x54, 0x42, 0x4C, 0x01, 0x00, 0x01, 0x01, 0xF0, 0xFF, 0xFF, 0xFF, 0x0F};
  TEST(!Timetable::Load(bytes), ());
}
}  // namespace timetable_tests
