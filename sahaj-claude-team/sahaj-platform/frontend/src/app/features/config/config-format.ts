import { ApiError } from '../../core/http/problem-detail';
import { formatDisplayDate } from '../../shared/format/ist-date';

const MONTHS =['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/** Today in India as `YYYY-MM-DD`, whatever the device time zone. */
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

/** `2026-10-24` plus `days`, as `YYYY-MM-DD` (calendar arithmetic in UTC, so no time zone drift). */
export function addDays(iso: string, days: number): string {
  const { y, m, d } = parts(iso);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** `24 Oct 2026` */
export function formatDate(iso: string): string {
  return formatDisplayDate(iso);
}

/** `24 Oct` */
export function formatDayMonth(iso: string): string {
  const { m, d } = parts(iso);
  return `${d} ${MONTHS[m - 1]}`;
}

/** `Sat 24 Oct`, for the holiday list. */
export function formatWeekdayDate(iso: string): string {
  const { y, m, d } = parts(iso);
  const weekday = WEEKDAYS[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
  return `${weekday} ${d} ${MONTHS[m - 1]}`;
}

/** `24 Oct 2026, 11:05 AM` in IST. */
export function formatWhen(iso: string | null | undefined): string {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const day = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(date);
  const time = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  })
    .format(date)
    .toUpperCase();
  return `${day}, ${time}`;
}

/**
 * Change-history labels for the audit actions of SAH-31 (ADR-010 D10 for the PIN, ADR-009 D7 for the
 * medicine orders). They name the event and never a person's PIN, name or medicine.
 */
const ACTION_LABELS: Readonly<Record<string, string>> = {
  PLATFORM_PIN_SET: 'PIN set',
  PLATFORM_PIN_CHANGED: 'PIN changed',
  PLATFORM_PIN_RESET: 'PIN reset by the owner',
  PLATFORM_PIN_LOCKED: 'PIN locked after 5 wrong tries',
  PLATFORM_PIN_CLEARED_AFTER_LOCKS: 'PIN switched off after too many wrong tries',
  IPD_MED_ORDERED: 'Medicine ordered',
  IPD_MED_ORDER_BLOCKED: 'Medicine order blocked',
  IPD_MED_STOPPED: 'Medicine stopped',
  IPD_MED_VERIFIED: 'Medicine verified',
  IPD_MED_REJECTED: 'Medicine rejected',
};

const ENTITY_LABELS: Readonly<Record<string, string>> = {
  STAFF_PIN: 'Staff PIN',
  IPD_MEDICINE_ORDER: 'Medicine order',
};

/** "Staff PIN" for `STAFF_PIN`; any other entity type is shown as it comes. */
export function describeEntity(entityType: string): string {
  return ENTITY_LABELS[entityType] ?? entityType;
}

/**
 * Turns an action code such as `clinic.profile_updated` into `Clinic profile updated`. The PIN and
 * medicine order actions have fixed labels; any other code is turned into a sentence as before.
 */
export function describeAction(action: string): string {
  const known = ACTION_LABELS[action];
  if (known) {
    return known;
  }
  if (!/^[a-z0-9._-]+$/i.test(action)) {
    return action;
  }
  const text = action.replace(/[._-]+/g, ' ').trim();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Message from a failed call: the server's own detail first, then a field error, then the fallback. */
export function problemMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    return error.problem.detail ?? error.fieldErrors[0]?.message ?? fallback;
  }
  return fallback;
}
