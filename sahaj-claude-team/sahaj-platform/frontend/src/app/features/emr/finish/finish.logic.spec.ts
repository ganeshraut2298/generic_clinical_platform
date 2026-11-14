import { dentalBlock, physioBlock } from './finish.testing';
import { EmrFinishBlocker, EmrFinishCheck, EmrSignOutcome } from '../emr-finish.model';
import { EmrBodyMapValue, EmrListValue, EmrPrescriptionValue } from '../emr-phase3.model';
import {
  afterSign,
  billLineText,
  blockerMessage,
  dentalAllDone,
  finishTitles,
  firstBlockedSection,
  followUpEffectText,
  patientRecordText,
  summaryRows,
} from './finish.logic';

const outcome = (over: Partial<EmrSignOutcome> = {}): EmrSignOutcome => ({
  stage: { outcome: 'MOVED', to: 'BILLING' },
  followUp: null,
  billLines: 0,
  billTotalPaise: 0,
  ipdOrders: [],
  round: null,
  emergency: null,
  patientRecord: 'NOT_CHANGED',
  course: null,
  prints: ['PRESCRIPTION'],
  ...over,
});

const list = (kind: EmrListValue['kind'], items: { text: string; attr1?: string; code?: string }[]): EmrListValue =>
  ({
    component: 'EMR_LIST',
    kind,
    schemaVersion: 1,
    items: items.map((i, n) => ({ id: `i${n}`, itemKey: null, text: i.text, newToList: true, attr1: i.attr1 ?? '', attr2: '', active: true, code: i.code })),
  }) as unknown as EmrListValue;

