import { DeskBoardCard, DeskDaySlots, DeskDoctor, DeskTokenPanel } from './desk.model';

/** Contract-shaped fixtures for the SAH-90 unit tests. */
export function makeDoctor(overrides: Partial<DeskDoctor> = {}): DeskDoctor {
  return {
    doctorMembershipId: 'd1',
    name: 'Dr. Meera Kulkarni',
    department: 'General medicine',
    departmentSource: 'EMR_DEPARTMENT',
    room: 'Room 2',
    slotMinutes: 15,
    consultFeePaise: 40000,
    followUpFeePaise: 20000,
    closeBeforeHours: 1,
    todayStatus: 'IN_OPD',
    startsAt: null,
    leaveUntil: null,
    freeToday: 7,
    nextFree: { date: '2026-11-24', freeCount: 7 },
    days: [],
    ...overrides,
  };
}

export function makeCard(overrides: Partial<DeskBoardCard> = {}): DeskBoardCard {
  return {
    key: 'k1',
    kind: 'APPOINTMENT',
    appointmentId: 'a1',
    appointmentVersion: 1,
    visitId: null,
    column: 'BOOKED',
    time: '10:30',
    patientId: 'p1',
    patientName: 'Rekha Shinde',
    doctorMembershipId: 'd1',
    doctorName: 'Dr. Meera Kulkarni',
    visitType: 'NEW',
    appointmentStatus: 'BOOKED',
    stage: null,
    nextStage: null,
    tokenNo: null,
    checkedInAt: null,
    tags: [],
    primaryAction: 'CHECK_IN',
    followUp: null,
    ...overrides,
  };
}

export const TEST_TODAY = '2026-11-25';

export function makeTokenPanel(overrides: Partial<DeskTokenPanel> = {}): DeskTokenPanel {
  return {
    visitId: 'v1',
    tokenNo: 8,
    tokenDate: TEST_TODAY,
    patientName: 'Rekha Shinde',
    doctorMembershipId: 'd1',
    doctorName: 'Dr. Meera Kulkarni',
    room: 'Room 2',
    stage: 'WAITING',
    checkedInAt: '2026-11-25T05:35:00Z',
    patientsAhead: 3,
    estimatedWaitMinutes: 45,
    slotMinutes: 15,
    ...overrides,
  };
}

export function makeDaySlots(overrides: Partial<DeskDaySlots> = {}): DeskDaySlots {
  return {
    doctorMembershipId: 'd1',
    date: TEST_TODAY,
    working: true,
    onLeave: false,
    slotMinutes: 15,
    closeBeforeHours: 1,
    freeCount: 3,
    groups: [
      {
        label: 'MORNING',
        slots: [
          { time: '10:30', insideCutOff: false },
          { time: '10:45', insideCutOff: false },
          { time: '11:00', insideCutOff: false },
        ],
      },
    ],
    nextFreeDate: null,
    ...overrides,
  };
}

/** A Problem answer as the server sends it, for HttpTestingController. */
export function problemBody(slug: string, status: number, extra: Record<string, unknown> = {}) {
  return { type: `https://sahaj.in/problems/${slug}`, title: slug, status, ...extra };
}
