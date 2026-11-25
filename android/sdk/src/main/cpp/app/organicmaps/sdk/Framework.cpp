#include "indexer/map_style.hpp"
#include "indexer/map_style_reader.hpp"
#include "app/organicmaps/sdk/Framework.hpp"

#include "app/organicmaps/sdk/UserMarkHelper.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "app/organicmaps/sdk/opengl/androidoglcontextfactory.hpp"
#include "app/organicmaps/sdk/platform/AndroidPlatform.hpp"
#include "app/organicmaps/sdk/routing/JunctionInfo.hpp"
#include "app/organicmaps/sdk/routing/RouteMarkData.hpp"
#include "app/organicmaps/sdk/routing/RouteMarkType.hpp"
#include "app/organicmaps/sdk/routing/RouteRecommendationType.hpp"
#include "app/organicmaps/sdk/routing/RouteStepInfo.hpp"
#include "app/organicmaps/sdk/routing/RoutingInfo.hpp"
#include "app/organicmaps/sdk/routing/TransitRouteInfo.hpp"
#include "app/organicmaps/sdk/util/Distance.hpp"
#include "app/organicmaps/sdk/util/FeatureIdBuilder.hpp"
#include "app/organicmaps/sdk/util/NetworkPolicy.hpp"
#include "app/organicmaps/sdk/vulkan/android_vulkan_context_factory.hpp"

#include "map/bookmark_helpers.hpp"
#include "map/chart_generator.hpp"
#include "map/everywhere_search_params.hpp"
#include "map/user_mark.hpp"
#include "map/live_vehicle_mark.hpp"

#include <algorithm>
#include <cmath>

#include "storage/country_info_getter.hpp"
#include "storage/storage_defines.hpp"
#include "storage/storage_helpers.hpp"

#include "drape_frontend/user_event_stream.hpp"
#include "drape_frontend/visual_params.hpp"

#include "drape/pointers.hpp"
#include "drape/support_manager.hpp"
#include "drape/visual_scale.hpp"

#include "coding/files_container.hpp"

#include "geometry/angles.hpp"
#include "geometry/distance_on_sphere.hpp"
#include "geometry/mercator.hpp"
#include "geometry/point_with_altitude.hpp"

#include "indexer/feature_decl.hpp"
#include "indexer/feature_altitude.hpp"
#include "indexer/map_object.hpp"
#include "indexer/validate_and_format_contacts.hpp"

#include "routing/following_info.hpp"
#include "routing/speed_camera_manager.hpp"

#include "platform/country_file.hpp"
#include "platform/local_country_file.hpp"
#include "platform/local_country_file_utils.hpp"
#include "platform/locale.hpp"
#include "platform/localization.hpp"
#include "platform/location.hpp"
#include "platform/measurement_utils.hpp"
#include "platform/network_policy.hpp"
#include "platform/platform.hpp"
#include "platform/preferred_languages.hpp"
#include "platform/settings.hpp"
#include "platform/utm_mgrs_utils.hpp"

#include "base/assert.hpp"
#include "base/file_name_utils.hpp"
#include "base/logging.hpp"
#include "base/math.hpp"
#include "base/sunrise_sunset.hpp"

#include "ge0/url_generator.hpp"

#include "3party/open-location-code/openlocationcode.h"

#include <memory>
#include <string>
#include <vector>

#include <android/api-level.h>

#include "drape/accessibility_data.hpp"

using namespace std;
using namespace std::placeholders;

unique_ptr<android::Framework> g_framework;

namespace platform
{
NetworkPolicy ToNativeNetworkPolicy(JNIEnv * env, jobject obj)
{
  return NetworkPolicy(network_policy::GetNetworkPolicyStatus(env, obj));
}
}  // namespace platform

using namespace storage;
using platform::CountryFile;
using platform::LocalCountryFile;
using platform::ToNativeNetworkPolicy;

static_assert(sizeof(int) >= 4, "Size of jint in less than 4 bytes.");

::Framework * frm()
{
  return g_framework->NativeFramework();
}

namespace
{
jobject g_placePageActivationListener = nullptr;

android::AndroidVulkanContextFactory * CastFactory(drape_ptr<dp::GraphicsContextFactory> const & f)
{
  return dynamic_cast<android::AndroidVulkanContextFactory *>(f.get());
}
}  // namespace

