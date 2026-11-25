#include "transit/timetable/calendar.hpp"

namespace transit::timetable
{
Day DayFromCivil(int year, unsigned month, unsigned day)
{
  year -= month <= 2;
  int const era = (year >= 0 ? year : year - 399) / 400;
  auto const yearOfEra = static_cast<unsigned>(year - era * 400);
  unsigned const dayOfYear = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
  unsigned const dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
  return era * 146097 + static_cast<Day>(dayOfEra) - 719468;
}

unsigned Weekday(Day day)
{
  return static_cast<unsigned>(((day % 7) + 7 + 3) % 7);
}
}  // namespace transit::timetable
