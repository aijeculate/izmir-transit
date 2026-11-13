// The map: MapLibre GL with OpenFreeMap's free vector tiles (Positron by day, Dark by night).
// Holds every overlay the app draws and redraws them after a theme switch.
import { db } from "./data.js";
import { decodePolyline } from "./geo.js";

export const BRANDS = {
  BUS: { primary: "#D71920", secondary: "#FFFFFF" },
  METRO: { primary: "#2A52A3", secondary: "#E3262E" },
  TRAM: { primary: "#3DA548", secondary: "#233F95" },
  IZBAN: { primary: "#0B5BA8", secondary: "#FFFFFF" },
  FERRY: { primary: "#1D3C8F", secondary: "#29A9E0" },
};
const ROUTE_BLUE = "#2F6FDB", ROUTE_ORANGE = "#F08A24";
const STYLES = {
  light: "https://tiles.openfreemap.org/styles/positron",
  dark: "https://tiles.openfreemap.org/styles/dark",
};
const EMPTY = { type: "FeatureCollection", features: [] };
const lineString = (pts, props) => ({ type: "Feature", properties: props, geometry: { type: "LineString", coordinates: pts.map(([a, b]) => [b, a]) } });
const point = (p, props) => ({ type: "Feature", properties: props, geometry: { type: "Point", coordinates: [p[1], p[0]] } });

let map = null;
let ready = false;
let dark = false;
const data = { overview: EMPTY, allStops: EMPTY, route: EMPTY, lineStops: EMPTY, buses: EMPTY, user: EMPTY };
const handlers = { stop: null, background: null };
let overviewVisible = true;

export function mapAvailable() { return !!window.maplibregl; }

export function initMap(container, isDark) {
  if (map || !mapAvailable()) return map;
  dark = isDark;
  map = new maplibregl.Map({
    container,
    style: STYLES[dark ? "dark" : "light"],
    center: [27.14, 38.42],
    zoom: 11,
    attributionControl: { compact: true },
    dragRotate: false,
    pitchWithRotate: false,
  });
  map.touchZoomRotate.disableRotation();
  map.on("style.load", () => { addOverlays(); ready = true; });
  map.on("click", (e) => {
    // A finger is wider than a stop dot: look 12 px around the tap.
    const box = [[e.point.x - 12, e.point.y - 12], [e.point.x + 12, e.point.y + 12]];
    const hits = ready ? map.queryRenderedFeatures(box, { layers: ["line-stops", "all-stops"] }) : [];
    if (hits.length && handlers.stop) handlers.stop(hits[0].properties.code, e);
    else handlers.background?.();
  });
  for (const layer of ["line-stops", "all-stops"]) {
    map.on("mouseenter", layer, () => { map.getCanvas().style.cursor = "pointer"; });
    map.on("mouseleave", layer, () => { map.getCanvas().style.cursor = ""; });
  }
  return map;
}

export function setMapTheme(isDark) {
  if (!map || dark === isDark) return;
  dark = isDark;
  ready = false;
  map.setStyle(STYLES[dark ? "dark" : "light"], { diff: false });
}

export function resizeMap() { map?.resize(); }
export function onStopClick(fn) { handlers.stop = fn; }
export function onBackgroundClick(fn) { handlers.background = fn; }

function addImages() {
  const make = (w, h, draw) => {
    const ratio = 2, c = document.createElement("canvas");
    c.width = w * ratio; c.height = h * ratio;
    const g = c.getContext("2d");
    g.scale(ratio, ratio);
    draw(g);
    return { image: g.getImageData(0, 0, c.width, c.height), ratio };
  };
  // A bus seen from above, front (windscreen) pointing north, in ESHOT red with a white rim.
  const bus = make(22, 42, (g) => {
    const rr = (x, y, w, h, r, fill) => { g.beginPath(); g.roundRect(x, y, w, h, r); g.fillStyle = fill; g.fill(); };
    g.shadowColor = "rgba(0,0,0,.45)"; g.shadowBlur = 3; g.shadowOffsetY = 1;
    rr(1, 1, 20, 40, 6, "#FFFFFF");
    g.shadowColor = "transparent";
    rr(3, 3, 16, 36, 4.5, BRANDS.BUS.primary);
    rr(5, 5, 12, 6, 2, "#CFE3F5");
    rr(6, 35, 10, 2.5, 1, "#8E1015");
    rr(7, 16, 8, 10, 2, "#B3121A");
  });
  const chevron = make(14, 14, (g) => {
    g.strokeStyle = "#FFFFFF"; g.lineWidth = 2.6; g.lineCap = "round"; g.lineJoin = "round";
    g.beginPath(); g.moveTo(4.5, 3); g.lineTo(9.5, 7); g.lineTo(4.5, 11); g.stroke();
  });
  for (const [name, img] of [["bus-top", bus], ["chevron", chevron]]) {
    if (!map.hasImage(name)) map.addImage(name, img.image, { pixelRatio: img.ratio });
  }
}

