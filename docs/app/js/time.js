// Time in İzmir and which timetable is running. Mirrors TimetableRepository and Holidays:
// the service day rolls over at 04:00, trips after midnight are stored as 1440+ minutes on
// the day before, and public holidays run the Sunday timetable.

export const WEEKDAY = 0, SATURDAY = 1, SUNDAY = 2;
const ROLLOVER = 4 * 60;

/** Wall-clock date and time in Europe/Istanbul, whatever the visitor's own time zone. */
export function istanbulNow(date = new Date()) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-GB", {
      timeZone: "Europe/Istanbul", year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
    }).formatToParts(date).map((p) => [p.type, p.value]),
  );
  return {
    y: +parts.year, m: +parts.month, d: +parts.day,
    minutes: +parts.hour * 60 + +parts.minute, seconds: +parts.second,
  };
}

const iso = (y, m, d) => `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
const addDays = (y, m, d, n) => {
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return [t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate()];
};

const FIXED = {
  "01-01": "Yılbaşı",
  "04-23": "Ulusal Egemenlik ve Çocuk Bayramı",
  "05-01": "Emek ve Dayanışma Günü",
  "05-19": "Atatürk'ü Anma, Gençlik ve Spor Bayramı",
  "07-15": "Demokrasi ve Millî Birlik Günü",
  "08-30": "Zafer Bayramı",
  "10-29": "Cumhuriyet Bayramı",
};
let bayrams = [
  { name: "Ramazan Bayramı", start: "2026-03-20", days: 3 },
  { name: "Kurban Bayramı", start: "2026-05-27", days: 4 },
  { name: "Ramazan Bayramı", start: "2027-03-09", days: 3 },
  { name: "Kurban Bayramı", start: "2027-05-16", days: 4 },
];

/** Later years' bayrams come from the site's holidays.json, the same file the app reads. */
export async function loadHolidays() {
  try {
    const res = await fetch("../holidays.json", { cache: "no-cache" });
    const file = await res.json();
    const extra = (file.bayrams || []).filter((b) => /^\d{4}-\d{2}-\d{2}$/.test(b.start) && b.days >= 1 && b.days <= 5);
    const seen = new Set(bayrams.map((b) => b.start));
    bayrams = bayrams.concat(extra.filter((b) => !seen.has(b.start)));
  } catch { /* the built-in years still apply */ }
}

/** { name, eve } for a public holiday or the half-day before one, else null. */
export function holidayOn(y, m, d) {
  const day = iso(y, m, d);
  for (const b of bayrams) {
    const [sy, sm, sd] = b.start.split("-").map(Number);
    for (let i = 0; i < b.days; i++) if (iso(...addDays(sy, sm, sd, i)) === day) return { name: b.name, eve: false };
    if (iso(...addDays(sy, sm, sd, -1)) === day) return { name: `${b.name} arifesi`, eve: true };
  }
  const md = day.slice(5);
  if (FIXED[md]) return { name: FIXED[md], eve: false };
  if (md === "10-28") return { name: "Cumhuriyet Bayramı arifesi", eve: true };
  return null;
}

export function serviceDayFor(y, m, d) {
  const h = holidayOn(y, m, d);
  if (h && !h.eve) return SUNDAY;
  const weekday = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return weekday === 6 ? SATURDAY : weekday === 0 ? SUNDAY : WEEKDAY;
}

/**
 * The running service day and the minute within it. At 00:30 that is yesterday's timetable
 * at minute 1470, so late trips stored as 1440+ still count as "now".
 */
export function serviceMoment(now = istanbulNow()) {
  let { y, m, d, minutes } = now;
  if (minutes < ROLLOVER) {
    [y, m, d] = addDays(y, m, d, -1);
    minutes += 1440;
  }
  return { day: serviceDayFor(y, m, d), minutes, seconds: now.seconds, date: { y, m, d }, holiday: holidayOn(y, m, d) };
}

/** The service day after the given one, for "first trips tomorrow" late at night. */
export function nextServiceDay(date) {
  const [y, m, d] = addDays(date.y, date.m, date.d, 1);
  return { day: serviceDayFor(y, m, d), holiday: holidayOn(y, m, d) };
}

export function clock(minutes) {
  const t = ((minutes % 1440) + 1440) % 1440;
  return `${String(Math.floor(t / 60)).padStart(2, "0")}:${String(t % 60).padStart(2, "0")}`;
}
