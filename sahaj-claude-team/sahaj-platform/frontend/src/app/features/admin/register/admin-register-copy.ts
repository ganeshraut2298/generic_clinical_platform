import { ApiError } from '../../../core/http/problem-detail';
import { problemSlug } from '../auth/admin-auth.model';
import { BOOTSTRAP_PROBLEM } from './admin-bootstrap.model';

/**
 * Exact texts of docs/stories/SAH-42.md ("Texts for sign-off"). The page shows these and never
 * the words the server sent, so nothing the caller typed can come back onto the screen.
 */
export const SIDE_PILL = 'Sahaj team only';
export const SIDE_HEADING = 'Create the first admin';
export const SIDE_LEDE = 'Local setup only. This page closes by itself once an admin exists.';

export const UNLOCK_HEADING = 'Unlock registration';
export const UNLOCK_HINT = 'Works only on your own computer while the switch in .env is on.';
export const CREATE_HEADING = 'Create the first admin';
export const PASSWORD_HINT = '12 to 64 characters. Do not use the first part of your email.';

export const PIN_BLANK = 'Enter the PIN.';
export const NAME_ERROR = 'Enter the full name.';
export const EMAIL_ERROR = 'Enter a valid email.';
export const PASSWORD_ERROR =
  'The password must be 12 to 64 characters and must not contain the first part of your email.';
export const PASSWORDS_DIFFER = 'The two passwords do not match.';

export const UNLOCK_EXPIRED = 'Your unlock took too long. Enter the PIN again.';
export const NO_CONNECTION =
  'We could not reach the server. Check that the app is running and try again.';
/** OQ-42-15: the server's 400 bad-request and any answer the page has no story text for. */
export const SOMETHING_WENT_WRONG = 'Something went wrong. Try again.';

export const NAME_MAX = 120;
export const EMAIL_MAX = 254;
export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 64;

export function wrongPinMessage(triesLeft: number | undefined): string {
  if (triesLeft === undefined || !Number.isFinite(triesLeft) || triesLeft < 1) {
    return 'That PIN is not right.';
  }
  return `That PIN is not right. ${triesLeft} ${triesLeft === 1 ? 'try' : 'tries'} left.`;
}

/** Minutes only (story SAH-42-3): the seconds are rounded up, never below 1. */
export function lockedMinutes(retryAfterSeconds: number | undefined): number {
  if (retryAfterSeconds === undefined || !Number.isFinite(retryAfterSeconds) || retryAfterSeconds <= 0) {
    return 60;
  }
  return Math.max(1, Math.ceil(retryAfterSeconds / 60));
}

export function lockedMessage(minutes: number): string {
  return `Registration is locked. Try again in ${minutes} min.`;
}

export type RegisterField = 'fullName' | 'email' | 'password';

export type BootstrapFailure =
  /** The server answered the empty 404: the feature is off, out of reach or closed. */
  | { kind: 'closed' }
  | { kind: 'locked'; minutes: number }
  | { kind: 'wrong-pin'; message: string }
  | { kind: 'ticket-expired' }
  /** Field errors by field (register), or the PIN field (unlock). The text is the page's own. */
  | { kind: 'fields'; fields: Partial<Record<RegisterField | 'pin', string>> }
  | { kind: 'message'; message: string };

const FIELD_TEXT: Record<RegisterField | 'pin', string> = {
  pin: PIN_BLANK,
  fullName: NAME_ERROR,
  email: EMAIL_ERROR,
  password: PASSWORD_ERROR,
};

function isKnownField(name: string): name is RegisterField | 'pin' {
  return Object.prototype.hasOwnProperty.call(FIELD_TEXT, name);
}

/** One place that turns a failed unlock or register call into what the page shows. */
export function describeBootstrapFailure(error: unknown): BootstrapFailure {
  if (!(error instanceof ApiError)) {
    return { kind: 'message', message: SOMETHING_WENT_WRONG };
  }
  const slug = problemSlug(error.problem.type);
  switch (error.status) {
    case 0:
      return { kind: 'message', message: NO_CONNECTION };
    case 404:
      return { kind: 'closed' };
    case 423:
      return { kind: 'locked', minutes: lockedMinutes(error.problem.retryAfterSeconds) };
    case 401:
      return slug === BOOTSTRAP_PROBLEM.pinInvalid
        ? { kind: 'wrong-pin', message: wrongPinMessage(error.problem.triesLeft) }
        : { kind: 'ticket-expired' };
    case 400: {
      if (slug === BOOTSTRAP_PROBLEM.validation) {
        const fields: Partial<Record<RegisterField | 'pin', string>> = {};
        for (const item of error.fieldErrors) {
          if (isKnownField(item.field)) {
            fields[item.field] = FIELD_TEXT[item.field];
          }
        }
        if (Object.keys(fields).length > 0) {
          return { kind: 'fields', fields };
        }
      }
      return { kind: 'message', message: SOMETHING_WENT_WRONG };
    }
    default:
      return { kind: 'message', message: SOMETHING_WENT_WRONG };
  }
}
