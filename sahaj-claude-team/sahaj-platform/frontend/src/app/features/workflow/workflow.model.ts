/**
 * Types for the SAH-12 `/opd/workflow/*` endpoints in `docs/api/opd.yaml`. No field is invented.
 * Shared by the Clinic setup "OPD workflow" panel (`features/config/workflow`) and the Queue
 * board (`features/queue`), which both need the clinic's current published steps.
 */

export type WorkflowStepKey =
  | 'token'
  | 'queue'
  | 'vitals'
  | 'consult'
  | 'counsel'
  | 'casepaper'
  | 'billing'
  | 'followup';

/** A role code. The API sends it only for Vitals and Counselling; the other steps have none. */
export type WorkflowStepOwner = 'FRONT_DESK' | 'NURSE' | 'DOCTOR' | 'OWNER';

export interface WorkflowStep {
  key: WorkflowStepKey;
  /** token/queue/consult/billing are always true; the backend rejects anything else with 400. */
  on: boolean;
  owner?: WorkflowStepOwner;
}

export interface WorkflowSteps {
  steps: WorkflowStep[];
}

export interface WorkflowVersion {
  versionNo: number;
  steps: WorkflowStep[];
  publishedAt: string;
  publishedByName?: string;
}

/** Fixed display/flow order (contract: "token first, consult before counsel and billing,
 * casepaper/followup after consult" — story acceptance criterion 1). Step reordering is out of
 * scope for this build. */
export const WORKFLOW_STEP_ORDER: readonly WorkflowStepKey[] = [
  'token',
  'queue',
  'vitals',
  'consult',
  'counsel',
  'casepaper',
  'billing',
  'followup',
];

export interface WorkflowStepMeta {
  readonly label: string;
  readonly description: string;
  /** Always on, owner cannot be changed — mockup `STEP_META[k].fixed`. */
  readonly fixed: boolean;
  /** Whether this step has an owner selector at all (mockup only shows one on steps that have
   * one: queue, casepaper and followup do not). */
  readonly hasOwner: boolean;
}

/** Mockup `STEP_META` (~line 2569). */
export const WORKFLOW_STEP_META: Record<WorkflowStepKey, WorkflowStepMeta> = {
  token: { label: 'Token', description: 'Mobile search, family members, token slip', fixed: true, hasOwner: true },
  queue: { label: 'Queue', description: 'Patient waits; "Send in now" skips it', fixed: true, hasOwner: false },
  vitals: {
    label: 'Vitals',
    description: 'BP, pulse, temperature, weight, SpO2 · new patients and children only',
    fixed: false,
    hasOwner: true,
  },
  consult: {
    label: 'Consultation',
    description: 'Doctor App: stylus or voice fills the case paper',
    fixed: true,
    hasOwner: true,
  },
  counsel: {
    label: 'Counselling',
    description: 'Treatment plan and estimate before billing',
    fixed: false,
    hasOwner: true,
  },
  casepaper: {
    label: 'Case paper print',
    description: 'Printed with a QR code for the next visit',
    fixed: false,
    hasOwner: false,
  },
  billing: { label: 'Billing', description: 'Fee rules, packages, discounts, UPI', fixed: true, hasOwner: true },
  followup: {
    label: 'Follow-up set',
    description: 'Next visit date and WhatsApp reminder',
    fixed: false,
    hasOwner: false,
  },
};

/** Owner choices for Vitals and Counselling (story acceptance criterion 1: "Front desk, Nurse,
 * Doctor or Owner — 'Counsellor' from the mockup has no backing role in this system, so
 * Counselling's owner choices are the same four"). */
export const WORKFLOW_STEP_OWNERS: readonly { value: WorkflowStepOwner; label: string }[] = [
  { value: 'FRONT_DESK', label: 'Front desk' },
  { value: 'NURSE', label: 'Nurse' },
  { value: 'DOCTOR', label: 'Doctor' },
  { value: 'OWNER', label: 'Owner' },
];

/** Default steps (story §3 "Technical design"), used only as a fallback while the real
 * draft/current load, and in tests. Same payload the backend lazily creates: the API sends an
 * `owner` only for Vitals and Counselling, none for the other steps. */
export const DEFAULT_WORKFLOW_STEPS: readonly WorkflowStep[] = [
  { key: 'token', on: true },
  { key: 'queue', on: true },
  { key: 'vitals', on: true, owner: 'NURSE' },
  { key: 'consult', on: true },
  { key: 'counsel', on: false, owner: 'DOCTOR' },
  { key: 'casepaper', on: true },
  { key: 'billing', on: true },
  { key: 'followup', on: true },
];

/**
 * Who handles the steps the API sends no `owner` for - the mockup `initSteps` defaults
 * (~line 2586). Shown read-only on the workflow panel; never editable, never sent to the server.
 */
export const FIXED_STEP_OWNER_LABELS: Partial<Record<WorkflowStepKey, string>> = {
  token: 'Front desk',
  queue: 'Front desk',
  consult: 'Doctor',
  billing: 'Front desk',
  casepaper: 'Front desk',
  followup: 'Doctor',
};

/** Whether two step lists have the same on/owner for every key, in order (used for the
 * "Live · vN" vs "Draft · not published" version badge — mockup `dirty()`). */
export function workflowStepsEqual(a: readonly WorkflowStep[], b: readonly WorkflowStep[]): boolean {
  if (a.length !== b.length) {
    return false;
  }
  return a.every((step, index) => {
    const other = b[index];
    return other != null && step.key === other.key && step.on === other.on && (step.owner ?? null) === (other.owner ?? null);
  });
}
