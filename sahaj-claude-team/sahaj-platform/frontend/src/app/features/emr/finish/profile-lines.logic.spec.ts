import { EmrFinishEffects } from '../emr-finish.model';
import { field, section } from '../emr.fixtures';
import { dentalBlock, ogBlock, oncoBlock, paedsBlock, physioBlock, surgeryBlock } from './finish.testing';
import {
  ProfileRecordFacts,
  dateWithDay,
  dentalNextSittingLines,
  emptyRecordFacts,
  printsLine,
  profileLines,
  recordFacts,
  visibleBillLines,
} from './profile-lines.logic';

const facts = (over: Partial<ProfileRecordFacts> = {}): ProfileRecordFacts => ({ ...emptyRecordFacts(), ...over });
const lines = (profile: EmrFinishEffects['profile'], f: ProfileRecordFacts = facts()): string[] => profileLines({ profile }, f);

describe('profile lines of "Check before completing" (ADR-025 D7, opd.yaml 0.6.1)', () => {
  describe('dental (#DoctorDental "Send to billing?")', () => {
    it('one line per done row with its sitting and charge; the sitting part is left out when there is one sitting', () => {
      const block = dentalBlock({
        doneRows: [
          { rowId: 'a', name: '36 · Root canal treatment', sitting: { no: 2, of: 3 }, amountPaise: 150000 },
          { rowId: 'b', name: '16 · Scaling', sitting: { no: 1, of: 1 }, amountPaise: 80000 },
          { rowId: 'c', name: '46 · Review', sitting: { no: 1, of: 1 }, amountPaise: null },
        ],
      });
      expect(lines(block)).toEqual([
        '• 36 · Root canal treatment — sitting 2 of 3 · ₹1,500',
        '• 16 · Scaling · ₹800',
        '• 46 · Review · Not in tariff',
      ]);
    });

    it('shows the plan charges once: the generic list keeps the services only', () => {
      const effects: EmrFinishEffects = {
        profile: dentalBlock(),
        billLines: [
          { name: 'Root canal treatment', qty: 1, amountPaise: 150000, state: 'NEW', source: 'DENTAL_PLAN' },
          { name: 'IOPA X-ray', qty: 1, amountPaise: 20000, state: 'NEW', source: 'SERVICE' },
        ],
      };
      expect(visibleBillLines(effects).map((l) => l.name)).toEqual(['IOPA X-ray']);
      expect(visibleBillLines({ ...effects, profile: null }).map((l) => l.name)).toEqual(['Root canal treatment', 'IOPA X-ray']);
    });

    it('next sitting: the interval with the date and slot, or the "No free slot" sentence, or nothing when every row is done', () => {
      const withSlot = dentalBlock({ nextSittingInterval: 'D7', nextSittingDate: '2026-11-20', nextSittingSlot: { date: '2026-11-20', time: '10:00 AM' } });
      expect(dentalNextSittingLines(withSlot)).toEqual(['Next sitting: 7 days · 20 Nov 2026, 10:00 AM']);
      expect(dentalNextSittingLines({ ...withSlot, nextSittingSlot: null })).toEqual([
        'Next sitting: 7 days · 20 Nov 2026',
        'No free slot found. Book the appointment from Appointments.',
      ]);
      expect(dentalNextSittingLines(dentalBlock({ allRowsDone: true, nextSittingInterval: null }))).toEqual([]);
    });
  });

  describe('Obstetrics and Gynaecology (#DoctorGynae "Complete this visit?")', () => {
    it('ANC: visit number, gestation, BP, Hb, what was ordered and the next ANC', () => {
      const block = ogBlock({ ancVisitNo: 5, gestationalAge: { weeks: 27, days: 1 }, bpSystolic: 124, bpDiastolic: 80, hbGdl: 9.8, orderedLabels: ['OGTT (75 g)', 'Td 2nd dose'], nextAncWeeks: 30 });
      expect(lines(block)).toEqual(['ANC visit 5 at 27w 1d · BP 124/80 · Hb 9.8', 'Ordered: OGTT (75 g), Td 2nd dose', 'Next ANC at 30 weeks']);
    });

    it('ANC: leaves the next ANC out when none is set and says nothing was ordered; missing values read "Not recorded"', () => {
      expect(lines(ogBlock({ ancVisitNo: 1 }))).toEqual(['ANC visit 1 at Not recorded · BP Not recorded · Hb Not recorded', 'Ordered: nothing']);
    });

    it('Gynae: composed from the record as drawn', () => {
      const f = facts({ gynae: { cycle: 'regular', flow: 'normal', contraception: 'Condoms', orders: ['Pap smear', 'Pelvic USG'] } });
      expect(lines(ogBlock({ mode: 'GYNAE' }), f)).toEqual(['Gynae visit · cycle regular, flow normal', 'Contraception: Condoms', 'Orders: Pap smear, Pelvic USG']);
      expect(lines(ogBlock({ mode: 'GYNAE' }))).toEqual(['Gynae visit', 'Contraception: Not recorded', 'Orders: none']);
    });
  });

  describe('Paediatrics (#DoctorPaeds)', () => {
    it('weight with its note, vaccines given today, the next due vaccine and the prescription', () => {
      const block = paedsBlock({
        weightKg: 10.2,
        weightNote: 'Within the usual band',
        vaccinesGivenToday: [{ vaccineKey: 'mmr_2', label: 'MMR 2' }, { vaccineKey: 'var_1', label: 'Varicella 1' }],
        nextDue: { vaccineKey: 'dtwp_b1', label: 'DTwP booster 1', groupLabel: '18 months' },
      });
      expect(lines(block, facts({ medicines: ['Paracetamol syrup', 'ORS'] }))).toEqual([
        'Weight 10.2 kg (Within the usual band)',
        'Vaccines given today: MMR 2, Varicella 1',
        'Next due: DTwP booster 1 (18 months)',
        'Prescription: Paracetamol syrup; ORS',
      ]);
    });

    it('leaves the bracket out without a note, says "Not recorded" without a weight and "none" without vaccines or medicines', () => {
      expect(lines(paedsBlock({ weightKg: 9 }))[0]).toBe('Weight 9 kg');
      expect(lines(paedsBlock())).toEqual(['Weight: Not recorded', 'Vaccines given today: none', 'Prescription: none']);
    });
  });

  describe('Physiotherapy (#DoctorPhysio)', () => {
    it('pain with last session, treatment, home exercises and the session that will be marked done', () => {
      const block = physioBlock({ courseId: 'c1', sessionNo: 4, packageSize: 10, nextSessionNo: 5, painScore: 4, previousPainScore: 5 });
      expect(lines(block, facts({ modalities: ['IFT', 'Hot pack'], homeExercises: 4 }))).toEqual([
        'Pain 4/10 (was 5 last session)',
        'Treatment: IFT, Hot pack',
        '4 home exercises',
        'Session 4 of 10 will be marked done',
      ]);
    });

    it('leaves the bracket out without an earlier score; a complete package reads "package complete"', () => {
      const block = physioBlock({ courseId: 'c1', sessionNo: 10, packageSize: 10, packageComplete: true, painScore: 2 });
      expect(lines(block)).toEqual(['Pain 2/10', 'Treatment: none', '0 home exercises', 'Session 10 done · package complete']);
    });
  });

  describe('Medical Oncology (H18 default)', () => {
    it('"Cycle n · decision" and "Next chemo <date with day name> · cycle n+1"', () => {
      const block = oncoBlock({ cycleNo: 3, decision: 'GIVE_REDUCED', nextCycleDate: '2026-12-03', nextCycleNo: 4, nextCycleLabel: 'NEXT_CHEMO' });
      expect(lines(block)).toEqual(['Cycle 3 · Give, reduce dose', 'Next chemo Thu 3 Dec 2026 · cycle 4']);
    });

    it('"Re-booked chemo" after Delay 1 week; the second line is left out after Stop chemo', () => {
      const delayed = oncoBlock({ cycleNo: 3, decision: 'DELAY_1_WEEK', nextCycleDate: '2026-12-10', nextCycleNo: 4, nextCycleLabel: 'REBOOKED_CHEMO' });
      expect(lines(delayed)[1]).toBe('Re-booked chemo Thu 10 Dec 2026 · cycle 4');
      expect(lines(oncoBlock({ cycleNo: 3, decision: 'STOP' }))).toEqual(['Cycle 3 · Stop chemo']);
    });
  });

  describe('General Surgery (H18 default)', () => {
    it('"OT booking: procedure · date, slot" when booked', () => {
      const block = surgeryBlock({ bookState: 'BOOKED', procedureText: 'Lap cholecystectomy', otDate: '2026-11-20', slotText: 'OT 2 · 10:00' });
      expect(lines(block)).toEqual(['OT booking: Lap cholecystectomy · 20 Nov 2026, OT 2 · 10:00']);
    });

    it('says nothing about the booking until it is booked', () => {
      expect(lines(surgeryBlock({ bookState: 'READY', procedureText: 'Hernia repair' }))).toEqual([]);
    });
  });

  it('General Medicine and records without a profile have no profile lines', () => {
    expect(lines({ profile: 'gm' })).toEqual([]);
    expect(lines(null)).toEqual([]);
  });

  describe('footer words for the prints', () => {
    it('oncology: "Cycle record, take-home medicines and calendar will print."', () => {
      expect(printsLine(['PRESCRIPTION', 'CYCLE_RECORD', 'CHEMO_CALENDAR'])).toBe('Cycle record, take-home medicines and calendar will print.');
      expect(printsLine(['PRESCRIPTION', 'CYCLE_RECORD'])).toBe('Cycle record, take-home medicines will print.');
    });

    it('surgery: "Admission letter and pre-op instructions will print."', () => {
      expect(printsLine(['PRESCRIPTION', 'ADMISSION_LETTER', 'PREOP_SHEET'])).toBe('Admission letter and pre-op instructions will print.');
    });

    it('names nothing extra for a prescription alone, no list or an empty list', () => {
      expect(printsLine(['PRESCRIPTION'])).toBeNull();
      expect(printsLine(undefined)).toBeNull();
      expect(printsLine([])).toBeNull();
    });
  });

  it('the date with its day name', () => {
    expect(dateWithDay('2026-11-13')).toBe('Fri 13 Nov 2026');
  });

  describe('facts read from the record', () => {
    const layout = [
      section({ key: 'menstrual', fields: [field({ key: 'cycle' }), field({ key: 'flow', valueKind: 'CHOICE', options: [{ key: 'normal', label: 'Normal', isNone: false }] })] }),
      section({ key: 'contraception', fields: [field({ key: 'findings' })] }),
    ];
    const values = {
      menstrual: { cycle: 'Regular', flow: 'normal' },
      contraception: { findings: 'Condoms' },
      rx: { rx: { component: 'PRESCRIPTION', schemaVersion: 1, rows: [{ id: '1', name: 'ORS' }] } },
      inv: { list: { component: 'EMR_LIST', kind: 'INVESTIGATIONS', schemaVersion: 1, items: [{ id: 'i', text: 'Pap smear', active: true }, { id: 'j', text: 'Old', active: false }] } },
      ex: {
        hep: { component: 'HOME_EXERCISES', schemaVersion: 1, items: [{ id: 'h1', name: 'Heel slides' }, { id: 'h2', name: 'Mini squats' }] },
        mod: { component: 'MODALITIES', schemaVersion: 1, given: [{ key: 'IFT', text: 'IFT', params: null }] },
      },
    };

    it('reads the medicines, the gynae choices in lower case, the active orders, the home exercises and the modalities', () => {
      const f = recordFacts(layout, values as never);
      expect(f.medicines).toEqual(['ORS']);
      expect(f.gynae).toEqual({ cycle: 'regular', flow: 'normal', contraception: 'Condoms', orders: ['Pap smear'] });
      expect(f.homeExercises).toBe(2);
      expect(f.modalities).toEqual(['IFT']);
    });
  });
});
