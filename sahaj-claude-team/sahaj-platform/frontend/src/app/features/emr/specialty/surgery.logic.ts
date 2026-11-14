import {
  AdmissionPlan,
  Anaesthesia,
  FitnessTestKey,
  FitnessTestState,
  PacState,
  PreopBlood,
  SpSurgeryFitness,
  SpSurgeryPlan,
  SurgeryPayer,
  SurgeryProcedureKey,
} from './specialty.model';

/**
 * General Surgery rules (SAH-64, SAH-71). Every number is "value from the EMR mockup, accepted by the
 * human in DEC-039, not clinically reviewed" (C-7, L-3, B-1, B-3). The server recomputes; its answer wins.
 */

/** The mockup draws nine rows; HIV, HBsAg and HCV are one row that moves the three keys together. */
export const TEST_ROWS: readonly { label: string; keys: FitnessTestKey[] }[] = [
  { label: 'CBC', keys: ['CBC'] },
  { label: 'LFT', keys: ['LFT'] },
  { label: 'KFT', keys: ['KFT'] },
  { label: 'PT / INR', keys: ['PT_INR'] },
  { label: 'Blood sugar', keys: ['BLOOD_SUGAR'] },
  { label: 'ECG', keys: ['ECG'] },
  { label: 'Chest X-ray', keys: ['CHEST_XRAY'] },
  { label: 'HIV, HBsAg, HCV', keys: ['HIV', 'HBSAG', 'HCV'] },
  { label: 'Blood group', keys: ['BLOOD_GROUP'] },
];

export const TEST_STATE_LABEL: Record<FitnessTestState, string> = { PENDING: 'Pending', NORMAL: 'Normal', ABNORMAL: 'Abnormal' };
const NEXT_TEST_STATE: Record<FitnessTestState, FitnessTestState> = { PENDING: 'NORMAL', NORMAL: 'ABNORMAL', ABNORMAL: 'PENDING' };

export function testState(value: SpSurgeryFitness, key: FitnessTestKey): FitnessTestState {
  return value.tests.find((t) => t.key === key)?.state ?? 'PENDING';
}

/** "Tap a test to move it: pending → normal → abnormal." A row of three keys moves together. */
export function cycleTest(value: SpSurgeryFitness, keys: readonly FitnessTestKey[]): SpSurgeryFitness {
  const next = NEXT_TEST_STATE[rowState(value, keys)];
  const rest = value.tests.filter((t) => !keys.includes(t.key));
  const added = next === 'PENDING' ? [] : keys.map((key) => ({ key, state: next }));
  return { ...value, tests: [...rest, ...added] };
}

export function rowState(value: SpSurgeryFitness, keys: readonly FitnessTestKey[]): FitnessTestState {
  const states = keys.map((k) => testState(value, k));
  return states.includes('ABNORMAL') ? 'ABNORMAL' : states.includes('PENDING') ? 'PENDING' : 'NORMAL';
}

export interface FitnessSummary {
  state: 'FIT' | 'TESTS_PENDING' | 'PAC_NOT_DONE' | 'NOT_FIT';
  text: string;
}

/** SAH-64-2 as drawn: not fit (abnormal test or PAC unfit), tests pending, PAC not done, else fit. */
export function fitnessSummary(value: SpSurgeryFitness): FitnessSummary {
  const pending = TEST_ROWS.filter((r) => rowState(value, r.keys) === 'PENDING').map((r) => r.label);
  const abnormal = TEST_ROWS.filter((r) => rowState(value, r.keys) === 'ABNORMAL').map((r) => r.label);
  if (value.pac === 'UNFIT' || abnormal.length > 0) {
    return { state: 'NOT_FIT', text: `Not fit yet: ${abnormal.length > 0 ? `${abnormal.join(', ')} abnormal` : 'PAC unfit'}` };
  }
  if (pending.length > 0) {
    return { state: 'TESTS_PENDING', text: `${pending.length} test(s) pending: ${pending.join(', ')}` };
  }
  if (value.pac === 'NOT_DONE') {
    return { state: 'PAC_NOT_DONE', text: 'PAC not done' };
  }
  return { state: 'FIT', text: 'Fit for surgery' };
}

/** Warnings are information, not a block: a blood thinner, another illness, PAC unfit. */
export function fitnessWarnings(value: SpSurgeryFitness): { blood: boolean; illness: boolean; pac: boolean } {
  return {
    blood: !!value.bloodThinner && value.bloodThinner !== 'NONE',
    illness: value.otherIllness.some((i) => i !== 'NONE'),
    pac: value.pac === 'UNFIT',
  };
}

export const PAC_LABEL: Record<PacState, string> = { NOT_DONE: 'Not done', FIT: 'Fit', FIT_WITH_CAUTION: 'Fit with caution', UNFIT: 'Unfit' };

export interface ProcedureDefault {
  key: SurgeryProcedureKey;
  label: string;
  anaesthesia: Anaesthesia;
  admission: AdmissionPlan;
}

