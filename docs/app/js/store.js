// Per-browser settings and favourites. Browser storage can be missing or throw (private
// windows, blocked site data), so every access is guarded and the app works without it.

const KEY = "izmir-transit";
let state = { favourites: [], theme: "system", lang: null };

try {
  const saved = JSON.parse(localStorage.getItem(KEY) || "null");
  if (saved && typeof saved === "object") state = { ...state, ...saved };
} catch { /* start fresh */ }

const listeners = new Set();
function save() {
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* not saved, still works */ }
  listeners.forEach((fn) => fn(state));
}

export const store = {
  get: () => state,
  subscribe: (fn) => (listeners.add(fn), () => listeners.delete(fn)),
  set(patch) { state = { ...state, ...patch }; save(); },

  /** Favourites are one ordered list: "s:CODE" for stops, "l:CODE" for lines (as in the app). */
  isFavourite: (kind, code) => state.favourites.includes(`${kind}:${code}`),
  toggleFavourite(kind, code) {
    const key = `${kind}:${code}`;
    state = { ...state, favourites: state.favourites.includes(key) ? state.favourites.filter((f) => f !== key) : [...state.favourites, key] };
    save();
  },
  moveFavourite(key, delta) {
    const list = [...state.favourites];
    const i = list.indexOf(key), j = i + delta;
    if (i < 0 || j < 0 || j >= list.length) return;
    [list[i], list[j]] = [list[j], list[i]];
    state = { ...state, favourites: list };
    save();
  },
};
