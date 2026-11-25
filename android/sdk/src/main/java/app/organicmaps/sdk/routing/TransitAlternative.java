package app.organicmaps.sdk.routing;

import androidx.annotation.NonNull;

/** Compact summary of one journey in the list of transport alternatives. */
public final class TransitAlternative
{
  /** When to set off (first ride minus the walk to it), and arrival, in epoch milliseconds. */
  public final long leave;
  public final long arrival;
  public final int boardings;
  public final int walkMetres;
  /** True when the first ride is timed from live vehicle data. */
  public final boolean live;
  /** Legs in order; a PEDESTRIAN entry is a stretch on foot whose number is its whole minutes. */
  @NonNull public final TransitStepType[] modes;
  @NonNull public final String[] numbers;
  @NonNull public final int[] colors;
  /** Other departures of the first ride (any line making the same ride), epoch ms, earliest first. */
  @NonNull public long[] also = new long[0];
  /** How late (positive) or early the first ride runs against the timetable, ms; {@link #leave} includes it. */
  public long delay;

  public TransitAlternative(long leave, long arrival, int boardings, int walkMetres, boolean live,
                            @NonNull TransitStepType[] modes, @NonNull String[] numbers, @NonNull int[] colors)
  {
    this.leave = leave;
    this.arrival = arrival;
    this.boardings = boardings;
    this.walkMetres = walkMetres;
    this.live = live;
    this.modes = modes;
    this.numbers = numbers;
    this.colors = colors;
  }
}
