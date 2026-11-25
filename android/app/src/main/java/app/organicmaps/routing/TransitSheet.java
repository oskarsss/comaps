package app.organicmaps.routing;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.core.widget.NestedScrollView;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitRouteInfo;
import app.organicmaps.transit.RigaTripPanel;

/**
 * The transit route panel as a sheet. Collapsed it shows the summary: the plan's choices, or during a trip the
 * step to do now and the one after it. Dragged up on its grip, or tapped, it opens in place into every step of the
 * journey (Google Maps style), with the step the rider is on marked and scrolled to; dragged down, it closes again.
 * The header (time, line chips) and the Start/End row stay in both states. Dragged down from the summary it folds
 * to just the header and the Start/End row, leaving the map most of the screen; dragged up or tapped it unfolds.
 */
final class TransitSheet
{
  private static final float MAX_SHARE = 0.5f;

  private final View mHandle;
  private final View mSummary;
  private final NestedScrollView mScroll;
  private final TransitItinerary mItinerary;
  private boolean mAvailable;
  private boolean mExpanded;
  /** Folded below the summary: only the header (time, arrival) and the Start/End row stay, for more map. */
  private boolean mCompact;
  /** Shown in the summary state but folded away in the compact one (besides the summary itself). */
  private final View[] mFoldable;
  /** 0 closed .. 1 open; between while dragged or animated. */
  private float mProgress;
  private int mSummaryHeight;
  private int mOpenHeight;
  @Nullable
  private ValueAnimator mAnimator;
  @Nullable
  private TransitRouteInfo mInfo;
  @Nullable
  private RigaTripPanel mPanel;

  TransitSheet(@NonNull View frame)
  {
    mHandle = frame.findViewById(R.id.transit_handle);
    mSummary = frame.findViewById(R.id.transit_summary);
    mScroll = frame.findViewById(R.id.transit_itinerary);
    LinearLayout content = frame.findViewById(R.id.transit_itinerary_content);
    mItinerary = new TransitItinerary(mScroll, content, () -> setExpanded(false, true));
    mFoldable = new View[] {frame.findViewById(R.id.transit_chips_scroll), frame.findViewById(R.id.transit_description),
                            frame.findViewById(R.id.transit_mobilly), frame.findViewById(R.id.transit_wait)};
    mHandle.setOnClickListener(v -> {
      if (mCompact) setCompact(false);
      else setExpanded(!mExpanded, true);
    });
    attachDrag(mHandle);
    attachDrag(frame.findViewById(R.id.total_time));
    if (frame instanceof TransitPanelLayout panel)
    {
      View.OnTouchListener drag = newDrag(panel);
      panel.setDrag(new TransitPanelLayout.Drag() {
        @Override
        public boolean wants(float x, float y, float dy)
        {
          if (!mAvailable) return false;
          // In the open steps a swipe scrolls them; at their top, a swipe down closes the sheet.
          return !inSteps(x, y) || (dy > 0 && !mScroll.canScrollVertically(-1));
        }

        @Override
        public boolean inSteps(float x, float y)
        {
          if (!mAvailable || !mExpanded || mScroll.getVisibility() != View.VISIBLE) return false;
          android.graphics.Rect rect = new android.graphics.Rect();
          mScroll.getHitRect(rect);
          View parent = (View) mScroll.getParent();
          while (parent != null && parent != panel)
          {
            rect.offset(parent.getLeft(), parent.getTop());
            parent = parent.getParent() instanceof View p ? p : null;
          }
          return rect.contains((int) x, (int) y);
        }

        @Override
        public boolean stepsAtTop() { return !mScroll.canScrollVertically(-1); }

        @Override
        public boolean onTouch(@NonNull View view, @NonNull MotionEvent event)
        {
          return drag.onTouch(view, event);
        }
      });
    }
    describe();
  }

  boolean isExpanded() { return mExpanded; }

  boolean isCompact() { return mCompact; }

  /** Folds or unfolds the panel to its header and Start/End row; the panel's own content is redrawn by the
   *  state listener when it unfolds. */
  void setCompact(boolean compact)
  {
    if (compact == mCompact || (compact && (!mAvailable || mExpanded))) return;
    beginTransition();
    mCompact = compact;
    setProgress(mProgress);
    applyCompact();
    describe();
    if (mOnState != null) mOnState.run();
    mHandle.announceForAccessibility(
        mHandle.getContext().getString(compact ? R.string.riga_sheet_folded : R.string.riga_sheet_collapsed));
  }

