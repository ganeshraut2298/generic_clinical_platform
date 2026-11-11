import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ReplaySubject } from 'rxjs';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { AdmissionDialogComponent } from './admission/admission-dialog.component';
import { DischargeSheetComponent } from './discharge/discharge-sheet.component';
import { EmrSheetComponent } from './emr/emr-sheet.component';
import { sheetOf } from './emr/emr.fixtures';
import { AdmissionDetail } from './ipd.model';
import { ScopeRefusal, ScopeRefusals } from './ipd-scope-refusal';
import { GiveSheetComponent, GiveSheetData } from './mar/give-sheet.component';
import { MarTabComponent } from './mar/mar-tab.component';
import { ORDER_PCM, marOf } from './mar/mar.fixtures';
import { NursingSheetComponent } from './nursing/nursing-sheet.component';
import { NursingSheet } from './nursing/nursing.model';
import { MedicineRoundComponent } from './safety/medicine-round.component';
import { AS_OF, ROUND_DUE, roundOf } from './safety/safety.fixtures';

/**
 * SAH-34 AC 38, 39: a sheet that meets 403 `outside-scope` reports it (ids only), clears the patient's data from the
 * screen and from its memory, and closes. It is not `forbidden` or `module-off`: nothing locks the page.
 */
const IPD = '/api/v1/ipd';

const outsideScope = {
  type: 'https://sahaj.in/problems/outside-scope',
  title: 'Outside your scope',
  status: 403,
  detail: 'This patient is not in your wards.',
};
const refuse = (req: TestRequest): void => req.flush(outsideScope, { status: 403, statusText: 'Forbidden' });

const SECRET_NAME = 'Asha Bendre';
const NURSING: NursingSheet = {
  admissionId: 'a1',
  patientName: SECRET_NAME,
  bedCode: '202',
  status: 'ADMITTED',
  state: 'on',
  canRecord: true,
  readOnlyReason: null,
  asOf: '2026-11-05T10:00:00Z',
  latest: null,
  vitalsDue: false,
  doseCount: 0,
  recentNotes: [],
};

const DETAIL: AdmissionDetail = {
  id: 'a1',
  ipNumber: 'IP/26-27/0042',
  patientId: 'p1',
  patientName: SECRET_NAME,
  age: 56,
  sex: 'F',
  doctorName: 'Dr. Meera Kulkarni',
  bedCode: '101-A',
  categoryName: 'General',
  wardName: 'General ward',
  admissionType: 'planned',
  payer: 'Self',
  dayNumber: 2,
  depositPaise: 400000,
  minimumDepositPaise: 1000000,
  runningBillPaise: 265000,
  depositLow: false,
  depositState: 'DUE',
  admittedAt: '2026-10-24T05:35:00Z',
  reason: 'Chest pain',
  canCollectDeposit: true,
  canAddCharge: false,
  bill: {
    billDays: 2,
    bedDayCut: 'midnight',
    categoryName: 'General',
    multiplier: 1,
    lines: [{ name: 'Bed · General', unitPaise: 80000, qty: 2, amountPaise: 160000 }],
    totalPaise: 265000,
    paidPaise: 400000,
    depositPercent: 66,
    alertPercent: 80,
  },
  deposits: [],
};

