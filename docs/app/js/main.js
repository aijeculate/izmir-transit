// İzmir Transit on the web: screens, routing and polling.
import {
  OUTBOUND, RETURN, db, isBus, loadCore, loadLine, nearbyStops, nextFromStart, ringStart, search, stationBoard, timetable, towards, transfers,
} from "./data.js";
import { distance } from "./geo.js";
import { lang, t } from "./i18n.js";
import {
  BusMotion, DirectionResolver, LiveError, estimateEta, fetchLinePositions, fetchStopArrivals, filterGhosts, isPlausible,
} from "./live.js";
import * as M from "./map.js";
import { store } from "./store.js";
import { SATURDAY, SUNDAY, WEEKDAY, clock, istanbulNow, loadHolidays, serviceMoment } from "./time.js";
import { badge, esc, heart, icon, modeIcon } from "./ui.js";

const $ = (sel, root = document) => root.querySelector(sel);
const panel = $("#panel");
const body = document.body;

// ---------------------------------------------------------------- theme

const darkQuery = matchMedia("(prefers-color-scheme: dark)");
const isDark = () => (store.get().theme === "system" ? darkQuery.matches : store.get().theme === "dark");
function applyTheme() {
  const dark = isDark();
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  $('meta[name="theme-color"]').setAttribute("content", dark ? "#06080D" : "#EEF1F5");
  M.setMapTheme(dark);
}
darkQuery.addEventListener("change", applyTheme);

// ---------------------------------------------------------------- routing

const desktop = matchMedia("(min-width: 960px)");
let cleanup = null;
let current = { name: "", params: {} };

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, "");
  const [path, query = ""] = raw.split("?");
  const parts = path.split("/").filter(Boolean).map(decodeURIComponent);
  const params = Object.fromEntries(new URLSearchParams(query));
  if (!parts.length) return { name: "home", params };
  const [name, arg] = parts;
  return { name, params: { ...params, code: arg } };
}

export function go(path, replace = false) {
  const url = `#/${path}`;
  if (replace) history.replaceState(null, "", url); else location.hash = url;
  if (replace) route();
}

let navigations = -1;
function route() {
  navigations++;
  cleanup?.();
  cleanup = null;
  current = parseHash();
  const views = { home: homeView, map: mapView, line: lineView, stop: stopView, timetable: timetableView, settings: settingsView };
  const view = views[current.name] || homeView;
  const onMap = current.name === "map" || current.name === "line";
  body.classList.toggle("view-map", onMap);
  body.classList.toggle("view-page", !onMap);
  document.querySelectorAll(".tabbar a").forEach((a) => {
    const tab = a.dataset.tab;
    const active = tab === "map" ? onMap : !onMap;
    if (active) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
  });
  panel.scrollTop = 0;
  M.onStopClick(null);
  M.onBackgroundClick(null);
  M.animateBuses(null);
  cleanup = view(current.params) || null;
  M.resizeMap();
}

// ---------------------------------------------------------------- shared bits

function header({ title, back = true, actions = "", sub = "" }) {
  return `<header class="screen-header">
    ${back ? `<button class="circle" data-action="back" aria-label="${t.back}">${icon("back")}</button>` : ""}
    <div class="title-block"><h1>${title}</h1>${sub ? `<p class="sub">${sub}</p>` : ""}</div>
    ${actions}
  </header>`;
}

function favButton(kind, code) {
  const on = store.isFavourite(kind, code);
  return `<button class="circle fav" data-action="fav" data-kind="${kind}" data-code="${esc(code)}" aria-pressed="${on}" aria-label="${on ? t.unfavourite : t.favourite}">${heart(on)}</button>`;
}

function every(ms, fn) {
  let timer = null, stopped = false;
  const run = async () => {
    if (stopped) return;
    if (!document.hidden) { try { await fn(); } catch { /* shown by the caller */ } }
    if (!stopped) timer = setTimeout(run, ms);
  };
  run();
  const wake = () => { if (!document.hidden && !stopped) { clearTimeout(timer); run(); } };
  document.addEventListener("visibilitychange", wake);
  return () => { stopped = true; clearTimeout(timer); document.removeEventListener("visibilitychange", wake); };
}

function liveErrorText(e) {
  return e instanceof LiveError && e.kind === "offline" ? t.liveOffline : t.liveServer;
}

function holidayNote(moment) {
  if (!moment.holiday) return "";
  return `<p class="notice">${icon("alert", 16)} ${esc(moment.holiday.eve ? t.eveNotice(moment.holiday.name) : t.holidayNotice(moment.holiday.name))}</p>`;
}

const lineRow = (line, extra = "") => `<a class="row" href="#/line/${encodeURIComponent(line.code)}">
  ${badge(line)}<span class="row-main"><span class="row-title">${esc(line.name)}</span>${extra}</span>${icon("chevronRight", 18, "dim")}</a>`;

