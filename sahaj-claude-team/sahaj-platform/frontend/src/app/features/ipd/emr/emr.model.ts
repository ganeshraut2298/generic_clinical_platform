/**
 * SAH-31 EMR sheet and medicine orders (mockup `pcEmr`, `sfIdBar`, `sfOrdBlock`, `sfCheck`).
 * Types follow `EmrSheet`, `MedicineOrderRow`, `OrderCheck`, `MedicineOrderInput` and
 * `MedicineOrderAnswer` in docs/api/ipd.yaml; no field is invented. Every rule (allergy match,
 * duplicate, interaction, outcome) is the server's: the client only draws what it is told.
 * Allergy text, names and findings are clinical data: on screen only, never logged or stored.
 */
export type AllergyStatus = 'NONE_KNOWN' | 'NOT_RECORDED' | 'RECORDED';
export type MedicineOrderStatus = 'PENDING' | 'ACTIVE' | 'REJECTED' | 'STOPPED';
export type FrequencyKey = 'od' | 'bd' | 'tds' | 'q8';
export type OverrideReasonKey =
  | 'BENEFIT_MONITORED'
  | 'SPECIALIST_ADVISED'
  | 'ALLERGY_HISTORY_CHECKED'
  | 'CARDIOLOGY_STENT';
export type RejectReasonKey =
  | 'ALLERGY_CONFLICT'
  | 'INTERACTION_RISK'
  | 'WRONG_DOSE'
  | 'DUPLICATE_THERAPY';
export type FindingCode =
  | 'ALLERGY_PENICILLIN'
  | 'ALLERGY_SULFA'
  | 'DUPLICATE'
  | 'ALLERGY_CROSS_CEPHALOSPORIN'
  | 'ALLERGIES_NOT_RECORDED'
  | 'INTERACTION';
export type FindingSeverity = 'HARD_STOP' | 'MAJOR' | 'MODERATE';
export type OrderLockReason = 'ROLE' | 'IPD_READONLY' | 'NOT_ADMITTED';

export interface KeyLabel {
  key: string;
  label: string;
}

export interface OrderFinding {
  code: FindingCode;
  severity: FindingSeverity;
  otherDrugKey: string | null;
  text: string;
}

export interface OrderCheck {
  allergyStatus: AllergyStatus;
  hardStops: OrderFinding[];
  warnings: OrderFinding[];
  /** 22 characters, opaque: sent back with a reason and with Verify so the server can see what was shown. */
  checkToken: string;
}

export interface DrugOption {
  key: string;
  name: string;
  route: string;
  highAlert: string | null;
}

export interface EmrPatient {
  patientId: string;
  name: string;
  uhid: string;
  age: number | null;
  sex: 'F' | 'M' | 'O' | null;
  bedCode: string;
  allergyText: string | null;
  allergyStatus: AllergyStatus;
}

export type DoseOutcome = 'GIVEN' | 'HELD' | 'REFUSED';
export type NotGivenReasonKey =
  | 'PATIENT_REFUSED'
  | 'HELD_DOCTOR'
  | 'NIL_BY_MOUTH'
  | 'NOT_AVAILABLE'
  | 'AWAY_FROM_WARD';

/** SAH-32 `DoseRow`: one recorded dose (append-only). Names on screen only. */
export interface DoseRow {
  id: string;
  slotAt: string;
  outcome: DoseOutcome;
  /** Key is a NotGivenReasonKey; null for GIVEN. */
  reason: KeyLabel | null;
  recordedAt: string;
  recordedByName: string;
  secondCheckByName: string | null;
}

export interface MedicineOrderRow {
  id: string;
  drugKey: string;
  drugName: string;
  route: string;
  highAlert: string | null;
  frequencyKey: FrequencyKey;
  frequencyLabel: string;
  status: MedicineOrderStatus;
  orderedByName: string;
  orderedAt: string;
  overrideReason: KeyLabel | null;
  verifiedByName: string | null;
  verifiedAt: string | null;
  rejectReason: KeyLabel | null;
  rejectedByName: string | null;
  rejectedAt: string | null;
  stoppedByName: string | null;
  stoppedAt: string | null;
  canStop: boolean;
  /** SAH-32 (OQ-32-4): the dose record with the latest slot, any day, any outcome; null when none. */
  lastDose: DoseRow | null;
}

