package app.organicmaps.settings;

import androidx.annotation.Keep;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import app.organicmaps.R;
import app.organicmaps.transit.RigaLiveSettings;

/**
 * Online sources of live vehicle positions and times, one switch each. A source appears once the map it covers
 * is downloaded; static timetables are not listed here because they come with the map.
 */
@Keep
public class LiveFeedsSettingsFragment extends BaseXmlSettingsFragment
{
  @Override
  protected int getXmlResources()
  {
    return R.xml.prefs_live_feeds;
  }

  @Override
  public void onResume()
  {
    super.onResume();
    TwoStatePreference riga = getPreference("live_feed_riga");
    boolean available = RigaLiveSettings.available();
    riga.setVisible(available);
    getPreference("live_feeds_none").setVisible(!available);
    riga.setChecked(RigaLiveSettings.live(requireContext()));
    riga.setOnPreferenceChangeListener((Preference preference, Object value) -> {
      RigaLiveSettings.setLive(requireContext(), (Boolean) value);
      return true;
    });
  }
}
