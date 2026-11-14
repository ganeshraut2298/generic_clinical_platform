import {
  EmrOncoView,
  OncAlert,
  OncDecisionKey,
  OncDeviationReason,
  OncEvent,
  OncResponse,
  OncSystemKey,
  SpOncEcog,
  SpOncExam,
  SpOncLabs,
  SpOncTox,
  SpVitalsEntry,
  ToxKey,
} from './specialty.model';

/**
 * Medical Oncology rules (SAH-65, SAH-66, SAH-70; C-8). Every number is "value from the EMR mockup,
 * accepted by the human in DEC-039, not clinically reviewed". The server recomputes on every read and
 * save and its answer wins; this preview only lets the screen answer while the doctor is typing.
 */

export const TOX_LABEL: Record<ToxKey, string> = {
  NAUSEA: 'Nausea',
  VOMITING: 'Vomiting',
  MOUTH_SORES: 'Mouth sores',
  LOOSE_MOTIONS: 'Loose motions',
  TINGLING_NUMBNESS: 'Tingling / numbness',
  FATIGUE: 'Fatigue',
  FEVER_LOW_COUNTS: 'Fever with low counts',
  PAIN: 'Pain',
  LOSS_OF_APPETITE: 'Loss of appetite',
};

export const EVENT_LABEL: Record<OncEvent, string> = {
  ER_VISIT: 'ER visit',
  ADMITTED: 'Admitted',
  FEVER_38_AT_HOME: 'Fever ≥ 38 °C at home',
  BLOOD_TRANSFUSION: 'Blood transfusion',
  ANTIBIOTICS: 'Antibiotics',
  MISSED_ORAL_MEDICINES: 'Missed oral medicines',
};

/** CTCAE 0 none · 1 mild · 2 moderate · 3 severe · 4 life-threatening. */
export const GRADE_LINE = 'CTCAE 0 none · 1 mild · 2 moderate · 3 severe · 4 life-threatening.';

export const LAB_ROWS: readonly {
  key: 'hb' | 'anc' | 'plt' | 'cr' | 'bili' | 'alt' | 'lvef';
  label: string;
  unit: string;
  step: number;
  decimals: number;
  min: number | null;
  max: number | null;
  needed: string;
}[] = [
  { key: 'hb', label: 'Haemoglobin', unit: 'g/dL', step: 0.2, decimals: 1, min: 8, max: null, needed: '≥ 8' },
  { key: 'anc', label: 'Neutrophils (ANC)', unit: '/µL', step: 100, decimals: 0, min: 1500, max: null, needed: '≥ 1,500' },
  { key: 'plt', label: 'Platelets', unit: '×10³/µL', step: 10, decimals: 0, min: 100, max: null, needed: '≥ 100' },
  { key: 'cr', label: 'Creatinine', unit: 'mg/dL', step: 0.1, decimals: 1, min: null, max: 1.5, needed: '≤ 1.5' },
  { key: 'bili', label: 'Bilirubin', unit: 'mg/dL', step: 0.1, decimals: 1, min: null, max: 1.5, needed: '≤ 1.5' },
  { key: 'alt', label: 'ALT (SGPT)', unit: 'U/L', step: 5, decimals: 0, min: null, max: 100, needed: '≤ 100' },
  { key: 'lvef', label: 'Heart pumping (LVEF)', unit: '%', step: 1, decimals: 0, min: 50, max: null, needed: '≥ 50' },
];

/** The sentence of the mockup for the ECOG grades 0 to 4. */
export const ECOG_TEXT: readonly string[] = [
  'Fully active',
  'Light work possible',
  'Up and about over 50% of day; no work',
  'In bed or chair over 50% of day',
  'Fully bedbound',
];

export const DECISIONS: readonly { key: OncDecisionKey; label: string; sub: string }[] = [
  { key: 'GIVE_AS_PLANNED', label: 'Give as planned', sub: 'Full dose today' },
  { key: 'GIVE_REDUCED', label: 'Give, reduce dose', sub: 'Next level down' },
  { key: 'DELAY_1_WEEK', label: 'Delay 1 week', sub: 'Repeat counts, re-book' },
  { key: 'CHANGE_REGIMEN', label: 'Change regimen', sub: 'New protocol' },
  { key: 'STOP', label: 'Stop chemo', sub: 'Palliative / supportive care' },
];

