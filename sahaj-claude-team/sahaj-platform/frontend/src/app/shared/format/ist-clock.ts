/**
 * Formats a date in `Asia/Kolkata`, whatever the device time zone, as the header shows it:
 * `Sat, 24 Oct 2026 · 11:05 AM`.
 */
export function formatIstDateTime(date: Date): string {
  const datePart = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    weekday: 'short',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  }).format(date);
  const timePart = new Intl.DateTimeFormat('en-IN', {
    timeZone: 'Asia/Kolkata',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  }).format(date);
  return `${datePart} · ${timePart}`;
}
