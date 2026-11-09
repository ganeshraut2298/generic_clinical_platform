/**
 * Formats a date in `Asia/Kolkata`, whatever the device time zone, as the header shows it:
 * `Sat, 24 Oct 2026 · 11:05 AM`.
 */
import { plainSpaces } from './plain-space';

export function formatIstDateTime(date: Date): string {
  // en-IN puts a comma before the year ("24 Oct, 2026"); the UI rule is "24 Oct 2026".
  const datePart = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    weekday: 'short',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  })
    .format(date)
    .replace(/,(\s+\d{4})$/, '$1');
  // en-IN gives "am"/"pm"; the UI rule is "11:05 AM" (the other formatters upper-case it too).
  const timePart = plainSpaces(
    new Intl.DateTimeFormat('en-IN', {
      timeZone: 'Asia/Kolkata',
      hour: 'numeric',
      minute: '2-digit',
      hour12: true,
    }).format(date),
  ).toUpperCase();
  return `${datePart} · ${timePart}`;
}
