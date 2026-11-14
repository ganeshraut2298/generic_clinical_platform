import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, field, section } from '../emr.fixtures';
import { EmrReprint } from '../emr-finish.model';
import { EmrSpecialtyReference, EmrSpecialtyView } from '../specialty/specialty.model';
import { EmrReprintService, roleCanReprint } from './emr-reprint.service';
import { canPrintVaccinationCard, reprintPaper, reprintSections, reprintVaccinationCard } from './reprint.logic';

const REPRINT: EmrReprint = {
  visitId: 'v1',
  recordId: 'r1',
  reprint: true,
  signedAt: '2026-11-13T05:30:00Z',
  context: casePaperContext(),
  sections: [
    {
      key: 'complaints',
      name: 'Chief complaints',
      heading: 'CHIEF_COMPLAINTS',
      values: {
        complaint: {
          component: 'EMR_LIST',
          kind: 'COMPLAINTS',
          schemaVersion: 1,
          items: [{ id: 'i1', itemKey: null, text: 'Headache', newToList: true, attr1: 'D2', attr2: 'MODERATE', active: true }],
        },
      },
    },
    {
      key: 'rx',
      name: 'Prescription',
      heading: 'MEDICINES',
      values: {
        rx: {
          component: 'PRESCRIPTION',
          schemaVersion: 1,
          rows: [{ id: 'r1', medicineId: null, name: 'Paracetamol 500 mg', frequency: '1-0-1', days: 3, when: 'AFTER_FOOD', source: 'TYPED' }],
        },
      },
    },
    { key: 'advice', name: 'Advice', heading: 'ADVICE', values: { note: 'Rest and fluids' } },
  ],
  addenda: [],
  layout: {
    departmentKey: 'gm',
    departmentName: 'General Medicine',
    starterKey: 'gm',
    layoutSource: 'PUBLISHED',
    versionNo: 6,
    choiceSource: 'DOCTOR_SPECIALTY',
    takenAt: '2026-11-12T05:00:00Z',
    sections: [
      section({ key: 'complaints', name: 'Chief complaints', print: true, fields: [field({ key: 'complaint', valueKind: 'COMPONENT' })] }),
      section({ key: 'rx', name: 'Prescription', print: true, fields: [field({ key: 'rx', valueKind: 'COMPONENT' })] }),
      section({ key: 'advice', name: 'Advice', print: true, fields: [field({ key: 'note' })] }),
    ],
  },
  specialty: null,
  specialtyReference: null,
};