export const DEVIATION_REASONS: readonly { key: OncDeviationReason; label: string }[] = [
  { key: 'PATIENT_PREFERENCE', label: 'Patient preference' },
  { key: 'TUMOUR_BOARD_ADVICE', label: 'Tumour board advice' },
  { key: 'COUNTS_RECOVERING', label: 'Counts recovering, recheck tomorrow' },
  { key: 'CLINICAL_JUDGEMENT', label: 'Clinical judgement' },
  { key: 'SCHEME_COST', label: 'Scheme / cost reasons' },
];

export function decisionLabel(key: OncDecisionKey | null | undefined): string {
  return DECISIONS.find((d) => d.key === key)?.label ?? '';
}

export const RESPONSE_LABEL: Record<OncResponse, string> = {
  COMPLETE_RESPONSE: 'Complete response',
  PARTIAL_RESPONSE: 'Partial response',
  STABLE_DISEASE: 'Stable disease',
  PROGRESSIVE_DISEASE: 'Progressive disease',
};
export const RESPONSE_SHORT: Record<OncResponse, string> = {
  COMPLETE_RESPONSE: 'CR',
  PARTIAL_RESPONSE: 'PR',
  STABLE_DISEASE: 'SD',
  PROGRESSIVE_DISEASE: 'PD',
};

export const ALERT_TEXT: Record<OncAlert, string> = {
  GRADE_3_PLUS: 'Grade 3 or more side effect',
  LAB_OUT_OF_RANGE: 'A lab is out of range',
  FEVER: 'Fever',
  WEIGHT_CHANGE_10: 'Weight changed by 10% or more',
  CUMULATIVE_OVER_400: 'Doxorubicin over 400 mg/m²',
  PORT_NO_BLOOD_RETURN: 'Port: no blood return yet',
};

export const SIGN_LABELS: readonly { key: keyof NonNullable<SpOncExam['signs']>; label: string }[] = [
  { key: 'pallor', label: 'Pallor' },
  { key: 'icterus', label: 'Icterus' },
  { key: 'cyanosis', label: 'Cyanosis' },
  { key: 'clubbing', label: 'Clubbing' },
  { key: 'lymphNodes', label: 'Lymph nodes (other)' },
  { key: 'pedalOedema', label: 'Pedal oedema' },
  { key: 'dehydration', label: 'Dehydration' },
];

export const BREAST_SKIN: readonly { key: SpOncExam['breastSkin'][number]; label: string }[] = [
  { key: 'SKIN_REDNESS', label: 'Skin redness' },
  { key: 'PEAU_D_ORANGE', label: 'Peau d’orange' },
  { key: 'ULCERATION', label: 'Ulceration' },
  { key: 'NIPPLE_RETRACTION', label: 'Nipple retraction' },
  { key: 'FIXED_TO_CHEST_WALL', label: 'Fixed to chest wall' },
  { key: 'LUMP_NOW_MOBILE', label: 'Lump now mobile' },
];

/** The five systems of the mockup with their "why" lines and finding chips (contract 0.5.1 adds `bone`, "Spine and bones"; `pa` shows as "Abdomen"). */
export const ONC_SYSTEMS: readonly { key: OncSystemKey; label: string; why: string; chips: string[]; normalText: string }[] = [
  { key: 'cvs', label: 'Heart (CVS)', why: 'Anthracycline: watch for heart failure', chips: ['New murmur', 'Raised JVP', 'Tachycardia', 'S3'], normalText: 'S1 S2 heard, no murmur' },
  { key: 'rs', label: 'Lungs (RS)', why: 'Effusion, infection, drug lung', chips: ['Reduced air entry', 'Crepitations', 'Dull note (effusion)', 'Wheeze'], normalText: 'Air entry equal, clear' },
  { key: 'pa', label: 'Abdomen', why: 'Liver spread, ascites', chips: ['Liver enlarged', 'Tenderness', 'Ascites', 'Mass'], normalText: 'Soft, non-tender' },
  { key: 'cns', label: 'Nervous system', why: 'Brain spread, neuropathy', chips: ['Focal weakness', 'Reduced sensation', 'Reflexes reduced', 'Confusion'], normalText: 'Conscious, oriented, no focal deficit' },
  { key: 'bone', label: 'Spine and bones', why: 'Bone spread', chips: ['Spine tenderness', 'Rib tenderness', 'Restricted movement'], normalText: '' },
];

