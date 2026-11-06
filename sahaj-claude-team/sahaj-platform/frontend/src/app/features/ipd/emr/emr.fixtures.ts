import {
  DrugOption,
  EmrPatient,
  EmrSheet,
  KeyLabel,
  MedicineOrderRow,
  OrderCheck,
  OrderFinding,
} from './emr.model';

/** Test data in the shape of docs/api/ipd.yaml. Fictional names only. */
export const AS_OF = '2026-11-06T05:47:00Z';
/** 22 characters of base64url, as the server sends. */
export const TOKEN_1 = 'AAAAAAAAAAAAAAAAAAAAAA';
export const TOKEN_2 = 'BBBBBBBBBBBBBBBBBBBBBB';

export const DRUGS: DrugOption[] = [
  { key: 'pcm', name: 'Tab. Paracetamol 650 mg', route: 'Oral', highAlert: null },
  { key: 'amox', name: 'Tab. Amoxicillin + clavulanate 625 mg', route: 'Oral', highAlert: null },
  { key: 'asp', name: 'Tab. Aspirin 75 mg', route: 'Oral', highAlert: null },
  { key: 'hep', name: 'Inj. Heparin 5,000 IU', route: 'SC', highAlert: 'Anticoagulant' },
];

export const FREQUENCIES: KeyLabel[] = [
  { key: 'od', label: 'Once daily' },
  { key: 'bd', label: 'Twice daily' },
  { key: 'tds', label: 'Three times daily' },
  { key: 'q8', label: '8-hourly' },
];

export const OVERRIDE_REASONS: KeyLabel[] = [
  { key: 'BENEFIT_MONITORED', label: 'Benefit outweighs the risk, monitoring in place' },
  { key: 'SPECIALIST_ADVISED', label: 'Specialist advised this combination' },
  {
    key: 'ALLERGY_HISTORY_CHECKED',
    label: 'Allergy history checked with the patient: mild rash only, no anaphylaxis',
  },
  { key: 'CARDIOLOGY_STENT', label: 'Recent coronary stent, cardiology advised' },
];

export const REJECT_REASONS: KeyLabel[] = [
  { key: 'ALLERGY_CONFLICT', label: 'Allergy conflict' },
  { key: 'INTERACTION_RISK', label: 'Interaction risk too high' },
  { key: 'WRONG_DOSE', label: 'Wrong dose or frequency' },
  { key: 'DUPLICATE_THERAPY', label: 'Duplicate therapy' },
];

export const PATIENT: EmrPatient = {
  patientId: 'p1',
  name: 'Asha Patil',
  uhid: 'UH-0001',
  age: 42,
  sex: 'F',
  bedCode: '101-A',
  allergyText: 'Penicillin (rash)',
  allergyStatus: 'RECORDED',
};

export const hardStop = (text: string): OrderFinding => ({
  code: 'ALLERGY_PENICILLIN',
  severity: 'HARD_STOP',
  otherDrugKey: null,
  text,
});

export const warning = (text: string, severity: 'MAJOR' | 'MODERATE' = 'MAJOR'): OrderFinding => ({
  code: 'INTERACTION',
  severity,
  otherDrugKey: 'hep',
  text,
});

export const checkOf = (over: Partial<OrderCheck> = {}): OrderCheck => ({
  allergyStatus: 'RECORDED',
  hardStops: [],
  warnings: [],
  checkToken: TOKEN_1,
  ...over,
});

export const ORDER: MedicineOrderRow = {
  id: 'o1',
  drugKey: 'pcm',
  drugName: 'Tab. Paracetamol 650 mg',
  route: 'Oral',
  highAlert: null,
  frequencyKey: 'od',
  frequencyLabel: 'Once daily',
  status: 'ACTIVE',
  orderedByName: 'Dr. Meera Kulkarni',
  orderedAt: '2026-11-06T04:10:00Z',
  overrideReason: null,
  verifiedByName: 'Rahul Deshmukh',
  verifiedAt: '2026-11-06T04:30:00Z',
  rejectReason: null,
  rejectedByName: null,
  rejectedAt: null,
  stoppedByName: null,
  stoppedAt: null,
  canStop: true,
  lastDose: null,
};

export function sheetOf(over: Partial<EmrSheet> = {}): EmrSheet {
  return {
    admissionId: 'a1',
    status: 'ADMITTED',
    state: 'on',
    asOf: AS_OF,
    patient: PATIENT,
    admittingDoctorName: 'Dr. Meera Kulkarni',
    diagnosis: 'Community acquired pneumonia',
    dayNumber: 3,
    orders: [ORDER],
    canOrder: true,
    orderLockReason: null,
    drugs: DRUGS,
    frequencies: FREQUENCIES,
    overrideReasons: OVERRIDE_REASONS,
    ...over,
  };
}

export const problem = (
  slug: string,
  status: number,
  detail: string,
  fieldErrors?: { field: string; message: string }[],
  extra: Record<string, unknown> = {},
) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
  ...(fieldErrors ? { fieldErrors } : {}),
  ...extra,
});
