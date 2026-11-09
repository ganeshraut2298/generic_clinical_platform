import type { AdmittedPatientRow } from '../ipd.model';
import type { CardDataGroup } from './card-setup.model';

/**
 * The fictional "Example patient" of the live preview (AC50), built in the browser when nobody is
 * admitted, when the patient list cannot be loaded, or when the user is not the owner. It is never saved
 * or sent. Invented name, numbers and times only; no real person.
 */
export const SAMPLE_AS_OF = '2026-11-09T05:30:00Z';
export const SAMPLE_ID = 'example-patient';

export const SAMPLE_ROW: AdmittedPatientRow = {
  id: SAMPLE_ID,
  ipNumber: 'IP/26-27/0000',
  patientId: SAMPLE_ID,
  patientName: 'Example patient',
  age: 52,
  sex: 'F',
  doctorName: 'Dr. Example Doctor',
  bedCode: '202',
  categoryName: 'General',
  wardName: 'General ward',
  admissionType: 'planned',
  payer: 'Self',
  dayNumber: 1,
  depositPaise: 1000000,
  minimumDepositPaise: 500000,
  runningBillPaise: 450000,
  depositLow: false,
  depositState: 'OK',
  admittedAt: '2026-11-09T03:50:00Z',
  goingHomeToday: false,
  uhid: 'PT-000000',
  wardId: 'example-ward',
  roomName: 'Room 2',
  mobile: '98000 00000',
  clinical: {
    escalation: null,
    latestEws: { score: 2, risk: 'LOW', recordedAt: '2026-11-09T04:45:00Z' },
    vitalsDue: false,
    allergy: { status: 'NONE_KNOWN' },
    diagnosis: 'Example diagnosis',
    latestVitals: {
      recordedAt: '2026-11-09T04:45:00Z',
      recordedByName: 'Example nurse',
      bp: { systolic: 168, diastolic: 98, mark: 'CHECK' },
      pulse: { value: 88 },
      spo2: { value: 97 },
      temp: { value: 37.0 },
      rr: { value: 18 },
    },
  },
};

/**
 * A row as a role would receive it: when the role may see neither the clinical nor the vitals group the
 * clinical facts are absent (not null), as the server sends them to FRONT_DESK. The groups themselves
 * come from the catalogue the server sent.
 */
export function rowForGroups(
  row: AdmittedPatientRow,
  groups: readonly CardDataGroup[],
): AdmittedPatientRow {
  if (groups.includes('clinical') || groups.includes('vitals')) {
    return row;
  }
  const copy: AdmittedPatientRow = { ...row };
  delete copy.clinical;
  return copy;
}
