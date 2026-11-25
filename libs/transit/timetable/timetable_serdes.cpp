#include "transit/timetable/timetable_data.hpp"

#include "coding/read_write_utils.hpp"
#include "coding/reader.hpp"
#include "coding/varint.hpp"
#include "coding/write_to_sink.hpp"
#include "coding/writer.hpp"

#include "base/bits.hpp"
#include "base/exception.hpp"

#include <cmath>
#include <limits>
#include <string>

namespace transit::timetable::detail
{
namespace
{
DECLARE_EXCEPTION(CorruptTimetableException, RootException);

uint32_t constexpr kMagic = 0x4C425454;
uint32_t constexpr kFormatVersion = 1;
uint32_t constexpr kEndMark = 0x444E4554;
double constexpr kCoordFactor = 1e6;
int64_t constexpr kMaxLat = 90 * 1000000;
int64_t constexpr kMaxLon = 180 * 1000000;
int64_t constexpr kMaxFirstDay = 1000000;

template <typename Source>
uint64_t ReadUint(Source & src)
{
  uint64_t value = 0;
  for (unsigned shift = 0; shift < 64; shift += 7)
  {
    auto const byte = ReadPrimitiveFromSource<uint8_t>(src);
    uint64_t const bits = byte & 127;
    if (shift == 63 && bits > 1)
      break;
    value |= bits << shift;
    if (!(byte & 128))
      return value;
  }
  MYTHROW(CorruptTimetableException, ("Bad varint"));
}

template <typename Source>
uint32_t ReadUint32(Source & src)
{
  auto const value = ReadUint(src);
  if (value > std::numeric_limits<uint32_t>::max())
    MYTHROW(CorruptTimetableException, ("Value", value, "is too large"));
  return static_cast<uint32_t>(value);
}

template <typename Source>
int64_t ReadInt(Source & src, int64_t limit)
{
  auto const value = bits::ZigZagDecode(ReadUint(src));
  if (value < -limit || value > limit)
    MYTHROW(CorruptTimetableException, ("Value", value, "is out of range", limit));
  return value;
}

template <typename Source>
void ReadString(Source & src, std::string & text)
{
  auto const size = ReadUint32(src);
  if (size > src.Size())
    MYTHROW(CorruptTimetableException, ("String of", size, "bytes exceeds the file"));
  text.resize(size);
  if (size > 0)
    src.Read(text.data(), size);
}

template <typename Sink>
void WriteIndex(Sink & sink, Index index)
{
  WriteVarUint(sink, index == kNoIndex ? 0 : index + 1);
}

template <typename Source>
Index ReadIndex(Source & src, size_t size)
{
  auto const value = ReadUint32(src);
  if (value == 0)
    return kNoIndex;
  if (value > size)
    MYTHROW(CorruptTimetableException, ("Index", value, "out of", size));
  return value - 1;
}

template <typename Source>
Index ReadRequiredIndex(Source & src, size_t size)
{
  auto const index = ReadIndex(src, size);
  if (index == kNoIndex)
    MYTHROW(CorruptTimetableException, ("Missing index"));
  return index;
}

template <typename Sink>
void WriteColor(Sink & sink, std::optional<uint32_t> const & color)
{
  WriteVarUint(sink, color ? *color + 1 : 0);
}

template <typename Source>
std::optional<uint32_t> ReadColor(Source & src)
{
  auto const value = ReadUint32(src);
  if (value == 0)
    return {};
  return value - 1;
}

template <typename Source>
uint32_t ReadCount(Source & src, uint64_t minBytesPerItem)
{
  auto const count = ReadUint32(src);
  if (count * minBytesPerItem > src.Size())
    MYTHROW(CorruptTimetableException, ("Count", count, "exceeds the file"));
  return count;
}

int32_t ToFixed(double value)
{
  return static_cast<int32_t>(std::lround(value * kCoordFactor));
}

template <typename Sink>
void WriteZones(Sink & sink, Data const & data)
{
  WriteVarUint(sink, static_cast<uint32_t>(data.m_zones.size()));
  for (auto const & zone : data.m_zones)
  {
    rw::Write(sink, zone.m_name);
    int64_t expected = data.m_firstDay * kSecondsInDay;
    for (int64_t const start : zone.m_dayStarts)
    {
      WriteVarInt(sink, start - expected);
      expected = start + kSecondsInDay;
    }
  }
}

template <typename Source>
void ReadZones(Source & src, Data & data)
{
  data.m_zones.resize(ReadCount(src, 1 + data.m_dayCount));
  for (auto & zone : data.m_zones)
  {
    ReadString(src, zone.m_name);
    zone.m_dayStarts.resize(data.m_dayCount);
    int64_t expected = data.m_firstDay * kSecondsInDay;
    for (auto & start : zone.m_dayStarts)
    {
      start = expected + ReadInt(src, kSecondsInDay);
      expected = start + kSecondsInDay;
    }
  }
}

template <typename Sink>
void WriteAgencies(Sink & sink, std::vector<Agency> const & agencies)
{
  WriteVarUint(sink, static_cast<uint32_t>(agencies.size()));
  for (auto const & agency : agencies)
  {
    rw::Write(sink, agency.m_id);
    rw::Write(sink, agency.m_name);
    rw::Write(sink, agency.m_url);
    rw::Write(sink, agency.m_fareUrl);
    WriteIndex(sink, agency.m_zone);
  }
}

template <typename Source>
void ReadAgencies(Source & src, Data & data)
{
  data.m_agencies.resize(ReadCount(src, 5));
  for (auto & agency : data.m_agencies)
  {
    ReadString(src, agency.m_id);
    ReadString(src, agency.m_name);
    ReadString(src, agency.m_url);
    ReadString(src, agency.m_fareUrl);
    agency.m_zone = ReadRequiredIndex(src, data.m_zones.size());
  }
}

template <typename Sink>
void WriteStops(Sink & sink, std::vector<Stop> const & stops)
{
  WriteVarUint(sink, static_cast<uint32_t>(stops.size()));
  int32_t lat = 0;
  int32_t lon = 0;
  for (auto const & stop : stops)
  {
    rw::Write(sink, stop.m_id);
    rw::Write(sink, stop.m_name);
    rw::Write(sink, stop.m_platform);
    int32_t const nextLat = ToFixed(stop.m_point.m_lat);
    int32_t const nextLon = ToFixed(stop.m_point.m_lon);
    WriteVarInt(sink, static_cast<int64_t>(nextLat) - lat);
    WriteVarInt(sink, static_cast<int64_t>(nextLon) - lon);
    lat = nextLat;
    lon = nextLon;
    WriteIndex(sink, stop.m_parent);
  }
}

template <typename Source>
void ReadStops(Source & src, Data & data)
{
  data.m_stops.resize(ReadCount(src, 6));
  int64_t lat = 0;
  int64_t lon = 0;
  for (auto & stop : data.m_stops)
  {
    ReadString(src, stop.m_id);
    ReadString(src, stop.m_name);
    ReadString(src, stop.m_platform);
    lat += ReadInt(src, 2 * kMaxLat);
    lon += ReadInt(src, 2 * kMaxLon);
    if (lat < -kMaxLat || lat > kMaxLat || lon < -kMaxLon || lon > kMaxLon)
      MYTHROW(CorruptTimetableException, ("Stop point", lat, lon));
    stop.m_point = ms::LatLon(lat / kCoordFactor, lon / kCoordFactor);
    stop.m_parent = ReadIndex(src, data.m_stops.size());
  }
}

template <typename Sink>
void WriteRoutes(Sink & sink, std::vector<Route> const & routes)
{
  WriteVarUint(sink, static_cast<uint32_t>(routes.size()));
  for (auto const & route : routes)
  {
    rw::Write(sink, route.m_id);
    rw::Write(sink, route.m_shortName);
    rw::Write(sink, route.m_longName);
    WriteIndex(sink, route.m_agency);
    WriteToSink(sink, static_cast<uint8_t>(route.m_mode));
    WriteColor(sink, route.m_color);
    WriteColor(sink, route.m_textColor);
  }
}

template <typename Source>
void ReadRoutes(Source & src, Data & data)
{
  data.m_routes.resize(ReadCount(src, 7));
  for (auto & route : data.m_routes)
  {
    ReadString(src, route.m_id);
    ReadString(src, route.m_shortName);
    ReadString(src, route.m_longName);
    route.m_agency = ReadRequiredIndex(src, data.m_agencies.size());
    auto const mode = ReadPrimitiveFromSource<uint8_t>(src);
    if (mode > static_cast<uint8_t>(Mode::Other))
      MYTHROW(CorruptTimetableException, ("Mode", mode));
    route.m_mode = static_cast<Mode>(mode);
    route.m_color = ReadColor(src);
    route.m_textColor = ReadColor(src);
  }
}

template <typename Sink>
void WritePatterns(Sink & sink, std::vector<Pattern> const & patterns)
{
  WriteVarUint(sink, static_cast<uint32_t>(patterns.size()));
  for (auto const & pattern : patterns)
  {
    WriteIndex(sink, pattern.m_route);
    WriteVarUint(sink, static_cast<uint32_t>(pattern.m_stops.size()));
    for (size_t i = 0; i < pattern.m_stops.size(); ++i)
    {
      WriteIndex(sink, pattern.m_stops[i]);
      WriteToSink(sink, pattern.m_rules[i]);
    }
  }
}

template <typename Source>
void ReadPatterns(Source & src, Data & data)
{
  data.m_patterns.resize(ReadCount(src, 6));
  for (auto & pattern : data.m_patterns)
  {
    pattern.m_route = ReadRequiredIndex(src, data.m_routes.size());
    auto const count = ReadCount(src, 2);
    if (count < 2)
      MYTHROW(CorruptTimetableException, ("Pattern with", count, "stops"));
    pattern.m_stops.resize(count);
    pattern.m_rules.resize(count);
    for (size_t i = 0; i < count; ++i)
    {
      pattern.m_stops[i] = ReadRequiredIndex(src, data.m_stops.size());
      pattern.m_rules[i] = ReadPrimitiveFromSource<uint8_t>(src);
    }
  }
}

template <typename Sink>
void WriteServices(Sink & sink, Data const & data)
{
  WriteVarUint(sink, static_cast<uint32_t>(data.m_services.size()));
  for (auto const & days : data.m_services)
  {
    for (size_t day = 0; day < data.m_dayCount; day += 8)
    {
      uint8_t bits = 0;
      for (size_t bit = 0; bit < 8 && day + bit < data.m_dayCount; ++bit)
        if (days[day + bit])
          bits |= 1 << bit;
      WriteToSink(sink, bits);
    }
  }
}

template <typename Source>
void ReadServices(Source & src, Data & data)
{
  data.m_services.resize(ReadCount(src, (data.m_dayCount + 7) / 8));
  for (auto & days : data.m_services)
  {
    days.resize(data.m_dayCount);
    for (size_t day = 0; day < data.m_dayCount; day += 8)
    {
      auto const bits = ReadPrimitiveFromSource<uint8_t>(src);
      for (size_t bit = 0; bit < 8 && day + bit < data.m_dayCount; ++bit)
        days[day + bit] = (bits >> bit) & 1;
    }
  }
}

template <typename Sink>
void WriteTrips(Sink & sink, Data const & data)
{
  WriteVarUint(sink, static_cast<uint32_t>(data.m_trips.size()));
  for (auto const & trip : data.m_trips)
  {
    rw::Write(sink, trip.m_id);
    rw::Write(sink, trip.m_headsign);
    WriteIndex(sink, trip.m_pattern);
    WriteIndex(sink, trip.m_service);
    size_t const count = data.m_patterns[trip.m_pattern].m_stops.size();
    int32_t previous = 0;
    for (size_t i = 0; i < count; ++i)
    {
      auto const & time = data.m_stopTimes[trip.m_firstStopTime + i];
      WriteVarUint(sink, static_cast<uint32_t>(time.m_arrival - previous));
      WriteVarUint(sink, static_cast<uint32_t>(time.m_departure - time.m_arrival));
      previous = time.m_departure;
    }
  }
}

template <typename Source>
void ReadTrips(Source & src, Data & data)
{
  data.m_trips.resize(ReadCount(src, 8));
  for (auto & trip : data.m_trips)
  {
    ReadString(src, trip.m_id);
    ReadString(src, trip.m_headsign);
    trip.m_pattern = ReadRequiredIndex(src, data.m_patterns.size());
    trip.m_service = ReadRequiredIndex(src, data.m_services.size());
    trip.m_firstStopTime = static_cast<Index>(data.m_stopTimes.size());
    size_t const count = data.m_patterns[trip.m_pattern].m_stops.size();
    int64_t previous = 0;
    for (size_t i = 0; i < count; ++i)
    {
      int64_t const arrival = previous + ReadUint32(src);
      int64_t const departure = arrival + ReadUint32(src);
      if (departure > kMaxTripTime)
        MYTHROW(CorruptTimetableException, ("Trip time", departure));
      data.m_stopTimes.push_back({static_cast<int32_t>(arrival), static_cast<int32_t>(departure)});
      previous = departure;
    }
  }
}
}  // namespace

void Serialize(Data const & data, Writer & writer)
{
  WriteToSink(writer, kMagic);
  WriteVarUint(writer, kFormatVersion);
  WriteVarInt(writer, data.m_firstDay);
  WriteVarUint(writer, data.m_dayCount);
  WriteZones(writer, data);
  WriteAgencies(writer, data.m_agencies);
  WriteStops(writer, data.m_stops);
  WriteRoutes(writer, data.m_routes);
  WritePatterns(writer, data.m_patterns);
  WriteServices(writer, data);
  WriteTrips(writer, data);
  WriteToSink(writer, kEndMark);
}

Data Deserialize(Reader const & reader)
{
  ReaderSource<Reader const &> src(reader);
  if (ReadPrimitiveFromSource<uint32_t>(src) != kMagic)
    MYTHROW(CorruptTimetableException, ("Unknown format"));
  if (auto const version = ReadUint32(src); version != kFormatVersion)
    MYTHROW(CorruptTimetableException, ("Unsupported version", version));

  Data data;
  data.m_firstDay = static_cast<Day>(ReadInt(src, kMaxFirstDay));
  data.m_dayCount = ReadUint32(src);
  if (data.m_dayCount == 0 || data.m_dayCount > kMaxDayCount)
    MYTHROW(CorruptTimetableException, ("Day count", data.m_dayCount));

  ReadZones(src, data);
  ReadAgencies(src, data);
  ReadStops(src, data);
  ReadRoutes(src, data);
  ReadPatterns(src, data);
  ReadServices(src, data);
  ReadTrips(src, data);
  if (ReadPrimitiveFromSource<uint32_t>(src) != kEndMark || src.Size() != 0)
    MYTHROW(CorruptTimetableException, ("Missing end mark"));
  return data;
}
}  // namespace transit::timetable::detail
