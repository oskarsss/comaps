package app.organicmaps.routing;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import com.google.android.material.button.MaterialButton;

/**
 * Opens a ticket app's own validation for a vehicle: Mobilly, the official Rīgas satiksme app or Timey.
 * The in-vehicle QR sticker carries rsat://a/&lt;fleet number&gt;; an app that handles it gets the link (and
 * asks the rider to confirm), so this only saves scanning the sticker. An app that does not handle it is
 * opened with the fleet number on the clipboard, ready to paste into its "enter vehicle number" field.
 */
final class MobillyShortcut
{
  static final String PACKAGE = "lv.mobilly.android";
  /** In order of preference when the rider has not chosen yet. */
  static final String[] PACKAGES = {PACKAGE, "com.flutter.rspassenger", "com.ntacticsoftware.rigapublictransport"};
  private static final String[] NAMES = {"Mobilly", "Rīgas satiksme", "Timey"};
  private static final String PREFERENCE = "riga-ticket-app";

  private MobillyShortcut() {}

  static Intent intent(String vehicle, String pkg)
  {
    return new Intent(Intent.ACTION_VIEW, Uri.parse("rsat://a/" + vehicle)).setPackage(pkg);
  }

  static Intent intent(String vehicle) { return intent(vehicle, PACKAGE); }

  /** Mobilly is known to handle the sticker link; the others are asked. */
  private static boolean handlesLink(Context context, String pkg)
  {
    return PACKAGE.equals(pkg) || !context.getPackageManager().queryIntentActivities(intent("0", pkg), 0).isEmpty();
  }

  static void open(@NonNull Context context, @NonNull String vehicle, @NonNull String pkg)
  {
    android.content.SharedPreferences preferences = app.organicmaps.transit.RigaLiveSettings.preferences(context);
    preferences.edit().putString(PREFERENCE, pkg).apply();
    if (handlesLink(context, pkg))
    {
      try { context.startActivity(intent(vehicle, pkg)); return; }
      catch (ActivityNotFoundException ignored) {}
    }
    copy(context, vehicle, true);
    Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
    if (launch != null) context.startActivity(launch);
  }

  static void open(@NonNull Context context, @NonNull String vehicle) { open(context, vehicle, PACKAGE); }