function stopRow(stop, extra = "") {
  const lines = stop.lines.slice(0, 6).map((c) => badge(db.lines.get(c), { small: true })).join("");
  const more = stop.lines.length > 6 ? `<span class="more">+${stop.lines.length - 6}</span>` : "";
  return `<a class="row" href="#/stop/${encodeURIComponent(stop.code)}">
    ${modeIcon(stop.mode)}<span class="row-main"><span class="row-title">${esc(stop.name)}</span>
    <span class="badges">${lines}${more}</span></span>${extra}</a>`;
}

/** Search field whose results replace `target` while there is a query. */
function bindSearch(input, target, restore) {
  let timer;
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      const q = input.value.trim();
      if (!q) { restore(); return; }
      const { lines, stops } = search(q);
      target.innerHTML = !lines.length && !stops.length
        ? `<div class="panel"><p class="empty">${t.noResults}</p></div>`
        : `${lines.length ? `<h2 class="section">${t.lines}</h2><div class="panel list">${lines.map((l) => lineRow(l, `<span class="row-sub">${esc(l.route || t.modes[l.mode])}</span>`)).join("")}</div>` : ""}
           ${stops.length ? `<h2 class="section">${t.stops}</h2><div class="panel list">${stops.map((s) => stopRow(s, `<span class="code">${esc(s.mode === "BUS" ? s.code : "")}</span>`)).join("")}</div>` : ""}`;
    }, 80);
  });
}

function searchField(id) {
  return `<label class="search" for="${id}">${icon("search", 20)}
    <input id="${id}" type="search" placeholder="${t.searchPlaceholder}" autocomplete="off" spellcheck="false" enterkeyhint="search"></label>`;
}

// ---------------------------------------------------------------- live bus arrivals at a stop

/** Arrivals at a bus stop with ghosts dropped and an estimated time each. */
async function busArrivals(stop) {
  const raw = await fetchStopArrivals(stop.code);
  const out = [];
  for (const a of raw) {
    let detail = null;
    if (db.lines.has(a.line)) detail = await loadLine(a.line).catch(() => null);
    const routes = detail?.routes || {};
    if (!isPlausible(a.position, routes)) continue;
    const eta = estimateEta(a.position, a.stopsLeft, stop.position, routes[a.direction]);
    out.push({ ...a, eta, line: db.lines.get(a.line) || { mode: "BUS", code: a.line, display: a.line, name: a.lineName } });
  }
  return out.sort((x, y) => x.stopsLeft - y.stopsLeft || x.eta.minutes - y.eta.minutes);
}

function arrivalRow(a, compact = false) {
  const dest = a.line.origin ? (a.direction === RETURN ? a.line.origin : a.line.destination) : a.lineName;
  const flags = compact ? "" : `${a.wheelchair ? `<span title="${t.wheelchair}">${icon("wheelchair", 16, "dim")}</span>` : ""}${a.bike ? `<span title="${t.bike}">${icon("bike", 16, "dim")}</span>` : ""}`;
  return `<a class="row arrival" href="#/line/${encodeURIComponent(a.line.code)}?dir=${a.direction}">
    ${badge(a.line)}
    <span class="row-main"><span class="row-title">${esc(t.towards(dest))}</span>
      <span class="row-sub">${esc(t.stopsAway(a.stopsLeft))} ${flags}</span></span>
    <span class="eta${a.eta.reliable ? "" : " unsure"}" title="${a.eta.reliable ? t.approx : t.unreliable}">
      <b>${a.eta.minutes <= 0 ? esc(t.min(0)) : `~${a.eta.minutes}`}</b>${a.eta.minutes > 0 ? `<small>${lang() === "tr" ? "dk" : "min"}</small>` : ""}</span></a>`;
}

function boardRows(board, perGroup = 3) {
  if (!board.groups.length) return `<p class="empty">${t.noMoreToday}</p>`;
  const now = board.moment.minutes;
  return `${board.tomorrow ? `<p class="hint">${t.firstTomorrow}</p>` : ""}${board.groups.map((g) => `
    <a class="row" href="#/line/${encodeURIComponent(g.line.code)}?dir=${g.direction}">
      ${badge(g.line)}<span class="row-main"><span class="row-title">${esc(t.towards(g.headsign))}</span>
      <span class="row-sub tabular">${g.times.slice(0, perGroup).map((m) => clock(m)).join(" · ")}</span></span>
      <span class="eta"><b>${board.tomorrow ? clock(g.times[0]) : g.times[0] - now <= 0 ? t.now : g.times[0] - now}</b>${!board.tomorrow && g.times[0] - now > 0 ? `<small>${lang() === "tr" ? "dk" : "min"}</small>` : ""}</span></a>`).join("")}`;
}

// ---------------------------------------------------------------- home

