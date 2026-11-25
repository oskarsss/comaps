project(transit_timetable_tests)

set(SRC
  calendar_tests.cpp
  timetable_tests.cpp
)

omim_add_test(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    base
    coding
    platform_tests_support
    transit_timetable
    transit_timetable_builder
)
