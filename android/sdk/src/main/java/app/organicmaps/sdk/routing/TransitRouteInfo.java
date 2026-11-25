package app.organicmaps.sdk.routing;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents TransitRouteInfo from core.
 */
// Used by JNI.
@Keep
@SuppressWarnings("unused")
public final class TransitRouteInfo
{
  @NonNull
  private final String mTotalDistance;
  @NonNull
  private final String mTotalDistanceUnits;
  private final int mTotalTimeInSec;
  @NonNull
  private final String mTotalPedestrianDistance;
  @NonNull
  private final String mTotalPedestrianDistanceUnits;
  private final int mTotalPedestrianTimeInSec;
  @NonNull
  private final TransitStepInfo[] mSteps;

  public TransitRouteInfo(@NonNull String totalDistance, @NonNull String totalDistanceUnits, int totalTimeInSec,
                           @NonNull String totalPedestrianDistance, @NonNull String totalPedestrianDistanceUnits,
                           int totalPedestrianTimeInSec, @NonNull TransitStepInfo[] steps)
  {
    mTotalDistance = totalDistance;
    mTotalDistanceUnits = totalDistanceUnits;
    mTotalTimeInSec = totalTimeInSec;
    mTotalPedestrianDistance = totalPedestrianDistance;
    mTotalPedestrianDistanceUnits = totalPedestrianDistanceUnits;
    mTotalPedestrianTimeInSec = totalPedestrianTimeInSec;
    mSteps = steps;
  }

  // Optional coordinate-provider instructions; native transit keeps its existing presentation.
  @NonNull public String description = "";
  @NonNull public String instructions = "";
  public TransitJourneyLeg[] journeyLegs = new TransitJourneyLeg[0];
  public String journeyStatus = "";
  public String timetableValidity = "";
  public long firstDeparture;
  /** When the shown journey reaches the destination (epoch ms), or 0. */
  public long arrival;
  public String actionLabel;
  public Runnable action;
  public boolean liveConsent;
  /** Journeys the rider can switch between; empty while they are still being found. */
  public TransitAlternative[] alternatives = new TransitAlternative[0];
  public int selectedAlternative;
  public boolean alternativesPending;
  public java.util.function.IntConsumer selectAlternative;
  public Runnable laterAlternatives;
  /** When the rider sets off: the first leg's departure, or the first ride's without legs. */
  public long leaves()
  {
    return journeyLegs.length > 0 && journeyLegs[0].stops.length > 0 ? journeyLegs[0].stops[0].departure
                                                                     : firstDeparture;
  }

  /** Starts guidance along this journey; null when it cannot be followed. */
  public Runnable startTrip;
  /** True while the rider follows this journey (it is re-timed, never replaced). */
  public boolean trip;
  public Runnable endTrip;
  public Runnable replanFromHere;

  @NonNull
  public String getTotalPedestrianDistance()
  {
    return mTotalPedestrianDistance;
  }

  public int getTotalPedestrianTimeInSec()
  {
    return mTotalPedestrianTimeInSec;
  }

  @NonNull
  public String getTotalPedestrianDistanceUnits()
  {
    return mTotalPedestrianDistanceUnits;
  }

  public int getTotalTime()
  {
    return mTotalTimeInSec;
  }

  @NonNull
  public List<TransitStepInfo> getTransitSteps()
  {
    return new ArrayList<>(Arrays.asList(mSteps));
  }
}