describe('SAH-34: sheets meeting 403 outside-scope', () => {
  let http: HttpTestingController;
  let bus: ScopeRefusals;
  let reported: ScopeRefusal[];
  let ref: { close: ReturnType<typeof vi.fn>; afterOpened?: () => ReplaySubject<void>; disableClose?: boolean };
  let snack: ReturnType<typeof vi.fn>;

  function configure(extra: object[] = []): void {
    ref = { close: vi.fn(), afterOpened: () => new ReplaySubject<void>(1) };
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialogRef, useValue: ref },
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
        ...extra,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    bus = TestBed.inject(ScopeRefusals);
    reported = [];
    bus.refusals.subscribe((r) => reported.push(r));
  }
  afterEach(() => http.verify());

  async function settle(f: ComponentFixture<unknown>): Promise<void> {
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
  }
  const text = (f: ComponentFixture<unknown>) => (f.nativeElement as HTMLElement).textContent ?? '';

  describe('Nursing sheet', () => {
    function mount(): ComponentFixture<NursingSheetComponent> {
      configure([{ provide: MAT_DIALOG_DATA, useValue: { admissionId: 'a1' } }]);
      const f = TestBed.createComponent(NursingSheetComponent);
      f.detectChanges();
      return f;
    }

    it('reports a refused read, closes, and shows no patient data and no Try again', async () => {
      const f = mount();
      refuse(http.expectOne(`${IPD}/admissions/a1/nursing`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: false }]);
      expect(ref.close).toHaveBeenCalledWith(false);
      expect(text(f)).not.toContain('Try again');
      expect(text(f)).not.toContain(SECRET_NAME);
    });

    it('reports a refused save as a write, clears the typed values and the sheet, and closes', async () => {
      const f = mount();
      http.expectOne(`${IPD}/admissions/a1/nursing`).flush(NURSING);
      await settle(f);
      const root = f.nativeElement as HTMLElement;
      const set = (id: string, value: string) => {
        const input = root.querySelector<HTMLInputElement>(id)!;
        input.value = value;
        input.dispatchEvent(new Event('input'));
      };
      set('#nv-bp', '120/80');
      set('#nv-pulse', '80');
      set('#nv-spo2', '98');
      set('#nv-temp', '37.0');
      set('#nv-rr', '16');
      set('#nv-note', 'Rested well');
      await settle(f);
      root.querySelector<HTMLButtonElement>('[data-testid=nursing-save]')!.click();
      await settle(f);
      refuse(http.expectOne(`${IPD}/admissions/a1/vitals`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: true }]);
      expect(ref.close).toHaveBeenCalledWith(false);
      expect(text(f)).not.toContain(SECRET_NAME);
      expect(text(f)).not.toContain('Rested well');
      expect(f.componentInstance['form'].getRawValue().note).toBe('');
      expect(snack).not.toHaveBeenCalled();
    });

    it('closes and clears when a refusal for the same patient comes from elsewhere (the Medicines tab)', async () => {
      const f = mount();
      http.expectOne(`${IPD}/admissions/a1/nursing`).flush(NURSING);
      await settle(f);
      bus.report('a2', false);
      await settle(f);
      expect(ref.close).not.toHaveBeenCalled();
      bus.report('a1', true);
      await settle(f);
      expect(ref.close).toHaveBeenCalledWith(false);
      expect(text(f)).not.toContain(SECRET_NAME);
    });
  });

  describe('Medicines tab (MAR)', () => {
    it('reports a refused read and keeps no medicines on screen', async () => {
      configure([{ provide: MatDialog, useValue: { open: vi.fn() } }]);
      const f = TestBed.createComponent(MarTabComponent);
      f.componentRef.setInput('admissionId', 'a1');
      f.detectChanges();
      refuse(http.expectOne(`${IPD}/admissions/a1/mar`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: false }]);
      expect(text(f)).not.toContain('Try again');
    });
  });

  describe('Give sheet', () => {
    function mount(): ComponentFixture<GiveSheetComponent> {
      const mar = marOf();
      const data: GiveSheetData = { mar, order: ORDER_PCM, slot: ORDER_PCM.slots[0] };
      configure([{ provide: MAT_DIALOG_DATA, useValue: data }]);
      const f = TestBed.createComponent(GiveSheetComponent);
      f.detectChanges();
      return f;
    }

    it('reports a refused dose as a write, records nothing, and closes with no result', async () => {
      const f = mount();
      const root = f.nativeElement as HTMLElement;
      const box = root.querySelector<HTMLInputElement>('#sf-id')!;
      box.value = 'PT-00042';
      box.dispatchEvent(new Event('input'));
      const said = root.querySelector<HTMLInputElement>('#sf-said')!;
      said.checked = true;
      said.dispatchEvent(new Event('change'));
      await settle(f);
      root.querySelector<HTMLButtonElement>('[data-act=sfgiveok]')!.click();
      await settle(f);
      refuse(http.expectOne(`${IPD}/medicine-orders/o1/doses`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: marOf().admissionId, write: true }]);
      expect(ref.close).toHaveBeenCalledWith();
      expect(snack).not.toHaveBeenCalled();
    });
  });

  describe('EMR sheet', () => {
    function mount(): ComponentFixture<EmrSheetComponent> {
      configure([{ provide: MAT_DIALOG_DATA, useValue: { admissionId: 'a1' } }]);
      const f = TestBed.createComponent(EmrSheetComponent);
      f.detectChanges();
      return f;
    }

    it('reports a refused read and does not show the role message "Clinical details are hidden"', async () => {
      const f = mount();
      refuse(http.expectOne(`${IPD}/admissions/a1/emr`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: false }]);
      expect(ref.close).toHaveBeenCalled();
      expect(f.componentInstance['hidden']()).toBe(false);
    });

    it('reports a refused order as a write and clears the chart', async () => {
      const f = mount();
      http.expectOne(`${IPD}/admissions/a1/emr`).flush(sheetOf());
      await settle(f);
      (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[data-act=sfordchk]')!.click();
      await settle(f);
      refuse(http.expectOne(`${IPD}/admissions/a1/medicine-orders`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: true }]);
      expect(ref.close).toHaveBeenCalled();
      expect(text(f)).not.toContain('Asha Patil');
      expect(snack).not.toHaveBeenCalled();
    });

    it('reports a refused Stop (a call by order id) as a write', async () => {
      const f = mount();
      http.expectOne(`${IPD}/admissions/a1/emr`).flush(sheetOf());
      await settle(f);
      const root = f.nativeElement as HTMLElement;
      root.querySelector<HTMLButtonElement>('[data-act=sfstop]')!.click();
      await settle(f);
      root.querySelector<HTMLButtonElement>('[data-act=sfstopok]')!.click();
      await settle(f);
      refuse(http.expectOne(`${IPD}/medicine-orders/o1/stop`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: true }]);
      expect(ref.close).toHaveBeenCalled();
      expect(snack).not.toHaveBeenCalled();
    });
  });

  describe('Admission sheet', () => {
    function mount(): ComponentFixture<AdmissionDialogComponent> {
      configure([
        { provide: MAT_DIALOG_DATA, useValue: { id: 'a1' } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ]);
      const f = TestBed.createComponent(AdmissionDialogComponent);
      f.detectChanges();
      return f;
    }

    it('reports a refused read, closes without a change, and shows no bill', async () => {
      const f = mount();
      refuse(http.expectOne(`${IPD}/admissions/a1`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: false }]);
      expect(ref.close).toHaveBeenCalledWith(false);
      expect(text(f)).not.toContain('Try again');
      expect(text(f)).not.toContain(SECRET_NAME);
    });

    it('reports a refused deposit as a write; the bill and the typed amount leave the screen', async () => {
      const f = mount();
      http.expectOne(`${IPD}/admissions/a1`).flush(DETAIL);
      await settle(f);
      const root = f.nativeElement as HTMLElement;
      const amount = root.querySelector<HTMLInputElement>('#ad-amount')!;
      amount.value = '5000';
      amount.dispatchEvent(new Event('input'));
      await settle(f);
      Array.from(root.querySelectorAll<HTMLButtonElement>('button'))
        .find((b) => b.textContent!.includes('Collect'))!
        .click();
      await settle(f);
      refuse(http.expectOne(`${IPD}/admissions/a1/deposits`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: true }]);
      expect(ref.close).toHaveBeenCalledWith(false);
      expect(text(f)).not.toContain(SECRET_NAME);
      expect(f.componentInstance['amount'].value).toBe('');
      expect(snack).not.toHaveBeenCalled();
    });

    it('closes when a sheet opened over it (Nursing, EMR) reports a refusal for the same admission', async () => {
      const f = mount();
      http.expectOne(`${IPD}/admissions/a1`).flush(DETAIL);
      await settle(f);
      bus.report('a1', false);
      await settle(f);
      expect(ref.close).toHaveBeenCalledWith(false);
      expect(text(f)).not.toContain(SECRET_NAME);
    });
  });

  describe('Discharge sheet', () => {
    function mount(): ComponentFixture<DischargeSheetComponent> {
      configure();
      const f = TestBed.createComponent(DischargeSheetComponent);
      f.componentRef.setInput('admissionId', 'a1');
      f.detectChanges();
      return f;
    }

    it('reports a refused read; the admission sheet around it clears and closes', async () => {
      const f = mount();
      refuse(http.expectOne(`${IPD}/admissions/a1/discharge`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a1', write: false }]);
      expect(text(f)).not.toContain('Try again');
    });
  });

  describe('Medicine round Give', () => {
    it('reports a refused MAR read for the row patient, shows no toast of failure, and keeps the round', async () => {
      configure([{ provide: MatDialog, useValue: { open: vi.fn() } }]);
      const f = TestBed.createComponent(MedicineRoundComponent);
      f.componentRef.setInput('round', roundOf({ rows: [ROUND_DUE] }));
      f.componentRef.setInput('asOf', AS_OF);
      await settle(f);
      (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[data-testid=round-give]')!.click();
      await settle(f);
      refuse(http.expectOne(`${IPD}/admissions/a2/mar`));
      await settle(f);
      expect(reported).toEqual([{ admissionId: 'a2', write: false }]);
      expect(snack).not.toHaveBeenCalled();
    });
  });
});