  private static void copy(Context context, String vehicle, boolean toast)
  {
    android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(vehicle, vehicle));
    // Android 13+ shows its own clipboard confirmation.
    if (toast || android.os.Build.VERSION.SDK_INT < 33)
      android.widget.Toast.makeText(context, context.getString(R.string.riga_vehicle_copied, vehicle),
          android.widget.Toast.LENGTH_SHORT).show();
  }

  /** Installed ticket apps, the rider's last choice first. */
  static java.util.List<String> installedApps(@NonNull Context context)
  {
    java.util.List<String> apps = new java.util.ArrayList<>();
    for (String pkg : PACKAGES) if (installed(context, pkg)) apps.add(pkg);
    String last = app.organicmaps.transit.RigaLiveSettings.preferences(context).getString(PREFERENCE, "");
    if (apps.remove(last)) apps.add(0, last);
    return apps;
  }

  private static String label(Context context, String pkg)
  {
    for (int i = 0; i < PACKAGES.length; ++i) if (PACKAGES[i].equals(pkg)) return NAMES[i];
    return pkg;
  }

  @Nullable
  private static Drawable icon(Context context, String pkg)
  {
    try { return context.getPackageManager().getApplicationIcon(pkg); }
    catch (PackageManager.NameNotFoundException | RuntimeException e) { return null; }
  }

  /**
   * A ride validated in the ticket apps: Rīgas satiksme lines only. A vehicle number alone (another operator's
   * live feed) does not make a ride one: its ticket is not validated with the rsat:// sticker.
   */
  static boolean validates(@NonNull TransitJourneyLeg leg)
  {
    return leg.isRide() && leg.validatable;
  }

  /**
   * The ride to validate next: the first not yet completed Rīgas satiksme ride.
   * Null when no ride ahead can be validated.
   */
  @Nullable
  static TransitJourneyLeg nextKnownVehicle(@NonNull TransitJourneyLeg[] legs)
  {
    long now = System.currentTimeMillis();
    for (TransitJourneyLeg leg : legs)
      if (validates(leg)
          && (leg.stops.length == 0 || leg.stops[leg.stops.length - 1].arrival > now))
        return leg;
    return null;
  }

  static boolean installed(@NonNull Context context) { return !installedApps(context).isEmpty(); }

  static boolean installed(@NonNull Context context, @NonNull String pkg)
  {
    try { context.getPackageManager().getApplicationInfo(pkg, 0); return true; }
    catch (PackageManager.NameNotFoundException e) { return false; }
  }

  /**
   * With Mobilly installed: a button with its launcher icon (so riders recognise it) that opens validation.
   * Without it: just the vehicle's fleet number as plain text, no store link.
   * @return whether the button is the Mobilly shortcut.
   */
  static boolean style(@NonNull MaterialButton button, @NonNull String vehicle)
  {
    Context context = button.getContext();
    float density = context.getResources().getDisplayMetrics().density;
    button.setAllCaps(false);
    java.util.List<String> apps = installedApps(context);
    if (vehicle.isEmpty())
    {
      // Not matched to a live vehicle yet: the app opens and the rider scans the QR sticker by the door.
      if (apps.isEmpty()) { button.setVisibility(android.view.View.GONE); return false; }
      button.setVisibility(android.view.View.VISIBLE);
      String preferred = apps.get(0);
      Drawable icon = icon(context, preferred);
      if (icon == null) icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_riga_ticket);
      button.setText(context.getString(R.string.riga_validate_in_app, label(context, preferred)));
      decorate(button, icon, density);
      button.setOnClickListener(v -> {
        if (apps.size() == 1) { launch(context, preferred); return; }
        chooseApp(context, apps);
      });
      button.setOnLongClickListener(v -> { chooseApp(context, apps); return true; });
      return true;
    }
    if (apps.isEmpty())
    {
      // Plain fleet number; tapping copies it, e.g. to paste into another ticket app.
      button.setText(context.getString(R.string.riga_vehicle_number, vehicle));
      button.setIcon(null);
      button.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
      button.setTextColor(app.organicmaps.util.ThemeUtils.getColor(context, android.R.attr.textColorSecondary));
      button.setPadding(0, button.getPaddingTop(), 0, button.getPaddingBottom());
      button.setOnClickListener(v -> copy(context, vehicle, false));
      return false;
    }
    String preferred = apps.get(0);
    Drawable icon = icon(context, preferred);
    if (icon == null) icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_riga_ticket);
    button.setText(context.getString(R.string.riga_validate_in, label(context, preferred), vehicle));
    decorate(button, icon, density);
    button.setOnClickListener(v -> {
      if (apps.size() == 1) { open(context, vehicle, preferred); return; }
      choose(context, vehicle, apps);
    });
    // A long press always offers the other installed apps.
    button.setOnLongClickListener(v -> { choose(context, vehicle, apps); return true; });
    return true;
  }

  private static void decorate(MaterialButton button, @Nullable Drawable icon, float density)
  {
    button.setIconTint(null);
    button.setIcon(icon);
    button.setIconSize(Math.round(24 * density));
    button.setIconPadding(Math.round(10 * density));
    button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
    button.setClickable(true);
  }

  private static void launch(Context context, String pkg)
  {
    app.organicmaps.transit.RigaLiveSettings.preferences(context).edit().putString(PREFERENCE, pkg).apply();
    Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
    if (launch != null) context.startActivity(launch);
  }

  private static void chooseApp(Context context, java.util.List<String> apps)
  {
    String[] names = new String[apps.size()];
    for (int i = 0; i < names.length; ++i) names[i] = label(context, apps.get(i));
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setItems(names, (dialog, which) -> launch(context, apps.get(which)))
        .show();
  }

  private static void choose(Context context, String vehicle, java.util.List<String> apps)
  {
    float density = context.getResources().getDisplayMetrics().density;
    android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(context,
        android.R.layout.select_dialog_item, android.R.id.text1, apps)
    {
      @NonNull @Override
      public android.view.View getView(int position, @Nullable android.view.View convert, @NonNull android.view.ViewGroup parent)
      {
        android.widget.TextView view = (android.widget.TextView) super.getView(position, convert, parent);
        String pkg = getItem(position);
        view.setText(label(context, pkg));
        Drawable icon = icon(context, pkg);
        if (icon != null) icon.setBounds(0, 0, Math.round(32 * density), Math.round(32 * density));
        view.setCompoundDrawables(icon, null, null, null);
        view.setCompoundDrawablePadding(Math.round(16 * density));
        return view;
      }
    };
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.riga_validate_choose, vehicle))
        .setAdapter(adapter, (dialog, which) -> open(context, vehicle, apps.get(which)))
        .show();
  }
}
