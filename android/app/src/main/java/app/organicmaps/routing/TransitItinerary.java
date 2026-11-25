package app.organicmaps.routing;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.NestedScrollView;
import app.organicmaps.R;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import java.text.*;
import java.util.*;

/**
 * The timed itinerary of the shown journey, drawn into the route panel's expandable part: every leg with its
 * stops and times; during a trip, legs behind the rider collapsed and faded and the current step marked "Now".
 */
final class TransitItinerary
{
  private final Context mContext;
  private final NestedScrollView scroll;
  private final LinearLayout content;
  private final Runnable mOnStop;
  private final Set<Integer> expanded=new HashSet<>();
  private final SimpleDateFormat clock=app.organicmaps.transit.RigaDelayText.clock();
  private TransitRouteInfo shown;
  private String shownDescription="";

  /** @param onStop run after a stop is tapped and the map moved to it (the sheet collapses to show the map). */
  TransitItinerary(@NonNull NestedScrollView scroll,@NonNull LinearLayout content,@NonNull Runnable onStop)
  {
    mContext=scroll.getContext();this.scroll=scroll;this.content=content;mOnStop=onStop;
  }

  /** Draws {@code info} unless it is already shown for the same step of the trip. */
  void update(@NonNull TransitRouteInfo info,@Nullable app.organicmaps.transit.RigaTripPanel panel)
  {
    if(info==shown&&info.description.equals(shownDescription)&&panelKey(panel).equals(shownKey))return;
    render(info,panel);
  }

  /** The next render scrolls to the current step again (the sheet was just opened). */
  void rewind(){scrolledToNow=false;shown=null;}

