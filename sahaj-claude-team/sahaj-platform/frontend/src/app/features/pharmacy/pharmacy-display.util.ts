import { AbstractControl } from '@angular/forms';
import { ApiError, FieldError } from '../../core/http/problem-detail';
import {
  LOAD_FAILED,
  SAVE_FAILED,
  SetupSummary,
  StaffMapping,
  Store,
  StoreRole,
  StoreType,
  STORE_ROLE_LABELS,
} from './pharmacy.model';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Pill class from `_primitives.scss`; the words carry the meaning, the colour never does. */
export interface Chip {
  text: string;
  css: 'p-blue' | 'p-good' | 'p-wait' | 'p-warn' | 'p-crit' | 'p-teal';
}

/** `30 Nov` from `2026-11-30`. */
export function dayMonth(iso: string): string {
  const [, month, day] = iso.split('-').map(Number);
  return `${day} ${MONTHS[month - 1]}`;
}

export function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** Mockup 16 tree chips: Central, Sale, Sub-store, "Ward · consumption". */
export function storeTypeChip(type: StoreType): Chip {
  switch (type) {
    case 'CENTRAL':
      return { text: 'Central', css: 'p-blue' };
    case 'PHARMACY':
      return { text: 'Sale', css: 'p-good' };
    case 'SUB_STORE':
      return { text: 'Sub-store', css: 'p-wait' };
    case 'WARD':
      return { text: 'Ward · consumption', css: 'p-warn' };
  }
}

/** Second line of a tree node: "Issues from Central Store · 5 staff", or the Central Store sentence. */
export function storeLine(store: Store): string {
  if (store.type === 'CENTRAL') {
    return 'Buys from vendors · issues to all stores';
  }
  const from = `Issues from ${store.issuesFromName ?? 'Central Store'}`;
  return store.staffCount > 0 ? `${from} · ${store.staffCount} staff` : from;
}

/** "Delegate ended 30 Nov" on a store row once the delegation is over (NEW text, story 78-3). */
export function delegateEndedText(store: Store): string | null {
  return store.delegate?.state === 'ENDED' ? `Delegate ended ${dayMonth(store.delegate.to)}` : null;
}

/** Live subtitle of the "Stores & staff" section tab: "7 stores · 24 staff mapped". */
export function storesSubtitle(summary: SetupSummary | null): string {
  if (!summary) {
    return '';
  }
  return `${plural(summary.storeCount, 'store', 'stores')} · ${summary.mappedStaffCount} staff mapped`;
}

/** Store role chip of the staff table (mockup 16). */
export function roleChip(mapping: StaffMapping): Chip {
  if (mapping.storeRole === 'DELEGATE' && mapping.delegateTo) {
    if (mapping.delegateState === 'ENDED') {
      return { text: `Delegate · ended ${dayMonth(mapping.delegateTo)}`, css: 'p-wait' };
    }
    return { text: `Delegate · till ${dayMonth(mapping.delegateTo)}`, css: 'p-warn' };
  }
  if (mapping.storeRole === 'APPROVER') {
    return { text: STORE_ROLE_LABELS.APPROVER, css: 'p-blue' };
  }
  return { text: STORE_ROLE_LABELS[mapping.storeRole], css: 'p-wait' };
}

/** Line under the name: the Sahaj role label, or "HOD, <store>" for the head of department. */
export function staffSubline(mapping: StaffMapping, storeName: string): string {
  return mapping.isHod ? `HOD, ${storeName}` : mapping.roleLabel;
}

export function yesNo(value: boolean): string {
  return value ? 'Yes' : 'No';
}

/** The Receive column: a pharmacist also receives Schedule X. */
export function receiveText(mapping: StaffMapping): string {
  if (!mapping.access.receive) {
    return 'No';
  }
  return mapping.access.receiveScheduleX ? 'Yes · incl. Schedule X' : 'Yes';
}

