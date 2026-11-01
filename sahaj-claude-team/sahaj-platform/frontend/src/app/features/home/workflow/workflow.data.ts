export type StageKey =
  | 'appt'
  | 'checkin'
  | 'vitals'
  | 'consult'
  | 'rx'
  | 'lab'
  | 'counsel'
  | 'billing'
  | 'pharmacy'
  | 'followup';

export interface Stage {
  name: string;
  owner: string;
  rule: string;
}

/** The stages a clinic can add to its workflow (WF in the mockup). */
export const STAGES: Record<StageKey, Stage> = {
  appt: { name: 'Appointment', owner: 'Front desk', rule: 'Booked by phone, WhatsApp or walk-in' },
  checkin: { name: 'Patient check-in', owner: 'Front desk', rule: 'Token by mobile number' },
  vitals: { name: 'Vitals', owner: 'Nurse', rule: 'Required for new patients and children' },
  consult: { name: 'Consultation', owner: 'Doctor', rule: 'Required step, cannot be removed' },
  rx: { name: 'Prescription', owner: 'Doctor', rule: 'Printed with a QR code' },
  lab: { name: 'Lab orders', owner: 'Doctor', rule: 'Added to the bill automatically' },
  counsel: { name: 'Counselling', owner: 'Counsellor', rule: 'Estimate and consent before billing' },
  billing: { name: 'Billing', owner: 'Billing staff', rule: 'Discount above 10% needs approval' },
  pharmacy: { name: 'Pharmacy', owner: 'Pharmacist', rule: 'Medicines dispensed in clinic' },
  followup: { name: 'Follow-up', owner: 'Doctor', rule: 'Free within 6 days, WhatsApp reminder' },
};

export const STAGE_KEYS = Object.keys(STAGES) as StageKey[];

export const INITIAL_WORKFLOW: readonly StageKey[] = [
  'appt',
  'checkin',
  'vitals',
  'consult',
  'rx',
  'billing',
  'followup',
];

export const DEFAULT_CAPTION = 'Hover a stage to see its owner and rule. Use the arrows to reorder.';
