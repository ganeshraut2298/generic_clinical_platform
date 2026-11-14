import { EmrFlag, EmrGrowthBand, ModalityKey, OutcomeScaleKey, SpAncCheck, SpAncToday, SpRomTable, TestResult } from './specialty.model';

/**
 * Rules of Obstetrics and Gynaecology (SAH-61, C-4), Paediatrics (SAH-62, C-5) and Physiotherapy
 * (SAH-63, C-6). Every number is "value from the EMR mockup, accepted by the human in DEC-039, not
 * clinically reviewed". The server recomputes on every save and read; its answer wins.
 */

// ---- Obstetrics (C-4) ----

export interface AncField {
  key: keyof SpAncToday;
  label: string;
  step: number;
  unit: string;
  decimals: number;
  /** Where "+" and "−" start when nothing is entered: the mockup's drawn starting value. */
  start: number;
}

export const ANC_FIELDS: readonly AncField[] = [
  { key: 'weightKg', label: 'Weight', step: 0.5, unit: 'kg', decimals: 1, start: 60 },
  { key: 'bpSystolic', label: 'BP systolic', step: 2, unit: 'mmHg', decimals: 0, start: 120 },
  { key: 'bpDiastolic', label: 'BP diastolic', step: 2, unit: 'mmHg', decimals: 0, start: 80 },
  { key: 'fundalHeightCm', label: 'Fundal height', step: 1, unit: 'cm', decimals: 0, start: 27 },
  { key: 'fetalHeartBpm', label: 'Fetal heart', step: 2, unit: 'bpm', decimals: 0, start: 140 },
  { key: 'hbGdl', label: 'Haemoglobin', step: 0.1, unit: 'g/dL', decimals: 1, start: 11 },
];

export function stepValue(current: number | null | undefined, field: AncField, direction: 1 | -1): number {
  const base = current ?? field.start;
  return Math.round((base + direction * field.step) * 10) / 10;
}

export function anaemiaGrade(hb: number | null | undefined): 'Mild' | 'Moderate' | 'Severe' | null {
  if (hb == null || hb >= 11) {
    return null;
  }
  return hb < 7 ? 'Severe' : hb < 10 ? 'Moderate' : 'Mild';
}

export function highBp(today: SpAncToday): boolean {
  return (today.bpSystolic ?? 0) >= 140 || (today.bpDiastolic ?? 0) >= 90;
}

/** The note under each value; the fundal check needs the gestational age in weeks (null: "Needs LMP or dating scan"). */
export function ancNote(key: keyof SpAncToday, today: SpAncToday, gaWeeks: number | null, weightChangeKg: number | null): EmrFlag | null {
  switch (key) {
    case 'weightKg':
      return today.weightKg != null && weightChangeKg != null
        ? { field: key, label: `${weightChangeKg >= 0 ? '+' : ''}${weightChangeKg.toFixed(1)} kg since last visit`, alert: false }
        : null;
    case 'bpSystolic':
    case 'bpDiastolic':
      return today.bpSystolic == null && today.bpDiastolic == null
        ? null
        : highBp(today)
          ? { field: key, label: 'High: 140/90 or above', alert: true }
          : { field: key, label: 'Normal', alert: false };
    case 'fundalHeightCm': {
      if (today.fundalHeightCm == null) {
        return null;
      }
      if (gaWeeks == null) {
        return { field: key, label: 'Needs LMP or dating scan', alert: false };
      }
      return Math.abs(today.fundalHeightCm - gaWeeks) > 2
        ? { field: key, label: `Does not match ${gaWeeks} weeks`, alert: true }
        : { field: key, label: 'Matches gestational age', alert: false };
    }
    case 'fetalHeartBpm':
      return today.fetalHeartBpm == null
        ? null
        : today.fetalHeartBpm < 110 || today.fetalHeartBpm > 160
          ? { field: key, label: 'Outside 110–160', alert: true }
          : { field: key, label: 'Normal (110–160)', alert: false };
    case 'hbGdl': {
      if (today.hbGdl == null) {
        return null;
      }
      const grade = anaemiaGrade(today.hbGdl);
      return grade ? { field: key, label: `${grade} anaemia`, alert: true } : { field: key, label: 'Normal', alert: false };
    }
    default:
      return null;
  }
}

export interface AncAlert {
  id: 'PIH' | 'ANAEMIA' | 'WARNING';
  title: string;
  text: string;
  button: string;
  /** The order the button records, when it records one. */
  order?: 'PIH_WORKUP' | 'NST';
}

