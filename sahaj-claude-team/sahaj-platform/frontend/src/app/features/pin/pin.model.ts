/**
 * SAH-31 personal staff PIN (ADR-010). Types follow `PinStatus`, `PinSetInput`, `PinCodeSent` and
 * `TeamMemberPin` in docs/api/platform.yaml. The PIN, the code and the hash are never in a model
 * that is kept: they live in a form control until the request is sent, and are cleared after.
 */
import { plainSpaces } from '../../shared/format/plain-space';
export type PinState = 'NOT_SET' | 'SET' | 'LOCKED';
export type NotSetReason = 'RESET_BY_OWNER' | 'TOO_MANY_LOCKS';

export interface PinStatus {
  /** False when the server cannot check or save PINs (SAHAJ_PIN_PEPPER not set). */
  available: boolean;
  state: PinState;
  setAt: string | null;
  lockedUntil: string | null;
  triesLeft: number | null;
  notSetReason: NotSetReason | null;
  /** Where the code goes, last 4 digits only. */
  mobileMasked: string;
}

export interface PinSetInput {
  code: string;
  newPin: string;
  confirmPin: string;
}

export interface PinCodeSent {
  resendAfterSeconds: number;
  mobileMasked: string;
}

/** `TeamMember.pin`: OWNER sees it on every row, everyone else only on their own row. */
export interface TeamMemberPin {
  state: PinState;
  setAt: string | null;
  lockedUntil: string | null;
  /**
   * SAH-38 (ADR-014 D9): wrong tries of this member's PIN by colleagues in the last 7 days. Filled
   * for OWNER callers only (every row); null or missing for every other role, own row included.
   */
  colleagueTries7d?: number | null;
}

/** `ColleagueTriesNotice` (SAH-38, ADR-014 D8): the signed-in person's own unseen colleague tries. Counts only. */
export interface ColleagueTriesNotice {
  unseenCount: number;
  /** The newest try counted; sent back as `seenThrough`. Not shown on screen. */
  latestAt: string | null;
  state: PinState;
  lockedUntil: string | null;
  notSetReason: NotSetReason | null;
}

export interface ColleagueTriesSeenInput {
  seenThrough: string;
}

export type ColleagueTriesKind = 'WRONG_TRIES' | 'LOCKED' | 'RIGHT_AFTER_WRONG';

export interface ColleagueTriesEntry {
  kind: ColleagueTriesKind;
  at: string;
  byMembershipId: string | null;
  byName: string | null;
  count: number | null;
}

/** `ColleagueTriesHistory` (OWNER only): the last 7 days of colleague tries on one member's PIN. */
export interface ColleagueTriesHistory {
  membershipId: string;
  asOf: string;
  wrongTries7d: number;
  truncated: boolean;
  entries: ColleagueTriesEntry[];
}

// ---- Texts (docs/api/platform.yaml, docs/api/ipd.yaml, ADR-010 D11 and D12) ----

export const PIN_UNAVAILABLE = 'PIN checks are not available right now. Try again later.';
export const PIN_RATE_LIMIT = 'Too many PIN tries in a short time. Try again in a few minutes.';
export const CODE_REQUIRED = 'Enter the 6-digit code.';
export const PIN_REQUIRED = 'Enter a 4-digit PIN.';
export const PIN_MISMATCH = 'The two PINs do not match.';
export const PIN_STATUS_ERROR = 'We could not load your PIN status. Try again.';
export const SEND_CODE_ERROR = 'We could not send the code. Try again.';
export const SAVE_PIN_ERROR = 'We could not save your PIN. Send a new code and try again.';

export const PIN_SET_TOAST = 'Your PIN is set.';
export const PIN_CHANGED_TOAST = 'Your PIN is changed.';
export const resetToast = (name: string): string => `PIN reset for ${name}.`;

/** Pill on a Team list row (mockup "PIN set"; the other two are ADR-010 D12). */
export const PIN_PILL: Record<PinState, { text: string; css: string }> = {
  SET: { text: 'PIN set', css: 'p-good' },
  LOCKED: { text: 'PIN locked', css: 'p-warn' },
  NOT_SET: { text: 'No PIN yet', css: 'p-wait' },
};

/** The own-row button: "Set my PIN" while there is none, else "Change my PIN". */
export const ownPinButton = (state: PinState): string =>
  state === 'NOT_SET' ? 'Set my PIN' : 'Change my PIN';

export const setSheetTitle = ownPinButton;

/** Why there is no PIN, as the Verify sheet says it (docs/api/ipd.yaml PharmacistCheckRow). */
export function notSetNotice(reason: NotSetReason | null): string {
  switch (reason) {
    case 'RESET_BY_OWNER':
      return 'The clinic owner reset your PIN. Set a new one to verify orders.';
    case 'TOO_MANY_LOCKS':
      return 'Your PIN was switched off after too many wrong tries. Set a new one to verify orders.';
    default:
      return 'You have not set your PIN yet. Set it to verify orders.';
  }
}

