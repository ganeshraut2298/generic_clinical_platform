import { newClientKey } from '../../../shared/client-key';
import { EmrLayoutSection } from '../emr.model';
import {
  EmrFlag,
  EmrOncoView,
  EmrSpecialtyComponent,
  EmrSpecialtyProfile,
  EmrSpecialtyValue,
  EmrSpecialtyView,
  EmrStillNeededItem,
  SpOncLabs,
  SpOncTox,
  SpSurgeryFitness,
  SpVitalsEntry,
} from './specialty.model';
import { fitnessSummary } from './surgery.logic';

/**
 * Pure rules shared by every specialty editor (ADR-023 D1 to D3): which component a section uses,
 * when a component counts as filled and as Done, what is sent to the server, and the footer items.
 * The server computes the same on every read and save and its answer wins; these functions let the
 * screen answer while the doctor is typing. Numbers below are "value from the EMR mockup, accepted
 * by the human in DEC-039, not clinically reviewed" and carry the story that drew them.
 */

export type ValuesBySection = Record<string, Record<string, unknown>>;

export const SPECIALTY_FIELD = 'specialty';

export function newId(): string {
  return newClientKey();
}

export function isSpecialtyValue(value: unknown): value is EmrSpecialtyValue {
  return typeof value === 'object' && value !== null && typeof (value as { component?: unknown }).component === 'string';
}

/** The specialty value of a section's values, when it has one. */
export function specialtyOf(values: Record<string, unknown> | undefined): EmrSpecialtyValue | null {
  const raw = values?.[SPECIALTY_FIELD];
  return isSpecialtyValue(raw) ? raw : null;
}

export function profileOfLayout(layout: readonly EmrLayoutSection[]): EmrSpecialtyProfile | null {
  return layout.find((s) => s.specialtyEditor)?.specialtyEditor?.profile ?? null;
}

export function editorOfSection(section: Pick<EmrLayoutSection, 'specialtyEditor'>): EmrSpecialtyComponent | null {
  return section.specialtyEditor?.component ?? null;
}

// ---- empty and sanitized values ----

const STORES_NOTHING: ReadonlySet<string> = new Set(['DENTAL_DONE', 'WEIGHT_DOSE']);

/** Nothing worth storing: the server keeps nothing for a value like this. */
export function isSpecialtyEmpty(value: EmrSpecialtyValue): boolean {
  switch (value.component) {
    case 'DENTAL_DONE':
    case 'WEIGHT_DOSE':
      return true;
    case 'VITALS_LOG':
      return value.entries.length === 0;
    case 'EXAM_GENERAL':
      return Object.values(value.signs).every((s) => !s) && Object.values(value.systems).every((s) => !s);
    case 'TOOTH_CHART':
      return value.teeth.length === 0 && !value.allHealthyConfirmed && value.shade === 'A2' && value.gum === 'PINK';
    case 'DENTAL_PLAN':
      return value.rows.length === 0;
    case 'LAB_WORK':
    case 'CONSENT_CHECKLIST':
      return value.items.length === 0;
    case 'ANC_CHECK':
      return (
        Object.values(value.today).every((v) => v === null || v === undefined) &&
        value.symptoms.length === 0 &&
        value.orders.length === 0 &&
        (value.supplements ?? []).length === 0 &&
        value.scans.every((s) => !s.done) &&
        !value.datingScanEdd &&
        !value.nextAncWeeks &&
        !value.planNote
      );
    case 'FORM_F':
      return !value.filledAndSigned;
    case 'GYNAE_EXAM':
      return !value.perAbdomen && !value.perSpeculum && !value.perVaginum && !value.chaperoneName && !value.chaperoneDeclined;
    case 'GROWTH':
      return value.weightKg == null && value.lengthCm == null && value.headCircCm == null;
    case 'IMMUNIZATION':
      return value.givenToday.length === 0 && !value.scheduleReviewed;
    case 'MILESTONES':
      return value.achieved.length === 0 && value.notYet.length === 0;
    case 'PAIN_SCORE':
      return value.score == null && !value.site;
    case 'POSTURE_GAIT':
      return value.gait == null;
    case 'ROM_TABLE':
      return value.rows.every((r) => r.rightDeg == null && r.leftDeg == null);
    case 'MMT_TABLE':
      return value.rows.every((r) => r.right == null && r.left == null);
    case 'SPECIAL_TESTS':
      return value.results.every((r) => r.result === 'NOT_DONE');
    case 'GOALS':
      return value.goals.length === 0;
    case 'MODALITIES':
      return value.given.length === 0;
    case 'HOME_EXERCISES':
      return value.items.length === 0;
    case 'SESSION_NOTE':
      return !value.note?.trim();
    case 'SURGICAL_EXAM':
      return (
        value.abdomen.length === 0 && !value.murphySign && !value.jaundice && value.herniaSites.length === 0 && !value.perRectal
      );
    case 'SURGERY_FITNESS':
      return !value.asa && value.otherIllness.length === 0 && !value.bloodThinner && value.pac === 'NOT_DONE' && value.tests.length === 0;
    case 'SURGERY_PLAN':
      return !value.procedureKey && !value.procedureText && !value.otDate && !value.slotText && !value.booking && value.payer === 'SELF';
    case 'PREAUTH':
      return !value.sent && !value.reference;
    case 'PREOP_ORDERS':
      return value.ticked.length === 0 && value.extraLines.length === 0 && !value.blood;
    case 'ONC_TOX':
      return value.symptoms.length === 0 && value.events.length === 0;
    case 'ONC_EXAM':
      return (
        value.weightKg == null &&
        value.heightCm == null &&
        value.lesions.length === 0 &&
        value.breastSkin.length === 0 &&
        !value.newLesionSeen &&
        Object.values(value.signs).every((s) => !s) &&
        Object.values(value.systems).every((s) => !s) &&
        !value.line.device
      );
    case 'ONC_LABS':
      return !value.drawnAt && Object.values(value.values).every((v) => v == null);
    case 'ONC_ECOG':
      return value.grade == null;
    case 'CHEMO_PLAN':
      return value.takeHome.length === 0 && value.referrals.length === 0 && !value.nextCycleDate && !value.imagingText && !value.cycleNote;
    case 'OUTCOME_SCALES':
      return value.rows.every((r) => r.score == null);
  }
}

