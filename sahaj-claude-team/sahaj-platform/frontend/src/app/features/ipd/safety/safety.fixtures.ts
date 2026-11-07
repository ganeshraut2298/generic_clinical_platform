import {
  ClinicalEvent,
  ClinicalEventPage,
  EscalationRow,
  MedicineRound,
  MedicineRoundRow,
  SafetyBoard,
} from './safety.model';

/** Test data in the shape of docs/api/ipd.yaml. Fictional names only. */
export const ROW: EscalationRow = {
  id: 'e1',
  admissionId: 'a1',
  version: 1,
  patientName: 'Asha Patil',
  bedCode: '101-A',
  ewsScore: 8,
  ewsRisk: 'HIGH',
  targetMinutes: 15,
  status: 'OPEN',
  raisedAt: '2026-11-05T05:30:00Z',
  raisedByName: 'Priya Nair',
  minutesSinceRaised: 17,
  reopenedAt: null,
  waitingMinutes: 17,
  pastTarget: true,
  seniorCalls: 0,
  acknowledgedByName: null,
  acknowledgedAt: null,
  response: null,
  canAcknowledge: true,
  canRespond: true,
  canCallSenior: false,
};

export const ACTIONS: SafetyBoard['responseActions'] = [
  { key: 'BEDSIDE_REVIEW', label: 'Reviewed at the bedside' },
  { key: 'TREATMENT_CHANGED', label: 'Treatment changed' },
  { key: 'MOVED_ICU_HDU', label: 'Moved to ICU or HDU' },
  { key: 'RAPID_RESPONSE', label: 'Rapid response team called' },
  { key: 'NO_CHANGE', label: 'Reviewed, no change needed' },
];

export const AS_OF = '2026-11-05T05:47:00Z';
/** 22 characters of base64url, as the server sends (ADR-007 R1). */
export const TOKEN_A = 'AAAAAAAAAAAAAAAAAAAAAA';
export const TOKEN_B = 'BBBBBBBBBBBBBBBBBBBBBB';

/** A row as a nurse sees it. */
export const asNurse = (row: EscalationRow): EscalationRow => ({
  ...row,
  canAcknowledge: false,
  canRespond: false,
  canCallSenior: row.status === 'OPEN',
});

/** 8 AM and 10:30 AM IST on 5 Nov 2026 (the board clock AS_OF is 11:17 AM IST). */
export const SLOT_8AM = '2026-11-05T02:30:00Z';
export const SLOT_1030AM = '2026-11-05T05:00:00Z';

/** A Late dose (3 h late at AS_OF) of a high-alert medicine. */
export const ROUND_LATE: MedicineRoundRow = {
  admissionId: 'a1',
  orderId: 'o2',
  slotAt: SLOT_8AM,
  state: 'LATE',
  lateHours: 3,
  drugName: 'Inj. Heparin 5,000 IU',
  highAlert: 'Anticoagulant',
  patientName: 'Asha Patil',
  bedCode: '101-A',
};

/** A Due dose. */
export const ROUND_DUE: MedicineRoundRow = {
  admissionId: 'a2',
  orderId: 'o1',
  slotAt: SLOT_1030AM,
  state: 'DUE',
  lateHours: null,
  drugName: 'Tab. Paracetamol 650 mg',
  highAlert: null,
  patientName: 'Ravi Shah',
  bedCode: '202-B',
};

export function roundOf(over: Partial<MedicineRound> = {}): MedicineRound {
  return { canGive: true, giveLockReason: null, truncated: false, rows: [ROUND_LATE, ROUND_DUE], ...over };
}

export function boardOf(over: Partial<SafetyBoard> = {}): SafetyBoard {
  const escalations = over.escalations ?? [ROW];
  const open = escalations.filter((e) => e.status !== 'DONE');
  const medicineRound = over.medicineRound ?? roundOf();
  const rows = medicineRound.rows;
  return {
    state: 'on',
    view: 'DOCTOR',
    asOf: AS_OF,
    counts: {
      open: open.length,
      pastTarget: open.filter((e) => e.pastTarget).length,
      dosesDue: rows.filter((r) => r.state === 'DUE').length,
      dosesLate: rows.filter((r) => r.state === 'LATE').length,
      changeToken: TOKEN_A,
      asOf: AS_OF,
    },
    yourPatients: null,
    responseActions: ACTIONS,
    escalations,
    medicineRound,
    ...over,
  };
}

export const eventOf = (over: Partial<ClinicalEvent> = {}): ClinicalEvent => ({
  id: 'x1',
  kind: 'esc',
  text: 'Acknowledged escalation after 20 min',
  at: '2026-11-05T05:35:00Z',
  actorName: 'Dr. Meera Kulkarni',
  admissionId: 'a1',
  patientName: 'Asha Patil',
  bedCode: '101-A',
  ...over,
});

export const pageOf = (items: ClinicalEvent[] = [eventOf()], nextCursor: string | null = null): ClinicalEventPage => ({
  asOf: AS_OF,
  items,
  nextCursor,
});

export const problem = (slug: string, status: number, detail: string, fieldErrors?: { field: string; message: string }[]) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
  ...(fieldErrors ? { fieldErrors } : {}),
});
