package app.organicmaps.routing;
import static org.junit.Assert.*;
import android.app.Application;
import android.os.Looper;
import android.view.*;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.*;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class,shadows=TransitItineraryTest.ShadowFramework.class)
public class TransitItineraryTest
{
 @Implements(Framework.class) public static class ShadowFramework {
  static double lat,lon;static int zoom;
  @Implementation protected static void nativeSetViewportCenter(double a,double b,int c){lat=a;lon=b;zoom=c;}
 }
 private static TextView find(View view,String value) {
  if(view instanceof TextView t && (t.getText().toString().equals(value)||t.getText().toString().startsWith(value+" · ")||(value.equals("B")&&t.getText().toString().endsWith("  B"))))return t;
  if(view instanceof ViewGroup g)for(int i=0;i<g.getChildCount();++i){TextView found=find(g.getChildAt(i),value);if(found!=null)return found;}
  return null;
 }
 private static TransitRouteInfo route() {
  TransitRouteInfo info=new TransitRouteInfo("","",600,"0","m",0,new TransitStepInfo[0]);
  info.journeyLegs=new TransitJourneyLeg[]{new TransitJourneyLeg(TransitStepType.BUS,"45","End",0xfff2b327,0,false,
   new TransitJourneyLeg.Stop[]{new TransitJourneyLeg.Stop("A",0,0,56.95,24.10),
    new TransitJourneyLeg.Stop("B",300000,300000,56.96,24.11),new TransitJourneyLeg.Stop("C",600000,600000,56.97,24.12)})};return info;
 }
 static int stopsTapped;
 /** The panel's steps, drawn as the open sheet draws them. */
 private static final class Shown { final FragmentActivity activity; final android.widget.LinearLayout root; final TransitItinerary itinerary;
  Shown(FragmentActivity a){activity=a;androidx.core.widget.NestedScrollView scroll=new androidx.core.widget.NestedScrollView(a);
   root=new android.widget.LinearLayout(a);root.setOrientation(android.widget.LinearLayout.VERTICAL);scroll.addView(root);
   a.setContentView(scroll);itinerary=new TransitItinerary(scroll,root,()->++stopsTapped);} }
 private static Shown show(TransitRouteInfo info) {
  var controller=Robolectric.buildActivity(FragmentActivity.class);controller.get().setTheme(R.style.MwmTheme);
  Shown shown=new Shown(controller.setup().get());shown.itinerary.update(info,null);
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));return shown;
 }
 private static void installMobilly() { install("lv.mobilly.android","Mobilly",true); }
 /** An installed app with its launcher label; {@code link}: it handles the rsat:// sticker link. */
 private static void install(String name,String label,boolean link) {
  android.content.pm.PackageInfo app=new android.content.pm.PackageInfo();app.packageName=name;
  app.applicationInfo=new android.content.pm.ApplicationInfo();app.applicationInfo.packageName=name;
  app.applicationInfo.nonLocalizedLabel=label;
  var packages=Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager());
  packages.installPackage(app);
  if(!link)return;
  android.content.ComponentName activity=new android.content.ComponentName(name,name+".Validate");
  packages.addActivityIfNotPresent(activity);
  android.content.IntentFilter filter=new android.content.IntentFilter(android.content.Intent.ACTION_VIEW);
  filter.addDataScheme("rsat");filter.addCategory(android.content.Intent.CATEGORY_DEFAULT);
  try{packages.addIntentFilterForActivity(activity,filter);}catch(Exception e){throw new AssertionError(e);}
 }
 private static android.content.Intent openedOnTap(Shown shown,String text) {
  find(shown.root,text).performClick();
  Shadows.shadowOf(Looper.getMainLooper()).idle();
  return Shadows.shadowOf(shown.activity).getNextStartedActivity();
 }
 @Test public void anAppHandlingTheStickerLinkIsOfferedWithoutBeingNamedInCoMaps() {
  install("org.example.tickets","City Tickets",true);
  TransitRouteInfo info=route();info.journeyLegs[0].vehicleNumber="17529";info.journeyLegs[0].ticketing="rigas-satiksme";
  ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",info);
  android.content.Intent opened=openedOnTap(show(RoutingController.get().getCachedTransitInfo()),"Validate in City Tickets");
  assertEquals("rsat://a/17529",opened.getDataString());
  assertEquals("org.example.tickets",opened.getPackage());
 }
 @Test public void withSeveralAppsTheFirstTapAsksThenTheLastUsedOpensAndALongPressAsksAgain() {
  installMobilly();install("com.ntacticsoftware.rigapublictransport","Timey",false);
  TransitRouteInfo info=route();info.journeyLegs[0].vehicleNumber="17529";info.journeyLegs[0].ticketing="rigas-satiksme";
  ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",info);
  Shown shown=show(RoutingController.get().getCachedTransitInfo());
  assertNull(openedOnTap(shown,"Validate in Mobilly"));
  android.app.Dialog dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();
  android.widget.ListView list=((androidx.appcompat.app.AlertDialog)dialog).getListView();
  assertEquals(2,list.getCount());
  list.performItemClick(list.getAdapter().getView(1,null,list),1,1);   // Timey
  Shadows.shadowOf(Looper.getMainLooper()).idle();
  Shadows.shadowOf(shown.activity).getNextStartedActivity();
  Shown again=show(RoutingController.get().getCachedTransitInfo());
  assertNotNull(find(again.root,"Validate in Timey"));          // remembered
  assertNull(find(again.root,"Validate in Mobilly"));
  find(again.root,"Validate in Timey").performLongClick();
  Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertEquals(2,((androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog()).getListView().getCount());
 }
 @Test public void timeyWithoutStickerLinkGetsTheFleetNumberOnTheClipboard() {
  install("com.ntacticsoftware.rigapublictransport","Timey",false);
  TransitRouteInfo info=route();info.journeyLegs[0].vehicleNumber="17529";info.journeyLegs[0].ticketing="rigas-satiksme";
  ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",info);
  Shown shown=show(RoutingController.get().getCachedTransitInfo());FragmentActivity activity=shown.activity;
  find(shown.root,"Validate in Timey").performClick();
  android.content.Intent opened=Shadows.shadowOf(activity).getNextStartedActivity();
  assertTrue(opened==null||!"rsat".equals(opened.getScheme()));
  android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
  assertEquals("17529",clipboard.getPrimaryClip().getItemAt(0).getText().toString());
 }
 @Test public void withoutMobillyTheFleetNumberIsCopiedInsteadOfAStoreLink() {
  TransitRouteInfo info=route();info.journeyLegs[0].vehicleNumber="17529";info.journeyLegs[0].ticketing="rigas-satiksme";
  ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",info);
  Shown shown=show(RoutingController.get().getCachedTransitInfo());FragmentActivity activity=shown.activity;
  assertNull(find(shown.root,"Validate in Mobilly"));
  TextView number=find(shown.root,"Vehicle 17529");
  assertNotNull(number);
  number.performClick();
  assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
  android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
  assertEquals("17529",clipboard.getPrimaryClip().getItemAt(0).getText().toString());
 }
 @Test public void liveVehicleOffersMobillyValidationWithItsFleetNumber() {
  installMobilly();
  TransitRouteInfo info=route();info.journeyLegs[0].vehicleNumber="17529";info.journeyLegs[0].ticketing="rigas-satiksme";
  ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",info);
  Shown shown=show(RoutingController.get().getCachedTransitInfo());FragmentActivity activity=shown.activity;
  find(shown.root,"Validate in Mobilly").performClick();
  android.content.Intent opened=Shadows.shadowOf(activity).getNextStartedActivity();
  assertEquals("rsat://a/17529",opened.getDataString());
  assertEquals("lv.mobilly.android",opened.getPackage());
 }
 @Test public void liveVehicleOutsideRigaHasNoValidationShortcut() {
  installMobilly();
  TransitRouteInfo info=route();info.journeyLegs[0].vehicleNumber="17529";
  ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",info);
  Shown shown=show(RoutingController.get().getCachedTransitInfo());
  assertNull(find(shown.root,"Validate in Mobilly"));
 }
 @Test public void scheduledRideHasNoValidationShortcut() {
  ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",route());
  Shown shown=show(RoutingController.get().getCachedTransitInfo());FragmentActivity activity=shown.activity;
  assertNull(find(shown.root,"Validate in Mobilly"));
 }
 @Test public void intermediateStopsRemainExpandedOnRefreshAndStopTapShowsItOnTheMap() {
  TransitRouteInfo first=route();ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",first);
  Shown shown=show(RoutingController.get().getCachedTransitInfo());FragmentActivity activity=shown.activity;
  View root=shown.root;assertNotNull(find(root,"Ride 2 stops"));
  find(root,"1 intermediate stop").performClick();
  assertEquals(View.VISIBLE,((View)find(root,"B").getParent()).getVisibility());
  first.description="Live status changed";shown.itinerary.update(first,null);
  assertNotNull(find(shown.root,"Hide stops"));
  TransitRouteInfo refreshed=route();ReflectionHelpers.setField(RoutingController.get(),"mCachedTransitRouteInfo",refreshed);
  shown.itinerary.update(refreshed,null);
  assertNotNull(find(shown.root,"Hide stops"));
  int tapped=stopsTapped;find(shown.root,"B").performClick();assertEquals(tapped+1,stopsTapped);
  assertEquals(56.96,ShadowFramework.lat,0);assertEquals(24.11,ShadowFramework.lon,0);assertEquals(17,ShadowFramework.zoom);
  assertSame(refreshed,RoutingController.get().getCachedTransitInfo());
 }
}
