/**
 * Types and pure rules for the SAH-28 endpoints in `docs/api/ipd.yaml` (emergency queue, triage,
 * Emergency and triage setup). Names, ages, mobiles and complaints are shown on screen only: they
 * never go to a log, the URL or storage.
 */
import { plainSpaces } from '../../../shared/format/plain-space';
export type TriageScale = 3 | 5;
export type TriageLevel = 1 | 2 | 3 | 4 | 5;
export type EmergencyTone = 'crit' | 'warn' | 'good';
export type EmergencySex = 'M' | 'F' | 'O';
/** SENT_TO_OPD (ipd.yaml 0.3.0, SAH-67-2) is closed like the others; a client treats an unknown status as closed. */
export type EmergencyCaseStatus = 'WAITING' | 'SEEN' | 'ADMITTED' | 'RELEASED' | 'REFERRED' | 'SENT_TO_OPD';
export type EmergencyState = 'trial' | 'on' | 'readonly';

export interface EmergencyLevel {
  level: TriageLevel;
  name: string;
  description: string;
  tone: EmergencyTone;
  targetMinutes: number;
}

export interface EmergencyCounts {
  writable: boolean;
  queue: number;
  overTarget: number;
}

export interface EmergencyCase {
  id: string;
  displayName: string;
  age: number | null;
  sex: EmergencySex;
  complaint: string;
  identityPending: boolean;
  level: TriageLevel;
  levelName: string;
  bay: string | null;
  status: EmergencyCaseStatus;
  arrivedAt: string;
  waitingMinutes: number;
  targetMinutes: number;
  overTarget: boolean;
  seenAt: string | null;
}

/**
 * `EmergencyCaseRef`: id, level and status only (no name). It is the `actedCase` of a 200 answer to a
 * repeated client key when the case is closed or the caller did not register it (security review L1).
 */
export interface EmergencyCaseRef {
  id: string;
  level: TriageLevel;
  status: EmergencyCaseStatus;
}

export interface EmergencyQueue {
  state: EmergencyState;
  scale: TriageScale;
  levels: EmergencyLevel[];
  defaultLevel: TriageLevel;
  unknownPatientRegistration: boolean;
  baysTotal: number;
  baysFree: number;
  counts: EmergencyCounts;
  cases: EmergencyCase[];
  canRegister: boolean;
  canRetriage: boolean;
  canAct: boolean;
  canAdmit: boolean;
  asOf: string;
  /**
   * Only in the answer of an action: the case it applied to, in its new state. A full case, except
   * the `EmergencyCaseRef` of a repeated client key (use `isFullCase` before reading the name).
   */
  actedCase?: EmergencyCase | EmergencyCaseRef;
}

/** Type guard: the full card has a `displayName`; the reference never does. */
export function isFullCase(
  acted: EmergencyCase | EmergencyCaseRef | null | undefined,
): acted is EmergencyCase {
  return !!acted && typeof (acted as Partial<EmergencyCase>).displayName === 'string';
}

export interface EmergencyCaseInput {
  clientKey: string;
  identityUnknown: boolean;
  name?: string | null;
  age?: number | null;
  sex: EmergencySex;
  mobile?: string | null;
  complaint: string;
  level: TriageLevel;
}

export interface EmergencySettingsInput {
  scale: TriageScale;
  targets3: number[];
  targets5: number[];
  bays: number;
  unknownPatientRegistration: boolean;
}

export interface EmergencySettings extends EmergencySettingsInput {
  levels: EmergencyLevel[];
  canEdit: boolean;
}

/** `AdmissionOptions.emergencyCase`: what the Admit sheet needs from an emergency case. */
export interface EmergencyHandoff {
  caseId: string;
  displayName: string;
  age: number | null;
  sex: EmergencySex;
  mobile: string | null;
  complaint: string;
  identityPending: boolean;
  level: TriageLevel;
  levelName: string;
  suggestedBedId: string | null;
}

export const MAX_NAME = 80;
export const MAX_COMPLAINT = 200;
export const MAX_AGE = 120;
export const TARGET_MIN = 0;
export const TARGET_MAX = 240;
export const TARGET_STEP = 5;
export const BAYS_MIN = 0;
export const BAYS_MAX = 10;

export const NO_FREE_BED = 'No free bed. Mark a cleaned bed ready, or transfer a patient.';
export const CASE_CLOSED = 'This patient is no longer in the queue.';
export const RELOAD_AND_RETRY = 'Reload the page and try again.';

export const SEX_CHOICES: readonly { value: EmergencySex; label: string }[] = [
  { value: 'M', label: 'Male' },
  { value: 'F', label: 'Female' },
  { value: 'O', label: 'Other' },
];

export const SCALE_CHOICES: readonly { value: TriageScale; label: string }[] = [
  { value: 3, label: '3 colours' },
  { value: 5, label: '5 levels' },
];

/** Text under the level name on the Triage level buttons: "Immediate · within 5 min" / "Resuscitation · now". */
export function levelHint(level: EmergencyLevel): string {
  return `${level.description} · ${level.targetMinutes > 0 ? `within ${level.targetMinutes} min` : 'now'}`;
}

export function targetText(minutes: number): string {
  return minutes > 0 ? `${minutes} min` : 'now';
}

/** "Waiting 38 min · target 30 min", "target now" when the target is 0. */
export function waitText(item: Pick<EmergencyCase, 'waitingMinutes' | 'targetMinutes'>): string {
  return `Waiting ${item.waitingMinutes} min · target ${targetText(item.targetMinutes)}`;
}

