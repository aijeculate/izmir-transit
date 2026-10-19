# İzmir Transit App — Project Brief

Oct 19, 2026 · @aijec

## How to use this brief

Export this doc as Markdown, save it as `CLAUDE.md` in the root of your new Android Studio project, and start Claude Code in that folder. Claude Code reads `CLAUDE.md` automatically at the start of every session, so it never loses the context below.

Everything under "Live API" was tested against the real servers on 19 Oct 2026 around 23:45 Istanbul time. Anything not tested is marked as such.

## Current state and commands (updated 20 Oct 2026, 02:15)

The data layer and four screen areas (home, line map, stop screen, bottom bar) are done and run on a real phone (Galaxy S25 Ultra, Android 17 / API 37).

- **Home** is the start screen. It shows favourite stops with their next three live arrivals, favourite lines, and stops near you (location, computed offline). A bottom bar switches between Home and the Map.

- **Search** finds lines and stops, Turkish-aware. Stop results list their lines, because names repeat: there are six "Bahribaba" stops.
- **Line map** shows both directions' routes and stops, plus live buses that glide between polls. Tapping a stop opens it.
- **Stop screen** lists the lines serving the stop and the approaching buses, sorted by stops remaining, with an approximate ETA. Tapping a line opens it on the map.
- **Favourites:** the ♥ on the stop screen and on the line card toggles a favourite. They are stored in DataStore (`user_preferences`).

Everything except live data works offline.

| Module | What it holds |
| --- | --- |
| `:core:model` | Plain JVM. Domain types (`Line`, `Stop`, `LiveBus`, `LiveSnapshot`, …) and geo helpers: `MeasuredPolyline`, `PolylineCodec`, Douglas–Peucker simplification. |
| `:core:database` | Room KMP with JVM and Android targets: entities, DAOs, `IzmirDatabase`. `AndroidDatabases.prepackaged` installs the shipped DB from assets the first time Room opens a connection (off the main thread) and reinstalls it after each app update. `JvmDatabases` serves the importer and tests. The schema is exported to `core/database/schemas/`. |
| `:core:data` | Plain JVM. CSV and GTFS parsers, the İztek client, `RequestPacer` (1 req/s app-wide), `GhostFilter`, `EtaEstimator`, `pollLive`, `EshotImporter`, and the repositories (`@Inject`), including `FavouritesRepository` (DataStore preferences-core, JVM-testable). |
| `:core:location` | `LocationProvider`: a one-shot Fused Location request with balanced accuracy, a 10 s timeout and a last-known fallback. Coarse/fine permission is asked only when the user taps the button on Home. |
| `:core:ui` | `IzmirTheme` (Material 3, dynamic colour, light/dark), `TransitColors` (fixed outbound/return colours), and shared pieces: `LineBadge`, `rememberNow`/`formatAge`, `liveErrorText`. |
| `:feature:linemap` | `LineMapViewModel` (live flow `WhileSubscribed(5 s)`, so polling stops in the background), `LineMapScreen`, and `TransitMap`, a MapLibre `MapView` in `AndroidView` with GeoJSON sources written by hand. Strings: Turkish default, English in `values-en`. |
| `:feature:home` | `HomeViewModel`: favourites, live arrivals for the first 5 favourite stops (shared pacer), and nearby stops within 750 m. `HomeScreen` handles the permission flow, including the "denied twice → open Settings" case. |
| `:feature:stop` | `StopViewModel` (nav arg `stopCode`) and `StopScreen`. |
| `:app` | `AppNavHost`: a bottom bar (Home / Map), with routes `home` (start), `line?lineCode=` (default line 5) and `stop/{stopCode}`; Hilt `DataModule`, `MainActivity`, and `IzmirTransitApp` (MapLibre init; StrictMode in debug). `copyTransitDatabase` puts the importer's DB into generated assets. |
| `:tools:importer` | A desktop tool. `run` builds `build/izmir-transit.db` (inputs/outputs declared, so it's skipped when nothing changed). `liveSmoke` polls the real API once through the repositories. |
| `build-logic` | Convention plugins: `izmir.jvm.library`, `izmir.kmp.library`, `izmir.android.application`, `izmir.android.library`, `izmir.android.compose`, `izmir.android.hilt`. |

**Toolchain (verified working together):**

- Gradle 9.6.1 and AGP 9.4.1, which needs Gradle 9.6 or newer. AGP 9 has built-in Kotlin, so don't apply `org.jetbrains.kotlin.android`.
- Kotlin 2.2.21, and KSP 2.3.12. KSP 2.2.x crashes on AGP 9's KMP Android target.
- Room 2.8.4, Hilt 2.59.2, Compose BOM 2026.06.01, MapLibre Android 13.6.0.
- compileSdk/targetSdk 37, minSdk 26.
- Map tiles are OpenFreeMap `liberty` (light) and `dark`, with no API key.

Commands (use Git Bash, with `JAVA_HOME` set to the Temurin 21 JDK):

```bash
./gradlew test                                  # 55 JVM tests, all fixtures are real API responses
./gradlew :tools:importer:run                   # research/raw → build/izmir-transit.db (~9 MB, ~10 s)
./gradlew :tools:importer:liveSmoke --args="--line 446 --stop 10007"
./gradlew :app:assembleDebug                    # also rebuilds the DB asset when needed
```

**Device testing:** use the user's phone over wireless adb, **not the emulator**. The user asked for this, and the emulator is too slow here. **adb's default port 5037 is unusable on this machine** because Windows has reserved TCP 5024–5123 (Hyper-V/Docker). Always set `ANDROID_ADB_SERVER_PORT=15037`. The phone shows up via `adb mdns services` and is already paired:

```bash
export ANDROID_ADB_SERVER_PORT=15037
adb connect <ip:port from `adb mdns services`>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`research/raw/` holds the downloaded CSVs and the GTFS zip. It is git-ignored, and the app build needs it; re-download from the URLs below.

**Next steps:**

1. Daytime check of live movement and animation, the ghost filter, and the ETA under load.
2. Not yet verified on a device: tapping a stop on the map (adb can't double-tap-zoom to stop level), and the nearby list after the location permission is granted. The user must grant that permission; Claude doesn't grant it.
3. Timetable and announcements.
6. App icon.
8. Release build with ABI splits or an App Bundle (the debug APK is 70 MB because it bundles every ABI).

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
8. **There IS a rate limit** (verified 20 Oct). A burst of about 3 requests per second returns HTTP 429 `{"message":"API rate limit exceeded"}` with no `Retry-After` header. Send all live calls through one app-wide queue at no more than 1 req/s, and treat 429 as retryable with backoff.
9. **Ghost bus records.** `hatotobuskonumlari` often repeats one `OtobusId` 2–5 times. Typically one copy is real and the others are frozen points km off the route, or `"0","0"`. Drop (0,0) and anything outside the İzmir bounding box. Drop points more than about 150 m from the shape of their direction. Keep one record per `OtobusId`, preferring the one that moved since the last poll.
10. **The stop endpoints return ghosts too.** Stop 10007 listed two line 29 buses as "2 stops away". Both were 10.7 km off the route, and line 29's own position endpoint reported 0 buses. `LiveBusRepository.observeStop` drops records more than 300 m from their line's shape (`GhostFilter.isPlausible`). `EtaEstimator` also flags inconsistent stop-count/distance pairs as `UNRELIABLE`. `HattinYonu` 1 = outbound was confirmed against the shape.
11. **Unknown IDs return empty 200s, not 404s.** No 204 was observed either.

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
