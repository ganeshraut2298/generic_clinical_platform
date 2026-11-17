import { FormControl, FormGroup } from '@angular/forms';
import { DoseUnitKey, OrderChangeLine, OrderDetails, OrderPriority, doseText } from '../orders/order.model';
import {
  ChangeOrderInput,
  DrugOption,
  EmrSheet,
  KeyLabel,
  MedicineOrderInput,
  OrderErrors,
} from './emr.model';

/**
 * SAH-76-1 order form (EMR sheet) and Change form: the draft the doctor types, the checks that mirror the
 * contract's field messages, and the request bodies. The server decides everything again; these checks only
 * save a round trip. Texts are the contract's (docs/api/ipd.yaml MedicineOrderInput; NEW, OQ-76-28).
 */

export const DOSE_MISSING = 'Enter the dose.';
export const UNIT_MISSING = 'Choose the unit.';
export const ROUTE_MISSING = 'Choose the route.';
export const DURATION_INVALID = 'Enter whole days, or choose Until stopped.';
export const INDICATION_MISSING = 'Enter what it is for.';
export const INDICATION_LONG = 'Keep it to 200 characters.';
export const INTERVAL_INVALID = 'Enter the minimum time between doses.';
export const MAX_INVALID = 'Enter the most doses in 24 hours.';
export const FREQUENCY_MISSING_TEXT = 'Choose how often';
export const REASON_CHANGE_MISSING = 'Write why you are changing this order.';
export const REASON_HOLD_MISSING = 'Write why you are holding this medicine.';
export const REASON_STOP_MISSING = 'Write why you are stopping this medicine.';
export const NOTHING_CHANGED = 'Nothing was changed.';
export const REASON_MAX = 200;
export const REASON_MIN = 4;

export const statNote = (minutes: number): string =>
  `A STAT order is one dose, due now. Give within ${minutes} min.`;

const text = (value = '') => new FormControl(value, { nonNullable: true });

/** The fields of the order and Change forms, all text as typed. */
export function newOrderForm() {
  return new FormGroup({
    drug: text(),
    freq: text(),
    dose: text(),
    unit: text(),
    route: text(),
    start: text(),
    duration: text(),
    indication: text(),
    priority: text('ROUTINE'),
    prnInterval: text(),
    prnUnit: text('min'),
    prnMax: text(),
    reason: text(),
  });
}
export type OrderForm = ReturnType<typeof newOrderForm>;
export type OrderDraft = ReturnType<OrderForm['getRawValue']>;

/** The form after the sheet opens or an order was placed: the first medicine, its default route and unit. */
export function blankDraft(sheet: EmrSheet): OrderDraft {
  const drug = sheet.drugs[0];
  return {
    drug: drug?.key ?? '',
    freq: sheet.frequencies[0]?.key ?? '',
    dose: '',
    unit: drug?.doseUnits[0] ?? '',
    route: drug?.routeKey ?? sheet.routes[0]?.key ?? '',
    start: '',
    duration: '',
    indication: '',
    priority: sheet.priorities[0]?.key ?? 'ROUTINE',
    prnInterval: '',
    prnUnit: 'min',
    prnMax: '',
    reason: '',
  };
}

/** A medicine was chosen: its default route and first unit replace the old ones. */
export function drugDefaults(drug: DrugOption | undefined): Pick<OrderDraft, 'unit' | 'route'> | null {
  return drug ? { unit: drug.doseUnits[0] ?? '', route: drug.routeKey } : null;
}

export const isStat = (draft: Pick<OrderDraft, 'priority'>): boolean => draft.priority === 'STAT';
export const isPrn = (draft: Pick<OrderDraft, 'priority' | 'freq'>): boolean =>
  !isStat(draft) && draft.freq === 'prn';

function wholeNumber(value: string): number | null {
  const t = value.trim();
  return /^\d+$/.test(t) ? Number(t) : null;
}