/** "Your PIN is locked after 5 wrong tries. Try again at 11:20 AM." */
export const lockedNotice = (time: string): string =>
  time === ''
    ? 'Your PIN is locked after 5 wrong tries. Try again later.'
    : `Your PIN is locked after 5 wrong tries. Try again at ${time}.`;

/** "0:28" from whole seconds. */
export function countdownText(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export type SetPinField = 'code' | 'newPin' | 'confirmPin';
export type SetPinErrors = Partial<Record<SetPinField, string>>;

const FOUR = /^[0-9]{4}$/;
const SIX = /^[0-9]{6}$/;

/** Same steps and messages as the server (order code, newPin, confirmPin); the server decides on weak PINs. */
export function validateSetPin(draft: PinSetInput): SetPinErrors {
  const errors: SetPinErrors = {};
  if (!SIX.test(draft.code)) {
    errors.code = CODE_REQUIRED;
  }
  if (!FOUR.test(draft.newPin)) {
    errors.newPin = PIN_REQUIRED;
  }
  if (draft.confirmPin !== draft.newPin) {
    errors.confirmPin = PIN_MISMATCH;
  }
  return errors;
}

/** Keeps only digits and at most `max` of them: typed letters never reach the field. */
export function digitsOnly(value: string, max: number): string {
  return value.replace(/[^0-9]/g, '').slice(0, max);
}

// ---- SAH-38 colleague tries (ADR-014 D8, D9). All of these texts are NEW: the mockup shows none. ----

/** T2: the banner text. Counts only, never a name. */
export const colleagueTriesText = (count: number): string =>
  count === 1
    ? '1 wrong try of your PIN by a colleague since your last use.'
    : `${count} wrong tries of your PIN by colleagues since your last use.`;

export const GOT_IT = 'Got it';
/** Toast when the "Got it" post fails; the banner returns so the user can tap again (R38-4). NEW wording. */
export const GOT_IT_ERROR = 'We could not save that. Try again.';
export const PIN_SWITCHED_OFF = 'Your PIN was switched off. Set a new one.';
export const NOTICE_LABEL = 'Wrong PIN tries by colleagues';

/** "11:05 AM" in IST from an instant; empty text for a missing or bad value. */
export function istClockText(instant: string | null | undefined): string {
  const date = instant ? new Date(instant) : null;
  if (!date || Number.isNaN(date.getTime())) {
    return '';
  }
  return plainSpaces(
    new Intl.DateTimeFormat('en-IN', {
      timeZone: 'Asia/Kolkata',
      hour: 'numeric',
      minute: '2-digit',
      hour12: true,
    })
      .format(date)
      .toUpperCase(),
  );
}

/** T3: the PIN state line of the banner; null when the state needs no line. */
export function noticeStateLine(notice: ColleagueTriesNotice): string | null {
  if (notice.state === 'LOCKED') {
    const time = istClockText(notice.lockedUntil);
    return time === '' ? 'Your PIN is locked.' : `Your PIN is locked until ${time} IST.`;
  }
  if (notice.state === 'NOT_SET' && notice.notSetReason === 'TOO_MANY_LOCKS') {
    return PIN_SWITCHED_OFF;
  }
  return null;
}

/** T4 and T4b: the owner's row line. Shown only above 0. */
export const colleagueTriesRowText = (count: number): string =>
  count === 1
    ? '1 wrong try by a colleague in 7 days'
    : `${count} wrong tries by colleagues in 7 days`;

/** T11: the history sheet title. */
export const colleagueTriesSheetTitle = (name: string): string => `Wrong PIN tries · ${name}`;
/** T12 */
export const COLLEAGUE_TRIES_EMPTY = 'No wrong tries by colleagues in the last 7 days.';
/** T10: only when the server could find no name. */
export const FORMER_STAFF = 'Former staff member';
export const COLLEAGUE_TRIES_ERROR = 'We could not load the wrong tries. Try again.';
export const COLLEAGUE_TRIES_TRUNCATED = 'Showing the newest 100 entries.';

/** T5: one history line (without its time). */
export function colleagueEntryText(entry: ColleagueTriesEntry): string {
  const name = entry.byName ?? FORMER_STAFF;
  switch (entry.kind) {
    case 'WRONG_TRIES':
      return `Wrong PIN tries by ${name}: ${entry.count ?? 0}`;
    case 'LOCKED':
      return 'PIN locked after wrong tries by colleagues';
    case 'RIGHT_AFTER_WRONG':
      return `Right PIN entered by ${name} after wrong tries`;
  }
}
