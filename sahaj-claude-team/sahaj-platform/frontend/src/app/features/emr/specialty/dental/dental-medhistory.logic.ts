import { toggleMany } from '../../components/chips.logic';
import {
  DentalAlertKey,
  DentalMedAllergy,
  DentalMedCondition,
  DentalMedMedicine,
  DentalMedicalAlert,
  SpDentalMedHistory,
} from '../specialty.model';

/**
 * "Medical history and alerts" of the Dental screen (SAH-75-6; DoctorDental.dc.html:468-475, 629-663).
 * The lists and the alert words are values from the EMR mockup, not clinically reviewed. The server
 * computes the banners and its answer wins; the same rules here show them as the chips change.
 */

export interface MedChip<K extends string> {
  readonly key: K;
  readonly label: string;
  readonly isNone?: boolean;
}

export const CONDITIONS: readonly MedChip<DentalMedCondition>[] = [
  { key: 'DIABETES', label: 'Diabetes' },
  { key: 'HIGH_BP', label: 'High BP' },
  { key: 'HEART_DISEASE', label: 'Heart disease' },
  { key: 'THYROID', label: 'Thyroid' },
  { key: 'ASTHMA', label: 'Asthma' },
  { key: 'BLEEDING_DISORDER', label: 'Bleeding disorder' },
  { key: 'EPILEPSY', label: 'Epilepsy' },
  { key: 'PREGNANT', label: 'Pregnant' },
  { key: 'NONE', label: 'None', isNone: true },
];

export const MEDICINES: readonly MedChip<DentalMedMedicine>[] = [
  { key: 'BLOOD_THINNERS', label: 'Blood thinners' },
  { key: 'ASPIRIN', label: 'Aspirin' },
  { key: 'BISPHOSPHONATES', label: 'Bisphosphonates' },
  { key: 'STEROIDS', label: 'Steroids' },
  { key: 'THYROID_MEDICINE', label: 'Thyroid medicine' },
  { key: 'NONE', label: 'None', isNone: true },
];

export const ALLERGIES: readonly MedChip<DentalMedAllergy>[] = [
  { key: 'PENICILLIN', label: 'Penicillin' },
  { key: 'LOCAL_ANAESTHETIC', label: 'Local anaesthetic' },
  { key: 'LATEX', label: 'Latex' },
  { key: 'NSAIDS', label: 'Painkillers (NSAIDs)' },
  { key: 'NONE', label: 'None', isNone: true },
];

/** The habit option keys of "Dental history and habits" that raise the tobacco banner. */
export const TOBACCO_HABITS: readonly string[] = ['tobacco_chewing', 'gutkha', 'paan_betel', 'smoking'];

export function blankMedHistory(): SpDentalMedHistory {
  return { component: 'DENTAL_MED_HISTORY', schemaVersion: 1, conditions: [], medicines: [], allergies: [], reviewedWithPatient: false };
}

/** Chip toggle with the "None" rule of the group. */
export function toggleGroup<K extends string>(chips: readonly MedChip<K>[], current: readonly K[], key: K): K[] {
  return toggleMany(chips, current, key) as K[];
}

const ALERT_TEXT: Readonly<Record<DentalAlertKey, { title: string; text: string }>> = {
  PENICILLIN_ALLERGY: { title: 'Penicillin allergy.', text: 'Avoid amoxicillin; use a non-penicillin antibiotic.' },
  BLEEDING_RISK: { title: 'Bleeding risk.', text: 'Check INR or stop advice with the physician before extraction or surgery.' },
  DIABETES: { title: 'Diabetes.', text: 'Check sugar before surgery; healing is slower.' },
  BP_HEART: { title: 'BP / heart.', text: 'Check BP; limit adrenaline in local anaesthetic.' },
  PREGNANT: { title: 'Pregnant.', text: 'Avoid X-rays unless essential; second trimester is safest for treatment.' },
  BISPHOSPHONATES: { title: 'Bisphosphonates.', text: 'Risk of jaw bone problems after extraction or implant.' },
  TOBACCO_USE: { title: 'Tobacco use.', text: 'Screen the mouth for pre-cancer patches and counsel to quit.' },
};

/** The banners in the drawn order, each only when its condition holds. `habits` are the chosen habit keys of the same visit. */
export function medicalAlerts(value: SpDentalMedHistory | null, habits: readonly string[]): DentalMedicalAlert[] {
  const keys: DentalAlertKey[] = [];
  const c = value?.conditions ?? [];
  const m = value?.medicines ?? [];
  const a = value?.allergies ?? [];
  if (a.includes('PENICILLIN')) {
    keys.push('PENICILLIN_ALLERGY');
  }
  if (m.includes('BLOOD_THINNERS') || m.includes('ASPIRIN') || c.includes('BLEEDING_DISORDER')) {
    keys.push('BLEEDING_RISK');
  }
  if (c.includes('DIABETES')) {
    keys.push('DIABETES');
  }
  if (c.includes('HIGH_BP') || c.includes('HEART_DISEASE')) {
    keys.push('BP_HEART');
  }
  if (c.includes('PREGNANT')) {
    keys.push('PREGNANT');
  }
  if (m.includes('BISPHOSPHONATES')) {
    keys.push('BISPHOSPHONATES');
  }
  if (habits.some((h) => TOBACCO_HABITS.includes(h))) {
    keys.push('TOBACCO_USE');
  }
  return keys.map((key) => ({ key, ...ALERT_TEXT[key] }));
}

const labelOf = <K extends string>(chips: readonly MedChip<K>[], key: K): string => chips.find((c) => c.key === key)?.label ?? key;

/**
 * The header tags built from the chosen chips: "Allergy: Penicillin" and "Medical: on thyroid medicine"
 * (conditions by name, medicines as "on <medicine>"). A group with nothing chosen, or only "None", gives none.
 */
export function headerTags(value: SpDentalMedHistory | null): { allergy: string | null; medical: string | null } {
  const allergies = (value?.allergies ?? []).filter((k) => k !== 'NONE').map((k) => labelOf(ALLERGIES, k));
  const conditions = (value?.conditions ?? []).filter((k) => k !== 'NONE').map((k) => labelOf(CONDITIONS, k));
  const medicines = (value?.medicines ?? []).filter((k) => k !== 'NONE').map((k) => `on ${labelOf(MEDICINES, k).toLowerCase()}`);
  const medical = [...conditions, ...medicines];
  return {
    allergy: allergies.length > 0 ? `Allergy: ${allergies.join(', ')}` : null,
    medical: medical.length > 0 ? `Medical: ${medical.join(', ')}` : null,
  };
}

/** The recap of the Assessment part: "<complaints> · pain n/10 · n medical alert(s): <alert names>" (mockup aRecap). */
export function dentalRecap(complaints: readonly string[], painScore: string | null, alerts: readonly DentalMedicalAlert[]): string {
  const base = `${complaints.length > 0 ? complaints.join(', ') : 'no complaints'} · pain ${painScore ?? '–'}/10`;
  return alerts.length > 0 ? `${base} · ${alerts.length} medical alert(s): ${alerts.map((a) => a.title.replace('.', '')).join(', ')}` : base;
}