namespace android
{

enum MultiTouchAction
{
  MULTITOUCH_UP = 0x00000001,
  MULTITOUCH_DOWN = 0x00000002,
  MULTITOUCH_MOVE = 0x00000003,
  MULTITOUCH_CANCEL = 0x00000004
};

Framework::Framework(std::function<void()> && afterMapsLoaded) : m_work({} /* params */, false /* loadMaps */)
{
  m_work.LoadMapsAsync(std::move(afterMapsLoaded));

  m_work.GetTrafficManager().SetStateListener(bind(&Framework::TrafficStateChanged, this, _1));
  m_work.GetTransitManager().SetStateListener(bind(&Framework::TransitSchemeStateChanged, this, _1));
  m_work.GetIsolinesManager().SetStateListener(bind(&Framework::IsolinesSchemeStateChanged, this, _1));
  m_work.GetPowerManager().Subscribe(this);
}

void Framework::OnLocationError(int errorCode)
{
  m_work.OnLocationError(static_cast<location::TLocationError>(errorCode));
}

void Framework::OnLocationUpdated(location::GpsInfo const & info)
{
  m_work.OnLocationUpdate(info);
}

void Framework::OnCompassUpdated(location::CompassInfo const & info, bool forceRedraw)
{
  static double const COMPASS_THRESHOLD = math::DegToRad(1.0);

  /// @todo Do not emit compass bearing too often.
  /// Need to make more experiments in future.
  if (forceRedraw || fabs(ang::GetShortestDistance(m_lastCompass, info.m_bearing)) >= COMPASS_THRESHOLD)
  {
    m_lastCompass = info.m_bearing;
    m_work.OnCompassUpdate(info);
  }
}

void Framework::MyPositionModeChanged(location::EMyPositionMode mode, bool routingActive)
{
  if (m_myPositionModeSignal)
    m_myPositionModeSignal(mode, routingActive);
}

void Framework::TrafficStateChanged(TrafficManager::TrafficState state)
{
  if (m_onTrafficStateChangedFn)
    m_onTrafficStateChangedFn(state);
}

void Framework::TransitSchemeStateChanged(TransitReadManager::TransitSchemeState state)
{
  if (m_onTransitStateChangedFn)
    m_onTransitStateChangedFn(state);
}

void Framework::IsolinesSchemeStateChanged(IsolinesManager::IsolinesState state)
{
  if (m_onIsolinesStateChangedFn)
    m_onIsolinesStateChangedFn(state);
}

bool Framework::DestroySurfaceOnDetach()
{
  if (m_vulkanContextFactory)
    return false;
  return true;
}

bool Framework::CreateDrapeEngine(JNIEnv * env, jobject jSurface, int densityDpi, bool firstLaunch,
                                  bool launchByDeepLink, uint32_t appVersionCode, bool isCustomROM)
{
  // Vulkan is supported only since Android 8.0, because some Android devices with Android 7.x
  // have fatal driver issue, which can lead to process termination and whole OS destabilization.
  int constexpr kMinSdkVersionForVulkan = 26;
  int const sdkVersion = android_get_device_api_level();
  LOG(LINFO, ("Android SDK version in the Drape Engine:", sdkVersion));
  auto const vulkanForbidden =
      sdkVersion < kMinSdkVersionForVulkan || dp::SupportManager::Instance().IsVulkanForbidden();
  if (vulkanForbidden)
    LOG(LWARNING, ("Vulkan API is forbidden on this device."));

  m_vulkanContextFactory.reset();
  m_oglContextFactory.reset();
  ::Framework::DrapeCreationParams p;

  if (m_work.LoadPreferredGraphicsAPI() == dp::ApiVersion::Vulkan && !vulkanForbidden)
  {
    auto vkFactory = make_unique_dp<AndroidVulkanContextFactory>(appVersionCode, sdkVersion, isCustomROM);
    if (!vkFactory->IsVulkanSupported())
    {
      LOG(LWARNING, ("Vulkan API is not supported."));
    }
    else
    {
      vkFactory->SetSurface(env, jSurface);
      if (!vkFactory->IsValid())
      {
        LOG(LWARNING, ("Invalid Vulkan API context."));
      }
      else
      {
        p.m_apiVersion = dp::ApiVersion::Vulkan;
        p.m_surfaceWidth = vkFactory->GetWidth();
        p.m_surfaceHeight = vkFactory->GetHeight();

        m_vulkanContextFactory = std::move(vkFactory);
      }
    }
  }

  if (!m_vulkanContextFactory)
  {
    auto oglFactory = make_unique_dp<AndroidOGLContextFactory>(env, jSurface);
    if (!oglFactory->IsValid())
    {
      LOG(LWARNING, ("Invalid GL context."));
      return false;
    }
    p.m_apiVersion = dp::ApiVersion::OpenGLES3;
    p.m_surfaceWidth = oglFactory->GetWidth();
    p.m_surfaceHeight = oglFactory->GetHeight();

    m_oglContextFactory = make_unique_dp<dp::ThreadSafeFactory>(oglFactory.release());
  }

  p.m_visualScale = static_cast<float>(dp::VisualScale(densityDpi));
  // Drape doesn't care about Editor vs Api mode differences.
  p.m_isChoosePositionMode = m_isChoosePositionMode != ChoosePositionMode::None;
  p.m_hints.m_isFirstLaunch = firstLaunch;
  p.m_hints.m_isLaunchByDeepLink = launchByDeepLink;
  ASSERT(!m_guiPositions.empty(), ("GUI elements must be set-up before engine is created"));
  p.m_widgetsInitInfo = m_guiPositions;

  m_work.SetMyPositionModeListener(bind(&Framework::MyPositionModeChanged, this, _1, _2));

  if (m_vulkanContextFactory)
    m_work.CreateDrapeEngine(make_ref(m_vulkanContextFactory), std::move(p));
  else
    m_work.CreateDrapeEngine(make_ref(m_oglContextFactory), std::move(p));
  m_work.EnterForeground();

  return true;
}

bool Framework::IsDrapeEngineCreated() const
{
  return m_work.IsDrapeEngineCreated();
}

void Framework::UpdateDpi(int dpi)
{
  ASSERT_GREATER(dpi, 0, ());
  m_work.UpdateVisualScale(dp::VisualScale(dpi));
}

void Framework::Resize(JNIEnv * env, jobject jSurface, int w, int h)
{
  if (m_vulkanContextFactory)
  {
    auto vulkanContextFactory = CastFactory(m_vulkanContextFactory);
    if (vulkanContextFactory->GetWidth() != w || vulkanContextFactory->GetHeight() != h)
    {
      m_vulkanContextFactory->SetPresentAvailable(false);
      m_work.SetRenderingDisabled(false /* destroySurface */);

      vulkanContextFactory->ChangeSurface(env, jSurface, w, h);

      vulkanContextFactory->SetPresentAvailable(true);
      m_work.SetRenderingEnabled();
    }
  }
  else
  {
    m_oglContextFactory->CastFactory<AndroidOGLContextFactory>()->UpdateSurfaceSize(w, h);
  }
  m_work.OnSize(w, h);
}

void Framework::DetachSurface(bool destroySurface)
{
  LOG(LINFO, ("Detach surface started. destroySurface =", destroySurface));
  if (m_vulkanContextFactory)
  {
    m_vulkanContextFactory->SetPresentAvailable(false);
  }
  else
  {
    ASSERT(m_oglContextFactory != nullptr, ());
    m_oglContextFactory->SetPresentAvailable(false);
  }

  if (destroySurface)
  {
    LOG(LINFO, ("Destroy surface."));
    m_isSurfaceDestroyed = true;
    m_work.OnDestroySurface();
  }

  if (m_vulkanContextFactory)
  {
    // With Vulkan we don't need to recreate all graphics resources,
    // we have to destroy only resources bound with surface (swapchains,
    // image views, framebuffers and command buffers). All these resources will be
    // destroyed in ResetSurface().
    m_work.SetRenderingDisabled(false /* destroySurface */);

    // Allow pipeline dump only on enter background.
    CastFactory(m_vulkanContextFactory)->ResetSurface(destroySurface /* allowPipelineDump */);
  }
  else
  {
    m_work.SetRenderingDisabled(destroySurface);
    auto factory = m_oglContextFactory->CastFactory<AndroidOGLContextFactory>();
    factory->ResetSurface();
  }
  LOG(LINFO, ("Detach surface finished."));
}

bool Framework::AttachSurface(JNIEnv * env, jobject jSurface)
{
  LOG(LINFO, ("Attach surface started."));

  int w = 0, h = 0;
  if (m_vulkanContextFactory)
  {
    auto factory = CastFactory(m_vulkanContextFactory);
    factory->SetSurface(env, jSurface);
    if (!factory->IsValid())
    {
      LOG(LWARNING, ("Invalid Vulkan API context."));
      return false;
    }
    w = factory->GetWidth();
    h = factory->GetHeight();
  }
  else
  {
    ASSERT(m_oglContextFactory != nullptr, ());
    auto factory = m_oglContextFactory->CastFactory<AndroidOGLContextFactory>();
    factory->SetSurface(env, jSurface);
    if (!factory->IsValid())
    {
      LOG(LWARNING, ("Invalid GL context."));
      return false;
    }
    w = factory->GetWidth();
    h = factory->GetHeight();
  }

  ASSERT(!m_guiPositions.empty(), ("GUI elements must be set-up before engine is created"));

  if (m_vulkanContextFactory)
  {
    m_vulkanContextFactory->SetPresentAvailable(true);
    m_work.SetRenderingEnabled();
  }
  else
  {
    m_oglContextFactory->SetPresentAvailable(true);
    m_work.SetRenderingEnabled(make_ref(m_oglContextFactory));
  }

  if (m_isSurfaceDestroyed)
  {
    LOG(LINFO, ("Recover surface, viewport size:", w, h));
    bool const recreateContextDependentResources = (m_vulkanContextFactory == nullptr);
    m_work.OnRecoverSurface(w, h, recreateContextDependentResources);
    m_isSurfaceDestroyed = false;
  }

  LOG(LINFO, ("Attach surface finished."));

  return true;
}

void Framework::PauseSurfaceRendering()
{
  if (m_vulkanContextFactory)
    m_vulkanContextFactory->SetPresentAvailable(false);
  if (m_oglContextFactory)
    m_oglContextFactory->SetPresentAvailable(false);

  LOG(LINFO, ("Pause surface rendering."));
}

void Framework::ResumeSurfaceRendering()
{
  if (m_vulkanContextFactory)
  {
    if (CastFactory(m_vulkanContextFactory)->IsValid())
      m_vulkanContextFactory->SetPresentAvailable(true);
  }
  if (m_oglContextFactory)
  {
    AndroidOGLContextFactory * factory = m_oglContextFactory->CastFactory<AndroidOGLContextFactory>();
    if (factory->IsValid())
      factory->SetPresentAvailable(true);
  }
  LOG(LINFO, ("Resume surface rendering."));
}

void Framework::SwitchToMapAppearance(MapAppearance mapAppearance)
{
  m_work.SwitchToMapAppearance(mapAppearance);
}

MapAppearance Framework::CurrentMapAppearance()
{
  return m_work.CurrentMapAppearance();
}

void Framework::SwitchToMapMode(MapMode mapMode)
{
  m_work.SwitchToMapMode(mapMode);
}

MapMode Framework::CurrentMapMode()
{
  return m_work.CurrentMapMode();
}

void Framework::SwitchToUsingVehicleStyle(bool enabled)
{
  m_work.SwitchToUsingVehicleStyle(enabled);
}

bool Framework::IsUsingVehicleStyle()
{
  return m_work.IsUsingVehicleStyle();
}

void Framework::Save3dMode(bool allow3d, bool allow3dBuildings)
{
  m_work.Save3dMode(allow3d, allow3dBuildings);
}

void Framework::Set3dMode(bool allow3d, bool allow3dBuildings)
{
  m_work.Allow3dMode(allow3d, allow3dBuildings);
}

void Framework::Get3dMode(bool & allow3d, bool & allow3dBuildings)
{
  m_work.Load3dMode(allow3d, allow3dBuildings);
}

void Framework::SetMapLanguageCode(std::string const & languageCode)
{
  m_work.SetCustomMapLanguageCode(languageCode);
}

std::string Framework::GetMapLanguageCode()
{
  return m_work.GetCustomMapLanguageCode().value_or("auto");
}

void Framework::SetChoosePositionMode(ChoosePositionMode mode, bool isBusiness, m2::PointD const * optionalPosition)
{
  m_isChoosePositionMode = mode;
  m_work.BlockTapEvents(mode != ChoosePositionMode::None);
  m_work.EnableChoosePositionMode(mode != ChoosePositionMode::None, isBusiness, optionalPosition);
}

ChoosePositionMode Framework::GetChoosePositionMode()
{
  return m_isChoosePositionMode;
}

Storage & Framework::GetStorage()
{
  return m_work.GetStorage();
}

DataSource const & Framework::GetDataSource()
{
  return m_work.GetDataSource();
}

void Framework::ShowNode(CountryId const & idx, bool zoomToDownloadButton)
{
  if (zoomToDownloadButton)
  {
    m2::RectD const rect = CalcLimitRect(idx, m_work.GetStorage(), m_work.GetCountryInfoGetter());
    m_work.SetViewportCenter(rect.Center(), 10);
  }
  else
  {
    m_work.ShowNode(idx);
  }
}

void Framework::Scale(double factor, m2::PointD const & pxPoint, bool isAnim)
{
  m_work.Scale(factor, pxPoint, isAnim);
}

void Framework::Scroll(double distanceX, double distanceY)
{
  m_work.Scroll(distanceX, distanceY);
}

void Framework::Touch(int action, Finger const & f1, Finger const & f2, uint8_t maskedPointer)
{
  MultiTouchAction eventType = static_cast<MultiTouchAction>(action);
  df::TouchEvent event;

  switch (eventType)
  {
  case MULTITOUCH_DOWN: event.SetTouchType(df::TouchEvent::TOUCH_DOWN); break;
  case MULTITOUCH_MOVE: event.SetTouchType(df::TouchEvent::TOUCH_MOVE); break;
  case MULTITOUCH_UP: event.SetTouchType(df::TouchEvent::TOUCH_UP); break;
  case MULTITOUCH_CANCEL: event.SetTouchType(df::TouchEvent::TOUCH_CANCEL); break;
  default: return;
  }

  df::Touch touch;
  touch.m_location = m2::PointD(f1.m_x, f1.m_y);
  touch.m_id = f1.m_id;
  event.SetFirstTouch(touch);

  touch.m_location = m2::PointD(f2.m_x, f2.m_y);
  touch.m_id = f2.m_id;
  event.SetSecondTouch(touch);

  event.SetFirstMaskedPointer(maskedPointer);
  m_work.TouchEvent(event);
}

m2::PointD Framework::GetViewportCenter() const
{
  return m_work.GetViewportCenter();
}

void Framework::AddString(string const & name, string const & value)
{
  m_work.AddString(name, value);
}

void Framework::Scale(::Framework::EScaleMode mode)
{
  m_work.Scale(mode, true);
}

void Framework::Scale(m2::PointD const & centerPt, int targetZoom, bool animate)
{
  ref_ptr<df::DrapeEngine> engine = m_work.GetDrapeEngine();
  if (engine)
    engine->SetModelViewCenter(centerPt, targetZoom, animate, false);
}

dp::TAccessibilityStableID Framework::GetAccessibilityNodeAtPoint(m2::PointD const & point)
{
  ref_ptr<df::DrapeEngine> engine = m_work.GetDrapeEngine();
  if (engine)
  {
    auto presenter = engine->GetAccessibilityPresenter();
    if (presenter)
      return (*presenter)->GetNodeAtPoint(point);
  }
  return 0;
}

// Not reentrant-safe; return value is only valid until the next call of this method.
dp::TAccessibilityStableIDContainer & Framework::GetAllAccessibilityNodes()
{
  m_allAccessibilityNodes.clear();

  ref_ptr<df::DrapeEngine> engine = m_work.GetDrapeEngine();
  if (engine)
  {
    auto presenter = engine->GetAccessibilityPresenter();
    if (presenter)
      (*presenter)->GetAllNodes(m_allAccessibilityNodes);
  }
  return m_allAccessibilityNodes;
}

// Not async-safe; return value is only valid until control of the thread is yielded.
// Make a copy if you want to store it.
std::optional<ref_ptr<dp::AccessibilityNodeContext>> Framework::GetAccessibilityNodeContext(
    dp::TAccessibilityStableID id)
{
  ref_ptr<df::DrapeEngine> engine = m_work.GetDrapeEngine();
  if (engine)
  {
    auto presenter = engine->GetAccessibilityPresenter();
    if (presenter)
    {
      auto overlay = (*presenter)->GetNode(id);
      if (overlay)
        return overlay;
    }
  }
  return {};
}

bool Framework::SetAccessibilityUpdateCallback(std::optional<dp::AccessibilityPresenter::TUpdateCallback> const & cb)
{
  ref_ptr<df::DrapeEngine> engine = m_work.GetDrapeEngine();
  if (engine)
  {
    auto presenter = engine->GetAccessibilityPresenter();

    if (static_cast<bool>(presenter) != static_cast<bool>(cb))
    {
      std::optional<drape_ptr<dp::AccessibilityPresenter>> new_presenter;
      if (cb)
        new_presenter = make_unique_dp<dp::AccessibilityPresenter>();
      else
        new_presenter = {};
      engine->SetAccessibilityPresenter(std::move(new_presenter));
      presenter = engine->GetAccessibilityPresenter();
    }

    if (presenter)
    {
      (*presenter)->SetUpdateCallback(cb);
      return true;
    }
  }
  return !cb;
}

void Framework::SetAccessibilityFreezeFrame(bool freeze)
{
  // we could just freeze the accessibility presenter, but better to freeze the whole drape engine so that the screen aligns
  // (e.g. for low vision users who still use sight during touch exploration)
  if (freeze)
    m_work.SetRenderingDisabled(false /* destroySurface */);
  else
    m_work.SetRenderingEnabled();
}

::Framework * Framework::NativeFramework()
{
  return &m_work;
}

bool Framework::Search(search::EverywhereSearchParams const & params)
{
  m_searchQuery = params.m_query;
  return m_work.GetSearchAPI().SearchEverywhere(params);
}

void Framework::AddLocalMaps()
{
  m_work.RegisterAllMaps();
}

void Framework::RemoveLocalMaps()
{
  m_work.DeregisterAllMaps();
}

void Framework::ReloadWorldMaps()
{
  /// @todo Can invent more optimal routine to remove/add World files only.
  RemoveLocalMaps();
  AddLocalMaps();
}

void Framework::ReplaceBookmark(kml::MarkId markId, kml::BookmarkData & bm)
{
  m_work.GetBookmarkManager().GetEditSession().UpdateBookmark(markId, bm);
}

void Framework::ReplaceTrack(kml::TrackId trackId, kml::TrackData & trackData)
{
  m_work.GetBookmarkManager().GetEditSession().UpdateTrack(trackId, trackData);
}

void Framework::ChangeTrackColor(kml::TrackId trackId, dp::Color color)
{
  m_work.GetBookmarkManager().GetEditSession().ChangeTrackColor(trackId, color);
}

void Framework::MoveBookmark(kml::MarkId markId, kml::MarkGroupId curCat, kml::MarkGroupId newCat)
{
  m_work.GetBookmarkManager().GetEditSession().MoveBookmark(markId, curCat, newCat);
}

void Framework::MoveTrack(kml::TrackId trackId, kml::MarkGroupId curCat, kml::MarkGroupId newCat)
{
  m_work.GetBookmarkManager().GetEditSession().MoveTrack(trackId, curCat, newCat);
}

void Framework::ExecuteMapApiRequest()
{
  return m_work.ExecuteMapApiRequest();
}

void Framework::DeactivatePopup()
{
  m_work.DeactivateMapSelection();
}

void Framework::DeactivateMapSelectionCircle(bool restoreViewport)
{
  m_work.DeactivateMapSelectionCircle(restoreViewport);
}

/*
string Framework::GetOutdatedCountriesString()
{
  vector<Country const *> countries;
  class Storage const & storage = GetStorage();
  storage.GetOutdatedCountries(countries);

  string res;
  NodeAttrs attrs;

  for (size_t i = 0; i < countries.size(); ++i)
  {
    storage.GetNodeAttrs(countries[i]->Name(), attrs);

    if (i > 0)
      res += ", ";

    res += attrs.m_nodeLocalName;
  }

  return res;
}
*/

void Framework::SetTrafficStateListener(TrafficManager::TrafficStateChangedFn const & fn)
{
  m_onTrafficStateChangedFn = fn;
}

void Framework::SetTransitSchemeListener(TransitReadManager::TransitStateChangedFn const & function)
{
  m_onTransitStateChangedFn = function;
}

void Framework::SetIsolinesListener(IsolinesManager::IsolinesStateChangedFn const & function)
{
  m_onIsolinesStateChangedFn = function;
}

bool Framework::IsTrafficEnabled()
{
  return NativeFramework()->DrivingMapModeHasTraffic();
}

void Framework::EnableTraffic()
{
  NativeFramework()->DrivingMapModeSetTraffic(true);
}

void Framework::DisableTraffic()
{
  NativeFramework()->DrivingMapModeSetTraffic(false);
}

void Framework::SetMyPositionModeListener(location::TMyPositionModeChanged const & fn)
{
  m_myPositionModeSignal = fn;
}

location::EMyPositionMode Framework::GetMyPositionMode() const
{
  // No need in assertion here, return location::PendingPosition if no engine created.
  // ASSERT(IsDrapeEngineCreated(), ());

  return m_work.GetMyPositionMode();
}

void Framework::SwitchMyPositionNextMode()
{
  ASSERT(IsDrapeEngineCreated(), ());
  m_work.SwitchMyPositionNextMode();
}

void Framework::StartPendingPositionMode()
{
  ASSERT(IsDrapeEngineCreated(), ());
  m_work.StartPendingPositionMode();
}

void Framework::SetupWidget(gui::EWidget widget, float x, float y, dp::Anchor anchor)
{
  m_guiPositions[widget] = gui::Position(m2::PointF(x, y), anchor);
}

void Framework::UpdateMyPositionRoutingOffset(int offsetY)
{
  NativeFramework()->UpdateMyPositionRoutingOffset(false, offsetY);
}

void Framework::SetCarNavigationView (bool enabled)
{
  NativeFramework()->SetCarNavigationView(enabled);
}

void Framework::ApplyWidgets()
{
  gui::TWidgetsLayoutInfo layout;
  for (auto const & widget : m_guiPositions)
    layout[widget.first] = widget.second.m_pixelPivot;

  m_work.SetWidgetLayout(std::move(layout));
}

void Framework::CleanWidgets()
{
  m_guiPositions.clear();
}

void Framework::SetupMeasurementSystem()
{
  m_work.SetupMeasurementSystem();
}

place_page::Info & Framework::GetPlacePageInfo()
{
  return m_work.GetCurrentPlacePageInfo();
}

osm::MapObject Framework::GetMapObjectByID(FeatureID const & featureId) const
{
  return m_work.GetMapObjectByID(featureId);
}

bool Framework::IsAutoRetryDownloadFailed()
{
  return m_work.GetDownloadingPolicy().IsAutoRetryDownloadFailed();
}

bool Framework::IsDownloadOn3gEnabled()
{
  return m_work.GetDownloadingPolicy().IsCellularDownloadEnabled();
}

void Framework::EnableDownloadOn3g()
{
  m_work.GetDownloadingPolicy().EnableCellularDownload(true);
}

/*
int Framework::ToDoAfterUpdate() const
{
  return (int) m_work.ToDoAfterUpdate();
}
*/

void Framework::OnPowerFacilityChanged(power_management::Facility const facility, bool enabled)
{
  // Dummy
  // TODO: provide information for UI Properties.
}

void Framework::OnPowerSchemeChanged(power_management::Scheme const actualScheme)
{
  // Dummy
  // TODO: provide information for UI Properties.
}

FeatureID Framework::BuildFeatureId(JNIEnv * env, jobject featureId)
{
  static FeatureIdBuilder const builder(env);

  return builder.Build(env, featureId);
}
}  // namespace android

