import {
  DrugOption,
  EmrPatient,
  EmrSheet,
  KeyLabel,
  MedicineOrderRow,
  OrderCheck,
  OrderFinding,
} from './emr.model';
import type { OrderDetails, OrderExecution } from '../orders/order.model';

/** Test data in the shape of docs/api/ipd.yaml. Fictional names only. */
export const AS_OF = '2026-11-06T05:47:00Z';
/** 22 characters of base64url, as the server sends. */
export const TOKEN_1 = 'AAAAAAAAAAAAAAAAAAAAAA';
export const TOKEN_2 = 'BBBBBBBBBBBBBBBBBBBBBB';

export const DRUGS: DrugOption[] = [
  { key: 'pcm', name: 'Tab. Paracetamol 650 mg', route: 'Oral', highAlert: null, routeKey: 'ORAL', doseUnits: ['TABLET', 'MG'], timeCritical: false },
  { key: 'amox', name: 'Tab. Amoxicillin + clavulanate 625 mg', route: 'Oral', highAlert: null, routeKey: 'ORAL', doseUnits: ['TABLET'], timeCritical: false },
  { key: 'asp', name: 'Tab. Aspirin 75 mg', route: 'Oral', highAlert: null, routeKey: 'ORAL', doseUnits: ['TABLET'], timeCritical: false },
  { key: 'hep', name: 'Inj. Heparin 5,000 IU', route: 'SC', highAlert: 'Anticoagulant', routeKey: 'SC', doseUnits: ['IU', 'ML'], timeCritical: false },
];

export const FREQUENCIES: KeyLabel[] = [
  { key: 'od', label: 'Once daily' },
  { key: 'bd', label: 'Twice daily' },
  { key: 'tds', label: 'Three times daily' },
  { key: 'q8', label: '8-hourly' },
  { key: 'prn', label: 'As needed (PRN)' },
];

export const ROUTES: KeyLabel[] = [
  { key: 'ORAL', label: 'Oral' },
  { key: 'IV', label: 'IV' },
  { key: 'IV_INFUSION', label: 'IV infusion' },
  { key: 'IM', label: 'IM' },
  { key: 'SC', label: 'SC' },
];

export const DOSE_UNITS: KeyLabel[] = [
  { key: 'TABLET', label: 'tablet' },
  { key: 'MG', label: 'mg' },
  { key: 'ML', label: 'ml' },
  { key: 'IU', label: 'IU' },
];

export const PRIORITIES: KeyLabel[] = [
  { key: 'ROUTINE', label: 'Routine' },
  { key: 'URGENT', label: 'Urgent' },
  { key: 'STAT', label: 'STAT' },
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

export const DETAILS: OrderDetails = {
  kind: 'SCHEDULED',
  doseAmount: 1,
  doseUnit: 'TABLET',
  routeKey: 'ORAL',
  routeLabel: 'Oral',
  frequencyKey: 'od',
  frequencyLabel: 'Once daily',
  startAt: '2026-11-06T04:30:00Z',
  endAt: null,
  durationDays: null,
  indication: null,
  priority: 'ROUTINE',
  prnMinIntervalMinutes: null,
  prnMaxPer24h: null,
  pharmacistChecked: true,
};

export const EXECUTION: OrderExecution = {
  notGivenToday: 0,
  nextDueAt: null,
  lateCount: 0,
  acknowledged: false,
  acknowledgedByName: null,
  acknowledgedAt: null,
};

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
  orderVersion: 1,
  details: DETAILS,
  changes: [],
  changeReason: null,
  hold: null,
  stopReason: null,
  query: null,
  canChange: true,
  canHold: true,
  canResume: false,
  canReply: false,
  execution: EXECUTION,
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
    routes: ROUTES,
    doseUnits: DOSE_UNITS,
    priorities: PRIORITIES,
    statWindowMinutes: 15,
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