export function otherStoresText(mapping: StaffMapping): string {
  return mapping.otherStores.length > 0 ? mapping.otherStores.map((s) => s.name).join(', ') : '—';
}

export function storeRoleLabel(role: StoreRole): string {
  return STORE_ROLE_LABELS[role];
}

/** True for a failed request that the person can only retry: no connection, or a server fault. */
function isTransport(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
}

/** The message of a failed save: the server's sentence when it gave one, else the standard line. */
export function saveErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 503 && error.problem.detail) {
    return error.problem.detail;
  }
  if (isTransport(error)) {
    return SAVE_FAILED;
  }
  const api = error as ApiError;
  return api.problem.detail ?? api.fieldErrors[0]?.message ?? SAVE_FAILED;
}

/** The message of a failed load; a 403 carries the server's sentence ("module-off" or no access). */
export function loadErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.problem.detail) {
    return error.problem.detail;
  }
  return LOAD_FAILED;
}

export function problemSlug(error: unknown): string | null {
  return error instanceof ApiError ? (error.problem.type.split('/').pop() ?? null) : null;
}

/**
 * Puts the server's field messages on the form controls. `map` turns a server field name into a
 * control name (for example `delegate.to` into `delegateTo`); the messages that fit no control are
 * returned so the form can show them together.
 */
export function applyFieldErrors(
  controls: Record<string, AbstractControl>,
  errors: readonly FieldError[],
  map: Record<string, string> = {},
): string[] {
  const rest: string[] = [];
  for (const { field, message } of errors) {
    const name = map[field] ?? field;
    const control = controls[name];
    if (control) {
      control.setErrors({ server: message });
      control.markAsTouched();
    } else {
      rest.push(message);
    }
  }
  return rest;
}

/** The server message set by `applyFieldErrors`, or the client message of a validator. */
export function controlMessage(
  control: AbstractControl,
  messages: Record<string, string>,
): string | null {
  if (!control.touched || !control.errors) {
    return null;
  }
  const server = control.errors['server'];
  if (typeof server === 'string') {
    return server;
  }
  const key = Object.keys(control.errors).find((k) => messages[k]);
  return key ? messages[key] : null;
}

export interface DelegateDraft {
  hodId: string;
  delegateId: string;
  from: string;
  to: string;
}

export type DelegateErrors = Partial<
  Record<'delegateMembershipId' | 'delegateFrom' | 'delegateTo', string>
>;

/** A delegation lasts at most 90 calendar days, counting both the first and the last day (DEC-068 Q1 A). */
export const MAX_DELEGATION_DAYS = 90;

function daysBetween(fromIso: string, toIso: string): number {
  const [fy, fm, fd] = fromIso.split('-').map(Number);
  const [ty, tm, td] = toIso.split('-').map(Number);
  return Math.round((Date.UTC(ty, tm - 1, td) - Date.UTC(fy, fm - 1, fd)) / 86_400_000);
}

/**
 * The delegate rules of `DelegateInput` (docs/api/pharmacy.yaml), with the contract's own sentences.
 * No delegate chosen means no delegation and no error. The server checks again.
 */
export function delegateErrors(draft: DelegateDraft, today: string): DelegateErrors {
  const errors: DelegateErrors = {};
  if (!draft.delegateId) {
    return errors;
  }
  if (draft.hodId && draft.delegateId === draft.hodId) {
    errors.delegateMembershipId = 'The delegate must be a different person from the approver';
  }
  if (!draft.from) {
    errors.delegateFrom = 'Enter the start date';
  }
  if (!draft.to) {
    errors.delegateTo = 'Enter the end date';
  } else if (draft.to < today) {
    errors.delegateTo = 'The end date cannot be in the past';
  } else if (draft.from && draft.to < draft.from) {
    errors.delegateTo = 'The end date must be on or after the start date';
  } else if (draft.from && daysBetween(draft.from, draft.to) + 1 > MAX_DELEGATION_DAYS) {
    errors.delegateTo = 'A delegation can be at most 90 days';
  }
  return errors;
}
