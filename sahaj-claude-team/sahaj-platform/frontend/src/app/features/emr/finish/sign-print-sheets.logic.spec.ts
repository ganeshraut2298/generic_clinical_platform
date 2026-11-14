import { PrintSheet } from '../specialty/print-sheet';
import { ChemoOrderView, EmrOncoView, EmrSpecialtyView, SpChemoPlan, SpPreauth, SpPreopOrders, SpSurgeryPlan } from '../specialty/specialty.model';
import { oncoBlock, surgeryBlock } from './finish.testing';
import { admissionLetterSheet, chemoCalendarSheet, cycleRecordSheet, preopSheet } from './sign-print-sheets.logic';

const sheetOf = (title: string, sections: PrintSheet['sections'], footer?: string): PrintSheet => ({
  clinicName: 'Test Clinic',
  clinicLines: [],
  title,
  patientLine: 'Test Patient',
  sections,
  footer,
});

const plan = (over: Partial<SpChemoPlan> = {}): SpChemoPlan => ({ component: 'CHEMO_PLAN', schemaVersion: 1, takeHome: [], referrals: [], ...over });
const onco = (over: Partial<EmrOncoView> = {}): EmrOncoView => ({ bsaUsed: 1.62, cycleNo: 3, response: 'PARTIAL_RESPONSE', nextCycle: null, ...over }) as EmrOncoView;
const lines = (sheet: PrintSheet, heading: string): string[] => sheet.sections.find((s) => s.heading === heading)?.lines ?? [];