export function ancAlerts(value: SpAncCheck): AncAlert[] {
  const out: AncAlert[] = [];
  const danger = value.symptoms.includes('HEADACHE') || value.symptoms.includes('BLURRED_VISION');
  if (highBp(value.today)) {
    out.push({
      id: 'PIH',
      title: 'Possible pre-eclampsia.',
      text: `Repeat BP after 15 minutes rest and check urine protein.${danger ? ' Headache / blurred vision reported.' : ''}`,
      button: value.orders.some((o) => o.key === 'PIH_WORKUP') ? '✓ Workup ordered' : 'Order PIH workup',
      order: 'PIH_WORKUP',
    });
  }
  const grade = anaemiaGrade(value.today.hbGdl);
  if (grade) {
    out.push({
      id: 'ANAEMIA',
      title: `${grade} anaemia (Hb ${(value.today.hbGdl ?? 0).toFixed(1)}).`,
      text: grade === 'Moderate' ? 'Consider IV iron at this stage of pregnancy.' : grade === 'Severe' ? 'Needs urgent evaluation.' : 'Continue oral iron.',
      button: value.anaemiaPlanAdded ? '✓ Added to plan' : 'Add to plan',
    });
  }
  if (value.symptoms.includes('BLEEDING_LEAKING') || value.symptoms.includes('REDUCED_MOVEMENTS')) {
    out.push({
      id: 'WARNING',
      title: 'Warning sign reported.',
      text: 'Do NST and examine before the patient leaves.',
      button: value.orders.some((o) => o.key === 'NST') ? '✓ NST ordered' : 'Order NST',
      order: 'NST',
    });
  }
  return out;
}

export const ANC_SYMPTOMS: readonly { key: SpAncCheck['symptoms'][number]; label: string; danger: boolean; text: string }[] = [
  { key: 'HEADACHE', label: 'Headache', danger: true, text: 'Headache' },
  { key: 'BLURRED_VISION', label: 'Blurred vision', danger: true, text: 'Blurred vision' },
  { key: 'BLEEDING_LEAKING', label: 'Bleeding / leaking', danger: true, text: 'Bleeding / leaking' },
  { key: 'REDUCED_MOVEMENTS', label: 'Reduced movements', danger: true, text: 'Reduced movements' },
];

/** "Order all" and the due-now list: key, label, window as drawn. */
export const DUE_ORDERS: readonly { key: SpAncCheck['orders'][number]['key']; label: string; window: string; investigation: string }[] = [
  { key: 'OGTT_75G', label: 'OGTT (75 g)', window: '24–28 w', investigation: 'OGTT (75 g)' },
  { key: 'TD_2ND_DOSE', label: 'Td 2nd dose', window: 'give today', investigation: 'Td 2nd dose' },
  { key: 'GROWTH_SCAN_FORM_F', label: 'Growth scan + Form F', window: '28–32 w', investigation: 'Growth scan + Form F' },
];
export const EXTRA_ORDERS = {
  PIH_WORKUP: { label: 'PIH workup', investigation: 'PIH workup: urine protein, LFT, platelets, uric acid' },
  NST: { label: 'NST', investigation: 'NST' },
} as const;

export const SCANS: readonly { key: SpAncCheck['scans'][number]['key']; name: string; window: string; later: string }[] = [
  { key: 'DATING', name: 'Dating scan', window: '6–9 w', later: '' },
  { key: 'NT', name: 'NT scan', window: '11–13+6 w', later: '' },
  { key: 'ANOMALY', name: 'Anomaly scan', window: '18–22 w', later: '' },
  { key: 'GROWTH', name: 'Growth scan', window: '28–32 w', later: 'Ordering it opens Form F automatically.' },
  { key: 'DOPPLER_NST', name: 'Doppler / NST', window: '36 w +', later: 'Planned from 36 weeks, or earlier if growth restriction is suspected.' },
];

export const SUPPLEMENTS: readonly {
  key: NonNullable<SpAncCheck['supplements']>[number];
  name: string;
  dose: string;
  rx: { frequency: '1-0-1' | '1-0-0' | '0-1-0'; when: 'AFTER_FOOD'; note: string | null };
}[] = [
  { key: 'IRON', name: 'Ferrous ascorbate 100 mg', dose: '1-0-1 · after food', rx: { frequency: '1-0-1', when: 'AFTER_FOOD', note: null } },
  {
    key: 'CALCIUM_VIT_D3',
    name: 'Calcium 500 mg + Vit D3',
    dose: '0-1-0 · 2 h apart from iron',
    rx: { frequency: '0-1-0', when: 'AFTER_FOOD', note: '2 h apart from iron' },
  },
  { key: 'FOLIC_ACID', name: 'Folic acid 5 mg', dose: '1-0-0', rx: { frequency: '1-0-0', when: 'AFTER_FOOD', note: null } },
];

