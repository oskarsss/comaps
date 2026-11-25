package app.organicmaps.transit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class RigaTransitNetwork
{
  interface Downloader { void fetch(String url, OutputStream output, int limit) throws IOException; }
  static final String CATALOG = "https://data.gov.lv/dati/api/3/action/package_show?id="
                                + "marsrutu-saraksti-rigas-satiksme-sabiedriskajam-transportam";
  static final String VEHICLES = "https://saraksti.lv/gpsdata.ashx?gps";

  static void download(String address, OutputStream output, int limit) throws IOException
  {
    fetch(address, output, limit);
  }

  private static void fetch(String address, OutputStream output, int limit) throws IOException
  {
    URL url = new URL(address);
    if (!url.getProtocol().equals("https"))
      throw new IOException("HTTPS required");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    if (address.startsWith("https://saraksti.lv/gpsdata.ashx"))
      connection.setRequestProperty("Origin-Custom", "saraksti.lv");
    fetch(connection, output, limit);
  }

  static void fetch(HttpURLConnection connection, OutputStream output, int limit) throws IOException
  {
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(15000);
    connection.setRequestProperty("User-Agent", "CoMaps Riga transport");
    connection.setRequestProperty("Cache-Control", "no-cache");
    try
    {
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
        throw new IOException("HTTP " + connection.getResponseCode());
      try (InputStream input = connection.getInputStream())
      {
        byte[] buffer = new byte[32768];
        int n;
        int total = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + 120000;
        while ((n = input.read(buffer)) != -1)
        {
          total += n;
          if (total > limit || android.os.SystemClock.elapsedRealtime() > deadline
              || Thread.currentThread().isInterrupted())
            throw new IOException("Download limit exceeded");
          output.write(buffer, 0, n);
        }
      }
    }
    finally
    {
      connection.disconnect();
    }
  }

  private static String text(String url, Downloader downloader) throws IOException
  {
    return text(url, downloader, 4 * 1024 * 1024);
  }

  static String text(String url, Downloader downloader, int limit) throws IOException
  {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    downloader.fetch(url, bytes, limit);
    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
  }

  static File timetable(File cache) throws IOException, JSONException
  {
    return timetable(cache, RigaTransitNetwork::fetch);
  }

  static File timetable(File cache, Downloader downloader) throws IOException, JSONException
  {
    String url = catalogArchive(downloader);
    File file = File.createTempFile("riga-gtfs-", ".zip", cache);
    try (FileOutputStream out = new FileOutputStream(file))
    {
      downloader.fetch(url, out, 100 * 1024 * 1024);
    }
    catch (IOException e)
    {
      file.delete();
      throw e;
    }
    return file;
  }

  /** The newest Rīgas satiksme archive listed in the national open data catalogue. */
  static String catalogArchive(Downloader downloader) throws IOException, JSONException
  {
    JSONObject catalog = new JSONObject(text(CATALOG, downloader));
    if (!catalog.optBoolean("success"))
      throw new IOException("Timetable catalog unavailable");
    JSONArray resources = catalog.getJSONObject("result").getJSONArray("resources");
    String latest = "";
    String url = "";
    for (int i = 0; i < resources.length(); ++i)
    {
      JSONObject resource = resources.getJSONObject(i);
      String candidate = resource.optString("url");
      String created = resource.optString("created");
      // Metadata modification dates can change for old archives. Use resource creation order.
      URL parsed = new URL(candidate);
      if (candidate.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") && created.compareTo(latest) > 0
          && parsed.getProtocol().equals("https")
          && (parsed.getHost().equals("data.gov.lv") || parsed.getHost().equals("dati.gov.lv")))
      {
        latest = created;
        url = candidate;
      }
    }
    if (url.isEmpty())
      throw new IOException("No Riga timetable archive found");
    return url;
  }

  static List<RigaVehicle> vehicles() throws IOException
  {
    return vehicles(RigaTransitNetwork::fetch);
  }

  static List<RigaVehicle> vehicles(Downloader downloader) throws IOException
  {
    List<RigaVehicle> vehicles = RigaVehicle.parse(text(VEHICLES, downloader));
    if (vehicles.isEmpty() || vehicles.size() > 2000)
      throw new IOException("No vehicle positions available");
    return vehicles;
  }
}