describe('finish logic (SAH-51-1, SAH-51-2, SAH-67)', () => {
  describe('titles', () => {
    it('General Medicine uses the words of the mockup', () => {
      expect(finishTitles('OPD_VISIT', 'gm', false)).toEqual({ title: 'Check before completing', confirm: 'Print Rx & call next patient', back: 'Back to edit' });
    });

    it('the IPD round signs today\'s note and the Emergency case signs the assessment', () => {
      expect(finishTitles('IPD_ROUND', null, false)).toEqual({ title: "Sign today's progress note", confirm: 'Sign note', back: 'Back to edit' });
      expect(finishTitles('ER_ASSESSMENT', null, false)).toEqual({ title: 'Sign emergency assessment', confirm: 'Sign assessment', back: 'Back to edit' });
    });

    it('dental asks "Send to billing?" and says Confirm when every row is done', () => {
      expect(finishTitles('OPD_VISIT', 'dental', false)).toEqual({ title: 'Send to billing?', confirm: 'Confirm & book next sitting', back: 'Back' });
      expect(finishTitles('OPD_VISIT', 'dental', true).confirm).toBe('Confirm');
    });

    it('physiotherapy asks "Complete session n?" (the mockup) and confirms with Confirm', () => {
      expect(finishTitles('OPD_VISIT', 'physio', false, physioBlock({ sessionNo: 4 }))).toEqual({ title: 'Complete session 4?', confirm: 'Confirm', back: 'Back' });
      expect(finishTitles('OPD_VISIT', 'physio', false, physioBlock()).title).toBe('Complete this visit?');
    });

    it('Obstetrics and Paediatrics ask "Complete this visit?" with "Complete & call next"', () => {
      for (const p of ['og', 'paeds'] as const) {
        expect(finishTitles('OPD_VISIT', p, false)).toEqual({ title: 'Complete this visit?', confirm: 'Complete & call next', back: 'Back' });
      }
    });

    it('reads dental "all rows done" from the server check only', () => {
      expect(dentalAllDone(null)).toBe(false);
      expect(dentalAllDone({ effects: { profile: dentalBlock({ allRowsDone: true }) } } as unknown as EmrFinishCheck)).toBe(true);
      expect(dentalAllDone({ effects: { profile: dentalBlock() } } as unknown as EmrFinishCheck)).toBe(false);
    });
  });

  describe('General Medicine summary', () => {
    const base = {
      profile: 'gm' as const,
      starterKey: 'gm',
      encounter: 'OPD_VISIT' as const,
      layout: [],
      states: [],
      attrLabel: (_kind: 'COMPLAINTS' | 'DIAGNOSIS', key: string | undefined) => (key === 'D2' ? '2 days' : (key ?? '')),
    };

    it('reads the seven rows from the record', () => {
      const rx: EmrPrescriptionValue = {
        component: 'PRESCRIPTION',
        schemaVersion: 1,
        rows: [{ id: 'r1', medicineId: null, name: 'Telmisartan 40 mg', frequency: '1-0-0', days: 30, when: 'AFTER_FOOD', source: 'TYPED' }],
      } as unknown as EmrPrescriptionValue;
      const body: EmrBodyMapValue = { component: 'BODY_MAP', schemaVersion: 1, pins: [], noFindingsConfirmed: false } as unknown as EmrBodyMapValue;
      const rows = summaryRows({
        ...base,
        paper: { rows: [], filledCount: 0, vitalsLine: 'BP 146/92 · P 82', mlc: false },
        values: {
          complaints: { complaint: list('COMPLAINTS', [{ text: 'Headache', attr1: 'D2' }]) },
          diagnosis: { dx: list('DIAGNOSIS', [{ text: 'Hypertension' }]) },
          tests: { inv: list('INVESTIGATIONS', [{ text: 'HbA1c' }]), svc: list('SERVICES', [{ text: 'ECG' }]) },
          rx: { rx },
          findings: { body },
          followup: { next: { component: 'FOLLOW_UP', schemaVersion: 1, interval: 'D7' } },
        },
      });
      expect(rows.map((r) => r.label)).toEqual(['Complaints', 'General exam', 'Local findings', 'Diagnosis', 'Tests & services', 'Medicines', 'Follow-up']);
      expect(Object.fromEntries(rows.map((r) => [r.label, r.text]))).toEqual({
        Complaints: 'Headache (2 days)',
        'General exam': 'BP 146/92 · P 82',
        'Local findings': 'None',
        Diagnosis: 'Hypertension',
        'Tests & services': 'HbA1c, ECG',
        Medicines: '1 medicine · Telmisartan 40 mg',
        'Follow-up': 'In 7 days',
      });
    });

    it('says "Not recorded" for what is empty', () => {
      const rows = summaryRows({ ...base, paper: null, values: {} });
      expect(rows.find((r) => r.label === 'Complaints')!.text).toBe('Not recorded');
      expect(rows.find((r) => r.label === 'General exam')!.text).toBe('Not recorded');
      expect(rows.find((r) => r.label === 'Follow-up')!.text).toBe('Not recorded');
      expect(rows.find((r) => r.label === 'Medicines')!.text).toBe('Not recorded');
    });
  });

  it('other screens show one row per shown section with its status', () => {
    const rows = summaryRows({
      profile: null,
      starterKey: 'ortho',
      encounter: 'OPD_VISIT',
      paper: null,
      values: {},
      layout: [
        { key: 'a', name: 'Chief complaints', required: true },
        { key: 'b', name: 'Advice', required: false },
        { key: 'c', name: 'Exam', required: true },
      ] as never,
      states: [
        { key: 'a', part: 'ASSESSMENT', required: true, done: true, status: 'DONE' },
        { key: 'b', part: 'TREATMENT', required: false, done: false, status: 'OPTIONAL' },
        { key: 'c', part: 'ASSESSMENT', required: true, done: false, status: 'REQUIRED' },
      ],
      attrLabel: () => '',
    });
    expect(rows).toEqual([
      { label: 'Chief complaints', text: 'Done' },
      { label: 'Advice', text: 'Not recorded' },
      { label: 'Exam', text: 'Required' },
    ]);
  });

  describe('effects in words', () => {
    it('bill lines, with the already-billed note', () => {
      expect(billLineText({ name: 'ECG', qty: 1, amountPaise: 30000, state: 'NEW' })).toBe('ECG · ₹300');
      expect(billLineText({ name: 'Dressing', qty: 2, amountPaise: 20000, state: 'ALREADY_BILLED' })).toBe(
        'Dressing × 2 · ₹200. Already billed. Ask billing to adjust.',
      );
    });

    it('follow-up: the date, the first free slot or "No free slot found"', () => {
      expect(followUpEffectText({ interval: 'D7', dueDate: '2026-11-20', firstFreeSlot: { date: '2026-11-20', time: '17:00' } })).toEqual([
        'Follow-up due 20 Nov 2026. Books an appointment on that day.',
        'First free slot: 20 Nov 2026, 17:00.',
      ]);
      expect(followUpEffectText({ interval: 'D7', dueDate: '2026-11-20', firstFreeSlot: null })[1]).toBe(
        'No free slot found. Book the appointment from Appointments.',
      );
    });

    it('patient record: the information row, and the conflict wording', () => {
      expect(patientRecordText({ fields: ['ALLERGIES', 'LONG_TERM_CONDITIONS'], removals: [], conflict: false })).toBe(
        'Patient record will be updated: Allergies, Long-term conditions',
      );
      expect(patientRecordText({ fields: ['ALLERGIES'], removals: [], conflict: true })).toBe("The patient's record changed. Review before updating");
      expect(patientRecordText({ fields: [], removals: [], conflict: false })).toBeNull();
    });
  });

  describe('blockers', () => {
    const blockers: EmrFinishBlocker[] = [
      { key: 'STILL_NEEDED', message: 'Still needed: diagnosis.', sectionKeys: ['diagnosis', 'rx'] },
      { key: 'ENCOUNTER_CLOSED', message: 'This admission is closed' },
    ];

    it('joins the messages and finds the first missing section', () => {
      expect(blockerMessage(blockers)).toBe('Still needed: diagnosis. This admission is closed');
      expect(firstBlockedSection(blockers)).toBe('diagnosis');
      expect(firstBlockedSection([blockers[1]])).toBeNull();
    });

    it('an unresolved allergy alert reads the fixed sentence', () => {
      expect(blockerMessage([{ key: 'ALLERGY_ALERT', message: 'x' }])).toBe('Remove the drug or confirm the allergy alert before completing.');
    });
  });

  describe('message after the sign', () => {
    it('OPD: "Visit completed · calling next patient", print, back to the Queue', () => {
      expect(afterSign('OPD_VISIT', 'gm', outcome(), {}, null)).toEqual({ message: 'Visit completed · calling next patient', target: 'QUEUE', prints: ['PRESCRIPTION'] });
    });

    it('dental names the interval when a next sitting was booked', () => {
      const a = afterSign('OPD_VISIT', 'dental', outcome({ course: { nextSittingId: 's2' } }), { dental: { nextInterval: 'D7' } }, null);
      expect(a.message).toBe('Sent to billing · next sitting booked in 7 days');
      expect(afterSign('OPD_VISIT', 'dental', outcome({ course: null }), { dental: { nextInterval: null } }, null).message).toBe('Sent to billing');
    });

    it('physiotherapy: next session booked, or package complete', () => {
      const booked = afterSign('OPD_VISIT', 'physio', outcome({ course: { nextAppointmentDate: '2026-11-18', nextAppointmentTime: '17:00' } }), {}, 3);
      expect(booked.message).toBe('Session 3 done · session 4 booked 18 Nov 2026, 17:00');
      expect(afterSign('OPD_VISIT', 'physio', outcome({ course: { packageComplete: true } }), {}, 6).message).toBe('Session 6 done · package complete');
    });

    it('oncology names the language of the prescriptions (the mockup message)', () => {
      const a = afterSign('OPD_VISIT', 'onco', outcome({ prints: ['PRESCRIPTION', 'CYCLE_RECORD', 'CHEMO_CALENDAR'] }), {}, null, 'Marathi');
      expect(a.message).toBe('Visit completed · cycle note saved, prescriptions printed in Marathi');
      expect(a.prints).toEqual(['PRESCRIPTION', 'CYCLE_RECORD', 'CHEMO_CALENDAR']);
    });

    it('surgery with a booking says "Go to ward and OT with the booking"', () => {
      const a = afterSign('OPD_VISIT', 'surgery', outcome({ prints: ['PRESCRIPTION', 'ADMISSION_LETTER', 'PREOP_SHEET'] }), {}, null);
      expect(a.message).toBe('Visit completed · Go to ward and OT with the booking');
    });

    it('IPD: "Round done", no print, back to IPD', () => {
      expect(afterSign('IPD_ROUND', null, outcome({ prints: [] }), {}, null)).toEqual({ message: 'Round done', target: 'IPD', prints: [] });
    });

    it('Emergency: the server lists the prescription only for Discharge from ER', () => {
      const e = (kind: 'DISCHARGE' | 'ADMIT' | 'REFER_OUT' | 'SEND_TO_OPD') =>
        afterSign('ER_ASSESSMENT', null, outcome({ prints: kind === 'DISCHARGE' ? ['PRESCRIPTION'] : [], emergency: { outcome: kind, admissionRequestId: null, opdVisitId: null, tokenNo: 7 } }), {}, null);
      expect(e('DISCHARGE').prints).toEqual(['PRESCRIPTION']);
      expect(e('ADMIT').prints).toEqual([]);
      expect(e('REFER_OUT').prints).toEqual([]);
      expect(e('SEND_TO_OPD').message).toBe('Assessment signed · sent to OPD, token 7');
      expect(e('DISCHARGE').target).toBe('EMERGENCY');
    });
  });
});
