import {
  BodyCause,
  BodyFinding,
  BodySeverity,
  BodySide,
  EmrBodyMapPin,
  EmrBodyMapValue,
} from '../emr-phase3.model';
import { BOX_H, BOX_W } from './body-geometry';

/**
 * Pure rules of the body map (SAH-57; mockup #BodyMap). The finding, cause and severity lists, the
 * defaults and the medico-legal rule are values from the EMR mockup, accepted by the human in
 * DEC-039, not clinically reviewed (C-10, L-1).
 */

export const FINDINGS: readonly { key: BodyFinding; label: string }[] = [
  { key: 'WOUND', label: 'Wound' },
  { key: 'SWELLING', label: 'Swelling' },
  { key: 'TENDERNESS', label: 'Tenderness' },
  { key: 'BRUISE', label: 'Bruise' },
  { key: 'BURN', label: 'Burn' },
  { key: 'RASH', label: 'Rash' },
  { key: 'LUMP', label: 'Lump' },
  { key: 'FRACTURE_SUSPECTED', label: 'Fracture?' },
  { key: 'SCAR', label: 'Scar' },
  { key: 'PAIN', label: 'Pain' },
];

export const CAUSES: readonly { key: BodyCause; label: string }[] = [
  { key: 'NO_INJURY', label: 'No injury (illness)' },
  { key: 'FALL', label: 'Fall' },
  { key: 'ROAD_ACCIDENT', label: 'Road accident' },
  { key: 'ASSAULT', label: 'Assault' },
  { key: 'ANIMAL_BITE', label: 'Animal bite' },
  { key: 'BURN', label: 'Burn' },
  { key: 'SPORTS', label: 'Sports' },
  { key: 'WORK_INJURY', label: 'Work injury' },
];

export const SEVERITIES: readonly { key: BodySeverity; label: string }[] = [
  { key: 'MILD', label: 'Mild' },
  { key: 'MODERATE', label: 'Moderate' },
  { key: 'SEVERE', label: 'Severe' },
];

/** A new or changed cause of these three marks the pin as a medico-legal case (L-1). */
export const MLC_CAUSES: ReadonlySet<BodyCause> = new Set(['ROAD_ACCIDENT', 'ASSAULT', 'BURN']);

export const MLC_NOTE = 'MLC: record time of examination, who brought the patient, and inform the police as per hospital policy.';
/** Only when the documents service exists (OQ-45-28), so the note never claims what the product does not do. */
export const MLC_PHOTOS_SENTENCE = ' Photos are kept with the record.';

export const PINS_MAX = 60;
export const SIZE_MAX_CM = 50;
export const NOTE_MAX = 500;
export const WHERE_MAX = 80;

export const BODY_HINT = 'Tap the body to drop a pin, drag a pin to move it, then record what you found and what happened.';

export function findingLabel(key: BodyFinding): string {
  return FINDINGS.find((f) => f.key === key)?.label ?? key;
}

export function causeLabel(key: BodyCause): string {
  return CAUSES.find((c) => c.key === key)?.label ?? key;
}

export function severityLabel(key: BodySeverity): string {
  return SEVERITIES.find((s) => s.key === key)?.label ?? key;
}

export function emptyBodyMap(): EmrBodyMapValue {
  return { component: 'BODY_MAP', schemaVersion: 1, pins: [], nextPinNo: 1, noFindingsConfirmed: false };
}

export function pinsOf(value: unknown): EmrBodyMapPin[] {
  const v = value as Partial<EmrBodyMapValue> | null;
  return v && Array.isArray(v.pins) ? v.pins : [];
}

/** Numbers are never reused: the next number is above every number ever given. */
export function nextPinNo(value: EmrBodyMapValue | null): number {
  const pins = pinsOf(value);
  const highest = pins.reduce((m, p) => Math.max(m, p.no), 0);
  return Math.max(value?.nextPinNo ?? 1, highest + 1);
}

export function newPin(
  no: number,
  side: BodySide,
  x: number,
  y: number,
  region: string,
  where: string,
): EmrBodyMapPin {
  return {
    no,
    side,
    x: round4(x / BOX_W),
    y: round4(y / BOX_H),
    region,
    where: where.slice(0, WHERE_MAX),
    finding: 'WOUND',
    cause: 'FALL',
    severity: 'MILD',
    sizeCm: 2,
    note: '',
    mlc: false,
  };
}

function round4(n: number): number {
  return Math.round(Math.min(1, Math.max(0, n)) * 10000) / 10000;
}

/** Position of a pin in the drawing box. */
export function pinPoint(pin: Pick<EmrBodyMapPin, 'x' | 'y'>): [number, number] {
  return [pin.x * BOX_W, pin.y * BOX_H];
}

/** Size in whole or half centimetres, 0 (not measured) to 50. */
export function stepSize(size: number, direction: 1 | -1): number {
  const next = Math.round((size + direction * 0.5) * 10) / 10;
  return Math.min(SIZE_MAX_CM, Math.max(0, next));
}

export function sizeText(size: number): string {
  return size > 0 ? `${size} cm` : 'not measured';
}

export function registerSize(size: number): string {
  return size > 0 ? `${size} cm` : '—';
}

/** Changing the cause: the three medico-legal causes set the flag; any other cause leaves it as it was. */
export function withCause(pin: EmrBodyMapPin, cause: BodyCause): { pin: EmrBodyMapPin; markedMlc: boolean } {
  const markedMlc = MLC_CAUSES.has(cause) && !pin.mlc;
  return { pin: { ...pin, cause, mlc: pin.mlc || MLC_CAUSES.has(cause) }, markedMlc };
}

export function mlcCount(pins: readonly EmrBodyMapPin[]): number {
  return pins.filter((p) => p.mlc).length;
}

/** Register side text: "n medico-legal" or "Tap a number to find the pin". */
export function registerSideText(pins: readonly EmrBodyMapPin[]): string {
  const n = mlcCount(pins);
  return n > 0 ? `${n} medico-legal` : 'Tap a number to find the pin';
}

/** A short line for the case paper and the history: "Abdomen: tenderness, moderate, 2 cm". */
export function pinLine(pin: EmrBodyMapPin): string {
  const size = pin.sizeCm > 0 ? `, ${pin.sizeCm} cm` : '';
  const side = pin.side === 'BACK' ? ' (back)' : '';
  return `${pin.where}${side}: ${findingLabel(pin.finding).toLowerCase()}, ${severityLabel(pin.severity).toLowerCase()}${size}`;
}

/** The pin number that follows when the doctor deletes one: other pins keep their numbers. */
export function removePin(value: EmrBodyMapValue, no: number): EmrBodyMapValue {
  return { ...value, pins: value.pins.filter((p) => p.no !== no), nextPinNo: nextPinNo(value) };
}
