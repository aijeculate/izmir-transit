# İzmir Transit App — Project Brief

Oct 19, 2026 · @aijec

## How to use this brief

Export this doc as Markdown, save it as `CLAUDE.md` in the root of your new Android Studio project, and start Claude Code in that folder. Claude Code reads `CLAUDE.md` automatically at the start of every session, so it never loses the context below.

Everything under "Live API" was tested against the real servers on 19 Oct 2026 around 23:45 Istanbul time. Anything not tested is marked as such.

## Current state and commands (updated 20 Oct 2026, 03:00)

**All MVP features in the brief are built.** The app runs on a real phone (Galaxy S25 Ultra, Android 17 / API 37). Everything except live data works offline.

- **Home** is the start screen. It shows favourite stops with their next three live arrivals, favourite lines, and stops near you (computed offline). A bottom bar switches between Home and the Map.
- **Line map:**
  - The route for both directions, stops, and live buses that glide between polls.
  - Active announcements, which expand from the card.
  - A ♥ favourite toggle and a "Timetable" button.
  - A my-location button: it centres on you and shows the nearby stops of any line. Tapping a stop opens it.
- **Search** covers lines and stops and is Turkish-aware. Stop results list their lines, because names repeat.
- **Stop screen:** the lines serving the stop, active announcements for those lines, and approaching buses sorted by stops remaining with an approximate ETA. It has a ♥ toggle. Tapping a line opens it.
- **Timetable:**
  - Tabs for weekday, Saturday and Sunday, and a choice of direction.
  - It opens on the timetable running now. The service day rolls over at 04:00, so at 00:30 it shows the previous day's late trips.
  - The next departure is highlighted and scrolled into view, with "in N min".
- **Weekly background refresh** (`StaticDataRefreshWorker`, WorkManager): every 7 days, Wi-Fi only, battery and storage not low.
  - It downloads the CSVs and GTFS, imports them, checks the minimum counts, then swaps the data in one transaction.
  - Rejected or failed downloads keep the existing data.
  - On an app update the shipped asset is reinstalled, so the data is never older than the build.
- **Connection hints:** the line panel shows chips such as "Connects to: Metro · Ferry", from `connections`. `ConnectionType` codes: 1 metro, 2 İZBAN, 3 ferry, 4 airport, 5 bus terminal. 4 and 5 are confirmed by their lines; 1–3 follow the dataset's documented order, and 3 matches the İskele lines.
- **Smooth bus motion:** `BusMotion` (core:data, unit-tested) tracks each bus as a distance along its route.
  - Speed comes from successive real updates, and the bus is extrapolated between them, capped at 45 s.
  - A correction glides over 1.5 s instead of snapping. Off-route buses are drawn as reported.
  - `TransitMap` redraws the bus layer at about 15 fps. Positions really change only about every 30 s, so this matters.
- **Arrival alerts ("notify me N stops away"):**
  - A bell on each arrival on the stop screen opens a sheet with 1, 2, 3 or 5 stops before.
  - `ArrivalAlertService` is a foreground service of type `specialUse`. That type must be declared in Play Console with the same justification text as in the manifest.
  - It polls `hattinyaklasanotobusleri` every 20 s through the shared pacer, with an ongoing notification and a Stop action.
  - It alerts via `ArrivalWatch` (core:data, unit-tested) and ends when the bus leaves the feed or after 60 minutes.
  - It needs `POST_NOTIFICATIONS`, which is requested on first use.
  - Tapping a notification opens the stop (`MainActivity.EXTRA_OPEN_STOP`).
- **Home-screen widget** (`FavouriteStopWidget`, Glance):
  - Shows the first favourite stop and its next three arrivals, in the calm palette, light and dark.
  - Has a refresh button and refreshes every 15 minutes via WorkManager, only while a widget exists.
  - Tapping it opens the stop.
- **Quiet rate limits:** `pollLive` ignores up to 2 consecutive 429s while data is fresh, because the daytime capture showed occasional 429s even at 1 req/s.
- **Every GitHub release must attach the APK as `izmir-transit.apk`.** The site's download button and the README link to `releases/latest/download/izmir-transit.apk`. A versioned copy is attached too.
- **Release build:** R8 shrinking and resource shrinking. There's an adaptive bus icon with a monochrome layer for themed icons.
- **Design ("calm glass", chosen by the user on 20 Oct 2026):** a fixed palette, deliberately not Material You dynamic colour.
  - Light: a soft blue-grey canvas `#EEF1F5`, white panels with a 22 dp radius, and one blue accent.
  - Dark: the same structure in deep slate.
  - Over the map, panels are slightly see-through ("glass").
  - Type is Inter, bundled as a variable font under `core/ui/res/font` (OFL licence in `core/ui/licenses`), with tabular numbers for times.
  - Build screens from the `:core:ui` components (`Panel`, `CalmRow`, `LineBadge`, `PillSearchField`, `SegmentedControl`, `ScreenHeader`, `EtaText`, `FloatingTabBar`, `PillButton`, `TextAction`, `CircleIconButton`) rather than stock Material components such as TopAppBar, Card, NavigationBar or TabRow. Stock components are what made it look generic.
  - The light map style is OpenFreeMap `positron`. The light theme hasn't been checked on the device yet (the user's phone runs dark mode).

**Added 20 Oct 2026, afternoon:**

- **Tabs:**
  - Routes: `home` (start), `map` (the Map tab), and pushed `line/{lineCode}`, `stop/{stopCode}`, `timetable/{lineCode}`, `settings`.
  - Home pops to `home`. Map pops to `map` if it is in the stack; otherwise it pops to home and pushes `map`.
  - The Map tab's line lives in the `MapSelection` singleton. Null means the all-lines overview.
- **Overview:**
  - With no line chosen, the map shows every bus route faintly in the accent colour.
  - Metro, tram and İZBAN are drawn bold in their colours, and ferries dashed.
  - `LineRepository.overviewShapes()` simplifies to 25 m and caches.