/** The body the server accepts: read-only and server-stamped parts are left out (the bodies are closed). */
export function sanitizeSpecialty(value: EmrSpecialtyValue): EmrSpecialtyValue {
  const copy = JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
  const drop = (obj: unknown, keys: string[]): void => {
    if (obj && typeof obj === 'object') {
      for (const k of keys) {
        delete (obj as Record<string, unknown>)[k];
      }
    }
  };
  switch (value.component) {
    case 'VITALS_LOG':
      (copy['entries'] as unknown[]).forEach((e) => drop(e, ['how', 'recorded']));
      break;
    case 'DENTAL_PLAN':
      drop(copy, ['estimate', 'courseId']);
      (copy['rows'] as unknown[]).forEach((r) =>
        drop(r, ['serviceId', 'estimatePaise', 'estimateSource', 'sittingSharesPaise', 'doneOn']),
      );
      break;
    case 'CONSENT_CHECKLIST':
      (copy['items'] as unknown[]).forEach((i) => drop(i, ['confirmedStamp']));
      break;
    case 'ANC_CHECK':
      drop(copy, ['pregnancyCourseId']);
      (copy['orders'] as unknown[]).forEach((o) => drop(o, ['ordered']));
      break;
    case 'FORM_F':
      drop(copy, ['marked', 'scanKey', 'scanRemoved']);
      break;
    case 'GROWTH':
      drop(copy, ['previous']);
      break;
    case 'IMMUNIZATION':
      (copy['givenToday'] as unknown[]).forEach((g) => drop(g, ['given']));
      break;
    case 'GOALS':
      (copy['goals'] as unknown[]).forEach((g) => drop(g, ['achievedOn']));
      break;
    case 'SURGERY_PLAN':
      drop(copy, ['packagePaise', 'packageSource', 'booking']);
      break;
    case 'ONC_EXAM':
      (copy['lesions'] as unknown[]).forEach((l) => drop(l, ['lastCm']));
      break;
    case 'ONC_LABS':
      drop(copy, ['source']);
      break;
    default:
      break;
  }
  if (STORES_NOTHING.has(value.component)) {
    return { component: value.component, schemaVersion: 1 } as EmrSpecialtyValue;
  }
  return copy as unknown as EmrSpecialtyValue;
}

// ---- Done (ADR-023 D3) ----

export interface DoneContext {
  readonly values: Readonly<ValuesBySection>;
  readonly view: EmrSpecialtyView | null;
  readonly today: string;
  /** The oncology order status the screen holds ("SENT" or "HELD" makes the plan section Done). */
  readonly orderStatus: 'NONE' | 'DRAFT' | 'SENT' | 'HELD' | null;
  readonly sectionDirty: (key: string) => boolean;
}

