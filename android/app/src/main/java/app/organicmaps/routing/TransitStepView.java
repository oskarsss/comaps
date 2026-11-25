package app.organicmaps.routing;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.ColorInt;
import androidx.annotation.Dimension;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.drawable.DrawableCompat;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitStepInfo;
import app.organicmaps.sdk.routing.TransitStepType;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.widget.recycler.MultilineLayoutManager;
import com.google.android.material.color.MaterialColors;

/**
 * Represents a specific transit step. It displays a transit info, such as a number, color, etc., for
 * the specific transit type: pedestrian, rail, metro, etc.
 */
public class TransitStepView extends View implements MultilineLayoutManager.SqueezingInterface
{
  @Nullable
  private Drawable mDrawable;
  @NonNull
  private final RectF mBackgroundBounds = new RectF();
  private final RectF mOutlineBounds = new RectF();
  @NonNull
  private final Rect mDrawableBounds = new Rect();
  @NonNull
  private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  /** Edge for light line colours (yellow buses), which would otherwise melt into a white panel. */
  @NonNull
  private final Paint mOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  @NonNull
  private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  /** The stop count, set beside the line badge in the quieter secondary colour. */
  @NonNull
  private final TextPaint mSuffixPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private int mBackgroundCornerRadius;
  @Nullable
  private String mText;
  @Nullable
  private String mSuffix;
  private boolean mOutline;
  private int mOnFill = Color.WHITE;
  @SuppressWarnings("NullableProblems")
  @NonNull
  private TransitStepType mStepType;

  public TransitStepView(Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    init(attrs);
  }

  public TransitStepView(Context context, @Nullable AttributeSet attrs, int defStyleAttr)
  {
    super(context, attrs, defStyleAttr);
    init(attrs);
  }

