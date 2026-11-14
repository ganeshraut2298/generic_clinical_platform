import { Type } from '@angular/core';
import { TreatmentCourseKind } from '../course/course.model';
import { EmrPart } from '../emr.model';
import { EmrScreenMode, EmrSpecialtyComponent, EmrSpecialtyProfile } from './specialty.model';

/**
 * The specialty registry (ADR-023 D1): the one place that says which Angular component edits which
 * specialty component, and what the screen chrome of each profile is. The record's snapshot decides
 * the profile on the server (starter key, OPD visits only); an unknown component, or a profile the
 * server does not send, falls back to the generic renderer. Adding a specialty later is an entry
 * here plus binders on the server, with no change to the frame. Editors load lazily; no library.
 */

export type EditorLoader = () => Promise<Type<unknown>>;

export const SPECIALTY_EDITORS: Record<EmrSpecialtyComponent, EditorLoader> = {
  VITALS_LOG: () => import('./gm/gm-editors').then((m) => m.VitalsLogComponent),
  EXAM_GENERAL: () => import('./gm/gm-editors').then((m) => m.ExamGeneralComponent),
  TOOTH_CHART: () => import('./dental/tooth-chart.component').then((m) => m.ToothChartComponent),
  DENTAL_PLAN: () => import('./dental/dental-plan.component').then((m) => m.DentalPlanComponent),
  DENTAL_DONE: () => import('./dental/dental-plan.component').then((m) => m.DentalDoneComponent),
  LAB_WORK: () => import('./dental/dental-plan.component').then((m) => m.LabWorkComponent),
  CONSENT_CHECKLIST: () => import('./shared/consent-checklist.component').then((m) => m.ConsentChecklistComponent),
  ANC_CHECK: () => import('./obgyn/obgyn-editors').then((m) => m.AncCheckComponent),
  FORM_F: () => import('./obgyn/obgyn-editors').then((m) => m.FormFComponent),
  GYNAE_EXAM: () => import('./obgyn/obgyn-editors').then((m) => m.GynaeExamComponent),
  GROWTH: () => import('./paeds/paeds-editors').then((m) => m.GrowthComponent),
  IMMUNIZATION: () => import('./paeds/paeds-editors').then((m) => m.ImmunizationComponent),
  MILESTONES: () => import('./paeds/paeds-editors').then((m) => m.MilestonesComponent),
  WEIGHT_DOSE: () => import('./paeds/paeds-editors').then((m) => m.WeightDoseComponent),
  PAIN_SCORE: () => import('./physio/physio-editors').then((m) => m.PainScoreComponent),
  POSTURE_GAIT: () => import('./physio/physio-editors').then((m) => m.PostureGaitComponent),
  ROM_TABLE: () => import('./physio/physio-editors').then((m) => m.RomTableComponent),
  MMT_TABLE: () => import('./physio/physio-editors').then((m) => m.MmtTableComponent),
  SPECIAL_TESTS: () => import('./physio/physio-editors').then((m) => m.SpecialTestsComponent),
  GOALS: () => import('./physio/physio-editors').then((m) => m.GoalsComponent),
  MODALITIES: () => import('./physio/physio-editors').then((m) => m.ModalitiesComponent),
  HOME_EXERCISES: () => import('./physio/physio-editors').then((m) => m.HomeExercisesComponent),
  SESSION_NOTE: () => import('./physio/physio-editors').then((m) => m.SessionNoteComponent),
  SURGICAL_EXAM: () => import('./surgery/surgery-editors').then((m) => m.SurgicalExamComponent),
  SURGERY_FITNESS: () => import('./surgery/surgery-editors').then((m) => m.SurgeryFitnessComponent),
  SURGERY_PLAN: () => import('./surgery/surgery-editors').then((m) => m.SurgeryPlanComponent),
  PREAUTH: () => import('./surgery/surgery-editors').then((m) => m.PreauthComponent),
  PREOP_ORDERS: () => import('./surgery/surgery-editors').then((m) => m.PreopOrdersComponent),
  ONC_TOX: () => import('./onco/onco-assessment').then((m) => m.OncToxComponent),
  ONC_EXAM: () => import('./onco/onco-assessment').then((m) => m.OncExamComponent),
  ONC_LABS: () => import('./onco/onco-assessment').then((m) => m.OncLabsComponent),
  ONC_ECOG: () => import('./onco/onco-assessment').then((m) => m.OncEcogComponent),
  CHEMO_PLAN: () => import('./onco/chemo-plan.component').then((m) => m.ChemoPlanComponent),
  DENTAL_MED_HISTORY: () => import('./dental/dental-medhistory.component').then((m) => m.DentalMedHistoryComponent),
  OUTCOME_SCALES: () => import('./physio/physio-editors').then((m) => m.OutcomeScalesComponent),
};

