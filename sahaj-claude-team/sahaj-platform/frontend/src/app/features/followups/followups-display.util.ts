import { formatDisplayDate } from '../../shared/format/ist-date';
import { FollowUp, FollowUpStatus } from './followups.model';

export const FOLLOW_UP_TABS: readonly { status: FollowUpStatus; label: string }[] = [
  { status: 'DUE', label: 'Due today' },
  { status: 'MISSED', label: 'Missed' },
  { status: 'UPCOMING', label: 'Upcoming' },
  { status: 'RETURNED', label: 'Returned' },
];

/** Mockup pill text: Today, "N days late", the due date, or "Came back". */
export function followUpPillText(item: FollowUp): string {
  switch (item.status) {
    case 'DUE':
      return 'Today';
    case 'MISSED': {
      const days = item.daysLate ?? 0;
      return days === 1 ? '1 day late' : `${days} days late`;
    }
    case 'UPCOMING':
      return formatDisplayDate(item.dueDate);
    case 'RETURNED':
      return 'Came back';
  }
}

export function followUpPillClass(status: FollowUpStatus): string {
  switch (status) {
    case 'DUE':
      return 'p-warn';
    case 'MISSED':
      return 'p-crit';
    case 'UPCOMING':
      return 'p-blue';
    case 'RETURNED':
      return 'p-good';
  }
}

export function followUpSeverity(status: FollowUpStatus): 'crit' | 'warn' | 'info' {
  return status === 'MISSED' ? 'crit' : status === 'DUE' ? 'warn' : 'info';
}

/** "Dr. Asha Kulkarni" -> "Dr. Asha" (the mockup shows the doctor's short name). */
export function doctorShortName(name: string): string {
  return name.split(/\s+/).slice(0, 2).join(' ');
}
