/** Typed from docs/api/admin.yaml 0.5.0 (SAH-42, dev-only bootstrap registration). */

export interface BootstrapUnlockBody {
  pin: string;
}

export interface BootstrapUnlocked {
  /** 43 characters, single use, 5 minutes. Keep in component memory only. */
  ticket: string;
  expiresInSeconds: number;
}

export interface BootstrapRegisterBody {
  ticket: string;
  fullName: string;
  email: string;
  password: string;
}

/** Problem type slugs under https://sahaj.in/problems/ that the registration page reacts to. */
export const BOOTSTRAP_PROBLEM = {
  pinInvalid: 'bootstrap-pin-invalid',
  locked: 'bootstrap-locked',
  ticketInvalid: 'bootstrap-ticket-invalid',
  validation: 'validation',
  badRequest: 'bad-request',
} as const;
