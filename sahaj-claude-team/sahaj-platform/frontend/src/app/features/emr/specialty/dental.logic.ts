import { DentalProcedureKey, PlanStatus, SpPlanRow, SpTooth, SpToothChart, ToothCondition, ToothSurface } from './specialty.model';

/**
 * Dental rules (SAH-49, SAH-69). Every number and list is "value from the EMR mockup, accepted by the
 * human in DEC-039, not clinically reviewed" (C-3, B-2). The server recomputes; its answer wins.
 */

export const ADULT_UPPER = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28];
export const ADULT_LOWER = [48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38];
export const CHILD_UPPER = [55, 54, 53, 52, 51, 61, 62, 63, 64, 65];
export const CHILD_LOWER = [85, 84, 83, 82, 81, 71, 72, 73, 74, 75];

export const CONDITIONS: readonly { key: ToothCondition; label: string }[] = [
  { key: 'HEALTHY', label: 'Healthy' },
  { key: 'CARIES', label: 'Caries' },
  { key: 'FILLED', label: 'Filled' },
  { key: 'RCT', label: 'RCT' },
  { key: 'CROWN', label: 'Crown' },
  { key: 'MISSING', label: 'Missing' },
  { key: 'TO_EXTRACT', label: 'To extract' },
  { key: 'IMPLANT', label: 'Implant' },
];

export function conditionLabel(key: ToothCondition): string {
  return CONDITIONS.find((c) => c.key === key)?.label ?? key;
}

/** Surfaces may be marked only for caries, filled and crown (contract). */
export const SURFACE_CONDITIONS: ReadonlySet<ToothCondition> = new Set<ToothCondition>(['CARIES', 'FILLED', 'CROWN']);
export const SURFACES: readonly { key: ToothSurface; label: string }[] = [
  { key: 'M', label: 'Mesial' },
  { key: 'O', label: 'Occlusal / incisal' },
  { key: 'D', label: 'Distal' },
  { key: 'B', label: 'Buccal / labial' },
  { key: 'L', label: 'Lingual / palatal' },
];
const SURFACE_ORDER: ToothSurface[] = ['M', 'O', 'D', 'B', 'L'];

export const SHADES = [
  { key: 'A1', swatch: '#F6EFDF' },
  { key: 'B1', swatch: '#FAF6EC' },
  { key: 'A2', swatch: '#F0E3C6' },
  { key: 'A3', swatch: '#E7D3AA' },
  { key: 'C2', swatch: '#DCD2BC' },
] as const;
export const GUMS = [
  { key: 'PINK', label: 'Pink', swatch: '#E8919B' },
  { key: 'LIGHT_BROWN', label: 'Light brown', swatch: '#C58579' },
  { key: 'DARK_BROWN', label: 'Dark brown', swatch: '#8E5650' },
] as const;

const NAMES: Record<number, string> = {
  1: 'Central incisor',
  2: 'Lateral incisor',
  3: 'Canine',
  4: 'First premolar',
  5: 'Second premolar',
  6: 'First molar',
  7: 'Second molar',
  8: 'Third molar',
};
const CHILD_NAMES: Record<number, string> = { 1: 'Central incisor', 2: 'Lateral incisor', 3: 'Canine', 4: 'First molar', 5: 'Second molar' };
const QUAD: Record<number, string> = {
  1: 'upper right',
  2: 'upper left',
  3: 'lower left',
  4: 'lower right',
  5: 'upper right',
  6: 'upper left',
  7: 'lower left',
  8: 'lower right',
};

export function isPrimary(tooth: number): boolean {
  return Math.floor(tooth / 10) > 4;
}

export function isUpper(tooth: number): boolean {
  return [1, 2, 5, 6].includes(Math.floor(tooth / 10));
}

export function toothName(tooth: number): string {
  const q = Math.floor(tooth / 10);
  const p = tooth % 10;
  return `${q > 4 ? `${CHILD_NAMES[p] ?? ''} (milk tooth)` : (NAMES[p] ?? '')}, ${QUAD[q] ?? ''}`;
}