function homeView() {
  const fav = store.get().favourites;
  panel.innerHTML = `
    ${header({ title: t.appName, back: false, actions: `<a class="circle" href="#/settings" aria-label="${t.settings}">${icon("settings")}</a>` })}
    ${searchField("home-search")}
    <div id="home-body">
      <div id="favs"></div>
      <div id="nearby"></div>
      <footer class="foot">
        <p>${t.about}</p>
        <p><a href="../">${t.getApp}</a>${db.builtAt ? ` · ${t.dataFrom(new Date(db.builtAt).toLocaleDateString(lang() === "tr" ? "tr-TR" : "en-GB", { day: "numeric", month: "long" }))}` : ""}</p>
      </footer>
    </div>`;
  const homeBody = $("#home-body");
  const saved = homeBody.innerHTML;
  let stopPolls = [];
  let editing = false;

  const renderFavs = () => {
    stopPolls.forEach((s) => s());
    stopPolls = [];
    const list = store.get().favourites;
    const box = $("#favs");
    if (!box) return;
    if (!list.length) {
      box.innerHTML = `<h2 class="section">${t.favourites}</h2><div class="panel"><p class="empty">${t.noFavourites}</p></div>`;
      return;
    }
    box.innerHTML = `<div class="section-row"><h2 class="section">${t.favourites}</h2>
      <button class="text-action" data-action="edit-favs">${editing ? t.done : t.edit}</button></div>
      <div class="fav-list">${list.map((key, i) => favCard(key, i, list.length)).join("")}</div>`;
    if (editing) return;
    let busStops = 0;
    list.forEach((key) => {
      const [kind, code] = [key.slice(0, 1), key.slice(2)];
      if (kind !== "s") return;
      const stop = db.stops.get(code);
      const slot = box.querySelector(`[data-slot="${CSS.escape(key)}"]`);
      if (!stop || !slot) return;
      if (stop.mode === "BUS") {
        if (++busStops > 4) { slot.innerHTML = `<p class="hint">${t.openStop} →</p>`; return; }
        stopPolls.push(every(30000, async () => {
          try {
            const arrivals = await busArrivals(stop);
            slot.innerHTML = arrivals.length ? arrivals.slice(0, 3).map((a) => arrivalRow(a, true)).join("") : `<p class="empty">${t.noBuses}</p>`;
          } catch (e) { if (!slot.innerHTML.includes("arrival")) slot.innerHTML = `<p class="hint">${liveErrorText(e)}</p>`; }
        }));
      } else {
        stopPolls.push(every(30000, async () => { slot.innerHTML = boardRows(await stationBoard(stop, 2), 2); }));
      }
    });
  };

  const favCard = (key, i, n) => {
    const [kind, code] = [key.slice(0, 1), key.slice(2)];
    const controls = editing ? `<span class="edit-controls">
      <button class="circle small" data-action="fav-move" data-key="${esc(key)}" data-delta="-1" ${i === 0 ? "disabled" : ""} aria-label="${t.moveUp}">${icon("up", 18)}</button>
      <button class="circle small" data-action="fav-move" data-key="${esc(key)}" data-delta="1" ${i === n - 1 ? "disabled" : ""} aria-label="${t.moveDown}">${icon("down", 18)}</button>
      <button class="circle small" data-action="fav-remove" data-key="${esc(key)}" aria-label="${t.remove}">${icon("trash", 18)}</button></span>` : "";
    if (kind === "l") {
      const line = db.lines.get(code);
      if (!line) return "";
      return `<div class="panel fav-card"><div class="fav-head">${editing ? `${badge(line)}<span class="row-title grow">${esc(line.name)}</span>${controls}` : lineRow(line)}</div></div>`;
    }
    const stop = db.stops.get(code);
    if (!stop) return "";
    return `<div class="panel fav-card">
      <div class="fav-head">${editing ? `${modeIcon(stop.mode)}<span class="row-title grow">${esc(stop.name)}</span>${controls}`
        : `<a class="row" href="#/stop/${encodeURIComponent(code)}">${modeIcon(stop.mode)}<span class="row-main"><span class="row-title">${esc(stop.name)}</span></span>${icon("chevronRight", 18, "dim")}</a>`}</div>
      ${editing ? "" : `<div class="fav-slot" data-slot="${esc(key)}"><p class="hint shimmer">…</p></div>`}</div>`;
  };

  renderFavs();
  const unsub = store.subscribe(() => { if (store.get().favourites.length !== fav.length || editing) renderFavs(); });

  // Nearby stops: asked for only when the visitor taps, remembered once granted.
  const nearby = $("#nearby");
  let expanded = false, found = null;
  const renderNearby = (state) => {
    if (!nearby) return;
    if (state === "ask") {
      nearby.innerHTML = `<h2 class="section">${t.nearby}</h2><div class="panel">
        <button class="pill-button" data-action="locate-home">${icon("locate", 18)} ${t.findNearby}</button>
        <p class="hint">${t.nearbyHint}</p></div>`;
    } else if (typeof state === "string") {
      nearby.innerHTML = `<h2 class="section">${t.nearby}</h2><div class="panel"><p class="hint">${state}</p>
        ${state !== t.locating ? `<button class="pill-button" data-action="locate-home">${icon("locate", 18)} ${t.retry}</button>` : ""}</div>`;
    } else {
      const items = state;
      const shown = expanded ? items : items.slice(0, 3);
      const hidden = items.length - 3;
      nearby.innerHTML = `<h2 class="section">${t.nearby}</h2><div class="panel list">
        ${items.length ? shown.map(({ stop, meters }) => stopRow(stop, `<span class="dist">${t.meters(meters)}</span>`)).join("") : `<p class="empty">${t.noneNearby}</p>`}
        ${hidden > 0 ? `<button class="row more-row" data-action="toggle-nearby">${expanded ? t.showFewer : t.showMore(hidden)} ${icon(expanded ? "chevronUp" : "chevronDown", 18)}</button>` : ""}</div>`;
    }
  };
  const locate = () => {
    renderNearby(t.locating);
    getPosition().then((pos) => {
      store.set({ nearby: true });
      found = nearbyStops(pos, 15);
      renderNearby(found);
    }).catch((err) => renderNearby(err?.code === 1 ? t.locationDenied : t.locationFailed));
  };
  if (store.get().nearby) {
    (navigator.permissions?.query({ name: "geolocation" }) || Promise.reject())
      .then((p) => (p.state === "granted" ? locate() : renderNearby("ask"))).catch(() => renderNearby("ask"));
  } else renderNearby("ask");

  bindSearch($("#home-search"), homeBody, () => { homeBody.innerHTML = saved; renderFavs(); if (found) renderNearby(found); else renderNearby("ask"); });

  const onClick = (e) => {
    const a = e.target.closest("[data-action]");
    if (!a) return;
    const action = a.dataset.action;
    if (action === "locate-home") locate();
    else if (action === "toggle-nearby") { expanded = !expanded; renderNearby(found); }
    else if (action === "edit-favs") { editing = !editing; renderFavs(); }
    else if (action === "fav-move") store.moveFavourite(a.dataset.key, +a.dataset.delta);
    else if (action === "fav-remove") { const [k, c] = [a.dataset.key.slice(0, 1), a.dataset.key.slice(2)]; store.toggleFavourite(k, c); }
  };
  panel.addEventListener("click", onClick);

  // On wide screens the map beside Home shows every line.
  M.clearLine();
  M.showOverview(true);
  return () => { stopPolls.forEach((s) => s()); unsub(); panel.removeEventListener("click", onClick); };
}

