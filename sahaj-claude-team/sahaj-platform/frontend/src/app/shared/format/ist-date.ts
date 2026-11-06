/**
 * Calendar-day helpers in `Asia/Kolkata`, independent of the device time zone. Shared by any
 * feature that needs "today"/"tomorrow" as a plain date, such as Appointments (SAH-11).
 */

/** Today in India as `YYYY-MM-DD`. */
export function istToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

function parts(iso: string): { y: number; m: number; d: number } {
  const [y, m, d] = iso.split('-').map(Number);
  return { y, m, d };
}

/** `iso` plus `days`, as `YYYY-MM-DD` (calendar arithmetic in UTC, so no time zone drift). */
export function addDays(iso: string, days: number): string {
  const { y, m, d } = parts(iso);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Day of week for a `YYYY-MM-DD` date, 0=Sunday..6=Saturday, independent of local time zone. */
export function isoWeekday(iso: string): number {
  const { y, m, d } = parts(iso);
  return new Date(Date.UTC(y, m - 1, d)).getUTCDay();
}

/** The current time of day in India, in minutes since midnight. */
export function nowMinutesIst(now: Date = new Date()): number {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(now);
  const hour = Number(parts.find((p) => p.type === 'hour')?.value ?? 0);
  const minute = Number(parts.find((p) => p.type === 'minute')?.value ?? 0);
  return hour * 60 + minute;
}

/** `09:00` -> `9:00 AM`. */
export function formatClockLabel(hhmm: string): string {
  const [h, m] = hhmm.split(':').map(Number);
  const period = h < 12 ? 'AM' : 'PM';
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  return `${hour12}:${String(m).padStart(2, '0')} ${period}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** `2026-10-24` -> `24 Oct 2026`. Pure string work, so the device time zone never shifts the day. */
export function formatDisplayDate(iso: string): string {
  const [year, month, day] = iso.split('-').map(Number);
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/** An instant (ISO date-time) as its IST calendar day, `24 Oct 2026`. Empty text for a missing or bad value. */
export function formatDisplayDateOfInstant(instant: string | null | undefined): string {
  if (!instant) {
    return '';
  }
  const date = new Date(instant);
  return Number.isNaN(date.getTime()) ? '' : formatDisplayDate(istToday(date));
}

/** An instant as `24 Oct 2026, 11:05 AM` in IST. Empty text for a missing or bad value. */
export function formatDisplayDateTimeOfInstant(instant: string | null | undefined): string {
  if (!instant) {
    return '';
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const time = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  })
    .format(date)
    .toUpperCase();
  return `${formatDisplayDate(istToday(date))}, ${time}`;
}
