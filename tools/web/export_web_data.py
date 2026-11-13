#!/usr/bin/env python3
"""Turns the app's database into the small JSON files the website reads.

    python3 tools/web/export_web_data.py build/izmir-transit.db docs/app/data

Writes:
  core.json         lines, stops (with the lines serving them), announcements, connections
  overview.json     every line's route, simplified to ~25 m, for the all-lines map
  lines/{code}.json one line: routes and stops per direction, timetables, station departures

Only the standard library is used, so it runs anywhere Python 3 does.
"""
import json
import math
import os
import shutil
import sqlite3
import sys


def decode_polyline(encoded):
    points, index, lat, lon = [], 0, 0, 0
    while index < len(encoded):
        for which in (0, 1):
            shift = result = 0
            while True:
                b = ord(encoded[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if which == 0:
                lat += delta
            else:
                lon += delta
        points.append((lat / 1e5, lon / 1e5))
    return points


def encode_polyline(points):
    out, plat, plon = [], 0, 0
    for lat, lon in points:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        for delta in (ilat - plat, ilon - plon):
            v = ~(delta << 1) if delta < 0 else delta << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plon = ilat, ilon
    return "".join(out)


def simplify(points, tolerance_m):
    """Douglas-Peucker on a local flat projection."""
    if len(points) < 3:
        return points
    lat0 = math.radians(points[0][0])
    xy = [(math.radians(lon) * math.cos(lat0) * 6371000, math.radians(lat) * 6371000) for lat, lon in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = xy[a]
        bx, by = xy[b]
        dx, dy = bx - ax, by - ay
        length2 = dx * dx + dy * dy
        worst, worst_i = 0.0, -1
        for i in range(a + 1, b):
            px, py = xy[i]
            if length2 == 0:
                d = math.hypot(px - ax, py - ay)
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length2))
                d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > worst:
                worst, worst_i = d, i
        if worst > tolerance_m:
            keep[worst_i] = True
            stack += [(a, worst_i), (worst_i, b)]
    return [p for p, k in zip(points, keep) if k]


def color_hex(argb):
    if argb is None:
        return None
    return "#%06X" % (argb & 0xFFFFFF)


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))


def main(db_path, out_dir):
    db = sqlite3.connect(db_path)
    q = lambda sql, *args: db.execute(sql, args).fetchall()

    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)

    lines = q("select mode, code, display_code, color, name, search_text, origin, destination, route_description, notes from lines")
    stop_lines = {}
    for mode, stop, line in q("select mode, stop_code, line_code from stop_lines order by line_code"):
        stop_lines.setdefault(stop, []).append(line)
    stops = [
        [mode, code, name, search, round(lat, 6), round(lon, 6), stop_lines.get(code, [])]
        for mode, code, name, search, lat, lon in q("select mode, code, name, search_text, latitude, longitude from stops")
    ]
    meta = dict(q("select key, value from dataset_meta"))
    write(os.path.join(out_dir, "core.json"), {
        "builtAt": max((v for k, v in meta.items() if k.endswith("imported_at")), default=None),
        # [mode, code, display, color, name, search, origin, destination, route, notes]
        "lines": [[m, c, d, color_hex(col), n, s, o, de, r, no] for m, c, d, col, n, s, o, de, r, no in lines],
        # [mode, code, name, search, lat, lon, [line codes]]
        "stops": stops,
        # [line, title, starts, ends]
        "announcements": q("select line_code, title, starts_at, ends_at from announcements"),
        # [line, type, outbound hours, return hours]
        "connections": q("select line_code, connection_type, outbound_hours, return_hours from connections"),
    })

    overview = []
    for mode, line, direction, encoded in q("select mode, line_code, direction, encoded_polyline from shapes"):
        overview.append([mode, line, direction, encode_polyline(simplify(decode_polyline(encoded), 25))])
    write(os.path.join(out_dir, "overview.json"), overview)

    for mode, code, *_ in lines:
        shapes = {str(d): [enc, round(length)] for d, enc, length in
                  q("select direction, encoded_polyline, length_meters from shapes where line_code = ?", code)}
        stops_by_dir = {}
        for direction, stop in q("select direction, stop_code from line_stops where line_code = ? order by direction, sequence", code):
            stops_by_dir.setdefault(str(direction), []).append(stop)
        # departures[day][direction] = [[minutes, flags]], flags: 1 wheelchair, 2 bike rack, 4 electric
        departures = {}
        for day, direction, minutes, wc, bike, ev in q(
                "select service_day, direction, minutes_after_midnight, wheelchair_accessible, bike_rack, electric "
                "from departures where line_code = ? order by minutes_after_midnight", code):
            departures.setdefault(str(day), {}).setdefault(str(direction), []).append(
                [minutes, (1 if wc else 0) | (2 if bike else 0) | (4 if ev else 0)])
        # stations[stop][day] = [[minutes, direction, headsign index]]
        headsigns, stations = [], {}
        for stop, day, direction, minutes, headsign in q(
                "select stop_code, service_day, direction, minutes_after_midnight, headsign "
                "from stop_departures where line_code = ? order by minutes_after_midnight", code):
            if headsign not in headsigns:
                headsigns.append(headsign)
            stations.setdefault(stop, {}).setdefault(str(day), []).append([minutes, direction, headsigns.index(headsign)])
        write(os.path.join(out_dir, "lines", f"{code}.json"), {
            "shapes": shapes, "stops": stops_by_dir, "departures": departures,
            "headsigns": headsigns, "stations": stations,
        })

    total = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(out_dir) for f in fs)
    print(f"{len(lines)} lines, {len(stops)} stops, {total / 1e6:.1f} MB in {out_dir}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
