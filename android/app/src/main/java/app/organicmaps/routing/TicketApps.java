package app.organicmaps.routing;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.transit.RigaLiveSettings;
import app.organicmaps.transit.TransitTicketing;
import app.organicmaps.util.ThemeUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * A button that opens the rider's ticket app for a ride; CoMaps itself sells and validates nothing. The apps are
 * the operator's known ones plus any installed app that handles its vehicle link, so a new app needs no change
 * here. A tap opens the app used last; a long press offers all of them.
 */
final class TicketApps
{
  private static final String PREFERENCE = "ticket-app:";

  private TicketApps() {}

  /** Whether the live feed tracking this ride names its operator's ticket apps. */
  static boolean validates(@NonNull TransitJourneyLeg leg)
  {
    return leg.isRide() && TransitTicketing.of(leg.ticketing) != null;
  }

  /** The first ride ahead that is not yet over and has ticket apps; null when there is none. */
  @Nullable
  static TransitJourneyLeg nextKnownVehicle(@NonNull TransitJourneyLeg[] legs)
  {
    long now = System.currentTimeMillis();
    for (TransitJourneyLeg leg : legs)
      if (validates(leg) && (leg.stops.length == 0 || leg.stops[leg.stops.length - 1].arrival > now))
        return leg;
    return null;
  }

  /**
   * Binds the button for one ride. Without an installed app it shows only the fleet number (a tap copies it),
   * or hides itself when that is unknown too.
   * @return whether the button opens a ticket app.
   */
  static boolean style(@NonNull MaterialButton button, @NonNull TransitJourneyLeg ride)
  {
    Context context = button.getContext();
    button.setAllCaps(false);
    TransitTicketing ticketing = TransitTicketing.of(ride.ticketing);
    String vehicle = ride.vehicleNumber;
    List<String> apps = ticketing == null ? new ArrayList<>() : installed(context, ticketing);
    if (apps.isEmpty())
    {
      if (vehicle.isEmpty())
      {
        button.setVisibility(View.GONE);
        return false;
      }
      button.setVisibility(View.VISIBLE);
      button.setText(context.getString(R.string.riga_vehicle_number, vehicle));
      button.setIcon(null);
      button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
      button.setTextColor(ThemeUtils.getColor(context, android.R.attr.textColorSecondary));
      button.setPadding(0, button.getPaddingTop(), 0, button.getPaddingBottom());
      button.setOnClickListener(v -> copy(context, vehicle, false));
      button.setOnLongClickListener(null);
      return false;
    }
    button.setVisibility(View.VISIBLE);
    String preferred = apps.get(0);
    // Asked once when several apps could take the ride; afterwards a tap goes straight to the last one used.
    boolean ask = apps.size() > 1 && !apps.get(0).equals(remembered(context, ticketing));
    button.setText(vehicle.isEmpty() ? context.getString(R.string.riga_validate_in_app, label(context, preferred))
                                     : context.getString(R.string.riga_validate_in, label(context, preferred), vehicle));
    decorate(button, icon(context, preferred));
    button.setOnClickListener(v -> {
      if (ask)
        choose(context, ticketing, vehicle, apps);
      else
        open(context, ticketing, vehicle, preferred);
    });
    button.setOnLongClickListener(v -> {
      choose(context, ticketing, vehicle, apps);
      return true;
    });
    return true;
  }