/** The loader of an editor, or null: the section then uses the generic renderer. */
export function editorLoader(component: string | null | undefined): EditorLoader | null {
  return component && component in SPECIALTY_EDITORS ? SPECIALTY_EDITORS[component as EmrSpecialtyComponent] : null;
}

/** What the "Quick tools" tab of a profile holds (SAH-75-1, ADR-026 D1). */
export type QuickToolsKind = 'gm' | 'paeds' | 'physio' | 'og';

export interface QuickToolsSpec {
  kind: QuickToolsKind;
  /** Sections of the layout whose cards move from the left column into the tab; hidden in the layout means not shown there either. */
  sectionKeys: readonly string[];
}

/**
 * Where the treatment course panel sits on a doctor screen (SAH-75-3). `before`: above the card of that section
 * (the end of the Treatment part when the layout does not show it); `after`: below the card of that section (only
 * while that card is shown); `mode`: only in this visit mode. Dental draws its panel inside the treatment plan.
 */
export interface CoursePanelSpec {
  kind: TreatmentCourseKind;
  before?: string;
  after?: string;
  mode?: EmrScreenMode;
}

/** The words of one part tab; the generic screen keeps the SAH-45 part lines. */
export type PartSubtitles = Readonly<Record<EmrPart, string>>;

export interface ProfileChrome {
  /** The words of the finish button; Complete itself arrives with SAH-51. */
  finishLabel: string;
  /** The visit-mode switch ("This visit is" in Obstetrics), if the profile has one. */
  modes?: { label: string; hint: string; options: { key: EmrScreenMode; label: string }[] };
  /** The right-hand "Quick tools" tab ("Helpers for this visit"). */
  quickTools?: QuickToolsSpec;
  /** A card shown after the last Assessment section (the oncology decision). */
  assessmentExtra?: () => Promise<Type<unknown>>;
  /** A bar shown above the Assessment cards and kept in view while scrolling (the oncology "Assessment steps"). */
  assessmentTop?: () => Promise<Type<unknown>>;
  /** Department accent colour (BR75-10); the screens of other departments keep the app colour. */
  accent?: string;
  /** The left "EMR sections" nav: drawn on General Medicine only among the drawn screens (OQ-75-10). */
  nav: boolean;
  /** The "1 Assessment / 2 Treatment" part tabs; Gynae, Paediatrics and Physiotherapy are one page (OQ-75-9). */
  partTabs: boolean;
  partSubtitles?: PartSubtitles;
  /** Badge word of a part tab: "n of m required" or, in oncology, "n of m done". */
  badgeWord: 'required' | 'done';
  /** Card titles of the design where they differ from the library name (ADR-026 H9). */
  cardTitles?: Readonly<Record<string, string>>;
  /** Cards that carry "1 · ", "2 · " ahead of their title, counted in the order of the layout (oncology, ADR-026 D14). */
  numberedCards?: readonly string[];
  /** The words the Assessment bar uses for a section that is still needed, where they differ from the card title. */
  neededWords?: Readonly<Record<string, string>>;
  /** The treatment course panel of the department, if it has one outside the dental plan. */
  coursePanel?: CoursePanelSpec;
  /** The print button of the footer; the label may depend on the visit mode. */
  printLabel?: (mode: string | null) => string;
}

/** The generic screen: every other starter, a clinic-made department, IPD rounds and Emergency. */
export const GENERIC_CHROME: ProfileChrome = { finishLabel: 'Complete visit', nav: true, partTabs: true, badgeWord: 'required' };

/** BR75-10: the department accent colours of the drawn screens (equal to the starter seed colours). */
export const ACCENT = {
  gm: '#1D5FA8',
  dental: '#0B6E69',
  og: '#A3366B',
  paeds: '#B4471F',
  physio: '#6D3FC0',
  surgery: '#1E40AF',
  onco: '#9D174D',
} as const;

const GM_SUBTITLES: PartSubtitles = { ASSESSMENT: 'Complaints and examination', TREATMENT: 'Diagnosis, medicines, tests and services' };

