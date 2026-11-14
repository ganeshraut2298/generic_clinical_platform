import { EmrBodyMapPin, EmrBodyMapValue, EmrListItem, EmrListValue, EmrPrescriptionRow, EmrPrescriptionValue } from '../emr-phase3.model';
import { EmrFollowUpValue, EmrIpdOrderRow, EmrIpdOrdersValue } from '../emr-finish.model';
import { isSpecialtyEmpty, sanitizeSpecialty } from '../specialty/specialty.logic';
import { EmrSpecialtyValue } from '../specialty/specialty.model';

/**
 * Rules shared by the three shared components (EMR list, prescription, body map): how a component
 * value counts as stored, as Done, and what is sent to the server (ADR-022 D2, D3, D4, D8).
 */

export type ComponentValue = EmrListValue | EmrPrescriptionValue | EmrBodyMapValue | EmrFollowUpValue | EmrIpdOrdersValue | EmrSpecialtyValue;

export function isComponentValue(value: unknown): value is ComponentValue {
  return typeof value === 'object' && value !== null && typeof (value as { component?: unknown }).component === 'string';
}

/** Nothing to store: no item, no row, no pin and no "No local findings" confirmation. */
export function isComponentEmpty(value: unknown): boolean {
  if (!isComponentValue(value)) {
    return true;
  }
  switch (value.component) {
    case 'EMR_LIST':
      return value.items.length === 0;
    case 'PRESCRIPTION':
      return value.rows.length === 0;
    case 'BODY_MAP':
      return value.pins.length === 0 && !value.noFindingsConfirmed;
    case 'FOLLOW_UP':
      return value.interval === null;
    case 'IPD_ORDERS':
      return value.rows.length === 0;
    default:
      return isSpecialtyEmpty(value);
  }
}

/**
 * Done rule (ADR-022 D2): an EMR list is filled with at least one active item, the prescription with
 * one row, the body map with one pin or the "No local findings" confirmation (SAH-57-4).
 */
export function isComponentFilled(value: unknown): boolean {
  if (!isComponentValue(value)) {
    return false;
  }
  if (value.component === 'EMR_LIST') {
    return value.items.some((i) => i.active);
  }
  return !isComponentEmpty(value);
}

function listItemOut(i: EmrListItem): EmrListItem {
  return {
    id: i.id,
    itemKey: i.itemKey,
    text: i.text,
    newToList: i.newToList,
    attr1: i.attr1,
    attr2: i.attr2,
    active: i.active,
  };
}

function rowOut(r: EmrPrescriptionRow): EmrPrescriptionRow {
  const out: EmrPrescriptionRow = {
    id: r.id,
    medicineId: r.medicineId,
    name: r.name,
    form: r.form ?? null,
    frequency: r.frequency,
    days: r.days,
    when: r.when,
    source: r.source,
  };
  if (r.doseText) {
    out.doseText = r.doseText;
  }
  if (r.frequencyText) {
    out.frequencyText = r.frequencyText;
  }
  if (r.note) {
    out.note = r.note;
  }
  if (r.calculation) {
    out.calculation = { doseRuleKey: r.calculation.doseRuleKey, weightKg: r.calculation.weightKg, mg: r.calculation.mg, ml: r.calculation.ml };
  }
  if (r.allergyReview) {
    out.allergyReview = { allergyClass: r.allergyReview.allergyClass, confirmed: r.allergyReview.confirmed };
  }
  return out;
}

/** Only the writable properties: `orderId` and `orderedAt` are stamped by the server at sign. */
function ipdRowOut(r: EmrIpdOrderRow): EmrIpdOrderRow {
  return { id: r.id, drugKey: r.drugKey, frequencyKey: r.frequencyKey };
}

function pinOut(p: EmrBodyMapPin): EmrBodyMapPin {
  return {
    no: p.no,
    side: p.side,
    x: p.x,
    y: p.y,
    region: p.region,
    where: p.where,
    finding: p.finding,
    cause: p.cause,
    severity: p.severity,
    sizeCm: p.sizeCm,
    note: p.note,
    mlc: p.mlc,
  };
}

/**
 * The body the server accepts: the writable properties only. Server-stamped or response-only
 * properties (code, addedAt, addedByName, unitPricePaise, billed, reviewedAt, recordedAt,
 * nextPinNo, legacyText) are left out because the request bodies are closed.
 */
export function sanitizeComponentValue(value: unknown): unknown {
  if (!isComponentValue(value)) {
    return value;
  }
  switch (value.component) {
    case 'EMR_LIST':
      return { component: 'EMR_LIST', kind: value.kind, schemaVersion: 1, items: value.items.map(listItemOut) };
    case 'PRESCRIPTION':
      return { component: 'PRESCRIPTION', schemaVersion: 1, rows: value.rows.map(rowOut) };
    case 'FOLLOW_UP':
      return { component: 'FOLLOW_UP', schemaVersion: 1, interval: value.interval };
    case 'IPD_ORDERS':
      return { component: 'IPD_ORDERS', schemaVersion: 1, rows: value.rows.map(ipdRowOut) };
    case 'BODY_MAP':
      return {
        component: 'BODY_MAP',
        schemaVersion: 1,
        pins: value.pins.map(pinOut),
        noFindingsConfirmed: value.noFindingsConfirmed,
      };
    default:
      return sanitizeSpecialty(value);
  }
}