//============ GLUE CODE for app.organicmaps.Framework class =============//
/*            ____
 *          _ |||| _
 *          \\    //
 *           \\  //
 *            \\//
 *             \/
 */

extern "C"
{
void CallRoutingListener(shared_ptr<jobject> listener, int errorCode, storage::CountriesSet const & absentMaps)
{
  JNIEnv * env = jni::GetEnv();
  jmethodID const method = jni::GetMethodID(env, *listener, "onRoutingEvent", "(I[Ljava/lang/String;)V");
  ASSERT(method, ());

  env->CallVoidMethod(*listener, method, errorCode,
                      jni::TScopedLocalObjectArrayRef(env, jni::ToJavaStringArray(env, absentMaps)).get());
}

void CallTurnPreviewChangedListener(shared_ptr<jobject> listener, bool isPreviewing, uint32_t segmentIndex)
{
  JNIEnv * env = jni::GetEnv();
  jmethodID const methodId = jni::GetMethodID(env, *listener, "onTurnPreviewChanged", "(ZI)V");
  env->CallVoidMethod(*listener, methodId, static_cast<jboolean>(isPreviewing), static_cast<jint>(segmentIndex));
}

void CallRouteProgressListener(shared_ptr<jobject> listener, float progress)
{
  JNIEnv * env = jni::GetEnv();
  jmethodID const methodId = jni::GetMethodID(env, *listener, "onRouteBuildingProgress", "(F)V");
  env->CallVoidMethod(*listener, methodId, progress);
}

void CallRouteRecommendationListener(shared_ptr<jobject> listener, RoutingManager::Recommendation recommendation)
{
  JNIEnv * env = jni::GetEnv();
  jmethodID const methodId =
      jni::GetMethodID(env, *listener, "onRecommend", "(Lapp/organicmaps/sdk/routing/RouteRecommendationType;)V");
  env->CallVoidMethod(*listener, methodId, GetRouteRecommendationType(env, recommendation));
}

void CallSetRoutingLoadPointsListener(shared_ptr<jobject> listener, bool success)
{
  JNIEnv * env = jni::GetEnv();
  jmethodID const methodId = jni::GetMethodID(env, *listener, "onRoutePointsLoaded", "(Z)V");
  env->CallVoidMethod(*listener, methodId, static_cast<jboolean>(success));
}

RoutingManager::LoadRouteHandler g_loadRouteHandler;

/// @name JNI EXPORTS
JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetAddress(JNIEnv * env, jclass clazz, jdouble lat,
                                                                              jdouble lon)
{
  auto const info = frm()->GetAddressAtPoint(mercator::FromLatLon(lat, lon));
  return jni::ToJavaString(env, info.FormatAddress());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeClearApiPoints(JNIEnv * env, jclass clazz)
{
  frm()->GetBookmarkManager().GetEditSession().ClearGroup(UserMark::Type::API);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetLiveVehicles(JNIEnv * env, jclass,
                                                                               jdoubleArray coordinates,
                                                                               jobjectArray labels)
{
  if (coordinates == nullptr || labels == nullptr)
    return;
  auto const count = env->GetArrayLength(labels);
  if (count > 2000 || env->GetArrayLength(coordinates) != count * 2)
    return;
  std::vector<jdouble> points(count * 2);
  if (count > 0)
    env->GetDoubleArrayRegion(coordinates, 0, count * 2, points.data());
  auto session = frm()->GetBookmarkManager().GetEditSession();
  session.ClearGroup(UserMark::LIVE_VEHICLE);
  for (jsize i = 0; i < count; ++i)
  {
    auto const lat = points[i * 2];
    auto const lon = points[i * 2 + 1];
    if (!std::isfinite(lat) || !std::isfinite(lon) || lat < 55 || lat > 58.6 || lon < 20 || lon > 28.8)
      continue;
    jni::TScopedLocalRef label(env, env->GetObjectArrayElement(labels, i));
    auto * mark = session.CreateUserMark<LiveVehicleMark>(mercator::FromLatLon(lat, lon));
    mark->SetTitle(jni::ToNativeString(env, static_cast<jstring>(label.get())));
  }
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeResetPedestrianConnections(JNIEnv *, jclass)
{
  frm()->GetRoutingManager().ResetPedestrianConnections();
}

// Called on the provider's serial worker, never on the UI thread.
JNIEXPORT jdoubleArray JNICALL Java_app_organicmaps_sdk_Framework_nativePedestrianConnection(
    JNIEnv * env, jclass, jdouble fromLat, jdouble fromLon, jdouble toLat, jdouble toLon)
{
  // Latvia with a margin (TransitArea.plausible): walks to and from stops in every Latvian city.
  if (!std::isfinite(fromLat) || !std::isfinite(fromLon) || !std::isfinite(toLat) || !std::isfinite(toLon)
      || fromLat < 55 || fromLat > 58.6 || toLat < 55 || toLat > 58.6
      || fromLon < 20 || fromLon > 28.8 || toLon < 20 || toLon > 28.8)
    return env->NewDoubleArray(0);
  auto const result = frm()->GetRoutingManager().CalculatePedestrianConnection(
      mercator::FromLatLon(fromLat, fromLon), mercator::FromLatLon(toLat, toLon));
  auto array = env->NewDoubleArray(result.size());
  if (!result.empty())
    env->SetDoubleArrayRegion(array, 0, result.size(), result.data());
  return array;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeCancelRouteBuild(JNIEnv *, jclass)
{
  frm()->GetRoutingManager().CloseRouting(false /* preserve route points */);
}

constexpr int kRigaJourneyMaxLegs = 64;

struct RigaJourneyLines
{
  std::vector<std::vector<m2::PointD>> geometry;
  std::vector<jboolean> walk;
  std::vector<jint> fills;
  /// Pixels per map unit the walks' dashes were laid out for; 0 while no journey is drawn.
  double dashScale = 0;
  bool listening = false;
};
RigaJourneyLines g_rigaJourney;

void RemoveRigaJourney()
{
  auto & api = frm()->GetDrapeApi();
  for (int i = 0; i < kRigaJourneyMaxLegs; ++i)
  {
    api.RemoveLine("riga-journey-halo:" + std::to_string(i));
    api.RemoveLine("riga-journey-leg:" + std::to_string(i));
    for (int end = 0; end < 2; ++end)
    {
      api.RemoveLine("riga-journey-stop:" + std::to_string(i) + ":" + std::to_string(end));
      api.RemoveLine("riga-journey-stop-centre:" + std::to_string(i) + ":" + std::to_string(end));
    }
  }
}

/// Adds the stored journey's lines, the walks' dashes laid out for dashScale pixels per map unit.
void RenderRigaJourney(double dashScale)
{
  g_rigaJourney.dashScale = dashScale;
  // The journey is drawn like a printed transit map laid over the city, in layers (DrapeApi draws lines in the
  // order they are added): first a halo under every ride that cuts it out of the busy base map (beige land and
  // yellow/orange roads by day, which bus yellow and tram red would otherwise melt into), then the walks, then
  // each ride as its line colour inside a keyline, then the boarding and alighting stops on top.
  //   Day:   white halo | keyline in a dark shade of the line colour | line colour.
  //   Night: near-black halo | keyline in a light tint of the line colour | line colour.
  // The halo is what roads never have, so a ride reads as "the route" whatever its colour; the keyline keeps
  // light colours (yellow buses) crisp on the white halo. Widths are in dp, as DrapeApi lines are in raw pixels.
  auto const & geometry = g_rigaJourney.geometry;
  auto const & walk = g_rigaJourney.walk;
  auto const & fills = g_rigaJourney.fills;
  auto const count = static_cast<jsize>(geometry.size());
  auto & api = frm()->GetDrapeApi();
  bool const dark = MapStyleIsDark(GetStyleReader().GetCurrentStyle());
  float const dp = static_cast<float>(df::VisualParams::Instance().GetVisualScale());
  dp::Color const halo = dark ? dp::Color(16, 18, 22, 255) : dp::Color(255, 255, 255, 255);
  auto const keyline = [dark](dp::Color const & fill)
  {
    // Day: 40% of the colour; night: 55% of the way to white.
    if (!dark)
      return dp::Color(fill.GetRed() * 40 / 100, fill.GetGreen() * 40 / 100, fill.GetBlue() * 40 / 100, 255);
    return dp::Color(fill.GetRed() + (255 - fill.GetRed()) * 55 / 100, fill.GetGreen() + (255 - fill.GetGreen()) * 55 / 100,
                     fill.GetBlue() + (255 - fill.GetBlue()) * 55 / 100, 255);
  };
  auto const colour = [&fills](jsize i)
  {
    uint32_t const argb = fills[i];
    return dp::Color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, 255);
  };
  float constexpr kRide = 7, kKeyline = 10.5f, kHalo = 15;
  dp::PenPatternT const dashes{dp::PatternFloat2Pixel(4.0 * dp), dp::PatternFloat2Pixel(4.0 * dp)};  // patterns.txt
  // 1. Halos under every ride, so where rides cross or meet at a change no halo cuts another ride's colour.
  for (jsize i = 0; i < count; ++i)
    if (!walk[i])
      api.AddLine("riga-journey-halo:" + std::to_string(i), df::DrapeApiLineData(geometry[i], halo).Width(kHalo * dp));
  // 2. Walks: dark (by day) or light (at night) dashes on their own narrow halo, under the rides.
  for (jsize i = 0; i < count; ++i)
    if (walk[i])
      api.AddLine("riga-journey-leg:" + std::to_string(i),
                  df::DrapeApiLineData(geometry[i], dark ? dp::Color(220, 224, 230, 255) : dp::Color(60, 70, 85, 255))
                      .Width(4 * dp).Casing(halo, 8 * dp).Dashes(dashes, dashScale));
  // 3. Rides: the line colour inside its keyline.
  for (jsize i = 0; i < count; ++i)
  {
    if (walk[i])
      continue;
    dp::Color const fill = colour(i);
    api.AddLine("riga-journey-leg:" + std::to_string(i),
                df::DrapeApiLineData(geometry[i], fill).Width(kRide * dp).Casing(keyline(fill), kKeyline * dp));
  }
  // 4. Boarding and alighting stops last, so no leg is drawn over them: a ring in the line colour with the same
  // keyline and halo as the ride, around a white (day) or dark (night) centre. Each is a round-capped stub of a
  // few metres, so it goes through the same line path as the journey.
  for (jsize i = 0; i < count; ++i)
  {
    if (walk[i])
      continue;
    dp::Color const fill = colour(i);
    auto const & line = geometry[i];
    for (int end = 0; end < 2; ++end)
    {
      m2::PointD const at = end == 0 ? line.front() : line.back();
      m2::PointD const toward = end == 0 ? line[1] : line[line.size() - 2];
      m2::PointD const step = toward - at;
      double const length = step.Length();
      m2::PointD const stub = at + (length > 0 ? step * (2e-5 / length) : m2::PointD(2e-5, 0));
      std::string const id = std::to_string(i) + ":" + std::to_string(end);
      api.AddLine("riga-journey-stop:" + id,
                  df::DrapeApiLineData({at, stub}, fill).Width(13 * dp).Casing(keyline(fill), 16 * dp));
      api.AddLine("riga-journey-stop-centre:" + id,
                  df::DrapeApiLineData({at, stub}, dark ? halo : dp::Color(255, 255, 255, 255)).Width(6.5f * dp));
    }
  }
}

/// Walk dashes are laid out in map units for one scale and stay right only near it: zoomed in a few times past it,
/// each piece of the line runs off the end of the dash pattern and draws as a blank (or one long dash) stretch.
/// Map features are rebuilt per zoom level for this; the journey is rebuilt here when the zoom has moved too far.
void OnRigaJourneyViewport(ScreenBase const & screen)
{
  if (g_rigaJourney.dashScale <= 0 || g_rigaJourney.geometry.empty())
    return;
  bool hasWalk = false;
  for (auto const w : g_rigaJourney.walk)
    hasWalk = hasWalk || w;
  if (!hasWalk)
    return;
  double const current = 1.0 / screen.GetScale();
  if (!std::isfinite(current) || current <= 0)
    return;
  double const ratio = current / g_rigaJourney.dashScale;
  if (ratio > 0.6 && ratio < 1.8)
    return;
  RemoveRigaJourney();
  RenderRigaJourney(current);
}

void DrawRigaJourney(JNIEnv * env,
                                                                              jobjectArray lines,
                                                                              jbooleanArray walking, jintArray colors, jboolean fit)
{
  // A multi-stop journey joins several plans (each up to 16 legs) with stays between them.
  RemoveRigaJourney();
  g_rigaJourney.geometry.clear();
  g_rigaJourney.dashScale = 0;
  if (lines == nullptr || walking == nullptr)
    return;
  auto const all = env->GetArrayLength(lines);
  if (env->GetArrayLength(walking) != all)
    return;
  // Longer journeys show their first legs rather than nothing.
  auto const count = std::min<jsize>(all, kRigaJourneyMaxLegs);
  std::vector<jint> fills(count, 0xff1868e0);
  if (colors != nullptr && env->GetArrayLength(colors) == all && count > 0)
    env->GetIntArrayRegion(colors,0,count,fills.data());
  std::vector<jboolean> walk(count);
  if (count > 0)
    env->GetBooleanArrayRegion(walking, 0, count, walk.data());
  std::vector<std::vector<m2::PointD>> geometry;
  size_t total = 0;
  for (jsize i = 0; i < count; ++i)
  {
    jni::TScopedLocalRef line(env, env->GetObjectArrayElement(lines, i));
    if (line.get() == nullptr)
      return;
    auto const array = static_cast<jdoubleArray>(line.get());
    auto const size = env->GetArrayLength(array);
    total += size;
    if (size < 4 || size % 2 != 0 || size > 40004 || total > 200000)
      return;
    std::vector<jdouble> coordinates(size);
    env->GetDoubleArrayRegion(array, 0, size, coordinates.data());
    std::vector<m2::PointD> points;
    for (jsize p = 0; p < size; p += 2)
    {
      double const lat = coordinates[p];
      double const lon = coordinates[p + 1];
      // Latvia with a margin (the app's transit area): Liepāja to Daugavpils, and walks over the border.
      if (!std::isfinite(lat) || !std::isfinite(lon) || lat < 55 || lat > 58.6 || lon < 20 || lon > 28.8)
        return;
      points.push_back(mercator::FromLatLon(lat, lon));
    }
    geometry.push_back(std::move(points));
  }
  g_rigaJourney.geometry = std::move(geometry);
  g_rigaJourney.walk = std::move(walk);
  g_rigaJourney.fills = std::move(fills);
  if (!g_rigaJourney.listening)
  {
    g_rigaJourney.listening = true;
    frm()->SetViewportListener(&OnRigaJourneyViewport);
  }
  m2::RectD bounds;
  for (auto const & line : g_rigaJourney.geometry)
    for (auto const & point : line)
      bounds.Add(point);
  bounds.Scale(1.25);
  // Dashes are laid out in map units: size them for the scale the journey is framed at, whether or not this draw
  // frames it. The current scale is no guide: a redraw can come while the camera is still moving (a refresh during
  // the framing animation), and dashes sized for that passing zoom come out several times too long or too fine.
  // Framing stops at kFitMaxScale, so a short journey (a walk of a few hundred metres) is sized for that zoom.
  int constexpr kFitMaxScale = 16;
  m2::RectD const view = frm()->GetCurrentViewport();
  double const current = frm()->GtoP(view.RightTop()).Length(frm()->GtoP(view.LeftBottom())) / view.LeftBottom().Length(view.RightTop());
  double const framed = bounds.IsEmptyInterior() || !std::isfinite(current) || current <= 0 ? current
      : std::min(current * std::min(view.SizeX() / bounds.SizeX(), view.SizeY() / bounds.SizeY()),
                 1.0 / df::GetScreenScale(kFitMaxScale));
  double const dashScale = std::isfinite(framed) && framed > 0 ? framed : 1.0;
  RenderRigaJourney(dashScale);
  if (count > 0 && fit)
  {
    frm()->ShowRect(bounds, kFitMaxScale, true /* animation */, true /* useVisibleViewport */);
  }
  // An idle renderer (a cold start, or a redraw for a new map style) would otherwise keep the old frame on screen
  // until the map is touched.
  frm()->MakeFrameActive();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeDrawRigaJourney(
    JNIEnv * env, jclass, jobjectArray lines, jbooleanArray walking, jboolean fit)
{
  DrawRigaJourney(env,lines,walking,nullptr,fit);
}
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeDrawColoredRigaJourney(
    JNIEnv * env, jclass, jobjectArray lines, jbooleanArray walking, jintArray colors, jboolean fit)
{
  DrawRigaJourney(env,lines,walking,colors,fit);
}
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetJourneyVehicles(
    JNIEnv * env, jclass, jdoubleArray coordinates, jobjectArray numbers, jintArray colors, jobjectArray titles)
{
  auto session = frm()->GetBookmarkManager().GetEditSession();
  session.ClearGroup(UserMark::LIVE_VEHICLE);
  if (!coordinates || !numbers || !colors || !titles) return;
  jsize const count = env->GetArrayLength(numbers);
  if (count > 16 || env->GetArrayLength(coordinates) != count*2 || env->GetArrayLength(colors) != count
      || env->GetArrayLength(titles) != count) return;
  std::vector<jdouble> points(count*2);
  std::vector<jint> fills(count);
  if (count > 0)
  {
    env->GetDoubleArrayRegion(coordinates,0,count*2,points.data());
    env->GetIntArrayRegion(colors,0,count,fills.data());
  }
  for (jsize i = 0; i < count; ++i)
  {
    double const lat = points[2*i], lon = points[2*i+1];
    if (!std::isfinite(lat) || !std::isfinite(lon) || lat < 55 || lat > 58.6 || lon < 20 || lon > 28.8) continue;
    jni::TScopedLocalRef number(env,env->GetObjectArrayElement(numbers,i));
    jni::TScopedLocalRef title(env,env->GetObjectArrayElement(titles,i));
    if (!number.get() || !title.get()) continue;
    uint32_t const fill = fills[i];
    auto * mark = session.CreateUserMark<LiveVehicleMark>(mercator::FromLatLon(lat,lon));
    mark->SetTitle(jni::ToNativeString(env,static_cast<jstring>(title.get())));
    mark->SetBadge(jni::ToNativeString(env,static_cast<jstring>(number.get())),
        dp::Color((fill >> 16) & 255,(fill >> 8) & 255,fill & 255,(fill >> 24) & 255));
  }
}

JNIEXPORT jint JNICALL Java_app_organicmaps_sdk_Framework_nativeParseAndSetApiUrl(JNIEnv * env, jclass clazz,
                                                                                  jstring url)
{
  return static_cast<jint>(frm()->ParseAndSetApiURL(jni::ToNativeString(env, url)));
}

JNIEXPORT jobject JNICALL Java_app_organicmaps_sdk_Framework_nativeGetParsedRoutingData(JNIEnv * env, jclass clazz)
{
  using namespace url_scheme;
  static jclass const pointClazz = jni::GetGlobalClassRef(env, "app/organicmaps/sdk/api/RoutePoint");
  // Java signature : RoutePoint(double lat, double lon, String name)
  static jmethodID const pointConstructor = jni::GetConstructorID(env, pointClazz, "(DDLjava/lang/String;)V");

  static jclass const routeDataClazz = jni::GetGlobalClassRef(env, "app/organicmaps/sdk/api/ParsedRoutingData");
  // Java signature : ParsedRoutingData(RoutePoint[] points, int routerType) {
  static jmethodID const routeDataConstructor =
      jni::GetConstructorID(env, routeDataClazz, "([Lapp/organicmaps/sdk/api/RoutePoint;I)V");

  auto const & routingData = frm()->GetParsedRoutingData();
  jobjectArray points =
      jni::ToJavaArray(env, pointClazz, routingData.m_points, [](JNIEnv * env, RoutePoint const & point)
  {
    jni::TScopedLocalRef const name(env, jni::ToJavaString(env, point.m_name));
    return env->NewObject(pointClazz, pointConstructor, mercator::YToLat(point.m_org.y),
                          mercator::XToLon(point.m_org.x), name.get());
  });

  return env->NewObject(routeDataClazz, routeDataConstructor, points, routingData.m_type);
}

JNIEXPORT jobject JNICALL Java_app_organicmaps_sdk_Framework_nativeGetParsedSearchRequest(JNIEnv * env, jclass clazz)
{
  using namespace url_scheme;
  static jclass const cl = jni::GetGlobalClassRef(env, "app/organicmaps/sdk/api/ParsedSearchRequest");
  // Java signature : ParsedSearchRequest(String query, String locale, double lat, double lon, boolean isSearchOnMap)
  static jmethodID const ctor = jni::GetConstructorID(env, cl, "(Ljava/lang/String;Ljava/lang/String;DDZ)V");
  auto const & r = frm()->GetParsedSearchRequest();
  ms::LatLon const center = frm()->GetParsedCenterLatLon();
  return env->NewObject(cl, ctor, jni::ToJavaString(env, r.m_query), jni::ToJavaString(env, r.m_locale), center.m_lat,
                        center.m_lon, r.m_isSearchOnMap);
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetParsedAppName(JNIEnv * env, jclass)
{
  std::string const & appName = frm()->GetParsedAppName();
  return (appName.empty()) ? nullptr : jni::ToJavaString(env, appName);
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetParsedOAuth2Code(JNIEnv * env, jclass)
{
  std::string const & code = frm()->GetParsedOAuth2Code();
  return jni::ToJavaString(env, code);
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetParsedBackUrl(JNIEnv * env, jclass)
{
  std::string const & backUrl = frm()->GetParsedBackUrl();
  return (backUrl.empty()) ? nullptr : jni::ToJavaString(env, backUrl);
}

JNIEXPORT jdoubleArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetParsedCenterLatLon(JNIEnv * env, jclass)
{
  ms::LatLon const center = frm()->GetParsedCenterLatLon();
  if (!center.IsValid())
    return nullptr;

  double latlon[] = {center.m_lat, center.m_lon};
  jdoubleArray jLatLon = env->NewDoubleArray(2);
  env->SetDoubleArrayRegion(jLatLon, 0, 2, latlon);

  return jLatLon;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativePlacePageActivationListener(JNIEnv * env, jclass,
                                                                                            jobject jListener)
{
  LOG(LINFO, ("Set global map object listener"));
  g_placePageActivationListener = env->NewGlobalRef(jListener);
  // void onPlacePageActivated(MapObject object);
  jmethodID const activatedId = jni::GetMethodID(env, g_placePageActivationListener, "onPlacePageActivated",
                                                 "(Lapp/organicmaps/sdk/widget/placepage/PlacePageData;)V");
  // void onPlacePageDeactivated();
  jmethodID const deactivateId = jni::GetMethodID(env, g_placePageActivationListener, "onPlacePageDeactivated", "()V");
  // void onPlacePageDeactivated();
  jmethodID const switchFullscreenId =
      jni::GetMethodID(env, g_placePageActivationListener, "onSwitchFullScreenMode", "()V");
  auto const fillPlacePage = [activatedId]()
  {
    JNIEnv * env = jni::GetEnv();
    auto const & info = frm()->GetCurrentPlacePageInfo();
    jni::TScopedLocalRef placePageDataRef(env, nullptr);
    placePageDataRef.reset(usermark_helper::CreateMapObject(env, info));
    env->CallVoidMethod(g_placePageActivationListener, activatedId, placePageDataRef.get());
  };
  auto const closePlacePage = [deactivateId]()
  {
    JNIEnv * env = jni::GetEnv();
    env->CallVoidMethod(g_placePageActivationListener, deactivateId);
  };
  auto const switchFullscreen = [switchFullscreenId]()
  {
    JNIEnv * env = jni::GetEnv();
    env->CallVoidMethod(g_placePageActivationListener, switchFullscreenId);
  };

  frm()->SetPlacePageListeners(fillPlacePage, closePlacePage, fillPlacePage, switchFullscreen);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeRemovePlacePageActivationListener(JNIEnv * env, jclass,
                                                                                                  jobject jListener)
{
  if (g_placePageActivationListener == nullptr)
    return;

  if (!env->IsSameObject(g_placePageActivationListener, jListener))
    return;

  frm()->SetPlacePageListeners({} /* onOpen */, {} /* onClose */, {} /* onUpdate */, {} /* onSwitchFullScreen */);
  LOG(LINFO, ("Remove global map object listener"));
  env->DeleteGlobalRef(g_placePageActivationListener);
  g_placePageActivationListener = nullptr;
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetGe0Url(JNIEnv * env, jclass, jdouble lat,
                                                                             jdouble lon, jdouble zoomLevel,
                                                                             jstring name)
{
  ::Framework * fr = frm();
  double const scale = (zoomLevel > 0 ? zoomLevel : fr->GetDrawScale());
  string const url = fr->CodeGe0url(lat, lon, scale, jni::ToNativeString(env, name));
  return jni::ToJavaString(env, url);
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetGeoUri(JNIEnv * env, jclass, jdouble lat,
                                                                             jdouble lon, jdouble zoomLevel,
                                                                             jstring name)
{
  ::Framework * fr = frm();
  double const scale = (zoomLevel > 0 ? zoomLevel : fr->GetDrawScale());
  string const url = ge0::GenerateGeoUri(lat, lon, scale, jni::ToNativeString(env, name));
  return jni::ToJavaString(env, url);
}

JNIEXPORT jobject JNICALL Java_app_organicmaps_sdk_Framework_nativeGetDistanceAndAzimuth(JNIEnv * env, jclass,
                                                                                         jdouble merX, jdouble merY,
                                                                                         jdouble cLat, jdouble cLon,
                                                                                         jdouble north)
{
  platform::Distance distance;
  double azimut = -1.0;
  frm()->GetDistanceAndAzimut(m2::PointD(merX, merY), cLat, cLon, north, distance, azimut);

  static jclass const daClazz = jni::GetGlobalClassRef(env, "app/organicmaps/sdk/bookmarks/data/DistanceAndAzimut");
  // Java signature : DistanceAndAzimut(Distance distance, double azimuth)
  static jmethodID const methodID = jni::GetConstructorID(env, daClazz, "(Lapp/organicmaps/sdk/util/Distance;D)V");

  return env->NewObject(daClazz, methodID, ToJavaDistance(env, distance), static_cast<jdouble>(azimut));
}

JNIEXPORT jobject JNICALL Java_app_organicmaps_sdk_Framework_nativeGetDistanceAndAzimuthFromLatLon(
    JNIEnv * env, jclass clazz, jdouble lat, jdouble lon, jdouble cLat, jdouble cLon, jdouble north)
{
  double const merY = mercator::LatToY(lat);
  double const merX = mercator::LonToX(lon);
  return Java_app_organicmaps_sdk_Framework_nativeGetDistanceAndAzimuth(env, clazz, merX, merY, cLat, cLon, north);
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeFormatLatLon(JNIEnv * env, jclass, jdouble lat,
                                                                                jdouble lon, int coordsFormat)
{
  switch (static_cast<android::CoordinatesFormat>(coordsFormat))
  {
  default:
  case android::CoordinatesFormat::LatLonDMS:  // DMS, comma separated
    return jni::ToJavaString(env, measurement_utils::FormatLatLonAsDMS(lat, lon, true /*withComma*/, 2));
  case android::CoordinatesFormat::LatLonDecimal:  // Decimal, comma separated
    return jni::ToJavaString(env, measurement_utils::FormatLatLon(lat, lon, true /* withComma */, 6));
  case android::CoordinatesFormat::OLCFull:  // Open location code, long format
    return jni::ToJavaString(env, openlocationcode::Encode({lat, lon}));
  case android::CoordinatesFormat::OSMLink:  // Link to osm.org
    return jni::ToJavaString(env, measurement_utils::FormatOsmLink(lat, lon, 14));
  case android::CoordinatesFormat::UTM:  // Universal Transverse Mercator
  {
    string utmFormat = utm_mgrs_utils::FormatUTM(lat, lon);
    if (!utmFormat.empty())
      return jni::ToJavaString(env, utmFormat);
    else
      return nullptr;
  }
  case android::CoordinatesFormat::MGRS:  // Military Grid Reference System
  {
    string mgrsFormat = utm_mgrs_utils::FormatMGRS(lat, lon, 5);
    if (!mgrsFormat.empty())
      return jni::ToJavaString(env, mgrsFormat);
    else
      return nullptr;
  }
  }
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeFormatAltitude(JNIEnv * env, jclass, jdouble alt)
{
  return jni::ToJavaString(env, platform::Distance::FormatAltitude(alt));
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeFormatSpeed(JNIEnv * env, jclass, jdouble speed)
{
  auto const units = measurement_utils::GetMeasurementUnits();
  return jni::ToJavaString(
      env, measurement_utils::FormatSpeedNumeric(speed, units) + " " + platform::GetLocalizedSpeedUnits(units));
}

/*
JNIEXPORT jobject JNICALL
Java_app_organicmaps_sdk_Framework_nativeGetOutdatedCountriesString(JNIEnv * env, jclass)
{
  return jni::ToJavaString(env, g_framework->GetOutdatedCountriesString());
}

JNIEXPORT jobjectArray JNICALL
Java_app_organicmaps_sdk_Framework_nativeGetOutdatedCountries(JNIEnv * env, jclass)
{
  vector<Country const *> countries;
  Storage const & storage = g_framework->GetStorage();
  storage.GetOutdatedCountries(countries);

  vector<string> ids;
  for (auto country : countries)
    ids.push_back(country->Name());

  return jni::ToJavaStringArray(env, ids);
}

JNIEXPORT jint JNICALL
Java_app_organicmaps_sdk_Framework_nativeToDoAfterUpdate(JNIEnv * env, jclass)
{
  return g_framework->ToDoAfterUpdate();
}

JNIEXPORT jboolean JNICALL
Java_app_organicmaps_sdk_Framework_nativeIsDataVersionChanged(JNIEnv * env, jclass)
{
  return frm()->IsDataVersionUpdated() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_organicmaps_sdk_Framework_nativeUpdateSavedDataVersion(JNIEnv * env, jclass)
{
  frm()->UpdateSavedDataVersion();
}
*/

JNIEXPORT jlong JNICALL Java_app_organicmaps_sdk_Framework_nativeGetDataVersion(JNIEnv * env, jclass)
{
  return frm()->GetCurrentDataVersion();
}

JNIEXPORT jint JNICALL Java_app_organicmaps_sdk_Framework_nativeGetDrawScale(JNIEnv * env, jclass)
{
  return static_cast<jint>(frm()->GetDrawScale());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativePokeSearchInViewport(JNIEnv * env, jclass)
{
  frm()->GetSearchAPI().PokeSearchInViewport();
}

JNIEXPORT jdoubleArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetScreenRectCenter(JNIEnv * env, jclass)
{
  m2::PointD const center = frm()->GetViewportCenter();

  double latlon[] = {mercator::YToLat(center.y), mercator::XToLon(center.x)};
  jdoubleArray jLatLon = env->NewDoubleArray(2);
  env->SetDoubleArrayRegion(jLatLon, 0, 2, latlon);

  return jLatLon;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeRestoreDownloadQueue(JNIEnv * env, jclass)
{
  frm()->GetStorage().RestoreDownloadQueue();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeShowTrackRect(JNIEnv * env, jclass, jlong track)
{
  frm()->ShowTrack(static_cast<kml::TrackId>(track));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSaveRoute(JNIEnv *, jclass)
{
  frm()->SaveRoute();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetAutoReroute(JNIEnv * env, jclass,
                                                                               jboolean autoReroute)
{
  frm()->GetRoutingManager().SetAutoReroute(static_cast<bool>(autoReroute));
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeAutoReroute(JNIEnv * env, jclass)
{
  return static_cast<jboolean>(frm()->GetRoutingManager().AutoReroute());
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetBookmarkDir(JNIEnv * env, jclass)
{
  return jni::ToJavaString(env, GetPlatform().SettingsDir().c_str());
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetWritableDir(JNIEnv * env, jclass)
{
  return jni::ToJavaString(env, GetPlatform().WritableDir().c_str());
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetSettingsDir(JNIEnv * env, jclass)
{
  return jni::ToJavaString(env, GetPlatform().SettingsDir().c_str());
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetDataFileExt(JNIEnv * env, jclass)
{
  return jni::ToJavaString(env, DATA_FILE_EXTENSION);
}

JNIEXPORT jobjectArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetMovableFilesExts(JNIEnv * env, jclass)
{
  vector<string> exts = {DATA_FILE_EXTENSION, FONT_FILE_EXTENSION};
  platform::CountryIndexes::GetIndexesExts(exts);
  return jni::ToJavaStringArray(env, exts);
}

JNIEXPORT jobjectArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetBookmarksFilesExts(JNIEnv * env, jclass)
{
  static std::array<std::string, 4> const kBookmarkExtensions = {
      std::string{kKmzExtension}, std::string{kKmlExtension}, std::string{kKmbExtension}, std::string{kGpxExtension}};

  return jni::ToJavaStringArray(env, kBookmarkExtensions);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeChangeWritableDir(JNIEnv * env, jclass,
                                                                                  jstring jNewPath)
{
  string newPath = jni::ToNativeString(env, jNewPath);
  g_framework->RemoveLocalMaps();
  android::Platform::Instance().SetWritableDir(newPath);
  g_framework->AddLocalMaps();
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsRoutingActive(JNIEnv * env, jclass)
{
  return frm()->GetRoutingManager().IsRoutingActive();
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsRouteBuilding(JNIEnv * env, jclass)
{
  return frm()->GetRoutingManager().IsRouteBuilding();
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsRouteBuilt(JNIEnv * env, jclass)
{
  return frm()->GetRoutingManager().IsRouteBuilt();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeCloseRouting(JNIEnv * env, jclass)
{
  frm()->GetRoutingManager().CloseRouting(true /* remove route points */);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeBuildRoute(JNIEnv * env, jclass)
{
  frm()->GetRoutingManager().BuildRoute();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeRemoveRoute(JNIEnv * env, jclass)
{
  frm()->GetRoutingManager().RemoveRoute(false /* deactivateFollowing */);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeFollowRoute(JNIEnv * env, jclass)
{
  frm()->GetRoutingManager().FollowRoute();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeDisableFollowing(JNIEnv * env, jclass)
{
  frm()->GetRoutingManager().DisableFollowMode();
}

JNIEXPORT jobjectArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGenerateNotifications(JNIEnv * env, jclass,
                                                                                              jboolean announceStreets)
{
  ::Framework * fr = frm();
  if (!fr->GetRoutingManager().IsRoutingActive())
    return nullptr;

  vector<string> notifications;
  fr->GetRoutingManager().GenerateNotifications(notifications, announceStreets);
  if (notifications.empty())
    return nullptr;

  return jni::ToJavaStringArray(env, notifications);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetSpeedCamManagerMode(JNIEnv * env, jclass, jint mode)
{
  frm()->GetRoutingManager().GetSpeedCamManager().SetMode(static_cast<routing::SpeedCameraManagerMode>(mode));
}

JNIEXPORT jint JNICALL Java_app_organicmaps_sdk_Framework_nativeGetSpeedCamManagerMode(JNIEnv * env, jclass)
{
  return static_cast<jint>(frm()->GetRoutingManager().GetSpeedCamManager().GetMode());
}

JNIEXPORT jobject JNICALL Java_app_organicmaps_sdk_Framework_nativeGetRouteFollowingInfo(JNIEnv * env, jclass)
{
  RoutingManager & rm = frm()->GetRoutingManager();
  if (!rm.IsRoutingActive())
    return nullptr;

  routing::FollowingInfo info;
  rm.GetRouteFollowingInfo(info);
  if (!info.IsValid())
    return nullptr;

  return CreateRoutingInfo(env, info, rm);
}

JNIEXPORT jdouble JNICALL Java_app_organicmaps_sdk_Framework_nativeGetRoadSpeedLimit(JNIEnv *, jclass, jdouble lat,
                                                                                    jdouble lon, jdouble bearingDeg)
{
  return frm()->GetRoutingManager().GetRoadSpeedLimitMps(lat, lon, bearingDeg);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeResetRoadSpeedLimit(JNIEnv *, jclass)
{
  frm()->GetRoutingManager().ResetRoadSpeedLimitCache();
}

JNIEXPORT jobjectArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetRouteJunctionPoints(JNIEnv * env, jclass,
                                                                                               jdouble maxDistM)
{
  vector<geometry::PointWithAltitude> points;
  if (!frm()->GetRoutingManager().RoutingSession().GetRouteJunctionPoints(points))
  {
    LOG(LWARNING, ("Can't get the route junction points"));
    return nullptr;
  }

  vector<geometry::PointWithAltitude> result;
  result.reserve(points.size());
  result.push_back(points[0]);
  for (size_t i = 1; i < points.size(); ++i)
  {
    double const dist = ms::DistanceOnEarth(points[i - 1].ToLatLon(), points[i].ToLatLon());
    if (dist > maxDistM)
    {
      int const steps = static_cast<int>(dist / maxDistM) + 1;
      for (int s = 1; s < steps; ++s)
        result.push_back(points[i - 1].Interpolate(points[i], static_cast<double>(s) / steps));
    }
    result.push_back(points[i]);
  }

  return CreateJunctionInfoArray(env, result);
}

JNIEXPORT jintArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGenerateRouteAltitudeChartBits(
    JNIEnv * env, jclass, jint width, jint height, jobject routeAltitudeLimits)
{
  RoutingManager::DistanceAltitude altitudes;
  if (!frm()->GetRoutingManager().GetRouteAltitudesAndDistancesM(altitudes))
  {
    LOG(LWARNING, ("Can't get distance to route points and altitude."));
    return nullptr;
  }

  altitudes.Simplify();

  vector<uint8_t> imageRGBAData;
  if (!altitudes.GenerateRouteAltitudeChart(width, height, imageRGBAData))
  {
    LOG(LWARNING, ("Can't generate route altitude image."));
    return nullptr;
  }

  uint32_t totalAscent, totalDescent;
  altitudes.CalculateAscentDescent(totalAscent, totalDescent);

  // Android platform code has specific result string formatting, so make conversion here.
  using namespace measurement_utils;
  auto units = Units::Metric;
  if (settings::Get(settings::kMeasurementUnits, units) && units == Units::Imperial)
  {
    totalAscent = measurement_utils::MetersToFeet(totalAscent);
    totalDescent = measurement_utils::MetersToFeet(totalDescent);
  }

  jni::TScopedLocalRef const totalAscentString(env, jni::ToJavaString(env, ToStringPrecision(totalAscent, 0)));

  jni::TScopedLocalRef const totalDescentString(env, jni::ToJavaString(env, ToStringPrecision(totalDescent, 0)));

  // Passing route limits.
  // Do not use jni::GetGlobalClassRef, because this class is used only to init static fieldId vars.
  static jclass const routeAltitudeLimitsClass = env->GetObjectClass(routeAltitudeLimits);
  ASSERT(routeAltitudeLimitsClass, ());

  static jfieldID const totalAscentField = env->GetFieldID(routeAltitudeLimitsClass, "totalAscent", "I");
  ASSERT(totalAscentField, ());
  env->SetIntField(routeAltitudeLimits, totalAscentField, static_cast<jint>(totalAscent));

  static jfieldID const totalDescentField = env->GetFieldID(routeAltitudeLimitsClass, "totalDescent", "I");
  ASSERT(totalDescentField, ());
  env->SetIntField(routeAltitudeLimits, totalDescentField, static_cast<jint>(totalDescent));

  static jfieldID const totalAscentStringField =
      env->GetFieldID(routeAltitudeLimitsClass, "totalAscentString", "Ljava/lang/String;");
  ASSERT(totalAscentStringField, ());
  env->SetObjectField(routeAltitudeLimits, totalAscentStringField, totalAscentString.get());

  static jfieldID const totalDescentStringField =
      env->GetFieldID(routeAltitudeLimitsClass, "totalDescentString", "Ljava/lang/String;");
  ASSERT(totalDescentStringField, ());
  env->SetObjectField(routeAltitudeLimits, totalDescentStringField, totalDescentString.get());

  static jfieldID const isMetricUnitsField = env->GetFieldID(routeAltitudeLimitsClass, "isMetricUnits", "Z");
  ASSERT(isMetricUnitsField, ());
  env->SetBooleanField(routeAltitudeLimits, isMetricUnitsField, units == Units::Metric);

  size_t const imageRGBADataSize = imageRGBAData.size();
  ASSERT_NOT_EQUAL(imageRGBADataSize, 0,
                   ("GenerateRouteAltitudeChart returns true but the vector with altitude image bits is empty."));

  size_t const pxlCount = width * height;
  if (maps::kAltitudeChartBPP * pxlCount != imageRGBADataSize)
  {
    LOG(LWARNING,
        ("Wrong size of vector with altitude image bits. Expected size:", pxlCount, ". Real size:", imageRGBADataSize));
    return nullptr;
  }

  jintArray imageRGBADataArray = env->NewIntArray(static_cast<jsize>(pxlCount));
  ASSERT(imageRGBADataArray, ());
  jint * arrayElements = env->GetIntArrayElements(imageRGBADataArray, 0);
  ASSERT(arrayElements, ());

  for (size_t i = 0; i < pxlCount; ++i)
  {
    size_t const shiftInBytes = i * maps::kAltitudeChartBPP;
    // Type of |imageRGBAData| elements is uint8_t. But uint8_t is promoted to unsinged int in code below before
    // shifting. So there's no data lost in code below.
    arrayElements[i] = (imageRGBAData[shiftInBytes + 3] << 24) /* alpha */
                     | (imageRGBAData[shiftInBytes] << 16)     /* red */
                     | (imageRGBAData[shiftInBytes + 1] << 8)  /* green */
                     | (imageRGBAData[shiftInBytes + 2]);      /* blue */
  }
  env->ReleaseIntArrayElements(imageRGBADataArray, arrayElements, 0);

  return imageRGBADataArray;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeShowCountry(JNIEnv * env, jclass, jstring countryId,
                                                                            jboolean zoomToDownloadButton)
{
  g_framework->ShowNode(jni::ToNativeString(env, countryId), (bool)zoomToDownloadButton);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetRoutingListener(JNIEnv * env, jclass,
                                                                                   jobject listener)
{
  CHECK(g_framework, ("Framework isn't created yet!"));
  auto const rf = jni::make_global_ref(listener);
  auto & rm = frm()->GetRoutingManager();
  rm.SetRouteBuildingListener([rf](routing::RouterResultCode e, storage::CountriesSet const & countries)
  { CallRoutingListener(rf, static_cast<int>(e), countries); });
  rm.SetPreviewedTurnChangedCallback([rf](bool isPreviewing, uint32_t segmentIndex)
  { CallTurnPreviewChangedListener(rf, isPreviewing, segmentIndex); });
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetRouteProgressListener(JNIEnv * env, jclass,
                                                                                         jobject listener)
{
  CHECK(g_framework, ("Framework isn't created yet!"));
  frm()->GetRoutingManager().SetRouteProgressListener(
      bind(&CallRouteProgressListener, jni::make_global_ref(listener), _1));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetRoutingRecommendationListener(JNIEnv * env, jclass,
                                                                                                 jobject listener)
{
  CHECK(g_framework, ("Framework isn't created yet!"));
  frm()->GetRoutingManager().SetRouteRecommendationListener(
      bind(&CallRouteRecommendationListener, jni::make_global_ref(listener), _1));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetRoutingLoadPointsListener(JNIEnv *, jclass,
                                                                                             jobject listener)
{
  CHECK(g_framework, ("Framework isn't created yet!"));
  if (listener != nullptr)
    g_loadRouteHandler = bind(&CallSetRoutingLoadPointsListener, jni::make_global_ref(listener), _1);
  else
    g_loadRouteHandler = nullptr;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeDeactivatePopup(JNIEnv * env, jclass)
{
  return g_framework->DeactivatePopup();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeDeactivateMapSelectionCircle(JNIEnv * env, jclass,
                                                                                             jboolean restoreViewport)
{
  return g_framework->DeactivateMapSelectionCircle(restoreViewport);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeAddRoutePoint(
    JNIEnv * env, jclass, jstring title, jstring subtitle, jobject markType, jint intermediateIndex,
    jboolean isMyPosition, jdouble lat, jdouble lon, jboolean reorderIntermediatePoints)
{
  RouteMarkData data;
  data.m_title = jni::ToNativeString(env, title);
  data.m_subTitle = jni::ToNativeString(env, subtitle);
  data.m_pointType = GetRouteMarkType(env, markType);
  data.m_intermediateIndex = static_cast<size_t>(intermediateIndex);
  data.m_isMyPosition = static_cast<bool>(isMyPosition);
  data.m_position = m2::PointD(mercator::FromLatLon(lat, lon));

  frm()->GetRoutingManager().AddRoutePoint(std::move(data), reorderIntermediatePoints);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeRemoveRoutePoints(JNIEnv * env, jclass)
{
  frm()->GetRoutingManager().RemoveRoutePoints();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeRemoveRoutePoint(JNIEnv * env, jclass, jobject markType,
                                                                                 jint intermediateIndex)
{
  frm()->GetRoutingManager().RemoveRoutePoint(GetRouteMarkType(env, markType), static_cast<size_t>(intermediateIndex));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeRemoveIntermediateRoutePoints(JNIEnv * env, jclass)
{
  frm()->GetRoutingManager().RemoveIntermediateRoutePoints();
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeCouldAddIntermediatePoint(JNIEnv * env, jclass)
{
  return frm()->GetRoutingManager().CouldAddIntermediatePoint();
}

JNIEXPORT jobjectArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetRoutePoints(JNIEnv * env, jclass)
{
  return CreateRouteMarkDataArray(env, frm()->GetRoutingManager().GetRoutePoints());
}

JNIEXPORT jdoubleArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetIntermediateStopsProgress(JNIEnv * env, jclass)
{
  return CreateIntermediateStopsProgressArray(env, frm()->GetRoutingManager());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeMoveRoutePoint(JNIEnv * env, jclass, jint currentIndex,
                                                                               jint targetIndex)
{
  frm()->GetRoutingManager().MoveRoutePoint(currentIndex, targetIndex);
}

JNIEXPORT jobjectArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetRouteSteps(JNIEnv * env, jclass,
                                                                                      jstring language)
{
  std::string nativeLanguage = jni::ToNativeString(env, language);
  RoutingManager & rm = frm()->GetRoutingManager();
  if (!rm.IsRoutingActive() || !rm.IsRouteValid())
    return nullptr;

  auto const steps = rm.GetRouteTurnsForDisplay(nativeLanguage);
  return CreateRouteStepInfoArray(env, steps);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativePreviewTurn(JNIEnv *, jclass, jint segmentIndex)
{
  if (segmentIndex < 0)
    return;

  frm()->PreviewTurn(static_cast<uint32_t>(segmentIndex));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeClearPreviewedTurn(JNIEnv *, jclass)
{
  frm()->GetRoutingManager().ClearPreviewedTurn();
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsPreviewingTurn(JNIEnv *, jclass)
{
  return static_cast<jboolean>(frm()->GetRoutingManager().IsPreviewingTurn());
}

JNIEXPORT jobject JNICALL Java_app_organicmaps_sdk_Framework_nativeGetTransitRouteInfo(JNIEnv * env, jclass)
{
  return CreateTransitRouteInfo(env, frm()->GetRoutingManager().GetTransitRouteInfo());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeReloadWorldMaps(JNIEnv * env, jclass)
{
  g_framework->ReloadWorldMaps();
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsDayTime(JNIEnv * env, jclass,
                                                                              jlong utcTimeSeconds, jdouble lat,
                                                                              jdouble lon)
{
  DayTimeType const dt = GetDayTime(static_cast<time_t>(utcTimeSeconds), lat, lon);
  return (dt == DayTimeType::Day || dt == DayTimeType::PolarDay);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSet3dMode(JNIEnv * env, jclass, jboolean allow,
                                                                          jboolean allowBuildings)
{
  bool const allow3d = static_cast<bool>(allow);
  bool const allow3dBuildings = static_cast<bool>(allowBuildings);

  g_framework->Save3dMode(allow3d, allow3dBuildings);
  g_framework->Set3dMode(allow3d, allow3dBuildings);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetCarScreenMode(JNIEnv * env, jclass, jboolean enabled)
{
  if (g_framework)
    frm()->SetCarScreenMode(static_cast<bool>(enabled));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeGet3dMode(JNIEnv * env, jclass, jobject result)
{
  bool enabled;
  bool buildings;
  g_framework->Get3dMode(enabled, buildings);

  static jclass const resultClass = env->GetObjectClass(result);

  static jfieldID const enabledField = env->GetFieldID(resultClass, "enabled", "Z");
  env->SetBooleanField(result, enabledField, enabled);

  static jfieldID const buildingsField = env->GetFieldID(resultClass, "buildings", "Z");
  env->SetBooleanField(result, buildingsField, buildings);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetCustomMapDownloadUrl(JNIEnv * env, jclass,
                                                                                        jstring url)
{
  std::string nativeUrl = jni::ToNativeString(env, url);
  GetPlatform().SetCustomMapServerUrl(nativeUrl);

  if (g_framework)
    frm()->GetStorage().ResetMapDownloadMetaConfig();
  else
    LOG(LINFO, ("nativeSetCustomMapDownloadUrl: framework not created yet, skipping ResetMapDownloadMetaConfig"));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetAutoZoomEnabled(JNIEnv * env, jclass,
                                                                                   jboolean enabled)
{
  bool const autoZoomEnabled = static_cast<bool>(enabled);
  frm()->SaveAutoZoom(autoZoomEnabled);
  frm()->AllowAutoZoom(autoZoomEnabled);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetTransitSchemeEnabled(JNIEnv * env, jclass,
                                                                                        jboolean enabled)
{
  if (enabled)
  {
    frm()->SwitchToMapMode(MapMode::PublicTransport);
    frm()->PublicTransportMapModeSetTransitLines(true);
  }
  else
  {
    frm()->PublicTransportMapModeSetTransitLines(false);
    frm()->SwitchToMapMode(MapMode::Default);
  }
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsTransitSchemeEnabled(JNIEnv * env, jclass)
{
  return static_cast<jboolean>(frm()->PublicTransportMapModeHasTransitLines());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetIsolinesLayerEnabled(JNIEnv * env, jclass,
                                                                                        jboolean enabled)
{
  frm()->SetContourLinesLayer(static_cast<bool>(enabled));
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsIsolinesLayerEnabled(JNIEnv * env, jclass)
{
  return static_cast<jboolean>(frm()->HasContourLinesLayer());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetOutdoorsLayerEnabled(JNIEnv * env, jclass,
                                                                                        jboolean enabled)
{
  frm()->SetOutdoorLayer(static_cast<bool>(enabled));
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsOutdoorsLayerEnabled(JNIEnv * env, jclass)
{
  return static_cast<jboolean>(frm()->HasOutdoorLayer());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSwitchToUsingVehicleStyle(JNIEnv * env, jclass,
                                                                                          jboolean enabled)
{
  frm()->SwitchToUsingVehicleStyle(static_cast<bool>(enabled));
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsUsingVehicleStyle(JNIEnv * env, jclass)
{
  return static_cast<jboolean>(frm()->IsUsingVehicleStyle());
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeGetAutoZoomEnabled(JNIEnv *, jclass)
{
  return frm()->LoadAutoZoom();
}

// static void nativeZoomToPoint(double lat, double lon, int zoom, boolean animate);
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeZoomToPoint(JNIEnv * env, jclass, jdouble lat,
                                                                            jdouble lon, jint zoom, jboolean animate)
{
  g_framework->Scale(m2::PointD(mercator::FromLatLon(lat, lon)), zoom, animate);
}

JNIEXPORT jobject JNICALL Java_app_organicmaps_sdk_Framework_nativeDeleteBookmarkFromMapObject(JNIEnv * env, jclass)
{
  if (!frm()->HasPlacePageInfo())
    return nullptr;

  place_page::Info const & info = g_framework->GetPlacePageInfo();
  auto const bookmarkId = info.GetBookmarkId();
  frm()->GetBookmarkManager().GetEditSession().DeleteBookmark(bookmarkId);

  auto buildInfo = info.GetBuildInfo();
  buildInfo.m_match = place_page::BuildInfo::Match::FeatureOnly;
  buildInfo.m_userMarkId = kml::kInvalidMarkId;
  buildInfo.m_source = place_page::BuildInfo::Source::Other;
  frm()->UpdatePlacePageInfoForCurrentSelection(buildInfo);

  return usermark_helper::CreateMapObject(env, g_framework->GetPlacePageInfo());
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetPoiContactUrl(JNIEnv * env, jclass, jint id)
{
  auto const metaID = static_cast<osm::MapObject::MetadataID>(id);
  string_view const value = g_framework->GetPlacePageInfo().GetMetadata(metaID);
  if (osm::isSocialContactTag(metaID))
    return jni::ToJavaString(env, osm::socialContactToURL(metaID, value));
  return jni::ToJavaString(env, value);
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsDownloadedMapAtScreenCenter(JNIEnv *, jclass)
{
  ::Framework * fr = frm();
  return storage::IsPointCoveredByDownloadedMaps(fr->GetViewportCenter(), fr->GetStorage(), fr->GetCountryInfoGetter());
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_Framework_nativeGetActiveObjectFormattedCuisine(JNIEnv * env, jclass)
{
  ::Framework * frm = g_framework->NativeFramework();
  if (!frm->HasPlacePageInfo())
    return {};

  return jni::ToJavaString(env, g_framework->GetPlacePageInfo().FormatCuisines());
}

JNIEXPORT jobjectArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetActiveObjectChargeSockets(JNIEnv * env,
                                                                                                     jclass)
{
  auto sockets = g_framework->GetPlacePageInfo().GetChargeSockets();

  jclass descClass = env->FindClass("app/organicmaps/sdk/bookmarks/data/ChargeSocketDescriptor");
  jmethodID ctor = env->GetMethodID(descClass, "<init>", "(Ljava/lang/String;ID)V");

  // Create a Java array
  jobjectArray result = env->NewObjectArray(sockets.size(), descClass, nullptr);

  for (size_t i = 0; i < sockets.size(); ++i)
  {
    auto const & s = sockets[i];

    jstring jType = env->NewStringUTF(s.type.c_str());
    jobject jDesc = env->NewObject(descClass, ctor, jType, (jint)s.count, (jdouble)s.power);

    env->SetObjectArrayElement(result, i, jDesc);

    env->DeleteLocalRef(jType);
    env->DeleteLocalRef(jDesc);
  }

  return result;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetVisibleRect(JNIEnv * env, jclass, jint left,
                                                                               jint top, jint right, jint bottom)
{
  frm()->SetVisibleViewport(m2::RectD(left, top, right, bottom));
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeIsRouteFinished(JNIEnv * env, jclass)
{
  return frm()->GetRoutingManager().IsRouteFinished();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeRunFirstLaunchAnimation(JNIEnv * env, jclass)
{
  frm()->RunFirstLaunchAnimation();
}

JNIEXPORT jint JNICALL Java_app_organicmaps_sdk_Framework_nativeOpenRoutePointsTransaction(JNIEnv * env, jclass)
{
  return frm()->GetRoutingManager().OpenRoutePointsTransaction();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeApplyRoutePointsTransaction(JNIEnv * env, jclass,
                                                                                            jint transactionId)
{
  frm()->GetRoutingManager().ApplyRoutePointsTransaction(transactionId);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeCancelRoutePointsTransaction(JNIEnv * env, jclass,
                                                                                             jint transactionId)
{
  frm()->GetRoutingManager().CancelRoutePointsTransaction(transactionId);
}

JNIEXPORT jint JNICALL Java_app_organicmaps_sdk_Framework_nativeInvalidRoutePointsTransactionId(JNIEnv * env, jclass)
{
  return frm()->GetRoutingManager().InvalidRoutePointsTransactionId();
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeHasSavedRoutePoints(JNIEnv *, jclass)
{
  return frm()->GetRoutingManager().HasSavedRoutePoints();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeLoadRoutePoints(JNIEnv *, jclass)
{
  frm()->GetRoutingManager().LoadRoutePoints(g_loadRouteHandler);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSaveRoutePoints(JNIEnv *, jclass)
{
  frm()->GetRoutingManager().SaveRoutePoints();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeDeleteSavedRoutePoints(JNIEnv *, jclass)
{
  frm()->GetRoutingManager().DeleteSavedRoutePoints();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeShowFeature(JNIEnv * env, jclass, jobject featureId)
{
  auto const f = g_framework->BuildFeatureId(env, featureId);

  if (f.IsValid())
    frm()->ShowFeature(f);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeMakeCrash(JNIEnv * env, jclass type)
{
  CHECK(false, ("Diagnostic native crash!"));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetPowerManagerFacility(JNIEnv *, jclass,
                                                                                        jint facilityType,
                                                                                        jboolean state)
{
  frm()->GetPowerManager().SetFacility(static_cast<power_management::Facility>(facilityType), static_cast<bool>(state));
}

JNIEXPORT jint JNICALL Java_app_organicmaps_sdk_Framework_nativeGetPowerManagerScheme(JNIEnv *, jclass)
{
  return static_cast<jint>(frm()->GetPowerManager().GetScheme());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetPowerManagerScheme(JNIEnv *, jclass, jint schemeType)
{
  frm()->GetPowerManager().SetScheme(static_cast<power_management::Scheme>(schemeType));
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetViewportCenter(JNIEnv *, jclass, jdouble lat,
                                                                                  jdouble lon, jint zoom)
{
  // isAnim = true because of previous nativeSetChoosePositionMode animations.
  frm()->SetViewportCenter(mercator::FromLatLon(lat, lon), static_cast<int>(zoom), true /* isAnim */);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeStopLocationFollow(JNIEnv *, jclass)
{
  frm()->StopLocationFollow();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetSearchViewport(JNIEnv *, jclass, jdouble lat,
                                                                                  jdouble lon, jint zoom)
{
  auto const center = mercator::FromLatLon(static_cast<double>(lat), static_cast<double>(lon));
  auto const rect = df::GetRectForDrawScale(static_cast<int>(zoom), center);
  frm()->GetSearchAPI().OnViewportChanged(rect);
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeHasPlacePageInfo(JNIEnv *, jclass)
{
  return static_cast<jboolean>(frm()->HasPlacePageInfo());
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeMemoryWarning(JNIEnv *, jclass)
{
  return frm()->MemoryWarning();
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_Framework_nativeSetShowBookmarkLabels(JNIEnv *, jclass,
                                                                                       jboolean show)
{
  frm()->SetShowBookmarkLabels(show);
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_Framework_nativeGetShowBookmarkLabels(JNIEnv *, jclass)
{
  return Framework::GetShowBookmarkLabels();
}
}  // extern "C"