export const LINE_DEVICES: readonly { key: 'PORT' | 'PICC' | 'PERIPHERAL'; label: string }[] = [
  { key: 'PORT', label: 'Chemo port (right chest)' },
  { key: 'PICC', label: 'PICC line' },
  { key: 'PERIPHERAL', label: 'Peripheral cannula' },
];
export const LINE_CHECKS: readonly { key: NonNullable<SpOncExam['line']>['checks'][number]; label: string }[] = [
  { key: 'FLUSHES_FREELY', label: 'Flushes freely' },
  { key: 'BLOOD_RETURN', label: 'Blood return seen' },
  { key: 'NO_REDNESS_SWELLING', label: 'No redness or swelling' },
  { key: 'NO_PAIN', label: 'No pain at site' },
  { key: 'DRESSING_CLEAN', label: 'Dressing clean' },
  { key: 'FLUSHED_4_WEEKS', label: 'Flushed in last 4 weeks' },
];

export const TAKE_HOME: readonly { key: string; name: string; dose: string; why: string }[] = [
  { key: 'ONDANSETRON_8', name: 'Ondansetron 8 mg', dose: '1-0-1 × 3 days', why: 'Nausea' },
  { key: 'DEXAMETHASONE_4', name: 'Dexamethasone 4 mg', dose: '1-0-1 × 2 days', why: 'Delayed nausea' },
  { key: 'PANTOPRAZOLE_40', name: 'Pantoprazole 40 mg', dose: '1-0-0 × 5 days', why: 'Stomach protection' },
  { key: 'PEGFILGRASTIM_6', name: 'Pegfilgrastim 6 mg SC', dose: 'Once, day 2', why: 'Prevents low counts / fever' },
  { key: 'BENZYDAMINE_MOUTHWASH', name: 'Benzydamine mouthwash', dose: '3 times a day × 7 days', why: 'Mouth sores' },
  { key: 'LOPERAMIDE_2', name: 'Loperamide 2 mg', dose: 'After each loose motion (max 8)', why: 'Loose motions' },
];
/** The first, second, third and fifth lines are ticked at the start of a cycle (SAH-70-1); Pegfilgrastim joins when suggested; Loperamide is not ticked. */
export const TAKE_HOME_DEFAULT: readonly string[] = ['ONDANSETRON_8', 'DEXAMETHASONE_4', 'PANTOPRAZOLE_40', 'BENZYDAMINE_MOUTHWASH'];

export const REFERRALS: readonly { key: string; label: string }[] = [
  { key: 'RADIOTHERAPY', label: 'Radiotherapy' },
  { key: 'SURGICAL_ONCOLOGY', label: 'Surgical oncology' },
  { key: 'PALLIATIVE_CARE', label: 'Palliative care' },
  { key: 'DIETITIAN', label: 'Dietitian' },
  { key: 'PSYCHO_ONCOLOGY_COUNSELLING', label: 'Psycho-oncology counselling' },
  { key: 'FERTILITY_COUNSELLING', label: 'Fertility counselling' },
  { key: 'SOCIAL_WORKER_SCHEME_PAPERS', label: 'Social worker (scheme papers)' },
  { key: 'CARDIOLOGY_ECHO', label: 'Cardiology (echo)' },
];

export const LEVELS: readonly (100 | 90 | 80 | 75)[] = [100, 90, 80, 75];
export const REDUCTION_REASONS: readonly { key: string; label: string }[] = [
  { key: 'LOW_NEUTROPHILS_LAST_CYCLE', label: 'Low neutrophils last cycle' },
  { key: 'FEVER_LOW_COUNTS', label: 'Fever with low counts' },
  { key: 'GRADE_3_SIDE_EFFECT', label: 'Grade 3 side effect' },
  { key: 'NEUROPATHY', label: 'Neuropathy' },
  { key: 'LIVER_FUNCTION', label: 'Liver function' },
  { key: 'FRAILTY', label: 'Frailty' },
];

