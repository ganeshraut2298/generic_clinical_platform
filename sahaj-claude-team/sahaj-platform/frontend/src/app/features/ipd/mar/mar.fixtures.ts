import { KeyLabel } from '../emr/emr.model';
import { PATIENT } from '../emr/emr.fixtures';
import { DoseRow, Mar, MarOrder, MarSlot, SecondNurseOption } from './mar.model';

/** Test data in the shape of docs/api/ipd.yaml (SAH-32). Fictional names only. */

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
];

export const GIVEN_DOSE: DoseRow = {
  id: 'd1',
  slotAt: AT_8AM,
  outcome: 'GIVEN',
  reason: null,
  recordedAt: '2026-11-06T02:34:00Z',
  recordedByName: 'Sr. Anjali Pawar',
  secondCheckByName: 'Sr. Rupali More',
};

export const slotOf = (over: Partial<MarSlot> = {}): MarSlot => ({
  slotAt: AT_8AM,
  state: 'DUE',
  lateHours: null,
  canGive: true,
  dose: null,
  ...over,
});

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
  slots: [slotOf({ slotAt: AT_8AM, state: 'DUE' }), slotOf({ slotAt: AT_8PM, state: 'LATER', canGive: false })],
};

export const ORDER_HEP: MarOrder = {
  orderId: 'o2',
  drugKey: 'hep',
  drugName: 'Inj. Heparin 5,000 IU',
  route: 'SC',
  highAlert: 'Anticoagulant',
  frequencyKey: 'bd',
  frequencyLabel: 'Twice daily',
  orderedByName: 'Dr. Meera Kulkarni',
  status: 'ACTIVE',
  slots: [
    slotOf({ slotAt: AT_8AM, state: 'GIVEN', canGive: false, dose: GIVEN_DOSE }),
    slotOf({ slotAt: AT_8PM, state: 'LATER', canGive: false }),
  ],
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
    orders: [ORDER_PCM, ORDER_HEP],
    secondNurses: SECOND_NURSES,
    notGivenReasons: NOT_GIVEN_REASONS,
    ...over,
  };
}