export const GYN_QUICK_ORDERS: readonly string[] = ['Pap smear', 'Pelvic USG', 'Thyroid profile', 'CBC', 'Hormone panel (FSH, LH, AMH)'];

// ---- Paediatrics (C-5) ----

export interface DoseRule {
  key: 'PARACETAMOL_120_5' | 'PARACETAMOL_250_5' | 'IBUPROFEN_100_5';
  name: string;
  sub: string;
  perKg: number;
  mgPerMl: number;
  maxMg: number;
  freq: string;
}

export const DOSE_RULES: readonly DoseRule[] = [
  { key: 'PARACETAMOL_120_5', name: 'Paracetamol syrup 120 mg / 5 ml', sub: 'Fever or pain', perKg: 15, mgPerMl: 24, maxMg: 500, freq: 'every 6 hours if needed, at most 4 doses a day' },
  { key: 'PARACETAMOL_250_5', name: 'Paracetamol syrup 250 mg / 5 ml', sub: 'Fever or pain, stronger syrup', perKg: 15, mgPerMl: 50, maxMg: 500, freq: 'every 6 hours if needed, at most 4 doses a day' },
  { key: 'IBUPROFEN_100_5', name: 'Ibuprofen syrup 100 mg / 5 ml', sub: 'Fever or pain, with food', perKg: 10, mgPerMl: 20, maxMg: 400, freq: 'every 8 hours with food, at most 3 doses a day' },
];

/** mg = min(max mg, round(weight × mg/kg)); ml = round(mg ÷ mg-per-ml, 1 decimal). No weight, no dose. */
export function doseFor(weightKg: number | null | undefined, rule: DoseRule): { mg: number; ml: number; capped: boolean } | null {
  if (weightKg == null || weightKg <= 0) {
    return null;
  }
  const raw = Math.round(weightKg * rule.perKg);
  const mg = Math.min(rule.maxMg, raw);
  return { mg, ml: Math.round((mg / rule.mgPerMl) * 10) / 10, capped: raw > rule.maxMg };
}

export function doseText(dose: { mg: number; ml: number }): string {
  return `${dose.ml.toFixed(1)} ml (${dose.mg} mg)`;
}

/** The weight-for-age notes at 18 months (OQ-45-32): the built-in drawn bands when the reference has none. */
export const DRAWN_WEIGHT_BANDS: readonly EmrGrowthBand[] = [
  { measure: 'WEIGHT', sex: null, ageMonthsFrom: 18, ageMonthsTo: 18, upperExclusive: 8.6, label: 'Below 3rd centile', alert: true },
  { measure: 'WEIGHT', sex: null, ageMonthsFrom: 18, ageMonthsTo: 18, upperExclusive: 9.8, label: '~15th centile', alert: false },
  { measure: 'WEIGHT', sex: null, ageMonthsFrom: 18, ageMonthsTo: 18, upperExclusive: 11, label: '~25–50th centile', alert: false },
  { measure: 'WEIGHT', sex: null, ageMonthsFrom: 18, ageMonthsTo: 18, upperExclusive: 13.7, label: '~50–97th centile', alert: false },
  { measure: 'WEIGHT', sex: null, ageMonthsFrom: 18, ageMonthsTo: 18, upperExclusive: null, label: 'Above 97th centile', alert: true },
];
export const NO_CENTILE = 'Centile not available for this age';

/**
 * First band of the measure whose age range holds the child's age and whose upper bound is above the
 * value; no band, no note. Length and head-circumference notes are never shown (DEC-044, H8).
 */
export function weightNote(bands: readonly EmrGrowthBand[] | undefined, ageMonths: number | null, weightKg: number | null | undefined): EmrFlag | null {
  if (weightKg == null) {
    return null;
  }
  const list = (bands && bands.length > 0 ? bands : DRAWN_WEIGHT_BANDS).filter((b) => b.measure.toUpperCase() === 'WEIGHT');
  const hit = ageMonths == null ? undefined : list.find((b) => ageMonths >= b.ageMonthsFrom && ageMonths <= b.ageMonthsTo && (b.upperExclusive == null || weightKg < b.upperExclusive));
  return hit ? { field: 'weight', label: hit.label, alert: hit.alert } : { field: 'weight', label: NO_CENTILE, alert: false };
}

