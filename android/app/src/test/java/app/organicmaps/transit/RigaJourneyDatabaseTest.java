package app.organicmaps.transit;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class RigaJourneyDatabaseTest
{
  private static void entry(ZipOutputStream zip, String name, String value) throws Exception
  {
    zip.putNextEntry(new ZipEntry(name + ".txt"));
    zip.write(value.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
  static RigaTransitStore fixture() throws Exception
  {
    RigaTransitStore store = new RigaTransitStore(RuntimeEnvironment.getApplication());
    File archive = File.createTempFile("journey", ".zip", RuntimeEnvironment.getApplication().getCacheDir());
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive)))
    {
      entry(zip, "stops", "stop_id,stop_name,stop_lat,stop_lon,parent_station\na,A,56.95,24.10,\n"
          + "b,B,56.95,24.11,p\nb2,B,56.9501,24.11,p\nc,C,56.95,24.12,\n");
      entry(zip, "routes", "route_id,route_short_name,route_type\nr,22,3\n");
      entry(zip, "trips", "trip_id,route_id,service_id,trip_headsign,shape_id\n"
          + "direct,r,added,C,riga_bus_22_a-b\nremoved,r,removed,Cancelled,riga_bus_22_a-b\n"
          + "night,r,regular,C,riga_bus_22_a-b\n");
      entry(zip, "calendar", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
          + "regular,1,1,1,1,1,1,1,20260101,20261231\nremoved,1,1,1,1,1,1,1,20260101,20261231\n");
      entry(zip, "calendar_dates", "service_id,date,exception_type\nadded,20261120,1\nremoved,20261120,2\n");
      entry(zip, "stop_times", "trip_id,stop_id,arrival_time,departure_time,stop_sequence,pickup_type,drop_off_type\n"
          + "direct,c,08:20:00,08:20:00,10,0,0\ndirect,a,08:00:00,08:00:00,1,0,0\n"
          + "direct,b,08:09:00,08:10:00,2,1,1\n"
          + "removed,a,08:00:00,08:00:00,1,0,0\nremoved,c,08:01:00,08:01:00,2,0,0\n"
          + "night,a,25:00:00,25:00:00,1,0,0\nnight,c,25:20:00,25:20:00,2,0,0\n");
      entry(zip, "shapes", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n"
          + "riga_bus_22_a-b,56.95,24.10,1\nriga_bus_22_a-b,56.95,24.11,2\nriga_bus_22_a-b,56.95,24.12,10\n");
    }
    store.install(archive);
    archive.delete();
    return store;
  }

  @Test public void importedJourneysHonorExceptionsNumericSequenceRestrictionsAndGeometry() throws Exception
  {
    RigaTransitStore store = fixture();
    assertTrue(store.supportsJourneys());
    RigaJourneyDatabase database = new RigaJourneyDatabase(store);
    long departure = Instant.parse("2026-11-20T05:55:00Z").toEpochMilli(); // 07:55 Riga.
    RigaJourneyPlanner.Timetable data = database.load(departure, () -> false);
    RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(data, "a", "c", departure,
                                                                Collections.emptyMap(), () -> false);
    assertNotNull(journey);
    assertEquals("direct@20261120", journey.legs.get(0).trip.id);
    assertEquals(Instant.parse("2026-11-20T06:20:00Z").toEpochMilli(), journey.arrival);
    assertEquals(10, journey.legs.get(0).trip.events.get(2).sequence);
    assertNull(RigaJourneyPlanner.plan(data, "b", "c", departure, Collections.emptyMap(), () -> false));
    assertTrue(data.footpaths.stream().anyMatch(p -> data.stops.get(p.from).id.equals("b")
                                                   && data.stops.get(p.to).id.equals("b2")));
    assertEquals(3, database.legPoints(data, journey.legs.get(0)).size());
    assertEquals(24.12, database.shape("riga_bus_22_a-b").points.get(2).lon, 0.000001);
    assertNull(database.shape("missing"));
    long night = Instant.parse("2026-11-19T22:55:00Z").toEpochMilli(); // 00:55 Nov20.
    journey = RigaJourneyPlanner.plan(database.load(night, () -> false), "a", "c", night,
                                     Collections.emptyMap(), () -> false);
    assertEquals("night@20261119", journey.legs.get(0).trip.id);
    assertEquals(Instant.parse("2026-11-19T23:20:00Z").toEpochMilli(), journey.arrival);
  }

  @Test public void parsedSnapshotSpeedsColdLoadsAndFallsBackWhenDamaged() throws Exception
  {
    RigaTransitStore store = fixture();
    long departure = Instant.parse("2026-11-20T05:55:00Z").toEpochMilli();
    RigaJourneyPlanner.Timetable fresh = new RigaJourneyDatabase(store).load(departure, () -> false);
    File snapshot = store.snapshotFile();
    assertTrue("A cold read stores the parsed timetable", snapshot.isFile());
    RigaJourneyPlanner.Timetable cached = new RigaJourneyDatabase(store).load(departure, () -> false);
    assertEquals(fresh.stops.size(), cached.stops.size());
    assertEquals(fresh.trips.size(), cached.trips.size());
    assertEquals(fresh.footpaths.size(), cached.footpaths.size());
    RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(cached, "a", "c", departure,
        Collections.emptyMap(), () -> false);
    assertEquals("direct@20261120", journey.legs.get(0).trip.id);
    assertFalse("Pickup restrictions survive the snapshot", journey.legs.get(0).trip.events.get(1).pickup);
    try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(snapshot, "rw"))
    { file.setLength(file.length() / 2); }
    RigaJourneyPlanner.Timetable recovered = new RigaJourneyDatabase(store).load(departure, () -> false);
    assertEquals(fresh.trips.size(), recovered.trips.size());
    assertTrue("A damaged snapshot is replaced", snapshot.length() > 0);
  }

  @Test public void oldSchemaLimitsAndInvalidReferencesFailExplicitly() throws Exception
  {
    RigaTransitStore store = fixture();
    try
    {
      new RigaJourneyDatabase(store, new RigaJourneyPlanner.Limits(2, 10, 50, 10))
          .load(Instant.parse("2026-11-20T06:00:00Z").toEpochMilli(), () -> false);
      fail("Stop ceiling ignored");
    }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("limits")); }
    File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "riga-timetable.sqlite");
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE))
    {
      db.execSQL("UPDATE stop_times SET stop_id='missing' WHERE trip_id='direct' AND stop_sequence=2");
    }
    try
    {
      new RigaJourneyDatabase(store).load(Instant.parse("2026-11-20T06:00:00Z").toEpochMilli(), () -> false);
      fail("Invalid intermediate stop accepted");
    }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("invalid stop")); }
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE))
    {
      db.setVersion(0);
    }
    assertFalse(store.supportsJourneys());
    try
    {
      new RigaJourneyDatabase(store).load(0, () -> false);
      fail("Old schema accepted");
    }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("Update")); }
  }

  @Test public void serviceFallsBackWhenLiveErrorsAndHonorsCancellation() throws Exception
  {
    RigaTransitStore store = fixture();
    RigaJourneyService service = new RigaJourneyService(new RigaJourneyDatabase(store), () -> {
      throw new java.net.SocketTimeoutException("fake timeout");
    });
    // Future fixture service may be outside today's active clock; the fallback is still explicit.
    RigaJourneyService.Result result = service.calculate("a", "c", System.currentTimeMillis(), true, () -> false);
    assertTrue(result.liveFailed);
    assertEquals(0, result.matched);
    try
    {
      service.calculate("a", "c", System.currentTimeMillis(), true, () -> true);
      fail("Cancelled request continued");
    }
    catch (java.util.concurrent.CancellationException expected) {}
  }

  @Test public void replacedTimetableCannotMixOldTripsWithNewShapes() throws Exception
  {
    RigaTransitStore store = fixture();
    RigaJourneyDatabase database = new RigaJourneyDatabase(store);
    database.load(Instant.parse("2026-11-20T05:55:00Z").toEpochMilli(), () -> false);
    File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "riga-timetable.sqlite");
    assertTrue(file.setLastModified(file.lastModified() + 1000));
    try
    {
      database.shape("riga_bus_22_a-b");
      fail("Geometry from a different timetable generation accepted");
    }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
    database.load(Instant.parse("2026-11-20T05:55:00Z").toEpochMilli(), () -> false);
    assertNotNull(database.shape("riga_bus_22_a-b"));
  }

  @Test public void geometryLimitsUseSmallFixtures() throws Exception
  {
    RigaTransitStore store = fixture();
    File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "riga-timetable.sqlite");
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE))
    {
      db.execSQL("INSERT INTO shapes SELECT 'another',shape_pt_lat,shape_pt_lon,shape_pt_sequence FROM shapes");
    }
    long departure = Instant.parse("2026-11-20T05:55:00Z").toEpochMilli();
    RigaJourneyDatabase database = new RigaJourneyDatabase(store, RigaJourneyPlanner.DEFAULT_LIMITS, 2, 10);
    database.load(departure, () -> false);
    try { database.shape("riga_bus_22_a-b"); fail("Shape point ceiling ignored"); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("oversized")); }
    database = new RigaJourneyDatabase(store, RigaJourneyPlanner.DEFAULT_LIMITS, 3, 5);
    database.load(departure, () -> false);
    assertNotNull(database.shape("riga_bus_22_a-b"));
    try { database.shape("another"); fail("Geometry cache ceiling ignored"); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("limits")); }
  }

  @Test public void journeyWindowIncludesTheSecondCivilDateAtSpringDst() throws Exception
  {
    RigaTransitStore store = fixture();
    File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "riga-timetable.sqlite");
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE))
    {
      db.execSQL("DELETE FROM calendar");
      db.execSQL("DELETE FROM calendar_dates");
      db.execSQL("INSERT INTO calendar_dates VALUES ('added','20260330','1')");
      db.execSQL("UPDATE stop_times SET departure_seconds=CASE stop_sequence WHEN 1 THEN 600 WHEN 2 THEN 960 ELSE 1200 END,"
          + " arrival_seconds=CASE stop_sequence WHEN 1 THEN 600 WHEN 2 THEN 900 ELSE 1200 END WHERE trip_id='direct'");
    }
    long departure = Instant.parse("2026-03-28T21:30:00Z").toEpochMilli(); // Riga23:30.
    RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(new RigaJourneyDatabase(store).load(departure, () -> false),
        "a", "c", departure, Collections.emptyMap(), () -> false);
    assertNotNull(journey);
    assertEquals(Instant.parse("2026-03-29T21:20:00Z").toEpochMilli(), journey.arrival);
  }

  @Test public void platformTransferCountAndCandidateWorkAreBounded() throws Exception
  {
    RigaTransitStore store = fixture();
    long departure = Instant.parse("2026-11-20T05:55:00Z").toEpochMilli();
    try
    {
      new RigaJourneyDatabase(store, new RigaJourneyPlanner.Limits(20, 10, 50, 1)).load(departure, () -> false);
      fail("Platform transfer count ignored");
    }
    catch (IOException expected) { assertEquals("Too many platform transfers", expected.getMessage()); }
    File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "riga-timetable.sqlite");
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE))
    {
      for (int i = 0; i < 4; ++i)
        db.execSQL("INSERT INTO stops VALUES (?,?,?,?,?,?)", new Object[]{"far" + i, "Repeated", 56.9 + i * 0.01, 24.1, "", ""});
    }
    try
    {
      new RigaJourneyDatabase(store, new RigaJourneyPlanner.Limits(20, 10, 50, 2)).load(departure, () -> false);
      fail("Platform candidate work ignored");
    }
    catch (IOException expected) { assertEquals("Too many platform transfer candidates", expected.getMessage()); }
  }

  @Test public void officialDownloadedFeedPlansARealJourneyWhenProvided() throws Exception
  {
    String path = System.getenv("RIGA_GTFS_FIXTURE");
    org.junit.Assume.assumeTrue(path != null && new File(path).isFile());
    RigaTransitStore store = new RigaTransitStore(RuntimeEnvironment.getApplication());
    store.install(new File(path));
    RigaJourneyDatabase database = new RigaJourneyDatabase(store);
    long departure = Instant.parse("2026-11-20T06:00:00Z").toEpochMilli();
    RigaJourneyPlanner.Timetable data = database.load(departure, () -> false);
    assertTrue(data.stops.size() > 1000);
    assertTrue(data.trips.size() > 1000);
    // Official route 22 from Abrenes iela towards the airport; select a real trip to exercise graph integration.
    RigaJourneyPlanner.Trip sample = data.trips.stream().filter(t -> t.routeName.equals("22") && t.type == 3
        && t.events.get(0).departure >= departure && t.events.size() > 10).findFirst().get();
    String from = data.stops.get(sample.events.get(0).stop).id;
    String to = data.stops.get(sample.events.get(sample.events.size() - 1).stop).id;
    RigaJourneyPlanner.Journey journey = RigaJourneyPlanner.plan(data, from, to, departure, Collections.emptyMap(), () -> false);
    assertNotNull(journey);
    assertTrue(journey.arrival <= sample.events.get(sample.events.size() - 1).arrival);
    assertFalse(database.legPoints(data, journey.legs.get(0)).isEmpty());
    System.out.println("Official GTFS: " + data.stops.size() + " stops, " + data.trips.size()
        + " active instances; " + from + " -> " + to + "; arrive=" + Instant.ofEpochMilli(journey.arrival));
    String livePath = System.getenv("RIGA_LIVE_FIXTURE");
    if (livePath != null)
    {
      File file = new File(livePath);
      long receipt = file.lastModified();
      List<RigaVehicle> vehicles = RigaVehicle.parse(new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                                               StandardCharsets.UTF_8), receipt);
      RigaJourneyPlanner.Timetable current = database.load(receipt, () -> false);
      java.util.Map<Integer, RigaJourneyPlanner.Prediction> matches = RigaJourneyMatcher.match(
          current, vehicles, receipt, database::shape, () -> false);
      assertTrue("Official live feed must match at least one active public trip and its shape", matches.size() > 0);
      int estimatedJourneys = 0;
      for (java.util.Map.Entry<Integer, RigaJourneyPlanner.Prediction> match : matches.entrySet())
      {
        RigaJourneyPlanner.Trip trip = current.trips.get(match.getKey());
        int first = match.getValue().firstBoardableEvent;
        String source = current.stops.get(trip.events.get(first).stop).id;
        String target = current.stops.get(trip.events.get(trip.events.size() - 1).stop).id;
        RigaJourneyPlanner.Journey liveJourney = RigaJourneyPlanner.plan(current, source, target, receipt,
                                                                         matches, () -> false);
        if (liveJourney != null && liveJourney.legs.stream().anyMatch(l -> l.prediction != null))
          ++estimatedJourneys;
        if (estimatedJourneys >= 3)
          break;
      }
      assertTrue("Matched live data must reach a computed itinerary", estimatedJourneys > 0);
      System.out.println("Official live snapshot " + Instant.ofEpochMilli(receipt) + ": " + matches.size()
          + "/" + vehicles.size() + " matched trips; " + estimatedJourneys + " live-estimated journeys checked");
    }
  }
}