/** FDI numbering: adult 11–48 (digit 1–8), primary 51–85 (digit 1–5). */
export function isValidTooth(tooth: number): boolean {
  const q = Math.floor(tooth / 10);
  const p = tooth % 10;
  return q >= 1 && q <= 8 && (q <= 4 ? p >= 1 && p <= 8 : p >= 1 && p <= 5);
}

export function archOf(dentition: 'ADULT' | 'CHILD'): { upper: number[]; lower: number[] } {
  return dentition === 'ADULT' ? { upper: ADULT_UPPER, lower: ADULT_LOWER } : { upper: CHILD_UPPER, lower: CHILD_LOWER };
}

export function emptyChart(dentition: 'ADULT' | 'CHILD'): SpToothChart {
  return {
    component: 'TOOTH_CHART',
    schemaVersion: 1,
    numbering: 'FDI',
    dentition,
    showNumbers: true,
    teeth: [],
    shade: 'A2',
    gum: 'PINK',
    allHealthyConfirmed: false,
  };
}

export function toothOf(chart: SpToothChart, tooth: number): SpTooth {
  return chart.teeth.find((t) => t.tooth === tooth) ?? { tooth, condition: 'HEALTHY', surfaces: [], mobility: 0 };
}

/** Only teeth that differ from Healthy, no surfaces and G0 are stored (contract). */
export function withTooth(chart: SpToothChart, next: SpTooth): SpToothChart {
  const rest = chart.teeth.filter((t) => t.tooth !== next.tooth);
  const surfaces = SURFACE_CONDITIONS.has(next.condition) ? next.surfaces : [];
  const clean: SpTooth = { ...next, surfaces };
  const plain = clean.condition === 'HEALTHY' && clean.surfaces.length === 0 && clean.mobility === 0;
  return { ...chart, allHealthyConfirmed: false, teeth: plain ? rest : [...rest, clean].sort((a, b) => a.tooth - b.tooth) };
}

export function toggleSurface(tooth: SpTooth, surface: ToothSurface): SpTooth {
  const list = tooth.surfaces.includes(surface) ? tooth.surfaces.filter((s) => s !== surface) : [...tooth.surfaces, surface];
  return { ...tooth, surfaces: SURFACE_ORDER.filter((s) => list.includes(s)) };
}

export function legendCounts(chart: SpToothChart, visible: readonly number[]): { condition: ToothCondition; count: number }[] {
  return CONDITIONS.filter((c) => c.key !== 'HEALTHY').map((c) => ({
    condition: c.key,
    count: visible.filter((n) => toothOf(chart, n).condition === c.key).length,
  }));
}

// ---- suggestions and the plan ----

export interface Suggestion {
  key: DentalProcedureKey;
  label: string;
  phase: number;
}

export const PROCEDURE_LABEL: Record<DentalProcedureKey, string> = {
  COMPOSITE_FILLING: 'Composite filling',
  ROOT_CANAL: 'Root canal treatment',
  PFM_CROWN: 'PFM crown',
  REPLACE_FILLING: 'Replace filling',
  CROWN_RECEMENTATION: 'Crown re-cementation',
  IMPLANT_WITH_CROWN: 'Implant with crown',
  BRIDGE: 'Bridge',
  EXTRACTION: 'Extraction',
  IMPLANT_REVIEW: 'Implant review',
};

const SUGGEST: Partial<Record<ToothCondition, Suggestion[]>> = {
  CARIES: [
    { key: 'COMPOSITE_FILLING', label: 'Composite filling', phase: 1 },
    { key: 'ROOT_CANAL', label: 'Root canal treatment', phase: 1 },
  ],
  RCT: [
    { key: 'ROOT_CANAL', label: 'Root canal treatment', phase: 1 },
    { key: 'PFM_CROWN', label: 'PFM crown', phase: 2 },
  ],
  FILLED: [{ key: 'REPLACE_FILLING', label: 'Replace filling', phase: 1 }],
  CROWN: [{ key: 'CROWN_RECEMENTATION', label: 'Crown re-cementation', phase: 1 }],
  MISSING: [
    { key: 'IMPLANT_WITH_CROWN', label: 'Implant with crown', phase: 3 },
    { key: 'BRIDGE', label: 'Bridge', phase: 2 },
  ],
  TO_EXTRACT: [{ key: 'EXTRACTION', label: 'Extraction', phase: 1 }],
  IMPLANT: [{ key: 'IMPLANT_REVIEW', label: 'Implant review', phase: 3 }],
};

