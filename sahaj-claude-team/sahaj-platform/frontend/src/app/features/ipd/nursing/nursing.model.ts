/**
 * SAH-30 Nursing sheet, tab "Vitals and notes" (mockup `pcNursing`, `pcvsave`, `pcEws`, `pcAgo`).
 * Types follow `NursingSheet`, `VitalsInput` and `VitalsSaved` in docs/api/ipd.yaml. The score is
 * always the server's: the client never calculates or sends one.
 */
export type EwsRisk = 'LOW' | 'MEDIUM' | 'HIGH';
export type ReadOnlyReason = 'ROLE' | 'IPD_READONLY' | 'NOT_ADMITTED';

/** Score of one vitals record (`EwsSummary`). */
export interface EwsSummary {
  score: number;
  risk: EwsRisk;
  recordedAt: string;
}

export interface LatestVitals {
  id: string;
  /** "120/80". */
  bp: string;
  bpSystolic: number;
  bpDiastolic: number;
  pulse: number;
  spo2: number;
  /** Degrees Celsius, one decimal. */
  tempC: number;
  rr: number;
  ewsScore: number;
  ewsRisk: EwsRisk;
  recordedAt: string;
  recordedByName: string;
}

export interface NursingNote {
  id: string;
  text: string;
  recordedAt: string;
  recordedByName: string;
}

export interface NursingSheet {
  admissionId: string;
  patientName: string;
  bedCode: string;
  status: 'ADMITTED' | 'DISCHARGED';
  state: 'trial' | 'on' | 'readonly';
  canRecord: boolean;
  readOnlyReason: ReadOnlyReason | null;
  /** Database time of the read or the save: the base of every "ago" text. */
  asOf: string;
  latest: LatestVitals | null;
  vitalsDue: boolean;
  recentNotes: NursingNote[];
  /** SAH-32: slots of today in state DUE or LATE of the Active orders. The tab count, shown only above 0. */
  doseCount: number;
}

export interface VitalsInput {
  clientKey: string;
  bp: string | null;
  pulse: string | null;
  spo2: string | null;
  temp: string | null;
  rr: string | null;
  note?: string | null;
}

export interface OpenEscalation {
  id: string;
  ewsScore: number;
  ewsRisk: 'MEDIUM' | 'HIGH';
  targetMinutes: 15 | 60;
  status: 'OPEN' | 'ACK';
  raisedAt: string;
}

export interface VitalsSaved extends NursingSheet {
  saved: {
    vitalsId: string;
    ewsScore: number;
    ewsRisk: EwsRisk;
    recordedAt: string;
    noteAdded: boolean;
    replayed: boolean;
  };
  escalation: {
    outcome: 'RAISED' | 'UPDATED' | 'REOPENED' | 'NONE';
    open: OpenEscalation | null;
  };
}

// ---- Text (mockup wording) ----

export const FORMAT_MESSAGE = 'Enter all five vitals. BP as 120/80.';
export const NOTE_TOO_LONG = 'Keep the note to 1,000 characters.';
export const NOTE_MAX = 1000;
export const ROLE_NOTICE = 'Your role can view vitals but not record them.';
export const NO_NOTES = 'No notes yet';
export const NOTE_PLACEHOLDER = 'What you observed and did';
export const LOAD_ERROR = 'We could not load the Nursing sheet. Try again.';
export const SAVE_ERROR = 'We could not save the vitals. What you typed is kept. Try again.';
/** The request may have reached the server: the user checks the sheet before saving again. */
export const LOST_ANSWER =
  'We could not confirm that the vitals were saved. Check the sheet for the last vitals time and the recent notes before saving again.';
export const KEY_ERROR = 'Reload the page and try again.';

export type VitalKey = 'bp' | 'pulse' | 'spo2' | 'temp' | 'rr';

export interface VitalField {
  key: VitalKey;
  label: string;
  unit: string;
  placeholder: string;
  inputMode: 'text' | 'numeric' | 'decimal';
}

/** Mockup order and units (`PC_F`, `fld(...)`). */
export const VITAL_FIELDS: readonly VitalField[] = [
  { key: 'bp', label: 'BP', unit: 'mmHg', placeholder: '120/80', inputMode: 'text' },
  { key: 'pulse', label: 'Pulse', unit: '/min', placeholder: '80', inputMode: 'numeric' },
  { key: 'spo2', label: 'SpO₂', unit: '%', placeholder: '98', inputMode: 'numeric' },
  { key: 'temp', label: 'Temp', unit: '°C', placeholder: '37.0', inputMode: 'decimal' },
  { key: 'rr', label: 'RR', unit: '/min', placeholder: '16', inputMode: 'numeric' },
];