  /** Hides what the compact panel leaves out; called after the panel's content is bound, which shows it. */
  void applyCompact()
  {
    if (!mCompact) return;
    if (mProgress <= 0) mSummary.setVisibility(View.GONE);
    // An offered action (a new route after a missed ride) keeps the message that says why.
    View action = mHandle.getRootView().findViewById(R.id.transit_action);
    boolean acting = action != null && action.getVisibility() == View.VISIBLE;
    for (View view : mFoldable)
      if (view != null && !(acting && view.getId() == R.id.transit_description)) view.setVisibility(View.GONE);
  }

  /** Animates the panel's height to the folded or unfolded layout. */
  private void beginTransition()
  {
    if (panel() instanceof ViewGroup group)
      android.transition.TransitionManager.beginDelayedTransition(group,
          new android.transition.AutoTransition().setDuration(200));
  }

  /** Told whenever the sheet opens or closes, so controls that toggle it can show which way they go. */
  @Nullable
  private Runnable mOnState;

  void setOnStateChange(@Nullable Runnable listener) { mOnState = listener; }

  /** Opens or closes from a tap on the summary (the plan's description, the trip's "now" card or next step). */
  void toggle()
  {
    if (mAvailable) setExpanded(!mExpanded, true);
  }

  /** Timed legs, or at least written instructions, to open into. */
  static boolean hasSteps(@NonNull TransitRouteInfo info)
  {
    return info.journeyLegs.length > 0 || !info.instructions.isEmpty();
  }

  /**
   * The shown route, and during a trip where the rider is. Routes without steps (a status message) keep the
   * plain panel.
   */
  void bind(@NonNull TransitRouteInfo info, @Nullable RigaTripPanel panel)
  {
    mInfo = info;
    mPanel = panel;
    mAvailable = hasSteps(info);
    mHandle.setVisibility(mAvailable ? View.VISIBLE : View.GONE);
    if (!mAvailable)
    {
      reset();
      return;
    }
    if (mExpanded && mAnimator == null)
    {
      mItinerary.update(info, panel);
      mScroll.post(() -> {
        if (!mExpanded || mAnimator != null) return;
        mOpenHeight = openHeight();
        setProgress(1);
      });
    }
  }

  /** Closes at once and hides the grip: the panel shows something that is not a journey. */
  void reset()
  {
    if (mAnimator != null) mAnimator.cancel();
    mAnimator = null;
    mInfo = null;
    mAvailable = false;
    mHandle.setVisibility(View.GONE);
    panel().setTranslationY(0);
    mCompact = false;
    if (mExpanded || mProgress > 0)
    {
      mExpanded = false;
      setProgress(0);
      describe();
      if (mOnState != null) mOnState.run();
    }
  }

  /** Opens from a tap on the summary (the "now" card, the next step, the plan's description). */
  void expand()
  {
    if (mAvailable && !mExpanded) setExpanded(true, true);
  }

  /** @return true if the back press closed the sheet. */
  boolean collapse()
  {
    if (!mExpanded) return false;
    setExpanded(false, true);
    return true;
  }

  private void setExpanded(boolean expanded, boolean announce)
  {
    if (!mAvailable && expanded) return;
    if (expanded && mProgress == 0) prepareOpen();
    boolean changed = mExpanded != expanded;
    mExpanded = expanded;
    animateTo(expanded ? 1 : 0);
    describe();
    if (changed && mOnState != null) mOnState.run();
    if (announce)
      mHandle.announceForAccessibility(
          mHandle.getContext().getString(expanded ? R.string.riga_sheet_expanded : R.string.riga_sheet_collapsed));
  }

  /** Draws the steps and measures both states before the sheet starts to open. */
  private void prepareOpen()
  {
    if (mInfo == null) return;
    mSummaryHeight = mSummary.getVisibility() == View.VISIBLE ? mSummary.getHeight() : 0;
    mItinerary.rewind();
    mItinerary.update(mInfo, mPanel);
    mOpenHeight = openHeight();
  }

  /** The steps' natural height, capped to a share of the screen so the map stays in view above. */
  private int openHeight()
  {
    View content = mScroll.getChildAt(0);
    int width = ((View) mScroll.getParent()).getWidth();
    content.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int screen = mScroll.getRootView().getHeight();
    return Math.max(1, Math.min(content.getMeasuredHeight(), (int) (screen * MAX_SHARE)));
  }

