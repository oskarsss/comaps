package app.organicmaps.transit;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import app.organicmaps.R;
import app.organicmaps.MwmActivity;
import app.organicmaps.base.BaseMwmFragmentActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class RigaTransitActivity extends BaseMwmFragmentActivity
{
  // Serializes queries and atomic timetable replacement across Activity recreation.
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static final AtomicBoolean INSTALLING = new AtomicBoolean();
  interface TimetableFetcher { File fetch() throws Exception; }
  /** Null downloads and merges every feed; tests substitute a single prepared archive. */
  Executor mWorker = WORKER;
  RigaLiveMap.Fetcher mVehicleFetcher = RigaTransitNetwork::vehicles;
  TimetableFetcher mTimetableFetcher;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private RigaTransitStore mStore;
  private TextView mStatus;
  private EditText mSearch;
  private ListView mList;
  private MaterialSwitch mLive;
  private Button mDownload;
  private RigaTransitStore.Stop mStop;
  private List<RigaVehicle> mVehicles = new ArrayList<>();
  private String mReceived;
  private boolean mResumed;
  private boolean mFetching;
  private int mGeneration;
  private final Runnable mRefresh = this::refresh;

  @Override
  protected int getContentLayoutResId()
  {
    return R.layout.activity_riga_transit;
  }

  @Override
  protected void onSafeCreate(@Nullable Bundle state)
  {
    super.onSafeCreate(state);
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.riga_root), (view, insets) -> {
      androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    MaterialToolbar toolbar = findViewById(R.id.riga_toolbar);
    toolbar.setTitle(R.string.riga_transport);
    toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
    toolbar.setNavigationContentDescription(R.string.back);
    toolbar.setNavigationOnClickListener(v -> {
      if (mStop != null && !mLive.isChecked())
      {
        mStop = null;
        refresh();
      }
      else
        finish();
    });
    mStore = new RigaTransitStore(this);
    mStatus = findViewById(R.id.riga_status);
    mSearch = findViewById(R.id.riga_search);
    mList = findViewById(R.id.riga_list);
    mLive = findViewById(R.id.riga_live);
    mDownload = findViewById(R.id.riga_download);
    mDownload.setOnClickListener(v -> download());
    findViewById(R.id.riga_journeys).setVisibility(View.GONE);
    Button map = findViewById(R.id.riga_map);
    map.setOnClickListener(v -> {
      RigaLiveMap.enable(mSearch.getText().toString().trim());
      app.organicmaps.sdk.Framework.nativeSetViewportCenter(56.95, 24.11, 12);
      startActivity(new Intent(this, MwmActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
      finish();
    });
    // Do not silently enable network polling when the user opens this screen.
    mLive.setSaveEnabled(false);
    mLive.setChecked(RigaLiveMap.isEnabled());
    mSearch.setHint(mLive.isChecked() ? R.string.riga_search_routes : R.string.riga_search_stops);
    map.setVisibility(mLive.isChecked() ? View.VISIBLE : View.GONE);
    mLive.setOnCheckedChangeListener((button, live) -> {
      map.setVisibility(live ? View.VISIBLE : View.GONE);
      if (!live)
        RigaLiveMap.disable();
      ++mGeneration;
      mStop = null;
      mVehicles.clear();
      mReceived = null;
      mSearch.setHint(live ? R.string.riga_search_routes : R.string.riga_search_stops);
      mSearch.setText("");
      refresh();
    });
    mSearch.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count)
      {
        ++mGeneration;
        mStop = null;
        if (mLive.isChecked())
          showVehicles();
        else
        {
          mHandler.removeCallbacks(mRefresh);
          mHandler.postDelayed(mRefresh, 250);
        }
      }
      @Override public void afterTextChanged(Editable s) {}
    });
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    if (mStore == null)
      return;
    mResumed = true;
    refresh();
  }

  @Override
  protected void onPause()
  {
    mResumed = false;
    ++mGeneration;
    // Completion callbacks must run to release mFetching, even while this screen is paused.
    mHandler.removeCallbacks(mRefresh);
    super.onPause();
  }

  private String time(long millis)
  {
    SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());
    format.setTimeZone(RigaTransitStore.ZONE);
    return format.format(new Date(millis));
  }

  private void rows(List<String> labels, android.widget.AdapterView.OnItemClickListener listener)
  {
    mList.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
    mList.setOnItemClickListener(listener);
  }

  private void refresh()
  {
    mHandler.removeCallbacks(mRefresh);
    if (!mResumed)
      return;
    mDownload.setEnabled(!INSTALLING.get());
    if (mLive.isChecked())
    {
      fetchVehicles();
      return;
    }
    if (!mStore.exists())
    {
      mStatus.setText(INSTALLING.get() ? R.string.riga_downloading : R.string.riga_intro);
      rows(new ArrayList<>(), null);
      if (INSTALLING.get())
        mHandler.postDelayed(mRefresh, 1000);
      return;
    }
    int generation = ++mGeneration;
    String query = mSearch.getText().toString().trim();
    RigaTransitStore.Stop stop = mStop;
    mWorker.execute(() -> {
      try
      {
        String validity = mStore.validity();
        List<String> labels = new ArrayList<>();
        List<RigaTransitStore.Stop> stops = stop == null ? mStore.stops(query) : new ArrayList<>();
        if (stop == null)
          for (RigaTransitStore.Stop item : stops)
            labels.add(item.toString());
        else
          for (RigaTransitStore.Departure departure : mStore.departures(stop.id, System.currentTimeMillis()))
          {
            String type = RigaModeStyle.name(this, departure.type);
            labels.add(getString(R.string.riga_departure, time(departure.time), type + " " + departure.route,
                                 departure.destination));
          }
        mHandler.post(() -> {
          if (!mResumed || generation != mGeneration)
            return;
          mDownload.setEnabled(!INSTALLING.get());
          mStatus.setText(getString(R.string.riga_offline, validity) + (stop == null ? "" : "\n" + stop.name));
          if (labels.isEmpty())
            mStatus.append("\n" + getString(stop == null ? R.string.riga_no_stops : R.string.riga_no_departures));
          rows(labels, stop == null ? (parent, view, position, id) -> {
            mStop = stops.get(position);
            refresh();
          } : null);
          mHandler.postDelayed(mRefresh, 60000);
        });
      }
      catch (Exception e)
      {
        mHandler.post(() -> {
          if (mResumed && generation == mGeneration)
            mStatus.setText(getString(R.string.riga_error, e.getMessage()));
        });
      }
    });
  }

  private void download()
  {
    if (!INSTALLING.compareAndSet(false, true))
      return;
    mDownload.setEnabled(false);
    mStatus.setText(R.string.riga_downloading);
    mWorker.execute(() -> {
      String error = null;
      File archive = null;
      try
      {
        if (mTimetableFetcher == null)
          RigaTransitFeeds.update(this);
        else
        {
          archive = mTimetableFetcher.fetch();
          mStore.install(archive);
        }
      }
      catch (Exception e)
      {
        error = e.getMessage();
      }
      finally
      {
        if (archive != null)
          archive.delete();
        INSTALLING.set(false);
      }
      String failure = error;
      mHandler.post(() -> {
        if (isDestroyed())
          return;
        mDownload.setEnabled(true);
        if (failure == null)
          refresh();
        else
          mStatus.setText(getString(R.string.riga_error, failure));
      });
    });
  }

  private void fetchVehicles()
  {
    if (mFetching)
    {
      mHandler.postDelayed(mRefresh, 15000);
      return;
    }
    mFetching = true;
    // Do not leave previously received coordinates presented as current during a slow request.
    mVehicles.clear();
    rows(new ArrayList<>(), null);
    if (mReceived == null)
      mStatus.setText(R.string.riga_loading);
    mWorker.execute(() -> {
      try
      {
        List<RigaVehicle> vehicles = mVehicleFetcher.fetch();
        String received = time(System.currentTimeMillis());
        mHandler.post(() -> {
          mFetching = false;
          if (!mResumed || !mLive.isChecked())
            return;
          mVehicles = vehicles;
          mReceived = received;
          showVehicles();
          mHandler.postDelayed(mRefresh, 15000);
        });
      }
      catch (Exception e)
      {
        mHandler.post(() -> {
          mFetching = false;
          if (!mResumed || !mLive.isChecked())
            return;
          // Never leave stale coordinates looking like a successful live update.
          mVehicles.clear();
          mReceived = null;
          rows(new ArrayList<>(), null);
          mStatus.setText(R.string.riga_live_error);
          mHandler.postDelayed(mRefresh, 15000);
        });
      }
    });
  }

  private void showVehicles()
  {
    String filter = mSearch.getText().toString().trim();
    List<RigaVehicle> selected = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (RigaVehicle vehicle : mVehicles)
    {
      if (!filter.isEmpty() && !vehicle.route.equalsIgnoreCase(filter))
        continue;
      selected.add(vehicle);
      String type = getString(vehicle.type == 1 ? R.string.riga_trolleybus
                             : vehicle.type == 3 ? R.string.riga_tram : R.string.riga_bus);
      labels.add(getString(R.string.riga_vehicle, type, vehicle.route, vehicle.id, vehicle.lat, vehicle.lon));
    }
    mStatus.setText(mReceived != null ? getString(R.string.riga_received, mReceived)
                                    : getString(mFetching ? R.string.riga_loading : R.string.riga_live_error));
    if (selected.isEmpty() && mReceived != null)
      mStatus.append("\n" + getString(R.string.riga_no_vehicles));
    rows(labels, (parent, view, position, id) -> {
      RigaVehicle vehicle = selected.get(position);
      String uri = "geo:" + vehicle.lat + "," + vehicle.lon + "?q=" + vehicle.lat + "," + vehicle.lon
                   + "(" + Uri.encode(vehicle.route + " · " + vehicle.id) + ")";
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(getPackageName()));
    });
  }
}
