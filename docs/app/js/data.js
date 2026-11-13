// The timetable data: lines, stops, routes and departures, exported from the app's database
// by tools/web/export_web_data.py. Loaded once and kept in memory.
import { MeasuredPolyline, decodePolyline, distance } from "./geo.js";
import { WEEKDAY, nextServiceDay, serviceMoment } from "./time.js";

export const OUTBOUND = 1, RETURN = 2;

export const db = {
  lines: new Map(), // code -> line
  stops: new Map(), // code -> stop
  announcements: new Map(), // line code -> [{ title, starts, ends }]
  connections: new Map(), // line code -> [type]
  builtAt: null,
};

export async function loadCore() {
  const res = await fetch("data/core.json");
  if (!res.ok) throw new Error("data");
  const core = await res.json();
  db.builtAt = core.builtAt;
  for (const [mode, code, display, color, name, search, origin, destination, route, notes] of core.lines) {
    db.lines.set(code, { mode, code, display, color, name, search, origin, destination, route, notes });
  }
  for (const [mode, code, name, search, lat, lon, lines] of core.stops) {
    db.stops.set(code, { mode, code, name, search, position: [lat, lon], lines });
  }
  const now = new Date().toISOString().slice(0, 16);
  for (const [line, title, starts, ends] of core.announcements) {
    if ((starts && starts > now) || (ends && ends < now)) continue;
    (db.announcements.get(line) || db.announcements.set(line, []).get(line)).push({ title, starts, ends });
  }
  for (const [line, type] of core.connections) {
    (db.connections.get(line) || db.connections.set(line, []).get(line)).push(type);
  }
}

export const isBus = (code) => db.lines.get(code)?.mode === "BUS";

// ---- One line's detail file, fetched on demand and cached. ----
const lineCache = new Map();

export function loadLine(code) {
  if (!lineCache.has(code)) {
    const p = fetch(`data/lines/${encodeURIComponent(code)}.json`)
      .then((r) => { if (!r.ok) throw new Error("line"); return r.json(); })
      .then((raw) => {
        const routes = {};
        for (const [dir, [encoded]] of Object.entries(raw.shapes)) {
          const pts = decodePolyline(encoded);
          if (pts.length > 1) routes[dir] = new MeasuredPolyline(pts);
        }
        return { ...raw, routes, directions: Object.keys(raw.stops).map(Number).sort() };
      });
    p.catch(() => lineCache.delete(code));
    lineCache.set(code, p);
  }
  return lineCache.get(code);
}

/**
 * A ring line ("HATTIMIZ RİNG HAT OLUP KONAKTAN SAAT UYGULAMASI YOKTUR") runs out and back as one
 * trip, timed from one end only. Returns that starting direction, or null (as Line.ringStart).
 */
export function ringStart(line, detail) {
  if (line.mode !== "BUS" || !(line.notes || "").toLocaleLowerCase("tr").includes("ring hat")) return null;
  if (detail.directions.length !== 2) return null;
  const timed = [OUTBOUND, RETURN].filter((d) => Object.values(detail.departures || {}).some((day) => (day[d] || []).length));
  return timed.length === 1 ? timed[0] : null;
}

/** The terminus a direction heads to, from the line's own stop list, else its name. */
export function towards(line, detail, direction) {
  const stops = detail?.stops?.[direction];
  if (stops?.length) {
    const last = db.stops.get(stops[stops.length - 1]);
    if (last && line.mode !== "BUS") return last.name;
  }
  return direction === RETURN ? line.origin : line.destination;
}

// ---- Search: case- and diacritic-insensitive with Turkish rules (İ↔i, I↔ı). ----
export function normalize(text) {
  return text
    .toLocaleLowerCase("tr")
    .replace(/ı/g, "i").replace(/ş/g, "s").replace(/ğ/g, "g").replace(/ç/g, "c").replace(/ö/g, "o").replace(/ü/g, "u")
    .normalize("NFD").replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, " ").trim();
}

export function search(query, limit = 30) {
  const q = normalize(query);
  if (!q) return { lines: [], stops: [] };
  const words = q.split(" ");
  const matches = (text) => words.every((w) => text.includes(w));
  const lines = [];
  for (const line of db.lines.values()) {
    const code = normalize(line.display + " " + line.code);
    let score = -1;
    if (code.split(" ").includes(q)) score = 0;
    else if (code.startsWith(q)) score = 1;
    else if (matches(line.search)) score = 2;
    if (score >= 0) lines.push({ line, score });
  }
  lines.sort((a, b) => a.score - b.score || a.line.code.length - b.line.code.length || a.line.code.localeCompare(b.line.code, "tr", { numeric: true }));
  const stops = [];
  for (const stop of db.stops.values()) {
    if (matches(stop.search) || stop.code === query.trim()) {
      stops.push({ stop, score: stop.code === query.trim() ? 0 : stop.search.startsWith(q) ? 1 : 2 });
    }
  }
  stops.sort((a, b) => a.score - b.score || modeRank(a.stop.mode) - modeRank(b.stop.mode) || a.stop.name.localeCompare(b.stop.name, "tr"));
  return { lines: lines.slice(0, limit).map((x) => x.line), stops: stops.slice(0, limit).map((x) => x.stop) };
}

