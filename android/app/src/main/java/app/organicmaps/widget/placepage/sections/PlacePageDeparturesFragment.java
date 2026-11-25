package app.organicmaps.widget.placepage.sections;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.R;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.transit.RigaStopBoard;
import app.organicmaps.widget.placepage.PlacePageViewModel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Next scheduled departures of a Riga public transport stop, by line and direction. */
public class PlacePageDeparturesFragment extends Fragment implements Observer<MapObject>
{
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static final long REFRESH = 30000;

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final Runnable mRefresh = this::load;
  private PlacePageViewModel mViewModel;
  private LinearLayout mRoot;
  private LinearLayout mRows;
  private MapObject mObject;
  private int mGeneration;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    mViewModel = new ViewModelProvider(requireActivity()).get(PlacePageViewModel.class);
    Context context = requireContext();
    mRoot = new LinearLayout(context);
    mRoot.setOrientation(LinearLayout.VERTICAL);
    int pad = getResources().getDimensionPixelSize(R.dimen.margin_base);
    mRoot.setPadding(pad, pad / 2, pad, pad / 2);
    TextView title = new TextView(context);
    title.setText(R.string.riga_departures_title);
    title.setTextSize(16);
    title.setTypeface(title.getTypeface(), Typeface.BOLD);
    mRoot.addView(title);
    mRows = new LinearLayout(context);
    mRows.setOrientation(LinearLayout.VERTICAL);
    mRoot.addView(mRows);
    TextView note = new TextView(context);
    note.setText(R.string.riga_departures_scheduled);
    note.setTextSize(12);
    note.setTextColor(com.google.android.material.color.MaterialColors.getColor(context,
        com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff757575));
    mRoot.addView(note);
    mRoot.setVisibility(View.GONE);
    return mRoot;
  }

  @Override
  public void onStart()
  {
    super.onStart();
    mViewModel.getMapObject().observe(requireActivity(), this);
  }

  @Override
  public void onStop()
  {
    super.onStop();
    mViewModel.getMapObject().removeObserver(this);
    mHandler.removeCallbacks(mRefresh);
    ++mGeneration;
  }

  @Override
  public void onChanged(@Nullable MapObject mapObject)
  {
    mObject = mapObject;
    ++mGeneration;
    mHandler.removeCallbacks(mRefresh);
    mRoot.setVisibility(View.GONE);
    load();
  }

  private void load()
  {
    MapObject object = mObject;
    if (object == null || !RigaStopBoard.covers(object.getLat(), object.getLon())) return;
    int generation = ++mGeneration;
    Context context = requireContext().getApplicationContext();
    String title = object.getTitle();
    double lat = object.getLat(), lon = object.getLon();
    WORKER.execute(() -> {
      List<RigaStopBoard.Row> rows;
      try { rows = RigaStopBoard.board(context, title, lat, lon, System.currentTimeMillis()); }
      catch (Exception e) { rows = java.util.Collections.emptyList(); }
      List<RigaStopBoard.Row> result = rows;
      mHandler.post(() -> {
        if (generation != mGeneration || !isAdded()) return;
        show(result);
        if (!result.isEmpty()) mHandler.postDelayed(mRefresh, REFRESH);
      });
    });
  }

  private void show(List<RigaStopBoard.Row> rows)
  {
    mRows.removeAllViews();
    mRoot.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    Context context = requireContext();
    long now = System.currentTimeMillis();
    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    clock.setTimeZone(TimeZone.getTimeZone("Europe/Riga"));
    int gap = dp(context, 6);
    for (RigaStopBoard.Row row : rows)
    {
      LinearLayout line = new LinearLayout(context);
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.setPadding(0, gap, 0, 0);
      TextView badge = new TextView(context);
      badge.setText(row.number);
      badge.setTextSize(14);
      badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
      badge.setGravity(Gravity.CENTER);
      badge.setMinWidth(dp(context, 40));
      badge.setTextColor(ColorUtils.calculateLuminance(row.color) > 0.4 ? 0xff1a1a1a : Color.WHITE);
      GradientDrawable background = new GradientDrawable();
      background.setColor(row.color);
      background.setCornerRadius(dp(context, 4));
      badge.setBackground(background);
      badge.setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2));
      line.addView(badge);
      TextView headsign = new TextView(context);
      headsign.setText(row.headsign);
      headsign.setTextSize(14);
      headsign.setSingleLine(true);
      headsign.setEllipsize(android.text.TextUtils.TruncateAt.END);
      headsign.setPadding(dp(context, 10), 0, dp(context, 8), 0);
      line.addView(headsign, new LinearLayout.LayoutParams(0, -2, 1));
      // The next departure is the answer; later ones are context, so they stay muted.
      android.text.SpannableStringBuilder times = new android.text.SpannableStringBuilder();
      int muted = com.google.android.material.color.MaterialColors.getColor(line,
          com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff757575);
      for (int i = 0; i < row.times.size(); ++i)
      {
        long time = row.times.get(i);
        long minutes = Math.round((time - now) / 60000.0);
        if (i > 0) times.append("  ");
        int start = times.length();
        times.append(i == 0 && minutes <= 0 ? getString(R.string.riga_live_now)
            : i == 0 && minutes < 60 ? getResources().getQuantityString(R.plurals.riga_live_in_minutes,
                (int) minutes, (int) minutes)
            : clock.format(new Date(time)));
        times.setSpan(i == 0 ? new android.text.style.StyleSpan(Typeface.BOLD)
                             : new android.text.style.ForegroundColorSpan(muted), start, times.length(), 0);
      }
      TextView when = new TextView(context);
      when.setText(times);
      when.setTextSize(14);
      when.setFontFeatureSettings("tnum");
      line.addView(when);
      line.setContentDescription(row.number + " " + row.headsign + ", " + times);
      mRows.addView(line);
    }
  }

  private static int dp(Context context, int value)
  {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