function amount(value: string): number | null {
  const t = value.trim();
  if (!/^\d+(\.\d{1,3})?$/.test(t)) {
    return null;
  }
  const n = Number(t);
  return n > 0 && n <= 100000 ? n : null;
}

/** "2026-11-15T10:30" typed as an IST wall time becomes an instant. Empty means "now" (the server clock). */
export function startInstant(value: string): string | null {
  const t = value.trim();
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(t) ? `${t}:00+05:30` : null;
}

function prnMinutes(draft: OrderDraft): number | null {
  const n = wholeNumber(draft.prnInterval);
  if (n === null) {
    return null;
  }
  const minutes = draft.prnUnit === 'h' ? n * 60 : n;
  return minutes >= 15 && minutes <= 4320 ? minutes : null;
}

/** The checks of the contract for the fields the doctor types (not the medicine, the reason to continue). */
export function validateDraft(draft: OrderDraft, units: readonly string[], routes: readonly KeyLabel[]): OrderErrors {
  const errors: OrderErrors = {};
  const stat = isStat(draft);
  const prn = isPrn(draft);
  if (!stat && draft.freq === '') {
    errors.frequencyKey = FREQUENCY_MISSING_TEXT;
  }
  if (amount(draft.dose) === null) {
    errors.doseAmount = DOSE_MISSING;
  }
  if (!units.includes(draft.unit)) {
    errors.doseUnit = UNIT_MISSING;
  }
  if (!routes.some((r) => r.key === draft.route)) {
    errors.routeKey = ROUTE_MISSING;
  }
  if (!stat && draft.duration.trim() !== '') {
    const days = wholeNumber(draft.duration);
    if (days === null || days < 1 || days > 365) {
      errors.durationDays = DURATION_INVALID;
    }
  }
  if (draft.indication.length > 200) {
    errors.indication = INDICATION_LONG;
  } else if (prn && draft.indication.trim() === '') {
    errors.indication = INDICATION_MISSING;
  }
  if (prn) {
    if (prnMinutes(draft) === null) {
      errors.prnMinIntervalMinutes = INTERVAL_INVALID;
    }
    const max = wholeNumber(draft.prnMax);
    if (max === null || max < 1 || max > 24) {
      errors.prnMaxPer24h = MAX_INVALID;
    }
  }
  return errors;
}

/** The request body of Check and order. A STAT order sends no frequency and no duration. */
export function toOrderInput(
  draft: OrderDraft,
  clientKey: string,
  override: { overrideReasonKey: string; checkToken: string } | null,
): MedicineOrderInput {
  const stat = isStat(draft);
  const prn = isPrn(draft);
  const start = stat ? null : startInstant(draft.start);
  return {
    clientKey,
    drugKey: draft.drug,
    ...(stat ? {} : { frequencyKey: draft.freq }),
    doseAmount: amount(draft.dose),
    doseUnit: draft.unit,
    routeKey: draft.route,
    ...(start ? { startAt: start } : {}),
    ...(!stat && draft.duration.trim() !== '' ? { durationDays: wholeNumber(draft.duration) } : {}),
    ...(draft.indication.trim() !== '' ? { indication: draft.indication.trim() } : {}),
    priority: draft.priority as OrderPriority,
    ...(prn ? { prnMinIntervalMinutes: prnMinutes(draft), prnMaxPer24h: wholeNumber(draft.prnMax) } : {}),
    ...(override ?? {}),
  };
}

// ---- Change ----

/** The Change form starts from the current version. */
export function draftOf(details: OrderDetails, drug: DrugOption | undefined): OrderDraft {
  const minutes = details.prnMinIntervalMinutes;
  const hours = minutes !== null && minutes % 60 === 0;
  return {
    drug: drug?.key ?? '',
    freq: details.kind === 'ONCE' ? '' : (details.frequencyKey ?? ''),
    dose: details.doseAmount === null ? '' : String(details.doseAmount),
    unit: details.doseUnit ?? '',
    route: details.routeKey,
    start: '',
    duration: details.durationDays === null ? '' : String(details.durationDays),
    indication: details.indication ?? '',
    priority: details.priority,
    prnInterval: minutes === null ? '' : String(hours ? minutes / 60 : minutes),
    prnUnit: hours ? 'h' : 'min',
    prnMax: details.prnMaxPer24h === null ? '' : String(details.prnMaxPer24h),
    reason: '',
  };
}