export const PROFILE_CHROME: Record<EmrSpecialtyProfile, ProfileChrome> = {
  gm: {
    finishLabel: 'Complete visit',
    quickTools: { kind: 'gm', sectionKeys: [] },
    accent: ACCENT.gm,
    nav: true,
    partTabs: true,
    partSubtitles: GM_SUBTITLES,
    badgeWord: 'required',
    cardTitles: { vitals: 'General examination', exam: 'Systemic examination', local_exam: 'Local examination · body map' },
  },
  dental: {
    finishLabel: 'Complete & send to billing',
    accent: ACCENT.dental,
    nav: false,
    partTabs: true,
    partSubtitles: { ASSESSMENT: 'Complaints, history, examination, X-rays', TREATMENT: 'Diagnosis, chart, plan, sittings and payments' },
    badgeWord: 'required',
    printLabel: () => 'Print estimate',
    neededWords: { dental_medhx: 'medical history review' },
  },
  og: {
    finishLabel: 'Complete visit',
    modes: {
      label: 'This visit is',
      hint: 'The screen shows only the sections for the visit type you pick.',
      options: [
        { key: 'ANC', label: 'Antenatal (ANC)' },
        { key: 'GYNAE', label: 'Gynae (not pregnant)' },
      ],
    },
    quickTools: { kind: 'og', sectionKeys: [] },
    coursePanel: { kind: 'ANC', after: 'anc', mode: 'ANC' },
    accent: ACCENT.og,
    nav: false,
    partTabs: false,
    badgeWord: 'required',
    printLabel: (mode) => (mode === 'GYNAE' ? 'Print Rx' : 'Print ANC card'),
  },
  paeds: {
    finishLabel: 'Complete visit',
    quickTools: { kind: 'paeds', sectionKeys: ['wtdose', 'feeding'] },
    accent: ACCENT.paeds,
    nav: false,
    partTabs: false,
    badgeWord: 'required',
    printLabel: () => 'Print vaccination card',
  },
  physio: {
    finishLabel: 'Complete session & book next',
    modes: {
      label: 'Visit',
      hint: '',
      options: [
        { key: 'FIRST_VISIT', label: 'First visit: assessment' },
        { key: 'SESSION', label: 'Session notes' },
      ],
    },
    quickTools: { kind: 'physio', sectionKeys: ['hep', 'goals'] },
    coursePanel: { kind: 'REHAB', before: 'advice' },
    accent: ACCENT.physio,
    nav: false,
    partTabs: false,
    badgeWord: 'required',
    printLabel: () => 'Progress report',
  },
  surgery: {
    finishLabel: 'Complete visit',
    accent: ACCENT.surgery,
    nav: false,
    partTabs: true,
    partSubtitles: { ASSESSMENT: 'Complaints, examination, body map, fitness', TREATMENT: 'Diagnosis, surgery plan, consent, episode, payments' },
    badgeWord: 'required',
    cardTitles: { local_exam: 'Local examination · body map' },
    coursePanel: { kind: 'SURGERY', before: 'advice' },
  },
  onco: {
    finishLabel: 'Complete visit',
    assessmentExtra: () => import('./onco/onc-decision.component').then((m) => m.OncDecisionComponent),
    assessmentTop: () => import('./onco/onc-steps.component').then((m) => m.OncStepsComponent),
    accent: ACCENT.onco,
    nav: false,
    partTabs: true,
    partSubtitles: { ASSESSMENT: 'Side effects · examination · labs · decision', TREATMENT: 'Order sheet · safety checks · take-home · next cycle' },
    badgeWord: 'done',
    cardTitles: {
      complaints: 'Other complaints (optional)',
      onc_tox: 'Side effects since last cycle',
      onc_bsa: 'Examination',
      onc_labs: 'Pre-chemo labs',
      onc_ecog: 'Performance status (ECOG)',
    },
    numberedCards: ['onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog'],
    coursePanel: { kind: 'CHEMO', before: 'referral' },
  },
};

/** The chrome of a record's profile, or the generic one. */
export function chromeOf(profile: EmrSpecialtyProfile | null): ProfileChrome {
  return profile ? PROFILE_CHROME[profile] : GENERIC_CHROME;
}

/**
 * The colour variables of a department accent (BR75-10): the primary colour of the EMR page and its panels
 * (the part tab, buttons, chips, the case paper bar) follow the department; null keeps the app colour.
 */
export function accentVars(accent: string | undefined): Record<string, string> | null {
  return accent
    ? {
        '--pri': accent,
        '--pri-ink': '#ffffff',
        '--pri-soft': `color-mix(in srgb, ${accent} 12%, transparent)`,
        '--pri-text': `color-mix(in srgb, ${accent} 85%, black)`,
        '--paper-accent': accent,
      }
    : null;
}