/** Hidden text after a card button's visible label, so a screen reader can tell the cards apart. */
export function forPatientText(item: Pick<EmergencyCase, 'displayName'>): string {
  const name = item.displayName.trim();
  return ` for ${name === '' ? 'unknown patient' : name}`;
}

/** `11:05 AM` in IST from an ISO date-time; empty text for a missing or bad value. */
export function timeOfInstant(instant: string | null | undefined): string {
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

/** Warning after a failed 60-second reload: the screen keeps the last good answer. */
export function staleQueueText(asOf: string | null | undefined): string {
  const time = timeOfInstant(asOf);
  return time === ''
    ? 'Could not refresh the queue.'
    : `Could not refresh the queue. Last updated ${time} IST`;
}

/** "40 M · ER-1"; "?" when the age is not known. */
export function ageSexText(item: Pick<EmergencyCase, 'age' | 'sex' | 'bay'>): string {
  return `${item.age ?? '?'} ${item.sex}${item.bay ? ` · ${item.bay}` : ''}`;
}

export function queueNote(queue: Pick<EmergencyQueue, 'baysFree' | 'baysTotal'>): string {
  return `Most urgent first, then longest waiting · bays free ${queue.baysFree} of ${queue.baysTotal}`;
}

/** Tile "Emergency": the number is "—" when the module is read-only. */
export function tileNumber(counts: EmergencyCounts): string {
  return counts.writable ? String(counts.queue) : '—';
}

export function tileText(counts: EmergencyCounts): { text: string; critical: boolean } {
  return counts.overTarget > 0
    ? { text: `${counts.overTarget} over target time`, critical: true }
    : { text: 'All within target time', critical: false };
}

export const registeredToast = (c: EmergencyCase): string =>
  `${c.displayName} added to the emergency queue · ${c.levelName}${c.bay ? ` · bay ${c.bay}` : ''}`;
export const seenToast = (c: EmergencyCase): string => `${c.displayName} seen by the duty doctor`;
export const retriagedToast = (c: EmergencyCase): string =>
  `${c.displayName} re-triaged to ${c.levelName} · logged`;
export const releasedToast = (c: EmergencyCase): string => `${c.displayName} treated and released`;
export const referredToast = (c: EmergencyCase): string => `${c.displayName} referred out`;

/** Draft of the Quick registration form as typed (all text). */
export interface RegistrationDraft {
  unknown: boolean;
  name: string;
  age: string;
  sex: EmergencySex;
  mobile: string;
  complaint: string;
  level: TriageLevel;
}

export type RegistrationField = 'name' | 'age' | 'mobile' | 'complaint';
export type RegistrationErrors = Partial<Record<RegistrationField, string>>;

const MOBILE = /^[6-9][0-9]{9}$/;
const WHOLE_NUMBER = /^[0-9]+$/;
const codePoints = (text: string): number => Array.from(text).length;

/** Age typed as text: empty = null (shown "?"), a whole number 0 to 120, otherwise `undefined`. */
export function parseAge(text: string): number | null | undefined {
  const t = text.trim();
  if (t === '') {
    return null;
  }
  if (!WHOLE_NUMBER.test(t) || Number(t) > MAX_AGE) {
    return undefined;
  }
  return Number(t);
}

/** The messages of AC4 and `POST /emergency/cases`; the server checks everything again. */
export function validateRegistration(
  draft: RegistrationDraft,
  unknownAllowed: boolean,
): RegistrationErrors {
  const errors: RegistrationErrors = {};
  const name = draft.name.trim();
  if (!(draft.unknown && unknownAllowed)) {
    if (name === '') {
      errors.name = 'Enter a name, or tick "Name not known yet"';
    } else if (codePoints(name) > MAX_NAME) {
      errors.name = `Enter a name of up to ${MAX_NAME} characters`;
    }
  }
  if (parseAge(draft.age) === undefined) {
    errors.age = `Enter the age as a whole number from 0 to ${MAX_AGE}, or leave it empty`;
  }
  const mobile = draft.mobile.trim();
  if (mobile !== '' && !MOBILE.test(mobile)) {
    errors.mobile = 'Enter a 10-digit mobile number, or leave it empty';
  }
  const complaint = draft.complaint.trim();
  if (complaint === '') {
    errors.complaint = 'Enter the main complaint';
  } else if (codePoints(complaint) > MAX_COMPLAINT) {
    errors.complaint = `Enter the main complaint in up to ${MAX_COMPLAINT} characters`;
  }
  return errors;
}

/** Request body from a valid draft. The same `clientKey` is sent again on a retry. */
export function toCaseInput(draft: RegistrationDraft, clientKey: string): EmergencyCaseInput {
  const mobile = draft.mobile.trim();
  return {
    clientKey,
    identityUnknown: draft.unknown === true,
    name: draft.unknown ? null : draft.name.trim(),
    age: parseAge(draft.age) ?? null,
    sex: draft.sex,
    mobile: mobile === '' ? null : mobile,
    complaint: draft.complaint.trim(),
    level: Number(draft.level) as TriageLevel,
  };
}

/** Moved to `shared/client-key` (SAH-30); kept here so existing imports keep working. */
export { newClientKey } from '../../../shared/client-key';

/** Setup row text of a target: "Now" for 0, otherwise "30 min". */
export function targetLabel(minutes: number): string {
  return minutes > 0 ? `${minutes} min` : 'Now';
}

/** Setup list "Red · Immediate". */
export function levelTitle(level: Pick<EmergencyLevel, 'name' | 'description'>): string {
  return `${level.name} · ${level.description}`;
}
