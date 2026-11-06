import { formatClockLabel, formatDisplayDate, isoWeekday } from '../../shared/format/ist-date';
import { AttendanceStatus, LeaveStatus, Shift } from './staff.model';

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export const SHIFT_CYCLE: readonly Shift[] = ['M', 'E', 'N', 'X'];

export const SHIFT_LABEL: Record<Shift, string> = {
  M: 'Morning',
  E: 'Evening',
  N: 'Night',
  X: 'Off',
};

/** Morning -> Evening -> Night -> Off -> Morning. A missing shift counts as Off. */
export function nextShift(current: Shift | null | undefined): Shift {
  const at = SHIFT_CYCLE.indexOf(current ?? 'X');
  return SHIFT_CYCLE[(at + 1) % SHIFT_CYCLE.length];
}

/** `Mon` for a `YYYY-MM-DD` date. */
export function weekdayShort(iso: string): string {
  return WEEKDAYS[isoWeekday(iso)];
}

/** `24 Oct` for a `YYYY-MM-DD` date. */
export function dayMonth(iso: string): string {
  const [, month, day] = iso.split('-').map(Number);
  return `${day} ${MONTHS[month - 1]}`;
}

export const ATTENDANCE_PILL: Record<AttendanceStatus, { text: string; cls: string }> = {
  in: { text: 'Present', cls: 'p-good' },
  late: { text: 'Late', cls: 'p-warn' },
  abs: { text: 'Absent', cls: 'p-crit' },
};

export const ATTENDANCE_OPTIONS: readonly { status: AttendanceStatus; label: string }[] = [
  { status: 'in', label: 'Present' },
  { status: 'late', label: 'Late' },
  { status: 'abs', label: 'Absent' },
];

/** `08:52` -> `in at 8:52 AM`. */
export function inAtText(hhmm: string | null | undefined): string {
  return hhmm ? `in at ${formatClockLabel(hhmm)}` : '';
}

/** Fixed public holidays for India · Maharashtra (SAH-20 acceptance criterion 6). */
export const PUBLIC_HOLIDAYS: readonly { date: string; name: string }[] = [
  { date: '2026-12-25', name: 'Christmas' },
  { date: '2027-01-26', name: 'Republic Day' },
  { date: '2027-05-01', name: 'Maharashtra Day' },
  { date: '2027-08-15', name: 'Independence Day' },
  { date: '2027-10-02', name: 'Gandhi Jayanti' },
];

/** `2 Nov 2026` for one day, `13 Nov 2026 to 15 Nov 2026` for a range. */
export function leaveRange(from: string, to: string): string {
  return from === to
    ? formatDisplayDate(from)
    : `${formatDisplayDate(from)} to ${formatDisplayDate(to)}`;
}

/** `1 booked appointment on that day` / `3 booked appointments on those days`. */
export function affectedText(count: number): string {
  return count === 1
    ? '1 booked appointment on that day'
    : `${count} booked appointments on those days`;
}

export const LEAVE_PILL: Record<LeaveStatus, { text: string; cls: string }> = {
  pending: { text: 'Waiting for owner', cls: 'p-warn' },
  approved: { text: 'Approved', cls: 'p-good' },
  declined: { text: 'Declined', cls: 'p-wait' },
};
