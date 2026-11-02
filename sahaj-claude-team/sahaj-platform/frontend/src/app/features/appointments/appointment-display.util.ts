import { toMinutes } from '../../shared/scheduling/slot.util';
import { AppointmentStatus } from './appointments.model';

/** `BOOKED` splits into `DUE` and `LATE` for today only (story acceptance criterion 2: a
 * booked slot shown as "Due" becomes "Late" after the no-show window — display only, the
 * appointment is never auto-cancelled). Port of the mockup's `apStatus`. */
export type AppointmentDisplayStatus = 'BOOKED' | 'DUE' | 'LATE' | 'ARRIVED' | 'NOSHOW' | 'CANCELLED';

export function appointmentDisplayStatus(
  status: AppointmentStatus,
  apptTime: string,
  isToday: boolean,
  noShowAfterMinutes: number,
  nowMinutes: number,
): AppointmentDisplayStatus {
  if (status !== 'BOOKED' || !isToday) {
    return status;
  }
  const diff = toMinutes(apptTime) - nowMinutes;
  if (diff < -noShowAfterMinutes) {
    return 'LATE';
  }
  if (diff <= 15) {
    return 'DUE';
  }
  return 'BOOKED';
}

export const APPOINTMENT_DISPLAY_LABELS: Record<AppointmentDisplayStatus, string> = {
  BOOKED: 'Booked',
  DUE: 'Due now',
  LATE: 'Late',
  ARRIVED: 'Arrived',
  NOSHOW: 'No-show',
  CANCELLED: 'Cancelled',
};

/** Maps a display status onto one of the shared `.pill` colour classes. */
export function appointmentDisplayPillClass(displayStatus: AppointmentDisplayStatus): string {
  switch (displayStatus) {
    case 'ARRIVED':
      return 'p-good';
    case 'NOSHOW':
    case 'LATE':
      return 'p-crit';
    case 'DUE':
      return 'p-warn';
    case 'CANCELLED':
      return 'p-wait';
    case 'BOOKED':
    default:
      return 'p-sec';
  }
}