export function toChangeInput(
  draft: OrderDraft,
  clientKey: string,
  orderVersion: number,
  reason: string,
  startEdited: boolean,
): ChangeOrderInput {
  const stat = isStat(draft);
  const prn = isPrn(draft);
  const start = startEdited ? startInstant(draft.start) : null;
  return {
    clientKey,
    orderVersion,
    reason: reason.trim(),
    doseAmount: amount(draft.dose),
    doseUnit: draft.unit,
    routeKey: draft.route,
    frequencyKey: stat ? null : draft.freq,
    ...(start ? { startAt: start } : {}),
    durationDays: stat || draft.duration.trim() === '' ? null : wholeNumber(draft.duration),
    indication: draft.indication.trim() === '' ? null : draft.indication.trim(),
    priority: draft.priority as OrderPriority,
    prnMinIntervalMinutes: prn ? prnMinutes(draft) : null,
    prnMaxPer24h: prn ? wholeNumber(draft.prnMax) : null,
  };
}

/** The reason of a change, hold or stop: 4 to 200 characters after trimming. */
export function reasonProblem(value: string, missingText: string): string | null {
  const t = value.trim();
  return t.length < REASON_MIN || t.length > REASON_MAX ? missingText : null;
}

/** "Changed: <old> → <new>" per edited field, before saving (NEW). Compared with the current version. */
export function previewLines(
  draft: OrderDraft,
  details: OrderDetails,
  labels: { routes: readonly KeyLabel[]; frequencies: readonly KeyLabel[]; priorities: readonly KeyLabel[] },
): OrderChangeLine[] {
  const lines: OrderChangeLine[] = [];
  const add = (field: OrderChangeLine['field'], label: string, before: string, after: string) => {
    if (before !== after) {
      lines.push({ field, label, before, after });
    }
  };
  const newDose = amount(draft.dose);
  add(
    'dose',
    'Dose',
    doseText(details),
    newDose === null ? doseText(details) : doseText({ doseAmount: newDose, doseUnit: (draft.unit || null) as DoseUnitKey | null }),
  );
  add('route', 'Route', details.routeLabel, labels.routes.find((r) => r.key === draft.route)?.label ?? details.routeLabel);
  const stat = isStat(draft);
  const freqAfter = stat ? 'STAT' : (labels.frequencies.find((f) => f.key === draft.freq)?.label ?? details.frequencyLabel);
  add('frequency', 'How often', details.kind === 'ONCE' ? 'STAT' : details.frequencyLabel, freqAfter);
  const days = stat ? null : wholeNumber(draft.duration);
  const before = details.durationDays === null ? 'Until stopped' : `${details.durationDays} days`;
  add('end', 'Duration', details.kind === 'ONCE' ? 'Until stopped' : before, stat || days === null ? 'Until stopped' : `${days} days`);
  add('indication', 'Indication', details.indication ?? '', draft.indication.trim());
  add('priority', 'Priority', labels.priorities.find((p) => p.key === details.priority)?.label ?? details.priority,
    labels.priorities.find((p) => p.key === draft.priority)?.label ?? draft.priority);
  if (isPrn(draft)) {
    add('prnInterval', 'Minimum interval', String(details.prnMinIntervalMinutes ?? ''), String(prnMinutes(draft) ?? ''));
    add('prnMax', 'Most doses in 24 hours', String(details.prnMaxPer24h ?? ''), String(wholeNumber(draft.prnMax) ?? ''));
  }
  return lines;
}
