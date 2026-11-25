#include "testing/testing.hpp"

#include "drape_frontend/line_shape_helper.hpp"

#include "base/math.hpp"

#include <cmath>
#include <vector>

namespace line_shape_helper_tests
{
UNIT_TEST(GenerateJoinNormals_RoundJoinUTurn)
{
  size_t constexpr kMaxNormalsCount = 3 * 8;
  for (int degrees = 0; degrees < 360; ++degrees)
  {
    double const angle = math::DegToRad(static_cast<double>(degrees));
    glsl::vec2 const normal1(static_cast<float>(std::cos(angle)), static_cast<float>(std::sin(angle)));
    glsl::vec2 const normal2 = -normal1;

    for (bool const isLeft : {true, false})
    {
      std::vector<glsl::vec2> uv;
      auto const normals = df::GenerateJoinNormals(dp::RoundJoin, normal1, normal2, 1.0f, isLeft, 1.0f, &uv);
      TEST(!normals.empty(), (degrees, isLeft));
      TEST_LESS_OR_EQUAL(normals.size(), kMaxNormalsCount, (degrees, isLeft));
      TEST_EQUAL(normals.size(), uv.size(), (degrees, isLeft));
    }
  }
}
}  // namespace line_shape_helper_tests
