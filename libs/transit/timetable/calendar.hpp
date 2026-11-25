#pragma once

#include "transit/timetable/timetable.hpp"

namespace transit::timetable
{
Day DayFromCivil(int year, unsigned month, unsigned day);

unsigned Weekday(Day day);
}  // namespace transit::timetable