/** The server reports errors in this order (bp, pulse, spo2, temp, rr, note). */
export type NursingField = VitalKey | 'note';
export const FIELD_ORDER: readonly NursingField[] = ['bp', 'pulse', 'spo2', 'temp', 'rr', 'note'];
export type VitalsErrors = Partial<Record<NursingField, string>>;

export interface VitalsDraft {
  bp: string;
  pulse: string;
  spo2: string;
  temp: string;
  rr: string;
  note: string;
}

export const EMPTY_DRAFT: VitalsDraft = { bp: '', pulse: '', spo2: '', temp: '', rr: '', note: '' };

// ---- "ago" from the server clock (mockup pcAgo) ----

/** "35 min ago", "1 h ago", "2 h 5 min ago". Whole minutes from `asOf`, never the device clock. */
export function agoText(asOf: string, instant: string): string {
  const diff = (new Date(asOf).getTime() - new Date(instant).getTime()) / 60000;
  const m = Number.isFinite(diff) ? Math.max(0, Math.floor(diff)) : 0;
  if (m < 60) {
    return `${m} min ago`;
  }
  const h = Math.floor(m / 60);
  const rest = m % 60;
  return rest ? `${h} h ${rest} min ago` : `${h} h ago`;
}

/** "Bed 202 · last vitals 35 min ago", or "Bed 202" when no vitals were recorded. */
export function subtitleText(sheet: NursingSheet): string {
  return sheet.latest
    ? `Bed ${sheet.bedCode} · last vitals ${agoText(sheet.asOf, sheet.latest.recordedAt)}`
    : `Bed ${sheet.bedCode}`;
}

export function noteAgoText(sheet: NursingSheet, note: NursingNote): string {
  return `${agoText(sheet.asOf, note.recordedAt)} · ${note.recordedByName}`;
}

// ---- Score notice (mockup pcNursing) ----

export interface EwsNotice {
  tone: 'good' | 'warn' | 'crit';
  /** "Early warning score" + bold part. */
  strong: string;
  advice: string;
}

const RISK_LABEL: Record<EwsRisk, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' };
const RISK_TONE: Record<EwsRisk, EwsNotice['tone']> = { LOW: 'good', MEDIUM: 'warn', HIGH: 'crit' };
const RISK_ADVICE: Record<EwsRisk, string> = {
  LOW: '. Routine vitals every 4 hours.',
  MEDIUM: '. Repeat vitals within 1 hour.',
  HIGH: '. Inform the doctor now and repeat vitals every 30 minutes.',
};

export function ewsNotice(latest: LatestVitals | null): EwsNotice | null {
  if (!latest) {
    return null;
  }
  return {
    tone: RISK_TONE[latest.ewsRisk],
    strong: `${latest.ewsScore} · ${RISK_LABEL[latest.ewsRisk]}`,
    advice: RISK_ADVICE[latest.ewsRisk],
  };
}

// ---- Prefill ----

/** The five fields prefilled with the last values (temperature with one decimal); the note is always empty. */
export function prefill(latest: LatestVitals | null): VitalsDraft {
  if (!latest) {
    return { ...EMPTY_DRAFT };
  }
  return {
    bp: latest.bp,
    pulse: String(latest.pulse),
    spo2: String(latest.spo2),
    temp: latest.tempC.toFixed(1),
    rr: String(latest.rr),
    note: '',
  };
}

/** Shown before a save whose five values equal the last recorded values (DEC-013, security M3; ADR-006 D5). */
export const SAME_VALUES_NOTICE =
  'These are the same values as the last reading. Check them against the patient, then choose Save again to confirm.';

/** The five values as numbers: BP as systolic/diastolic, temperature to one decimal. Null when the text is not a value. */
function parsedValues(draft: VitalsDraft): string | null {
  const bp = /^(\d{2,3})\/(\d{2,3})$/.exec(draft.bp.trim());
  const whole = [draft.pulse, draft.spo2, draft.rr].map((t) => (/^\d{1,3}$/.test(t.trim()) ? Number(t.trim()) : NaN));
  const temp = /^\d{1,2}(\.\d)?$/.test(draft.temp.trim()) ? Number(draft.temp.trim()).toFixed(1) : null;
  if (!bp || temp === null || whole.some((n) => Number.isNaN(n))) {
    return null;
  }
  return [Number(bp[1]), Number(bp[2]), ...whole, temp].join('|');
}

/**
 * True when the five typed values equal the last recorded values exactly. Compared by value, not by
 * what was typed: a value typed and put back still counts as the same.
 */