const modeRank = (mode) => ["METRO", "IZBAN", "TRAM", "FERRY", "BUS"].indexOf(mode);

export function nearbyStops(position, count = 15, maxMeters = 1500) {
  const out = [];
  for (const stop of db.stops.values()) {
    if (Math.abs(stop.position[0] - position[0]) > 0.02 || Math.abs(stop.position[1] - position[1]) > 0.025) continue;
    const meters = distance(position, stop.position);
    if (meters <= maxMeters) out.push({ stop, meters });
  }
  return out.sort((a, b) => a.meters - b.meters).slice(0, count);
}

/** Rail and ferry stations of other modes within 350 m (interchanges). */
export function transfers(stop) {
  const out = [];
  for (const other of db.stops.values()) {
    if (other.mode === "BUS" || other.mode === stop.mode) continue;
    const m = distance(stop.position, other.position);
    if (m <= 350) out.push({ stop: other, meters: m });
  }
  return out.sort((a, b) => a.meters - b.meters);
}

// ---- Timetables ----

/** First-stop departures of a bus line, or one station's departures for rail and ferry. */
export function timetable(detail, line, day, direction, stopCode) {
  if (line.mode === "BUS") return (detail.departures?.[day]?.[direction] || []).map(([minutes, flags]) => ({ minutes, flags }));
  const board = detail.stations?.[stopCode]?.[day] || [];
  return board.filter(([, dir]) => dir === direction).map(([minutes, , h]) => ({ minutes, headsign: detail.headsigns[h] }));
}

/**
 * Next departures at a station or pier, per line and headsign, plus the ones that just left
 * (trains and ferries often run a few minutes late). Late at night it shows the next
 * service day's first trips.
 */
export async function stationBoard(stop, perGroup = 3) {
  const moment = serviceMoment();
  const groups = new Map();
  const recent = [];
  let tomorrow = false;
  const details = await Promise.all(stop.lines.map((code) => loadLine(code).then((d) => [code, d]).catch(() => null)));
  const add = (code, detail, day, offset, from) => {
    for (const [minutes, direction, h] of detail.stations?.[stop.code]?.[day] || []) {
      const at = minutes + offset;
      if (at < from) continue;
      const key = `${code}|${direction}|${h}`;
      const g = groups.get(key) || groups.set(key, { line: db.lines.get(code), direction, headsign: detail.headsigns[h], times: [] }).get(key);
      if (g.times.length < perGroup) g.times.push(at);
    }
  };
  for (const entry of details) {
    if (!entry) continue;
    const [code, detail] = entry;
    add(code, detail, moment.day, 0, moment.minutes);
    for (const [minutes, direction, h] of detail.stations?.[stop.code]?.[moment.day] || []) {
      if (minutes < moment.minutes && minutes >= moment.minutes - 20) {
        const prev = recent.find((r) => r.code === code && r.direction === direction && r.headsign === detail.headsigns[h]);
        if (prev) prev.minutes = Math.max(prev.minutes, minutes);
        else recent.push({ code, line: db.lines.get(code), direction, headsign: detail.headsigns[h], minutes });
      }
    }
  }
  if (!groups.size) {
    // Nothing left today: the first departures of the next service day.
    const next = nextServiceDay(moment.date);
    for (const entry of details) if (entry) add(entry[0], entry[1], next.day, 1440, 0);
    tomorrow = groups.size > 0;
  }
  const list = [...groups.values()].sort((a, b) => a.times[0] - b.times[0]);
  return { moment, groups: list, recent: recent.sort((a, b) => b.minutes - a.minutes), tomorrow };
}

/** Next few departures of a line from each direction's first stop (rail/ferry line panel). */
export function nextFromStart(detail, line, direction, count = 3) {
  const moment = serviceMoment();
  const first = detail.stops?.[direction]?.[0];
  if (line.mode === "BUS") {
    return (detail.departures?.[moment.day]?.[direction] || []).map(([m]) => m).filter((m) => m >= moment.minutes).slice(0, count);
  }
  return (detail.stations?.[first]?.[moment.day] || []).filter(([m, d]) => d === direction && m >= moment.minutes).map(([m]) => m).slice(0, count);
}

export { WEEKDAY };
