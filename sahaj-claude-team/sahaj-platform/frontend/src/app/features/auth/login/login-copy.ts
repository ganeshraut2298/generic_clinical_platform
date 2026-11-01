import { AuthMode } from '../../../core/auth/auth.model';

/** Copy for the login screen (mockup `pLogin`), kept out of the component for readability. */
export const SIDE_TICKS: readonly string[] = [
  'No password to remember: a one-time code each time',
  'Your clinic gets its own web address',
  'Each staff member sees only their own tasks',
];

export const CONSENT_LINE =
  'By continuing you agree to the Terms of Use and Privacy Policy. Patient data is stored in India.';

export const LOCK_MESSAGE = 'Too many wrong codes. Try again in 15 minutes.';

export const NOT_REGISTERED_MESSAGE =
  'This number is not registered. Ask your clinic owner to add you, or sign up to create a new clinic.';

export const ALREADY_HAS_CLINIC_MESSAGE = 'This number already has a clinic. Logging you in.';

export const SIDE_COPY: Record<AuthMode, { heading: string; lede: string }> = {
  SIGNUP: {
    heading: 'Start your clinic on Sahaj',
    lede: 'Verify your mobile number and we will walk you through a five-step setup. It takes about 15 minutes.',
  },
  LOGIN: {
    heading: 'Welcome back',
    lede: 'Log in with the mobile number you registered. Your staff log in with their own numbers.',
  },
};

export const CARD_HEADING: Record<AuthMode, string> = {
  SIGNUP: 'Create your clinic account',
  LOGIN: 'Log in to your clinic',
};