export function suggestionsFor(condition: ToothCondition): Suggestion[] {
  return SUGGEST[condition] ?? [];
}

export const NEXT_SITTING = ['3 days', '7 days', '14 days', '1 month'] as const;

export function procedureText(label: string, surfaces: readonly ToothSurface[]): string {
  return surfaces.length > 0 ? `${label} (${SURFACE_ORDER.filter((s) => surfaces.includes(s)).join('')})` : label;
}

/** Root canal: 3 sittings at start (tagged C-3); every other procedure 1. */
export function defaultSittings(key: DentalProcedureKey | null): number {
  return key === 'ROOT_CANAL' ? 3 : 1;
}

/** Same tooth and procedure twice (ignoring case) is refused (400 duplicate-item on the server). */
export function isDuplicateRow(rows: readonly SpPlanRow[], tooth: number, text: string): boolean {
  const t = text.trim().toLowerCase();
  return rows.some((r) => r.tooth === tooth && r.procedureText.trim().toLowerCase() === t);
}

export const STATUS_LABEL: Record<PlanStatus, string> = { PLANNED: 'Planned', IN_PROGRESS: 'In progress', DONE: 'Done' };
const NEXT_STATUS: Record<PlanStatus, PlanStatus> = { PLANNED: 'IN_PROGRESS', IN_PROGRESS: 'DONE', DONE: 'PLANNED' };

/** The running treatment for the header: "RCT on 36 in progress, sitting 2 of 3" (SAH-75-11 D9); null without a multi-sitting row in progress. */
export function courseSummary(rows: readonly Pick<SpPlanRow, 'tooth' | 'procedureText' | 'status' | 'sittingsPlanned' | 'currentSitting'>[]): string | null {
  const row = rows.find((r) => r.status === 'IN_PROGRESS' && r.sittingsPlanned > 1);
  return row ? `${row.procedureText} on ${row.tooth} in progress, sitting ${row.currentSitting} of ${row.sittingsPlanned}` : null;
}

/** "Tap a status to move it forward": Planned → In progress → Done → Planned. */
export function nextStatus(status: PlanStatus): PlanStatus {
  return NEXT_STATUS[status];
}

export function planSummary(rows: readonly SpPlanRow[]): string {
  const count = (s: PlanStatus): number => rows.filter((r) => r.status === s).length;
  return `${rows.length} items · ${count('DONE')} done · ${count('IN_PROGRESS')} in progress · ${count('PLANNED')} planned`;
}

/** The amount a row shows: the server's tariff or typed price, else the typed one on screen, else none ("Not in tariff"). */
export function rowAmountPaise(row: SpPlanRow): number | null {
  return row.estimatePaise ?? row.typedEstimatePaise ?? null;
}

export function estimateTotalPaise(rows: readonly SpPlanRow[]): number {
  return rows.reduce((sum, r) => sum + (rowAmountPaise(r) ?? 0), 0);
}

/**
 * B-2: a row with several sittings splits its estimate into equal parts rounded to ₹ 50, the last
 * sitting taking the rest (analyst reading, flagged). Amounts in paise.
 */
export function splitShares(totalPaise: number, sittings: number): number[] {
  if (sittings <= 1) {
    return [totalPaise];
  }
  const part = Math.round(totalPaise / sittings / 5000) * 5000;
  const shares = Array.from({ length: sittings - 1 }, () => part);
  return [...shares, totalPaise - part * (sittings - 1)];
}

export const NO_TARIFF = 'Not in tariff';