  /** The operator's apps on this phone, the one used last first, then the known ones in order, then the others. */
  private static List<String> installed(Context context, TransitTicketing ticketing)
  {
    PackageManager packages = context.getPackageManager();
    List<String> apps = new ArrayList<>();
    for (String pkg : ticketing.knownApps())
      if (isInstalled(packages, pkg))
        apps.add(pkg);
    // Any number stands in for the vehicle: only which apps handle the link matters.
    String probe = ticketing.vehicleLink("0");
    if (probe != null)
      for (ResolveInfo handler : packages.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse(probe)), 0))
        if (!apps.contains(handler.activityInfo.packageName))
          apps.add(handler.activityInfo.packageName);
    String last = remembered(context, ticketing);
    if (apps.remove(last))
      apps.add(0, last);
    return apps;
  }

  /**
   * Opens {@code pkg}: through the vehicle link when the vehicle is known and the app handles it (the app then
   * asks the rider to confirm), else at its start screen with the fleet number on the clipboard.
   */
  private static void open(Context context, TransitTicketing ticketing, String vehicle, String pkg)
  {
    preferences(context).edit().putString(PREFERENCE + ticketing.id(), pkg).apply();
    String link = ticketing.vehicleLink(vehicle);
    if (link != null && handles(context, link, pkg))
    {
      try
      {
        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg));
        return;
      }
      catch (ActivityNotFoundException ignored)
      {
        // This app does not take the link: it opens normally instead.
      }
    }
    if (!vehicle.isEmpty())
      copy(context, vehicle, true);
    Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
    if (launch != null)
      context.startActivity(launch);
  }

  private static void choose(Context context, TransitTicketing ticketing, String vehicle, List<String> apps)
  {
    float density = context.getResources().getDisplayMetrics().density;
    ArrayAdapter<String> adapter =
        new ArrayAdapter<String>(context, android.R.layout.select_dialog_item, android.R.id.text1, apps) {
          @NonNull
          @Override
          public View getView(int position, @Nullable View convert, @NonNull ViewGroup parent)
          {
            TextView view = (TextView) super.getView(position, convert, parent);
            String pkg = getItem(position);
            view.setText(label(context, pkg));
            Drawable icon = icon(context, pkg);
            if (icon != null)
              icon.setBounds(0, 0, Math.round(32 * density), Math.round(32 * density));
            view.setCompoundDrawables(icon, null, null, null);
            view.setCompoundDrawablePadding(Math.round(16 * density));
            return view;
          }
        };
    MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(context);
    if (!vehicle.isEmpty())
      dialog.setTitle(context.getString(R.string.riga_validate_choose, vehicle));
    dialog.setAdapter(adapter, (d, which) -> open(context, ticketing, vehicle, apps.get(which))).show();
  }

  private static void decorate(MaterialButton button, @Nullable Drawable icon)
  {
    float density = button.getResources().getDisplayMetrics().density;
    if (icon == null)
      icon = ContextCompat.getDrawable(button.getContext(), R.drawable.ic_riga_ticket);
    button.setIconTint(null);
    button.setIcon(icon);
    button.setIconSize(Math.round(24 * density));
    button.setIconPadding(Math.round(10 * density));
    button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
    button.setClickable(true);
  }

  private static String label(Context context, String pkg)
  {
    PackageManager packages = context.getPackageManager();
    try
    {
      ApplicationInfo info = packages.getApplicationInfo(pkg, 0);
      return packages.getApplicationLabel(info).toString();
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return pkg;
    }
  }

  @Nullable
  private static Drawable icon(Context context, String pkg)
  {
    try
    {
      return context.getPackageManager().getApplicationIcon(pkg);
    }
    catch (PackageManager.NameNotFoundException | RuntimeException e)
    {
      return null;
    }
  }

  private static boolean handles(Context context, String link, String pkg)
  {
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg);
    return !context.getPackageManager().queryIntentActivities(intent, 0).isEmpty();
  }

  private static boolean isInstalled(PackageManager packages, String pkg)
  {
    try
    {
      packages.getApplicationInfo(pkg, 0);
      return true;
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return false;
    }
  }

  private static void copy(Context context, String vehicle, boolean toast)
  {
    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText(vehicle, vehicle));
    // Android 13+ shows its own clipboard confirmation.
    if (toast || Build.VERSION.SDK_INT < 33)
      Toast.makeText(context, context.getString(R.string.riga_vehicle_copied, vehicle), Toast.LENGTH_SHORT).show();
  }

  private static String remembered(Context context, TransitTicketing ticketing)
  {
    SharedPreferences preferences = preferences(context);
    // Earlier versions kept one choice for Riga under this key.
    return preferences.getString(PREFERENCE + ticketing.id(), preferences.getString("riga-ticket-app", ""));
  }

  private static SharedPreferences preferences(Context context)
  {
    return RigaLiveSettings.preferences(context);
  }
}
