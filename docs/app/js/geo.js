// Geometry: polylines, distances, projecting a point onto a route. Mirrors core:model's geo
// helpers (MeasuredPolyline, PolylineCodec) so ETAs and bus motion match the Android app.

const R = 6371000;
const rad = (d) => (d * Math.PI) / 180;

export function distance(a, b) {
  const dLat = rad(b[0] - a[0]);
  const dLon = rad(b[1] - a[1]);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[0])) * Math.cos(rad(b[0])) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Google's encoded polyline format, precision 1e5; points as [lat, lon]. */
export function decodePolyline(encoded) {
  const points = [];
  let index = 0, lat = 0, lon = 0;
  while (index < encoded.length) {
    for (let which = 0; which < 2; which++) {
      let shift = 0, result = 0, b;
      do {
        b = encoded.charCodeAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (which === 0) lat += delta; else lon += delta;
    }
    points.push([lat / 1e5, lon / 1e5]);
  }
  return points;
}

export const IZMIR_BOX = { south: 37.8, north: 39.4, west: 26.2, east: 28.5 };
export const inIzmir = (p) => p[0] >= IZMIR_BOX.south && p[0] <= IZMIR_BOX.north && p[1] >= IZMIR_BOX.west && p[1] <= IZMIR_BOX.east;

/** A route with cumulative distances, for "how far along" and "how far off" questions. */
export class MeasuredPolyline {
  constructor(points) {
    this.points = points;
    this.cumulative = [0];
    for (let i = 1; i < points.length; i++) this.cumulative.push(this.cumulative[i - 1] + distance(points[i - 1], points[i]));
    this.length = this.cumulative[this.cumulative.length - 1] || 0;
    this.lat0 = points.length ? rad(points[0][0]) : 0;
  }

  xy(p) {
    return [rad(p[1]) * Math.cos(this.lat0) * R, rad(p[0]) * R];
  }

  /** Closest point on the route: { along, off } in metres, or null for an empty route. */
  project(p) {
    if (this.points.length < 2) return null;
    const [px, py] = this.xy(p);
    let best = null;
    for (let i = 1; i < this.points.length; i++) {
      const [ax, ay] = this.xy(this.points[i - 1]);
      const [bx, by] = this.xy(this.points[i]);
      const dx = bx - ax, dy = by - ay;
      const len2 = dx * dx + dy * dy;
      const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
      const off = Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
      if (!best || off < best.off) {
        best = { off, along: this.cumulative[i - 1] + t * (this.cumulative[i] - this.cumulative[i - 1]) };
      }
    }
    return best;
  }

  pointAt(along) {
    const d = Math.max(0, Math.min(this.length, along));
    let lo = 1, hi = this.cumulative.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (this.cumulative[mid] < d) lo = mid + 1; else hi = mid;
    }
    const i = Math.max(1, lo);
    const a = this.points[i - 1], b = this.points[i];
    const span = this.cumulative[i] - this.cumulative[i - 1];
    const t = span === 0 ? 0 : (d - this.cumulative[i - 1]) / span;
    return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
  }
}

/** Compass bearing, degrees clockwise from north; null when the points coincide. */
export function bearing(from, to) {
  const dLon = rad(to[1] - from[1]) * Math.cos(rad(from[0]));
  const dLat = rad(to[0] - from[0]);
  if (dLon === 0 && dLat === 0) return null;
  return ((Math.atan2(dLon, dLat) * 180) / Math.PI + 360) % 360;
}
