# Data reconnaissance — 20 Oct 2026 (00:20–00:40 Istanbul)

Raw downloads are in `research/raw/`, the unpacked GTFS in `research/gtfs/`, live API responses in `research/fixtures/`, and the Yon experiment in `research/yon/`. All of these are git-ignore candidates except `fixtures/`, which becomes test resources.

## Static CSVs

All six files share one format: **`;` delimiter, UTF-8 without BOM, CRLF line endings, dot decimals** (no comma decimals in the CSVs, only in the live API). Fields containing `;` are wrapped in `"…"` with doubled `""` escapes (seen in the line description field), so a real RFC-4180 parser is needed, not `split(';')`.

| File | Data rows | Notes |
| --- | --- | --- |
| duraklari (stops) | 11,945 | Unique DURAK_ID. `DURAKTAN_GECEN_HATLAR` separator is `-` (e.g. `21-910-920`); 68 stops have no lines. Coordinates sit inside lat 37.84–39.34 / lon 26.30–28.22 (the whole province). |
| hatlari (lines) | 446 | HAT_NO always numeric. 3 rows have a quoted ACIKLAMA containing `;`. |
| hat-guzergahlari (shapes) | 569,007 | **Column order is `HAT_NO;YON;BOYLAM;ENLEM`, so longitude comes before latitude.** YON 1/2 only. 446 lines. Coordinates have only 4 decimals (~10 m). Points are ordered from start to end of the direction. |
| hareketsaatleri (timetables) | 97,523 | See below. |
| hat-duyurulari (announcements) | 93 | Dates are `yyyy-MM-dd HH:mm:ss`, local time. |
| baglantili-hatlar (connections) | 578 | BAGLANTI_TIP_ID 1–5 (200/250/105/4/19 rows). Hours are `"HH:mm - HH:mm"` strings. The meaning of each type ID is not documented yet. |

Sample rows:

```
10005;Bahribaba;38.41526836260150;27.12763952722090;32
5;NARLIDERE - ÜÇKUYULAR İSKELE;MİTHAT PAŞA CAD. - 9 EYLÜL HASTANESİ-F.ALTAY AKT.;;NARLIDERE;ÜÇKUYULAR İSKELE
5;1;26.99;38.3921
5;3;06:00;06:45;1;True;True;True;True;False;False
33;33 NO.LU HAT GEÇİCİ GÜZERGAH DEĞİŞİKLİĞİ (GDZ ELEKTRİK);2026-10-02 00:00:00;2026-11-01 23:00:00
1;5;NARLIDERE - ÜÇKUYULAR İSKELE;…;NARLIDERE;ÜÇKUYULAR İSKELE;06:00 - 23:00;06:45 - 23:40
```

### Timetable quirks

- **TARIFE_ID: 1 = weekday, 2 = Saturday, 3 = Sunday/holiday (likely; confirm once).** Evidence: 445 / 406 / 324 lines run on each, and the departure counts (13.6k / 11.2k / 9.1k) follow the same ratio as GTFS weekday / Saturday / Sunday trips.
- **Rows are duplicated:** tariff 1 rows appear ×5 (once per weekday), tariff 3 ×2, tariff 2 ×1. That is 22,876 exact duplicate rows. Deduplicate on (HAT_NO, TARIFE_ID, SIRA). About 170 keys have copies that differ slightly; keep the first and log it.
- Each row pairs one outbound and one return departure. **Either time can be empty** (12,617 rows) when a direction has fewer trips. Model them as two independent departure lists.
- The flags are the strings `True` / `False`. No times are ≥ 24:00, so after-midnight trips are written as `00:05`, and sorting must treat them as late.

## GTFS

