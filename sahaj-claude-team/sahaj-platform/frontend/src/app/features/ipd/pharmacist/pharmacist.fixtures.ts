import { AS_OF, PATIENT, REJECT_REASONS, TOKEN_1, checkOf } from '../emr/emr.fixtures';
import { PharmacistActionResult, PharmacistCheckRow, PharmacistChecks } from './pharmacist.model';

/** Test data in the shape of docs/api/ipd.yaml. Fictional names only. */
export { AS_OF, TOKEN_1 };

export const ROW: PharmacistCheckRow = {
  orderId: 'o1',
  admissionId: 'a1',
  drugKey: 'pcm',
  drugName: 'Tab. Paracetamol 650 mg',
  highAlert: null,
  frequencyKey: 'od',
  frequencyLabel: 'Once daily',
  patient: PATIENT,
  orderedByName: 'Dr. Meera Kulkarni',
  orderedAt: '2026-11-06T05:35:00Z',
  overrideReason: null,
  check: checkOf(),
  canVerify: true,
  verifyLockReason: null,
  canReject: true,
};

export const rowOf = (over: Partial<PharmacistCheckRow> = {}): PharmacistCheckRow => ({ ...ROW, ...over });

export function checksOf(over: Partial<PharmacistChecks> = {}): PharmacistChecks {
  const items = over.items ?? [ROW];
  return {
    state: 'on',
    asOf: AS_OF,
    waiting: items.length,
    verifierName: 'Rahul Deshmukh',
    rejectReasons: REJECT_REASONS,
    items,
    nextCursor: null,
    ...over,
  };
}

export const verifiedResult = (over: Partial<PharmacistActionResult['result']> = {}): PharmacistActionResult => ({
  result: {
    orderId: 'o1',
    drugName: 'Tab. Paracetamol 650 mg',
    outcome: 'VERIFIED',
    replayed: false,
    actorName: 'Rahul Deshmukh',
    rejectReason: null,
    ...over,
  },
  checks: checksOf({ items: [] }),
});