  private int dp(int value) { return Math.round(value*mContext.getResources().getDisplayMetrics().density); }
  private int primaryText() { return ThemeUtils.getColor(mContext,android.R.attr.textColorPrimary); }
  private int secondaryText() { return ThemeUtils.getColor(mContext,android.R.attr.textColorSecondary); }
  private int accent() { return ThemeUtils.getColor(mContext,androidx.appcompat.R.attr.colorPrimary); }
  private MaterialTextView text(String value,int size,boolean bold)
  {
    MaterialTextView v=new MaterialTextView(mContext);v.setText(value);v.setTextSize(size);
    v.setTextColor(bold?primaryText():secondaryText());
    if(bold)v.setTypeface(v.getTypeface(),Typeface.BOLD);
    v.setFontFeatureSettings("tnum");v.setPadding(0,dp(2),0,dp(2));return v;
  }
  private MaterialButton button(String value)
  {
    MaterialButton v=new MaterialButton(mContext);
    v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
    v.setTextColor(accent());v.setElevation(0);v.setStateListAnimator(null);
    v.setInsetTop(0);v.setInsetBottom(0);v.setMinHeight(dp(40));v.setMinimumHeight(dp(40));
    v.setText(value);v.setAllCaps(false);v.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);v.setFontFeatureSettings("tnum");
    v.setPadding(0,0,dp(8),0);return v;
  }
  private LinearLayout column()
  {
    LinearLayout v=new LinearLayout(mContext);v.setOrientation(LinearLayout.VERTICAL);return v;
  }
  private String time(long millis){return clock.format(new Date(millis));}
  private void stop(LinearLayout parent,TransitJourneyLeg.Stop stop,String label,long instant,long delay)
  { stop(parent,stop,label,instant,delay,false); }
  /** "21:59 <s>21:52</s>   Label": the live time in bold, the timetable one struck through when the delay moves it. */
  private void stop(LinearLayout parent,TransitJourneyLeg.Stop stop,String label,long instant,long delay,boolean intermediate)
  {
    int size=intermediate?14:16;
    android.text.SpannableString bold=new android.text.SpannableString(time(instant));
    bold.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,bold.length(),0);
    android.text.SpannableStringBuilder value=new android.text.SpannableStringBuilder(
        app.organicmaps.transit.RigaDelayText.mark(bold,instant,delay,clock));
    value.append("   ").append(label);
    MaterialButton row=button("");row.setText(value);row.setTextSize(size);
    row.setTextColor(intermediate?secondaryText():primaryText());row.setMinHeight(dp(size>=16?40:34));
    row.setMinimumHeight(dp(size>=16?40:34));
    row.setContentDescription(time(instant)+" "+label+". "+mContext.getString(R.string.search_show_on_map));
    row.setOnClickListener(v->{Framework.nativeSetViewportCenter(stop.lat,stop.lon,17);mOnStop.run();});
    parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
  }
  private void render(TransitRouteInfo info,app.organicmaps.transit.RigaTripPanel panel)
  {
    int position=scroll.getScrollY();shown=info;shownDescription=info.description;content.removeAllViews();
    if(info.journeyLegs.length==0)
    {
      // A route from a provider without timed legs: its written instructions are the steps.
      MaterialTextView steps=text(info.instructions,15,false);steps.setTextColor(primaryText());
      steps.setLineSpacing(dp(2),1);content.addView(steps);shownKey=panelKey(panel);return;
    }
    // The panel keeps the total time and line chips above and the status lines below; the sheet carries the steps.
    content.addView(new Space(mContext),new LinearLayout.LayoutParams(1,dp(8)));
    View current=null;
    boolean waitShown=false;
    int stays=0;
    for(int index=0;index<info.journeyLegs.length;++index)
    {
      TransitJourneyLeg leg=info.journeyLegs[index];if(leg.stops.length<2)continue;
      TransitJourneyLeg.Stop first=leg.stops[0],last=leg.stops[leg.stops.length-1];
      boolean done=panel!=null&&index<panel.done;
      boolean now=panel!=null&&index==panel.now.leg;
      // The planned wait before a ride the rider is waiting for now is stale: the panel counts it down live.
      boolean waitingNow=now&&panel.now.kind==app.organicmaps.transit.RigaTripPanel.Kind.BOARD;
      if(index>0&&!waitShown&&!done&&!waitingNow){TransitJourneyLeg previous=info.journeyLegs[index-1];
        long wait=first.departure-previous.stops[previous.stops.length-1].arrival;
        if(wait>=60000){MaterialTextView waiting=text(mContext.getString(R.string.riga_wait,(wait+59999)/60000),14,false);
          waiting.setPadding(dp(24),dp(4),0,dp(8));content.addView(waiting);}}
      waitShown=false;
      if(done)
      {
        // Behind the rider: one quiet line, so the sheet opens on what is still to do.
        content.addView(doneRow(leg));continue;
      }
      if(leg.isStay())
      {
        View row=stayRow(leg,++stays,now,!info.trip);
        if(now){highlight(row);current=row;}
        content.addView(row);waitShown=true;continue;
      }
      if(app.organicmaps.transit.RigaWalks.shortChange(info.journeyLegs,index))
      {
        // A change of vehicle on foot: one line, with the wait for the next ride folded in (as Google shows it).
        TransitJourneyLeg next=info.journeyLegs[index+1];
        long wait=next.stops[0].departure-last.arrival;
        int walked=(int)Math.max(1,(last.arrival-first.departure+59999)/60000);
        String change=wait>=60000?mContext.getString(R.string.riga_route_change_wait,walked,leg.walkingMetres,(int)((wait+59999)/60000))
            :mContext.getString(R.string.riga_route_change,walked,leg.walkingMetres);
        LinearLayout row=new LinearLayout(mContext);row.setGravity(Gravity.CENTER_VERTICAL);
        View line=new View(mContext);line.setBackground(new Timeline(leg.color));
        LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(dp(16),dp(36));lineParams.setMargins(dp(3),0,dp(5),0);
        row.addView(line,lineParams);
        MaterialTextView label=text(change,15,false);label.setPadding(0,dp(6),0,dp(14));
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        if(now){highlight(row);current=row;}
        content.addView(row);waitShown=true;continue;
      }
      LinearLayout row=new LinearLayout(mContext);row.setBaselineAligned(false);
      View line=new View(mContext);line.setBackground(new Timeline(leg.color));
      LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(dp(16),-1);lineParams.setMargins(dp(3),dp(5),dp(5),dp(12));
      row.addView(line,lineParams);LinearLayout body=column();body.setPadding(0,0,0,dp(20));
      row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
      int minutes=(int)Math.max(1,(last.arrival-first.departure+59999)/60000);
      if(now)body.addView(nowPill());
      if(leg.isWalking())
      {
        body.addView(text(mContext.getString(R.string.riga_route_walk,minutes,leg.walkingMetres),16,true));
        stop(body,first,first.name,first.departure,0);stop(body,last,last.name,last.arrival,0);
      }
      else
      {
        MaterialTextView badge=text(app.organicmaps.transit.RigaModeStyle.name(mContext,leg.type)+" "+leg.number,18,true);
        badge.setGravity(Gravity.CENTER);badge.setPadding(dp(12),dp(4),dp(12),dp(4));
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(leg.color);background.setCornerRadius(dp(5));badge.setBackground(background);
        badge.setTextColor(ColorUtils.calculateLuminance(leg.color)>0.4?0xff1a1a1a:Color.WHITE);
        LinearLayout header=new LinearLayout(mContext);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(badge,new LinearLayout.LayoutParams(-2,-2));
        MaterialTextView source=text(mContext.getString(leg.live?R.string.riga_route_live_estimate:R.string.riga_route_scheduled),13,false);
        source.setPadding(dp(10),0,0,0);if(leg.live)source.setTextColor(accent());
        header.addView(source);body.addView(header,new LinearLayout.LayoutParams(-2,-2));
        body.addView(text(mContext.getString(R.string.riga_towards,leg.headsign),16,true));
        String also=RoutingBottomMenuController.alsoAt(mContext,leg,clock);
        if(!also.isEmpty())body.addView(text(also,14,false));
        if(!leg.progress.isEmpty())
        {
          MaterialTextView live=text("● "+leg.progress,15,true);live.setTextColor(accent());
          live.setPadding(0,dp(4),0,dp(4));body.addView(live);
        }
        if(TicketApps.validates(leg))
        {
          MaterialButton validate=(MaterialButton)LayoutInflater.from(mContext).inflate(R.layout.ticket_app_button,body,false);
          boolean opensApp=TicketApps.style(validate,leg);
          LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);params.topMargin=dp(4);
          if(validate.getVisibility()!=android.view.View.GONE)body.addView(validate,params);
          if(opensApp)body.addView(text(mContext.getString(leg.vehicleNumber.isEmpty()?R.string.riga_validate_hint_scan
              :R.string.riga_validate_hint),13,false));
        }
        stop(body,first,mContext.getString(R.string.riga_board,first.name),first.departure,leg.delay);
        MaterialTextView ride=text(mContext.getResources().getQuantityString(R.plurals.riga_ride_stops,leg.stopCount(),leg.stopCount())
            +" · "+minutes+" "+mContext.getString(R.string.minute),14,false);ride.setPadding(0,dp(2),0,0);body.addView(ride);
        int middle=leg.stops.length-2;
        if(middle>0)
        {
          final int key=index;
          MaterialButton toggle=button(expanded.contains(key)?mContext.getString(R.string.riga_hide_stops):
              mContext.getResources().getQuantityString(R.plurals.riga_intermediate_stops,middle,middle));
          LinearLayout stops=column();stops.setPadding(dp(12),0,0,0);
          for(int i=1;i<leg.stops.length-1;++i){TransitJourneyLeg.Stop item=leg.stops[i];stop(stops,item,item.name,item.arrival,leg.delay,true);}
          stops.setVisibility(expanded.contains(key)?View.VISIBLE:View.GONE);
          toggle.setOnClickListener(v->{if(!expanded.remove(key))expanded.add(key);
            stops.setVisibility(expanded.contains(key)?View.VISIBLE:View.GONE);
            toggle.setText(expanded.contains(key)?mContext.getString(R.string.riga_hide_stops):
                mContext.getResources().getQuantityString(R.plurals.riga_intermediate_stops,middle,middle));});
          body.addView(toggle);body.addView(stops);
        }
        stop(body,last,mContext.getString(R.string.riga_alight,last.name),last.arrival,leg.delay);
      }
      if(now){highlight(row);current=row;}
      content.addView(row);
    }

    shownKey=panelKey(panel);
    // Opened during a trip: start at the step the rider is on; afterwards keep the rider's own scrolling.
    final View target=current;
    if(target!=null&&!scrolledToNow){scrolledToNow=true;
      scroll.post(()->scroll.smoothScrollTo(0,Math.max(0,target.getTop()-dp(8))));}
    else scroll.post(()->scroll.scrollTo(0,position));
  }
  /**
   * A stop of the route where the rider spends time, as a station of the timeline: its number as on the map and
   * in the chips, its name, when the rider gets there and goes on, and before the trip the stay itself as a
   * drop-down ("30 min ⌄") that changes it and plans the route again.
   */
  private View stayRow(TransitJourneyLeg leg,int number,boolean now,boolean editable)
  {
    TransitJourneyLeg.Stop first=leg.stops[0],last=leg.stops[leg.stops.length-1];
    LinearLayout row=new LinearLayout(mContext);row.setBaselineAligned(false);row.setGravity(Gravity.CENTER_VERTICAL);
    // The rail sits 3 dp in, so this wider marker is centred on it without reaching past the edge.
    row.setPadding(0,0,0,dp(16));
    TextView marker=new TextView(mContext);marker.setTextSize(13);marker.setTypeface(Typeface.DEFAULT_BOLD);
    marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);TransitBadge.stop(marker,number,22);
    LinearLayout.LayoutParams markerParams=new LinearLayout.LayoutParams(dp(22),dp(22));
    markerParams.setMargins(0,0,dp(2),0);row.addView(marker,markerParams);
    LinearLayout body=column();
    row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
    if(now)body.addView(nowPill());
    String chosen=StayPicker.duration(mContext,StayPicker.chosen(mContext,leg));
    MaterialTextView name=text(editable?leg.headsign:mContext.getString(R.string.riga_stay_title,leg.headsign,chosen),16,true);
    name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);body.addView(name);
    // The real times: the next ride may leave a little after the chosen stay ends.
    body.addView(text(mContext.getString(R.string.riga_stay_times,time(first.arrival),time(last.departure)),14,false));
    if(editable)
    {
      MaterialButton change=(MaterialButton)LayoutInflater.from(mContext).inflate(R.layout.riga_stay_button,row,false);
      change.setText(chosen);
      change.setContentDescription(mContext.getString(R.string.riga_stay_change_description,leg.headsign,chosen));
      change.setOnClickListener(v->StayPicker.show(mContext,leg));
      row.addView(change);
    }
    return row;
  }

  private boolean scrolledToNow;
  private String shownKey="";

  /** Changes when the rider moves on to another step, so the sheet re-renders then. */
  private static String panelKey(app.organicmaps.transit.RigaTripPanel panel)
  {
    return panel==null?"":panel.now.kind+":"+panel.now.leg+":"+panel.done;
  }

  /** A leg already behind the rider: its badge or walk and where it ended, faded. */
  private View doneRow(TransitJourneyLeg leg)
  {
    LinearLayout row=new LinearLayout(mContext);row.setGravity(Gravity.CENTER_VERTICAL);
    row.setAlpha(0.5f);row.setPadding(0,dp(2),0,dp(10));
    TextView badge=new TextView(mContext);badge.setTextSize(13);badge.setTypeface(Typeface.DEFAULT_BOLD);
    badge.setFontFeatureSettings("tnum");
    TransitBadge.bind(badge,leg,leg.number,!leg.isRide()?20:14);
    LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(-2,-2);badgeParams.setMarginEnd(dp(10));
    row.addView(badge,badgeParams);
    TransitJourneyLeg.Stop last=leg.stops[leg.stops.length-1];
    MaterialTextView label=text(time(last.arrival)+"   "+last.name,14,false);
    row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
    ImageView check=new ImageView(mContext);check.setImageResource(R.drawable.ic_done);
    check.setImageTintList(android.content.res.ColorStateList.valueOf(secondaryText()));
    check.setContentDescription(mContext.getString(R.string.riga_trip_done));
    row.addView(check,new LinearLayout.LayoutParams(dp(20),dp(20)));
    return row;
  }

  /** The "Now" marker over the step the rider is on. */
  private View nowPill()
  {
    MaterialTextView pill=text(mContext.getString(R.string.riga_trip_now),12,true);
    int on=ThemeUtils.getColor(mContext,com.google.android.material.R.attr.colorOnPrimary);
    pill.setTextColor(on);pill.setPadding(dp(8),dp(1),dp(8),dp(1));
    android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
    background.setColor(accent());background.setCornerRadius(dp(10));pill.setBackground(background);
    LinearLayout wrap=new LinearLayout(mContext);wrap.setPadding(0,0,0,dp(4));
    wrap.addView(pill,new LinearLayout.LayoutParams(-2,-2));return wrap;
  }

  /** The current step sits on a tinted surface so it reads as "you are here". */
  private void highlight(View row)
  {
    android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
    background.setColor(ThemeUtils.getColor(mContext,com.google.android.material.R.attr.colorSurfaceContainerHigh));
    background.setCornerRadius(dp(12));row.setBackground(background);
    row.setPadding(dp(8),dp(8),dp(8),0);
  }

  private final class Timeline extends Drawable
  {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int color;
    Timeline(int value){color=value;}
    @Override public void draw(@NonNull Canvas canvas){
      float x=getBounds().centerX();
      int surface=ThemeUtils.getColor(mContext,android.R.attr.colorBackground);
      paint.setColor(ColorUtils.calculateLuminance(surface)>0.4?0xff555555:0xffeeeeee);
      // The dot's edge is 6 dp round its centre: the centre sits 7 dp down so the top of the dot is not cut off.
      paint.setStrokeWidth(dp(5));canvas.drawLine(x,dp(7),x,getBounds().bottom,paint);canvas.drawCircle(x,dp(7),dp(6),paint);
      paint.setColor(color);paint.setStrokeWidth(dp(3));
      canvas.drawLine(x,dp(7),x,getBounds().bottom,paint);canvas.drawCircle(x,dp(7),dp(4),paint);
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(android.graphics.ColorFilter value){paint.setColorFilter(value);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
  }
}