/** The weight shown by the server as a note for the weight only (length and head circumference hidden). */
export function weightOnly(flags: readonly EmrFlag[] | undefined): EmrFlag | null {
  return (flags ?? []).find((f) => f.field.toLowerCase().startsWith('weight')) ?? null;
}

export function stepOne(value: number | null | undefined, start: number, step: number, direction: 1 | -1): number {
  return Math.round(((value ?? start) + direction * step) * 10) / 10;
}

export const GROWTH_STEPS = {
  weightKg: { label: 'Weight', unit: 'kg', step: 0.1, start: 10, min: 0.3, max: 150 },
  lengthCm: { label: 'Length', unit: 'cm', step: 0.5, start: 80, min: 25, max: 200 },
  headCircCm: { label: 'Head circ.', unit: 'cm', step: 0.5, start: 46, min: 20, max: 70 },
} as const;

/** x for the age (Birth 40, 12 m about 200, 18 m 280, 24 m 360) and y for the weight, clamped, as drawn. */
export function chartPoint(ageMonths: number, weightKg: number): { x: number; y: number } {
  const y = Math.round(Math.max(30, Math.min(165, 90 - (weightKg - 10.2) * 18)));
  return { x: Math.round(40 + Math.min(24, Math.max(0, ageMonths)) * (320 / 24)), y };
}

/** The drawn milestones for 18 months, used when the reference has none (labels only). */
export const DRAWN_MILESTONES: readonly { key: string; label: string; domain: string }[] = [
  { key: 'walks_alone', label: 'Walks alone', domain: 'Gross motor' },
  { key: 'climbs_stairs_with_help', label: 'Climbs stairs with help', domain: 'Gross motor' },
  { key: 'scribbles_with_crayon', label: 'Scribbles with crayon', domain: 'Fine motor' },
  { key: 'feeds_self_with_spoon', label: 'Feeds self with spoon', domain: 'Social' },
  { key: 'points_to_show_interest', label: 'Points to show interest', domain: 'Social' },
  { key: 'says_6_or_more_words', label: 'Says 6 or more words', domain: 'Language' },
];

// ---- Physiotherapy (C-6) ----

/** Knee reference of the mockup: movement, normal maximum, bar percent of right vs normal. */
export const KNEE_ROM: readonly { key: string; label: string; normalMax: number }[] = [
  { key: 'flexion', label: 'Flexion', normalMax: 135 },
  { key: 'extension', label: 'Extension', normalMax: 0 },
  { key: 'flexion_passive', label: 'Flexion (passive)', normalMax: 135 },
];
/** A joint item: the reference key (`emr_joint_item.item_key`) a reference row stores, and its label. A typed row stores its text. */
export interface JointItem {
  key: string;
  label: string;
  normalMax?: number | null;
}
export const KNEE_MMT: readonly JointItem[] = [
  { key: 'quadriceps', label: 'Quadriceps' },
  { key: 'hamstrings', label: 'Hamstrings' },
];
export const KNEE_TESTS: readonly JointItem[] = [
  { key: 'mcmurray_medial', label: 'McMurray (medial)' },
  { key: 'lachman', label: 'Lachman' },
  { key: 'patellar_grind', label: 'Patellar grind' },
  { key: 'anterior_drawer', label: 'Anterior drawer' },
  { key: 'valgus_stress', label: 'Valgus stress' },
  { key: 'apley_compression', label: 'Apley compression' },
];

/** The reference items of a joint (`group` = the joint key), or the drawn knee items when the list is absent or has none for the joint. */
export function jointItems(
  list: readonly { key: string; label: string; group?: string | null; number?: number | null }[] | undefined,
  joint: string,
  fallback: readonly JointItem[],
): readonly JointItem[] {
  const own = (list ?? []).filter((i) => (i.group ?? '').toLowerCase() === joint.trim().toLowerCase());
  if (own.length > 0) {
    return own.map((i) => ({ key: i.key, label: i.label, normalMax: i.number ?? null }));
  }
  return (list ?? []).length === 0 && joint.trim().toLowerCase() === 'knee' ? fallback : [];
}

// ---- outcome scales (contract 0.5.1, ADR-023 D13; tagged C-6, H18, H20) ----

