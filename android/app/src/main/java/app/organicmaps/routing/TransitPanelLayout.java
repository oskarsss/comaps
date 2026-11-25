package app.organicmaps.routing;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * The transit panel's root. A vertical swipe anywhere on it moves the sheet, as one on its grip does: taps,
 * sideways scrolls (route choices, line chips) and scrolling the open steps still go to the views under the finger.
 */
public class TransitPanelLayout extends ConstraintLayout
{
  /** Says whether a vertical swipe from a point should move the sheet, then gets the swipe's events. */
  interface Drag
  {
    boolean wants(float x, float y, float dy);

    /** Whether the point is on the open steps, which scroll. */
    boolean inSteps(float x, float y);

    /** Whether the open steps are scrolled to their top. */
    boolean stepsAtTop();

    boolean onTouch(@NonNull View panel, @NonNull MotionEvent event);
  }

  @Nullable
  private Drag mDrag;
  private final int mSlop;
  private float mDownX;
  private float mDownY;
  @Nullable
  private MotionEvent mDown;
  private boolean mDragging;
  /** The gesture began on the open steps: once they are at their top, its downward rest closes the sheet. */
  private boolean mInSteps;
  /** The highest point of the finger since the steps reached their top. */
  private float mTopY;
  /** The sheet took the gesture from the scrolled steps; it gets every event until the finger lifts. */
  private boolean mTaken;

  public TransitPanelLayout(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
    mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
  }

  void setDrag(@Nullable Drag drag) { mDrag = drag; }

  /**
   * The steps' scroll keeps a gesture it started (it stops the panel from intercepting): a finger that scrolls them
   * back to the top and goes on down then closes the sheet, as it would in one swipe on the grip.
   */
  @Override
  public boolean dispatchTouchEvent(MotionEvent event)
  {
    if (mDrag == null) return super.dispatchTouchEvent(event);
    int action = event.getActionMasked();
    if (mTaken)
    {
      mDrag.onTouch(this, event);
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) mTaken = false;
      return true;
    }
    if (action == MotionEvent.ACTION_DOWN)
    {
      mInSteps = mDrag.inSteps(event.getX(), event.getY());
      mTopY = event.getY();
    }
    else if (action == MotionEvent.ACTION_MOVE && mInSteps && !mDragging)
    {
      if (!mDrag.stepsAtTop()) mTopY = event.getY();
      else mTopY = Math.min(mTopY, event.getY());
      if (event.getY() - mTopY > mSlop)
      {
        mInSteps = false;
        mTaken = true;
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        // The sheet's drag starts here, at the top of the steps, so the panel does not jump.
        MotionEvent down = MotionEvent.obtain(event);
        down.setAction(MotionEvent.ACTION_DOWN);
        mDrag.onTouch(this, down);
        down.recycle();
        return true;
      }
    }
    return super.dispatchTouchEvent(event);
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent event)
  {
    if (mDrag == null) return false;
    switch (event.getActionMasked())
    {
    case MotionEvent.ACTION_DOWN:
      mDragging = false;
      rememberDown(event);
      return false;
    case MotionEvent.ACTION_MOVE:
      return startDrag(event);
    case MotionEvent.ACTION_UP:
    case MotionEvent.ACTION_CANCEL:
      clearDown();
      return false;
    default:
      return false;
    }
  }

  /** Hands a vertical swipe past the slop to the sheet. */
  private boolean startDrag(@NonNull MotionEvent event)
  {
    float dx = event.getX() - mDownX;
    float dy = event.getY() - mDownY;
    if (Math.abs(dy) <= mSlop || Math.abs(dy) < Math.abs(dx) * 1.5f || !mDrag.wants(mDownX, mDownY, dy))
      return false;
    mDragging = true;
    // The sheet's drag starts where the finger went down, so the panel does not jump by the slop.
    if (mDown != null) mDrag.onTouch(this, mDown);
    mDrag.onTouch(this, event);
    return true;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event)
  {
    if (mDrag == null) return super.onTouchEvent(event);
    if (!mDragging)
    {
      // A touch no child takes (plain text: the folded panel, the "Then" row) comes here, and the panel never
      // sees the rest of the gesture unless it keeps this one: keep it, and swipe the sheet from it.
      switch (event.getActionMasked())
      {
      case MotionEvent.ACTION_DOWN:
        rememberDown(event);
        super.onTouchEvent(event);
        return true;
      case MotionEvent.ACTION_MOVE:
        startDrag(event);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        clearDown();
        super.onTouchEvent(event);
        return true;
      default:
        return true;
      }
    }
    mDrag.onTouch(this, event);
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
    {
      mDragging = false;
      clearDown();
    }
    return true;
  }

  private void rememberDown(@NonNull MotionEvent event)
  {
    mDownX = event.getX();
    mDownY = event.getY();
    if (mDown != null) mDown.recycle();
    mDown = MotionEvent.obtain(event);
  }

  private void clearDown()
  {
    if (mDown != null) mDown.recycle();
    mDown = null;
  }
}
