import { Type } from '@angular/core';
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
  OUTCOME_SCALES: () => import('./physio/physio-editors').then((m) => m.OutcomeScalesComponent),
};

/** The loader of an editor, or null: the section then uses the generic renderer. */
export function editorLoader(component: string | null | undefined): EditorLoader | null {
  return component && component in SPECIALTY_EDITORS ? SPECIALTY_EDITORS[component as EmrSpecialtyComponent] : null;
}

export interface ProfileChrome {
  /** The words of the finish button; Complete itself arrives with SAH-51. */
  finishLabel: string;
  /** The visit-mode switch ("This visit is" in Obstetrics), if the profile has one. */
  modes?: { label: string; hint: string; options: { key: EmrScreenMode; label: string }[] };
  /** The right-hand "Quick tools" tab ("Helpers for this visit"). */
  quickTools?: boolean;
  /** A card shown after the last Assessment section (the oncology decision). */
  assessmentExtra?: () => Promise<Type<unknown>>;
}

export const PROFILE_CHROME: Record<EmrSpecialtyProfile, ProfileChrome> = {
  gm: { finishLabel: 'Complete visit', quickTools: true },
  dental: { finishLabel: 'Complete & send to billing' },
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
  },
  paeds: { finishLabel: 'Complete visit' },
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
  },
  surgery: { finishLabel: 'Complete visit' },
  onco: {
    finishLabel: 'Complete visit',
    assessmentExtra: () => import('./onco/onc-decision.component').then((m) => m.OncDecisionComponent),
  },
};
