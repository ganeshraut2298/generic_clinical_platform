/**
 * The one age rule of the screen (SAH-73, ADR-024 D1 and D8). The server works the age out and wins
 * after every save; this is the same rule in the browser for the read-only preview in the
 * registration dialog and the "Edit details" sheet, and for the date checks. "Today" is the date in
 * India (`Asia/Kolkata`), never the browser's zone. Pure functions, no storage, no logging; a date of
 * birth is never put in a URL, a title or any browser storage.
 */
import { istToday } from './ist-date';

export type AgeUnit = 'YEARS' | 'MONTHS' | 'WEEKS' | 'DAYS';

/** `AgeDetail` of `docs/api/patient.yaml` and `docs/api/opd.yaml` (same shape). */
export interface AgeDetail {
  value: number;
  unit: AgeUnit;
  fromDateOfBirth: boolean;
}

export interface WorkedOutAge {
  /** Completed years (29 Feb birthdays count on 1 Mar in other years). */
  years: number;
  /** Completed months. */
  totalMonths: number;
  /** Days after the completed months. */
  daysAfterMonths: number;
  totalDays: number;
  unit: AgeUnit;
  value: number;
}

interface Ymd {
  y: number;
  m: number;
  d: number;
}

export const DOB_INVALID = 'Enter a valid date of birth.';
export const DOB_FUTURE = 'Date of birth cannot be in the future.';
export const DOB_TOO_OLD = 'Date of birth must be within the last 120 years.';

/** Strict `YYYY-MM-DD` of a date that exists; otherwise null. */
export function parseIsoDate(iso: string | null | undefined): Ymd | null {
  if (typeof iso !== 'string') {
    return null;
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!match) {
    return null;
  }
  const out = { y: Number(match[1]), m: Number(match[2]), d: Number(match[3]) };
  if (out.m < 1 || out.m > 12 || out.d < 1 || out.d > daysInMonth(out.y, out.m)) {
    return null;
  }
  return out;
}

function daysInMonth(y: number, m: number): number {
  return [31, isLeap(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1];
}

function isLeap(y: number): boolean {
  return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
}

function epochDay(v: Ymd): number {
  const date = new Date(0);
  date.setUTCFullYear(v.y, v.m - 1, v.d);
  return Math.round(date.getTime() / 86_400_000);
}

/** Java's `LocalDate.plusMonths`: the day is cut back to the end of a shorter month. */
function plusMonths(v: Ymd, months: number): Ymd {
  const total = v.y * 12 + (v.m - 1) + months;
  const y = Math.floor(total / 12);
  const m = (total % 12) + 1;
  return { y, m, d: Math.min(v.d, daysInMonth(y, m)) };
}

/**
 * The age on `asOf` of a person born on `dob`, both `YYYY-MM-DD`. `asOf` defaults to today in
 * India. Mirrors `com.sahaj.patient.api.PatientAge` (`Period.between`): null when either date is
 * not a real date or `dob` is after `asOf`.
 */
export function workOutAge(dob: string, asOf: string = istToday()): WorkedOutAge | null {
  const from = parseIsoDate(dob);
  const to = parseIsoDate(asOf);
  if (!from || !to || epochDay(from) > epochDay(to)) {
    return null;
  }
  let totalMonths = to.y * 12 + to.m - (from.y * 12 + from.m);
  let days = to.d - from.d;
  if (totalMonths > 0 && days < 0) {
    totalMonths -= 1;
    days = epochDay(to) - epochDay(plusMonths(from, totalMonths));
  }
  const totalDays = epochDay(to) - epochDay(from);
  const years = Math.floor(totalMonths / 12);
  let unit: AgeUnit;
  let value: number;
  if (years >= 2) {
    unit = 'YEARS';
    value = years;
  } else if (totalMonths >= 3) {
    unit = 'MONTHS';
    value = totalMonths;
  } else if (totalDays >= 14) {
    unit = 'WEEKS';
    value = Math.floor(totalDays / 7);
  } else {
    unit = 'DAYS';
    value = totalDays;
  }
  return { years, totalMonths, daysAfterMonths: days, totalDays, unit, value };
}

/** Completed years on `asOf`, or null for a missing, unreal or future date. */
export function completedYears(dob: string, asOf: string = istToday()): number | null {
  return workOutAge(dob, asOf)?.years ?? null;
}

/**
 * The refusal text for a date of birth, or null when it is acceptable (empty is acceptable: the date
 * is optional). Today is accepted. The text never repeats the date.
 */
export function dateOfBirthProblem(value: string | null | undefined, asOf: string = istToday()): string | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  if (!parseIsoDate(value)) {
    return DOB_INVALID;
  }
  const age = workOutAge(value, asOf);
  if (!age) {
    return DOB_FUTURE;
  }
  return age.years > 120 ? DOB_TOO_OLD : null;
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/**
 * The age as text. `row` (list and family rows): years as "34", small children "18 months". `header`
 * (record header, preview): years as "34 yrs". Singular for 1 ("1 day").
 */
export function ageText(detail: Pick<AgeDetail, 'value' | 'unit'>, style: 'row' | 'header' = 'header'): string {
  switch (detail.unit) {
    case 'YEARS':
      return style === 'row' ? String(detail.value) : `${detail.value} yrs`;
    case 'MONTHS':
      return plural(detail.value, 'month', 'months');
    case 'WEEKS':
      return plural(detail.value, 'week', 'weeks');
    default:
      return plural(detail.value, 'day', 'days');
  }
}

/** The age text of a patient answer: `ageDetail` when the server sent it, else the whole-number age. */
export function patientAgeText(
  patient: { age: number; ageDetail?: Pick<AgeDetail, 'value' | 'unit'> | null },
  style: 'row' | 'header' = 'header',
): string {
  return patient.ageDetail ? ageText(patient.ageDetail, style) : style === 'row' ? String(patient.age) : `${patient.age} yrs`;
}