- `https://www.eshot.gov.tr/gtfs/bus-eshot-gtfs.zip` still serves the **same feed built on 5 Aug. Its calendar ended on 5 Oct (weekday) and 8–9 Oct (Sat/Sun), so it is expired today.** The answer to the open question is that it lapses.
- Contents: 421 routes, 61,343 trips, 11,739 stops, 2.1M stop_times, 397k shape points with full-precision coordinates. There is no calendar_dates.txt.
- Stop IDs match the CSV: 11,731 shared, 214 only in the CSV, 8 only in GTFS.
- **Plan:** use GTFS only for the **ordered stop sequence of each line and direction**, taken from one representative trip. Route geometry changes rarely, so this is acceptable even when the calendar has expired. Take departure times from the CSV timetable. The mapping from GTFS `direction_id` 0/1 to CSV YON is not verified yet.

## Live İztek API

| Finding | Detail |
| --- | --- |
| **Rate limit exists** | Bursts of about 3 requests per second get **HTTP 429** with body `{"message":"API rate limit exceeded"}` and no `Retry-After` header. 1 request/second for over 20 calls never failed. Treat 429 as retryable with backoff. Budget no more than 1 req/s per device and use a shared request queue. |
| **`Yon` mapping** | **`Yon` 1 = outbound (YON 1), `Yon` 0 = return (YON 2).** Checked on 5 buses on lines 121, 202, 470, 671 and 950 that moved 400 m to 6 km along their shape between samples. Every one matched. |
| `HattinYonu` | On the stop endpoints this uses **1/2**, the same as the datasets, unlike `Yon`. |
| **Ghost records** | `hatotobuskonumlari` often returns the **same `OtobusId` 2–5 times**. Usually one copy moves on the route and the rest are frozen positions 0.5–6 km off the route, or **`"0","0"`**. Filter: drop (0,0) and anything outside the İzmir bounding box. Drop points more than about 150 m from the line's shape for that direction. Then keep one per `OtobusId`, preferring the one that moved since the last poll. |
| Stale arrivals | The stop endpoint listed a line 29 bus as "2 stops away" while it was 10 km off and stationary. Show `KalanDurakSayisi` together with a distance sanity check. |
| Empty / unknown | Unknown line → 200 `{"HatOtobusKonumlari":[],"HataVarMi":false}`. Unknown stop → 200 `[]`. No 204 or 404 was seen. |
| Latency | 150–470 ms. Response header `Content-Type: application/json; charset=utf-8`, CORS `*`. |

Fixtures saved: `night_hatotobuskonumlari_{5,169,202,446,950,99999}.json`, `…_470_with_ghosts.json`, `…_671_with_ghosts.json`, `night_duragayaklasanotobusler_{10005,10007,10107,10225,12345,21050,21056,40001,99999999}.json`, `night_hattinyaklasanotobusleri_446_21056.json` and `error_429_rate_limit.json`.

**Still to do:** a daytime capture (around 08:00 or 17:00) of a few busy lines, to get fixtures with many buses and to re-check the ghost rate under load.

## Added while building the data layer (20 Oct 2026, ~00:50)

- **Day copies in the timetable differ.** The ×5 weekday blocks are not identical. In 160 departures some days differ by a few minutes; for line 5 SIRA 31, two copies have a 20:25 return and three have 20:30. The importer keeps the majority version.
- **GTFS direction_id ↔ YON:** 0 → YON 1 and 1 → YON 2 for 416 of 418 route directions, matched by endpoint distance. Lines 49 and 839 had direction_id 1 matching YON 1. 23 GTFS stop IDs are missing from the stops CSV and were dropped from the sequences.
- **Stop endpoint ghosts:** `duragayaklasanotobusler/10007` returned two line 29 buses "2 stops away". Both were 10.7 km off the route, and `hatotobuskonumlari/29` showed no buses at all. These are now filtered out the same way as on the line endpoint.
- **`HattinYonu` 1 = outbound** was confirmed on one bus (line 30, 2 m from the outbound shape, with the stop ahead of it).
- **Import result:** 446 lines, 11,945 stops, 892 shapes (569k points simplified to 234k at 3 m), 63.7k departures, 34.6k ordered line stops. The database is 9.1 MB.