  private void init(@Nullable AttributeSet attrs)
  {
    mBackgroundCornerRadius = getResources().getDimensionPixelSize(R.dimen.routing_transit_step_corner_radius);
    TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.TransitStepView);
    float textSize = a.getDimensionPixelSize(R.styleable.TransitStepView_android_textSize, 0);
    @ColorInt
    int textColor = a.getColor(R.styleable.TransitStepView_android_textColor, Color.BLACK);
    mTextPaint.setTextSize(textSize);
    mTextPaint.setColor(textColor);
    mDrawable = a.getDrawable(R.styleable.TransitStepView_android_drawable);
    if (mDrawable != null)
      mDrawable = DrawableCompat.wrap(mDrawable);
    mStepType = TransitStepType.PEDESTRIAN;
    a.recycle();
  }

  public void setTransitStepInfo(@NonNull TransitStepInfo info)
  {
    mStepType = info.getType();
    int fill = getBackgroundColor(getContext(), info);
    int secondary = MaterialColors.getColor(this, android.R.attr.textColorSecondary);
    mBackgroundPaint.setColor(fill);
    mOnFill = ColorUtils.calculateLuminance(fill | 0xff000000) > 0.4 ? 0xff1a1a1a : Color.WHITE;
    mTextPaint.setColor(mOnFill);
    mTextPaint.setFontFeatureSettings("tnum");
    mSuffixPaint.set(mTextPaint);
    mSuffixPaint.setColor(secondary);
    // Only light fills need an edge on the panel; dark ones stand on their own.
    mOutline = mStepType != TransitStepType.PEDESTRIAN && mStepType != TransitStepType.RULER
               && ColorUtils.calculateLuminance(fill | 0xff000000) > 0.6;
    mOutlinePaint.setStyle(Paint.Style.STROKE);
    mOutlinePaint.setStrokeWidth(getResources().getDisplayMetrics().density);
    mOutlinePaint.setColor(ColorUtils.blendARGB(fill | 0xff000000, Color.BLACK, 0.25f));
    mSuffix = null;
    if (mStepType == TransitStepType.PEDESTRIAN)
    {
      // Walks are the connective tissue between rides: an icon and minutes in the secondary text colour,
      // with no box, so the coloured line badges carry the row.
      mBackgroundPaint.setColor(Color.TRANSPARENT);
      mOnFill = secondary;
      mTextPaint.setColor(secondary);
    }
    if (mStepType == TransitStepType.INTERMEDIATE_POINT)
    {
      mDrawable = null;
      mText = String.valueOf(info.getIntermediateIndex() + 1);
      // A stop of a public transport route: the numbered marker, as on the map, then the time spent there in the
      // accent colour, the app's cue for "tap to change" (the chip opens the stay picker).
      if (info.getTimeInSec() > 0)
      {
        String stay = StayPicker.duration(getContext(), info.getTimeInSec() * 1000L);
        mSuffix = stay;
        mSuffixPaint.setColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
        mSuffixPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setContentDescription(getContext().getString(R.string.riga_stay_chip_description, mText, stay));
      }
      else
        setContentDescription(null);
    }
    else if (mStepType == TransitStepType.RULER)
    {
      mDrawable = null;
      mText = info.getDistance() + " " + info.getDistanceUnits();
      mTextPaint.setColor(ThemeUtils.isDefaultTheme() ? Color.BLACK : Color.WHITE);
    }
    else
    {
      mDrawable = ResourcesCompat.getDrawable(getResources(), mStepType.getDrawable(), null);
      int minutes = (info.getTimeInSec() + 59) / 60;
      String number = info.getNumber();
      // Kept short so a long route reads at a glance (the steps have the rest): a walk is its icon and minutes,
      // a ride its line, with the other lines that make the same ride in the same badge ("7/14").
      mText = mStepType == TransitStepType.PEDESTRIAN ? String.valueOf(minutes) : number;
      String stops = info.getStopCount() > 0
          ? getResources().getQuantityString(R.plurals.riga_stops_count, info.getStopCount(), info.getStopCount())
          : null;
      int mode = modeName(mStepType);
      setContentDescription(mStepType == TransitStepType.PEDESTRIAN
          ? getResources().getQuantityString(R.plurals.riga_walk_minutes, minutes, minutes)
          : (mode == 0 ? "" : getContext().getString(mode) + " ") + (number == null ? "" : number.replace("/", ", "))
            + (stops == null ? "" : ", " + stops));
    }
    invalidate();
    requestLayout();
  }

  private static int modeName(@NonNull TransitStepType type)
  {
    return switch (type)
    {
      case TRAM -> R.string.riga_tram;
      case TROLLEYBUS -> R.string.riga_trolleybus;
      case BUS -> R.string.riga_bus;
      case TRAIN, LIGHT_RAIL -> R.string.riga_train;
      default -> 0;
    };
  }

  /** Space between the line badge and its stop count. */
  private int suffixGap()
  {
    return getResources().getDimensionPixelSize(R.dimen.margin_quarter) * 3 / 2;
  }

  @ColorInt
  private static int getBackgroundColor(@NonNull Context context, @NonNull TransitStepInfo info)
  {
    return switch (info.getType())
    {
      case PEDESTRIAN -> ThemeUtils.getColor(context, R.attr.transitPedestrianBackground);
      case RULER -> ThemeUtils.getColor(context, R.attr.transitRulerBackground);
      case INTERMEDIATE_POINT -> ThemeUtils.getColor(context, androidx.appcompat.R.attr.colorPrimary);
      default -> info.getColor();
    };
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
  {
    int height = getDefaultSize(getSuggestedMinimumHeight(), MeasureSpec.UNSPECIFIED);
    // At least as tall as a line of its text, so a large system font does not spill out of the badge.
    if (!TextUtils.isEmpty(mText))
      height = Math.max(height, getPaddingTop() + getPaddingBottom()
                                    + (int) Math.ceil(mTextPaint.descent() - mTextPaint.ascent()));
    int width = getPaddingStart() + getPaddingEnd();
    if (mDrawable != null)
    {
      calculateDrawableBounds(height, mDrawable);
      width += mDrawableBounds.width();
    }

    if (!TextUtils.isEmpty(mText))
    {
      final int computedTextWidth = (int) ((mDrawable != null ? getPaddingStart() : 0) + mTextPaint.measureText(mText));

      if (mStepType == TransitStepType.INTERMEDIATE_POINT)
      {
        // Make the background a round/pill shape for intermediate points to mimic the map icons
        width = Math.max(width + computedTextWidth, height);
        mBackgroundCornerRadius = width / 2;
      }
      else
        width += computedTextWidth;
    }
    mBackgroundBounds.set(0, 0, width, height);
    if (!TextUtils.isEmpty(mSuffix))
      width += suffixGap() + (int) Math.ceil(mSuffixPaint.measureText(mSuffix));
    setMeasuredDimension(width, height);
  }

  @Override
  public void squeezeTo(@Dimension int width)
  {
    // The stay goes first: the line number is what the rider looks for.
    if (mSuffix != null)
    {
      mSuffix = null;
      if (mBackgroundBounds.width() <= width)
        return;
    }
    int tSize = width - 2 * getPaddingStart() - getPaddingEnd() - mDrawableBounds.width();
    mText = TextUtils.ellipsize(mText, mTextPaint, tSize, TextUtils.TruncateAt.END).toString();
  }

  @Override
  public int getMinimumAcceptableSize()
  {
    return getResources().getDimensionPixelSize(R.dimen.routing_transit_setp_min_acceptable_with);
  }

  private void calculateDrawableBounds(int height, @NonNull Drawable drawable)
  {
    // If the clear view height, i.e. without top/bottom padding, is greater than the drawable height
    // the drawable should be centered vertically by adding additional vertical top/bottom padding.
    // If the drawable height is greater than the clear view height the drawable will be fitted
    // (squeezed) into the parent container.
    int clearHeight = height - getPaddingTop() - getPaddingBottom();
    int vPad = 0;
    if (clearHeight >= drawable.getIntrinsicHeight())
      vPad = (clearHeight - drawable.getIntrinsicHeight()) / 2;
    int acceptableDrawableHeight =
        clearHeight >= drawable.getIntrinsicHeight() ? drawable.getIntrinsicHeight() : clearHeight;
    // A transit icon must be squared-shaped. So, if the drawable width is greater than height the
    // drawable will be squeezed horizontally to make it squared-shape.
    int acceptableDrawableWidth = drawable.getIntrinsicWidth() > acceptableDrawableHeight
                                    ? acceptableDrawableHeight
                                    : drawable.getIntrinsicWidth();
    mDrawableBounds.set(getPaddingStart(), getPaddingTop() + vPad, acceptableDrawableWidth + getPaddingStart(),
                        getPaddingTop() + vPad + acceptableDrawableHeight);
  }

  @Override
  protected void onDraw(Canvas canvas)
  {
    if (getBackground() == null)
    {
      canvas.drawRoundRect(mBackgroundBounds, mBackgroundCornerRadius, mBackgroundCornerRadius, mBackgroundPaint);
      if (mOutline)
      {
        float inset = mOutlinePaint.getStrokeWidth() / 2;
        mOutlineBounds.set(mBackgroundBounds);
        mOutlineBounds.inset(inset, inset);
        canvas.drawRoundRect(mOutlineBounds, mBackgroundCornerRadius, mBackgroundCornerRadius, mOutlinePaint);
      }
    }

    if (mDrawable != null)
      drawDrawable(getContext(), mStepType, mDrawable, canvas);

    if (!TextUtils.isEmpty(mText))
    {
      int yPos = (int) ((getHeight() / 2) - ((mTextPaint.descent() + mTextPaint.ascent()) / 2));
      int xPos = mDrawable != null ? mDrawable.getBounds().right + getPaddingStart()
                                   : (int) ((mBackgroundBounds.width() - mTextPaint.measureText(mText)) / 2);
      canvas.drawText(mText, xPos, yPos, mTextPaint);
      if (!TextUtils.isEmpty(mSuffix))
        canvas.drawText(mSuffix, mBackgroundBounds.right + suffixGap(), yPos, mSuffixPaint);
    }
  }

  private void drawDrawable(@NonNull Context context, @NonNull TransitStepType type, @NonNull Drawable drawable,
                            @NonNull Canvas canvas)
  {
    drawable.mutate();
    DrawableCompat.setTint(drawable, mOnFill);
    drawable.setBounds(mDrawableBounds);
    drawable.draw(canvas);
  }
}
