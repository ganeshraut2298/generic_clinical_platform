import { KeyLabel } from '../emr/emr.model';
import { PATIENT } from '../emr/emr.fixtures';
import { OrderDetails } from '../orders/order.model';
import { Mar, MarDoseRow, MarOrder, MarSlot, PrnLine, SecondNurseOption } from './mar.model';

/** Test data in the shape of docs/api/ipd.yaml 0.4.0 (SAH-32, SAH-76). Fictional names only. */

/** 11:05 AM IST on 6 Nov 2026. */
export const MAR_AS_OF = '2026-11-06T05:35:00Z';
/** The dose times of 6 Nov 2026 in IST as instants. */
export const AT_8AM = '2026-11-06T02:30:00Z';
export const AT_2PM = '2026-11-06T08:30:00Z';
export const AT_8PM = '2026-11-06T14:30:00Z';

export const SECOND_NURSES: SecondNurseOption[] = [
  { membershipId: 'm-rupali', name: 'Sr. Rupali More' },
  { membershipId: 'm-nikhil', name: 'Br. Nikhil Jadhav' },
];

export const NOT_GIVEN_REASONS: KeyLabel[] = [
  { key: 'PATIENT_REFUSED', label: 'Patient refused' },
  { key: 'HELD_DOCTOR', label: "Held on doctor's advice" },
  { key: 'NIL_BY_MOUTH', label: 'Nil by mouth' },
  { key: 'NOT_AVAILABLE', label: 'Medicine not available' },
  { key: 'AWAY_FROM_WARD', label: 'Patient away from the ward' },
  { key: 'VOMITED', label: 'Vomited' },
  { key: 'OTHER', label: 'Other' },
];

export const OVERRIDE_REASONS: KeyLabel[] = [
  { key: 'NO_BARCODE', label: 'Medicine has no barcode' },
  { key: 'SCANNER_DOWN', label: 'Scanner not working' },
  { key: 'EMERGENCY', label: 'Emergency' },
];

export const IN_ERROR_REASONS: KeyLabel[] = [
  { key: 'NOT_GIVEN_ACTUALLY', label: 'Dose was not actually given' },
  { key: 'WRONG_PATIENT', label: 'Documented on the wrong patient' },
  { key: 'WRONG_TIME', label: 'Wrong time recorded' },
  { key: 'DUPLICATE', label: 'Duplicate entry' },
];

export const doseOf = (over: Partial<MarDoseRow> = {}): MarDoseRow => ({
  id: 'd1',
  slotAt: AT_8AM,
  outcome: 'GIVEN',
  reason: null,
  recordedAt: '2026-11-06T02:34:00Z',
  recordedByName: 'Sr. Anjali Pawar',
  secondCheckByName: 'Sr. Rupali More',
  orderVersion: 1,
  method: 'SCAN',
  overrideReason: null,
  beforeVerification: false,
  correction: null,
  undoSecondsLeft: 0,
  canUndo: false,
  canMarkInError: false,
  ...over,
});

export const GIVEN_DOSE: MarDoseRow = doseOf();

export const slotOf = (over: Partial<MarSlot> = {}): MarSlot => ({
  slotAt: AT_8AM,
  state: 'DUE',
  lateHours: null,
  lateMinutes: null,
  canGive: true,
  orderVersion: 1,
  stateBy: null,
  stateAt: null,
  history: [],
  beforeVerification: false,
  dose: null,
  ...over,
});

export const DETAILS: OrderDetails = {
  kind: 'SCHEDULED',
  doseAmount: null,
  doseUnit: null,
  routeKey: 'ORAL',
  routeLabel: 'Oral',
  frequencyKey: 'bd',
  frequencyLabel: 'Twice daily',
  startAt: '2026-11-05T04:30:00Z',
  endAt: null,
  durationDays: null,
  indication: null,
  priority: 'ROUTINE',
  prnMinIntervalMinutes: null,
  prnMaxPer24h: null,
  pharmacistChecked: true,
};

export const ORDER_PCM: MarOrder = {
  orderId: 'o1',
  drugKey: 'pcm',
  drugName: 'Tab. Paracetamol 650 mg',
  route: 'Oral',
  highAlert: null,
  frequencyKey: 'bd',
  frequencyLabel: 'Twice daily',
  orderedByName: 'Dr. Meera Kulkarni',
  status: 'ACTIVE',
  orderVersion: 1,
  changed: false,
  details: DETAILS,
  hold: null,
  prn: null,
  hasMedicineCode: true,
  lastGivenAt: null,
  acknowledged: true,
  folded: false,
  slots: [slotOf({ slotAt: AT_8AM, state: 'DUE' }), slotOf({ slotAt: AT_8PM, state: 'LATER', canGive: false })],
};

export const ORDER_HEP: MarOrder = {
  ...ORDER_PCM,
  orderId: 'o2',
  drugKey: 'hep',
  drugName: 'Inj. Heparin 5,000 IU',
  route: 'SC',
  highAlert: 'Anticoagulant',
  slots: [
    slotOf({ slotAt: AT_8AM, state: 'GIVEN', canGive: false, dose: GIVEN_DOSE }),
    slotOf({ slotAt: AT_8PM, state: 'LATER', canGive: false }),
  ],
};

export const PRN_LINE: PrnLine = {
  indication: 'Fever above 38 C',
  minIntervalMinutes: 240,
  maxPer24h: 4,
  givenLast24h: 2,
  lastGivenAt: '2026-11-06T01:00:00Z',
  nextAllowedAt: null,
  lockReason: null,
  canGive: true,
};

export const ORDER_PRN: MarOrder = {
  ...ORDER_PCM,
  orderId: 'o3',
  drugKey: 'pcm-prn',
  drugName: 'Tab. Paracetamol 500 mg',
  frequencyKey: 'prn',
  frequencyLabel: 'As needed (PRN)',
  details: { ...DETAILS, kind: 'PRN', frequencyKey: 'prn', frequencyLabel: 'As needed (PRN)', prnMinIntervalMinutes: 240, prnMaxPer24h: 4 },
  prn: PRN_LINE,
  slots: [],
};

export function orderOf(over: Partial<MarOrder> = {}): MarOrder {
  return { ...ORDER_PCM, ...over };
}

export function marOf(over: Partial<Mar> = {}): Mar {
  return {
    admissionId: 'a1',
    status: 'ADMITTED',
    state: 'on',
    asOf: MAR_AS_OF,
    patient: { ...PATIENT, name: 'Ramesh Kale', uhid: 'PT-00042', age: 64, sex: 'M', bedCode: 'G-03' },
    doseCount: 1,
    canGive: true,
    giveLockReason: null,
    counters: { late: 0, dueNow: 1, given: 1 },
    foldedCount: 0,
    windows: { beforeMinutes: 60, afterMinutes: 60, statMinutes: 15, undoSeconds: 10 },
    overrideReasons: OVERRIDE_REASONS,
    inErrorReasons: IN_ERROR_REASONS,
    ordersToAcknowledge: { count: 0, stat: 0, overdue: 0 },
    orders: [ORDER_PCM, ORDER_HEP],
    secondNurses: SECOND_NURSES,
    notGivenReasons: NOT_GIVEN_REASONS,
    ...over,
  };
}
