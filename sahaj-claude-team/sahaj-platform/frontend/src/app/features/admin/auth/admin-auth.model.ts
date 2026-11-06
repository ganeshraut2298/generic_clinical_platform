/** Typed from docs/api/admin.yaml (ADM-1). Field names and types follow the contract. */

export type AdminNext = 'TOTP' | 'ENROL';

export interface AdminLoginBody {
  email: string;
  password: string;
}

export interface AdminLoginResult {
  next: AdminNext;
  /** Opaque, 5 minutes, single use. Keep in component memory only. */
  preAuthToken: string;
  expiresInSeconds: number;
}

export interface TotpEnrolBody {
  preAuthToken: string;
}

export interface TotpEnrolment {
  /** Base32 in groups of 4, for manual entry. */
  secretKey: string;
  otpauthUri: string;
  accountName: string;
  issuer: 'Sahaj admin';
  digits: 6;
  periodSeconds: 30;
  algorithm: 'SHA1';
}

export type TotpVerifyBody =
  | { preAuthToken: string; code: string }
  | { preAuthToken: string; recoveryCode: string };

export interface AdminMe {
  id: string;
  name: string;
  role: 'PLATFORM_ADMIN';
  roleLabel: string;
}

export interface AdminAuthResult {
  accessToken: string;
  expiresInSeconds: number;
  admin: AdminMe;
  /** Only on the verify call that confirms enrolment. Shown once, never stored. */
  recoveryCodes?: string[];
}

/** Problem type slugs under https://sahaj.in/problems/ that the sign-in screens react to. */
export const ADMIN_PROBLEM = {
  credentialsInvalid: 'admin-credentials-invalid',
  totpInvalid: 'totp-invalid',
  preauthExpired: 'preauth-expired',
  sessionEnded: 'admin-session-ended',
  networkDenied: 'admin-network-denied',
  signInLocked: 'admin-sign-in-locked',
  unavailable: 'admin-unavailable',
  totpAlreadyEnrolled: 'totp-already-enrolled',
  totpEnrolmentNotStarted: 'totp-enrolment-not-started',
} as const;

/** `https://sahaj.in/problems/<slug>` to `<slug>`. */
export function problemSlug(type: string): string {
  return type.substring(type.lastIndexOf('/') + 1);
}