const FITNESS_TEST_KEYS = ['CBC', 'LFT', 'KFT', 'PT_INR', 'BLOOD_SUGAR', 'ECG', 'CHEST_XRAY', 'HIV', 'HBSAG', 'HCV', 'BLOOD_GROUP'] as const;

function marked(value: unknown): boolean {
  return value !== null && value !== undefined;
}

export const TOX_KEYS = [
  'NAUSEA',
  'VOMITING',
  'MOUTH_SORES',
  'LOOSE_MOTIONS',
  'TINGLING_NUMBNESS',
  'FATIGUE',
  'FEVER_LOW_COUNTS',
  'PAIN',
  'LOSS_OF_APPETITE',
] as const;

export function confirmedGradeCount(value: SpOncTox | null | undefined): number {
  return value ? value.symptoms.filter((s) => s.confirmedGrade != null).length : 0;
}

export const LAB_KEYS = ['hb', 'anc', 'plt', 'cr', 'bili', 'alt', 'lvef'] as const;

export function labsComplete(value: SpOncLabs | null | undefined): boolean {
  return !!value && !!value.drawnAt && LAB_KEYS.every((k) => value.values[k] != null);
}

/** Whether a section's specialty value counts as Done (the server's answer wins when nothing is unsaved). */
export function isSpecialtyDone(
  component: EmrSpecialtyComponent,
  value: EmrSpecialtyValue | null,
  ctx: DoneContext,
  sectionKey: string,
): boolean {
  if (component === 'DENTAL_DONE') {
    return doneTodayRows(ctx).length > 0;
  }
  if (component === 'CHEMO_PLAN') {
    return ctx.orderStatus === 'SENT' || ctx.orderStatus === 'HELD';
  }
  if (component === 'SURGERY_PLAN') {
    return !ctx.sectionDirty(sectionKey) && ctx.view?.surgery?.bookState === 'BOOKED';
  }
  if (component === 'FORM_F') {
    return !formFRequired(ctx) || (value?.component === 'FORM_F' && value.filledAndSigned);
  }
  if (!value) {
    return false;
  }
  switch (value.component) {
    case 'VITALS_LOG':
      return value.entries.length > 0;
    case 'EXAM_GENERAL': {
      const any = Object.values(value.signs).some(marked) || Object.values(value.systems).some(marked);
      const abnormalOk = Object.values(value.systems).every((s) => !s || s.state === 'NORMAL' || !!s.text?.trim());
      return any && abnormalOk;
    }
    case 'TOOTH_CHART':
      return value.allHealthyConfirmed || value.teeth.some((t) => t.condition !== 'HEALTHY');
    case 'DENTAL_PLAN':
      return value.rows.length > 0;
    case 'LAB_WORK':
      return value.items.length > 0;
    case 'CONSENT_CHECKLIST':
      return value.items.length > 0 && value.items.every((i) => i.confirmed);
    case 'ANC_CHECK':
      return Object.values(value.today).some(marked);
    case 'GYNAE_EXAM':
      return (
        !!(value.perAbdomen?.trim() || value.perSpeculum?.trim() || value.perVaginum?.trim()) &&
        !!(value.chaperoneName?.trim() || value.chaperoneDeclined)
      );
    case 'GROWTH':
      return value.weightKg != null;
    case 'IMMUNIZATION':
      return value.scheduleReviewed || value.givenToday.length > 0;
    case 'MILESTONES':
      return value.achieved.length + value.notYet.length > 0;
    case 'PAIN_SCORE':
      return value.score != null;
    case 'POSTURE_GAIT':
      return value.gait != null;
    case 'ROM_TABLE':
      return value.rows.some((r) => r.rightDeg != null || r.leftDeg != null);
    case 'MMT_TABLE':
      return value.rows.some((r) => r.right != null || r.left != null);
    case 'SPECIAL_TESTS':
      return value.results.some((r) => r.result !== 'NOT_DONE');
    case 'GOALS':
      return value.goals.length > 0;
    case 'MODALITIES':
      return value.given.length > 0;
    case 'HOME_EXERCISES':
      return value.items.length > 0;
    case 'SESSION_NOTE':
      return !!value.note?.trim();
    case 'SURGICAL_EXAM':
      return !isSpecialtyEmpty(value);
    case 'SURGERY_FITNESS':
      return fitnessSummary(value).state === 'FIT';
    case 'PREAUTH':
      return value.sent;
    case 'PREOP_ORDERS':
      return value.ticked.length > 0;
    case 'ONC_TOX':
      return confirmedGradeCount(value) === TOX_KEYS.length;
    case 'ONC_EXAM':
      return (
        value.weightKg != null &&
        value.heightCm != null &&
        (['cvs', 'rs', 'pa', 'cns', 'bone'] as const).every((k) => marked(value.systems[k]))
      );
    case 'ONC_LABS':
      return labsComplete(value);
    case 'ONC_ECOG':
      return value.grade != null;
    case 'OUTCOME_SCALES':
      return value.rows.some((r) => r.score != null);
    default:
      return false;
  }
}