let lastPosition = null;
function getPosition() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) { reject(new Error("none")); return; }
    navigator.geolocation.getCurrentPosition(
      (p) => { lastPosition = [p.coords.latitude, p.coords.longitude]; M.setUser(lastPosition); resolve(lastPosition); },
      reject,
      { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 },
    );
  });
}

// ---------------------------------------------------------------- map tab

function mapView() {
  panel.innerHTML = `
    <div class="map-top">${searchField("map-search")}</div>
    <div id="map-results"></div>
    <div id="map-card" class="panel map-card"><p class="hint">${t.zoomForStops}</p></div>
    <button class="circle floating locate" data-action="locate-map" aria-label="${t.myLocation}">${icon("locate")}</button>`;
  M.clearLine();
  M.showOverview(true);
  if (lastPosition) M.setUser(lastPosition);
  const results = $("#map-results");
  bindSearch($("#map-search"), results, () => { results.innerHTML = ""; });
  const card = $("#map-card");

  M.onStopClick((code) => {
    const stop = db.stops.get(code);
    if (!stop) return;
    const near = transfers(stop).slice(0, 3);
    card.innerHTML = `<div class="card-head">${modeIcon(stop.mode)}<div class="grow"><h2>${esc(stop.name)}</h2>
        <p class="sub">${esc(t.modes[stop.mode])}${stop.mode === "BUS" ? ` · ${esc(stop.code)}` : ""}</p></div>
        <button class="circle small" data-action="close-card" aria-label="${t.close}">${icon("close", 18)}</button></div>
      <div class="badges wrap">${stop.lines.map((c) => `<a href="#/line/${encodeURIComponent(c)}?stop=${encodeURIComponent(code)}">${badge(db.lines.get(c))}</a>`).join("")}</div>
      ${near.length ? `<p class="chips">${near.map((n) => `<a class="chip" href="#/stop/${encodeURIComponent(n.stop.code)}">${modeIcon(n.stop.mode, 18)} ${esc(n.stop.name)}</a>`).join("")}</p>` : ""}
      <a class="pill-button primary" href="#/stop/${encodeURIComponent(code)}">${t.openStop}</a>`;
    card.classList.add("open");
  });
  M.onBackgroundClick(() => { card.classList.remove("open"); card.innerHTML = `<p class="hint">${t.zoomForStops}</p>`; });

  const onClick = (e) => {
    const a = e.target.closest("[data-action]");
    if (!a) return;
    if (a.dataset.action === "close-card") { card.classList.remove("open"); card.innerHTML = `<p class="hint">${t.zoomForStops}</p>`; }
    if (a.dataset.action === "locate-map") getPosition().then((p) => M.flyTo(p, 15.5)).catch(() => {});
  };
  panel.addEventListener("click", onClick);
  return () => panel.removeEventListener("click", onClick);
}

