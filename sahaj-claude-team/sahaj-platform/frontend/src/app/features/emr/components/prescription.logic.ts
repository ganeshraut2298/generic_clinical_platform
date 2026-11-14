import { newClientKey } from '../../../shared/client-key';
import {
  EmrAllergyAlert,
  EmrAllergyClassKey,
  EmrMedicineOption,
  EmrPrescriptionRow,
  EmrPrescriptionValue,
  EmrRxFrequency,
  EmrRxSource,
  EmrRxWhen,
} from '../emr-phase3.model';

/**
 * Pure rules of the prescription (SAH-48-1, SAH-48-2; mockup #GeneralTreatment "Prescription").
 * Frequency, days and when are fixed lists of the product. The sulfa and penicillin classes are
 * values from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed (C-2).
 */

export const FREQUENCIES: readonly { key: EmrRxFrequency; label: string }[] = [
  { key: '1-0-0', label: '1-0-0 OD' },
  { key: '1-0-1', label: '1-0-1 BD' },
  { key: '0-0-1', label: '0-0-1 HS' },
  { key: '1-1-1', label: '1-1-1 TDS' },
];

export const WHEN_CHOICES: readonly { key: EmrRxWhen; label: string }[] = [
  { key: 'AFTER_FOOD', label: 'After food' },
  { key: 'BEFORE_FOOD', label: 'Before food' },
  { key: 'BEDTIME', label: 'Bedtime' },
  { key: 'MORNING_EMPTY_STOMACH', label: 'Morning, empty stomach' },
];

export const ROWS_MAX = 30;
export const DAYS_MAX = 365;

export function whenLabel(when: EmrRxWhen): string {
  return WHEN_CHOICES.find((w) => w.key === when)?.label ?? when;
}

export function frequencyLabel(freq: EmrRxFrequency | null): string {
  return freq ? (FREQUENCIES.find((f) => f.key === freq)?.label ?? freq) : '';
}

/** The short "how often" of a row: the fixed text of a dose-by-weight or take-home row (0.5.0), else the pattern. */
export function howOften(row: Pick<EmrPrescriptionRow, 'frequency' | 'frequencyText'>): string {
  return row.frequency ? frequencyLabel(row.frequency).split(' ')[0] : (row.frequencyText ?? '');
}

/** Days change by 1 up to 7 and by 7 above that, never below 1 (and never above 365). */
export function stepDays(days: number, direction: 1 | -1): number {
  if (direction === 1) {
    return Math.min(DAYS_MAX, days >= 7 ? days + 7 : days + 1);
  }
  return Math.max(1, days > 7 ? days - 7 : days - 1);
}

export function emptyRx(): EmrPrescriptionValue {
  return { component: 'PRESCRIPTION', schemaVersion: 1, rows: [] };
}

export function rowsOf(value: unknown): EmrPrescriptionRow[] {
  const v = value as Partial<EmrPrescriptionValue> | null;
  return v && Array.isArray(v.rows) ? v.rows : [];
}

export function withRows(value: EmrPrescriptionValue | null, rows: EmrPrescriptionRow[]): EmrPrescriptionValue {
  return { ...(value ?? emptyRx()), component: 'PRESCRIPTION', schemaVersion: 1, rows };
}

/** Same generic name and strength (ignoring case) is already on the prescription. */
export function hasMedicine(rows: readonly EmrPrescriptionRow[], name: string): boolean {
  const n = name.trim().toLowerCase();
  return rows.some((r) => r.name.trim().toLowerCase() === n);
}

export function rowFromOption(option: EmrMedicineOption, source: EmrRxSource): EmrPrescriptionRow {
  return {
    id: newClientKey(),
    medicineId: option.medicineId,
    name: option.name,
    form: option.form || null,
    frequency: option.defaultFrequency,
    days: option.defaultDays,
    when: option.defaultWhen,
    source,
  };
}

/** A medicine typed by the doctor: never allergy-checked (ADR-022 H6); defaults 1-0-1, 5 days, after food. */
export function typedRow(name: string): EmrPrescriptionRow {
  return {
    id: newClientKey(),
    medicineId: null,
    name: name.trim(),
    form: null,
    frequency: '1-0-1',
    days: 5,
    when: 'AFTER_FOOD',
    source: 'TYPED',
  };
}

// ---- drug-class allergy check ----

export const ALLERGY_CLASS_LABEL: Record<EmrAllergyClassKey, string> = {
  SULFA: 'Sulfa drugs',
  PENICILLIN: 'Penicillin',
};

export const ALERT_BLOCK_TEXT = 'Remove the drug or confirm the allergy alert before completing.';
export const ALLERGY_LINE = 'Allergy check covers listed allergy classes only. No alert is not a clearance.';

/** The alert wording of the mockup: sulfa "contains a sulfa drug", penicillin "is a penicillin". */
export function alertText(medicineName: string, allergyClass: EmrAllergyClassKey): string {
  return allergyClass === 'SULFA'
    ? `${medicineName} contains a sulfa drug. This patient is allergic to sulfa drugs.`
    : `${medicineName} is a penicillin. This patient is allergic to penicillin.`;
}

/** The "Warning: ..." message shown when a flagged drug is added. */
export function warningMessage(medicineName: string, allergyClass: EmrAllergyClassKey): string {
  return allergyClass === 'SULFA'
    ? `Warning: ${medicineName} clashes with sulfa allergy`
    : `Warning: ${medicineName} clashes with penicillin allergy`;
}

export function isAllergyClassKey(key: string | null | undefined): key is EmrAllergyClassKey {
  return key === 'SULFA' || key === 'PENICILLIN';
}

export function rowReviewed(row: EmrPrescriptionRow, allergyClass: string): boolean {
  return row.allergyReview?.confirmed === true && row.allergyReview.allergyClass === allergyClass;
}

export function unresolved(alerts: readonly Pick<EmrAllergyAlert, 'reviewed'>[]): number {
  return alerts.filter((a) => !a.reviewed).length;
}