  /** Summary shrinks and fades while the steps grow: the panel's height follows the finger. */
  private void setProgress(float progress)
  {
    mProgress = Math.max(0, Math.min(1, progress));
    ViewGroup.LayoutParams summary = mSummary.getLayoutParams();
    ViewGroup.LayoutParams scroll = mScroll.getLayoutParams();
    // The summary keeps its natural height (a squeezed LinearLayout would re-wrap its rows and jump): its lower
    // part is clipped away and the margin lets the steps take that space.
    summary.height = ViewGroup.LayoutParams.WRAP_CONTENT;
    int hidden = 0;
    if (mProgress <= 0)
    {
      mSummary.setAlpha(1);
      mSummary.setVisibility(mCompact ? View.GONE : View.VISIBLE);
      mScroll.setVisibility(View.GONE);
      scroll.height = 0;
    }
    else if (mProgress >= 1)
    {
      mSummary.setVisibility(View.GONE);
      mScroll.setVisibility(View.VISIBLE);
      mScroll.setAlpha(1);
      scroll.height = mOpenHeight;
    }
    else
    {
      mSummary.setVisibility(View.VISIBLE);
      int shown = Math.round(mSummaryHeight * (1 - mProgress));
      hidden = mSummaryHeight - shown;
      mClip.set(0, 0, Math.max(1, mSummary.getWidth()), shown);
      mSummary.setClipBounds(mClip);
      mSummary.setAlpha(1 - mProgress);
      mScroll.setVisibility(View.VISIBLE);
      mScroll.setAlpha(Math.min(1, mProgress * 1.5f));
      scroll.height = Math.round(mOpenHeight * mProgress);
    }
    if (hidden == 0) mSummary.setClipBounds(null);
    if (summary instanceof ViewGroup.MarginLayoutParams margins) margins.bottomMargin = -hidden;
    mSummary.setLayoutParams(summary);
    mScroll.setLayoutParams(scroll);
  }

  private final Rect mClip = new Rect();

  /** The panel that holds the sheet: the view whose top edge the rider sees moving. */
  @NonNull
  private View panel()
  {
    View panel = mHandle;
    View root = panel.getRootView();
    while (panel.getParent() instanceof View parent && parent != root && parent.getHeight() < root.getHeight() * 0.9)
      panel = parent;
    return panel;
  }

  /** The panel's height (as far as the sheet changes it) at a progress. */
  private float sheetHeight(float progress)
  {
    return mSummaryHeight * (1 - progress) + mOpenHeight * progress;
  }

  private void animateTo(float target)
  {
    if (mAnimator != null) mAnimator.cancel();
    ValueAnimator animator = ValueAnimator.ofFloat(mProgress, target);
    View panel = panel();
    float slid = panel.getTranslationY();
    animator.setDuration((long) (250 * Math.max(0.4f, Math.max(Math.abs(target - mProgress),
                                                                 slid / Math.max(1, panel.getHeight())))));
    animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(a -> {
      setProgress((float) a.getAnimatedValue());
      panel.setTranslationY(slid * (1 - a.getAnimatedFraction()));
    });
    animator.addListener(new AnimatorListenerAdapter() {
      private boolean mCancelled;
      @Override
      public void onAnimationCancel(Animator animation) { mCancelled = true; }
      @Override
      public void onAnimationEnd(Animator animation)
      {
        if (mAnimator == animation) mAnimator = null;
        if (!mCancelled)
        {
          setProgress(target);
          panel.setTranslationY(0);
        }
      }
    });
    mAnimator = animator;
    animator.start();
  }

  /** Spoken name, state and expand/collapse actions on the grip. */
  private void describe()
  {
    android.content.Context context = mHandle.getContext();
    mHandle.setContentDescription(context.getString(mCompact ? R.string.riga_sheet_unfold
                                                    : mExpanded ? R.string.riga_sheet_hide : R.string.riga_sheet_show));
    ViewCompat.setStateDescription(mHandle, context.getString(mCompact ? R.string.riga_sheet_folded
        : mExpanded ? R.string.riga_sheet_expanded : R.string.riga_sheet_collapsed));
    ViewCompat.removeAccessibilityAction(mHandle, AccessibilityActionCompat.ACTION_EXPAND.getId());
    ViewCompat.removeAccessibilityAction(mHandle, AccessibilityActionCompat.ACTION_COLLAPSE.getId());
    // Up: folded -> summary -> steps; down: steps -> summary -> folded.
    if (!mExpanded)
      ViewCompat.replaceAccessibilityAction(mHandle, AccessibilityActionCompat.ACTION_EXPAND, null,
          (view, arguments) -> {
            if (mCompact) setCompact(false);
            else setExpanded(true, true);
            return true;
          });
    if (!mCompact)
      ViewCompat.replaceAccessibilityAction(mHandle, AccessibilityActionCompat.ACTION_COLLAPSE, null,
          (view, arguments) -> {
            if (mExpanded) setExpanded(false, true);
            else setCompact(true);
            return true;
          });
  }

