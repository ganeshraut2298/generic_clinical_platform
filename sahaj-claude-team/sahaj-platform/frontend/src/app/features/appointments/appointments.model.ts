import { SlotSession } from '../../shared/scheduling/slot.util';

/** Types for the SAH-11 `/opd/doctor-slots`, `/opd/booking-rules` and `/opd/appointments*`
 * endpoints in `docs/api/opd.yaml`. No field is invented. */

export type AppointmentStatus = 'BOOKED' | 'ARRIVED' | 'NOSHOW' | 'CANCELLED';
export type AppointmentVisitType = 'NEW' | 'FOLLOW_UP';
export type AppointmentSource = 'PHONE' | 'WHATSAPP' | 'FRONT_DESK';
export type AppointmentSex = 'F' | 'M' | 'O';

export interface DoctorSlotSettings {
  doctorMembershipId: string;
  /** 5 to 60. */
  slotMinutes: number;
  /** 0=Sunday..6=Saturday. */
  workingDays: number[];
  /** 0 means no slots are reserved for walk-ins. */
  walkInEveryNth: number;
  maxOnlinePerDay: number;
  /** Up to two sessions. */
  sessions: SlotSession[];
}

export interface BookingRules {
  /** 0 to 48. */
  closeBeforeHours: number;
  /** 0 to 48. Stored only; no notification channel yet. */
  remindBeforeHours: number;
  /** 0 to 180. */
  noShowAfterMinutes: number;
  followPriority: boolean;
  /** Stored only; no patient-facing booking channel yet. */
  selfBook: boolean;
  overbook: boolean;
}

export interface AppointmentCreate {
  patientId: string;
  doctorMembershipId: string;
  apptDate: string;
  apptTime: string;
  visitType: AppointmentVisitType;
  source: AppointmentSource;
  note?: string | null;
}

export interface Appointment {
  id: string;
  patientId: string;
  patientName: string;
  age?: number;
  sex?: AppointmentSex;
  doctorMembershipId: string;
  doctorName: string;
  apptDate: string;
  apptTime: string;
  status: AppointmentStatus;
  visitType: AppointmentVisitType;
  source: AppointmentSource;
  note?: string | null;
  visitId?: string | null;
  tokenNo?: number | null;
}

export const APPOINTMENT_VISIT_TYPE_LABELS: Record<AppointmentVisitType, string> = {
  NEW: 'New',
  FOLLOW_UP: 'Follow-up',
};

export const APPOINTMENT_SOURCES: readonly { value: AppointmentSource; label: string }[] = [
  { value: 'PHONE', label: 'Phone' },
  { value: 'WHATSAPP', label: 'WhatsApp' },
  { value: 'FRONT_DESK', label: 'Front desk' },
];

export const APPOINTMENT_SOURCE_LABELS: Record<AppointmentSource, string> = {
  PHONE: 'Phone',
  WHATSAPP: 'WhatsApp',
  FRONT_DESK: 'Front desk',
};

export const APPOINTMENT_STATUS_LABELS: Record<AppointmentStatus, string> = {
  BOOKED: 'Booked',
  ARRIVED: 'Arrived',
  NOSHOW: 'No-show',
  CANCELLED: 'Cancelled',
};

/** Mockup `cfgSlots`' walk-in reserve options. */
export const WALK_IN_EVERY_NTH_OPTIONS: readonly { value: number; label: string }[] = [
  { value: 0, label: 'None' },
  { value: 4, label: 'Every 4th' },
  { value: 3, label: 'Every 3rd' },
  { value: 2, label: 'Every 2nd' },
];

export const SLOT_LENGTH_OPTIONS: readonly number[] = [10, 15, 20, 30];

/** Working-day toggle order, as in the mockup: Monday first, Sunday last. */
export const WORKING_DAY_OPTIONS: readonly { value: number; label: string }[] = [
  { value: 1, label: 'Mon' },
  { value: 2, label: 'Tue' },
  { value: 3, label: 'Wed' },
  { value: 4, label: 'Thu' },
  { value: 5, label: 'Fri' },
  { value: 6, label: 'Sat' },
  { value: 0, label: 'Sun' },
];

/** A fresh doctor who has never saved slot settings gets this starting point, matching the
 * mockup's sample data shape (one morning session, every day, no walk-in reserve). */
export function defaultSlotSettings(doctorMembershipId: string): DoctorSlotSettings {
  return {
    doctorMembershipId,
    slotMinutes: 15,
    workingDays: [1, 2, 3, 4, 5, 6],
    walkInEveryNth: 0,
    maxOnlinePerDay: 20,
    sessions: [{ startTime: '09:00', endTime: '13:00' }],
  };
}

export const DEFAULT_BOOKING_RULES: BookingRules = {
  closeBeforeHours: 1,
  remindBeforeHours: 2,
  noShowAfterMinutes: 15,
  followPriority: false,
  selfBook: false,
  overbook: false,
};
