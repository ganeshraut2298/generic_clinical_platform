import { Role } from '../../../core/platform/platform.model';

/** Types for the SAH-7 endpoints in `docs/api/opd.yaml`. No field is invented. */

export type ServiceCategory =
  | 'Consultation'
  | 'Procedure'
  | 'Nursing'
  | 'Lab'
  | 'Radiology'
  | 'Dental'
  | 'Package';

export const SERVICE_CATEGORIES: readonly ServiceCategory[] = [
  'Consultation',
  'Procedure',
  'Nursing',
  'Lab',
  'Radiology',
  'Dental',
  'Package',
];

export interface Service {
  id?: string;
  name: string;
  category: ServiceCategory;
  priceRupees: number;
  active?: boolean;
}

export type ServiceUpdate = Partial<Omit<Service, 'id'>>;

export type FeeTiming = 'before' | 'after' | 'split';
export type FollowUpFee = 'free' | 'half' | 'full';

export interface FeeSettings {
  feeTiming: FeeTiming;
  /** 1 to 30 */
  followUpDays: number;
  followUpFee: FollowUpFee;
  /** Percent by role (0 to 100). Keys are `Role` values. */
  discountLimits: Partial<Record<Role, number>>;
  upiOnBill: boolean;
}

export const FEE_TIMINGS: readonly { value: FeeTiming; label: string; hint: string }[] = [
  {
    value: 'before',
    label: 'At the token',
    hint: 'The patient pays when the token is made. Fewer unpaid visits; common in busy GP clinics.',
  },
  {
    value: 'after',
    label: 'After consultation',
    hint: 'The patient pays after seeing the doctor, together with any procedures. Most common in Indian clinics.',
  },
  {
    value: 'split',
    label: 'Split',
    hint: 'Consultation is paid at the token; procedures and medicines are paid after. Good for dental and procedure-heavy clinics.',
  },
];

export const FOLLOW_UP_FEES: readonly { value: FollowUpFee; label: string }[] = [
  { value: 'free', label: 'Free' },
  { value: 'half', label: '50%' },
  { value: 'full', label: 'Full' },
];

/** Roles with a discount limit, in mockup order. */
export const DISCOUNT_ROLES: readonly { role: Role; label: string }[] = [
  { role: 'FRONT_DESK', label: 'Front desk' },
  { role: 'NURSE', label: 'Nurse' },
  { role: 'OWNER', label: 'Owner' },
];

export interface Medicine {
  id: string;
  name: string;
  form?: string;
  schedule?: string;
  usualDose?: string;
  favourite: boolean;
}

export interface MedicinePage {
  items: Medicine[];
  total: number;
}

export type SecondLanguage = 'mr' | 'hi';
export type PaperSize = 'A5' | 'A4';

export interface CasePaperSettings {
  size: PaperSize;
  secondLanguage?: SecondLanguage | null;
  preprinted: boolean;
  caps: boolean;
  /** Section key to on or off. `rx` is always on. */
  sections: Record<string, boolean>;
}

/** Sections of the case paper, in mockup order. `rx` cannot be switched off. */
export const PAPER_SECTIONS: readonly { key: string; label: string }[] = [
  { key: 'complaints', label: 'Chief complaints' },
  { key: 'vitals', label: 'Vitals' },
  { key: 'history', label: 'Past history' },
  { key: 'exam', label: 'Examination' },
  { key: 'diagnosis', label: 'Diagnosis' },
  { key: 'inv', label: 'Investigations' },
  { key: 'rx', label: 'Medicines (Rx)' },
  { key: 'advice', label: 'Advice' },
  { key: 'next', label: 'Next visit' },
];
