// Live buses from İzmir Teknoloji's open API, with the same safeguards as the Android app:
// one request per ~1.1 s, ghost records dropped, direction worked out from motion, ETAs
// estimated from distance along the route, and buses glided between real updates.
import { MeasuredPolyline, bearing, distance, inIzmir } from "./geo.js";

const API = "https://openapi.izmir.bel.tr/api/iztek/";
const SPACING_MS = 1100;

// ---- One request at a time, about one a second (the server answers 429 above that). ----
let queue = Promise.resolve();
let last = 0;
function paced(url) {
  const run = queue.then(async () => {
    const wait = last + SPACING_MS - Date.now();
    if (wait > 0) await new Promise((r) => setTimeout(r, wait));
    last = Date.now();
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 10000);
    try {
      return await fetch(url, { signal: controller.signal, cache: "no-store" });
    } finally {
      clearTimeout(timer);
    }
  });
  queue = run.catch(() => {});
  return run;
}

export class LiveError extends Error {
  constructor(kind) { super(kind); this.kind = kind; }
}

async function getJson(path) {
  let res;
  try {
    res = await paced(API + path);
  } catch {
    throw new LiveError("offline");
  }
  if (res.status === 429) throw new LiveError("busy");
  if (res.status === 204) return null;
  if (!res.ok) throw new LiveError("server");
  const text = await res.text();
  if (!text.trim()) return null;
  try { return JSON.parse(text); } catch { throw new LiveError("server"); }
}

/** The API sends "38,4534" strings (comma decimals); accept real numbers too. KoorX is latitude. */
const num = (v) => (typeof v === "number" ? v : parseFloat(String(v ?? "").replace(",", ".")));

/** hatotobuskonumlari: raw records, ghosts included. Yon 1 = outbound (1), 0 = return (2). */
export async function fetchLinePositions(line) {
  const body = await getJson(`hatotobuskonumlari/${encodeURIComponent(line)}`);
  const list = body?.HatOtobusKonumlari || [];
  return list
    .map((r) => ({ id: r.OtobusId, direction: r.Yon === 1 ? 1 : 2, position: [num(r.KoorX), num(r.KoorY)] }))
    .filter((r) => Number.isFinite(r.position[0]) && Number.isFinite(r.position[1]) && r.id != null);
}

/** duragayaklasanotobusler: every bus heading for a stop (bare array). */
export async function fetchStopArrivals(stop) {
  const body = await getJson(`duragayaklasanotobusler/${encodeURIComponent(stop)}`);
  return (Array.isArray(body) ? body : [])
    .map((r) => ({
      id: r.OtobusId,
      line: String(r.HatNumarasi),
      lineName: r.HatAdi,
      direction: r.HattinYonu === 2 ? 2 : 1,
      stopsLeft: r.KalanDurakSayisi ?? 0,
      position: [num(r.KoorX), num(r.KoorY)],
      wheelchair: !!r.EngelliMi,
      bike: !!r.BisikletAparatliMi,
    }))
    .filter((r) => Number.isFinite(r.position[0]) && r.id != null);
}

// ---- Ghost records: copies of one bus frozen km away, or at (0,0). See CLAUDE.md gotcha 9. ----
const GHOST_METERS = 300;

function offRoute(position, routes) {
  let best = Infinity;
  for (const r of Object.values(routes)) best = Math.min(best, r.project(position)?.off ?? Infinity);
  return best;
}

export function isPlausible(position, routes) {
  if (!inIzmir(position)) return false;
  if (!Object.keys(routes).length) return true;
  return offRoute(position, routes) <= GHOST_METERS;
}

/** Keeps one real record per bus, preferring the copy that moved since the last poll. */
export function filterGhosts(raw, routes, previousRaw = [], previousChosen = new Map()) {
  const hasRoutes = Object.keys(routes).length > 0;
  const candidates = raw
    .filter((r) => inIzmir(r.position))
    .map((r) => ({ r, off: hasRoutes ? offRoute(r.position, routes) : 0 }))
    .filter((c) => c.off <= GHOST_METERS);
  const before = new Map();
  for (const p of previousRaw) (before.get(p.id) || before.set(p.id, []).get(p.id)).push(p.position);
  const byId = new Map();
  for (const c of candidates) (byId.get(c.r.id) || byId.set(c.r.id, []).get(c.r.id)).push(c);
  const out = [];
  for (const [id, copies] of byId) {
    let chosen;
    if (copies.length === 1) chosen = copies[0];
    else {
      const earlier = before.get(id) || [];
      const moved = copies.filter((c) => earlier.length && earlier.every((e) => distance(e, c.r.position) >= 10));
      const lastPos = previousChosen.get(id);
      if (moved.length) chosen = moved.reduce((a, b) => (b.off < a.off ? b : a));
      else if (lastPos) chosen = copies.reduce((a, b) => (distance(b.r.position, lastPos) < distance(a.r.position, lastPos) ? b : a));
      else chosen = copies.reduce((a, b) => (b.off < a.off ? b : a));
    }
    out.push({ id, direction: chosen.r.direction, position: chosen.r.position });
  }
  return out.sort((a, b) => a.id - b.id);
}