/** Plan rows done on this record's date: newly set on screen (no stamp yet) or stamped today. */
export function doneTodayRows(ctx: Pick<DoneContext, 'values' | 'today'>): { id: string; tooth: number; procedureText: string }[] {
  const out: { id: string; tooth: number; procedureText: string }[] = [];
  for (const section of Object.values(ctx.values)) {
    const v = specialtyOf(section);
    if (v?.component === 'DENTAL_PLAN') {
      for (const r of v.rows) {
        if (r.status === 'DONE' && (!r.doneOn || r.doneOn === ctx.today)) {
          out.push({ id: r.id, tooth: r.tooth, procedureText: r.procedureText });
        }
      }
    }
  }
  return out;
}

/** Form F is needed while a growth scan order is on the ANC check and the form is not marked (H10). */
export function formFRequired(ctx: Pick<DoneContext, 'values' | 'view'>): boolean {
  for (const section of Object.values(ctx.values)) {
    const v = specialtyOf(section);
    if (v?.component === 'ANC_CHECK' && v.orders.some((o) => o.key === 'GROWTH_SCAN_FORM_F')) {
      return true;
    }
  }
  return ctx.view?.og?.formFRequired ?? false;
}

// ---- footer (ADR-023 D3 "Footer items") ----

export interface FooterInput {
  readonly profile: EmrSpecialtyProfile | null;
  readonly mode: string | null;
  readonly layout: readonly EmrLayoutSection[];
  readonly doneByKey: Readonly<Record<string, boolean>>;
  readonly values: Readonly<ValuesBySection>;
  readonly decisionRecorded: boolean;
  readonly holdMode: boolean;
}

const PROFILE_LABELS: Record<string, string> = {
  sx_plan: 'OT booking',
  onc_bsa: 'systemic exam',
};

/**
 * The footer list "Still needed: …" in order. Required sections that are not Done, the profile's own
 * words ("OT booking", "systemic exam", "n side effect grade(s)", "send chemo order" or "record the
 * hold"), and the screen items: oncology "today's decision" and physio "Select at least one
 * treatment given today." In oncology the grades, the systemic exam, the decision and the order
 * are listed whenever the section is shown, whatever its Required setting (ADR-023 H2).
 */
export function footerItems(input: FooterInput): EmrStillNeededItem[] {
  const items: EmrStillNeededItem[] = [];
  const onco = input.profile === 'onco';
  const alwaysListed = new Set(onco ? ['onc_tox', 'onc_bsa', 'onc_regimen'] : []);
  for (const section of input.layout) {
    if (!(section.required || alwaysListed.has(section.key)) || input.doneByKey[section.key]) {
      continue;
    }
    let label = PROFILE_LABELS[section.key] ?? section.name.toLowerCase();
    let count: number | null = null;
    if (section.key === 'onc_tox') {
      const v = specialtyOf(input.values['onc_tox']);
      count = TOX_KEYS.length - (v?.component === 'ONC_TOX' ? confirmedGradeCount(v) : 0);
      label = `${count} side effect grade(s)`;
    } else if (section.key === 'onc_regimen') {
      label = input.holdMode ? 'record the hold' : 'send chemo order';
    }
    items.push({ key: section.key, sectionKey: section.key, label, count });
  }
  if (onco && !input.decisionRecorded && input.layout.some((s) => s.key === 'onc_tox' || s.key === 'onc_labs')) {
    items.splice(Math.min(items.length, 3), 0, { key: 'ONC_DECISION', sectionKey: null, label: "today's decision", count: null });
  }
  if (input.profile === 'physio' && input.mode === 'SESSION') {
    const modalities = specialtyOf(input.values['modalities']);
    if (!(modalities?.component === 'MODALITIES' && modalities.given.length > 0)) {
      items.push({ key: 'PHYSIO_TREATMENT_TODAY', sectionKey: 'modalities', label: 'Select at least one treatment given today.', count: null });
    }
  }
  return items;
}

