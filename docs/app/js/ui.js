// Small shared pieces of markup: escaping, mode pictograms, line badges, icons.
import { BRANDS } from "./map.js";
import { t } from "./i18n.js";

export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const PICTOGRAMS = {
  BUS: '<path d="M6.5 3.5h11a2 2 0 0 1 2 2V17h-15V5.5a2 2 0 0 1 2-2Z"/><path d="M4.5 11h15M7 17v2.5M17 17v2.5"/><circle cx="8" cy="14" r=".4"/><circle cx="16" cy="14" r=".4"/>',
  METRO: '<rect x="6" y="3" width="12" height="14" rx="3"/><path d="M6 10h12M8.5 21l1.5-4M15.5 21 14 17"/>',
  IZBAN: '<rect x="6" y="3" width="12" height="14" rx="3"/><path d="M6 10h12M8.5 21l1.5-4M15.5 21 14 17M9 6.5h6"/>',
  TRAM: '<rect x="6" y="7" width="12" height="11" rx="3"/><path d="M6 12.5h12M9 3h6M12 3v4M8.5 21l1.5-3M15.5 21 14 18"/>',
  FERRY: '<path d="M4 15l1.5-5h13L20 15M7.5 10V7h9v3M3 19.5c2 1.2 4 1.2 6 0s4-1.2 6 0 4 1.2 6 0"/>',
};

export function modeIcon(mode, size = 32) {
  const b = BRANDS[mode] || BRANDS.BUS;
  return `<span class="mode-icon" style="--brand:${b.primary};--stripe:${b.secondary};width:${size}px;height:${size}px" title="${esc(t.modes[mode] || "")}">
    <svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${PICTOGRAMS[mode] || PICTOGRAMS.BUS}</svg></span>`;
}

export function badge(line, { small = false } = {}) {
  if (!line) return "";
  const b = BRANDS[line.mode] || BRANDS.BUS;
  const fill = line.mode === "BUS" ? b.primary : line.color || b.primary;
  return `<span class="badge${small ? " small" : ""}" style="--fill:${fill};--rim:${b.secondary}">${esc(line.display)}</span>`;
}

const ICONS = {
  back: '<path d="M15 5l-7 7 7 7"/>',
  heart: '<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10Z"/>',
  swap: '<path d="M7 4v14M3 14l4 4 4-4M17 20V6M13 10l4-4 4 4"/>',
  locate: '<circle cx="12" cy="12" r="4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  chevronDown: '<path d="M6 9l6 6 6-6"/>',
  chevronUp: '<path d="M6 15l6-6 6 6"/>',
  chevronRight: '<path d="M9 6l6 6-6 6"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  home: '<path d="M4 11l8-7 8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1Z"/>',
  map: '<path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2ZM9 4v14M15 6v14"/>',
  wheelchair: '<circle cx="10" cy="4.5" r="1.5"/><path d="M10 8v6h5l2 5M7.5 10.5A5.5 5.5 0 1 0 14 18"/>',
  bike: '<circle cx="6" cy="16" r="3.5"/><circle cx="18" cy="16" r="3.5"/><path d="M6 16l4-7h5l3 7M10 9 8.5 6H7M14 6h2"/>',
  alert: '<path d="M12 3 2 20h20L12 3ZM12 10v4M12 17v.5"/>',
  up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
  down: '<path d="M12 5v14M6 13l6 6 6-6"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
};

export function icon(name, size = 20, extra = "") {
  return `<svg class="icon ${extra}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name] || ""}</svg>`;
}

export function heart(on) {
  return `<svg class="icon heart${on ? " on" : ""}" width="22" height="22" viewBox="0 0 24 24" fill="${on ? "currentColor" : "none"}" stroke="currentColor" stroke-width="2" stroke-linejoin="round" aria-hidden="true">${ICONS.heart}</svg>`;
}