// ---------------------------------------------------------------- line

const fitted = { key: null };

function lineView(params) {
  const line = db.lines.get(params.code);
  if (!line) { go("", true); return; }
  M.showOverview(false);
  panel.innerHTML = `<div class="panel line-panel"><p class="hint shimmer">…</p></div>`;
  let detail = null, direction = +params.dir || 0, ring = null;
  let stopped = false;
  const ringEnds = () => (ring === OUTBOUND ? [line.origin, line.destination] : [line.destination, line.origin]);
  const motion = new BusMotion();
  const resolver = new DirectionResolver();
  let previousRaw = [], chosen = new Map(), buses = [], updatedAt = 0, liveError = null;
  let stopPoll = null, clockTimer = null;

  const otherDir = () => detail.directions.find((d) => d !== direction);

  const render = () => {
    if (stopped) return;
    const dirs = detail.directions;
    const loop = dirs.length < 2 || ring != null;
    const anns = db.announcements.get(line.code) || [];
    const conns = [...new Set(db.connections.get(line.code) || [])].sort();
    const live = isBus(line.code);
    let status = "";
    if (live) {
      const here = buses.filter((b) => loop || b.direction === direction).length;
      const there = buses.length - here;
      const age = updatedAt ? Math.round((Date.now() - updatedAt) / 1000) : null;
      status = `<div class="status-row">
        <span class="bus-chip" aria-hidden="true"></span>
        <span class="grow">${here === 0 && there > 0 ? esc(t.noneThisWay(towards(line, detail, direction), there)) : esc(t.buses(here))}${age != null ? ` · <span class="dim">${t.updated(age)}</span>` : ""}</span></div>
        ${liveError ? `<p class="hint warn">${liveError}</p>` : ""}`;
    } else {
      const next = nextFromStart(detail, line, direction);
      const first = db.stops.get(detail.stops[direction]?.[0]);
      const now = serviceMoment().minutes;
      status = `<div class="status-row">${icon("clock", 18, "dim")}<span class="grow">${t.nextDepartures}${first ? ` <span class="dim">${esc(t.fromFirstStop(first.name))}</span>` : ""}</span></div>
        <p class="times tabular">${next.length ? next.map((m) => `<span>${clock(m)}<small>${m - now <= 0 ? t.now : t.inMin(m - now)}</small></span>`).join("") : `<span class="dim">${t.noMoreToday}</span>`}</p>`;
    }
    panel.innerHTML = `<div class="panel line-panel">
      <div class="card-head">
        <button class="circle small" data-action="back" aria-label="${t.back}">${icon("back", 18)}</button>
        ${badge(line)}<h1 class="grow line-name">${esc(line.name)}</h1>${favButton("l", line.code)}</div>
      ${ring ? `<div class="direction-row"><span class="dot" style="background:#2F6FDB"></span><span class="grow">${esc(t.ring(...ringEnds()))}</span></div>` : ""}
      ${!ring && (!loop || line.mode !== "BUS") ? `<div class="direction-row"><span class="dot" style="background:${line.mode === "BUS" ? "#2F6FDB" : line.color}"></span>
        <span class="grow">${esc(t.towards(towards(line, detail, direction)))}</span>
        ${!loop ? `<button class="pill-button small" data-action="switch">${icon("swap", 16)} ${t.switchDirection}</button>` : ""}</div>` : ""}
      ${status}
      ${conns.length ? `<p class="chips"><span class="dim">${t.connects}</span> ${conns.map((c) => `<span class="chip">${esc(t.connectionTypes[c] || "")}</span>`).join("")}</p>` : ""}
      ${anns.length ? `<details class="announcements"><summary>${icon("alert", 16)} ${t.announcements} (${anns.length})</summary>${anns.map((a) => `<p>${esc(a.title)}</p>`).join("")}</details>` : ""}
      <div class="actions"><a class="pill-button" href="#/timetable/${encodeURIComponent(line.code)}?dir=${direction}${params.stop ? `&stop=${encodeURIComponent(params.stop)}` : ""}">${icon("clock", 16)} ${t.timetable}</a></div>
    </div>`;
  };

  const drawRoute = () => {
    // A ring line is one round trip: both halves, in one colour.
    const shown = ring ? [ring, ...detail.directions.filter((d) => d !== ring)] : [direction];
    M.showLine(line, detail, shown, !!ring);
    const key = `${line.code}|${ring ? "ring" : direction}`;
    if (fitted.key !== key) {
      fitted.key = key;
      const pts = shown.flatMap((d) => detail.routes[d]?.points || (detail.stops[d] || []).map((c) => db.stops.get(c)?.position).filter(Boolean));
      M.fitTo(pts, fitPadding());
    }
  };

  const poll = async () => {
    try {
      const raw = await fetchLinePositions(line.code);
      const filtered = filterGhosts(raw, detail.routes, previousRaw, chosen);
      previousRaw = raw;
      chosen = new Map(filtered.map((b) => [b.id, b.position]));
      buses = resolver.resolve(filtered, detail.routes);
      motion.update(buses, detail.routes, performance.now());
      updatedAt = Date.now();
      liveError = null;
    } catch (e) {
      liveError = e instanceof LiveError && e.kind === "busy" ? null : liveErrorText(e);
    }
    render();
  };

  loadLine(line.code).then((d) => {
    if (stopped) return;
    detail = d;
    ring = ringStart(line, detail);
    if (ring) direction = ring;
    if (!detail.directions.includes(direction)) {
      // From a stop: the direction serving it; otherwise outbound.
      direction = (params.stop && detail.directions.find((dir) => detail.stops[dir]?.includes(params.stop))) || detail.directions[0] || OUTBOUND;
    }
    render();
    drawRoute();
    if (isBus(line.code)) {
      stopPoll = every(15000, poll);
      M.animateBuses((now) => motion.drawn(now).filter((b) => ring || detail.directions.length < 2 || b.direction === direction));
    }
    clockTimer = setInterval(render, 15000);
  }).catch(() => { panel.innerHTML = `<div class="panel"><p class="empty">${t.loadFailed}</p></div>`; });

  const onClick = (e) => {
    const a = e.target.closest("[data-action]");
    if (!a || !detail) return;
    if (a.dataset.action === "switch" && !ring) {
      direction = otherDir();
      history.replaceState(null, "", `#/line/${encodeURIComponent(line.code)}?dir=${direction}`);
      render();
      drawRoute();
    }
  };
  panel.addEventListener("click", onClick);
  M.onStopClick((code) => go(`stop/${encodeURIComponent(code)}`));
  return () => {
    stopped = true;
    stopPoll?.();
    clearInterval(clockTimer);
    fitted.key = null;
    panel.removeEventListener("click", onClick);
  };
}

