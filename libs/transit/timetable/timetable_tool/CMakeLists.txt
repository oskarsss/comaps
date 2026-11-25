project(transit_timetable_tool)

omim_add_executable(${PROJECT_NAME} timetable_tool.cpp)

target_link_libraries(${PROJECT_NAME}
  PRIVATE
    base
    gflags::gflags
    transit_timetable
    transit_timetable_builder
)
