import { CourseAccount, CourseSitting, TreatmentCourseView } from './course.model';

/** Contract-shaped test data for the treatment course specs. Amounts are paise. No patient data. */

export function sitting(over: Partial<CourseSitting> = {}): CourseSitting {
  return {
    id: 's1',
    sittingNo: 1,
    encounterKind: 'OPD',
    encounterLabel: 'OPD sitting',
    encounterRef: null,
    plannedDate: '2026-11-18',
    status: 'PLANNED',
    items: ['Cleaning and shaping'],
    chargePaise: 150000,
    note: null,
    admissionRequested: false,
    settledInIpd: false,
    ipdBillTotalPaise: null,
    opdVisitId: null,
    admissionId: null,
    ...over,
  };
}

export function course(over: Partial<TreatmentCourseView> = {}): TreatmentCourseView {
  return {
    id: 'c1',
    courseNo: 'TC-0412',
    kind: 'DENTAL',
    unit: 'SITTING',
    name: 'Dental treatment course',
    startedOn: '2026-10-28',
    status: 'ACTIVE',
    sittingCount: 3,
    doneCount: 1,
    patientId: 'p1',
    doctorMembershipId: 'm1',
    doctorName: 'Dr Test',
    note: 'estimate signed by patient',
    revision: 5,
    sittings: [
      sitting({ id: 's1', sittingNo: 1, status: 'DONE', encounterRef: 'OPD-10231', plannedDate: '2026-10-28', chargePaise: 175000, items: ['Examination', 'Filling 16'] }),
      sitting({ id: 's2', sittingNo: 2, status: 'TODAY', encounterRef: 'OPD-10502', plannedDate: '2026-11-11', chargePaise: 150000 }),
      sitting({ id: 's3', sittingNo: 3, status: 'PLANNED', plannedDate: '2026-11-18', chargePaise: 300000 }),
    ],
    addOptions: [
      { key: 'opd_sitting', label: 'OPD sitting', encounterKind: 'OPD' },
      { key: 'day_care_admission', label: 'Day-care admission', encounterKind: 'DAY_CARE' },
      { key: 'ipd_admission', label: 'IPD admission', encounterKind: 'IPD' },
    ],
    canPlan: true,
    canAdmit: false,
    accountNote: 'one running account: advances and part payments carry across visits',
    ...over,
  };
}

export function account(over: Partial<CourseAccount> = {}): CourseAccount {
  return {
    courseId: 'c1',
    courseNo: 'TC-0412',
    unit: 'SITTING',
    estimatePaise: 625000,
    paidPaise: 600000,
    creditPaise: 275000,
    dueFromEarlierPaise: 0,
    todayChargePaise: 150000,
    dueTodayPaise: 0,
    balancePaise: 25000,
    sittingCount: 3,
    doneCount: 1,
    receiptCount: 2,
    nextPlannedAdmission: null,
    suggestions: { partPaymentPaise: 50000, ipdDepositPaise: null },
    canCollect: true,
    ledger: [
      { kind: 'PAYMENT', date: '2026-10-28', type: 'ADVANCE', mode: 'UPI', against: 'Course advance', amountPaise: 500000, receiptNo: 'RC-2201' },
      { kind: 'PAYMENT', date: '2026-11-04', type: 'PART_PAYMENT', mode: 'CASH', against: 'Sitting 2', amountPaise: 100000, receiptNo: 'RC-2287' },
      { kind: 'CREDIT_USED', date: '2026-11-11', type: null, mode: null, against: 'Sitting 2', amountPaise: -150000, receiptNo: null },
    ],
    ...over,
  };
}