- **Settings screen** (`:feature:settings`, `SettingsRepository` in DataStore): system/light/dark theme, five accent palettes (`AccentTheme`), and a "search bar at the bottom" option. Status-bar icons follow the app theme.
- **Phase 2 (metro, tram, İZBAN, ferries):**
  - **Codes are unique across modes.** Bus codes are plain numbers. Others carry a prefix:
    - metro: `M1` with stations `M{id}`;
    - tram: `T-KONAK`, `T-KARSIYAKA`, `T-CIGLI_IC`, `T-CIGLI_DIS`, `T-TURUNCU` ("Ring"), with stops `T{id}`;
    - İZBAN: `IZBAN` with stations `I{id}`, where id is the izban.com.tr station id;
    - ferries: one line per pier pair, `F-{a}-{b}` (displayed as "Vapur", named "Konak – Karşıyaka"), with piers `F{id}`.
  - DAOs therefore take no mode. `isBusCode()` decides whether live calls apply.
  - **Schema v2:**
    - `lines.display_code` and `lines.color` (ARGB, null for buses).
    - `stop_departures` table: every station's timetabled departures, with a headsign.
    - Destructive fallback is safe, because the shipped asset is reinstalled on each update.
  - **`RailImporter`** reads the operators' GTFS:
    - Sources: `izmirmetro.com.tr/gtfs/rail-metro-gtfs.zip`, `tramizmir.com/gtfs/rail-tramizmir-gtfs.zip`, `izban.com.tr/gtfs/rail-izban-gtfs.zip`. None has shapes.txt.
    - Station order merges all trips topologically. Routes are drawn through the stations.
    - **İZBAN times come from izban.com.tr instead** (`IzbanImporter`), because its GTFS is a 2023 snapshot (the calendar ended 2025-01-01). The feed now only supplies station coordinates, matched by name.
      - The [Sefer Saatleri page](https://www.izban.com.tr/Sayfalar/SeferSaatleri.aspx?MenuId=24) is an ASP.NET form: day (1 Mon … 7 Sun), from-station, to-station and date, posted with the page's `__VIEWSTATE`/`__EVENTVALIDATION` and its session cookie.
      - It returns (departure, arrival) pairs only, with no train numbers or destinations.
      - `OtherModesDownloader.downloadIzban` queries every adjacent pair, both ways, for days 1, 6 and 7: 228 posts, 1 s apart, about 4 minutes. `./gradlew :tools:importer:downloadIzban` saves them to `research/raw/izban-web/`.
      - `IzbanImporter.chainTrips` rebuilds whole trips: a train arriving at a station that leaves again within 2 minutes is the same train. That gives each departure its destination. The breaks were checked against the data: they are real termini (Menemen, Cumaovası, Tepeköy, Alsancak, Gaziemir) plus the separate Tepeköy–Sağlık shuttle.
      - The line runs Aliağa → Sağlık (39 stations, no Selçuk branch on the site); southbound is outbound.
      - The site lists after-midnight trains first ("00:05"); they are stored as 1440+ on the same day.
  - **`FerryImporter`** reads the İzdeniz open API (`openapi.izmir.bel.tr/api/izdeniz/iskeleler`, `iskelesefersaatleri/{pier}/{day}`), saved as `research/raw/ferry/pier_{id}_day_{1|6|7}.json`.
    - Day 1 stands for every weekday; Mon–Fri differ by a sailing or two.
    - Urla, Güzelbahçe and Mordoğan are seasonal and have 0 sailings in October.
  - The weekly refresh also updates rail and ferries via `OtherModesDownloader`, one mode at a time. A failure keeps that mode's old data. İzdeniz calls go through the shared pacer.
  - **UI:**
    - Rail and ferry badges wear their line colour.
    - The stop screen shows a departure board for stations and piers, refreshed each minute, with no live arrivals and no alert bells.
    - The line panel shows the next 3 departures from each direction's first stop instead of live buses.
    - Home favourites show station boards.
    - The widget uses the first favourite *bus* stop only.
    - The weekly refresh scrapes İZBAN too; if the site fails, the existing İZBAN data is kept (the 2023 GTFS times are never used again).

**Added 21 Oct 2026 (after real-world use):**

- **Map is Google Maps** (maps-compose 8.3.1, play-services-maps 20.0.0). The user asked for it because OSM is missing roads in İzmir.
  - The map lives in `:core:map` (`TransitMap`, marker artwork in `MapIcons`) and is shared by the line map and the planner.
  - maps-compose 8.4+ needs Kotlin 2.4; 8.3.x is built for Kotlin 2.3, which the 2.2 compiler can read.
  - **Keys:** `MAPS_API_KEY=…` and `PLACES_API_KEY=…` in `local.properties`, never committed. The Maps key becomes the manifest `com.google.android.geo.API_KEY`; the Places key becomes `BuildConfig.PLACES_API_KEY` and falls back to the Maps key. Both are set up on the user's Google Cloud project, restricted to the app (debug SHA-1). Place search is restricted to the İzmir province rectangle.
  - **Without a key the map shows nothing at all, not even our overlays.** Enable Maps SDK for Android and Places API (New), and restrict the key to package `com.aijec.izmirtransit` plus the signing SHA-1. The debug SHA-1 is `56:1C:9E:24:1B:14:64:C0:25:E7:D5:28:21:F6:B6:E5:3A:7D:90:C2`.
- **Look:**
  - Routes have a casing and chevrons stamped along them (direction).
  - Stops are rounded mode-pictogram badges (rings when zoomed out), and buses are pictogram discs.
  - Rail and ferry use the colours from `TransitColors.forMode`.
- **Stops in view:** at zoom ≥ 15 every stop in the viewport is drawn (`StopRepository.inArea`, max 300). Tapping a stop opens a preview card with lines, Open and Directions here. Long-press drops a pin.
- **Layers button:** bus lines, metro, tram, İZBAN, ferry and stops, saved in DataStore as the hidden set (`MapLayer`, `SettingsRepository.setMapLayer`).
- **Trip planner** (`:feature:planner`, engine in `core/data/routing`):
  - **Engine:** RAPTOR over a per-service-day `TransitNetwork`, built from the DB by `NetworkRepository` (about 0.6 s on desktop, cached). A plan takes 5–30 ms.
    - Bus times along a route are estimated at 300 m/min from first-stop departures.
    - Rail and ferry use station departures. Hop times are medians of departure-to-next-departure gaps, counting only trains that continue.
    - Trains ending early (by headsign) form their own shorter pattern.
    - Walking transfers go up to 400 m at 75 m/min with a 1.25 detour factor. Access and egress go up to 1.2 km (2 km if nothing is closer).
    - It returns one journey per number of rides. Each needs to be at least 5 min faster than one with fewer rides, or at most 2 min slower while walking 800 m less. Walking is offered when under 2.5 km.
    - Limit: only the earliest arrival per stop is kept, so an equally fast trip with less walking can be missed (Gaziemir → Menemen picks a 2.1 km walk). Multi-criteria RAPTOR would fix it.
    - Late at night it adds the first trips of the next service day.
  - **Destinations:** Places API (New) REST autocomplete and details (`PlacesRepository`), signed with `X-Android-Package` and `X-Android-Cert`, with one session token per search. Our stops are always searched as well.
  - **Smoke test:** `./gradlew :tools:importer:planSmoke` plans real trips on the built DB.
- The timetable crash (duplicate lazy-grid keys when two departures share a minute) is fixed. Rail and ferry timetables have a "departing from" station picker.

**Added 21 Oct 2026, night:**

- **One direction at a time** is the default for a chosen line. The panel shows "Towards X" with a **Switch direction** button; only that direction's route, stops and buses are drawn. The setting "Show both directions" (`AppSettings.showBothDirections`) brings back blue and orange together. Loop lines with one direction show no switch.
- **Operator colours** (`ModeBrands` in `:core:ui`, from the logos; colours only, logos never used):

  | Mode | Primary | Secondary |
  | --- | --- | --- |
  | Bus (ESHOT) | red `#D71920` | white |
  | Metro | blue `#2A52A3` | red `#E3262E` |
  | Tram | green `#3DA548` | blue `#233F95` |
  | İZBAN | blue `#0B5BA8` | white |
  | İzdeniz | navy `#1D3C8F` | sky `#29A9E0` |

  - `ModeIcon` is a pictogram tile with the second colour as a stripe. It is used in the map legend, the layers panel, search results, favourites, nearby stops, stop headers and planner steps.
  - `LineBadge` takes a `mode`: strong is the primary with a secondary rim, soft is a tint with legible "ink" text.
  - Map stop badges use the mode primary. Map routes keep the official per-line colours.
- **Interchanges:** `StopRepository.transfers(stop)` lists other-mode rail and ferry stations within 350 m (Hilal's Metro and İZBAN are 285 m apart), shown as "Aktarma" on station screens and as chips on the map's stop card. Bus stops show "Stations nearby".
- **Station boards late at night** show the next morning's first departures instead of "none".
- **Liquid glass style** (`UiStyle`, Settings → Style):
  - Every surface goes through `Modifier.calmSurface()`.
  - Glass is a translucent gradient fill, a top sheen and a bright gradient rim. Google's map is a separate surface Compose can't blur behind, so there is no blur. Panes over the map are more opaque so their text stays legible.
  - Other screens get a colour wash behind them (`glassBackdrop`, on the root in `AppNavHost`, with the canvas made transparent).
- **Performance:** all CPU work moved off the main thread in the repositories:
  - overview decode and simplify
  - route decode and measure
  - `inArea`, `transfers`, and the trip network build
  - live flows (`flowOn(Default)`)

  Stops in view are capped at 200 markers. Panning stays at 5 ms per frame with no slow frames on the S25 Ultra.
- Notification and widget intents use `CLEAR_TOP | SINGLE_TOP`, so a tap reuses the open app (`onNewIntent`) instead of recreating it. Note: `adb shell am start --es openStop …` on a running app only brings the task to the front (result code 3) and never delivers the extra. That's a test artefact, not a bug.

**Added 21 Oct 2026, late night:**

- **The camera never moves on its own after the user does.** `TransitMap(fitKey = …)` fits routes or a plan only when the key changes: a new line, or a newly selected journey. The fitted key survives in `rememberSaveable`. The Map tab passes `memoryKey = "map-tab"`, so its camera and fitted line live in a process-wide `CameraMemory`; going Home and back keeps the zoom. Switching direction keeps the zoom.
- **Stops in view cover the whole screen** plus a 15 % margin, computed from the map's pixel size with `projection.fromScreenLocation`. Google's `visibleRegion` excludes the content padding under the cards and the search bar, which made stops vanish there. The limit is 260.
- Google's own transit station icons are hidden (`MapStyleOptions`, `transit.station` off). They duplicated our badges. The dark colour scheme still applies.
- **Dark liquid glass** follows the user's references (iOS 26 tab bar, One UI 9 concept): no sheen, a faint white fill, and a thin diagonal-gradient rim. Behind it is a near-black backdrop with a deep blue glow bottom-right and violet left. Selected segments and tabs use translucent white (`Calm.selectedFill()`). Light glass is unchanged; the user likes it.
- **AMOLED** is a fourth theme mode (`ThemeMode.AMOLED`): a pure black canvas, near-black panels (`AmoledCalm`), `LocalAmoled` for dimmer glass glows, and the dark map.
- **First-run tutorial** (`app/onboarding/Onboarding.kt`): five pager pages (welcome with the mode tiles, map, line, planner, location permission). It sits behind `AppSettings.onboardingDone`, and Settings has "Show the tutorial again". The location permission is only requested from the last page, after explaining why.

**Release prep (21 Oct 2026):**

- **Map:** fullscreen button (hides search, cards and the tab bar; Back exits). The layers menu is a compact 2×3 chip grid.
- **Lines opened from a stop** show the direction serving that stop, and their timetable starts at that stop (`?stop=` nav argument on `line/` and `timetable/`).
- **Fixes:**
  - The planner's cached network is cleared after the weekly refresh.
  - Loop lines' timetables show only their one direction.
- **Open source:**
  - GPL-3.0 (`LICENSE`), `README.md`, `PRIVACY.md`.
  - Landing page in `docs/` (GitHub Pages, TR/EN, screenshots in `docs/screenshots`).
  - CI runs unit tests (`.github/workflows/build.yml`).
- **Release build:**
  - `./gradlew :tools:importer:downloadData` fetches all sources for a fresh clone.
  - Signing reads git-ignored `keystore.properties` (unsigned without it). Version 1.0.0.

**Added 22 Oct 2026:**

- **App icon:** the Konak clock tower over the bay (the user's artwork). The adaptive icon uses the light version (`#EEF1F5` background) by default and the dark version (`#0E1217`) from `drawable-night`/`values-night`, so it follows the phone's dark mode where the launcher supports it. `ic_launcher_monochrome` (cut-out tower and waves) is the themed-icon layer and the notification small icon. The site and README use `docs/icons/icon-{light,dark}.svg` by `prefers-color-scheme`.
- **Notifications** are asked for only when the user sets an alert (stop screen bell). If Android won't show the dialog any more, or notifications are blocked, a dialog offers the app's notification settings.
- **Direction chevrons:** Google stamps the texture with the image's bottom toward the polyline's end, so the chevron bitmap points down (the old up-arrow pointed backwards). The selected line is 8 dp (11 dp casing) and the chevron fills its width on a tall canvas, so arrows are larger and spaced out.
- **Favourites edit mode** on Home ("Edit" next to Favourites): up, down and remove per stop and line (`FavouritesRepository.moveStop/moveLine`).
- **Update checker** (`core/data/update/UpdateChecker`): `GET api.github.com/repos/aijeculate/izmir-transit/releases/latest`, numeric version compare against `BuildConfig.VERSION_NAME`, downloads the `izmir-transit.apk` asset. Automatic check at most once a day on app start (`AppSettings.autoCheckUpdates`, `lastUpdateCheck`); "Later" stores `skippedUpdate`. Settings has the toggle, "Check for updates", a GitHub link and the version line. **Release tags must be `vX.Y.Z` and versionName `X.Y.Z`.**
**Added 23 Oct 2026:**

- **Live bus direction** comes from motion, not the feed's `Yon` (`DirectionResolver`, per polled line): near one direction's route → that one; on a shared street → the one it moves forward along; else the last known, else `Yon`. `Yon` was wrong for some lines (233), which hid their buses in the one-direction map while the stop screen showed them.
- **Rail short-turns:** a departure row opens `line/{code}?stop=&toward={headsign}`; `LineRepository.tripRoute` trims the route from the first station trains to that headsign leave from, to the headsign station. Next departures in the panel count only those trains.
- **Favourites** are one ordered list in DataStore (`favourites_order`, "s:CODE,l:CODE"); the old `favourite_stops`/`favourite_lines` keys are read once as stops then lines. Home groups neighbouring lines into one panel.
- **Metro and tram times** come from `metrotramapi.iztek.tr/api/v1/line-schedules` and `tramline-schedules` (the API behind izmirmetro.com.tr/sefer-plani): service bands per line and day ("06:30–09:00 every 4"), no exact times. `RailFrequencies` parses them; `RailImporter` lays each banded line-day's trips on the bands with the GTFS's most common stop sequence and running times, longer patterns first (the metro's trunk every 4 includes the every-8 Narlıdere trains). The bands are saved beside the GTFS zip as `{metro,tram}-frequencies.json` (refresh and downloadData). The GTFS terminus is "Kaymakamlık", the site's "Narlıdere Kaymakamlık" (`RailFrequencies.sameEnds`).
- **Holidays** (`Holidays`): fixed national days plus Ramazan/Kurban Bayramı run the Sunday timetable (`serviceDayFor`); arife half days keep theirs. 2026–2027 bayrams are built in; later years come from `docs/holidays.json` on the website, fetched by the weekly worker into `filesDir/holidays.json` (`HolidayFile`). **Add each new year's bayrams to docs/holidays.json.** Boards and timetables show a notice.
- **Trip planner:** McRAPTOR (bags of (arrival, walking) per round, 100 m walking tolerance), then a short list: fastest, fewer rides, or ≥500 m less walking within 10 min and at most one more change. `planArriveBy` binary-searches the latest start. ~50–130 ms per plan on desktop. Time control: now / leave at / arrive by, today or tomorrow. Home, work and recents in DataStore (`SavedPlacesRepository`); Home's Where-to card has Go home / Go to work.
- **Widgets** are per instance: `WidgetConfigActivity` (APPWIDGET_CONFIGURE, `reconfigurable|configuration_optional`) stores the stop per app-widget id (`WidgetStops`, SharedPreferences); stations show next departures as clock times.
- **Trip tracking:** "Start trip" on an expanded journey (asks for precise location and notifications then). `TripTrackingService` (foreground, type location) feeds `TripProgress` (core:data, tested) with 5 s fixes; stops left come from projecting onto the ride's path, and underground or with accuracy > 150 m from the timetable. Heads-up alerts for get off next / now (with the change) and arrival.
- **Night lines** (910, 920, 930, 200, 940…) run 00:15–05:00. Every bus departure before 04:00 is stored as 1440+ (end of the service day); before, they were stored as early morning and never matched "now" after midnight, so the planner and timetables skipped them. Departures from 04:00 belong to the new service day.
- **One-direction line map:** when every live bus is going the other way, the panel says "No buses towards X · N towards Y" instead of "no buses". This, not `Yon`, was most likely the line 233 report: the line opened towards Konak while the bus was heading the other way (verified with 910 at night, 23 Oct).
- **Dark liquid glass is "Frosted Night"** (user's pick from the glass mockups, 23 Oct): panes are an even white frost at 8 % with a 14 % hairline rim lit to 30 % along the top; selected tabs/segments white 16 %; the backdrop is #06080D with blue (bottom right), violet (left) and teal (top right) glows, dimmed to 45 % on AMOLED. There is no real blur, so floating bars and panes over the map use a dense smoke (#121822 at 92 %, AMOLED #07090D at 94 %): at 80 % list text showed through the tab bar.
- **Motion** (from the Frosted Night motion mockups, 23 Oct; shared pieces in `core/ui/Motion.kt`, all off when Android's "Remove animations" is on, `rememberReducedMotion`):
  - Home cards rise and fade in one after another the first time Home is shown (`rememberEntrance` / `Modifier.entrance`).
  - Live minutes roll like a departure board (`RollingText` in `EtaText`).
  - The tab pill slides on a spring, leading edge faster than the trailing one so it stretches.
  - Map: the selected line's static stamped chevrons are replaced by flowing chevron markers (`FlowingChevrons`, one per 500 m, at most 24, moved every 66 ms; 5 ms median frames on the phone). Routes fade in on a line or direction switch; the direction label rolls; the line panel resizes on a spring.
  - A tracked trip's GetOffNext/GetOffNow shows `TripAlertBanner` at the top of the app on a spring with a glowing rim; tap hides it until the next alert.
- **"Just left" on station and pier boards** (not bus stops, which are live): the last scheduled departure per line and direction in the past 20 minutes (`StopRepository.recentDepartures`), counting up by the second from its scheduled minute, with a note that trains and ferries often run late so it may not have come yet. Asked for by the user because Metro and İZBAN run a few minutes late. A departure in the current minute stays on the main board as "now".

| Module | What it holds |
| --- | --- |
| `:core:model` | Plain JVM. Domain types (`Line`, `Stop`, `LiveBus`, `LiveSnapshot`, …) and geo helpers: `MeasuredPolyline`, `PolylineCodec`, Douglas–Peucker simplification. |
| `:core:database` | Room KMP with JVM and Android targets: entities, DAOs, `IzmirDatabase`. `AndroidDatabases.prepackaged` installs the shipped DB from assets the first time Room opens a connection (off the main thread) and again after each app update. `JvmDatabases` serves the tools and tests. |
| `:core:data` | Plain JVM. Parsers, the İztek client, `RequestPacer` (1 req/s app-wide), `GhostFilter`, `EtaEstimator`, `pollLive`, `EshotImporter`, `EshotDownloader` + `StaticDataRefresher`, and the repositories, including `FavouritesRepository` (DataStore) and `TimetableRepository` (`serviceMoment`, `localNow` in Europe/Istanbul, `formatClock`). |
| `:core:location` | `LocationProvider`: a one-shot Fused Location request with balanced accuracy, a 10 s timeout and a last-known fallback. |
| `:core:ui` | `IzmirTheme`, `TransitColors`, `LineBadge`, `AnnouncementCard`, `rememberNow`/`formatAge`, `liveErrorText`. |
| `:feature:home` | Favourites with live arrivals (first 5 stops) and nearby stops, including the permission flow. |
| `:feature:linemap` | `LineMapViewModel`, `LineMapScreen`, and `TransitMap` (MapLibre in `AndroidView`; GeoJSON sources written by hand; layers for routes, stops, nearby stops, the user dot and buses). |
| `:feature:stop` | `StopViewModel` / `StopScreen`. |
| `:feature:timetable` | `TimetableViewModel` / `TimetableScreen`. |
| `:app` | `AppNavHost` (bottom bar; routes `home`, `line?lineCode=`, `stop/{stopCode}`, `timetable/{lineCode}`), Hilt `DataModule`, `StaticDataRefreshWorker`, the icon, and `copyTransitDatabase`, which builds the DB asset from `:tools:importer`. |
| `:tools:importer` | `run` builds `build/izmir-transit.db`. `liveSmoke` does one real poll through the repositories. `refreshSmoke` runs the real weekly refresh into a temporary DB (about 24 s). |
| `build-logic` | Convention plugins: `izmir.jvm.library`, `izmir.kmp.library`, `izmir.android.application`, `izmir.android.library`, `izmir.android.compose`, `izmir.android.hilt`. |

**Toolchain (verified working together):**

- Gradle 9.6.1 and AGP 9.4.1, which needs Gradle 9.6 or newer. AGP 9 has built-in Kotlin, so don't apply `org.jetbrains.kotlin.android`.
- Kotlin 2.2.21, and KSP 2.3.12. KSP 2.2.x crashes on AGP 9's KMP Android target.
- Room 2.8.4, Hilt 2.59.2, Compose BOM 2026.06.01, Navigation 2.9.8, MapLibre 13.6.0, DataStore 1.1.7, WorkManager 2.10.5, play-services-location 21.3.0.
- compileSdk/targetSdk 37, minSdk 26.

Commands (use Git Bash, with `JAVA_HOME` set to the Temurin 21 JDK):

```bash
./gradlew test                                  # 97 JVM tests, fixtures are real API responses (night_* and day_*)
./gradlew lintDebug                             # 0 errors; known warnings: LogNotTimber, ObsoleteSdkInt (aapt2 rejects plain mipmap-anydpi)
./gradlew :tools:importer:run                   # research/raw (+ rail/*.zip, ferry/*.json) → build/izmir-transit.db (~13 MB, ~10 s)
./gradlew :tools:importer:refreshSmoke          # real download + import + validation
./gradlew :tools:importer:downloadIzban         # current İZBAN timetable → research/raw/izban-web (~4 min)
./gradlew :tools:importer:planSmoke             # plans a few real trips with the trip planner
./gradlew :app:assembleDebug :app:assembleRelease
```

The release APK is unsigned; a Play upload key is the user's decision. For a local R8 test, sign it with the debug key: run `zipalign -p 4`, then `java -jar build-tools/36.0.0/lib/apksigner.jar sign --ks ~/.android/debug.keystore …` (the `.bat` wrapper fails from Git Bash). The release APK is 59 MB because it bundles 4 ABIs; an App Bundle on Play delivers only one.

**Install release builds on the phone, not debug.** A debuggable build runs mostly in ART's interpreter, and the map stuttered badly (median frame 150 ms when panning; simpleperf showed 99% interpreter frames). The R8 release build, signed with the debug key so the Maps key's SHA-1 still matches, pans at 5 ms per frame with no slow frames:

```bash
./gradlew :app:assembleRelease
cd app/build/outputs/apk/release && rm -f app-release-aligned.apk app-release-signed.apk
"$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/zipalign.exe" -p 4 app-release-unsigned.apk app-release-aligned.apk
"$JAVA_HOME/bin/java" -jar "$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/lib/apksigner.jar" sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out app-release-signed.apk app-release-aligned.apk
adb install -r app-release-signed.apk
```

Release builds are `profileable`, so `simpleperf record --app com.aijec.izmirtransit` still works. Measure jank with `adb shell dumpsys gfxinfo com.aijec.izmirtransit` after `reset` and a few `input swipe`s.

Map performance rules, learned the hard way:

- Never enable StrictMode disk detection: Google Maps reads disk on every camera move.
- The bus network is drawn by `RouteTileProvider` (a TileOverlay), not ~900 polylines.
- Moving vehicles live in their own composable, so their 15 fps updates don't recompose every marker.

**Device testing:** use the user's phone over wireless adb, **not the emulator**. The user asked for this, and the emulator is too slow here.

- **adb's default port 5037 is unusable on this machine** because Windows has reserved TCP 5024–5123. Always set `ANDROID_ADB_SERVER_PORT=15037`.
- In Git Bash, also set `MSYS_NO_PATHCONV=1` for any `adb shell` command that takes a device path such as `/sdcard`.
- Never grant permissions or unlock the phone on the user's behalf.

```bash
export ANDROID_ADB_SERVER_PORT=15037
adb connect <ip:port from `adb mdns services`>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`research/raw/` holds the downloaded CSVs and the GTFS zip. It is git-ignored, and the app build needs it; re-download from the URLs below, or with `EshotDownloader`.

**Verified on the device so far:**

- Line map, search, the stop screen, navigation, favourites (they persist and show live arrivals), and the Turkish and English text.
- The release (R8) build starts without crashing.

**Not yet verified on the device** (the phone locked at 03:00):

- the timetable screen;
- announcements on the line card and the stop screen;
- the my-location button and nearby stops on the map and Home (the user must grant the permission);
- tapping a stop on the map;
- the release build's UI;
- the worker running on the phone (its logic is verified on the desktop by `refreshSmoke`).

**Not yet verified on the device:**

- arrival alerts end to end (the notification permission isn't granted on the user's phone);
- the widget on the home screen (a user has to place it);
- the light theme.

**Later (from the brief):**

- Phase 2 follow-ups: the light theme, and the metro, tram and ferry screens on the device (the İZBAN station board is verified);
- per-line ETA speeds (see gotcha 12);
- dark map tuning and TalkBack review;
- public holidays (currently treated as weekdays or Saturdays).

## Goal and scope

A polished, stable native Android app for İzmir's ESHOT buses, built on the city's official open data instead of reverse engineering the official app. It should beat the official app on speed, reliability and day-to-day usefulness.

- **Phase 1 (now):** ESHOT buses: lines, stops, route shapes, live bus positions, arrivals at a stop, timetables, announcements.
- **Phase 2 (later):** Metro, İZBAN, tram and ferries in one unified app. Keep the data layer mode-agnostic from day one so these slot in.
- **Platform:** Android only, distributed via Google Play. No iOS.
- **Language:** UI in Turkish first, with English as a second locale.

## Live API (tested)

All live data comes from İzmir Teknoloji's open web services at `https://openapi.izmir.bel.tr/api/iztek/`. They are plain HTTPS GET requests, return JSON, and needed no API key or login in testing. The [official İztek documentation](https://openfiles.izmir.bel.tr/471231/docs/iztek-WebServisKullanimDokumani_1.0.pdf) lists no rate limits; be polite anyway.

| Endpoint | Purpose | Tested result |
| --- | --- | --- |
| `hatotobuskonumlari/{hatNo}` | Every bus currently on a line, with GPS position | 5 buses on line 446 |
| `duragayaklasanotobusler/{durakId}` | All buses approaching a stop, any line | 1 bus at stop 21050 |
| `hattinyaklasanotobusleri/{hatNo}/{durakId}` | Buses of one line approaching one stop | Empty `[]` for 446 / 21056 at night |
| `/api/ibb/cbs/noktayayakinduraklar?x=&y=&inCoordSys=&outCoordSys=` | Stops near a coordinate | Not tested; parameters undocumented |

### Bus positions on a line

`GET https://openapi.izmir.bel.tr/api/iztek/hatotobuskonumlari/446` returned a wrapped object:

```json
{
  "HataMesaj": "",
  "HataVarMi": false,
  "HatOtobusKonumlari": [
    { "OtobusId": 2242, "Yon": 0, "KoorX": "38,45342667", "KoorY": "27,097125" },
    { "OtobusId": 2202, "Yon": 1, "KoorX": "38,45705833", "KoorY": "27,09139" }
  ]
}
```

`Yon` is the direction: **`Yon` 1 = outbound (dataset YON 1), `Yon` 0 = return (dataset YON 2)**. This was verified on 20 Oct 2026 against route shapes using 5 moving buses. The stop endpoints' `HattinYonu` already uses 1/2.

### Buses approaching a stop

`GET https://openapi.izmir.bel.tr/api/iztek/duragayaklasanotobusler/21050` returned a **bare array** (no wrapper):

```json
[
  {
    "OtobusId": 12337,
    "HatNumarasi": 346,
    "HatAdi": "EVKA.5 - EGEKENT AKT.MRK",
    "HattinYonu": 1,
    "KalanDurakSayisi": 17,
    "KoorX": "38,5179",
    "KoorY": "27,06496",
    "EngelliMi": true,
    "BisikletAparatliMi": true
  }
]
```

`KalanDurakSayisi` is stops remaining until this stop. There is **no ETA in minutes**; the app must estimate it. `EngelliMi` means wheelchair-accessible; `BisikletAparatliMi` means it has a bike rack. `hattinyaklasanotobusleri` returns the same fields, filtered to one line.

## Data gotchas (must handle)

These were found by comparing live responses with the official docs. Each one would cause a real bug.

1. **`KoorX` is latitude, `KoorY` is longitude.** This is the reverse of the usual x = longitude convention. İzmir is around lat 38.4, lon 27.1, so a swapped pin lands in Somalia.
2. **Coordinates are strings with a comma decimal** (`"38,45342667"`), even though the docs say `double`. Parse by replacing `,` with `.`, and also accept a real number in case the server changes. Use a custom kotlinx.serialization serializer, never locale-dependent parsing.
3. **Response shapes differ.** `hatotobuskonumlari` wraps results with `HataVarMi` / `HataMesaj`; the approaching-bus endpoints return a bare array. Model them separately.
4. **Empty is normal.** `[]`, an empty list, or HTTP 204 means no buses right now (night, line not running). Show a friendly "no buses currently" state, not an error.
5. **Documented status codes:** 200, 204, 401, 404, 500. Treat 5xx and timeouts as retryable with backoff; keep showing the last known positions, marked as stale.
6. **Turkish text.** Names use İ, ı, ş, ğ, ç, ö, ü. Search must be case- and diacritic-insensitive with Turkish rules (`İ`↔`i`, `I`↔`ı`), so "izmir" finds "İZMİR". Use `Locale("tr")` for case conversion.
7. **No ETA field.** Estimate minutes from `KalanDurakSayisi` and the distance along the route shape, and label it as approximate.
8. **There IS a rate limit** (verified 20 Oct). A burst of about 3 requests per second returns HTTP 429 `{"message":"API rate limit exceeded"}` with no `Retry-After` header. Send all live calls through one app-wide queue at no more than 1 req/s, and treat 429 as retryable with backoff. The real limit is about 1 req/s at the server: in daytime runs, pacing at 1.1–1.25 s still got an occasional 429 (22 in 950), so 429s will happen in normal use and must never surface as an error state.
9. **Ghost bus records.** `hatotobuskonumlari` often repeats one `OtobusId` 2–5 times. Typically one copy is real and the others are frozen points km off the route, or `"0","0"`. Drop (0,0) and anything outside the İzmir bounding box. Drop points more than 300 m from the line's shape (`GhostFilter`). Keep one record per `OtobusId`, preferring the one that moved since the last poll. **Daytime check (20 Oct, 10:26–11:13, 8 lines):** 6–80 % of records are ghosts depending on the line (line 30: 80 %; two of its four buses exist only as copies 5–7 km off). Moving buses sit 3 m from the shape at the median and 120 m at p99, and the nearest ghost cluster is 479 m off, so 300 m has a safe margin on both sides. There is no cap on the record count (line 879 returned 27). Moving buses beyond 300 m were deadheading or in a depot, not in service.
10. **The stop endpoints return ghosts too.** Stop 10007 listed two line 29 buses as "2 stops away". Both were 10.7 km off the route, and line 29's own position endpoint reported 0 buses. `LiveBusRepository.observeStop` drops records more than 300 m from their line's shape (`GhostFilter.isPlausible`). `EtaEstimator` also flags inconsistent stop-count/distance pairs as `UNRELIABLE`. `HattinYonu` 1 = outbound was confirmed against the shape. By day, stop 10007 listed line 30 bus 12383, a pure ghost 5.7 km off, as "9 stops away" for 20 minutes straight. A bus leaves the stop list as it passes; `KalanDurakSayisi` 1 can last 3+ minutes.
11. **Unknown IDs return empty 200s, not 404s.** No 204 was observed either.
12. **ETA speed validated by day.** On 32.8k observed bus movements (63 buses), `EtaEstimator`'s 300 m/min gave an MAE of 2.3 min and a bias of +0.3 min. 62 % of estimates were within 2 min. Short hops are slower (about 250 m/min under 1 km) and arterial lines faster (line 121: about 455 m/min), so per-line speeds would be the next improvement. The evening peak is not measured yet.
13. **Positions refresh about every 30 s.** Most moving buses had a new position on every 30 s poll. Polling every 10–15 s returns repeats, so animate between real changes and don't treat an unchanged point as stale.

Full evidence is in `research/FINDINGS.md`, and the real responses are in `research/fixtures/`.

## Static datasets

Stops, lines, shapes and timetables change rarely, so ship them inside the app and refresh in the background. All are CSV files from the [İzmir open data portal](https://acikveri.bizizmir.com), licensed under the İzmir Metropolitan Municipality Open Data License.

| Dataset | File URL | Key columns | Last updated |
| --- | --- | --- | --- |
| [Stops](https://acikveri.bizizmir.com/en/dataset/eshot-otobus-duraklari/resource/0c791266-a2e4-4f14-82b8-9a9b102fbf94) | `https://openfiles.izmir.bel.tr/211488/docs/eshot-otobus-duraklari.csv` | DURAK\_ID, DURAK\_ADI, ENLEM, BOYLAM, DURAKTAN\_GECEN\_HATLAR | 26 Jul 2025 |
| [Lines](https://acikveri.bizizmir.com/en/dataset/eshot-otobus-hat-listesi/resource/bd6c84f8-49ba-4cf4-81f8-81a0fbb5caa3) | `https://openfiles.izmir.bel.tr/211488/docs/eshot-otobus-hatlari.csv` | HAT\_NO, HAT\_ADI, GUZERGAH\_ACIKLAMA, ACIKLAMA, HAT\_BASLANGIC, HAT\_BITIS | 8 May 2024 |
| [Route shapes](https://acikveri.bizizmir.com/en/dataset/otobus-hat-guzergahlari/resource/543f2249-c734-48e4-8739-72efbbfc843c) | `https://openfiles.izmir.bel.tr/211488/docs/eshot-otobus-hat-guzergahlari.csv` | HAT\_NO, YON (1 outbound, 2 return), ENLEM, BOYLAM | 14 Apr 2026 |
| [Timetables](https://acikveri.bizizmir.com/tr/dataset/otobus-hareket-saatleri/resource/c6fa6046-f755-47d7-b69e-db6bb06a8b5a) | `https://openfiles.izmir.bel.tr/211488/docs/eshot-otobus-hareketsaatleri.csv` | HAT\_NO, TARIFE\_ID, GIDIS\_SAATI, DONUS\_SAATI, SIRA, accessibility / bike / electric flags per direction | 10 Oct 2026 |
| [Announcements](https://acikveri.bizizmir.com/en/dataset/otobus-hattina-ait-duyurular/resource/aeafda53-3db8-46fa-abe3-47b773fc8b90) | `https://openfiles.izmir.bel.tr/211488/docs/eshot-otobus-hat-duyurulari.csv` | HAT\_NO, BASLIK, BASLAMA\_TARIHI, BITIS\_TARIHI | 14 Apr 2026 |
| [Lines connecting to metro, İZBAN, ferry, airport, bus terminal](https://acikveri.bizizmir.com/en/dataset/baglanti-noktalarindan-gecen-otobus-hat-listesi/resource/81138188-9e50-476d-a1d0-d069e3ec3878) | `https://openfiles.izmir.bel.tr/211488/docs/eshot-otobus-baglantili-hatlar.csv` | BAGLANTI\_TIP\_ID, HAT\_NO, operating hours per direction | 19 Oct 2026 |

**CSV format (verified 20 Oct 2026):**

- Every file is `;`-delimited, UTF-8 without BOM, with CRLF line endings and dot decimals.
- Quoted fields with `""` escapes occur, so use a real CSV parser.
- `DURAKTAN_GECEN_HATLAR` is `-`-separated.
- **The shapes file's column order is `HAT_NO;YON;BOYLAM;ENLEM`**, with longitude first, and only 4 decimals.
- Timetables:
  - `TARIFE_ID` 1/2/3 is most likely weekday / Saturday / Sunday.
  - Rows are duplicated (×5 for tariff 1, ×2 for tariff 3), one copy per day, so deduplicate on (HAT_NO, TARIFE_ID, SIRA). In 160 departures the day copies differ by a few minutes. The importer keeps the majority version, because the file doesn't say which copy belongs to which day.
  - `GIDIS_SAATI` or `DONUS_SAATI` may be empty, so treat them as two independent lists.
  - After-midnight trips appear as `00:xx`.

The portal's CKAN API blocked automated fetching, so download the CSVs directly.

### Official GTFS feed

ESHOT also publishes a standard [GTFS schedule feed](https://mobilitydatabase.org/feeds/gtfs/mdb-1823) at `https://www.eshot.gov.tr/gtfs/bus-eshot-gtfs.zip`. GTFS links stops to trips in order, which the CSVs don't, so it is the better base for stop sequences and per-stop timetables. Caveats: the latest archived copy (26 Sep 2026) only covers service from 5 Aug to 5 Oct 2026, and it had one validation error. Check `calendar.txt` dates on download; if expired, fall back to the CSVs. **On 20 Oct 2026 the live URL still served the 5 Aug build, which expired on 5–9 Oct, so the feed does lapse.** Use GTFS only for the ordered stop sequence of each line and direction. Stop IDs match the CSV for 11,731 of 11,945 stops. Timetables come from the CSV. The importer matches GTFS `direction_id` to YON per route by comparing endpoints with the shapes: 0 → YON 1 and 1 → YON 2 for 416 of 418 directions (lines 49 and 839 are exceptions). GTFS orders 32k of the 34.6k line-stop rows. The 56 directions missing from GTFS are ordered by projecting their stops onto the shape. There is no GTFS-realtime feed, so live positions still come from the İztek API.

## Architecture and tech stack

The app is offline-first: everything except live bus positions is stored on the phone, so it opens instantly and works on a bad connection.

&#91;embedded content: data flow · 2 sources, 2 stores, 1 UI\]

Screens read from the local database and the live repository through ViewModels, so a network failure only affects the moving buses.

| Concern | Choice |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Architecture | MVVM with Kotlin Flow, single activity, Compose Navigation |
| Dependency injection | Hilt |
| Networking | Retrofit + OkHttp + kotlinx.serialization |
| Local storage | Room (static data), DataStore (settings, favourites) |
| Background refresh | WorkManager, weekly, on Wi-Fi |
| Map | MapLibre + OSM tiles or Google Maps Compose (see open questions) |
| Location | Fused Location Provider |
| Min / target SDK | 26 / latest stable |
| Modules | `:app`, `:core:data`, `:core:model`, `:core:ui`, `:feature:*` — add `:core:data` sources per transit mode later |

## Features

The MVP's core is live bus tracking that is faster and more reliable than the official app; everything else builds on it.

### MVP

- [ ] **Line map:** pick a line, see its route shape for both directions, its stops, and live buses that move smoothly between updates.
- [ ] **Stop screen:** buses approaching a stop, sorted by stops remaining, with an estimated time, accessibility and bike-rack icons.
- [ ] **Nearby stops:** stops around the user's location on the map and in a list, worked out offline from the stops dataset.
- [ ] **Search:** lines by number or name, stops by name, Turkish-aware and instant.
- [ ] **Favourites:** starred lines and stops on the home screen, with their live arrivals.
- [ ] **Timetable:** departure times per line and direction, with the next departure highlighted.
- [ ] **Announcements:** active route changes and disruptions shown on the affected line and stop.

### Later

- [ ] Home-screen widget for a favourite stop's arrivals.
- [ ] "Notify me when the bus is N stops away" alert.
- [ ] Connection hints from the connecting-lines dataset ("this line meets İZBAN at …").
- [ ] Metro, İZBAN, tram and ferries (Phase 2), then trip planning across modes.
- [ ] Dark map style, larger-text mode, TalkBack support.

## Polish and stability requirements

The official app's main failing is unreliability, so these are requirements, not nice-to-haves.

- **Instant start:** the app opens to favourites and the map from the local database in under a second, with no network needed.
- **Works offline:** lines, stops, shapes and timetables are always available; only live positions need the network.
- **Never a blank screen:** every screen has explicit loading, empty, stale and error states. On failure, keep showing the last data with a "last updated 40 s ago" note.
- **Smart polling:** refresh live positions every 10–15 s, only for what is on screen, only while the app is in the foreground. Stop in the background to save battery and data.
- **Smooth motion:** animate bus markers between updates instead of jumping.
- **Resilient networking:** 10 s timeouts, retries with exponential backoff, and no crash on malformed data (log it and skip the bad record).
- **Design:** Material 3 with dynamic colour, light and dark themes, 48 dp touch targets, readable at large font sizes.
- **Quality gates:** unit tests for parsers (comma decimals, swapped coordinates, empty responses), using the real JSON samples above as fixtures.

## First session plan

Start with data, not screens: the first session should end with a verified data layer and one working map.

1. Download every CSV above and the GTFS zip; report delimiter, encoding, row counts, 3 sample rows each, and GTFS `calendar.txt` validity.
2. Call the three live endpoints for a few busy lines and stops during the day; save responses as test fixtures and confirm what `Yon` 0/1 means against route shapes.
3. Propose the module structure and Gradle setup, then wait for approval.
4. Build the data layer: parsers, Room schema, importer, live API client, with tests.
5. Build the first screen: one line on the map with its route, stops and moving live buses.

### Open questions

- [ ] Google Maps (needs an API key and billing account; free quota is generous) or MapLibre with OpenStreetMap tiles (no key, fully free)?
- [x] Is the GTFS feed kept current? **No.** It was expired on 20 Oct 2026, so treat it as a stop-sequence source only.
- [ ] Exact parameters for `noktayayakinduraklar` (likely not needed; nearby stops can be computed offline).
- [ ] App name and package id (e.g. `com.<you>.izmirtransit`).