export function sameAsLast(draft: VitalsDraft, latest: LatestVitals | null): boolean {
  if (!latest) {
    return false;
  }
  const typed = parsedValues(draft);
  return typed !== null && typed === parsedValues(prefill(latest));
}

// ---- Client-side check (same patterns and messages as VitalsInput) ----

const BP_PATTERN = /^([0-9]{2,3})\/([0-9]{2,3})$/;
const WHOLE_PATTERN = /^[0-9]{1,3}$/;
const TEMP_PATTERN = /^([0-9]{1,2})(?:\.([0-9]))?$/;

const RANGE_MESSAGE = {
  bp: 'Check the BP. Systolic 40 to 300, diastolic 20 to 200.',
  pulse: 'Check the pulse. Enter 1 to 300 per minute.',
  spo2: 'Check the SpO₂. Enter 1 to 100 %.',
  temp: 'Check the temperature. Enter 25.0 to 45.0 °C.',
  rr: 'Check the RR. Enter 1 to 300 per minute.',
} as const;

function wholeError(text: string, max: number, message: string): string | undefined {
  const t = text.trim();
  if (!WHOLE_PATTERN.test(t) || Number(t) === 0) {
    return FORMAT_MESSAGE;
  }
  return Number(t) > max ? message : undefined;
}

function bpError(text: string): string | undefined {
  const m = BP_PATTERN.exec(text.trim());
  if (!m) {
    return FORMAT_MESSAGE;
  }
  const systolic = Number(m[1]);
  const diastolic = Number(m[2]);
  const ok = systolic >= 40 && systolic <= 300 && diastolic >= 20 && diastolic <= 200;
  return ok ? undefined : RANGE_MESSAGE.bp;
}

function tempError(text: string): string | undefined {
  const m = TEMP_PATTERN.exec(text.trim());
  if (!m) {
    return FORMAT_MESSAGE;
  }
  const tenths = Number(m[1]) * 10 + Number(m[2] ?? 0);
  if (tenths === 0) {
    return FORMAT_MESSAGE;
  }
  return tenths >= 250 && tenths <= 450 ? undefined : RANGE_MESSAGE.temp;
}

/** Characters as the server counts them (Unicode code points, line breaks as one). */
export function noteLength(text: string): number {
  return Array.from(text.normalize('NFC').replace(/\r\n|\r/g, '\n').trim()).length;
}

export function validateVitals(draft: VitalsDraft): VitalsErrors {
  const errors: VitalsErrors = {};
  const set = (key: NursingField, message: string | undefined): void => {
    if (message) {
      errors[key] = message;
    }
  };
  set('bp', bpError(draft.bp));
  set('pulse', wholeError(draft.pulse, 300, RANGE_MESSAGE.pulse));
  set('spo2', wholeError(draft.spo2, 100, RANGE_MESSAGE.spo2));
  set('temp', tempError(draft.temp));
  set('rr', wholeError(draft.rr, 300, RANGE_MESSAGE.rr));
  set('note', noteLength(draft.note) > NOTE_MAX ? NOTE_TOO_LONG : undefined);
  return errors;
}

/** Request body: the typed strings (the server is the only judge of the numbers), trimmed; no score. */
export function toVitalsInput(draft: VitalsDraft, clientKey: string): VitalsInput {
  return {
    clientKey,
    bp: draft.bp.trim(),
    pulse: draft.pulse.trim(),
    spo2: draft.spo2.trim(),
    temp: draft.temp.trim(),
    rr: draft.rr.trim(),
    note: draft.note.trim() === '' ? null : draft.note,
  };
}

// ---- Toast (contract text of POST /admissions/{id}/vitals) ----

/** Nothing is sent to anyone yet (no Safety board): the nurse must tell the doctor (DEC-013, security M2). */
export const TELL_DOCTOR = 'Tell the doctor now: no alert is sent.';

/**
 * Low, or Medium/High when no escalation is open any more: "Vitals saved for Asha Patil · early
 * warning 2 (low)". Medium/High with an open escalation: "Escalation raised for Asha Patil · EWS 6 ·
 * doctor review within 1 hour. Tell the doctor now: no alert is sent." The mockup's "duty doctor alerted" is never shown: nothing is sent.
 */
export function savedToast(answer: VitalsSaved): string {
  const open = answer.escalation.open;
  const { ewsScore, ewsRisk } = answer.saved;
  if (ewsRisk === 'LOW' || !open) {
    return `Vitals saved for ${answer.patientName} · early warning ${ewsScore} (${ewsRisk.toLowerCase()})`;
  }
  const within = open.targetMinutes === 15 ? '15 min' : '1 hour';
  return `Escalation raised for ${answer.patientName} · EWS ${ewsScore} · doctor review within ${within}. ${TELL_DOCTOR}`;
}