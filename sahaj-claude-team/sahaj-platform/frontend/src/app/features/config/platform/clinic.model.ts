import { Brand } from '../../../core/platform/platform.model';

/** Types for the SAH-7 endpoints in `docs/api/platform.yaml`. No field is invented. */

export interface ClinicProfile {
  name: string;
  nameLocal?: string;
  address?: string;
  area?: string;
  city?: string;
  pin?: string;
  phone?: string;
  whatsapp?: string;
  mapsUrl?: string;
  registrationNo?: string;
  /** Read-only: the server sets it, so it is never sent back. */
  slug?: string;
  specialtyCode?: string;
  brand?: Brand;
  brandHex?: string;
}

export type HolidayType = 'CLOSED' | 'MORNING_ONLY' | 'EVENING_ONLY';

export interface Holiday {
  id?: string;
  /** `YYYY-MM-DD` */
  date: string;
  name: string;
  type: HolidayType;
}

export const HOLIDAY_TYPES: readonly { value: HolidayType; label: string }[] = [
  { value: 'CLOSED', label: 'Closed' },
  { value: 'MORNING_ONLY', label: 'Morning only' },
  { value: 'EVENING_ONLY', label: 'Evening only' },
];

export interface Specialty {
  code: string;
  name: string;
  features?: string[];
  /** 1 is available now; anything higher shows as "Phase 2". */
  phase: number;
}

export interface AuditEvent {
  id: string;
  actorName?: string;
  action: string;
  entityType?: string;
  before?: Record<string, unknown> | null;
  after?: Record<string, unknown> | null;
  /** ISO date-time */
  at: string;
}

export interface SetupCheck {
  key: string;
  label: string;
  why?: string;
  done: boolean;
  optional?: boolean;
}

export interface SetupOverview {
  percent: number;
  checks: SetupCheck[];
  lastChange?: AuditEvent;
}

export const BRAND_PRESETS: readonly { value: Brand; label: string; hex: string }[] = [
  { value: 'blue', label: 'Medical blue', hex: '#1f5bc8' },
  { value: 'teal', label: 'Teal', hex: '#0b8782' },
  { value: 'green', label: 'Green', hex: '#1b8048' },
  { value: 'maroon', label: 'Maroon', hex: '#a3304b' },
];

export const HEX_COLOUR = /^#[0-9a-fA-F]{6}$/;
