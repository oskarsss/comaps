package app.organicmaps.transit;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The public timetables merged into the offline store: Rīgas satiksme (city), Vivi (national trains) and the
 * ATD register of regional and intercity buses. Each archive is kept so one failed download never drops a feed.
 */
final class RigaTransitFeeds
{
  interface Resolver { String url(RigaTransitNetwork.Downloader downloader) throws Exception; }

  static final class Source
  {
    final String id;
    final String prefix;
    final boolean required;
    final List<Resolver> urls;
    Source(String id, String prefix, boolean required, Resolver... urls)
    {
      this.id = id;
      this.prefix = prefix;
      this.required = required;
      this.urls = Arrays.asList(urls);
    }
  }

  private static final String MIRROR = "https://files.mobilitydatabase.org/";

  // The Riga feed keeps unprefixed identifiers: live vehicle matching relies on its shape names.
  // Vivi and ATD are official national feeds; city feeds come from their operators or the community
  // collection at gsvalbe.id.lv, each with the Mobility Database mirror as a fallback.
  static final List<Source> SOURCES = Arrays.asList(
      new Source("rigas-satiksme", "", true, RigaTransitNetwork::catalogArchive),
      city("vivi", "pv:", "https://vivi.lv/uploads/GTFS.zip", "mdb-3385"),
      city("atd", "atd:", "https://www.atd.lv/sites/default/files/GTFS/gtfs-latvia-lv.zip", null),
      city("daugavpils", "dgp:", "https://satiksme.daugavpils.lv/saraksti/GTFS.zip", "mdb-3494"),
      city("liepaja", "lpj:", "https://www.marsruti.lv/liepaja/liepaja/gtfs.zip", "mdb-2016"),
      city("jelgava", "jlg:", "https://data.lvnap.lv/jap/gtfs-lv-jap.zip", "mdb-3495"),
      city("rezekne", "rzk:", "https://www.marsruti.lv/rezekne/rezekne/gtfs.zip", "mdb-2017"),
      city("ventspils", "vnt:", "https://gsvalbe.id.lv/pieturas/dati/gtfs/ventspils.zip", "mdb-3498"),
      city("valmiera", "vlm:", "https://gsvalbe.id.lv/pieturas/dati/gtfs/valmiera.zip", "mdb-3496"),
      city("jurmala", "jrm:", "https://gsvalbe.id.lv/pieturas/dati/gtfs/jurmala.zip", "mdb-3497"),
      city("jekabpils", "jkp:", "https://gsvalbe.id.lv/pieturas/dati/gtfs/jekabpils.zip", "mdb-3499"));

  private static Source city(String id, String prefix, String url, String mirror)
  {
    if (mirror == null)
      return new Source(id, prefix, false, d -> url);
    return new Source(id, prefix, false, d -> url, d -> MIRROR + mirror + "/latest.zip");
  }

  /** Older than this, a usable timetable is refreshed in the background. */
  static final long MAX_AGE = 7 * 24 * 3600000L;

  private RigaTransitFeeds() {}

  static void update(Context context) throws Exception
  {
    Context app = context.getApplicationContext();
    update(new File(app.getFilesDir(), "transit-feeds"), app.getCacheDir(), new RigaTransitStore(app), SOURCES,
           RigaTransitNetwork::download);
  }

  /** Downloads every feed, then imports the merged set if anything changed or the store is outdated. */
  static void update(File directory, File cache, RigaTransitStore store, List<Source> sources,
                     RigaTransitNetwork.Downloader downloader) throws Exception
  {
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Cannot create " + directory);
    boolean changed = !store.exists() || store.version() < RigaTransitStore.VERSION;
    Exception firstError = null;
    List<RigaTransitStore.Feed> feeds = new ArrayList<>();
    for (Source source : sources)
    {
      File kept = new File(directory, source.id + ".zip");
      File fresh = null;
      Exception sourceError = null;
      for (Resolver resolver : source.urls)
      {
        try
        {
          fresh = fetch(resolver.url(downloader), cache, downloader);
          break;
        }
        catch (Exception e)
        {
          android.util.Log.w("RigaTransitFeeds", "Feed " + source.id + " unavailable", e);
          sourceError = e;
        }
      }
      // A mirror that answered makes the failed primary address irrelevant.
      if (fresh == null && firstError == null)
        firstError = sourceError;
      if (fresh != null)
      {
        if (kept.isFile() && digest(kept).equals(digest(fresh)))
          fresh.delete();
        else
        {
          File staged = new File(directory, source.id + ".zip.new");
          // Copy first: the cache may be another file system, and a rename keeps the old archive until done.
          copy(fresh, staged);
          fresh.delete();
          if (!staged.renameTo(kept))
          {
            staged.delete();
            throw new IOException("Cannot store " + source.id);
          }
          changed = true;
        }
      }
      if (kept.isFile())
        feeds.add(new RigaTransitStore.Feed(source.prefix, kept, source.required));
      else if (source.required)
        throw firstError != null ? firstError : new IOException("No timetable for " + source.id);
    }
    if (changed)
      store.install(feeds);
    else if (firstError != null)
      throw firstError;
    else
      store.touch();
  }

  static boolean stale(RigaTransitStore store, long now)
  {
    return store.exists() && (store.version() < RigaTransitStore.VERSION || now - store.updated() > MAX_AGE);
  }

  private static File fetch(String url, File cache, RigaTransitNetwork.Downloader downloader) throws IOException
  {
    File file = File.createTempFile("transit-feed-", ".zip", cache);
    try (FileOutputStream out = new FileOutputStream(file))
    {
      downloader.fetch(url, out, 100 * 1024 * 1024);
    }
    catch (IOException | RuntimeException e)
    {
      file.delete();
      throw e;
    }
    return file;
  }

  private static void copy(File from, File to) throws IOException
  {
    try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to))
    {
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1)
        out.write(buffer, 0, n);
      out.getFD().sync();
    }
  }

  private static String digest(File file) throws IOException
  {
    try (InputStream in = new FileInputStream(file))
    {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536];
      int n;
      while ((n = in.read(buffer)) != -1)
        sha.update(buffer, 0, n);
      StringBuilder hex = new StringBuilder();
      for (byte b : sha.digest())
        hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
      return hex.toString();
    }
    catch (NoSuchAlgorithmException e)
    {
      throw new IOException(e);
    }
  }
}
