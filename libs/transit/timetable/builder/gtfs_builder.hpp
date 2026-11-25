#pragma once

#include "transit/timetable/timetable.hpp"

#include <memory>
#include <string>

class Writer;

namespace transit::timetable
{
class GtfsBuilder
{
public:
  GtfsBuilder();
  ~GtfsBuilder();

  bool AddFeed(std::string const & directory, std::string const & idPrefix);

  bool Write(Day firstDay, uint32_t dayCount, Writer & writer) const;
  bool Write(Day firstDay, uint32_t dayCount, std::string const & path) const;

private:
  class Impl;
  std::unique_ptr<Impl> m_impl;
};
}  // namespace transit::timetable
