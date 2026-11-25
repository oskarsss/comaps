# Riga public transport on Android

Use the normal map route planner: choose a start and destination from My position,
the map, address search or bookmarks, then select **Public transport**. In Riga,
the app chooses boarding and alighting stops for buses, trolleybuses and trams.
Access, exit and transfer walks use the same pedestrian street graph as the Walk tab.
Download the Latvia map before planning offline.

The Riga timetable installs automatically when first needed by the transport tab.
Installation progress and retry appear in the route panel, preserving both route points.
Subsequent planning works offline. The result shows the installed timetable's service
dates. Transport settings and timetable updates are available from the transport
gear; failed downloads preserve the installed timetable. Other cities' native transit
data remains bundled in their map files; Riga's dated timetable is stored separately.

The main route panel shows the total travel time, walking distance and ride chips.
**Steps** opens a timed itinerary in a bottom sheet. Each ride shows its line,
direction, boarding and alighting stops, and how many stops to ride. Expand the
intermediate-stop list to see the names and scheduled or estimated times; tapping
a stop focuses the existing map without changing the journey. Walking and waiting
sections stay separate. Expanded stop lists and scroll position survive time updates.
**Manage route** edits the existing route endpoints. Cancelling the route or
switching modes clears its map overlay. Other routing modes retain the native router.

**Use live times** is optional. Consent explains the network request before enabling
it. Each ride is labelled scheduled or a live estimate; unmatched trips remain
scheduled. The app matches route, direction, trip start and position against the
published timetable and shapes. Ambiguous or invalid observations cannot provide
estimates. The status shows when data was received; the feed provides no observation
timestamps. Estimates are not guaranteed arrival times.

While a live route is visible, refreshes run every 30 seconds. They preserve the
panel and camera. If a different journey is needed, a visible notice offers
**Find new route**. Requests stop in the background. No phone coordinates are sent
to the timetable or live-feed provider.

## Data sources

- Static schedules: Rīgas satiksme, published monthly under CC0 on the
  [Latvian open-data portal](https://data.gov.lv/dati/lv/dataset/marsrutu-saraksti-rigas-satiksme-sabiedriskajam-transportam).
  The app discovers ZIP resources through the CKAN `package_show` endpoint and
  selects the newest resource by creation time. Modification time is unsuitable
  because older archives can be modified after newer archives are published.
- Vehicle positions: `https://saraksti.lv/gpsdata.ashx?gps`, with the same
  `Origin-Custom: saraksti.lv` header used by the timetable website. This is the
  website's current CSV interface, rather than a versioned GTFS Realtime API.
  Its stability and redistribution terms require confirmation before distribution.
  The implementation requests the feed directly and does not redistribute it.

Neither mode sends the phone's location. Network requests reveal the user's IP
address to the respective source. Requests use HTTPS, connection/read timeouts,
and download size limits. Timetable parsing and database queries run off the UI
thread. A complete new database replaces the old database only after validation.

## Scope and validation

This is Android main-map routing integration. It does not add an iOS interface.
The underlying native transit generator remains unchanged; see
[experimental transport support](EXPERIMENTAL_PUBLIC_TRANSPORT_SUPPORT.md).

Run the offline tests from `android/`:

```sh
./gradlew :app:testWebDebugUnitTest -Px64 --max-workers=1
```

Runtime integration evidence and remaining verification are tracked locally in
`.evidence/riga-integrated-routing/`. Passing unit tests alone does not establish
that the main-map journey works on a device.