export interface EmrSheet {
  admissionId: string;
  status: 'ADMITTED' | 'DISCHARGED';
  state: 'trial' | 'on' | 'readonly';
  asOf: string;
  patient: EmrPatient;
  admittingDoctorName: string;
  diagnosis: string;
  dayNumber: number;
  orders: MedicineOrderRow[];
  canOrder: boolean;
  orderLockReason: OrderLockReason | null;
  drugs: DrugOption[];
  frequencies: KeyLabel[];
  overrideReasons: KeyLabel[];
}

export interface MedicineOrderInput {
  clientKey: string;
  drugKey: string;
  frequencyKey: string;
  overrideReasonKey?: string | null;
  checkToken?: string | null;
}

export type OrderOutcome = 'PLACED' | 'BLOCKED' | 'NEEDS_REASON';

export interface MedicineOrderAnswer {
  outcome: OrderOutcome;
  replayed: boolean;
  check: OrderCheck;
  order: MedicineOrderRow | null;
  sheet: EmrSheet;
}

// ---- Texts (exact, from the mockup and docs/api/ipd.yaml) ----

export const EMR_LOAD_ERROR = 'We could not load the clinical record. Try again.';
export const EMR_HIDDEN = 'Clinical details are hidden for your role.';
export const MEDS_TITLE = 'Medicines on chart';
export const MEDS_SUBTITLE = "Doctor's orders · pharmacist checked before the first dose";
export const NO_ORDERS = 'No medicines ordered yet';
export const ROLE_LOCK_LINE = 'Only doctors can order medicines.';
/** Why the order form is not there (DEC-017 R6; docs/api/ipd.yaml says no text for the last two, the tech lead decided on one). */
export const ORDER_LOCK_LINES: Record<OrderLockReason, string> = {
  ROLE: ROLE_LOCK_LINE,
  IPD_READONLY: 'IPD is read-only. Medicines can be stopped but not added.',
  NOT_ADMITTED: 'This patient is not admitted any more.',
};
export const ORDER_HINT =
  'Checks allergies, duplicates and interactions. A pharmacist checks every order before the first dose.';
export const LOGGED_LINE = 'Opening this record is logged in the clinical audit trail.';
export const HARD_STOP_HEAD = 'Hard stop. This order cannot be placed.';
export const WARN_HEAD = 'Check before ordering';
export const REASON_LABEL = 'Reason to continue (recorded in the audit trail)';
export const REASON_PLACEHOLDER = 'Choose a reason';
export const CHOOSE_REASON_TOAST = 'Choose a reason to continue, or change the medicine.';
export const ORDER_ERROR = 'We could not place that order. Try again.';
export const STOP_ERROR = 'We could not stop that medicine. Try again.';
export const STOP_CONFIRM = 'Stop this medicine?';
export const KEY_RELOAD = 'Reload the page and try again.';
export const DRUG_MISSING = 'Choose a medicine from the list';
export const FREQUENCY_MISSING = 'Choose how often';
export const REASON_INVALID = 'Choose a reason from the list';

export const CHECK_AND_ORDER = 'Check and order';
export const ORDER_WITH_REASON = 'Order with this reason';

/** Status pill (mockup `SF_ST`): label and the pill colour class. */
export const STATUS_PILL: Record<MedicineOrderStatus, { text: string; css: string }> = {
  PENDING: { text: 'Pharmacist check', css: 'p-warn' },
  ACTIVE: { text: 'Active', css: 'p-good' },
  REJECTED: { text: 'Rejected', css: 'p-crit' },
  STOPPED: { text: 'Stopped', css: 'p-wait' },
};

const SEX_LABEL = { M: 'Male', F: 'Female', O: 'Other' } as const;

export function sexLabel(sex: EmrPatient['sex']): string {
  return sex ? SEX_LABEL[sex] : '';
}

/** "UHID · 42 · Female" (parts that are not known are left out). */
export function ageSexParts(patient: Pick<EmrPatient, 'age' | 'sex'>): string[] {
  const parts: string[] = [];
  if (patient.age !== null && patient.age !== undefined) {
    parts.push(String(patient.age));
  }
  const sex = sexLabel(patient.sex);
  if (sex !== '') {
    parts.push(sex);
  }
  return parts;
}

/** Sheet subtitle: "<uhid> · <age> · <Male|Female|Other> · <admitting doctor>". */
export function emrSubtitle(sheet: Pick<EmrSheet, 'patient' | 'admittingDoctorName'>): string {
  return [sheet.patient.uhid, ...ageSexParts(sheet.patient), sheet.admittingDoctorName]
    .filter((p) => p !== '')
    .join(' · ');
}

