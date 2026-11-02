import { Role } from '../../../core/platform/platform.model';

/** Types for the SAH-6 endpoints in `docs/api/platform.yaml`. No field is invented. */

export type MemberStatus = 'ACTIVE' | 'INACTIVE' | 'REMOVED';

export interface DoctorDetails {
  specialty?: string;
  degree?: string;
  registrationNo?: string;
  feeRupees?: number;
}

export interface TeamMember {
  id: string;
  name: string;
  /** Always masked by the server, for example `••••••7410`. */
  mobileMasked: string;
  role: Role;
  status: MemberStatus;
  lastLoginAt?: string | null;
  doctor?: DoctorDetails;
}

export interface TeamMemberCreate {
  name: string;
  mobile: string;
  role: Role;
  doctor?: DoctorDetails;
}

export interface TeamMemberUpdate {
  name?: string;
  role?: Role;
  doctor?: DoctorDetails;
}

export type PermissionKey =
  | 'token'
  | 'vitals'
  | 'collect'
  | 'cancel'
  | 'refund'
  | 'revenue'
  | 'export';

export interface RolePermission {
  role: Role;
  permission: PermissionKey;
  allowed: boolean;
}

/** Rows of "Who can do what", in mockup order (`cfgTeam`'s `PERM`). */
export const PERMISSION_ROWS: readonly { key: PermissionKey; label: string }[] = [
  { key: 'token', label: 'Make tokens and book appointments' },
  { key: 'vitals', label: 'Enter vitals' },
  { key: 'collect', label: 'Collect payments' },
  { key: 'cancel', label: 'Cancel a bill (with a reason)' },
  { key: 'refund', label: 'Give refunds' },
  { key: 'revenue', label: 'See revenue' },
  { key: 'export', label: 'Export patient data' },
];

/** Columns of the permission table, in mockup order. The owner column is locked on. */
export const PERMISSION_COLUMNS: readonly { role: Role; label: string }[] = [
  { role: 'FRONT_DESK', label: 'Front desk' },
  { role: 'NURSE', label: 'Nurse' },
  { role: 'VISITING_DOCTOR', label: 'Visiting doctor' },
];

/** Roles the owner can choose when adding or changing a person (story acceptance criterion 8,
 * plus Counsellor from the wizard). */
export const TEAM_ROLES: readonly Role[] = [
  'OWNER',
  'DOCTOR',
  'VISITING_DOCTOR',
  'NURSE',
  'FRONT_DESK',
  'BILLING',
  'PHARMACIST',
  'COUNSELLOR',
];

export function isDoctorRole(role: Role): boolean {
  return role === 'DOCTOR' || role === 'VISITING_DOCTOR';
}
