import { Provider } from '@angular/core';
import { DateAdapter, MAT_DATE_FORMATS, MatDateFormats } from '@angular/material/core';
import { formatDisplayDate, istToday } from './ist-date';

/**
 * Date control for forms that send a plain calendar day (`YYYY-MM-DD`, the API's `format: date`).
 *
 * The native `type="date"` input shows the browser's own format. This adapter lets the Material
 * datepicker keep the form value as the ISO string and show "24 Oct 2026" (UI text rule). Because
 * the value is a string, there is no `Date` object and so no device time zone shift, and the
 * forms and API models stay unchanged. Typing is accepted as "24 Oct 2026", "24 October 2026",
 * "24/10/2026" or "2026-10-24". Provide it on the component that uses the datepicker.
 */
export const ISO_DATE_FORMATS: MatDateFormats = {
  parse: { dateInput: 'input' },
  display: {
    dateInput: 'input',
    monthYearLabel: 'month-year',
    dateA11yLabel: 'full',
    monthYearA11yLabel: 'month-year-long',
  },
};

const ISO = /^(\d{4})-(\d{2})-(\d{2})$/;
const MONTHS_LONG = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];
const MONTHS_SHORT = MONTHS_LONG.map((m) => m.slice(0, 3));
const DAYS_LONG = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

function pad(n: number, width: number): string {
  return String(n).padStart(width, '0');
}

function daysIn(year: number, month: number): number {
  return new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
}

function build(year: number, month: number, day: number): string {
  return `${pad(year, 4)}-${pad(month + 1, 2)}-${pad(day, 2)}`;
}

function split(iso: string): { y: number; m: number; d: number } | null {
  const match = ISO.exec(iso);
  if (!match) {
    return null;
  }
  const y = Number(match[1]);
  const m = Number(match[2]) - 1;
  const d = Number(match[3]);
  return m >= 0 && m <= 11 && d >= 1 && d <= daysIn(y, m) ? { y, m, d } : null;
}

/** A `DateAdapter` whose date is the ISO string `YYYY-MM-DD`. */
export class IsoDateAdapter extends DateAdapter<string> {
  private static readonly INVALID = 'Invalid date';

  private parts(date: string): { y: number; m: number; d: number } {
    const p = split(date);
    if (!p) {
      throw new Error(`IsoDateAdapter: "${date}" is not a valid date.`);
    }
    return p;
  }

  getYear(date: string): number {
    return this.parts(date).y;
  }

  getMonth(date: string): number {
    return this.parts(date).m;
  }

  getDate(date: string): number {
    return this.parts(date).d;
  }

  getDayOfWeek(date: string): number {
    const { y, m, d } = this.parts(date);
    return new Date(Date.UTC(y, m, d)).getUTCDay();
  }

  getMonthNames(style: 'long' | 'short' | 'narrow'): string[] {
    if (style === 'long') {
      return [...MONTHS_LONG];
    }
    return style === 'short' ? [...MONTHS_SHORT] : MONTHS_LONG.map((m) => m[0]);
  }

  getDateNames(): string[] {
    return Array.from({ length: 31 }, (_, i) => String(i + 1));
  }

  getDayOfWeekNames(style: 'long' | 'short' | 'narrow'): string[] {
    if (style === 'long') {
      return [...DAYS_LONG];
    }
    return style === 'short' ? DAYS_LONG.map((d) => d.slice(0, 3)) : DAYS_LONG.map((d) => d[0]);
  }

  getYearName(date: string): string {
    return String(this.getYear(date));
  }

  /** Sunday, as on Indian wall calendars. */
  getFirstDayOfWeek(): number {
    return 0;
  }

  getNumDaysInMonth(date: string): number {
    const { y, m } = this.parts(date);
    return daysIn(y, m);
  }

  clone(date: string): string {
    return date;
  }

  createDate(year: number, month: number, date: number): string {
    if (month < 0 || month > 11) {
      throw new Error(`Invalid month index "${month}". Month index has to be between 0 and 11.`);
    }
    if (date < 1) {
      throw new Error(`Invalid date "${date}". Date has to be greater than 0.`);
    }
    if (date > daysIn(year, month)) {
      throw new Error(`Invalid date "${date}" for month with index "${month}".`);
    }
    return build(year, month, date);
  }

  today(): string {
    return istToday();
  }

  parse(value: unknown): string | null {
    if (value === null || value === undefined) {
      return null;
    }
    if (typeof value !== 'string') {
      return this.invalid();
    }
    const text = value.trim();
    if (text === '') {
      return null;
    }
    if (ISO.test(text)) {
      return this.isValid(text) ? text : this.invalid();
    }
    const named = /^(\d{1,2})[ -]([A-Za-z]{3,9})[ -](\d{4})$/.exec(text);
    if (named) {
      const key = named[2].slice(0, 3).toLowerCase();
      const month = MONTHS_SHORT.findIndex((m) => m.toLowerCase() === key);
      return this.fromParts(Number(named[3]), month, Number(named[1]));
    }
    const numeric = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$/.exec(text);
    if (numeric) {
      return this.fromParts(Number(numeric[3]), Number(numeric[2]) - 1, Number(numeric[1]));
    }
    return this.invalid();
  }

  format(date: string, displayFormat: unknown): string {
    if (!this.isValid(date)) {
      throw new Error('IsoDateAdapter: Cannot format invalid date.');
    }
    const { y, m, d } = this.parts(date);
    switch (displayFormat) {
      case 'month-year':
        return `${MONTHS_SHORT[m]} ${y}`;
      case 'month-year-long':
        return `${MONTHS_LONG[m]} ${y}`;
      case 'full':
        return `${DAYS_LONG[this.getDayOfWeek(date)]}, ${d} ${MONTHS_LONG[m]} ${y}`;
      default:
        return formatDisplayDate(date);
    }
  }

  addCalendarYears(date: string, years: number): string {
    return this.addCalendarMonths(date, years * 12);
  }

  addCalendarMonths(date: string, months: number): string {
    const { y, m, d } = this.parts(date);
    const total = y * 12 + m + months;
    const year = Math.floor(total / 12);
    const month = total - year * 12;
    return build(year, month, Math.min(d, daysIn(year, month)));
  }

  addCalendarDays(date: string, days: number): string {
    const { y, m, d } = this.parts(date);
    const moved = new Date(Date.UTC(y, m, d + days));
    return build(moved.getUTCFullYear(), moved.getUTCMonth(), moved.getUTCDate());
  }

  toIso8601(date: string): string {
    return date;
  }

  isDateInstance(obj: unknown): boolean {
    return typeof obj === 'string';
  }

  isValid(date: string): boolean {
    return typeof date === 'string' && split(date) !== null;
  }

  invalid(): string {
    return IsoDateAdapter.INVALID;
  }

  /** The API and the forms use `''` for "no date"; the datepicker wants `null`. */
  override deserialize(value: unknown): string | null {
    if (value === null || value === undefined || value === '') {
      return null;
    }
    return typeof value === 'string' && this.isValid(value) ? value : this.invalid();
  }

  private fromParts(year: number, month: number, day: number): string {
    return month >= 0 && month <= 11 && day >= 1 && day <= daysIn(year, month)
      ? build(year, month, day)
      : this.invalid();
  }
}

/** Providers for a component that uses `matDatepicker` with ISO date strings. */
export function provideIsoDateAdapter(): Provider[] {
  return [
    { provide: DateAdapter, useClass: IsoDateAdapter },
    { provide: MAT_DATE_FORMATS, useValue: ISO_DATE_FORMATS },
  ];
}