describe('reprint (SAH-51-6)', () => {
  describe('roles', () => {
    it('owner, front desk and pharmacist reprint any visit', () => {
      for (const role of ['OWNER', 'FRONT_DESK', 'PHARMACIST'] as const) {
        expect(roleCanReprint(role, false)).toBe(true);
      }
    });

    it('doctors reprint only their own visits', () => {
      expect(roleCanReprint('DOCTOR', true)).toBe(true);
      expect(roleCanReprint('DOCTOR', false)).toBe(false);
      expect(roleCanReprint('VISITING_DOCTOR', true)).toBe(true);
      expect(roleCanReprint('VISITING_DOCTOR', false)).toBe(false);
    });

    it('nurse, billing and counsellor cannot', () => {
      for (const role of ['NURSE', 'BILLING', 'COUNSELLOR'] as const) {
        expect(roleCanReprint(role, true)).toBe(false);
      }
      expect(roleCanReprint(undefined, true)).toBe(false);
    });
  });

  describe('paper', () => {
    it('is composed by the same function as the live case paper', () => {
      const model = reprintPaper(REPRINT, () => null);
      const byHeading = Object.fromEntries(model.rows.map((r) => [r.heading, r]));
      expect(byHeading['CHIEF_COMPLAINTS'].lines).toEqual(['Headache · 2 days']);
      expect(byHeading['MEDICINES'].rx.map((m) => m.name)).toEqual(['PARACETAMOL 500 MG']); // the clinic's capitals setting is on
      expect(byHeading['ADVICE'].lines).toEqual(['Rest and fluids']);
      expect(model.filledCount).toBe(3);
    });

    it('takes each section from the frozen layout of the answer, in the answer order', () => {
      const sections = reprintSections(REPRINT);
      expect(sections.map((x) => x.key)).toEqual(['complaints', 'rx', 'advice']);
      expect(sections[0].fields[0].valueKind).toBe('COMPONENT');
      expect(sections[2].fields[0].valueKind).toBe('TEXT');
      expect(sections.every((x) => x.print && x.shown)).toBe(true);
    });

    it('prints a choice and a multiple choice as their words, not their keys', () => {
      const reprint: EmrReprint = {
        ...REPRINT,
        sections: [{ key: 'exam', name: 'Examination', heading: 'EXAMINATION', values: { gait: 'with_stick', signs: ['pallor', 'oedema'] } }],
        layout: {
          ...REPRINT.layout,
          sections: [
            section({
              key: 'exam',
              name: 'Examination',
              print: true,
              fields: [
                field({ key: 'gait', label: 'Gait', valueKind: 'CHOICE', options: [{ key: 'with_stick', label: 'With stick', isNone: false }] }),
                field({
                  key: 'signs',
                  label: 'Signs',
                  valueKind: 'MULTI_CHOICE',
                  options: [
                    { key: 'pallor', label: 'Pallor', isNone: false },
                    { key: 'oedema', label: 'Pedal oedema', isNone: false },
                  ],
                }),
              ],
            }),
          ],
        },
      };
      const exam = reprintPaper(reprint, () => null).rows.find((r) => r.heading === 'EXAMINATION')!;
      expect(exam.lines).toEqual(['Gait: With stick', 'Signs: Pallor, Pedal oedema']);
    });

    it('prints the profile lines from the computed block and its reference labels (vaccines given today, fitness, weight note)', () => {
      const specialty = {
        profile: 'paeds',
        mode: null,
        tagNote: '',
        paeds: { growthNotes: [{ field: 'weight', label: 'Within the usual band', alert: false }], vaccines: [] },
        surgery: { fitness: { state: 'FIT', text: 'Fit for surgery' } },
      } as unknown as EmrSpecialtyView;
      const reference = { profile: 'paeds', tagNote: '', lists: { iapVaccines: [{ key: 'mmr_2', label: 'MMR 2', group: 'g15' }] } } as unknown as EmrSpecialtyReference;
      const reprint: EmrReprint = {
        ...REPRINT,
        specialty,
        specialtyReference: reference,
        sections: [
          { key: 'immunization', name: 'Vaccines given today', heading: 'PROCEDURES', values: { imm: { component: 'IMMUNIZATION', schemaVersion: 1, givenToday: [{ vaccineKey: 'mmr_2' }], scheduleReviewed: true } } },
          { key: 'fitness', name: 'Fitness', heading: 'EXAMINATION', values: { fit: { component: 'SURGERY_FITNESS', schemaVersion: 1, asa: 'II', otherIllness: [], pac: 'FIT', tests: [] } } },
          { key: 'growth', name: 'Growth', heading: 'EXAMINATION', values: { gr: { component: 'GROWTH', schemaVersion: 1, weightKg: 10.2 } } },
        ],
        layout: {
          ...REPRINT.layout,
          sections: [
            section({ key: 'immunization', print: true, fields: [field({ key: 'imm', valueKind: 'COMPONENT' })] }),
            section({ key: 'fitness', print: true, fields: [field({ key: 'fit', valueKind: 'COMPONENT' })] }),
            section({ key: 'growth', print: true, fields: [field({ key: 'gr', valueKind: 'COMPONENT' })] }),
          ],
        },
      };
      const rows = Object.fromEntries(reprintPaper(reprint, () => null).rows.map((r) => [r.heading, r.lines]));
      expect(rows['PROCEDURES']).toEqual(['Vaccine given: MMR 2']);
      expect(rows['EXAMINATION']).toEqual(['Fitness: ASA II · Fit for surgery', 'Weight 10.2 kg (Within the usual band)']);
    });

    it('copes with a pruned specialty block and no reference (front desk or pharmacist, H32): no error, lines without the note', () => {
      const specialty = { profile: 'paeds', paeds: {}, surgery: {} } as unknown as EmrSpecialtyView;
      const reprint: EmrReprint = {
        ...REPRINT,
        specialty,
        specialtyReference: null,
        sections: [
          { key: 'immunization', name: 'Vaccines given today', heading: 'PROCEDURES', values: { imm: { component: 'IMMUNIZATION', schemaVersion: 1, givenToday: [{ vaccineKey: 'mmr_2' }], scheduleReviewed: true } } },
          { key: 'fitness', name: 'Fitness', heading: 'EXAMINATION', values: { fit: { component: 'SURGERY_FITNESS', schemaVersion: 1, asa: 'II', otherIllness: [], pac: 'FIT', tests: [] } } },
          { key: 'growth', name: 'Growth', heading: 'EXAMINATION', values: { gr: { component: 'GROWTH', schemaVersion: 1, weightKg: 10.2 } } },
        ],
        layout: {
          ...REPRINT.layout,
          sections: [
            section({ key: 'immunization', print: true, fields: [field({ key: 'imm', valueKind: 'COMPONENT' })] }),
            section({ key: 'fitness', print: true, fields: [field({ key: 'fit', valueKind: 'COMPONENT' })] }),
            section({ key: 'growth', print: true, fields: [field({ key: 'gr', valueKind: 'COMPONENT' })] }),
          ],
        },
      };
      const rows = Object.fromEntries(reprintPaper(reprint, () => null).rows.map((r) => [r.heading, r.lines]));
      expect(rows['PROCEDURES']).toEqual(['Vaccine given: Mmr 2']);
      expect(rows['EXAMINATION']).toEqual(['Weight 10.2 kg']);
    });
  });

  describe('vaccination card', () => {
    const paeds = (): EmrReprint => ({
      ...REPRINT,
      context: casePaperContext({ patient: { name: 'Test Child', age: 1, sex: 'M', uhid: 'U-9' } }),
      specialty: {
        profile: 'paeds',
        mode: null,
        tagNote: '',
        paeds: {
          dateOfBirth: '2025-05-10',
          ageMonths: 18,
          ageDays: 2,
          growthNotes: [],
          vaccines: [
            { vaccineKey: 'bcg', state: 'GIVEN', givenOn: '2025-05-10' },
            { vaccineKey: 'mmr_2', state: 'GIVEN', givenOn: '2026-11-13', givenHere: true },
            { vaccineKey: 'varicella_1', state: 'OVERDUE' },
            { vaccineKey: 'dtwp_b1', state: 'LATER' },
          ],
          dueTodayCount: 0,
          overdueCount: 1,
          nextDue: { vaccineKey: 'dtwp_b1', groupLabel: '18 months' },
          milestonesSetUp: true,
          doseWeightKg: null,
          doseWeightChangedRowIds: [],
        },
      },
      specialtyReference: {
        profile: 'paeds',
        tagNote: '',
        lists: {
          iapGroups: [
            { key: 'birth', label: 'Birth' },
            { key: 'g15', label: '15 months' },
          ],
          iapVaccines: [
            { key: 'bcg', label: 'BCG', group: 'birth' },
            { key: 'mmr_2', label: 'MMR 2', group: 'g15' },
            { key: 'varicella_1', label: 'Varicella 1', group: 'g15' },
          ],
        },
      },
    });

    it('prints a card from a pruned block that keeps only the vaccines (H32)', () => {
      const full = paeds();
      const pruned = { ...full, specialty: { profile: 'paeds', paeds: { vaccines: full.specialty!.paeds!.vaccines } } } as unknown as EmrReprint;
      const sheet = reprintVaccinationCard(pruned)!;
      expect(sheet.title).toBe('Vaccination card');
      expect(sheet.patientLine).not.toContain('Date of birth');
      expect(sheet.footer ?? '').not.toContain('Next due');
    });

    it('is offered on a Paediatrics reprint only', () => {
      expect(canPrintVaccinationCard(paeds())).toBe(true);
      expect(canPrintVaccinationCard(REPRINT)).toBe(false);
      expect(reprintVaccinationCard(REPRINT)).toBeNull();
    });

    it('prints every vaccine with its date or state, the next due vaccine and the date of birth, as on the signing day', () => {
      const sheet = reprintVaccinationCard(paeds())!;
      expect(sheet.title).toBe('Vaccination card');
      expect(sheet.clinicName).toBe('Test Clinic');
      expect(sheet.patientLine).toContain('Test Child');
      expect(sheet.patientLine).toContain('Date of birth 10 May 2025');
      expect(sheet.sections).toEqual([
        { heading: 'Birth', lines: ['BCG — given 10 May 2025'] },
        { heading: '15 months', lines: ['MMR 2 — given 13 Nov 2026', 'Varicella 1 — overdue'] },
      ]);
      expect(sheet.footer).toBe('Next due: 18 months');
    });
  });

  describe('service', () => {
    let http: HttpTestingController;
    let dialogOpen: ReturnType<typeof vi.fn>;
    let snack: ReturnType<typeof vi.fn>;
    let service: EmrReprintService;

    beforeEach(() => {
      dialogOpen = vi.fn();
      snack = vi.fn();
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          { provide: MatDialog, useValue: { open: dialogOpen } },
          { provide: MatSnackBar, useValue: { open: snack } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      service = TestBed.inject(EmrReprintService);
    });

    afterEach(() => http.verify());

    it('reads the signed prescription and opens it', () => {
      service.open('v1');
      expect(service.opening()).toBe('v1');
      http.expectOne('/api/v1/opd/visits/v1/prescription-print').flush(REPRINT);
      expect(dialogOpen).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: { reprint: REPRINT } }));
      expect(service.opening()).toBeNull();
    });

    it('does not open a second one while one is on its way', () => {
      service.open('v1');
      service.open('v2');
      http.expectOne('/api/v1/opd/visits/v1/prescription-print').flush(REPRINT);
    });

    it('says the visit is not signed yet', () => {
      service.open('v1');
      http.expectOne('/api/v1/opd/visits/v1/prescription-print').flush(
        { type: 'https://sahaj.in/problems/emr-record-not-signed', title: 'x', status: 409 },
        { status: 409, statusText: 'x' },
      );
      expect(snack).toHaveBeenCalledWith('This visit is not signed yet.', 'Close', expect.anything());
    });
  });
});