describe('documents printed after the sign (ADR-025 D19)', () => {
  describe('cycle record', () => {
    const order = {
      status: 'SENT',
      protocolKey: 'AC',
      levelPct: 90,
      drugs: [{ name: 'Doxorubicin', finalMg: 96, roundedMg: 95 }, { name: 'Cyclophosphamide', finalMg: null, roundedMg: 960 }],
      premeds: [{ key: 'a', text: 'Ondansetron', minutes: 30, given: true }, { key: 'b', text: 'Dexamethasone', minutes: 30, given: false }],
      secondCheck: { state: 'CONFIRMED', confirmedByName: 'Nurse Joshi' },
      hold: null,
    } as unknown as ChemoOrderView;

    it('holds the cycle, decision, BSA and response, the order, the cycle note and the referrals', () => {
      const sheet = cycleRecordSheet({
        sheetOf,
        block: oncoBlock({ cycleNo: 3, decision: 'GIVE_REDUCED' }),
        onco: onco(),
        plan: plan({ cycleNote: 'Cycle 3, day 1.\nWeight 58 kg.', referrals: ['DIETITIAN', 'CARDIOLOGY_ECHO'] }),
        order,
        reference: { profile: 'onco', tagNote: '', protocols: [{ key: 'AC', label: 'AC (doxorubicin + cyclophosphamide)' }] } as never,
      });
      expect(sheet.title).toBe('Cycle record');
      expect(lines(sheet, 'Cycle')).toEqual(['Cycle 3', 'Decision: Give, reduce dose', 'BSA 1.62 m²', 'Response: Partial response']);
      expect(lines(sheet, 'Chemotherapy order')).toEqual([
        'Protocol: AC (doxorubicin + cyclophosphamide) at 90%',
        'Doxorubicin · 96 mg',
        'Cyclophosphamide · 960 mg',
        'Pre-medicines given: Ondansetron',
        'Pre-medicines not given: Dexamethasone',
        'Order: Sent to pharmacy',
        'Second check: confirmed by Nurse Joshi',
      ]);
      expect(lines(sheet, 'Cycle note')).toEqual(['Cycle 3, day 1.', 'Weight 58 kg.']);
      expect(lines(sheet, 'Referrals')).toEqual(['Dietitian', 'Cardiology (echo)']);
    });

    it('prints without the order when it could not be read, and says a held order is on hold', () => {
      const none = cycleRecordSheet({ sheetOf, block: oncoBlock({ cycleNo: 3 }), onco: null, plan: null, order: null, reference: null });
      expect(none.sections.map((s) => s.heading)).toEqual(['Cycle', 'Cycle note']);
      const held = cycleRecordSheet({ sheetOf, block: oncoBlock({ cycleNo: 3, decision: 'STOP', orderStatus: 'HELD' }), onco: null, plan: null, order: null, reference: null });
      expect(lines(held, 'Chemotherapy order')).toEqual(['On hold']);
    });
  });

  describe('chemo calendar', () => {
    it('lists the next chemo date, blood test, echo, imaging, the fever warning and the daily symptom check', () => {
      const sheet = chemoCalendarSheet({
        sheetOf,
        block: oncoBlock({ nextCycleDate: '2026-12-03', nextCycleNo: 4, nextCycleLabel: 'NEXT_CHEMO' }),
        onco: onco({ nextCycle: { defaultDate: '2026-12-03', label: 'NEXT_CHEMO', bloodTestDate: '2026-12-02', echo: 'Echo due after cycle 4' } }),
        plan: plan({ nextCycleDate: '2026-12-03', imagingText: 'CT after cycle 4' }),
      });
      expect(sheet.title).toBe('Chemotherapy calendar');
      expect(lines(sheet, 'Next chemo')).toEqual(['Next chemo Thu 3 Dec 2026 · cycle 4']);
      expect(lines(sheet, 'Blood test reminders')).toEqual(['Wed 2 Dec 2026 · CBC, kidney, liver · day before']);
      expect(lines(sheet, 'Echo')).toEqual(['Echo due after cycle 4']);
      expect(lines(sheet, 'Imaging')).toEqual(['CT after cycle 4']);
      expect(lines(sheet, 'Fever warning')).toEqual(['Call if 38 °C or above.']);
      expect(lines(sheet, 'Daily symptom check')).toHaveLength(1);
    });

    it('reads "Re-booked chemo" after a delay and takes the date from the server default when the plan has none', () => {
      const sheet = chemoCalendarSheet({
        sheetOf,
        block: oncoBlock({ nextCycleLabel: 'REBOOKED_CHEMO', nextCycleNo: 4 }),
        onco: onco({ nextCycle: { defaultDate: '2026-12-10', label: 'REBOOKED_CHEMO', bloodTestDate: '2026-12-09', echo: '' } }),
        plan: plan(),
      });
      expect(lines(sheet, 'Next chemo')).toEqual(['Re-booked chemo Thu 10 Dec 2026 · cycle 4']);
      expect(sheet.sections.some((s) => s.heading === 'Imaging')).toBe(false);
    });
  });

  describe('admission letter (facts and headings only, H19)', () => {
    const surgeryPlan: SpSurgeryPlan = {
      component: 'SURGERY_PLAN',
      schemaVersion: 1,
      procedureKey: 'LAP_CHOLECYSTECTOMY',
      procedureText: 'Lap cholecystectomy',
      approach: 'Laparoscopic, may convert to open',
      anaesthesia: 'GA',
      admission: 'IPD_1_2_DAYS',
      payer: 'INSURANCE_TPA',
      packagePaise: 8000000,
    };
    const view = { profile: 'surgery', surgery: { payerChip: 'Insurance (TPA) · pre-auth sent', depositPaise: 1000000 } } as unknown as EmrSpecialtyView;

    it('lists procedure, anaesthesia, stay, OT date and slot, payer, package, deposit, pre-authorisation and the ward bed', () => {
      const preauth: SpPreauth = { component: 'PREAUTH', schemaVersion: 1, sent: true, sentOn: '2026-11-12', reference: 'PA-77' };
      const sheet = admissionLetterSheet({
        sheetOf,
        block: surgeryBlock({ bookState: 'BOOKED', procedureText: 'Lap cholecystectomy', otDate: '2026-11-20', slotText: 'OT 2 · 10:00', admission: 'IPD_1_2_DAYS', admissionRequested: true }),
        plan: surgeryPlan,
        preauth,
        view,
      });
      expect(sheet.title).toBe('Admission letter');
      expect(lines(sheet, 'Procedure')).toEqual(['Lap cholecystectomy · Laparoscopic, may convert to open']);
      expect(lines(sheet, 'Anaesthesia')).toEqual(['GA']);
      expect(lines(sheet, 'Stay')).toEqual(['IPD 1–2 days']);
      expect(lines(sheet, 'OT date and slot')).toEqual(['20 Nov 2026, OT 2 · 10:00']);
      expect(lines(sheet, 'Who pays')).toEqual(['Insurance (TPA) · pre-auth sent']);
      expect(lines(sheet, 'Package')).toEqual(['₹80,000']);
      expect(lines(sheet, 'Deposit at admission')).toEqual(['₹10,000']);
      expect(lines(sheet, 'Pre-authorisation')).toEqual(['Sent · 12 Nov 2026 · ref PA-77']);
      expect(lines(sheet, 'Ward bed')).toEqual(['Requested with the booking']);
      expect(sheet.footer).toBeUndefined();
    });

    it('leaves out what is not known and the pre-authorisation for a self-paying patient', () => {
      const sheet = admissionLetterSheet({
        sheetOf,
        block: surgeryBlock({ bookState: 'BOOKED', procedureText: 'Excision of lipoma' }),
        plan: { ...surgeryPlan, payer: 'SELF', approach: null, anaesthesia: null, admission: null, packagePaise: null },
        preauth: null,
        view: null,
      });
      expect(sheet.sections.map((s) => s.heading)).toEqual(['Procedure', 'Who pays']);
      expect(lines(sheet, 'Who pays')).toEqual(['Self']);
    });
  });

  describe('pre-op sheet', () => {
    const preop: SpPreopOrders = {
      component: 'PREOP_ORDERS',
      schemaVersion: 1,
      ticked: ['NIL_BY_MOUTH', 'ADMIT_7AM_OT_10AM'],
      extraLines: ['Bring the earlier reports'],
      blood: 'GROUP_AND_SAVE',
    };

    it('lists the ticked instructions, the extra lines, the blood arrangement and the OT slot, and says the ward places medicine orders', () => {
      const sheet = preopSheet({ sheetOf, block: surgeryBlock({ bookState: 'BOOKED', otDate: '2026-11-20', slotText: 'OT 2 · 10:00' }), plan: null, preop, reference: null });
      expect(sheet.title).toBe('Pre-op instructions');
      expect(lines(sheet, 'OT date and slot')).toEqual(['20 Nov 2026, OT 2 · 10:00']);
      expect(lines(sheet, 'Pre-op instructions')).toEqual(['Nothing by mouth from midnight', 'Admit 7 am, OT at 10 am', 'Bring the earlier reports']);
      expect(lines(sheet, 'Blood')).toEqual(['Group and save']);
      expect(sheet.footer).toBe('Medicine orders are placed by the ward');
    });

    it('prefers the reference labels of the profile', () => {
      const sheet = preopSheet({
        sheetOf,
        block: null,
        plan: null,
        preop: { ...preop, ticked: ['NIL_BY_MOUTH'], extraLines: [], blood: null },
        reference: { profile: 'surgery', tagNote: '', lists: { surgeryPreopOrders: [{ key: 'NIL_BY_MOUTH', label: 'Nil by mouth from 12 midnight' }] } },
      });
      expect(lines(sheet, 'Pre-op instructions')).toEqual(['Nil by mouth from 12 midnight']);
    });
  });
});
