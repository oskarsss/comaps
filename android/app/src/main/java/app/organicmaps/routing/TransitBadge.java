package app.organicmaps.routing;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;
import app.organicmaps.sdk.routing.TransitJourneyLeg;
import app.organicmaps.sdk.routing.TransitStepType;
import com.google.android.material.color.MaterialColors;

/** Line badges as the route chips draw them (mode icon and number on the line colour), for plain text views. */
final class TransitBadge
{
  private TransitBadge() {}

  /** Text colour on a line colour: dark on light fills (yellow buses), white otherwise. */
  static int onFill(int color)
  {
    return ColorUtils.calculateLuminance(color | 0xff000000) > 0.4 ? 0xff1a1a1a : Color.WHITE;
  }

  /** A ride's badge: its mode icon and {@code number} on the line colour; a walk: the walking icon, no box. */
  static void bind(@NonNull TextView view, @Nullable TransitJourneyLeg leg, @NonNull String number, int iconDp)
  {
    Context context = view.getContext();
    float density = context.getResources().getDisplayMetrics().density;
    if (view.getTag(app.organicmaps.R.id.transit_badge_min) instanceof int[] min)
    {
      view.setMinWidth(min[0]);
      view.setMinHeight(min[1]);
      view.setMaxWidth(Integer.MAX_VALUE);
      view.setTag(app.organicmaps.R.id.transit_badge_min, null);
    }
    // A walk or a stay at a stop of the route: an icon alone, no box.
    boolean walk = leg == null || !leg.isRide();
    int text = walk ? MaterialColors.getColor(view, android.R.attr.textColorSecondary) : onFill(leg.color);
    view.setText(walk ? "" : number);
    view.setTextColor(text);
    if (walk) view.setBackground(null);
    else
    {
      GradientDrawable background = new GradientDrawable();
      background.setColor(leg.color);
      background.setCornerRadius(6 * density);
      // Light fills (yellow buses) need an edge on a light surface.
      if (ColorUtils.calculateLuminance(leg.color | 0xff000000) > 0.6)
        background.setStroke(Math.max(1, Math.round(density)), ColorUtils.blendARGB(leg.color, Color.BLACK, 0.25f));
      view.setBackground(background);
    }
    int horizontal = Math.round((walk ? 0 : 8) * density), vertical = Math.round((walk ? 0 : 3) * density);
    view.setPadding(horizontal, vertical, horizontal, vertical);
    Drawable icon = ContextCompat.getDrawable(context, leg != null && leg.isStay() ? app.organicmaps.R.drawable.ic_clock
        : (walk ? TransitStepType.PEDESTRIAN : leg.type).getDrawable());
    if (icon != null)
    {
      icon = DrawableCompat.wrap(icon.mutate());
      DrawableCompat.setTint(icon, text);
      int size = Math.round(iconDp * density);
      icon.setBounds(0, 0, size, size);
    }
    view.setCompoundDrawablesRelative(icon, null, null, null);
    view.setCompoundDrawablePadding(walk ? 0 : Math.round(4 * density));
  }

  /**
   * A stop of the route where the rider stays: its number in a filled circle, as the map marks it and the chips
   * show it, so a stop reads as the same place in every part of the panel.
   */
  static void stop(@NonNull TextView view, int number, int sizeDp)
  {
    float density = view.getResources().getDisplayMetrics().density;
    GradientDrawable dot = new GradientDrawable();
    dot.setShape(GradientDrawable.OVAL);
    dot.setColor(MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary));
    int size = Math.round(sizeDp * density);
    dot.setSize(size, size);
    view.setBackground(dot);
    view.setText(String.valueOf(number));
    view.setTextColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary));
    view.setGravity(android.view.Gravity.CENTER);
    // A circle, whatever minimum the view had for line badges (given back by bind()).
    if (view.getTag(app.organicmaps.R.id.transit_badge_min) == null)
      view.setTag(app.organicmaps.R.id.transit_badge_min, new int[] {view.getMinWidth(), view.getMinHeight()});
    view.setMinWidth(size);
    view.setMinHeight(size);
    view.setMaxWidth(size);
    view.setPadding(0, 0, 0, 0);
    view.setCompoundDrawablesRelative(null, null, null, null);
  }

  /** The number of the stay {@code legs[index]} among the route's stops, from 1. */
  static int stopNumber(@NonNull TransitJourneyLeg[] legs, int index)
  {
    int number = 0;
    for (int i = 0; i <= index && i < legs.length; ++i)
      if (legs[i].isStay()) ++number;
    return number;
  }

  /** {@code text} led by a small badge of the ride's line, e.g. "[49] Validate in Mobilly · vehicle 79349". */
  @NonNull
  static CharSequence prefix(@NonNull TransitJourneyLeg ride, @NonNull CharSequence text, float density)
  {
    SpannableStringBuilder value = new SpannableStringBuilder(ride.number).append("  ").append(text);
    value.setSpan(new Badge(ride.color, density), 0, ride.number.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return value;
  }

  /** A rounded line-colour box behind a run of text. */
  private static final class Badge extends ReplacementSpan
  {
    private final int mColor;
    private final float mDensity;
    Badge(int color, float density)
    {
      mColor = color;
      mDensity = density;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm)
    {
      return Math.round(paint.measureText(text, start, end) + 12 * mDensity);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                     int bottom, @NonNull Paint paint)
    {
      Paint.FontMetrics metrics = paint.getFontMetrics();
      float width = paint.measureText(text, start, end) + 12 * mDensity;
      RectF box = new RectF(x, y + metrics.ascent - 2 * mDensity, x + width, y + metrics.descent + 2 * mDensity);
      int color = paint.getColor();
      boolean bold = paint.isFakeBoldText();
      paint.setColor(mColor);
      canvas.drawRoundRect(box, 4 * mDensity, 4 * mDensity, paint);
      paint.setColor(onFill(mColor));
      paint.setFakeBoldText(true);
      canvas.drawText(text, start, end, x + 6 * mDensity, y, paint);
      paint.setColor(color);
      paint.setFakeBoldText(bold);
    }
  }
}
