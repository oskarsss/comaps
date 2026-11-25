package app.organicmaps.transit;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RigaStopBoardTest
{
  private static final long MIN = 60000;
  private static final long T = 1_800_000_000_000L;

  private static RigaTransitStore.Stop stop(String id, String name, double lat, double lon)
  {
    return new RigaTransitStore.Stop(id, name, lat, lon);
  }

  @Test
  public void bothDirectionsOfANamedStopMatchAcrossTheStreet()
  {
    List<RigaTransitStore.Stop> near = Arrays.asList(stop("1", "Brīvības iela", 56.9600, 24.1200),
        stop("2", "Brīvības iela", 56.9603, 24.1204), stop("3", "Ģertrūdes iela", 56.9601, 24.1201));
    List<RigaTransitStore.Stop> found = RigaStopBoard.match(near, "Brivibas iela", 56.9601, 24.1201);
    assertEquals(2, found.size());
    assertEquals("1", found.get(0).id);
    assertEquals("2", found.get(1).id);
  }

  @Test
  public void anAbbreviatedFirstNameStillMatches()
  {
    List<RigaTransitStore.Stop> near = Arrays.asList(stop("1", "A.Deglava iela", 56.9400, 24.1600));
    assertEquals(1, RigaStopBoard.match(near, "Augusta Deglava iela", 56.9401, 24.1601).size());
  }

  @Test
  public void anUnnamedPlaceTakesOnlyVeryCloseStops()
  {
    List<RigaTransitStore.Stop> near = Arrays.asList(stop("1", "Origo", 56.9470, 24.1200),
        stop("2", "Stacija", 56.9480, 24.1200));
    List<RigaTransitStore.Stop> found = RigaStopBoard.match(near, "", 56.9471, 24.1200);
    assertEquals(1, found.size());
    assertEquals("1", found.get(0).id);
  }

  @Test
  public void rowsGroupByLineAndDirectionInDepartureOrder()
  {
    List<RigaTransitStore.Departure> departures = new ArrayList<>(Arrays.asList(
        new RigaTransitStore.Departure("3", "Jugla", T + 20 * MIN, 11),
        new RigaTransitStore.Departure("11", "Mežaparks", T + 5 * MIN, 0),
        new RigaTransitStore.Departure("3", "Jugla", T + 8 * MIN, 11),
        new RigaTransitStore.Departure("3", "Jugla", T + 30 * MIN, 11),
        new RigaTransitStore.Departure("3", "Jugla", T + 40 * MIN, 11),
        new RigaTransitStore.Departure("3", "Jugla", T + 20 * MIN, 11),
        new RigaTransitStore.Departure("11", "Mežaparks", T - 10 * MIN, 0),
        new RigaTransitStore.Departure("11", "Mežaparks", T + 3 * 60 * MIN, 0)));
    List<RigaStopBoard.Row> rows = RigaStopBoard.rows(departures, T);
    assertEquals(2, rows.size());
    assertEquals("11", rows.get(0).number);
    assertEquals(Arrays.asList(T + 5 * MIN), rows.get(0).times);
    assertEquals("3", rows.get(1).number);
    assertEquals(Arrays.asList(T + 8 * MIN, T + 20 * MIN, T + 30 * MIN), rows.get(1).times);
  }

  @Test
  public void tripsEndingAtThisStopAreNotDepartures()
  {
    List<RigaTransitStore.Departure> kept = RigaStopBoard.boarding(Arrays.asList(
        new RigaTransitStore.Departure("27", "Stacijas laukums", T, 11),
        new RigaTransitStore.Departure("27", "Ziepniekkalns", T, 11)),
        Arrays.asList(stop("1", "Stacijas laukums", 56.9477, 24.1201)));
    assertEquals(1, kept.size());
    assertEquals("Ziepniekkalns", kept.get(0).destination);
  }

  @Test
  public void normalDropsDiacriticsSpacesAndPunctuation()
  {
    assertEquals("adeglavaiela", RigaStopBoard.normal("A. Deglava iela"));
    assertEquals("gertrudesiela", RigaStopBoard.normal("Ģertrūdes iela"));
  }
}
