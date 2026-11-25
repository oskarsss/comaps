package app.organicmaps.sdk.routing;

/** Structured instructions for a coordinate-based transport itinerary. */
public final class TransitJourneyLeg
{
  public static final class Stop
  {
    public final String name;
    public final long arrival,departure;
    public final double lat,lon;
    public Stop(String name,long arrival,long departure,double lat,double lon)
    { this.name=name;this.arrival=arrival;this.departure=departure;this.lat=lat;this.lon=lon; }
  }
  public final TransitStepType type;
  public final String number,headsign;
  public final int color,walkingMetres;
  public final boolean live;
  public final Stop[] stops;
  /** Localised live vehicle progress for this ride, e.g. "2 stops away · near X"; empty when unknown. */
  public String progress = "";
  /** Stops the matched vehicle still has to pass before the boarding stop; -1 when unknown. */
  public int vehicleStopsAway = -1;
  /** Fleet number of the matched live vehicle (the number on its ticket-validation sticker); empty when unknown. */
  public String vehicleNumber = "";
  /**
   * The ticketing this ride is validated through, given by the live feed that tracks it (validation needs the
   * vehicle's fleet number); empty when no live feed tracks the ride or live data is off.
   */
  public String ticketing = "";
  /** Lines making the same ride (same boarding and exit stop), this leg's own first; empty for walks. */
  public String[] lines = new String[0];
  /** Other departures of the same ride from the boarding stop (epoch ms) and the line of each. */
  public long[] also = new long[0];
  public String[] alsoLines = new String[0];
  /** How late (positive) or early the live estimate runs against the timetable, ms; the stop times include it. */
  public long delay;
  public TransitJourneyLeg(TransitStepType type,String number,String headsign,int color,int walkingMetres,
                           boolean live,Stop[] stops)
  { this.type=type;this.number=number;this.headsign=headsign;this.color=color;
    this.walkingMetres=walkingMetres;this.live=live;this.stops=stops.clone(); }
  public boolean isWalking() { return type==TransitStepType.PEDESTRIAN; }
  /** Time spent at a stop of a multi-stop journey: its two stops are the place, at arrival and at leaving. */
  public boolean isStay() { return type==TransitStepType.INTERMEDIATE_POINT; }
  /** A vehicle ride: neither a walk nor a stay. */
  public boolean isRide() { return !isWalking() && !isStay(); }
  public int stopCount() { return isRide()?Math.max(0,stops.length-1):0; }
}
