package app.organicmaps.transit;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.base.BaseMwmFragmentActivity;
import app.organicmaps.sdk.Framework;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RigaJourneyActivity extends BaseMwmFragmentActivity
{
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  interface Calculator
  {
    RigaJourneyService.Result calculate(String from, String to, long departure, boolean live,
                                       java.util.function.BooleanSupplier cancelled) throws Exception;
  }
  ExecutorService mWorker = WORKER;
  Calculator mCalculator;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final Runnable mRefresh = this::plan;
  private RigaTransitStore mStore;
  private Button mFromButton;
  private Button mToButton;
  private Button mWhenButton;
  private Button mMapButton;
  private MaterialSwitch mLive;
  private TextView mStatus;
  private LinearLayout mLegs;
  private RigaTransitStore.Stop mFrom;
  private RigaTransitStore.Stop mTo;
  private long mWhen; // zero means Leave now.
  private boolean mResumed;
  private volatile int mGeneration;
  private Future<?> mTask;
  private AlertDialog mPicker;
  private int mSearchGeneration;
  private RigaJourneyService.Result mResult;

  @Override protected int getContentLayoutResId() { return R.layout.activity_riga_journey; }

  @Override protected void onSafeCreate(@Nullable Bundle state)
  {
    super.onSafeCreate(state);
    mStore = new RigaTransitStore(this);
    if (mCalculator == null)
      mCalculator = new RigaJourneyService(new RigaJourneyDatabase(mStore), RigaTransitNetwork::vehicles)::calculate;
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.riga_journey_root), (view, insets) -> {
      androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    MaterialToolbar toolbar = findViewById(R.id.riga_journey_toolbar);
    toolbar.setTitle(R.string.riga_plan);
    toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
    toolbar.setNavigationContentDescription(R.string.back);
    toolbar.setNavigationOnClickListener(v -> finish());
    mFromButton = findViewById(R.id.riga_from);
    mToButton = findViewById(R.id.riga_to);
    mWhenButton = findViewById(R.id.riga_when);
    mMapButton = findViewById(R.id.riga_journey_map);
    mLive = findViewById(R.id.riga_estimates);
    mStatus = findViewById(R.id.riga_journey_status);
    mLegs = findViewById(R.id.riga_legs);
    mLive.setSaveEnabled(false);
    if (state != null)
    {
      if (state.containsKey("from"))
        mFrom = new RigaTransitStore.Stop(state.getString("from"), state.getString("fromName"));
      if (state.containsKey("to"))
        mTo = new RigaTransitStore.Stop(state.getString("to"), state.getString("toName"));
      mWhen = state.getLong("when");
      mLive.setChecked(state.getBoolean("live"));
    }
    labels();
    mFromButton.setOnClickListener(v -> chooseStop(true));
    mToButton.setOnClickListener(v -> chooseStop(false));
    mWhenButton.setOnClickListener(v -> new AlertDialog.Builder(this)
        .setItems(new String[]{getString(R.string.riga_leave_now), getString(R.string.riga_choose_time)}, (dialog, which) -> {
          if (which == 0) { mWhen = 0; labels(); plan(); }
          else chooseTime();
        }).show());
    mLive.setOnCheckedChangeListener((button, checked) -> plan());
    findViewById(R.id.riga_plan).setOnClickListener(v -> plan());
    findViewById(R.id.riga_clear_journey).setOnClickListener(v ->
        Framework.nativeSetRigaJourney(new double[0][], new boolean[0]));
    mMapButton.setOnClickListener(v -> showMap());
  }

  @Override protected void onSaveInstanceState(Bundle state)
  {
    super.onSaveInstanceState(state);
    if (mFrom != null) { state.putString("from", mFrom.id); state.putString("fromName", mFrom.name); }
    if (mTo != null) { state.putString("to", mTo.id); state.putString("toName", mTo.name); }
    state.putLong("when", mWhen);
    state.putBoolean("live", mLive.isChecked());
  }

  @Override protected void onResume()
  {
    super.onResume();
    mResumed = true;
    if (mStore != null)
      plan();
  }

  @Override protected void onPause()
  {
    mResumed = false;
    ++mGeneration;
    ++mSearchGeneration;
    mHandler.removeCallbacks(mRefresh);
    if (mTask != null)
      mTask.cancel(true);
    if (mPicker != null)
      mPicker.dismiss();
    super.onPause();
  }

  private String time(long millis)
  {
    SimpleDateFormat format = new SimpleDateFormat("EEE dd MMM HH:mm", Locale.getDefault());
    format.setTimeZone(RigaTransitStore.ZONE);
    return format.format(new Date(millis));
  }

  private void labels()
  {
    mFromButton.setText(mFrom == null ? getString(R.string.riga_choose_from) : getString(R.string.riga_from, mFrom));
    mToButton.setText(mTo == null ? getString(R.string.riga_choose_to) : getString(R.string.riga_to, mTo));
    mWhenButton.setText(mWhen == 0 ? getString(R.string.riga_leave_now) : getString(R.string.riga_depart_at, time(mWhen)));
  }

  private void chooseTime()
  {
    Calendar date = Calendar.getInstance(RigaTransitStore.ZONE);
    date.setTimeInMillis(mWhen == 0 ? System.currentTimeMillis() : mWhen);
    new DatePickerDialog(this, (picker, year, month, day) -> {
      date.set(year, month, day);
      new TimePickerDialog(this, (clock, hour, minute) -> {
        date.set(Calendar.HOUR_OF_DAY, hour);
        date.set(Calendar.MINUTE, minute);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);
        mWhen = date.getTimeInMillis();
        labels();
        plan();
      }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), true).show();
    }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show();
  }

  private void chooseStop(boolean from)
  {
    if (!mStore.exists())
    {
      mStatus.setText(R.string.riga_need_timetable);
      return;
    }
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    EditText search = new EditText(this);
    search.setSingleLine(true);
    search.setHint(R.string.riga_search_stops);
    content.addView(search);
    ListView list = new ListView(this);
    content.addView(list, new LinearLayout.LayoutParams(-1, (int) (320 * getResources().getDisplayMetrics().density)));
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(from ? R.string.riga_choose_from : R.string.riga_choose_to)
        .setView(content).setNegativeButton(android.R.string.cancel, null).create();
    mPicker = dialog;
    Runnable query = () -> {
      int generation = ++mSearchGeneration;
      String text = search.getText().toString().trim();
      mWorker.execute(() -> {
        try
        {
          List<RigaTransitStore.Stop> stops = mStore.stops(text);
          mHandler.post(() -> {
            if (!mResumed || generation != mSearchGeneration || !dialog.isShowing())
              return;
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, stops));
            list.setOnItemClickListener((parent, view, position, id) -> {
              if (from) mFrom = stops.get(position); else mTo = stops.get(position);
              dialog.dismiss();
              ++mSearchGeneration;
              labels();
              plan();
            });
          });
        }
        catch (Exception e)
        {
          mHandler.post(() -> {
            if (mResumed && generation == mSearchGeneration)
              mStatus.setText(getString(R.string.riga_journey_failed, e.getMessage()));
          });
        }
      });
    };
    search.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) { query.run(); }
      @Override public void afterTextChanged(Editable s) {}
    });
    dialog.setOnDismissListener(v -> ++mSearchGeneration);
    dialog.show();
    query.run();
  }

  private void plan()
  {
    mHandler.removeCallbacks(mRefresh);
    int generation = ++mGeneration;
    if (mTask != null)
      mTask.cancel(true);
    mResult = null;
    mLegs.removeAllViews();
    mMapButton.setVisibility(View.GONE);
    if (!mResumed)
      return;
    if (!mStore.exists()) { mStatus.setText(R.string.riga_need_timetable); return; }
    if (mFrom == null || mTo == null) { mStatus.setText(R.string.riga_choose_stops); return; }
    if (mFrom.id.equals(mTo.id)) { mStatus.setText(R.string.riga_same_stop); return; }
    String from = mFrom.id;
    String to = mTo.id;
    long departure = mWhen == 0 ? System.currentTimeMillis() : mWhen;
    boolean live = mLive.isChecked() && mWhen == 0;
    boolean futureLive = mLive.isChecked() && mWhen != 0;
    mStatus.setText(R.string.riga_planning);
    mTask = mWorker.submit(() -> {
      try
      {
        RigaJourneyService.Result result = mCalculator.calculate(from, to, departure, live,
            () -> generation != mGeneration);
        mHandler.post(() -> {
          if (!mResumed || generation != mGeneration)
            return;
          mResult = result;
          render(result, live, futureLive);
          if (mWhen == 0)
            mHandler.postDelayed(mRefresh, 30000);
        });
      }
      catch (CancellationException ignored) {}
      catch (Exception e)
      {
        mHandler.post(() -> {
          if (mResumed && generation == mGeneration)
            mStatus.setText(getString(R.string.riga_journey_failed, e.getMessage()));
        });
      }
    });
  }

  private void render(RigaJourneyService.Result result, boolean live, boolean futureLive)
  {
    String source = futureLive ? getString(R.string.riga_estimate_future) : !live ? getString(R.string.riga_scheduled)
        : result.liveFailed ? getString(R.string.riga_estimate_unavailable)
        : getString(R.string.riga_estimate_status, time(result.received), result.matched);
    RigaJourneyPlanner.Journey journey = result.journey;
    if (journey == null) { mStatus.setText(getString(R.string.riga_no_journey) + "\n" + source); return; }
    mStatus.setText(getString(R.string.riga_journey_summary, time(journey.arrival),
        (int) ((journey.arrival - journey.departure + 59999) / 60000), Math.max(0, journey.boardings - 1)) + "\n" + source);
    for (RigaJourneyPlanner.Leg leg : journey.legs)
    {
      String from = result.stop(leg.from).name + " · " + result.stop(leg.from).id;
      String to = result.stop(leg.to).name + " · " + result.stop(leg.to).id;
      String label;
      if (leg.trip == null)
        label = getString(R.string.riga_walk_leg, time(leg.departure), time(leg.arrival), leg.walkingMetres, from, to);
      else
      {
        String type = RigaModeStyle.name(this, leg.trip.type);
        label = getString(R.string.riga_ride_leg, time(leg.departure), time(leg.arrival), type,
                          leg.trip.routeName, leg.trip.headsign, from, to);
        label += "\n" + (leg.prediction == null ? getString(R.string.riga_scheduled)
            : getString(R.string.riga_estimated_leg, leg.prediction.vehicleId, leg.prediction.delaySeconds));
      }
      TextView row = new TextView(this);
      row.setText(label);
      row.setTextIsSelectable(true);
      int pad = (int) (12 * getResources().getDisplayMetrics().density);
      row.setPadding(0, pad, 0, pad);
      mLegs.addView(row);
    }
    TextView note = new TextView(this);
    note.setText(R.string.riga_map_note);
    mLegs.addView(note);
    mMapButton.setVisibility(result.lines.length > 0 ? View.VISIBLE : View.GONE);
  }

  private void showMap()
  {
    RigaJourneyService.Result result = mResult;
    if (result == null || result.lines.length == 0)
      return;
    Framework.nativeSetRigaJourney(result.lines, result.walking);
    if (mLive.isChecked() && mWhen == 0)
      RigaLiveMap.enable("");
    startActivity(new Intent(this, MwmActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    finish();
  }
}
