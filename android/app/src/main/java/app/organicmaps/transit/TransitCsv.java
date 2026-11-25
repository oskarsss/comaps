package app.organicmaps.transit;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Streaming RFC 4180 reader, including quoted newlines, the optional GTFS BOM and spaces after separators. */
final class TransitCsv
{
  private final PushbackReader mReader;
  private boolean mFirst = true;

  TransitCsv(Reader reader)
  {
    mReader = new PushbackReader(reader, 2);
  }

  List<String> readRow() throws IOException
  {
    List<String> row = new ArrayList<>();
    StringBuilder value = new StringBuilder();
    boolean quoted = false;
    boolean seen = false;
    boolean fieldStart = true; // Leading spaces of unquoted fields are padding (the ATD feed writes ", ").
    int c;
    while ((c = mReader.read()) != -1)
    {
      if (mFirst)
      {
        mFirst = false;
        if (c == '\ufeff')
          continue;
      }
      seen = true;
      if (fieldStart && c == ' ')
        continue;
      fieldStart = false;
      if (c == '"')
      {
        if (quoted)
        {
          int next = mReader.read();
          if (next == '"')
          {
            // "" is an escaped quote, except right before a comma: ATD writes "Bērnudārzs "Priedīte "",
            // where a bare inner quote is followed by the closing one.
            int after = mReader.read();
            if (after != -1)
              mReader.unread(after);
            value.append('"');
            if (after == ',')
              quoted = false;
          }
          else if (next == -1 || next == ',' || next == '\n' || next == '\r')
          {
            quoted = false;
            if (next != -1)
              mReader.unread(next);
          }
          else
            // A bare quote inside a quoted field (ATD: "Kultūras centrs "Ulbrokas Pērle"") is text.
            value.append('"').append((char) next);
        }
        else if (value.length() == 0)
          quoted = true;
        else
          throw new IOException("Invalid CSV quote");
      }
      else if (!quoted && c == ',')
      {
        if (row.size() >= 256)
          throw new IOException("Too many CSV columns");
        row.add(value.toString());
        value.setLength(0);
        fieldStart = true;
      }
      else if (!quoted && (c == '\n' || c == '\r'))
      {
        if (c == '\r')
        {
          int next = mReader.read();
          if (next != '\n' && next != -1)
            mReader.unread(next);
        }
        row.add(value.toString());
        return row;
      }
      else
        value.append((char) c);
      if (value.length() > 1024 * 1024)
        throw new IOException("CSV field too large");
    }
    if (quoted)
      throw new IOException("Unterminated CSV field");
    if (!seen)
      return null;
    row.add(value.toString());
    return row;
  }

  static int seconds(String time) throws IOException
  {
    try
    {
      String[] parts = time.split(":", -1);
      if (parts.length != 3)
        throw new NumberFormatException();
      int h = Integer.parseInt(parts[0]);
      int m = Integer.parseInt(parts[1]);
      int s = Integer.parseInt(parts[2]);
      if (h < 0 || h > 71 || m < 0 || m > 59 || s < 0 || s > 59)
        throw new NumberFormatException();
      return h * 3600 + m * 60 + s;
    }
    catch (NumberFormatException e)
    {
      throw new IOException("Invalid GTFS time: " + time, e);
    }
  }
}
