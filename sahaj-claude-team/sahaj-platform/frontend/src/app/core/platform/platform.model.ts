/**
 * Shared enums from `docs/api/platform.yaml`, used by both the setup wizard (SAH-4) and the
 * app shell (SAH-5). Hand-written from the contract; no value is invented.
 */

export type Role =
  | 'OWNER'
  | 'DOCTOR'
  | 'VISITING_DOCTOR'
  | 'NURSE'
  | 'FRONT_DESK'
  | 'BILLING'
  | 'PHARMACIST'
  | 'COUNSELLOR';

export type Brand = 'blue' | 'teal' | 'green' | 'maroon' | 'custom';

export type Language = 'en' | 'mr' | 'hi';

export const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'Owner',
  DOCTOR: 'Doctor',
  VISITING_DOCTOR: 'Visiting doctor',
  NURSE: 'Nurse',
  FRONT_DESK: 'Front desk',
  BILLING: 'Billing',
  PHARMACIST: 'Pharmacist',
  COUNSELLOR: 'Counsellor',
};

export const BRAND_SWATCHES: readonly { value: Brand; label: string; hex: string }[] = [
  { value: 'blue', label: 'Medical blue', hex: '#1f5bc8' },
  { value: 'teal', label: 'Teal', hex: '#0b8782' },
  { value: 'green', label: 'Green', hex: '#1b8048' },
  { value: 'maroon', label: 'Maroon', hex: '#a3304b' },
];
