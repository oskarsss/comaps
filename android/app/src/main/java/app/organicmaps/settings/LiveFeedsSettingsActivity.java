package app.organicmaps.settings;

import androidx.fragment.app.Fragment;
import app.organicmaps.R;

/** Opens Settings directly at Live feeds, e.g. from the public transport route panel. */
public class LiveFeedsSettingsActivity extends SettingsActivity
{
  @Override
  protected int getToolbarTitle()
  {
    return R.string.live_feeds;
  }

  @Override
  protected Class<? extends Fragment> getFragmentClass()
  {
    return LiveFeedsSettingsFragment.class;
  }
}
