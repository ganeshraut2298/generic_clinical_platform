import { resolvedStandard } from '../card/card-setup.fixtures';
import { AdmissionList, AdmittedCardAbilities, AdmittedClinicalFlags, AdmittedPatientRow } from '../ipd.model';

/**
 * Contract-shaped data for the SAH-36 specs (`AdmittedPatientRow`, `AdmittedClinicalFlags`,
 * `AdmittedCardAbilities`, `WardOption` in docs/api/ipd.yaml). Invented people and numbers only.
 */
export const WARD_A = '11111111-1111-4111-8111-111111111111';
export const WARD_B = '22222222-2222-4222-8222-222222222222';
export const WARD_EMPTY = '33333333-3333-4333-8333-333333333333';

export const AS_OF = '2026-10-24T07:30:00Z'; // 24 Oct 2026, 1:00 PM IST

export const CLINICAL_QUIET: AdmittedClinicalFlags = {
  escalation: null,
  latestEws: null,
  vitalsDue: false,
  allergy: { status: 'NOT_RECORDED' },
};

export const ROW: AdmittedPatientRow = {
  id: 'a1',
  ipNumber: 'IP/26-27/0042',
  patientId: 'p1',
  patientName: 'Asha Patil',
  age: 56,
  sex: 'F',
  doctorName: 'Dr. Meera Rao',
  bedCode: '101-A',
  categoryName: 'General',
  wardName: 'General ward',
  admissionType: 'emergency',
  payer: 'Self',
  dayNumber: 3,
  depositPaise: 1000000,
  minimumDepositPaise: 500000,
  runningBillPaise: 300000,
  depositLow: false,
  depositState: 'OK',
  admittedAt: '2026-10-22T03:50:00Z', // 22 Oct 2026, 9:20 AM IST
  goingHomeToday: false,
  uhid: 'PT-000042',
  wardId: WARD_A,
  roomName: 'Room 1',
  clinical: CLINICAL_QUIET,
};

export const ABILITIES_NURSE: AdmittedCardAbilities = {
  nursing: true,
  emr: true,
  transfer: true,
  discharge: false,
  deposit: false,
  depositLowFlag: false,
  visibleGroups: ['patient', 'clinical', 'vitals'],
};

export const ABILITIES_FRONT_DESK: AdmittedCardAbilities = {
  nursing: false,
  emr: false,
  transfer: true,
  discharge: false,
  deposit: true,
  depositLowFlag: true,
  visibleGroups: ['patient', 'contact', 'billing'],
};

export const ABILITIES_OWNER: AdmittedCardAbilities = {
  nursing: true,
  emr: true,
  transfer: true,
  discharge: true,
  deposit: true,
  depositLowFlag: true,
  visibleGroups: ['patient', 'contact', 'clinical', 'vitals', 'billing'],
};

/** A row for FRONT_DESK: the `clinical` property is absent (not null), as the server sends it. */
export function withoutClinical(row: AdmittedPatientRow): AdmittedPatientRow {
  const copy: AdmittedPatientRow = { ...row };
  delete copy.clinical;
  return copy;
}

export function rowOf(partial: Partial<AdmittedPatientRow> = {}): AdmittedPatientRow {
  return { ...ROW, ...partial };
}

/** `count` generated patients in WARD_A / WARD_B alternately, ids `g1`..`g<count>`. */
export function manyRows(count: number): AdmittedPatientRow[] {
  return Array.from({ length: count }, (_, i) => {
    const n = i + 1;
    return rowOf({
      id: `g${n}`,
      patientName: `Patient ${n}`,
      ipNumber: `IP/26-27/${String(1000 + n)}`,
      uhid: `PT-${String(5000 + n)}`,
      bedCode: `B-${n}`,
      wardId: n % 2 === 0 ? WARD_B : WARD_A,
      wardName: n % 2 === 0 ? 'Surgical ward' : 'General ward',
    });
  });
}

export function listOf(partial: Partial<AdmissionList> = {}): AdmissionList {
  return {
    bedsTotal: 8,
    bedsOccupied: 3,
    bedsAvailable: 5,
    canAdmit: true,
    depositAlerts: 1,
    alertPercent: 80,
    emergency: { writable: true, queue: 3, overTarget: 1 },
    safety: null,
    asOf: AS_OF,
    wards: [
      { id: WARD_A, name: 'General ward' },
      { id: WARD_B, name: 'Surgical ward' },
      { id: WARD_EMPTY, name: 'Maternity ward' },
    ],
    card: ABILITIES_NURSE,
    cardSetup: resolvedStandard(),
    items: [
      ROW,
      rowOf({
        id: 'a2',
        ipNumber: 'IP/26-27/0043',
        patientName: 'Ravi Shah',
        uhid: 'PT-000077',
        bedCode: '201-B',
        wardId: WARD_B,
        wardName: 'Surgical ward',
        roomName: null,
        age: null,
        sex: null,
        payer: 'Insurance',
        depositState: 'NOT_NEEDED',
        depositPaise: 0,
        runningBillPaise: 450000,
        admissionType: 'planned',
        dayNumber: 1,
        admittedAt: '2026-10-24T03:50:00Z',
      }),
      rowOf({
        id: 'a3',
        ipNumber: 'IP/26-27/0044',
        patientName: 'Sunita More',
        uhid: null,
        bedCode: '102-A',
        depositState: 'DUE',
        depositPaise: 100000,
        runningBillPaise: 90000,
        depositLow: true,
        admissionType: 'planned',
        dayNumber: 2,
      }),
    ],
    ...partial,
  };
}