export const CHECK_LABEL: Record<string, string> = {
  DECISION_RECORDED: 'Decision recorded',
  FIT_TO_TREAT: 'Fit to treat today',
  LABS_72H: 'Labs within 72 hours',
  BSA_TODAY: 'BSA from today’s weight',
  CUMULATIVE_DOSE: 'Cumulative dose within limit',
  REDUCTION_REASON: 'Dose-reduction reason',
  ALLERGIES_REVIEWED: 'Allergies reviewed',
  CONSENT_REGIMEN: 'Consent for this regimen',
  SCHEME_APPROVAL: 'Scheme / TPA approval',
  LINE_VESICANT: 'Line ready for vesicant',
};
export const CHECK_ACTION_LABEL: Record<string, string> = {
  GO: 'Go',
  CONFIRM: 'Confirm',
  UNDO: 'Undo',
  MARK_SIGNED: 'Mark signed',
  RAISE_PREAUTH: 'Raise pre-auth',
};

export const STAGE_LABELS = ['Decided', 'Doses checked', 'Second check', 'Pharmacy', 'Chair', 'Given'] as const;

// ---- preview ----

/** BSA = √(height cm × weight kg ÷ 3600), 2 decimals; capped at 2.0 m² when the cap is on. */
export function bsa(heightCm: number | null | undefined, weightKg: number | null | undefined, cap: boolean): { raw: number | null; used: number | null } {
  if (!heightCm || !weightKg) {
    return { raw: null, used: null };
  }
  const raw = Math.round(Math.sqrt((heightCm * weightKg) / 3600) * 100) / 100;
  return { raw, used: cap ? Math.min(2, raw) : raw };
}

/** Weight change % = (today − cycle 1 weight) ÷ cycle 1 weight × 100; 10 or more either way recalculates. */
export function weightChangePct(todayKg: number | null | undefined, cycle1Kg: number | null | undefined): number | null {
  if (!todayKg || !cycle1Kg) {
    return null;
  }
  return Math.round(((todayKg - cycle1Kg) / cycle1Kg) * 1000) / 10;
}

export function lesionPct(baseline: number, today: number | null | undefined): number | null {
  return baseline > 0 && today != null ? ((today - baseline) / baseline) * 100 : null;
}

/** Per lesion: green at −30 % or better, red at +20 % or worse. */
export function lesionColour(pct: number | null): 'GREEN' | 'RED' | 'NONE' {
  return pct == null ? 'NONE' : pct <= -30 ? 'GREEN' : pct >= 20 ? 'RED' : 'NONE';
}

export function lesionSums(exam: SpOncExam): EmrOncoView['sums'] | null {
  if (exam.lesions.length === 0) {
    return null;
  }
  const B = exam.lesions.reduce((s, l) => s + l.baselineCm, 0);
  const P = exam.lesions.reduce((s, l) => s + (l.lastCm ?? l.baselineCm), 0);
  const T = exam.lesions.reduce((s, l) => s + (l.todayCm ?? l.lastCm ?? l.baselineCm), 0);
  const nadir = Math.min(B, P);
  return {
    baseline: round1(B),
    previous: round1(P),
    today: round1(T),
    nadir: round1(nadir),
    pctFromBaseline: B > 0 ? ((T - B) / B) * 100 : 0,
    pctFromNadir: nadir > 0 ? ((T - nadir) / nadir) * 100 : 0,
  };
}

function round1(n: number): number {
  return Math.round(n * 10) / 10;
}