function fitPadding() {
  if (desktop.matches) return { top: 60, bottom: 60, left: 60, right: 60 };
  const card = panel.getBoundingClientRect();
  return { top: 70, left: 30, right: 30, bottom: Math.min(window.innerHeight * 0.55, window.innerHeight - card.top + 30) };
}

// ---------------------------------------------------------------- stop

function stopView(params) {
  const stop = db.stops.get(params.code);
  if (!stop) { go("", true); return; }
  const bus = stop.mode === "BUS";
  const anns = stop.lines.flatMap((c) => (db.announcements.get(c) || []).map((a) => ({ ...a, line: db.lines.get(c) })));
  const near = transfers(stop);
  panel.innerHTML = `
    ${header({ title: `${modeIcon(stop.mode, 30)} <span>${esc(stop.name)}</span>`, sub: `${esc(t.modes[stop.mode])}${bus ? ` · ${esc(stop.code)}` : ""}`, actions: favButton("s", stop.code) })}
    <h2 class="section">${t.linesHere}</h2>
    <div class="panel"><div class="badges wrap big">${stop.lines.map((c) => `<a href="#/line/${encodeURIComponent(c)}?stop=${encodeURIComponent(stop.code)}" title="${esc(db.lines.get(c)?.name || "")}">${badge(db.lines.get(c))}</a>`).join("")}</div>
      ${near.length ? `<p class="chips"><span class="dim">${bus ? t.stationsNearby : t.transfers}:</span> ${near.slice(0, 4).map((n) => `<a class="chip" href="#/stop/${encodeURIComponent(n.stop.code)}">${modeIcon(n.stop.mode, 18)} ${esc(n.stop.name)} <span class="dim">${t.meters(n.meters)}</span></a>`).join("")}</p>` : ""}</div>
    ${anns.length ? `<details class="panel announcements"><summary>${icon("alert", 16)} ${t.announcements} (${anns.length})</summary>${anns.map((a) => `<p>${badge(a.line, { small: true })} ${esc(a.title)}</p>`).join("")}</details>` : ""}
    <div class="section-row"><h2 class="section">${bus ? t.approaching : t.departures}</h2><span class="dim small" id="stop-age"></span></div>
    <div id="stop-holiday"></div>
    <div class="panel list" id="stop-list"><p class="hint shimmer">…</p></div>
    <div id="stop-recent"></div>`;
  const list = $("#stop-list"), age = $("#stop-age");
  let stopPoll, timer, updatedAt = 0, recent = [];

  // Show the stop on the map beside the page (wide screens).
  M.showOverview(false);
  M.showLine({ mode: stop.mode, color: M.BRANDS[stop.mode].primary }, { routes: {}, stops: { 1: [stop.code] } }, [1]);
  M.flyTo(stop.position, 16);

  if (bus) {
    stopPoll = every(20000, async () => {
      try {
        const arrivals = await busArrivals(stop);
        updatedAt = Date.now();
        list.innerHTML = arrivals.length ? arrivals.map((a) => arrivalRow(a)).join("") : `<p class="empty">${t.noBuses}</p>`;
      } catch (e) {
        if (!updatedAt) list.innerHTML = `<p class="empty">${liveErrorText(e)}</p>`;
        else age.textContent = `${t.stale} · ${liveErrorText(e)}`;
      }
    });
    timer = setInterval(() => { if (updatedAt) age.textContent = t.updated(Math.round((Date.now() - updatedAt) / 1000)); }, 1000);
  } else {
    const refresh = async () => {
      const board = await stationBoard(stop);
      $("#stop-holiday").innerHTML = holidayNote(board.moment);
      list.innerHTML = boardRows(board);
      recent = board.recent;
      renderRecent();
    };
    // "Just left" counts up by the second from the scheduled minute.
    const renderRecent = () => {
      const box = $("#stop-recent");
      if (!box) return;
      if (!recent.length) { box.innerHTML = ""; return; }
      const now = istanbulNow();
      const nowSec = serviceMoment(now).minutes * 60 + now.seconds;
      box.innerHTML = `<h2 class="section">${t.justLeft}</h2><div class="panel list">${recent.map((r) => {
        const s = Math.max(0, nowSec - r.minutes * 60);
        return `<a class="row" href="#/line/${encodeURIComponent(r.code)}?dir=${r.direction}">${badge(r.line)}
          <span class="row-main"><span class="row-title">${esc(t.towards(r.headsign))}</span><span class="row-sub tabular">${clock(r.minutes)}</span></span>
          <span class="eta past tabular">+${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}</span></a>`;
      }).join("")}<p class="hint">${t.justLeftNote}</p></div>`;
    };
    stopPoll = every(30000, refresh);
    timer = setInterval(renderRecent, 1000);
  }
  return () => { stopPoll?.(); clearInterval(timer); M.clearLine(); };
}

