package app.organicmaps.transit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class RigaWalkOriginTest
{
  private static final double[] HOME = {56.9400, 24.1480};

  @Test
  public void aFixWanderingWithinItsAccuracyKeepsTheOrigin()
  {
    // About 33 m north, reported ±50 m: the same spot indoors.
    assertSame(HOME, RigaRouteProvider.walkOrigin(HOME, 56.9403, 24.1480, 50));
    // About 11 m away with no accuracy: under the 20 m floor.
    assertSame(HOME, RigaRouteProvider.walkOrigin(HOME, 56.9401, 24.1480, 0));
  }

  @Test
  public void movingFurtherThanTheAccuracyMovesTheOrigin()
  {
    // About 33 m with a ±10 m fix: the rider moved.
    assertArrayEquals(new double[] {56.9403, 24.1480}, RigaRouteProvider.walkOrigin(HOME, 56.9403, 24.1480, 10), 0);
    // A ±500 m fix still only absorbs 60 m: about 89 m is a move.
    assertArrayEquals(new double[] {56.9408, 24.1480}, RigaRouteProvider.walkOrigin(HOME, 56.9408, 24.1480, 500), 0);
    assertArrayEquals(new double[] {56.9403, 24.1480}, RigaRouteProvider.walkOrigin(null, 56.9403, 24.1480, 50), 0);
  }
}