/** "Still needed: a, b." or, for the physio screen item, its own sentence (mockup footText). */
export function footerSentence(items: readonly EmrStillNeededItem[]): string {
  if (items.length === 0) {
    return 'Ready to complete.';
  }
  const own = items.find((i) => i.key === 'PHYSIO_TREATMENT_TODAY');
  const rest = items.filter((i) => i !== own);
  if (rest.length === 0 && own) {
    return own.label;
  }
  const text = `Still needed: ${rest.map((i) => i.label).join(', ')}.`;
  return own ? `${text} ${own.label}` : text;
}

// ---- vitals (GM C-1, Oncology C-1 and C-8) ----

/** Temperature limits are in °F in General Medicine and in °C in Oncology (ADR-023 H19). */
export function vitalsFlags(entry: SpVitalsEntry | null | undefined, profile: EmrSpecialtyProfile | null): EmrFlag[] {
  if (!entry) {
    return [];
  }
  const out: EmrFlag[] = [];
  const add = (field: string, label: string, alert: boolean): void => {
    out.push({ field, label, alert });
  };
  const sys = entry.bpSystolic;
  const dia = entry.bpDiastolic;
  if (profile === 'onco') {
    if (sys != null && (sys >= 160 || sys < 90)) {
      add('bp', 'Check again', true);
    } else if (dia != null && dia >= 100) {
      add('bp', 'High', true);
    }
    if (entry.pulse != null && entry.pulse > 100) {
      add('pulse', 'Fast · check for fever, anaemia, heart', true);
    }
    if (entry.temperature && entry.temperature.value >= 38) {
      add('temperature', 'Fever · rule out infection before chemo', true);
    }
    if (entry.spo2 != null && entry.spo2 < 94) {
      add('spo2', 'Low · examine lungs', true);
    }
    return out;
  }
  if ((sys != null && sys >= 140) || (dia != null && dia >= 90)) {
    add('bp', 'High', true);
  } else if (sys != null && sys < 90) {
    add('bp', 'Low', false);
  }
  if (entry.pulse != null) {
    if (entry.pulse > 100) {
      add('pulse', 'High', true);
    } else if (entry.pulse < 60) {
      add('pulse', 'Low', false);
    }
  }
  if (entry.spo2 != null && entry.spo2 < 95) {
    add('spo2', 'Low', false);
  }
  if (entry.temperature) {
    const t = entry.temperature.value;
    if (t >= 99.5) {
      add('temperature', 'Fever', true);
    } else if (t < 96) {
      add('temperature', 'Low', false);
    }
  }
  if (entry.respRate != null && entry.respRate > 20) {
    add('respRate', 'High', true);
  }
  return out;
}

/** The oncology view block the screen shows: the server's when nothing is unsaved, else the local preview. */
export function pickOnco(server: EmrOncoView | null | undefined, preview: EmrOncoView | null, fresh: boolean): EmrOncoView | null {
  return fresh && server ? server : (preview ?? server ?? null);
}

export function fitnessOf(value: SpSurgeryFitness | null | undefined): ReturnType<typeof fitnessSummary> {
  return fitnessSummary(
    value ?? { component: 'SURGERY_FITNESS', schemaVersion: 1, otherIllness: [], pac: 'NOT_DONE', tests: [] },
  );
}

export { FITNESS_TEST_KEYS };

// ---- visit mode (SAH-61-1, SAH-63-1; BR45-6) ----

const ANC_ONLY = ['obstetric', 'anc', 'pcpndt', 'usg'];
const GYNAE_ONLY = ['gyn_exam', 'infertility', 'contraception'];
const FIRST_VISIT_HIDES = ['modalities', 'sessions'];
const SESSION_HIDES = ['special_tests', 'posture'];

/**
 * "The screen shows only the sections for the visit type you pick": sections the mode hides (they keep
 * their values). The server's list (`EmrSpecialtyView.hiddenSectionKeys`) is the one used; this table is
 * its fallback. Gynae keeps "pcpndt" while Form F is required (H21: a legal duty, ADR-023 D13.3).
 */
export function hiddenSectionKeys(profile: EmrSpecialtyProfile | null, mode: string | null, formFRequired = false): ReadonlySet<string> {
  if (profile === 'og') {
    return new Set(mode === 'GYNAE' ? ANC_ONLY.filter((k) => !(k === 'pcpndt' && formFRequired)) : GYNAE_ONLY);
  }
  if (profile === 'physio') {
    return new Set(mode === 'SESSION' ? SESSION_HIDES : FIRST_VISIT_HIDES);
  }
  return new Set();
}