// ---------------------------------------------------------------- timetable

function timetableView(params) {
  const line = db.lines.get(params.code);
  if (!line) { go("", true); return; }
  panel.innerHTML = `${header({ title: `${badge(line)} <span>${esc(line.name)}</span>`, sub: t.timetable })}<div class="panel"><p class="hint shimmer">…</p></div>`;
  const moment = serviceMoment();
  let day = params.day != null ? +params.day : moment.day;
  let direction = +params.dir || 0;
  let station = params.stop || null;
  let detail = null, scrolled = false, stopped = false;

  const render = () => {
    if (stopped) return;
    // A ring line has one timetable for the whole round trip, from where it starts.
    const ring = ringStart(line, detail);
    const dirs = ring ? [ring] : detail.directions;
    if (!dirs.includes(direction)) direction = (station && dirs.find((d) => detail.stops[d]?.includes(station))) || dirs[0];
    const rail = line.mode !== "BUS";
    const stopsHere = detail.stops[direction] || [];
    if (rail && (!station || !stopsHere.includes(station))) station = stopsHere[0];
    const rows = timetable(detail, line, day, direction, station);
    const today = day === serviceMoment().day;
    const now = serviceMoment().minutes;
    const nextIndex = today ? rows.findIndex((r) => r.minutes >= now) : -1;
    const seg = (items, value, action) => `<div class="segmented" role="tablist">${items.map(([v, label]) => `<button role="tab" data-action="${action}" data-value="${v}" aria-selected="${v === value}">${esc(label)}</button>`).join("")}</div>`;
    const days = [[WEEKDAY, t.weekday], [SATURDAY, t.saturday], [SUNDAY, t.sunday]];
    const dirItems = dirs.map((d) => [d, ring ? t.ringFrom(d === OUTBOUND ? line.origin : line.destination) : t.towards(towards(line, detail, d))]);
    panel.innerHTML = `${header({ title: `${badge(line)} <span>${esc(line.name)}</span>`, sub: t.timetable })}
      ${holidayNote(moment)}
      ${seg(days, day, "day")}
      ${dirs.length > 1 ? seg(dirItems, direction, "dir") : `<p class="hint">${esc(dirItems[0]?.[1] || "")}</p>`}
      ${rail ? `<label class="select-row"><span class="dim">${t.departingFrom}</span>
        <select data-action="station">${stopsHere.map((c) => `<option value="${esc(c)}" ${c === station ? "selected" : ""}>${esc(db.stops.get(c)?.name || c)}</option>`).join("")}</select></label>` : ""}
      <div class="panel">${rows.length ? `<div class="times-grid tabular">${rows.map((r, i) => `<span class="${i === nextIndex ? "next" : i < nextIndex ? "past" : ""}" ${i === nextIndex ? 'id="next-dep"' : ""}>
          ${clock(r.minutes)}${r.minutes >= 1440 ? "<sup>+1</sup>" : ""}${i === nextIndex ? `<small>${r.minutes - now <= 0 ? t.now : t.inMin(r.minutes - now)}</small>` : ""}${rail && r.headsign && r.headsign !== towards(line, detail, direction) ? `<small class="dim">${esc(r.headsign)}</small>` : ""}</span>`).join("")}</div>` : `<p class="empty">${t.noMoreToday}</p>`}</div>`;
    const next = $("#next-dep");
    if (next && !scrolled) { scrolled = true; next.scrollIntoView({ block: "center" }); }
  };

  loadLine(line.code).then((d) => { detail = d; render(); }).catch(() => { panel.innerHTML = `<div class="panel"><p class="empty">${t.loadFailed}</p></div>`; });
  const onClick = (e) => {
    const a = e.target.closest("button[data-action]");
    if (!a || !detail) return;
    if (a.dataset.action === "day") { day = +a.dataset.value; scrolled = false; render(); }
    if (a.dataset.action === "dir") { direction = +a.dataset.value; station = null; scrolled = false; render(); }
  };
  const onChange = (e) => { if (e.target.matches('select[data-action="station"]')) { station = e.target.value; scrolled = false; render(); } };
  panel.addEventListener("click", onClick);
  panel.addEventListener("change", onChange);
  const timer = setInterval(() => detail && render(), 30000);
  M.clearLine();
  M.showOverview(true);
  return () => { stopped = true; clearInterval(timer); panel.removeEventListener("click", onClick); panel.removeEventListener("change", onChange); };
}