/** PD: a new lesion, or 20 % and 0.5 cm above the smallest so far; CR: all gone; PR: 30 % smaller; else SD. */
export function responseOf(exam: SpOncExam | null | undefined): OncResponse | null {
  if (!exam) {
    return null;
  }
  const sums = lesionSums(exam);
  if (!sums) {
    return exam.newLesionSeen ? 'PROGRESSIVE_DISEASE' : null;
  }
  if (exam.newLesionSeen || (sums.pctFromNadir >= 20 && sums.today - sums.nadir >= 0.5)) {
    return 'PROGRESSIVE_DISEASE';
  }
  if (sums.today === 0) {
    return 'COMPLETE_RESPONSE';
  }
  return sums.pctFromBaseline <= -30 ? 'PARTIAL_RESPONSE' : 'STABLE_DISEASE';
}

export function labFails(labs: SpOncLabs | null | undefined): { key: string; direction: 'BELOW_NEEDED' | 'ABOVE_NEEDED' }[] {
  const out: { key: string; direction: 'BELOW_NEEDED' | 'ABOVE_NEEDED' }[] = [];
  if (!labs) {
    return out;
  }
  for (const row of LAB_ROWS) {
    const v = labs.values[row.key];
    if (v == null) {
      continue;
    }
    if (row.key === 'hb' || row.key === 'anc' || row.key === 'plt' || row.key === 'lvef') {
      const lower = row.min ?? 0;
      if (v < lower) {
        out.push({ key: row.key, direction: 'BELOW_NEEDED' });
      }
    } else if (row.max != null && v > row.max) {
      out.push({ key: row.key, direction: 'ABOVE_NEEDED' });
    }
  }
  return out;
}

/** Labs are fresh when drawn within the 72 hours before now. */
export function labsFresh(drawnAt: string | null | undefined, now: Date): boolean | null {
  if (!drawnAt) {
    return null;
  }
  const ms = now.getTime() - new Date(drawnAt).getTime();
  return ms >= 0 && ms <= 72 * 3_600_000;
}

export function effectiveWorst(tox: SpOncTox | null | undefined): number | null {
  if (!tox) {
    return null;
  }
  const grades = tox.symptoms.map((s) => s.confirmedGrade).filter((g): g is number => g != null);
  return grades.length > 0 ? Math.max(...grades) : null;
}

export interface OncoPreviewInput {
  tox: SpOncTox | null;
  exam: SpOncExam | null;
  labs: SpOncLabs | null;
  ecog: SpOncEcog | null;
  vitals: SpVitalsEntry | null;
  decision: { choice: OncDecisionKey; reasonKey: OncDeviationReason | null } | null;
  server: EmrOncoView | null;
  now: Date;
}

/** The suggestion in order: STOP, DELAY, CHANGE, REDUCE, else as planned. Null until ECOG is chosen. */
export function suggestionOf(input: {
  ecog: number | null;
  countOrganFail: boolean;
  fever: boolean;
  response: OncResponse | null;
  lvefLow: boolean;
  worstGrade: number | null;
  feverOrAdmission: boolean;
}): OncDecisionKey | null {
  if (input.ecog == null) {
    return null;
  }
  if (input.ecog >= 3) {
    return 'STOP';
  }
  if (input.countOrganFail || input.fever) {
    return 'DELAY_1_WEEK';
  }
  if (input.response === 'PROGRESSIVE_DISEASE' || input.lvefLow) {
    return 'CHANGE_REGIMEN';
  }
  if ((input.worstGrade ?? 0) >= 3 || input.feverOrAdmission) {
    return 'GIVE_REDUCED';
  }
  return 'GIVE_AS_PLANNED';
}