export const OUTCOME_SCALE_KEYS: readonly OutcomeScaleKey[] = ['ODI', 'NDI', 'WOMAC', 'LEFS'];
/** The scales' ranges, used as sanity limits (not clinical thresholds). */
export const OUTCOME_SCALE_MAX: Record<OutcomeScaleKey, number> = { ODI: 100, NDI: 50, WOMAC: 96, LEFS: 80 };

/** "WOMAC 48 → 41": the chip of the mockup; the previous score is the latest earlier signed record's. */
export function scaleChip(scale: string, previous: number | null | undefined, today: number | null | undefined): string | null {
  if (previous == null && today == null) {
    return null;
  }
  if (previous == null) {
    return `${scale} ${today}`;
  }
  return `${scale} ${previous} → ${today ?? '—'}`;
}

/** Right ÷ normal maximum × 100; extension (normal 0°) is 100 % at 0° and 90 % otherwise. */
export function romPercent(rightDeg: number | null, normalMax: number): number | null {
  if (rightDeg == null) {
    return null;
  }
  return normalMax === 0 ? (rightDeg === 0 ? 100 : 90) : Math.round((rightDeg / normalMax) * 100);
}

export function normalText(normalMax: number): string {
  return normalMax === 0 ? '0°' : `0–${normalMax}°`;
}

export function romRows(table: SpRomTable): { movement: string; label: string; normalMax: number | null; rightDeg: number | null; leftDeg: number | null }[] {
  const knee = table.joint === 'knee';
  return table.rows.map((r) => {
    const ref = knee ? KNEE_ROM.find((k) => k.key === r.movement) : undefined;
    return { movement: r.movement, label: ref?.label ?? r.movement, normalMax: ref ? ref.normalMax : null, rightDeg: r.rightDeg, leftDeg: r.leftDeg };
  });
}

export const MODALITIES: readonly { key: ModalityKey; name: string; params: string }[] = [
  { key: 'IFT', name: 'IFT', params: '15 min · knee, 4-pole' },
  { key: 'HOT_PACK', name: 'Hot pack', params: '10 min · before exercise' },
  { key: 'ULTRASOUND', name: 'Ultrasound', params: '1 MHz · 5 min' },
  { key: 'TENS', name: 'TENS', params: '20 min · conventional' },
  { key: 'EXERCISE_THERAPY', name: 'Exercise therapy', params: 'Supervised, 20 min' },
  { key: 'TAPING', name: 'Taping', params: 'Patella, unloading' },
];

export const GAITS: readonly { key: 'NORMAL' | 'ANTALGIC' | 'WITH_STICK' | 'WITH_WALKER'; label: string }[] = [
  { key: 'NORMAL', label: 'Normal' },
  { key: 'ANTALGIC', label: 'Antalgic' },
  { key: 'WITH_STICK', label: 'With stick' },
  { key: 'WITH_WALKER', label: 'With walker' },
];

export const RESULT_LABEL: Record<TestResult, string> = { NOT_DONE: 'Not done', POSITIVE: 'Positive', NEGATIVE: 'Negative' };
const NEXT_RESULT: Record<TestResult, TestResult> = { NOT_DONE: 'POSITIVE', POSITIVE: 'NEGATIVE', NEGATIVE: 'NOT_DONE' };

/** Not done → Positive (red) → Negative (green) → Not done. */
export function nextResult(result: TestResult): TestResult {
  return NEXT_RESULT[result];
}

/** The pain pad colour band of the mockup: 0–3 good, 4–6 amber, 7–10 red. */
export function painBand(n: number): 'good' | 'warn' | 'crit' {
  return n <= 3 ? 'good' : n <= 6 ? 'warn' : 'crit';
}

export const PHYSIO_PACKAGE_SESSIONS = 10;

/** Home exercises: the exercise library is Later (OQ-45-28); the doctor types the name. */
export const HEP_DOSE_TEXT = (e: { sets?: number | null; reps?: number | null; holdSeconds?: number | null }): string => {
  const parts: string[] = [];
  if (e.sets && e.reps) {
    parts.push(`${e.sets} × ${e.reps}`);
  } else if (e.sets) {
    parts.push(`${e.sets} sets`);
  } else if (e.reps) {
    parts.push(`${e.reps} reps`);
  }
  if (e.holdSeconds) {
    parts.push(`hold ${e.holdSeconds} s`);
  }
  return parts.join(' · ');
};
