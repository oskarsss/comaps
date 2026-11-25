#pragma once

#include "map/user_mark.hpp"
#include <algorithm>

// Transient vehicles have their own group; refreshing them must not clear routing/API/search marks.
class LiveVehicleMark : public UserMark
{
public:
  explicit LiveVehicleMark(m2::PointD const & point) : UserMark(point, UserMark::LIVE_VEHICLE) {}

  void SetTitle(std::string const & title)
  {
    m_title = title;
    SetDirty();
  }

  std::string const & GetTitle() const { return m_title; }
  void SetBadge(std::string number, dp::Color fill)
  {
    m_number = number.substr(0,8);
    m_fill = fill;
    SetDirty();
  }
  drape_ptr<ColoredSymbolZoomInfo> GetColoredSymbols() const override
  {
    if (m_number.empty()) return nullptr;
    auto symbols = make_unique_dp<ColoredSymbolZoomInfo>();
    df::ColoredSymbolViewParams params;
    params.m_shape = df::ColoredSymbolViewParams::Shape::RoundedRectangle;
    params.m_color = m_fill;
    params.m_outlineColor = dp::Color(255,255,255,255);
    params.m_outlineWidth = 2;
    params.m_radiusInPixels = 7;
    params.m_sizeInPixels = m2::PointF(std::max(30.0f, 8.0f * m_number.size() + 12.0f),26.0f);
    symbols->m_zoomInfo.emplace(10,params);
    return symbols;
  }
  drape_ptr<TitlesInfo> GetTitleDecl() const override
  {
    if (m_number.empty()) return nullptr;
    auto titles = make_unique_dp<TitlesInfo>();
    dp::TitleDecl title;
    title.m_primaryText = m_number;
    title.m_primaryTextFont = dp::FontDecl(m_fill.GetGreen() > 120
        ? dp::Color(26,26,26,255) : dp::Color(255,255,255,255),14);
    titles->push_back(title);
    return titles;
  }
  bool IsNonDisplaceable() const override { return !m_number.empty(); }

  drape_ptr<SymbolNameZoomInfo> GetSymbolNames() const override
  {
    if (!m_number.empty()) return nullptr;
    auto symbols = make_unique_dp<SymbolNameZoomInfo>();
    symbols->emplace(1, "coloredmark-default-s");
    return symbols;
  }

  df::ColorConstant GetColorConstant() const override { return "BookmarkBlue"; }
  bool IsSymbolSelectable() const override { return true; }
  int GetMinZoom() const override { return 10; }

private:
  std::string m_title;
  std::string m_number;
  dp::Color m_fill;
};