/** C-7: the drawn procedures with their default anaesthesia and stay (the sample rates are never used). */
export const PROCEDURES: readonly ProcedureDefault[] = [
  { key: 'LAP_CHOLECYSTECTOMY', label: 'Lap cholecystectomy', anaesthesia: 'GA', admission: 'IPD_1_2_DAYS' },
  { key: 'OPEN_CHOLECYSTECTOMY', label: 'Open cholecystectomy', anaesthesia: 'GA', admission: 'IPD_4_5_DAYS' },
  { key: 'LAP_HERNIA_TEP', label: 'Lap hernia repair (TEP)', anaesthesia: 'GA', admission: 'IPD_1_DAY' },
  { key: 'OPEN_MESH_HERNIOPLASTY', label: 'Open mesh hernioplasty', anaesthesia: 'SPINAL', admission: 'IPD_2_DAYS' },
  { key: 'LAP_APPENDICECTOMY', label: 'Lap appendicectomy', anaesthesia: 'GA', admission: 'IPD_1_2_DAYS' },
  { key: 'HAEMORRHOIDECTOMY', label: 'Haemorrhoidectomy', anaesthesia: 'SPINAL', admission: 'IPD_1_DAY' },
  { key: 'EXCISION_LIPOMA', label: 'Excision of lipoma', anaesthesia: 'LOCAL', admission: 'DAY_CARE' },
  { key: 'INCISION_DRAINAGE', label: 'Incision and drainage', anaesthesia: 'LOCAL', admission: 'DAY_CARE' },
];

export const APPROACHES: readonly string[] = ['Laparoscopic, may convert to open', 'Open', 'Not applicable'];
export const ANAESTHESIA_LABEL: Record<Anaesthesia, string> = { GA: 'GA', SPINAL: 'Spinal', LOCAL: 'Local', LOCAL_SEDATION: 'Local + sedation' };
export const ADMISSION_LABEL: Record<AdmissionPlan, string> = {
  DAY_CARE: 'Day care',
  IPD_1_DAY: 'IPD 1 day',
  IPD_1_2_DAYS: 'IPD 1–2 days',
  IPD_2_DAYS: 'IPD 2 days',
  IPD_4_5_DAYS: 'IPD 4–5 days',
};
export const PAYER_LABEL: Record<SurgeryPayer, string> = {
  SELF: 'Self',
  INSURANCE_TPA: 'Insurance (TPA)',
  MJPJAY_PMJAY: 'MJPJAY / PM-JAY',
  COMPANY: 'Company',
};
/** The mockup's single-choice Blood chips; the key is what SpPreopOrders.blood stores (reference surgeryBlood). */
export const BLOOD_CHIPS: readonly { key: PreopBlood; label: string }[] = [
  { key: 'NOT_NEEDED', label: 'Not needed' },
  { key: 'GROUP_AND_SAVE', label: 'Group and save' },
  { key: 'ONE_UNIT_RESERVED', label: '1 unit reserved' },
];

export function applyProcedure(plan: SpSurgeryPlan, key: SurgeryProcedureKey): SpSurgeryPlan {
  const def = PROCEDURES.find((p) => p.key === key);
  return def ? { ...plan, procedureKey: key, procedureText: def.label, anaesthesia: def.anaesthesia, admission: def.admission } : plan;
}

/** B-1: Self pays half the package rounded to ₹ 1,000; any other payer ₹ 10,000. Amounts in paise. */
export function depositPaise(payer: SurgeryPayer, packagePaise: number | null): number | null {
  if (payer !== 'SELF') {
    return 1_000_000;
  }
  return packagePaise == null ? null : Math.round((packagePaise * 0.5) / 100_000) * 100_000;
}

export function payerChip(payer: SurgeryPayer, preauthSent: boolean): string {
  return payer === 'SELF' ? 'Self-pay' : `${PAYER_LABEL[payer]} · ${preauthSent ? 'pre-auth sent' : 'pre-auth pending'}`;
}

/** B-3: booking needs a diagnosis, every consent item ticked and, unless Self, pre-authorisation sent. */
export function bookMissing(input: {
  hasDiagnosis: boolean;
  consentTicked: number;
  consentTotal: number;
  payer: SurgeryPayer;
  preauthSent: boolean;
}): string[] {
  const out: string[] = [];
  if (!input.hasDiagnosis) {
    out.push('diagnosis');
  }
  const consentTotal = input.consentTotal === 0 ? SURGERY_CONSENT_ITEMS.length : input.consentTotal;
  if (input.consentTicked < consentTotal) {
    out.push(`${consentTotal - input.consentTicked} consent item(s)`);
  }
  if (input.payer !== 'SELF' && !input.preauthSent) {
    out.push('pre-authorisation');
  }
  return out;
}

/** L-3: the five fixed consent items, none ticked at start. */
export const SURGERY_CONSENT_ITEMS: readonly { key: string; label: string }[] = [
  { key: 'PROCEDURE_RISKS', label: 'Procedure, benefits and risks explained' },
  { key: 'CONVERSION_TO_OPEN', label: 'Possible conversion to open surgery' },
  { key: 'ANAESTHESIA', label: 'Anaesthesia consent' },
  { key: 'BLOOD_TRANSFUSION', label: 'Blood transfusion consent' },
  { key: 'PHOTOS_VIDEO', label: 'Photos / video for records' },
];

export const PREOP_LABEL: Record<string, string> = {
  NIL_BY_MOUTH: 'Nothing by mouth from midnight',
  INHALER_MORNING: 'Inhaler on the morning of surgery',
  ANTIBIOTIC_INDUCTION: 'Antibiotic at induction: cefazolin 2 g IV',
  PART_PREPARATION: 'Part preparation in ward',
  CLOT_PREVENTION: 'Clot prevention: stockings, early walking',
  ADMIT_7AM_OT_10AM: 'Admit 7 am, OT at 10 am',
};
