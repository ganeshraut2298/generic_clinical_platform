import { Role } from '../../core/platform/platform.model';

/** Roles that may book, cancel, mark no-show or create a token (story acceptance criteria 3,
 * 5 and 7; the backend enforces the same roles and returns 403 otherwise). */
export function canManageAppointments(role: Role | undefined): boolean {
  return role === 'OWNER' || role === 'FRONT_DESK';
}

/** Roles that may change a doctor's slot settings (story acceptance criterion 5). */
export function canEditSlotSettings(role: Role | undefined): boolean {
  return role === 'OWNER' || role === 'FRONT_DESK';
}

/** Only the owner may change the clinic-wide booking rules, including overbook (story
 * acceptance criterion 5). */
export function canEditBookingRules(role: Role | undefined): boolean {
  return role === 'OWNER';
}
