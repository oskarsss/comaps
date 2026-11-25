package app.organicmaps.transit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * How riders of one operator reach its ticket apps. CoMaps only opens those apps: it sells, holds and validates
 * nothing itself. Part of a live feed's data, since validation needs the fleet number only live data gives; another
 * operator needs a new entry, not new code.
 */
public final class TransitTicketing
{
  private static final String VEHICLE = "{vehicle}";

  private final String mId;
  /** Opens validation for one vehicle in any app that handles it, e.g. rsat://a/{vehicle}; empty for none. */
  private final String mVehicleLink;
  /** Apps known to sell this operator's tickets, in order of preference, whether or not they handle the link. */
  private final List<String> mApps;

  TransitTicketing(@NonNull String id, @NonNull String vehicleLink, @NonNull String... apps)
  {
    mId = id;
    mVehicleLink = vehicleLink;
    mApps = Collections.unmodifiableList(Arrays.asList(apps));
  }

  /** The ticketing named by a ride (its {@code ticketing}), or null when it names none. */
  @Nullable
  public static TransitTicketing of(@NonNull String id)
  {
    return RigaLiveSettings.TICKETING.mId.equals(id) ? RigaLiveSettings.TICKETING : null;
  }

  /** The link for one vehicle, or null when the operator has none or the vehicle is unknown. */
  @Nullable
  public String vehicleLink(@NonNull String vehicle)
  {
    return mVehicleLink.isEmpty() || vehicle.isEmpty() ? null : mVehicleLink.replace(VEHICLE, vehicle);
  }

  @NonNull
  public List<String> knownApps()
  {
    return mApps;
  }

  /** Identifies the operator, e.g. to remember the rider's app per operator. */
  @NonNull
  public String id()
  {
    return mId;
  }
}
