// Offline support: the app's own files and timetable data are kept, so the site opens and
// shows lines, stops and timetables without a connection. Live bus data is never cached.
const VERSION = "v1";
const SHELL = [
  "./", "index.html", "app.css", "manifest.webmanifest",
  "js/main.js", "js/data.js", "js/geo.js", "js/i18n.js", "js/live.js", "js/map.js", "js/store.js", "js/time.js", "js/ui.js",
  "data/core.json", "data/overview.json",
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET") return;
  const ours = url.origin === location.origin;
  const library = url.hostname === "unpkg.com" || url.hostname.endsWith("fonts.googleapis.com") || url.hostname.endsWith("fonts.gstatic.com");
  if (!ours && !library) return; // live API and map tiles go straight to the network
  e.respondWith(
    caches.open(VERSION).then(async (cache) => {
      if (library) {
        // Versioned library files never change: the saved copy is enough.
        const saved = await cache.match(e.request);
        if (saved) return saved;
        const res = await fetch(e.request);
        if (res.ok || res.type === "opaque") cache.put(e.request, res.clone());
        return res;
      }
      // Our own files: the network first so updates show at once, the saved copy offline.
      try {
        const res = await fetch(e.request);
        if (res.ok) cache.put(e.request, res.clone());
        return res;
      } catch {
        const saved = await cache.match(e.request, { ignoreSearch: true });
        if (saved) return saved;
        throw new Error("offline");
      }
    }),
  );
});
