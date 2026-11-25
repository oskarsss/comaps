project(transit_timetable)

set(SRC
  calendar.cpp
  calendar.hpp
  timetable.cpp
  timetable.hpp
  timetable_data.hpp
  timetable_serdes.cpp
)

omim_add_library(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PUBLIC
    geometry
  PRIVATE
    base
    coding
)

if (PLATFORM_DESKTOP AND NOT WIN32)
  add_subdirectory(builder)
  omim_add_tool_subdirectory(timetable_tool)
endif()
