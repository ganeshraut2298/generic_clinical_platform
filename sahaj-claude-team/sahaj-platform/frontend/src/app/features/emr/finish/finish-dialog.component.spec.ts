import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, record } from '../emr.fixtures';
import { EmrFinishCheck } from '../emr-finish.model';
import { EmrRecordView } from '../emr.model';
import { EmrRecordStore } from '../record/emr-record.store';
import { EmrFinishFlow } from './emr-finish.flow';
import { FinishDialogComponent } from './finish-dialog.component';
import { dentalBlock, ogBlock, oncoBlock, paedsBlock, physioBlock, surgeryBlock } from './finish.testing';

const EMR = '/api/v1/opd/emr/records';
const SHARE = { whatsapp: { available: false as const, reason: 'Needs the WhatsApp service' }, abha: { available: false as const, reason: 'Needs the ABDM service' } };
const check = (over: Partial<EmrFinishCheck> = {}): EmrFinishCheck => ({ ready: true, blockers: [], encounterType: 'OPD_VISIT', effects: {}, info: [], share: SHARE, ...over });

describe('FinishDialogComponent (SAH-51-1, SAH-67-1, SAH-67-2)', () => {
  let fixture: ComponentFixture<FinishDialogComponent>;
  let flow: EmrFinishFlow;
  let store: EmrRecordStore;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let el: HTMLElement;

  function boot(view: EmrRecordView, answer: EmrFinishCheck): void {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: { close } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        EmrRecordStore,
        EmrFinishFlow,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(EmrRecordStore);
    flow = TestBed.inject(EmrFinishFlow);
    store.load(view.id);
    http.expectOne(`${EMR}/${view.id}`).flush(view);
    http.expectOne(`${EMR}/${view.id}/case-paper`).flush(casePaperContext());
    flow.check.set(answer);
    fixture = TestBed.createComponent(FinishDialogComponent);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  const text = (sel: string) => el.querySelector(sel)?.textContent?.replace(/\s+/g, ' ').trim();
  const confirm = () => el.querySelector<HTMLButtonElement>('[data-act=finish-confirm]')!;

  afterEach(() => http.verify());

  it('names the patient and the department and lists the seven rows of General Medicine', () => {
    boot(record(), check());
    expect(text('#finish-title')).toBe('Check before completing');
    expect(text('[data-testid=finish-sub]')).toBe('Test Patient · General Medicine');
    expect(Array.from(el.querySelectorAll('.srow .sl')).map((e) => e.textContent!.trim())).toEqual([
      'Complaints',
      'General exam',
      'Local findings',
      'Diagnosis',
      'Tests & services',
      'Medicines',
      'Follow-up',
    ]);
    expect(confirm().textContent!.trim()).toBe('Print Rx & call next patient');
  });

  it('shows the WhatsApp tick switched off with its note, and the print language', () => {
    boot(record(), check());
    const box = el.querySelector<HTMLInputElement>('[data-testid=finish-whatsapp]')!;
    expect(box.disabled).toBe(true);
    expect(box.checked).toBe(false);
    expect(el.textContent).toContain('Send prescription to patient on WhatsApp');
    expect(el.textContent).toContain('Needs the WhatsApp service');
    expect(text('[data-testid=finish-print]')).toBe('Prescription prints in English with English drug names.');
  });

  it('shows what the sign will do: the move, the follow-up, the bill lines and the patient record', () => {
    boot(
      record(),
      check({
        effects: {
          stage: { outcome: 'WILL_MOVE', to: 'BILLING' },
          followUp: { interval: 'D7', dueDate: '2026-11-20', firstFreeSlot: null },
          billLines: [{ name: 'ECG', qty: 1, amountPaise: 30000, state: 'NEW' }, { name: 'Dressing', qty: 1, amountPaise: 20000, state: 'ALREADY_BILLED' }],
          billTotalPaise: 50000,
          patientRecord: { fields: ['ALLERGIES'], removals: ['Sulfa drugs'], conflict: false },
        },
        info: ['Advice only: next ANC at 30 weeks'],
      }),
    );
    expect(text('[data-testid=finish-stage]')).toBe('The visit moves to Billing.');
    expect(el.textContent).toContain('Follow-up due 20 Nov 2026. Books an appointment on that day.');
    expect(el.textContent).toContain('No free slot found. Book the appointment from Appointments.');
    expect(el.textContent).toContain('Already billed. Ask billing to adjust.');
    expect(text('[data-testid=finish-patient]')).toBe('Patient record will be updated: Allergies');
    expect(text('[data-testid=finish-info]')).toBe('Advice only: next ANC at 30 weeks');
    const tick = el.querySelector<HTMLInputElement>('[data-act=remove-allergy]')!;
    expect(tick.parentElement!.textContent).toContain('Remove from the patient record: Sulfa drugs');
  });

  it('"Back to edit" closes and signs nothing', () => {
    boot(record(), check());
    el.querySelector<HTMLButtonElement>('[data-act=back-to-edit]')!.click();
    expect(close).toHaveBeenCalled();
    http.expectNone(`${EMR}/r1/sign`);
  });

  it('Confirm signs through the flow', () => {
    boot(record(), check());
    const sign = vi.spyOn(flow, 'sign').mockImplementation(() => undefined);
    confirm().click();
    expect(sign).toHaveBeenCalled();
  });

  it('shows the error with "Try again" when the sign failed, and the retry signs again', () => {
    boot(record(), check());
    flow.signError.set('We could not sign this visit. Nothing was saved. Try again.');
    fixture.detectChanges();
    expect(text('[data-testid=finish-error]')).toContain('Nothing was saved');
    const sign = vi.spyOn(flow, 'sign').mockImplementation(() => undefined);
    el.querySelector<HTMLButtonElement>('[data-act=finish-retry]')!.click();
    expect(sign).toHaveBeenCalled();
  });

  describe('IPD round', () => {
    const view = () => record({ encounterType: 'IPD_ROUND' });

    it('says "Sign today\'s progress note" and the button "Sign note"', () => {
      boot(view(), check({ encounterType: 'IPD_ROUND' }));
      expect(text('#finish-title')).toBe("Sign today's progress note");
      expect(confirm().textContent!.trim()).toBe('Sign note');
      http.expectOne(`${EMR}/r1/ipd-order-options`).flush({ drugs: [], frequencies: [], overrideReasons: [{ key: 'CLINICAL_NEED', label: 'Clinical need' }] });
    });

    it('lists a refused medicine as "Not ordered: <reason>" and disables Confirm until it is removed', () => {
      boot(
        view(),
        check({
          ready: false,
          encounterType: 'IPD_ROUND',
          blockers: [{ key: 'IPD_ORDER_BLOCKED', message: 'x' }],
          effects: { ipdOrders: [{ rowId: 'o1', drugName: 'Amoxicillin', frequencyLabel: 'Twice a day', outcome: 'BLOCKED', texts: ['Allergy to penicillins'], checkToken: null }] },
        }),
      );
      http.expectOne(`${EMR}/r1/ipd-order-options`).flush({ drugs: [], frequencies: [], overrideReasons: [] });
      fixture.detectChanges();
      expect(text('.oerr')).toBe('Not ordered: Allergy to penicillins');
      expect(confirm().disabled).toBe(true);
    });

    it('asks for the reason of a warning, with SAH-31\'s lines, and enables Confirm once it is chosen', () => {
      boot(
        view(),
        check({
          ready: false,
          encounterType: 'IPD_ROUND',
          blockers: [{ key: 'IPD_ORDER_NEEDS_REASON', message: 'x' }],
          effects: { ipdOrders: [{ rowId: 'o1', drugName: 'Warfarin', frequencyLabel: 'Once a day', outcome: 'NEEDS_REASON', texts: ['Interacts with aspirin'], checkToken: 'A'.repeat(22) }] },
        }),
      );
      http.expectOne(`${EMR}/r1/ipd-order-options`).flush({ drugs: [], frequencies: [], overrideReasons: [{ key: 'CLINICAL_NEED', label: 'Clinical need' }] });
      fixture.detectChanges();
      expect(el.textContent).toContain('Interacts with aspirin');
      expect(el.textContent).toContain('Reason to continue (recorded in the audit trail)');
      expect(confirm().disabled).toBe(true);
      const select = el.querySelector<HTMLSelectElement>('[data-act=order-reason]')!;
      select.value = 'CLINICAL_NEED';
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      expect(confirm().disabled).toBe(false);
    });

    it('shows what nursing will see as Investigations advised', () => {
      boot(view(), check({ encounterType: 'IPD_ROUND', effects: { investigationsAdvised: 'CBC; Chest X-ray' } }));
      http.expectOne(`${EMR}/r1/ipd-order-options`).flush({ drugs: [], frequencies: [], overrideReasons: [] });
      fixture.detectChanges();
      expect(text('[data-testid=finish-investigations]')).toBe('Nursing will see: Investigations advised: CBC; Chest X-ray');
    });
  });

  describe('Emergency', () => {
    it('says "Sign emergency assessment", shows the triage level and the outcome', () => {
      boot(
        record({ encounterType: 'ER_ASSESSMENT' }),
        check({ encounterType: 'ER_ASSESSMENT', effects: { emergency: { triageLevel: 1, triageLevelName: 'Red · Immediate', outcome: 'REFER_OUT' } } }),
      );
      expect(text('#finish-title')).toBe('Sign emergency assessment');
      expect(confirm().textContent!.trim()).toBe('Sign assessment');
      expect(text('[data-testid=finish-triage]')).toContain('Red · Immediate');
      expect(text('[data-testid=finish-outcome]')).toContain('Refer out');
    });
  });

  describe('dental and physiotherapy choices', () => {
    function withProfile(profile: 'dental' | 'physio'): EmrRecordView {
      const view = record();
      view.layout = { ...view.layout, starterKey: profile };
      view.specialty = { profile } as never;
      return view;
    }

    it('dental: "Send to billing?", the rows done today, the estimate and the next sitting choices with 7 days at start', () => {
      boot(
        withProfile('dental'),
        check({ effects: { profile: dentalBlock({ doneRows: [{ rowId: 'p1', name: '36 · Scaling', sitting: { no: 1, of: 1 }, amountPaise: 80000 }], estimateTotalPaise: 240000 }) } }),
      );
      expect(text('#finish-title')).toBe('Send to billing?');
      expect(text('[data-testid=finish-profile]')).toBe('• 36 · Scaling · ₹800');
      expect(el.textContent).toContain('Estimate total ₹2,400');
      const chips = Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid=finish-dental] .chip'));
      expect(chips.map((c) => c.textContent!.trim())).toEqual(['3 days', '7 days', '14 days', '1 month']);
      expect(chips[1].getAttribute('aria-pressed')).toBe('true');
      expect(confirm().textContent!.trim()).toBe('Confirm & book next sitting');
      chips[2].click();
      expect(flow.dentalInterval()).toBe('D14');
      http.expectOne(`${EMR}/r1/finish-check`).flush(check());
    });

    it('dental: when every row is done the button reads "Confirm" and no next sitting is asked', () => {
      boot(withProfile('dental'), check({ effects: { profile: dentalBlock({ allRowsDone: true, nextSittingInterval: null }) } }));
      expect(confirm().textContent!.trim()).toBe('Confirm');
      expect(el.querySelector('[data-testid=finish-dental] .chip')).toBeNull();
    });

    it('physiotherapy: offers the free slots of the doctor and sends the one chosen', () => {
      boot(withProfile('physio'), check({ effects: { profile: physioBlock({ courseId: 'c1', sessionNo: 3, packageSize: 6, nextSessionNo: 4 }) } }));
      http.expectOne(`${EMR}/r1/free-slots?limit=4`).flush([{ date: '2026-11-18', time: '17:00' }, { date: '2026-11-19', time: '17:30' }]);
      http.expectOne(`${EMR}/r1/finish-check`).flush(check({ effects: { profile: physioBlock({ courseId: 'c1', sessionNo: 3, packageSize: 6, nextSessionNo: 4 }) } }));
      fixture.detectChanges();
      expect(text('#finish-title')).toBe('Complete session 3?');
      expect(text('[data-testid=finish-profile]')).toContain('Session 3 of 6 will be marked done');
      expect(text('[data-testid=finish-physio] legend')).toBe('Book session 4');
      expect(flow.physioSlot()).toEqual({ date: '2026-11-18', time: '17:00' });
      const slots = Array.from(el.querySelectorAll<HTMLButtonElement>('[data-act=pick-slot]'));
      expect(slots).toHaveLength(2);
      expect(confirm().textContent!.trim()).toBe('Confirm');
    });
  });
  describe('profile lines and prints (ADR-025 D7, D19)', () => {
    function withProfile(profile: 'og' | 'paeds' | 'onco' | 'surgery' | 'dental'): EmrRecordView {
      const view = record();
      view.layout = { ...view.layout, starterKey: profile };
      view.specialty = { profile } as never;
      return view;
    }

    it('dental: the charge of a done row is shown once, in its row, and the services list holds the services only', () => {
      boot(
        withProfile('dental'),
        check({
          effects: {
            profile: dentalBlock({
              doneRows: [{ rowId: 'p1', name: '36 · Root canal treatment', sitting: { no: 2, of: 3 }, amountPaise: 150000 }],
              estimateTotalPaise: 450000,
              nextSittingInterval: 'D7',
              nextSittingDate: '2026-11-20',
              nextSittingSlot: { date: '2026-11-20', time: '10:00 AM' },
            }),
            billLines: [
              { name: 'Root canal treatment', qty: 1, amountPaise: 150000, state: 'NEW', source: 'DENTAL_PLAN' },
              { name: 'IOPA X-ray', qty: 1, amountPaise: 20000, state: 'NEW', source: 'SERVICE' },
            ],
            billTotalPaise: 170000,
          },
        }),
      );
      expect(text('[data-testid=finish-profile]')).toBe('• 36 · Root canal treatment — sitting 2 of 3 · ₹1,500');
      expect(text('[data-testid=finish-bill] b')).toBe('Services');
      expect(Array.from(el.querySelectorAll('[data-testid=finish-bill] li')).map((e) => e.textContent!.trim())).toEqual(['IOPA X-ray · ₹200']);
      expect(text('[data-testid=finish-total]')).toBe('Total ₹1,700');
      expect(text('[data-testid=finish-next-sitting]')).toBe('Next sitting: 7 days · 20 Nov 2026, 10:00 AM');
      expect(el.textContent).not.toContain('₹1,500 ·');
    });

    it('Obstetrics and Paediatrics: "Complete this visit?" with the lines of the mockup dialogs', () => {
      boot(withProfile('og'), check({ effects: { profile: ogBlock({ ancVisitNo: 5, gestationalAge: { weeks: 27, days: 1 }, bpSystolic: 124, bpDiastolic: 80, hbGdl: 9.8, nextAncWeeks: 30 }) } }));
      expect(text('#finish-title')).toBe('Complete this visit?');
      expect(text('[data-testid=finish-profile]')).toContain('ANC visit 5 at 27w 1d · BP 124/80 · Hb 9.8');
      expect(text('[data-testid=finish-profile]')).toContain('Next ANC at 30 weeks');
      expect(confirm().textContent!.trim()).toBe('Complete & call next');
    });

    it('Paediatrics: weight with its note, vaccines given today and the next due vaccine', () => {
      boot(
        withProfile('paeds'),
        check({ effects: { profile: paedsBlock({ weightKg: 10.2, weightNote: 'Within the usual band', vaccinesGivenToday: [{ vaccineKey: 'm', label: 'MMR 2' }], nextDue: { vaccineKey: 'd', label: 'DTwP booster 1', groupLabel: '18 months' } }) } }),
      );
      const t = text('[data-testid=finish-profile]');
      expect(t).toContain('Weight 10.2 kg (Within the usual band)');
      expect(t).toContain('Vaccines given today: MMR 2');
      expect(t).toContain('Next due: DTwP booster 1 (18 months)');
    });

    it('oncology: the cycle and next chemo lines and the footer naming the prints', () => {
      boot(
        withProfile('onco'),
        check({
          effects: {
            profile: oncoBlock({ cycleNo: 3, decision: 'GIVE_AS_PLANNED', nextCycleDate: '2026-12-03', nextCycleNo: 4, nextCycleLabel: 'NEXT_CHEMO' }),
            prints: ['PRESCRIPTION', 'CYCLE_RECORD', 'CHEMO_CALENDAR'],
          },
        }),
      );
      expect(Array.from(el.querySelectorAll('[data-testid=finish-profile] .pline')).map((e) => e.textContent!.trim())).toEqual([
        'Cycle 3 · Give as planned',
        'Next chemo Thu 3 Dec 2026 · cycle 4',
      ]);
      expect(text('[data-testid=finish-prints]')).toBe('Cycle record, take-home medicines and calendar will print.');
    });

    it('surgery: the OT booking line and "Admission letter and pre-op instructions will print."', () => {
      boot(
        withProfile('surgery'),
        check({
          effects: {
            profile: surgeryBlock({ bookState: 'BOOKED', procedureText: 'Lap cholecystectomy', otDate: '2026-11-20', slotText: 'OT 2 · 10:00' }),
            prints: ['PRESCRIPTION', 'ADMISSION_LETTER', 'PREOP_SHEET'],
          },
        }),
      );
      expect(text('[data-testid=finish-profile]')).toBe('OT booking: Lap cholecystectomy · 20 Nov 2026, OT 2 · 10:00');
      expect(text('[data-testid=finish-prints]')).toBe('Admission letter and pre-op instructions will print.');
    });

    it('names the prescription language only when the server lists the prescription', () => {
      boot(record(), check({ effects: { prints: ['PRESCRIPTION'] } }));
      expect(text('[data-testid=finish-print]')).toContain('Prescription prints in English');
    });
  });
});
