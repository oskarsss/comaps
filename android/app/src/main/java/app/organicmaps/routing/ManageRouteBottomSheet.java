package app.organicmaps.routing;

import static androidx.recyclerview.widget.ItemTouchHelper.*;

import android.app.Dialog;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.util.UiUtils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;

public class ManageRouteBottomSheet
    extends BottomSheetDialogFragment implements View.OnClickListener, ManageRouteAdapter.ManageRouteListener
{
  ManageRouteAdapter mManageRouteAdapter;
  ItemTouchHelper mTouchHelper;
  private boolean mStayChanged;
  private boolean mPlanned;

  @Override
  public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
  {
    View v = inflater.inflate(R.layout.manage_route_bottom_sheet, container, false);

    MaterialButton cancelButton = v.findViewById(R.id.btn__cancel);
    cancelButton.setOnClickListener(this);

    MaterialButton planButton = v.findViewById(R.id.btn__plan);
    planButton.setOnClickListener(this);

    MaterialButton addStopButton = v.findViewById(R.id.btn__add_stop);
    addStopButton.setOnClickListener(this);
    UiUtils.showIf(RoutingController.get().isStopPointAllowed(), addStopButton);

    RecyclerView manageRouteList = v.findViewById(R.id.manage_route_list);
    LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
    manageRouteList.setLayoutManager(layoutManager);

    mManageRouteAdapter = new ManageRouteAdapter(getContext(), Framework.nativeGetRoutePoints(), this);

    manageRouteList.setAdapter(mManageRouteAdapter);

    // Enable drag & drop in route list.
    mTouchHelper = new ItemTouchHelper(new ManageRouteItemTouchHelperCallback(mManageRouteAdapter, getResources()));
    mTouchHelper.attachToRecyclerView(manageRouteList);

    return v;
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle savedInstanceState)
  {
    Dialog dialog = super.onCreateDialog(savedInstanceState);
    dialog.setCanceledOnTouchOutside(true);

    // Expand bottom sheet dialog.
    dialog.setOnShowListener(dialogInterface -> {
      FrameLayout bottomSheet =
          ((BottomSheetDialog) dialogInterface).findViewById(com.google.android.material.R.id.design_bottom_sheet);

      if (bottomSheet != null)
        BottomSheetBehavior.from(bottomSheet).setState(BottomSheetBehavior.STATE_EXPANDED);
    });

    // Set key listener to detect back button pressed.
    dialog.setOnKeyListener((dialog1, keyCode, event) -> {
      if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP)
      {
        // Dismiss the fragment
        dismiss();
        return true;
      }

      // Otherwise, do nothing else.
      return false;
    });

    return dialog;
  }

  @Override
  public void onClick(View v)
  {
    int buttonId = v.getId();

    if (buttonId == R.id.btn__cancel)
    {
      // Close dialog if 'Cancel' button is pressed.
      dismiss();
    }
    else if (buttonId == R.id.btn__plan)
    {
      applyRoutePoints();

      // Launch route planning.
      mPlanned = true;
      RoutingController.get().launchPlanning();

      // Dismiss (close) manage route bottom sheet.
      dismiss();
    }
    else if (buttonId == R.id.btn__add_stop)
    {
      // Edits made here are kept: the new stop joins the route as it is now listed.
      if (!sameAsRoute(mManageRouteAdapter.getRoutePoints(), Framework.nativeGetRoutePoints()))
      {
        applyRoutePoints();
        mPlanned = true;
        RoutingController.get().launchPlanning();
      }
      else if (mStayChanged)
      {
        mPlanned = true;
        RoutingController.get().checkAndBuildRoute();
      }
      // The same stop-picking search as the route panel's "Add stop"; the pick adds the stop and re-plans.
      RoutingBottomMenuListener listener =
          getActivity() instanceof RoutingBottomMenuListener l ? l : null;
      dismiss();
      if (listener != null)
        listener.onSearchRoutePoint(app.organicmaps.sdk.routing.RouteMarkType.Intermediate);
    }
  }

  /** Replaces the route's points with the ones listed here, in their listed order. */
  private void applyRoutePoints()
  {
    // Get route points from adapter.
    ArrayList<RouteMarkData> newRoutePoints = mManageRouteAdapter.getRoutePoints();

    // Make sure that the new route contains at least 2 points (start and destination).
    assert (newRoutePoints.size() >= 2);

    // Remove all existing route points.
    Framework.nativeRemoveRoutePoints();

    // First, add the destination point.
    Framework.addRoutePoint(newRoutePoints.get(newRoutePoints.size() - 1));

    // Secondly, add the starting point.
    Framework.addRoutePoint(newRoutePoints.get(0));

    // And then, add all intermediate points (with no reordering).
    for (int pos = 1; pos < newRoutePoints.size() - 1; pos++)
      Framework.addRoutePoint(newRoutePoints.get(pos), false);
  }

  /** Whether {@code listed} is the route as it stands: the same places, in the same order. */
  static boolean sameAsRoute(@NonNull java.util.List<RouteMarkData> listed, @NonNull RouteMarkData[] route)
  {
    if (listed.size() != route.length)
      return false;
    for (int i = 0; i < route.length; ++i)
    {
      RouteMarkData a = listed.get(i), b = route[i];
      if (a.mIsMyPosition != b.mIsMyPosition || a.mLat != b.mLat || a.mLon != b.mLon)
        return false;
    }
    return true;
  }

  @Override
  public void onStayChanged()
  {
    mStayChanged = true;
  }

  @Override
  public void onDismiss(@NonNull android.content.DialogInterface dialog)
  {
    super.onDismiss(dialog);
    // A stay changed here counts even when the sheet is closed without "Plan".
    if (mStayChanged && !mPlanned)
      RoutingController.get().checkAndBuildRoute();
    mStayChanged = false;
  }

  @Override
  public void startDrag(RecyclerView.ViewHolder viewHolder)
  {
    // Start dragging.
    mTouchHelper.startDrag(viewHolder);
  }

  @Override
  public void onRoutePointDeleted(RecyclerView.ViewHolder viewHolder)
  {
    mManageRouteAdapter.deleteRoutePoint(viewHolder);

    mManageRouteAdapter.notifyDataSetChanged();
  }

  private static class ManageRouteItemTouchHelperCallback extends ItemTouchHelper.Callback
  {
    private final ManageRouteAdapter mManageRouteAdapter;

    public ManageRouteItemTouchHelperCallback(ManageRouteAdapter adapter, Resources resources)
    {
      mManageRouteAdapter = adapter;
    }

    @Override
    public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder)
    {
      return makeMovementFlags(UP | DOWN, 0);
    }

    @Override
    public boolean isLongPressDragEnabled()
    {
      return false;
    }

    @Override
    public boolean isItemViewSwipeEnabled()
    {
      return false;
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                          @NonNull RecyclerView.ViewHolder target)
    {
      mManageRouteAdapter.moveRoutePoint(viewHolder, target);
      return true;
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction)
    {}
  }
}
