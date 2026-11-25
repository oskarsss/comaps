package app.organicmaps.routing;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.TransitStepInfo;
import java.util.ArrayList;
import java.util.List;

public class TransitStepAdapter extends RecyclerView.Adapter<TransitStepAdapter.TransitStepViewHolder>
{
  @NonNull
  private final List<TransitStepInfo> mItems = new ArrayList<>();

  @Override
  public TransitStepViewHolder onCreateViewHolder(ViewGroup parent, int viewType)
  {
    return new TransitStepViewHolder(
        LayoutInflater.from(parent.getContext()).inflate(R.layout.routing_transit_step_view, parent, false));
  }

  /** Chips before this position are legs already behind the rider during a trip: drawn faded. */
  private int mDone;

  /** Taps on the stops of a route with their stay time (the chips that change it); null: no chip is tappable. */
  public interface OnStayClick
  {
    void onStayClick(int position);
  }

  @androidx.annotation.Nullable
  private OnStayClick mOnStayClick;

  public void setOnStayClick(@androidx.annotation.Nullable OnStayClick listener)
  {
    mOnStayClick = listener;
    notifyDataSetChanged();
  }

  @Override
  public void onBindViewHolder(TransitStepViewHolder holder, int position)
  {
    TransitStepInfo item = mItems.get(position);
    holder.bind(item);
    holder.itemView.setAlpha(position < mDone ? 0.38f : 1f);
    boolean stay = mOnStayClick != null && item.getType() == app.organicmaps.sdk.routing.TransitStepType.INTERMEDIATE_POINT
                   && item.getTimeInSec() > 0;
    if (stay)
    {
      OnStayClick listener = mOnStayClick;
      holder.itemView.setOnClickListener(v -> {
        int at = holder.getBindingAdapterPosition();
        if (at != RecyclerView.NO_POSITION) listener.onStayClick(at);
      });
      android.util.TypedValue ripple = new android.util.TypedValue();
      holder.itemView.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
        holder.itemView.setForeground(androidx.core.content.ContextCompat.getDrawable(holder.itemView.getContext(),
                                                                                     ripple.resourceId));
    }
    else
    {
      holder.itemView.setOnClickListener(null);
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
        holder.itemView.setForeground(null);
    }
    holder.itemView.setClickable(stay);
  }

  public void setDone(int done)
  {
    if (done == mDone) return;
    mDone = done;
    notifyDataSetChanged();
  }

  @Override
  public int getItemCount()
  {
    return mItems.size();
  }

  public void setItems(@NonNull List<TransitStepInfo> items)
  {
    mItems.clear();
    mItems.addAll(items);
    notifyDataSetChanged();
  }

  static class TransitStepViewHolder extends RecyclerView.ViewHolder
  {
    @NonNull
    private final TransitStepView mView;

    TransitStepViewHolder(@NonNull View itemView)
    {
      super(itemView);
      mView = (TransitStepView) itemView;
    }

    void bind(@NonNull TransitStepInfo info)
    {
      mView.setTransitStepInfo(info);
    }
  }
}
