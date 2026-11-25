package app.organicmaps.routing;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.*;
import app.organicmaps.transit.RigaStays;
import com.google.android.material.button.MaterialButton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

/** Stops of a public transport route where the rider stays: the chips, the steps, the picker and the toggle. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class TransitStaysTest
{
  private static final long T = 1_800_000_000_000L;

  private static TransitJourneyLeg stay(String name, long arrive, long leave)
  {
    return new TransitJourneyLeg(TransitStepType.INTERMEDIATE_POINT, "", name, 0xff777777, 0, false,
        new TransitJourneyLeg.Stop[] {new TransitJourneyLeg.Stop(name, arrive, arrive, 56.95, 24.12),
                                      new TransitJourneyLeg.Stop(name, leave, leave, 56.95, 24.12)});
  }

  private static TransitRouteInfo route(boolean trip)
  {
    TransitRouteInfo info = new TransitRouteInfo("", "", 3600, "0", "m", 0, new TransitStepInfo[0]);
    TransitJourneyLeg bus = new TransitJourneyLeg(TransitStepType.BUS, "13", "Centrs", 0xfff2b327, 0, false,
        new TransitJourneyLeg.Stop[] {new TransitJourneyLeg.Stop("A", T, T, 56.94, 24.10),
                                      new TransitJourneyLeg.Stop("B", T + 600000, T + 600000, 56.95, 24.12)});
    // Arrive 10 min in, leave 48 min in: a 30 min stay and the next ride 8 min after it.
    info.journeyLegs = new TransitJourneyLeg[] {bus, stay("Rimi", T + 600000, T + 48 * 60000)};
    info.trip = trip;
    return info;
  }

  private static FragmentActivity activity()
  {
    var controller = Robolectric.buildActivity(FragmentActivity.class);
    controller.get().setTheme(R.style.MwmTheme);
    return controller.setup().get();
  }

  private static ViewGroup render(FragmentActivity activity, TransitRouteInfo info)
  {
    androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(activity);
    android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    scroll.addView(root);
    activity.setContentView(scroll);
    new TransitItinerary(scroll, root, () -> {}).update(info, null);
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    return root;
  }

  private static void collect(View view, List<TextView> into)
  {
    if (view instanceof TextView text) into.add(text);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); ++i) collect(group.getChildAt(i), into);
  }

  private static TextView find(View root, String value)
  {
    List<TextView> all = new ArrayList<>();
    collect(root, all);
    for (TextView view : all) if (view.getText().toString().equals(value)) return view;
    return null;
  }

  private static String clock(long millis)
  {
    java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
    format.setTimeZone(TimeZone.getTimeZone("Europe/Riga"));
    return format.format(new java.util.Date(millis));
  }

  @Test
  public void plannedStayIsAStationWithItsNumberTimesAndAStayDropDown()
  {
    FragmentActivity activity = activity();
    ViewGroup root = render(activity, route(false));
    assertNotNull("numbered like the map marker and the chip", find(root, "1"));
    assertNotNull(find(root, "Rimi"));
    assertNotNull(find(root, "Arrive " + clock(T + 600000) + " · leave " + clock(T + 48 * 60000)));
    TextView stay = find(root, "30 min");
    assertTrue("the stay changes from its own drop-down", stay instanceof MaterialButton);
    assertEquals("Time at Rimi: 30 min. Change time", stay.getContentDescription().toString());
  }

  @Test
  public void duringATripTheStayIsReadOnly()
  {
    FragmentActivity activity = activity();
    ViewGroup root = render(activity, route(true));
    assertNull(find(root, "30 min"));
    assertNotNull(find(root, "Rimi · 30 min"));
  }

  @Test
  public void stayPickerOffersToRemoveTheStop()
  {
    FragmentActivity activity = activity();
    StayPicker.show(activity, route(false).journeyLegs[1]);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    androidx.appcompat.app.AlertDialog dialog =
        (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
    assertEquals("Remove stop", dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).getText().toString());
  }

  @Test
  public void closestStopIgnoresStartAndFinish()
  {
    RouteMarkData[] points = {
        new RouteMarkData("Start", "", RouteMarkType.Start, 0, false, false, false, 56.9501, 24.1201),
        new RouteMarkData("Rimi", "", RouteMarkType.Intermediate, 0, false, false, false, 56.95, 24.13),
        new RouteMarkData("Spice", "", RouteMarkType.Intermediate, 1, false, false, false, 56.90, 24.10),
        new RouteMarkData("End", "", RouteMarkType.Finish, 0, false, false, false, 56.80, 24.00)};
    assertEquals(0, StayPicker.closestStop(points, 56.9501, 24.1201).mIntermediateIndex);
    assertEquals(1, StayPicker.closestStop(points, 56.899, 24.10).mIntermediateIndex);
    assertNull(StayPicker.closestStop(new RouteMarkData[] {points[0], points[3]}, 56.95, 24.12));
  }

  @Test
  public void stopChipShowsItsStayAndOnlyStayChipsAreTappable()
  {
    FragmentActivity activity = activity();
    RigaStays.set(activity, 56.95, 24.12, 45 * 60000L);
    TransitStepView view = (TransitStepView) android.view.LayoutInflater.from(activity)
        .inflate(R.layout.routing_transit_step_view, null, false);
    view.setTransitStepInfo(new TransitStepInfo(TransitStepType.INTERMEDIATE_POINT.ordinal(), "", "", 45 * 60, "", 0, 0));
    assertEquals("Stop 1, 45 min there. Change time", view.getContentDescription().toString());

    TransitStepAdapter adapter = new TransitStepAdapter();
    List<TransitStepInfo> items = new ArrayList<>();
    items.add(new TransitStepInfo(TransitStepType.BUS.ordinal(), "", "", 600, "13", 0xfff2b327, -1));
    items.add(new TransitStepInfo(TransitStepType.INTERMEDIATE_POINT.ordinal(), "", "", 45 * 60, "", 0, 0));
    adapter.setItems(items);
    int[] tapped = {-1};
    adapter.setOnStayClick(position -> tapped[0] = position);
    androidx.recyclerview.widget.RecyclerView parent = new androidx.recyclerview.widget.RecyclerView(activity);
    parent.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity));
    TransitStepAdapter.TransitStepViewHolder bus = adapter.onCreateViewHolder(parent, 0);
    TransitStepAdapter.TransitStepViewHolder stop = adapter.onCreateViewHolder(parent, 0);
    adapter.onBindViewHolder(bus, 0);
    adapter.onBindViewHolder(stop, 1);
    assertFalse(bus.itemView.isClickable());
    assertTrue(stop.itemView.isClickable());
  }
}