// ---- Which way a bus is really going (the feed's Yon is wrong on some lines). ----
export class DirectionResolver {
  constructor() { this.lastAlong = new Map(); this.resolved = new Map(); }

  resolve(buses, routes) {
    if (Object.keys(routes).length < 2) return buses;
    return buses.map((bus) => {
      const along = {}, near = [];
      for (const [dir, route] of Object.entries(routes)) {
        const p = route.project(bus.position);
        if (!p) continue;
        along[dir] = p.along;
        if (p.off <= 60) near.push(+dir);
      }
      const previous = this.lastAlong.get(bus.id);
      this.lastAlong.set(bus.id, along);
      let direction;
      if (near.length === 1) direction = near[0];
      else if (near.length > 1 && previous) {
        direction = near.find((d) => along[d] != null && previous[d] != null && along[d] - previous[d] >= 20) ?? this.resolved.get(bus.id);
      } else direction = this.resolved.get(bus.id);
      direction = direction ?? bus.direction;
      this.resolved.set(bus.id, direction);
      return { ...bus, direction };
    });
  }
}

// ---- Smooth motion between the ~30 s server updates (port of BusMotion). ----
const MAX_SPEED = 16, MAX_EXTRAPOLATION = 45000, ON_ROUTE = 60, CORRECTION = 1500, SPEED_FACTOR = 0.85;
const HEADING_SPAN = 12;
const ease = (ms) => { const x = Math.max(0, Math.min(1, ms / CORRECTION)); return 1 - (1 - x) * (1 - x); };

export class BusMotion {
  constructor() { this.tracks = new Map(); }

  update(buses, routes, now) {
    const seen = new Set();
    for (const bus of buses) {
      seen.add(bus.id);
      const route = routes[bus.direction];
      const proj = route?.project(bus.position);
      const existing = this.tracks.get(bus.id);
      if (!route || !proj || proj.off > ON_ROUTE) {
        const drawn = existing ? this.position(existing, now) : bus.position;
        const heading = existing ? this.heading(existing, now) : null;
        this.tracks.set(bus.id, { free: true, direction: bus.direction, from: drawn, to: bus.position, changedAt: now, heading });
        continue;
      }
      if (!existing || existing.free || existing.direction !== bus.direction || existing.route !== route) {
        this.tracks.set(bus.id, { free: false, direction: bus.direction, route, lastAlong: proj.along, lastAt: now, speed: null, correctionFrom: proj.along, correctionAt: 0 });
        continue;
      }
      if (Math.abs(proj.along - existing.lastAlong) < 1) continue; // no new position yet
      const seconds = (now - existing.lastAt) / 1000;
      const observed = seconds > 0 ? (proj.along - existing.lastAlong) / seconds : 0;
      existing.correctionFrom = this.drawnAlong(existing, now);
      existing.correctionAt = now;
      existing.speed = observed < 0 || observed > MAX_SPEED ? 0 : existing.speed == null ? observed : 0.5 * existing.speed + 0.5 * observed;
      existing.lastAlong = proj.along;
      existing.lastAt = now;
    }
    for (const id of [...this.tracks.keys()]) if (!seen.has(id)) this.tracks.delete(id);
  }

  drawnAlong(t, now) {
    const elapsed = Math.min(Math.max(now - t.lastAt, 0), MAX_EXTRAPOLATION);
    const predicted = Math.min(t.lastAlong + (t.speed ?? 0) * SPEED_FACTOR * (elapsed / 1000), t.route.length);
    return t.correctionFrom + (predicted - t.correctionFrom) * ease(now - t.correctionAt);
  }

  position(t, now) {
    if (t.free) {
      const k = ease(now - t.changedAt);
      return [t.from[0] + (t.to[0] - t.from[0]) * k, t.from[1] + (t.to[1] - t.from[1]) * k];
    }
    return t.route.pointAt(this.drawnAlong(t, now));
  }

  heading(t, now) {
    if (t.free) {
      if (t.from[0] === t.to[0] && t.from[1] === t.to[1]) return t.heading;
      return bearing(t.from, t.to) ?? t.heading;
    }
    const along = this.drawnAlong(t, now);
    return bearing(t.route.pointAt(along - HEADING_SPAN), t.route.pointAt(along + HEADING_SPAN));
  }

  drawn(now) {
    const out = [];
    for (const [id, t] of this.tracks) out.push({ id, direction: t.direction, position: this.position(t, now), heading: this.heading(t, now) });
    return out;
  }
}

// ---- Minutes until a bus reaches a stop (the API only gives stops remaining). ----
export function estimateEta(bus, stopsLeft, stop, route) {
  const limit = Math.max(stopsLeft, 1) * 1500 + 1000;
  if (stop && route) {
    const b = route.project(bus), s = route.project(stop);
    if (b && s && b.off <= 300 && s.off <= 300) {
      const ahead = s.along - b.along;
      if (ahead > -100) {
        const meters = Math.max(0, ahead);
        return { minutes: Math.ceil(meters / 300), reliable: meters <= limit };
      }
    }
  }
  const byStops = Math.round(Math.max(stopsLeft, 0) * 1.5);
  return { minutes: byStops, reliable: !(stop && distance(bus, stop) > limit) };
}

export { MeasuredPolyline };
