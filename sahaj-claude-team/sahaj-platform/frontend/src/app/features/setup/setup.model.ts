import { Brand, Language, Role } from '../../core/platform/platform.model';

/**
 * Types for `/setup/*` (SAH-4), from `docs/api/platform.yaml`. `answers` on `SetupState` is a
 * contract `object` with no fixed shape; `SetupAnswers` below is this service's own, defensive
 * reading of it (every field optional), not a contract type.
 */

export type SpecialtyCode = 'gp' | 'dental' | 'paeds' | 'homeo';
export type FeeTiming = 'before' | 'after' | 'split';
export type FollowUpFee = 'free' | 'half' | 'full';

export interface SetupClinic {
  name: string;
  address?: string;
  city: string;
  pin?: string;
  phone?: string;
  registrationNo?: string;
}

export interface SetupSpecialty {
  code: SpecialtyCode;
}

export interface SetupDoctor {
  name: string;
  specialty: string;
  feeRupees: number;
  mobile?: string;
}

/** Staff role options for step 3, matching the mockup's list exactly: Front desk, Nurse,
 * Counsellor, Pharmacist. */
export const STAFF_ROLES: readonly Role[] = ['FRONT_DESK', 'NURSE', 'COUNSELLOR', 'PHARMACIST'];

export interface SetupStaffMember {
  name: string;
  role: Role;
  mobile: string;
}

export interface SetupTeam {
  doctors: SetupDoctor[];
  staff: SetupStaffMember[];
}

export interface SetupOpd {
  feeTiming: FeeTiming;
  queue: boolean;
  vitals: boolean;
  followUpDays: number;
  followUpFee: FollowUpFee;
}

export interface SetupLook {
  brand: Brand;
  slug: string;
  language: Language;
}

export type SetupStepBody = SetupClinic | SetupSpecialty | SetupTeam | SetupOpd | SetupLook;

export interface SetupLimits {
  doctors: number;
  users: number;
}

export interface SetupState {
  currentStep: number;
  answers: Record<string, unknown>;
  limits: SetupLimits;
}

export interface SlugAvailability {
  available: boolean;
  alternatives: string[];
}

export interface SetupCompleteResult {
  next: 'APP';
  brand: Brand;
}

/** This service's own shape for the merged `answers` object, read back defensively. */
export interface SetupAnswers {
  clinic?: Partial<SetupClinic>;
  specialty?: Partial<SetupSpecialty>;
  team?: Partial<SetupTeam>;
  opd?: Partial<SetupOpd>;
  look?: Partial<SetupLook>;
}