/** The local answer shown while entries are unsaved. The server's cycle, cumulative and next-cycle facts are kept. */
export function previewOnco(input: OncoPreviewInput): EmrOncoView {
  const base = input.server;
  const exam = input.exam;
  const { raw, used } = bsa(exam?.heightCm, exam?.weightKg, exam?.capAt2 ?? false);
  const cycle1 = base?.cycle1WeightKg ?? exam?.weightKg ?? null;
  const wc = weightChangePct(exam?.weightKg, cycle1);
  const sums = exam ? lesionSums(exam) : null;
  const fails = labFails(input.labs);
  const countOrganFail = fails.some((f) => f.key !== 'lvef');
  const lvefLow = fails.some((f) => f.key === 'lvef');
  const fever = !!input.vitals?.temperature && input.vitals.temperature.value >= 38;
  const confirmed = (key: ToxKey): number => input.tox?.symptoms.find((s) => s.key === key)?.confirmedGrade ?? 0;
  const events = input.tox?.events ?? [];
  const feverOrAdmission = confirmed('FEVER_LOW_COUNTS') >= 1 || events.includes('FEVER_38_AT_HOME') || events.includes('ADMITTED');
  const worst = effectiveWorst(input.tox);
  const response = responseOf(exam);
  const ecog = input.ecog?.grade ?? null;
  const device = exam?.line.device ?? null;
  const checks = exam?.line.checks ?? [];
  const lineReady = device == null ? null : ['FLUSHES_FREELY', 'BLOOD_RETURN', 'NO_REDNESS_SWELLING'].every((c) => checks.includes(c as never));
  const suggestion = suggestionOf({ ecog, countOrganFail, fever, response, lvefLow, worstGrade: worst, feverOrAdmission });
  const cvs = exam?.systems.cvs;
  const heartOk = !lvefLow && !(cvs?.state === 'ABNORMAL');
  const alerts: OncAlert[] = [];
  if ((worst ?? 0) >= 3) {
    alerts.push('GRADE_3_PLUS');
  }
  if (fails.length > 0) {
    alerts.push('LAB_OUT_OF_RANGE');
  }
  if (fever) {
    alerts.push('FEVER');
  }
  if (wc != null && Math.abs(wc) >= 10) {
    alerts.push('WEIGHT_CHANGE_10');
  }
  if ((base?.cumulativeDoxorubicinMgM2 ?? 0) > 400) {
    alerts.push('CUMULATIVE_OVER_400');
  }
  if (device === 'PORT' && !checks.includes('BLOOD_RETURN')) {
    alerts.push('PORT_NO_BLOOD_RETURN');
  }
  const chosen = input.decision;
  const decision = chosen
    ? {
        choice: chosen.choice,
        reasonKey: chosen.reasonKey,
        suggestionAtDecision: base?.decision?.suggestionAtDecision ?? suggestion ?? chosen.choice,
        stamp: base?.decision?.stamp ?? { at: input.now.toISOString(), byMembershipId: '' },
      }
    : null;
  return {
    bsa: raw,
    bsaUsed: used,
    capped: !!exam?.capAt2 && raw != null && raw > 2,
    cycle1WeightKg: cycle1,
    weightChangePct: wc,
    recalculateDoses: wc != null && Math.abs(wc) >= 10,
    lesionChanges: (exam?.lesions ?? []).map((l) => {
      const pct = lesionPct(l.baselineCm, l.todayCm);
      return { id: l.id, pctFromBaseline: pct, colour: lesionColour(pct) };
    }),
    sums: sums ?? { baseline: 0, previous: 0, today: 0, nadir: 0, pctFromBaseline: 0, pctFromNadir: 0 },
    response,
    labFails: fails,
    labsBadge: fails.length > 0 ? `${fails.length} out of range` : 'Within limits',
    labsFresh: labsFresh(input.labs?.drawnAt, input.now),
    fever,
    feverOrAdmissionEvent: feverOrAdmission,
    worstGrade: worst,
    ecogText: ecog == null ? null : `ECOG ${ecog} · ${ecog <= 2 ? 'fit for chemo' : 'usually not fit for chemo'}`,
    lineReady,
    suggestion,
    reasons: [
      { key: 'ECOG', ok: ecog == null || ecog <= 2, text: ecog == null ? 'ECOG not chosen yet' : `ECOG ${ecog}` },
      { key: 'COUNTS_FEVER', ok: !countOrganFail && !fever, text: countOrganFail || fever ? 'Counts, organ tests or fever need a look' : 'Counts OK, no fever' },
      { key: 'WORST_GRADE', ok: (worst ?? 0) < 3, text: `Worst side effect grade ${worst ?? 0}` },
      { key: 'RESPONSE', ok: response !== 'PROGRESSIVE_DISEASE', text: response ? RESPONSE_LABEL[response] : 'Response not measured' },
      { key: 'HEART', ok: heartOk, text: heartOk ? 'Heart pumping and exam OK' : 'Heart: check LVEF and exam' },
      { key: 'LINE', ok: lineReady !== false, text: lineReady === false ? 'Port: no blood return yet' : 'Line ready' },
    ],
    decision,
    factsChanged: !!decision && !!suggestion && decision.suggestionAtDecision !== suggestion && base?.decision?.suggestionAtDecision !== undefined,
    alerts,
    cumulativeDoxorubicinMgM2: base?.cumulativeDoxorubicinMgM2 ?? null,
    cycleNo: base?.cycleNo ?? null,
    nextCycle: base?.nextCycle ?? null,
    orderStatus: base?.orderStatus ?? 'NONE',
  };
}