function addOverlays() {
  addImages();
  const sources = Object.keys(data);
  for (const s of sources) if (!map.getSource(s)) map.addSource(s, { type: "geojson", data: data[s] });

  const before = map.getStyle().layers.find((l) => l.type === "symbol")?.id;
  map.addLayer({
    id: "overview-bus", type: "line", source: "overview", filter: ["==", ["get", "mode"], "BUS"],
    layout: { "line-cap": "round", "line-join": "round", visibility: overviewVisible ? "visible" : "none" },
    paint: { "line-color": ROUTE_BLUE, "line-opacity": dark ? 0.32 : 0.22, "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1, 15, 2.5] },
  }, before);
  map.addLayer({
    id: "overview-ferry", type: "line", source: "overview", filter: ["==", ["get", "mode"], "FERRY"],
    layout: { "line-cap": "round", visibility: overviewVisible ? "visible" : "none" },
    paint: { "line-color": ["get", "color"], "line-width": 2.5, "line-dasharray": [2, 2], "line-opacity": 0.9 },
  }, before);
  map.addLayer({
    id: "overview-rail", type: "line", source: "overview", filter: ["in", ["get", "mode"], ["literal", ["METRO", "TRAM", "IZBAN"]]],
    layout: { "line-cap": "round", "line-join": "round", visibility: overviewVisible ? "visible" : "none" },
    paint: { "line-color": ["get", "color"], "line-width": ["interpolate", ["linear"], ["zoom"], 10, 3, 15, 6] },
  }, before);

  map.addLayer({
    id: "route-casing", type: "line", source: "route",
    layout: { "line-cap": "round", "line-join": "round" },
    paint: { "line-color": dark ? "#0B0E13" : "#FFFFFF", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 6, 16, 12] },
  });
  map.addLayer({
    id: "route", type: "line", source: "route",
    layout: { "line-cap": "round", "line-join": "round" },
    paint: { "line-color": ["get", "color"], "line-width": ["interpolate", ["linear"], ["zoom"], 10, 3.5, 16, 8] },
  });
  map.addLayer({
    id: "route-arrows", type: "symbol", source: "route",
    layout: { "symbol-placement": "line", "symbol-spacing": 90, "icon-image": "chevron", "icon-rotation-alignment": "map", "icon-allow-overlap": true, "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 16, 1] },
  });

  map.addLayer({
    id: "all-stops", type: "circle", source: "allStops", minzoom: 14.5,
    paint: {
      "circle-radius": ["interpolate", ["linear"], ["zoom"], 14.5, 3.5, 17, 7],
      "circle-color": ["get", "color"],
      "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 1.5,
    },
  });
  map.addLayer({
    id: "line-stops", type: "circle", source: "lineStops",
    paint: {
      "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 2.5, 13, 4, 17, 8],
      "circle-color": dark ? "#0B0E13" : "#FFFFFF",
      "circle-stroke-color": ["get", "color"],
      "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 10, 1.5, 16, 3],
    },
  });
  map.addLayer({
    id: "line-stop-names", type: "symbol", source: "lineStops", minzoom: 14,
    layout: { "text-field": ["get", "name"], "text-font": ["Noto Sans Regular"], "text-size": 12, "text-offset": [0, 1.2], "text-anchor": "top", "text-optional": true },
    paint: { "text-color": dark ? "#C7D0DB" : "#3A4250", "text-halo-color": dark ? "#0B0E13" : "#FFFFFF", "text-halo-width": 1.4 },
  });

  map.addLayer({
    id: "user-halo", type: "circle", source: "user",
    paint: { "circle-radius": 16, "circle-color": ROUTE_BLUE, "circle-opacity": 0.18 },
  });
  map.addLayer({
    id: "user", type: "circle", source: "user",
    paint: { "circle-radius": 7, "circle-color": ROUTE_BLUE, "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 2.5 },
  });

  map.addLayer({
    id: "buses", type: "symbol", source: "buses",
    layout: {
      "icon-image": "bus-top", "icon-rotate": ["get", "heading"], "icon-rotation-alignment": "map",
      "icon-allow-overlap": true, "icon-ignore-placement": true,
      "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 15, 1],
    },
  });
}

function setSource(name, value) {
  data[name] = value;
  if (ready) map.getSource(name)?.setData(value);
}

// ---- What the screens put on the map ----

let overviewLoaded = false;
export async function loadOverview() {
  if (overviewLoaded) return;
  overviewLoaded = true;
  try {
    const rows = await (await fetch("data/overview.json")).json();
    const features = [];
    for (const [mode, code, , encoded] of rows) {
      const line = db.lines.get(code);
      features.push(lineString(decodePolyline(encoded), { mode, color: line?.color || BRANDS[mode]?.primary || ROUTE_BLUE }));
    }
    setSource("overview", { type: "FeatureCollection", features });
  } catch { overviewLoaded = false; }
  const stops = [];
  for (const s of db.stops.values()) stops.push(point(s.position, { code: s.code, color: BRANDS[s.mode]?.primary || ROUTE_BLUE }));
  setSource("allStops", { type: "FeatureCollection", features: stops });
}

export function showOverview(visible) {
  overviewVisible = visible;
  if (!ready) return;
  for (const id of ["overview-bus", "overview-ferry", "overview-rail"]) map.setLayoutProperty(id, "visibility", visible ? "visible" : "none");
}

/** One line: the chosen direction(s)' routes and stops. */
export function showLine(line, detail, directions) {
  const routes = [], stops = [];
  directions.forEach((dir, i) => {
    const color = line.mode === "BUS" ? (i === 0 ? ROUTE_BLUE : ROUTE_ORANGE) : line.color || BRANDS[line.mode].primary;
    const r = detail.routes[dir];
    if (r) routes.push(lineString(r.points, { color }));
    for (const code of detail.stops[dir] || []) {
      const s = db.stops.get(code);
      if (s) stops.push(point(s.position, { code, name: s.name, color }));
    }
  });
  setSource("route", { type: "FeatureCollection", features: routes });
  setSource("lineStops", { type: "FeatureCollection", features: stops });
}

export function clearLine() {
  setSource("route", EMPTY);
  setSource("lineStops", EMPTY);
  setBuses([]);
}

export function setBuses(drawn) {
  setSource("buses", { type: "FeatureCollection", features: drawn.map((b) => point(b.position, { id: b.id, heading: b.heading ?? 0 })) });
}

export function setUser(position) {
  setSource("user", position ? { type: "FeatureCollection", features: [point(position, {})] } : EMPTY);
}

export function fitTo(points, padding) {
  if (!map || !points.length) return;
  let s = 90, n = -90, w = 180, e = -180;
  for (const [lat, lon] of points) { s = Math.min(s, lat); n = Math.max(n, lat); w = Math.min(w, lon); e = Math.max(e, lon); }
  map.fitBounds([[w, s], [e, n]], { padding, duration: 600, maxZoom: 16 });
}

export function flyTo(position, zoom = 15.5) {
  map?.easeTo({ center: [position[1], position[0]], zoom: Math.max(map.getZoom(), zoom), duration: 600 });
}

export function zoom() { return map?.getZoom() ?? 0; }
export function onMove(fn) { map?.on("moveend", fn); }

// ---- ~15 fps bus animation, only while something supplies buses ----
let provider = null, frame = 0, lastDraw = 0;
export function animateBuses(fn) {
  provider = fn;
  cancelAnimationFrame(frame);
  if (!fn) { setBuses([]); return; }
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const tick = (ts) => {
    if (!provider) return;
    if (ts - lastDraw >= (reduce ? 1000 : 66)) {
      lastDraw = ts;
      setBuses(provider(performance.now()));
    }
    frame = requestAnimationFrame(tick);
  };
  frame = requestAnimationFrame(tick);
}
