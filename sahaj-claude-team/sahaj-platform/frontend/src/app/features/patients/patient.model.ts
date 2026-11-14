import { AgeDetail } from '../../shared/format/patient-age';
import { ConsentInput } from '../privacy/privacy.model';

export type { AgeDetail, AgeUnit } from '../../shared/format/patient-age';

/** Types for the SAH-8 endpoints in `docs/api/patient.yaml`. No field is invented. */

export type Sex = 'F' | 'M' | 'O';

export type PatientLanguage = 'mr' | 'hi' | 'en';

/** Matches the `filter` query enum. `due`, `missed`, `overdue` and `lapsed` need visit data
 * that does not exist until SAH-9, so they always come back empty (acceptance criterion 4). */
export type PatientFilter =
  | 'all'
  | 'new'
  | 'due'
  | 'missed'
  | 'chronic'
  | 'overdue'
  | 'lapsed'
  | 'kids'
  | 'seniors';

/** Filter chips in mockup order (`PT_F` in the mockup). */
export const PATIENT_FILTERS: readonly { key: PatientFilter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'new', label: 'New this month' },
  { key: 'due', label: 'Follow-up due' },
  { key: 'missed', label: 'Missed follow-up' },
  { key: 'chronic', label: 'Long-term care' },
  { key: 'overdue', label: 'Check-up overdue' },
  { key: 'lapsed', label: 'Not seen 6+ months' },
  { key: 'kids', label: 'Children' },
  { key: 'seniors', label: '60+' },
];

export interface PatientSummary {
  id: string;
  uhid: string;
  name: string;
  /** Effective completed years (0.2.0). */
  age: number;
  /** What the screen shows as the age (0.2.0). A list row never carries the date of birth. */
  ageDetail: AgeDetail;
  sex: Sex;
  mobile: string;
  chronic?: string | null;
  /** Always null until SAH-9 (Queue/consultation) exists. */
  lastVisitAt?: string | null;
}

export interface PatientPage {
  items: PatientSummary[];
  total: number;
}

export interface PatientCreate {
  name: string;
  /** Required only when `dateOfBirth` is absent; the form never sends both. */
  age?: number;
  /** ISO date, no time (0.2.0). Personal data: never logged or put in a URL. */
  dateOfBirth?: string | null;
  sex: Sex;
  mobile: string;
  area?: string | null;
  language?: PatientLanguage;
  allergy?: string | null;
  chronic?: string | null;
  abhaLinked?: boolean;
  consents?: ConsentInput[];
}

export interface PatientUpdate {
  name?: string;
  age?: number;
  /** Absent = unchanged, a date = set or correct, explicit null = clear (0.2.0). */
  dateOfBirth?: string | null;
  sex?: Sex;
  area?: string | null;
  language?: PatientLanguage;
  allergy?: string | null;
  chronic?: string | null;
  abhaLinked?: boolean;
}

export interface Patient {
  id: string;
  uhid: string;
  name: string;
  age: number;
  ageDetail: AgeDetail;
  /** Present (a date, or null when not recorded) only for owner, doctor, visiting doctor, nurse and front desk. */
  dateOfBirth?: string | null;
  sex: Sex;
  mobile: string;
  area?: string | null;
  language: PatientLanguage;
  allergy?: string | null;
  chronic?: string | null;
  abhaLinked: boolean;
  createdAt: string;
  /** Set when a likely duplicate was found at creation; points at the older, surviving record. */
  possibleDuplicateOfId?: string | null;
}

/** The merge answer: the kept record plus the date of birth notice (SO-73-8). */
export interface PatientMergeResult extends Patient {
  datesOfBirthDiffered: boolean;
}

export const DATES_OF_BIRTH_DIFFERED_NOTICE =
  'The two records have different dates of birth. The date on the kept record was kept.';

export const LANGUAGE_LABELS: Record<PatientLanguage, string> = {
  mr: 'मराठी',
  hi: 'हिंदी',
  en: 'English',
};

export function patientInitials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}
