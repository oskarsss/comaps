package app.organicmaps.transit;

import android.content.Context;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitStepType;

/** Shared mode palette from the user's Riga reference, used for chips, lines and vehicle badges. */
public final class RigaModeStyle
{
  static final int TRAM = 0, TRAIN = 2, BUS = 3, TROLLEYBUS = 11;

  /** Folds basic and extended GTFS route types into the four modes the app shows. */
  static int base(int gtfsType)
  {
    if (gtfsType == TRAM || gtfsType == 5 || gtfsType == 6 || (gtfsType >= 900 && gtfsType < 1000)) return TRAM;
    if (gtfsType == TROLLEYBUS || gtfsType == 800) return TROLLEYBUS;
    if (gtfsType == 1 || gtfsType == TRAIN || gtfsType == 12 || (gtfsType >= 100 && gtfsType < 200)
        || (gtfsType >= 400 && gtfsType < 500)) return TRAIN;
    return BUS;
  }
  static int color(int gtfsType)
  {
    switch (base(gtfsType))
    {
    case TRAM: return 0xfff2000b;
    case TROLLEYBUS: return 0xff009de0;
    case TRAIN: return 0xff1b8a3c;
    default: return 0xfff2b327;
    }
  }
  static TransitStepType type(int gtfsType)
  {
    switch (base(gtfsType))
    {
    case TRAM: return TransitStepType.TRAM;
    case TROLLEYBUS: return TransitStepType.TROLLEYBUS;
    case TRAIN: return TransitStepType.TRAIN;
    default: return TransitStepType.BUS;
    }
  }
  static String name(Context context, int gtfsType)
  {
    return name(context, type(gtfsType));
  }
  public static String name(Context context, TransitStepType type)
  {
    return context.getString(type == TransitStepType.TRAM ? R.string.riga_tram
        : type == TransitStepType.TROLLEYBUS ? R.string.riga_trolleybus
        : type == TransitStepType.TRAIN ? R.string.riga_train : R.string.riga_bus);
  }
  static int vehicleType(int type) { return type == 1 ? 11 : type == 3 ? 0 : 3; }
}