  @SuppressLint("ClickableViewAccessibility")
  private void attachDrag(@NonNull View view)
  {
    view.setOnTouchListener(newDrag(view));
  }

  /** The sheet's drag: up opens the steps, down closes them, and below the summary folds the panel. */
  @NonNull
  private View.OnTouchListener newDrag(@NonNull View view)
  {
    final int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
    final float fling = ViewConfiguration.get(view.getContext()).getScaledMinimumFlingVelocity() * 2f;
    return new View.OnTouchListener() {
      private float mDownY;
      private float mStart;
      private boolean mDragging;
      /** The panel slid while dragging, found once per drag. */
      private View mPanel;

      /**
       * The finger's speed on the screen. The event's own coordinates are the moving panel's, which follows the
       * finger: measured there, a drag down reads as standing still or going up, and the sheet springs back.
       */
      private void track(MotionEvent event)
      {
        if (mVelocity == null) return;
        MotionEvent screen = MotionEvent.obtain(event);
        screen.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(screen);
        screen.recycle();
      }

      /** The gesture is the sheet's: the parent stops intercepting and the view under the finger is not pressed. */
      private void claim(View v)
      {
        v.getParent().requestDisallowInterceptTouchEvent(true);
        v.setPressed(false);
        v.cancelLongPress();
      }
      /** This gesture already folded or unfolded the panel: the rest of it does nothing. */
      private boolean mFolded;
      @Nullable
      private VelocityTracker mVelocity;

      @Override
      public boolean onTouch(View v, MotionEvent event)
      {
        if (!mAvailable) return false;
        switch (event.getActionMasked())
        {
        case MotionEvent.ACTION_DOWN:
          mDownY = event.getRawY();
          mDragging = false;
          mFolded = false;
          mVelocity = VelocityTracker.obtain();
          track(event);
          return v != mHandle;
        case MotionEvent.ACTION_MOVE:
        {
          track(event);
          float dy = mDownY - event.getRawY();
          // Below the summary the panel folds instead of following the finger: down folds, up unfolds.
          if (!mDragging && !mFolded && Math.abs(dy) > slop
              && (mCompact ? dy > 0 : dy < 0 && !mExpanded && mProgress == 0 && mAnimator == null))
          {
            mFolded = true;
            claim(v);
            setCompact(!mCompact);
          }
          if (mFolded) return true;
          if (!mDragging && Math.abs(dy) > slop)
          {
            mDragging = true;
            if (mAnimator != null) mAnimator.cancel();
            mAnimator = null;
            if (mProgress == 0) prepareOpen();
            mStart = mProgress;
            mPanel = panel();
            claim(v);
          }
          if (mDragging)
          {
            int range = Math.max(Math.abs(mOpenHeight - mSummaryHeight), mOpenHeight / 2);
            setProgress(mStart + dy / Math.max(1, range));
            // Where the height change alone leaves the top edge behind a downward finger (steps about as tall as
            // the summary), the panel slides down the rest of the way, and back on release.
            float behind = -dy - (sheetHeight(mStart) - sheetHeight(mProgress));
            mPanel.setTranslationY(Math.max(0, behind));
            return true;
          }
          return v != mHandle;
        }
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
        {
          float velocity = 0;
          if (mVelocity != null)
          {
            track(event);
            mVelocity.computeCurrentVelocity(1000);
            velocity = -mVelocity.getYVelocity();
            mVelocity.recycle();
            mVelocity = null;
          }
          if (mFolded) return true;
          if (!mDragging)
          {
            // A tap on the header opens or closes like a tap on the grip; the grip handles its own click.
            if (v != mHandle && event.getActionMasked() == MotionEvent.ACTION_UP)
            {
              if (mCompact) setCompact(false);
              else setExpanded(!mExpanded, true);
            }
            return v != mHandle;
          }
          mDragging = false;
          // A flick goes the way it went; a slow drag commits once it is a fifth of the way to the other state.
          boolean open = Math.abs(velocity) > fling ? velocity > 0 : mStart < 0.5f ? mProgress > 0.2f : mProgress > 0.8f;
          if (open != mExpanded || mProgress != (open ? 1 : 0) || mPanel.getTranslationY() != 0)
            setExpanded(open, open != mExpanded);
          return true;
        }
        default:
          return false;
        }
      }
    };
  }
}
