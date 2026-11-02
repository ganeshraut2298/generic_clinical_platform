/**
 * Types for the `/auth/*` endpoints of `docs/api/platform.yaml` (SAH-3).
 * Hand-written from the contract; no field is invented.
 */

/** 10 digits, starts with 6, 7, 8 or 9. */
export type Mobile = string;

export type AuthMode = 'LOGIN' | 'SIGNUP';

export const MOBILE_PATTERN = /^[6-9][0-9]{9}$/;

export interface OtpRequestBody {
  mobile: Mobile;
  mode: AuthMode;
}

export interface OtpRequestResult {
  /** Seconds to wait before the resend button is usable again. */
  resendAfterSeconds: number;
}

export interface ConsentInput {
  termsVersion: string;
  privacyVersion: string;
}

/** The placeholder legal text version, decided by the human on 1 Nov 2026. */
export const TERMS_VERSION = 'v0.1';
export const PRIVACY_VERSION = 'v0.1';

export interface OtpVerifyBody {
  mobile: Mobile;
  /** Exactly 6 digits. */
  code: string;
  mode: AuthMode;
  consent?: ConsentInput;
}

export interface SelectClinicBody {
  selectionToken: string;
  tenantId: string;
}

export type AuthNext = 'SETUP' | 'APP' | 'CHOOSE_CLINIC' | 'NOT_REGISTERED';

export interface ClinicChoice {
  tenantId: string;
  name: string;
  area?: string;
}

export interface AuthResult {
  next: AuthNext;
  accessToken?: string;
  expiresInSeconds?: number;
  selectionToken?: string;
  clinics?: ClinicChoice[];
}
