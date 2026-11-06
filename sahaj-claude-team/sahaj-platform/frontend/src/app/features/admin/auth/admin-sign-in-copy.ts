import { ApiError } from '../../../core/http/problem-detail';
import { ADMIN_PROBLEM, problemSlug } from './admin-auth.model';

/** Exact texts of ADR-003 D2.7 (mockup `pAdminLogin` where it has them). */
export const SIDE_PILL = 'Sahaj team only';
export const SIDE_HEADING = 'Admin console';
export const SIDE_LEDE =
  'For the Sahaj team to look after clinics on the platform, approve sensitive actions, and configure plans, features and templates.';
export const SIDE_TICKS: readonly string[] = [
  "Every action is logged with the admin's name and a reason",
  'Patient records stay private: admins see counts, not patients',
  "Support sessions need the clinic owner's OTP consent",
];
export const CARD_HINT =
  'Two-step sign-in is always on for admins, and sign-in works only from approved networks.';

export const CODE_TOAST = 'Enter the 6-digit code from your authenticator app';
export const EMAIL_ERROR = 'Enter a valid work email';
export const PASSWORD_ERROR = 'Enter your password';
export const RECOVERY_REQUIRED = 'Enter one of your recovery codes';
export const RECOVERY_FORMAT = 'Enter the recovery code as XXXX-XXXX-XXXX-XXXX';

export const BAD_CREDENTIALS = 'The email, password or code is not right.';
export const PREAUTH_EXPIRED = 'Your sign-in took too long. Start again.';
export const NETWORK_DENIED = 'Sign-in works only from approved networks.';
export const SESSION_ENDED = 'Your session ended. Sign in again.';
export const RATE_LIMITED = 'Too many attempts. Try again in a few minutes.';
export const UNAVAILABLE = 'The admin console is not available right now. Try again later.';
export const NO_CONNECTION = 'Cannot reach the server. Check your connection and try again.';
export const GENERIC_FAILURE = 'Something went wrong. Start again.';

export function lockedMessage(retryAfterSeconds: number | undefined): string {
  if (retryAfterSeconds === undefined || retryAfterSeconds <= 0) {
    return RATE_LIMITED;
  }
  return `Too many attempts. Try again in ${Math.max(1, Math.ceil(retryAfterSeconds / 60))} min.`;
}

export interface SignInFailure {
  message: string;
  /** True when the pre-auth token is no longer usable and the card must start again. */
  reset: boolean;
}

/**
 * One place that turns an API failure from the sign-in calls into the single user text.
 * Never echoes anything the server sent about an email, password or code.
 */
export function describeSignInFailure(error: unknown): SignInFailure {
  if (!(error instanceof ApiError)) {
    return { message: GENERIC_FAILURE, reset: true };
  }
  const slug = problemSlug(error.problem.type);
  switch (error.status) {
    case 0:
      return { message: NO_CONNECTION, reset: false };
    case 401:
      return slug === ADMIN_PROBLEM.preauthExpired
        ? { message: PREAUTH_EXPIRED, reset: true }
        : { message: BAD_CREDENTIALS, reset: false };
    case 403:
      return { message: NETWORK_DENIED, reset: false };
    case 423:
      return { message: lockedMessage(error.problem.retryAfterSeconds), reset: true };
    case 429:
      return { message: RATE_LIMITED, reset: false };
    case 503:
      return { message: UNAVAILABLE, reset: false };
    case 409:
      return { message: GENERIC_FAILURE, reset: true };
    default:
      return { message: GENERIC_FAILURE, reset: false };
  }
}
