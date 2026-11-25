#include "testing/testing.hpp"

#include "transit/timetable/calendar.hpp"

namespace calendar_tests
{
using namespace transit::timetable;

UNIT_TEST(Timetable_DayFromCivil)
{
  TEST_EQUAL(DayFromCivil(1970, 1, 1), 0, ());
  TEST_EQUAL(DayFromCivil(1969, 12, 31), -1, ());
  TEST_EQUAL(DayFromCivil(2000, 3, 1) - DayFromCivil(2000, 2, 28), 2, ());
  TEST_EQUAL(DayFromCivil(2026, 3, 2), 20514, ());
}

UNIT_TEST(Timetable_Weekday)
{
  TEST_EQUAL(Weekday(DayFromCivil(1970, 1, 1)), 3, ());
  TEST_EQUAL(Weekday(DayFromCivil(2026, 3, 2)), 0, ());
  TEST_EQUAL(Weekday(DayFromCivil(2026, 3, 8)), 6, ());
  TEST_EQUAL(Weekday(DayFromCivil(1969, 12, 29)), 0, ());
}
}  // namespace calendar_tests
