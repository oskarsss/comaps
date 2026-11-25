package app.organicmaps.transit;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class RigaTransitTest
{
  @Test
  public void csvHandlesBomQuotesNewlinesAndEmptyFields() throws Exception
  {
    TransitCsv reader = new TransitCsv(new StringReader("\ufeffid,name,empty\r\n1,\"A, \"\"B\"\"\nC\",\r\n"));
    assertEquals(Arrays.asList("id", "name", "empty"), reader.readRow());
    assertEquals(Arrays.asList("1", "A, \"B\"\nC", ""), reader.readRow());
    assertNull(reader.readRow());
    assertEquals(90061, TransitCsv.seconds("25:01:01"));
    try
    {
      TransitCsv.seconds("12:60:00");
      fail("Invalid minutes accepted");
    }
    catch (java.io.IOException expected) {}
  }

  @Test
  public void csvSkipsSpacesAfterSeparators() throws Exception
  {
    // The national bus feed writes a space after every comma, quoted fields included.
    TransitCsv reader = new TransitCsv(new StringReader("agency_id, agency_name, agency_url\n23, \"SIA GALSS, BUSS\", x y\n"));
    assertEquals(Arrays.asList("agency_id", "agency_name", "agency_url"), reader.readRow());
    assertEquals(Arrays.asList("23", "SIA GALSS, BUSS", "x y"), reader.readRow());
    assertNull(reader.readRow());
    // It also leaves inner quotes unescaped; read them as text rather than dropping the whole feed.
    reader = new TransitCsv(new StringReader("8972, \"Kultūras centrs \"Ulbrokas Pērle\"\", , 56.94\n"
        + "14533, \"Bērnudārzs \"Priedīte \"\", , 56.85\n102, \"SIA \"\"Lux\"\"\", x\n"));
    assertEquals(Arrays.asList("8972", "Kultūras centrs \"Ulbrokas Pērle\"", "", "56.94"), reader.readRow());
    assertEquals(Arrays.asList("14533", "Bērnudārzs \"Priedīte \"", "", "56.85"), reader.readRow());
    assertEquals(Arrays.asList("102", "SIA \"Lux\"", "x"), reader.readRow());
    assertNull(reader.readRow());
  }

  @Test
  public void returningToAnOldLeaveNowPlanRebuildsIt()
  {
    long now = 1_000_000_000L;
    // The phone was put away at 19:52 and taken out at 23:06: the 19:55 departure is long gone.
    assertTrue(RigaRouteProvider.outdatedOnReturn(now, now - 3 * 3600000L, now - 3 * 3600000L + 180000, true, false));
    // A long absence alone also replans, even if the shown departure is still ahead.
    assertTrue(RigaRouteProvider.outdatedOnReturn(now, now - 20 * 60000L, now + 60000, true, false));
    // A short glance away keeps the plan; a chosen time and a started trip are never replaced.
    assertFalse(RigaRouteProvider.outdatedOnReturn(now, now - 60000L, now + 60000, true, false));
    assertFalse(RigaRouteProvider.outdatedOnReturn(now, now - 3 * 3600000L, now - 3600000L, false, false));
    assertFalse(RigaRouteProvider.outdatedOnReturn(now, now - 3 * 3600000L, now - 3600000L, true, true));
  }

  @Test
  public void liveCoordinatesUseOperatorOrderAndRejectInvalidRows()
  {
    List<RigaVehicle> result = RigaVehicle.parse("1,22,24197320,56930187,0,214,L,16351,a-b,1335,0,\n"
        + "3,1,NaN,56930187,0,0,L,bad\n2,3,0,0,0,0,L,bad\nBad request\n"
        + "12,3,24197320,56930187,0,0,L,regional\n");
    assertEquals(1, result.size());
    assertEquals(56.930187, result.get(0).lat, 0.0000001);
    assertEquals(24.197320, result.get(0).lon, 0.0000001);
    assertEquals("16351", result.get(0).id);
    assertEquals(1, result.get(0).type);
  }

  private static void entry(ZipOutputStream zip, String name, String text) throws Exception
  {
    zip.putNextEntry(new ZipEntry(name + ".txt"));
    zip.write(text.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private File fixture(boolean withCalendar) throws Exception
  {
    File file = File.createTempFile("gtfs", ".zip", RuntimeEnvironment.getApplication().getCacheDir());
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file)))
    {
      entry(zip, "stops", "stop_id,stop_name,stop_lat,stop_lon\ns,\"Rīga, Centrs\",56.95,24.1\n");
      entry(zip, "routes", "route_id,route_short_name,route_type\nr,22,3\n");
      entry(zip, "trips", "trip_id,route_id,service_id,trip_headsign\nnight,r,regular,Airport\nremoved,r,removed,Cancelled\nadded,r,added,Special\n");
      if (withCalendar)
      {
        entry(zip, "calendar", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
          + "regular,1,1,1,1,1,1,1,20260101,20261231\nremoved,1,1,1,1,1,1,1,20260101,20261231\n");
        entry(zip, "calendar_dates", "service_id,date,exception_type\nremoved,20261119,2\nadded,20261119,1\n");
      }
      entry(zip, "stop_times", "trip_id,stop_id,departure_time,pickup_type\nnight,s,25:10:00,0\n"
          + "removed,s,01:15:00,0\nadded,s,01:20:00,0\nadded,s,01:30:00,1\n");
    }
    return file;
  }

  private File archive(String... entries) throws Exception
  {
    File file = File.createTempFile("feed", ".zip", RuntimeEnvironment.getApplication().getCacheDir());
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file)))
    {
      for (int i = 0; i < entries.length; i += 2)
        entry(zip, entries[i], entries[i + 1]);
    }
    return file;
  }

  /** A national train feed in the Vivi style: no short names, "A - B" headsigns, ids that clash with Riga's. */
  private File trainFeed() throws Exception
  {
    return archive("stops", "stop_id, stop_name, stop_lat, stop_lon\ns, Rīga, 56.946, 24.121\nj, Jelgava, 56.65, 23.73\n",
        "routes", "route_id,route_short_name,route_long_name,route_type\nr,,Rīga - Jelgava,2\n",
        "trips", "trip_id,route_id,service_id,trip_headsign,shape_id\nnight,r,regular,Rīga - Jelgava,sh\n"
        + "late,r,regular,Rīga - Jelgava,sh2\n",
        "calendar", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
        + "regular,1,1,1,1,1,1,1,20260101,20261231\n",
        "stop_times", "trip_id,stop_id,departure_time,arrival_time,stop_sequence\nnight,s,25:20:00,25:20:00,1\n"
        + "night,j,26:05:00,26:05:00,2\n",
        "shapes", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nsh,56.94600,24.12100,1\nsh,56.94601,24.12100,2\n"
        + "sh,56.94800,24.12200,3\nsh,56.95000,24.12100,4\nsh,56.95001,24.12100,5\n"
        + "sh2,56.94600,24.12100,1\nsh2,56.94800,24.12200,2\nsh2,56.95001,24.12100,3\n");
  }

  @Test
  public void mergedFeedsKeepIdentifiersApartAndNameTrainsByDestination() throws Exception
  {
    RigaTransitStore store = new RigaTransitStore(RuntimeEnvironment.getApplication());
    File city = fixture(true);
    File trains = trainFeed();
    File tram = archive("stops", "stop_id,stop_name,stop_lat,stop_lon\ns,Cietoksnis,55.9,26.5\n",
        "routes", "route_id,route_short_name,route_type\nr,1,900\n",
        "trips", "trip_id,route_id,service_id,trip_headsign\nt,r,regular,\n",
        "calendar", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
        + "regular,1,1,1,1,1,1,1,20260101,20261231\n",
        "stop_times", "trip_id,stop_id,departure_time,stop_sequence\nt,s,25:30:00,1\n");
    store.install(Arrays.asList(new RigaTransitStore.Feed("", city, true),
                                new RigaTransitStore.Feed("pv:", trains, false),
                                new RigaTransitStore.Feed("dgp:", tram, false)));
    long now = Instant.parse("2026-11-18T23:00:00Z").toEpochMilli();
    assertEquals(2, store.departures("s", now).size());
    List<RigaTransitStore.Departure> train = store.departures("pv:s", now);
    assertEquals(1, train.size());
    assertEquals("Jelgava", train.get(0).route);
    assertEquals("Jelgava", train.get(0).destination);
    assertEquals(RigaModeStyle.TRAIN, train.get(0).type);
    List<RigaTransitStore.Departure> tramDepartures = store.departures("dgp:s", now);
    assertEquals(RigaModeStyle.TRAM, tramDepartures.get(0).type);
    assertEquals("Cietoksnis", tramDepartures.get(0).destination);
    try (android.database.sqlite.SQLiteDatabase db = store.open();
         android.database.Cursor c = db.rawQuery("SELECT COUNT(*) FROM shapes WHERE shape_id='pv:sh'", null))
    {
      c.moveToFirst();
      assertEquals("Points within 10 m of the line are dropped; the bend and both ends stay", 3, c.getInt(0));
    }
    try (android.database.sqlite.SQLiteDatabase db = store.open();
         android.database.Cursor c = db.rawQuery("SELECT shape_id,(SELECT COUNT(*) FROM shapes WHERE shape_id='pv:sh2') "
                                                 + "FROM trips WHERE trip_id='pv:late'", null))
    {
      c.moveToFirst();
      assertEquals("An identical path is stored once", "pv:sh", c.getString(0));
      assertEquals(0, c.getInt(1));
    }
    assertEquals(RigaTransitStore.VERSION, store.version());
    city.delete(); trains.delete(); tram.delete();
  }

  @Test
  public void aBrokenOptionalFeedIsLeftOutWithoutLosingTheOthers() throws Exception
  {
    RigaTransitStore store = new RigaTransitStore(RuntimeEnvironment.getApplication());
    File city = fixture(true);
    File trains = trainFeed();
    File broken = archive("stops", "stop_id,stop_name,stop_lat,stop_lon\nx,X,56.9,24.1\n",
        "routes", "route_id,route_short_name,route_type\nr,7,3\n",
        "trips", "trip_id,route_id,service_id,trip_headsign\nt,r,regular,X\n",
        "calendar", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
        + "regular,1,1,1,1,1,1,1,20260101,20261231\n",
        "stop_times", "trip_id,stop_id,departure_time\nt,x,99:99:99\n");
    store.install(Arrays.asList(new RigaTransitStore.Feed("", city, true),
                                new RigaTransitStore.Feed("bad:", broken, false),
                                new RigaTransitStore.Feed("pv:", trains, false)));
    long now = Instant.parse("2026-11-18T23:00:00Z").toEpochMilli();
    assertEquals(2, store.departures("s", now).size());
    assertEquals(1, store.departures("pv:s", now).size());
    try (android.database.sqlite.SQLiteDatabase db = store.open();
         android.database.Cursor c = db.rawQuery("SELECT (SELECT COUNT(*) FROM stops WHERE stop_id LIKE 'bad:%')"
             + "+(SELECT COUNT(*) FROM routes WHERE route_id LIKE 'bad:%')"
             + "+(SELECT COUNT(*) FROM trips WHERE trip_id LIKE 'bad:%')", null))
    {
      c.moveToFirst();
      assertEquals(0, c.getInt(0));
    }
    // The city feed stays required: a broken one still fails the whole install.
    try
    {
      store.install(Arrays.asList(new RigaTransitStore.Feed("", broken, true),
                                  new RigaTransitStore.Feed("pv:", trains, false)));
      fail("Broken city timetable accepted");
    }
    catch (java.io.IOException expected) {}
    assertEquals(2, store.departures("s", now).size());
    city.delete(); trains.delete(); broken.delete();
  }

  @Test
  public void lineNamesUseTheEndAwayFromRiga()
  {
    assertEquals("Jelgava", RigaTransitStore.lineName("Rīga - Jelgava"));
    assertEquals("Tukums II", RigaTransitStore.lineName("Tukums II - Rīga"));
    assertEquals("Gulbene-Alūksne", RigaTransitStore.lineName("Gulbene-Alūksne"));
    assertEquals(RigaModeStyle.TROLLEYBUS, RigaModeStyle.base(800));
    assertEquals(RigaModeStyle.TRAIN, RigaModeStyle.base(109));
    assertEquals(RigaModeStyle.BUS, RigaModeStyle.base(700));
  }

  @Test
  public void feedUpdateFallsBackToMirrorsKeepsOldArchivesAndSkipsUnchangedImports() throws Exception
  {
    Application app = RuntimeEnvironment.getApplication();
    File directory = new File(app.getFilesDir(), "feeds-test");
    RigaTransitStore store = new RigaTransitStore(app);
    store.delete();
    byte[] city = java.nio.file.Files.readAllBytes(fixture(true).toPath());
    byte[] trains = java.nio.file.Files.readAllBytes(trainFeed().toPath());
    java.util.Map<String, byte[]> served = new java.util.HashMap<>();
    served.put("https://city", city);
    served.put("https://mirror", trains);
    List<String> asked = new java.util.ArrayList<>();
    RigaTransitNetwork.Downloader downloader = (url, out, limit) -> {
      asked.add(url);
      byte[] body = served.get(url);
      if (body == null)
        throw new java.io.IOException("HTTP 403");
      out.write(body);
    };
    List<RigaTransitFeeds.Source> sources = Arrays.asList(
        new RigaTransitFeeds.Source("city", "", true, d -> "https://city"),
        new RigaTransitFeeds.Source("trains", "pv:", false, d -> "https://primary", d -> "https://mirror"));
    RigaTransitFeeds.update(directory, app.getCacheDir(), store, sources, downloader);
    assertEquals(Arrays.asList("https://city", "https://primary", "https://mirror"), asked);
    long now = Instant.parse("2026-11-18T23:00:00Z").toEpochMilli();
    assertEquals(1, store.departures("pv:s", now).size());
    String generation = store.generation();
    // Nothing changed upstream: no new import, and the check is recorded.
    RigaTransitFeeds.update(directory, app.getCacheDir(), store, sources, downloader);
    assertEquals(generation, store.generation());
    assertFalse(RigaTransitFeeds.stale(store, System.currentTimeMillis()));
    // Trains unreachable: the kept archive is used; still nothing to import.
    served.remove("https://mirror");
    try
    {
      RigaTransitFeeds.update(directory, app.getCacheDir(), store, sources, downloader);
      fail("A failed download with nothing new must be reported");
    }
    catch (java.io.IOException expected) {}
    assertEquals(1, store.departures("pv:s", now).size());
    // The city changes: a new import still includes the kept train archive.
    File changed = archive("stops", "stop_id,stop_name,stop_lat,stop_lon\ns,Centrs,56.95,24.1\n",
        "routes", "route_id,route_short_name,route_type\nr,22,3\n",
        "trips", "trip_id,route_id,service_id,trip_headsign\nt,r,regular,Airport\n",
        "calendar", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
        + "regular,1,1,1,1,1,1,1,20260101,20261231\n",
        "stop_times", "trip_id,stop_id,departure_time\nt,s,25:40:00\n");
    served.put("https://city", java.nio.file.Files.readAllBytes(changed.toPath()));
    RigaTransitFeeds.update(directory, app.getCacheDir(), store, sources, downloader);
    assertNotEquals(generation, store.generation());
    assertEquals(1, store.departures("s", now).size());
    assertEquals(1, store.departures("pv:s", now).size());
    changed.delete();
  }

  @Test
  public void offlineImportHonorsOvernightTimesCalendarExceptionsAndAtomicFailure() throws Exception
  {
    RigaTransitStore store = new RigaTransitStore(RuntimeEnvironment.getApplication());
    File archive = fixture(true);
    store.install(archive);
    assertEquals("Rīga, Centrs", store.stops("Centrs").get(0).name);
    assertTrue(store.stops("%").isEmpty());
    long now = Instant.parse("2026-11-18T23:00:00Z").toEpochMilli(); // 01:00 in Riga on Nov 19.
    List<RigaTransitStore.Departure> departures = store.departures("s", now);
    assertEquals(2, departures.size());
    assertEquals("Airport", departures.get(0).destination);
    assertEquals(Instant.parse("2026-11-18T23:10:00Z").toEpochMilli(), departures.get(0).time);
    assertEquals("Special", departures.get(1).destination);
    assertEquals(Instant.parse("2026-11-18T23:20:00Z").toEpochMilli(), departures.get(1).time);
    File broken = File.createTempFile("broken", ".zip");
    try
    {
      store.install(broken);
      fail("Broken timetable replaced the database");
    }
    catch (java.io.IOException expected) {}
    assertEquals(2, store.departures("s", now).size());
    File noService = fixture(false);
    try
    {
      store.install(noService);
      fail("Timetable with no service calendar accepted");
    }
    catch (java.io.IOException expected) {}
    assertEquals(2, store.departures("s", now).size());
    archive.delete();
    broken.delete();
    noService.delete();
  }

  @Test
  public void failedImportAfterLoadingTablesPreservesPreviousDepartures() throws Exception
  {
    RigaTransitStore store = new RigaTransitStore(RuntimeEnvironment.getApplication());
    File valid = fixture(true);
    store.install(valid);
    File malformed = File.createTempFile("malformed-gtfs", ".zip");
    try (java.util.zip.ZipFile source = new java.util.zip.ZipFile(valid);
         ZipOutputStream output = new ZipOutputStream(new FileOutputStream(malformed)))
    {
      java.util.Enumeration<? extends ZipEntry> entries = source.entries();
      while (entries.hasMoreElements())
      {
        ZipEntry entry = entries.nextElement();
        output.putNextEntry(new ZipEntry(entry.getName()));
        try (java.io.InputStream input = source.getInputStream(entry))
        {
          byte[] buffer = new byte[4096];
          int count;
          while ((count = input.read(buffer)) != -1)
            output.write(buffer, 0, count);
        }
        if (entry.getName().equals("stop_times.txt"))
          output.write("night,s,25:99:00,0\n".getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    long now = Instant.parse("2026-11-18T23:00:00Z").toEpochMilli();
    try
    {
      store.install(malformed);
      fail("Invalid departure accepted after loading previous rows");
    }
    catch (java.io.IOException expected) {}
    assertEquals(2, store.departures("s", now).size());
    assertEquals("Airport", store.departures("s", now).get(0).destination);
    valid.delete();
    malformed.delete();
  }

  @Test
  public void serviceOriginUsesNoonMinusTwelveHoursAcrossDst()
  {
    Calendar day = Calendar.getInstance(RigaTransitStore.ZONE);
    day.setTimeInMillis(Instant.parse("2026-03-29T12:00:00Z").toEpochMilli());
    assertEquals(Instant.parse("2026-03-28T21:00:00Z").toEpochMilli(), RigaTransitStore.serviceOrigin(day));
    day.setTimeInMillis(Instant.parse("2026-10-25T12:00:00Z").toEpochMilli());
    assertEquals(Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), RigaTransitStore.serviceOrigin(day));
  }

  @Test
  public void next24HoursCanReachSecondCivilDateAcrossSpringDst() throws Exception
  {
    File archive = File.createTempFile("riga-spring", ".zip", RuntimeEnvironment.getApplication().getCacheDir());
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive)))
    {
      entry(zip, "stops", "stop_id,stop_name,stop_lat,stop_lon\ns,Centrs,56.95,24.1\n");
      entry(zip, "routes", "route_id,route_short_name,route_type\nr,22,3\n");
      entry(zip, "trips", "trip_id,route_id,service_id,trip_headsign\nt,r,added,Airport\n");
      entry(zip, "calendar_dates", "service_id,date,exception_type\nadded,20260330,1\n");
      entry(zip, "stop_times", "trip_id,stop_id,departure_time,pickup_type\nt,s,00:10:00,0\n");
    }
    RigaTransitStore store = new RigaTransitStore(RuntimeEnvironment.getApplication());
    store.install(archive);
    long now = Instant.parse("2026-03-28T21:30:00Z").toEpochMilli(); // Riga March 28, 23:30.
    long expected = Instant.parse("2026-03-29T21:10:00Z").toEpochMilli(); // March 30, 00:10.
    assertTrue(expected < now + 24 * 3600000L);
    assertEquals(1, store.departures("s", Instant.parse("2026-03-29T21:00:00Z").toEpochMilli()).size());
    List<RigaTransitStore.Departure> departures = store.departures("s", now);
    assertEquals(1, departures.size());
    assertEquals(expected, departures.get(0).time);
    archive.delete();
  }

  @Test
  public void csvRejectsExcessColumnsLargeFieldsAndTextAfterQuotes() throws Exception
  {
    String[] invalid = { String.join(",", java.util.Collections.nCopies(258, "x")),
                         "x".repeat(1024 * 1024 + 1), "\"quoted\"unexpected" };
    for (String text : invalid)
    {
      try
      {
        new TransitCsv(new StringReader(text)).readRow();
        fail("Malformed or oversized CSV accepted");
      }
      catch (java.io.IOException expected) {}
    }
  }

  private File declaredZipSizes(File source, long size, int count) throws Exception
  {
    byte[] bytes = java.nio.file.Files.readAllBytes(source.toPath());
    int modified = 0;
    for (int i = 0; i + 28 < bytes.length && modified < count; ++i)
    {
      if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2)
      {
        for (int j = 0; j < 4; ++j)
          bytes[i + 24 + j] = (byte) (size >> (8 * j));
        ++modified;
      }
    }
    assertEquals(count, modified);
    File modifiedZip = File.createTempFile("riga-bounded", ".zip");
    java.nio.file.Files.write(modifiedZip.toPath(), bytes);
    return modifiedZip;
  }

  @Test
  public void oversizedImportsAndRenameFailureKeepPreviousTimetable() throws Exception
  {
    Application context = RuntimeEnvironment.getApplication();
    RigaTransitStore store = new RigaTransitStore(context);
    File archive = fixture(true);
    store.install(archive);
    File hugeEntry = declaredZipSizes(archive, 256 * 1024 * 1024L + 1, 1);
    File hugeTotal = declaredZipSizes(archive, 200 * 1024 * 1024L, 3);
    for (File oversized : new File[]{hugeEntry, hugeTotal})
    {
      try
      {
        store.install(oversized);
        fail("Oversized ZIP accepted");
      }
      catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("too large")); }
    }
    try
    {
      new RigaTransitStore(context, 3).install(archive);
      fail("Row ceiling ignored");
    }
    catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("oversized stop_times")); }
    assertEquals(1, store.stops("Centrs").size());

    File database = new File(context.getFilesDir(), "riga-timetable.sqlite");
    File backup = new File(context.getFilesDir(), "riga-test-backup.sqlite");
    assertTrue(database.renameTo(backup));
    assertTrue(database.mkdir()); // A directory cannot be atomically replaced by a database file.
    try
    {
      try
      {
        store.install(archive);
        fail("Failed final rename was ignored");
      }
      catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Cannot install")); }
      assertFalse(new File(database.getPath() + ".new").exists());
    }
    finally
    {
      assertTrue(database.delete());
      assertTrue(backup.renameTo(database));
    }
    assertEquals(1, store.stops("Centrs").size());
    archive.delete();
    hugeEntry.delete();
    hugeTotal.delete();
  }

  @Test
  public void downloadDeadlineAlsoBoundsAContinuouslyRespondingServer() throws Exception
  {
    java.net.HttpURLConnection connection = org.mockito.Mockito.mock(java.net.HttpURLConnection.class);
    org.mockito.Mockito.when(connection.getResponseCode()).thenReturn(200);
    java.io.ByteArrayInputStream slow = new java.io.ByteArrayInputStream(new byte[1]) {
      @Override public synchronized int read(byte[] bytes, int offset, int length)
      {
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(121));
        return super.read(bytes, offset, length);
      }
    };
    org.mockito.Mockito.when(connection.getInputStream()).thenReturn(slow);
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    try
    {
      RigaTransitNetwork.fetch(connection, output, 16);
      fail("Server exceeded the elapsed download budget");
    }
    catch (java.io.IOException expected) {}
    assertEquals(0, output.size());
    org.mockito.Mockito.verify(connection).disconnect();
  }

  @Test
  public void catalogArchiveAndLiveFailuresAndTimeoutsLeaveNoPartialDownloads() throws Exception
  {
    File cache = RuntimeEnvironment.getApplication().getCacheDir();
    String catalog = "{\"success\":true,\"result\":{\"resources\":[{\"url\":\"https://data.gov.lv/riga.zip\","
                     + "\"created\":\"2026-11-01\"}]}}";
    for (String phase : new String[]{"catalog", "archive", "live"})
    {
      for (boolean timeout : new boolean[]{false, true})
      {
        int before = cache.list((directory, name) -> name.startsWith("riga-gtfs-")).length;
        RigaTransitNetwork.Downloader failed = (url, output, limit) -> {
          if (phase.equals("archive") && url.equals(RigaTransitNetwork.CATALOG))
          {
            output.write(catalog.getBytes(StandardCharsets.UTF_8));
            return;
          }
          output.write(new byte[8]);
          if (timeout)
            throw new java.net.SocketTimeoutException(phase + " never answers");
          throw new java.io.IOException(phase + " failed");
        };
        try
        {
          if (phase.equals("live"))
            RigaTransitNetwork.vehicles(failed);
          else
            RigaTransitNetwork.timetable(cache, failed);
          fail("Dependency failure was ignored: " + phase);
        }
        catch (java.io.IOException expected)
        {
          assertTrue(expected.getMessage().startsWith(phase));
          assertEquals(timeout, expected instanceof java.net.SocketTimeoutException);
        }
        assertEquals(before, cache.list((directory, name) -> name.startsWith("riga-gtfs-")).length);
      }
    }
    String oversized = "1,22,24197320,56930187,0,214,L,16351,a-b,1335,0,\n".repeat(2001);
    try
    {
      RigaTransitNetwork.vehicles((url, output, limit) -> output.write(oversized.getBytes(StandardCharsets.UTF_8)));
      fail("Oversized live fleet accepted");
    }
    catch (java.io.IOException expected) {}
  }

  @Test
  public void networkErrorsTimeoutsAndOversizedResponsesReleaseConnection() throws Exception
  {
    java.net.HttpURLConnection connection = org.mockito.Mockito.mock(java.net.HttpURLConnection.class);
    org.mockito.Mockito.when(connection.getResponseCode()).thenReturn(503);
    try
    {
      RigaTransitNetwork.fetch(connection, new java.io.ByteArrayOutputStream(), 8);
      fail("HTTP error accepted");
    }
    catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("503")); }
    org.mockito.Mockito.verify(connection).disconnect();

    org.mockito.Mockito.reset(connection);
    org.mockito.Mockito.when(connection.getResponseCode()).thenReturn(200);
    java.io.InputStream stalled = org.mockito.Mockito.mock(java.io.InputStream.class);
    org.mockito.Mockito.when(stalled.read(org.mockito.ArgumentMatchers.any(byte[].class)))
        .thenThrow(new java.net.SocketTimeoutException("simulated server never answers"));
    org.mockito.Mockito.when(connection.getInputStream()).thenReturn(stalled);
    try
    {
      RigaTransitNetwork.fetch(connection, new java.io.ByteArrayOutputStream(), 8);
      fail("Timeout accepted");
    }
    catch (java.net.SocketTimeoutException expected) {}
    org.mockito.Mockito.verify(connection).setReadTimeout(15000);
    org.mockito.Mockito.verify(connection).setConnectTimeout(15000);
    org.mockito.Mockito.verify(connection).disconnect();
    org.mockito.Mockito.verify(stalled).close();

    org.mockito.Mockito.reset(connection);
    org.mockito.Mockito.when(connection.getResponseCode()).thenReturn(200);
    org.mockito.Mockito.when(connection.getInputStream())
        .thenReturn(new java.io.ByteArrayInputStream(new byte[9]));
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    try
    {
      RigaTransitNetwork.fetch(connection, output, 8);
      fail("Oversized response accepted");
    }
    catch (java.io.IOException expected) {}
    assertEquals(0, output.size());
    org.mockito.Mockito.verify(connection).disconnect();
  }

  @Test
  @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
  public void liveMapResumesAfterQueuedCompletionAndExpiresStalledPositions()
  {
    java.util.ArrayDeque<Runnable> requests = new java.util.ArrayDeque<>();
    java.util.ArrayList<Integer> counts = new java.util.ArrayList<>();
    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    org.robolectric.shadows.ShadowLooper looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
    RigaLiveMap.enable("22");
    RigaLiveMap map = new RigaLiveMap(handler, requests::add,
        () -> RigaVehicle.parse("1,22,24197320,56930187,0,214,L,16351,a-b,1335,0,\n"
                              + "1,3,24197320,56930187,0,214,L,other,a-b,1335,0,\n"),
        (coordinates, labels) -> counts.add(labels.length));
    map.resume();
    requests.remove().run(); // Completion posted, but not yet dispatched.
    map.pause();
    looper.idle();
    assertEquals(Integer.valueOf(0), counts.get(counts.size() - 1));
    map.resume();
    assertEquals(1, requests.size()); // A paused completion must not leave loading stuck.
    requests.remove().run();
    looper.idle();
    assertEquals(Integer.valueOf(1), counts.get(counts.size() - 1));
    looper.idleFor(15, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(1, requests.size()); // Hold the next server response indefinitely.
    looper.idleFor(30, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(Integer.valueOf(0), counts.get(counts.size() - 1));
    map.pause();
    requests.remove().run();
    looper.idleFor(60, java.util.concurrent.TimeUnit.SECONDS);
    assertTrue(requests.isEmpty()); // No polling while paused.
    assertEquals(Integer.valueOf(0), counts.get(counts.size() - 1));
  }
}