// ---------------------------------------------------------------- settings

function settingsView() {
  const render = () => {
    const s = store.get();
    const seg = (items, value, key) => `<div class="segmented">${items.map(([v, label]) => `<button data-set="${key}" data-value="${v}" aria-selected="${v === value}">${label}</button>`).join("")}</div>`;
    panel.innerHTML = `${header({ title: t.settings })}
      <h2 class="section">${t.theme}</h2>${seg([["system", t.system], ["light", t.light], ["dark", t.dark]], s.theme, "theme")}
      <h2 class="section">${t.language}</h2>${seg([["tr", "Türkçe"], ["en", "English"]], lang(), "lang")}
      <div class="panel foot"><p>${t.about}</p><p><a href="../">${t.getApp}</a> · <a href="https://github.com/aijeculate/izmir-transit">GitHub</a> · <a href="../privacy.html">Privacy</a></p></div>`;
  };
  render();
  const onClick = (e) => {
    const b = e.target.closest("[data-set]");
    if (!b) return;
    store.set({ [b.dataset.set]: b.dataset.value });
    if (b.dataset.set === "theme") applyTheme();
    if (b.dataset.set === "lang") { document.documentElement.lang = lang(); translateChrome(); }
    render();
  };
  panel.addEventListener("click", onClick);
  return () => panel.removeEventListener("click", onClick);
}

// ---------------------------------------------------------------- start

panel.addEventListener("click", (e) => {
  const a = e.target.closest("[data-action]");
  if (!a) return;
  if (a.dataset.action === "back") {
    if (navigations > 0) history.back();
    else go("");
  } else if (a.dataset.action === "fav") {
    store.toggleFavourite(a.dataset.kind, a.dataset.code);
    const on = store.isFavourite(a.dataset.kind, a.dataset.code);
    a.setAttribute("aria-pressed", on);
    a.setAttribute("aria-label", on ? t.unfavourite : t.favourite);
    a.innerHTML = heart(on);
  }
});

function translateChrome() {
  document.title = t.appName;
  document.querySelector('.tabbar [data-tab="home"] span').textContent = t.home;
  document.querySelector('.tabbar [data-tab="map"] span').textContent = t.map;
}

async function start() {
  applyTheme();
  document.documentElement.lang = lang();
  translateChrome();
  if (M.mapAvailable()) {
    M.initMap($("#map"), isDark());
  } else {
    $("#map").innerHTML = `<p class="map-failed">${t.mapFailed}</p>`;
  }
  try {
    await Promise.all([loadCore(), loadHolidays()]);
  } catch {
    panel.innerHTML = `<div class="panel"><p class="empty">${t.loadFailed}</p><button class="pill-button" onclick="location.reload()">${t.retry}</button></div>`;
    return;
  }
  M.loadOverview();
  window.addEventListener("hashchange", route);
  desktop.addEventListener("change", () => M.resizeMap());
  route();
  body.classList.add("ready");
}

start();

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => navigator.serviceWorker.register("sw.js").catch(() => {}));
}

// Exposed for tests in the browser console.
window.__izmir = { db, distance };