/** "Facts changed. Suggested now: <decision>". */
export function factsChangedText(view: Pick<EmrOncoView, 'suggestion' | 'factsChanged'>): string | null {
  return view.factsChanged && view.suggestion ? `Facts changed. Suggested now: ${decisionLabel(view.suggestion)}` : null;
}

/** Deviation: the doctor chose something other than the suggestion; a reason is then required. */
export function needsReason(choice: OncDecisionKey | null, suggestion: OncDecisionKey | null): boolean {
  return !!choice && !!suggestion && choice !== suggestion;
}

// ---- the cycle note (SAH-70-2) ----

export interface CycleNoteInput {
  cycleNo: number | null;
  ecog: number | null;
  worstGrade: number | null;
  weightKg: number | null;
  bsaUsed: number | null;
  labs: SpOncLabs | null;
  labsDrawnText: string | null;
  response: OncResponse | null;
  decision: OncDecisionKey | null;
  reasonLabel: string | null;
  protocolLabel: string | null;
  levelPct: number | null;
  doses: { name: string; finalMg: number | null }[];
}

/**
 * "Cycle note (written for you)": fixed sentences filled only from recorded fields; nothing is
 * invented and a missing fact leaves its sentence out. The doctor edits the text (analyst reading,
 * flagged in ADR-023).
 */
export function composeCycleNote(i: CycleNoteInput): string {
  const lines: string[] = [];
  lines.push(`Cycle ${i.cycleNo ?? '—'}, day 1.`);
  if (i.ecog != null) {
    lines.push(`ECOG ${i.ecog}${i.worstGrade != null ? `, worst side effect grade ${i.worstGrade}` : ''}.`);
  }
  if (i.weightKg != null) {
    lines.push(`Weight ${i.weightKg} kg${i.bsaUsed != null ? `, BSA ${i.bsaUsed.toFixed(2)} m²` : ''}.`);
  }
  if (i.labs) {
    const v = i.labs.values;
    const parts = [
      v.hb != null ? `Hb ${v.hb}` : null,
      v.anc != null ? `ANC ${v.anc}` : null,
      v.plt != null ? `platelets ${v.plt}` : null,
      v.cr != null ? `creatinine ${v.cr}` : null,
      v.bili != null ? `bilirubin ${v.bili}` : null,
      v.alt != null ? `ALT ${v.alt}` : null,
      v.lvef != null ? `LVEF ${v.lvef}%` : null,
    ].filter((p): p is string => p !== null);
    if (parts.length > 0) {
      lines.push(`Labs${i.labsDrawnText ? ` (drawn ${i.labsDrawnText})` : ''}: ${parts.join(', ')}.`);
    }
  }
  if (i.response) {
    lines.push(`Response: ${RESPONSE_LABEL[i.response]}.`);
  }
  if (i.decision) {
    lines.push(`Decision: ${decisionLabel(i.decision)}${i.reasonLabel ? ` (${i.reasonLabel})` : ''}.`);
  }
  if (i.protocolLabel && i.levelPct != null) {
    const doses = i.doses.filter((d) => d.finalMg != null).map((d) => `${d.name} ${d.finalMg} mg`);
    lines.push(`Order: ${i.protocolLabel} at ${i.levelPct}%${doses.length > 0 ? `: ${doses.join(', ')}` : ''}.`);
  }
  return lines.join(' ');
}