/** Identity bar small line: "<uhid> · <age> · <Male|Female|Other> · bed <bedCode>". */
export function identityLine(patient: EmrPatient): string {
  return [patient.uhid, ...ageSexParts(patient), `bed ${patient.bedCode}`].join(' · ');
}

export interface AllergyChip {
  tone: 'ok' | 'nr' | 'bad';
  icon: 'check' | 'alert';
  text: string;
}

/**
 * The chip beside the name (mockup `sfalg`). Chosen only by `allergyStatus`, which the server
 * computes with the same rule as the check, so a chip never says "No known allergies" while an
 * order is blocked for an allergy. RECORDED is red whatever the keyword.
 */
export function allergyChip(patient: Pick<EmrPatient, 'allergyStatus' | 'allergyText'>): AllergyChip {
  switch (patient.allergyStatus) {
    case 'NONE_KNOWN':
      return { tone: 'ok', icon: 'check', text: 'No known allergies' };
    case 'NOT_RECORDED':
      return { tone: 'nr', icon: 'alert', text: 'Allergies not recorded' };
    default:
      return { tone: 'bad', icon: 'alert', text: `Allergy: ${patient.allergyText ?? ''}`.trimEnd() };
  }
}

/** "Moderate" / "Major" from a finding severity. */
export function severityWord(severity: FindingSeverity): string {
  return severity === 'MAJOR' ? 'Major' : severity === 'MODERATE' ? 'Moderate' : 'Hard stop';
}

/** A drug option as the select shows it: "<name>" or "<name> · high-alert". */
export function drugOptionLabel(drug: Pick<DrugOption, 'name' | 'highAlert'>): string {
  return drug.highAlert ? `${drug.name} · high-alert` : drug.name;
}

/**
 * Small line under a medicine: "<route> · <frequency> · <ordered by>" then " · override: <reason>"
 * and, for a rejected order, " · rejected: <reason>" (OQ-31-5).
 */
export function orderSmallLine(order: MedicineOrderRow): string {
  let line = `${order.route} · ${order.frequencyLabel} · ${order.orderedByName}`;
  if (order.overrideReason) {
    line += ` · override: ${order.overrideReason.label}`;
  }
  if (order.status === 'REJECTED' && order.rejectReason) {
    line += ` · rejected: ${order.rejectReason.label}`;
  }
  return line;
}

export const orderedToast = (drugName: string): string =>
  `${drugName} ordered. It goes to the pharmacist before the first dose.`;
export const stoppedToast = (drugName: string): string =>
  `${drugName} stopped. Remaining doses are removed from the MAR.`;

/** Field errors of a 400 that belong to the order form, by the contract's field names. */
export type OrderField = 'drugKey' | 'frequencyKey' | 'overrideReasonKey';
export type OrderErrors = Partial<Record<OrderField, string>>;

/**
 * What the form shows after the last answer: the check of the medicine and frequency that were
 * sent, and whether it asks for a reason.
 */
export interface ShownCheck {
  drugKey: string;
  frequencyKey: string;
  outcome: 'BLOCKED' | 'NEEDS_REASON';
  check: OrderCheck;
}

/**
 * Whether the client key is replaced when the reason changes. A reason that was already chosen may
 * have been sent with the key (a lost answer), so changing it would be a 409 `client-key-reused`;
 * going from no reason to a reason keeps the key, because NEEDS_REASON stores nothing under it.
 */
export function reasonChangeNeedsNewKey(before: string, after: string): boolean {
  return before !== '' && before !== after;
}

/** The button text: "Order with this reason" once the form shows warnings that need a reason. */
export function orderButtonText(shown: ShownCheck | null): string {
  return shown && shown.outcome === 'NEEDS_REASON' ? ORDER_WITH_REASON : CHECK_AND_ORDER;
}

/** Maps contract field errors of a 400 to the form's fields; anything else is "other". */
export function orderFieldErrors(
  fieldErrors: readonly { field: string; message: string }[],
): { shown: OrderErrors; other: string | null; newKey: boolean } {
  const shown: OrderErrors = {};
  let other: string | null = null;
  let newKey = false;
  for (const item of fieldErrors) {
    if (item.field === 'drugKey') {
      shown.drugKey ??= item.message || DRUG_MISSING;
    } else if (item.field === 'frequencyKey') {
      shown.frequencyKey ??= item.message || FREQUENCY_MISSING;
    } else if (item.field === 'overrideReasonKey') {
      shown.overrideReasonKey ??= item.message || REASON_INVALID;
    } else {
      newKey = newKey || item.field === 'clientKey';
      other ??= item.message || KEY_RELOAD;
    }
  }
  return { shown, other, newKey };
}
