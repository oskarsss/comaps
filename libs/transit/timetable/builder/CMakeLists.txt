project(transit_timetable_builder)

set(SRC
  gtfs_builder.cpp
  gtfs_builder.hpp
)

omim_add_library(${PROJECT_NAME} ${SRC})

target_link_libraries(${PROJECT_NAME}
  PUBLIC
    transit_timetable
  PRIVATE
    base
    coding
)
