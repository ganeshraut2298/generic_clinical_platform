import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ReplaySubject, of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { DischargeSheet } from '../discharge.model';
import { AdmissionBedOption, AdmissionDetail, AdmissionOptions, ChargeService } from '../ipd.model';
import { AdmissionsApi } from '../admissions.api';
import { AdmissionDialogComponent } from './admission-dialog.component';

const URL = '/api/v1/ipd/admissions/a1';
const SERVICES_URL = '/api/v1/ipd/charge-services';
const OPTIONS_URL = '/api/v1/ipd/admission-options';

const bed = (id: string, code: string, categoryKey: AdmissionBedOption['categoryKey'], categoryName: string, ratePaise: number): AdmissionBedOption => ({
  id,
  code,
  wardName: 'Ward',
  categoryKey,
  categoryName,
  ratePaise,
});

/** In the order the server sends them (category, room, code); the sheet does not sort them again. */
const FREE_BEDS: AdmissionBedOption[] = [
  bed('b-gen10', '101-10', 'gen', 'General', 80000),
  bed('b-gen1', '101-B', 'gen', 'General', 80000),
  bed('b-gen2', '101-C', 'gen', 'General', 80000),
  bed('b-semi', '104-A', 'semi', 'Semi-private', 120000),
  bed('b-pvt', '201-A', 'pvt', 'Private', 200000),
  bed('b-icu', 'ICU-1', 'icu', 'ICU', 400000),
];

function optionsOf(beds: AdmissionBedOption[]): AdmissionOptions {
  return {
    doctors: [],
    beds,
    rules: {
      depositPaise: { planned: 0, emergency: 0, daycare: 0, maternity: 0 },
      categoryAddPaise: { gen: 0, semi: 0, pvt: 0, icu: 0 },
      emergencyWithoutDeposit: true,
      alertPercent: 80,
      confirmed: true,
    },
  };
}

const SERVICES: ChargeService[] = [
  { key: 'visit', name: 'Extra doctor visit', basePaise: 50000 },
  { key: 'cbc', name: 'CBC', basePaise: 30000 },
];

const DETAIL: AdmissionDetail = {
  id: 'a1',
  ipNumber: 'IP/26-27/0042',
  patientId: 'p1',
  patientName: 'Asha Patil',
  age: 56,
  sex: 'F',
  mobile: '9876543210',
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
  canAddCharge: true,
  bill: {
    billDays: 2,
    bedDayCut: 'midnight',
    categoryName: 'General',
    multiplier: 1.5,
    lines: [
      { name: 'Bed · General', unitPaise: 80000, qty: 2, amountPaise: 160000 },
      { name: 'Nursing care', unitPaise: 30000, qty: 2, amountPaise: 60000 },
      { name: 'CBC', unitPaise: 45000, qty: 1, amountPaise: 45000 },
    ],
    totalPaise: 265000,
    paidPaise: 400000,
    depositPercent: 66,
    alertPercent: 80,
  },
  deposits: [
    { id: 'd1', amountPaise: 400000, mode: 'UPI', collectedAt: '2026-10-24T05:35:00Z', collectedByName: 'Pooja Gaikwad' },
  ],
};

describe('AdmissionDialogComponent', () => {
  let fixture: ComponentFixture<AdmissionDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn>; afterOpened: () => ReplaySubject<void> };
  /** The dialog finished opening (Material's afterOpened, a replaying subject). */
  let opened: ReplaySubject<void>;
  let snack: ReturnType<typeof vi.fn>;

  async function setup(
    detail: AdmissionDetail | null = DETAIL,
    free: AdmissionBedOption[] = FREE_BEDS,
  ): Promise<void> {
    opened = new ReplaySubject<void>(1);
    dialogRef = { close: vi.fn(), afterOpened: () => opened };
    snack = vi.fn();
    await TestBed.configureTestingModule({
      imports: [AdmissionDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: { id: 'a1' } },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatSnackBar, useValue: { open: snack } },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdmissionDialogComponent);
    fixture.detectChanges();
    if (detail) {
      http.expectOne(URL).flush(detail);
      if (detail.canAddCharge) {
        http.expectOne(SERVICES_URL).flush(SERVICES);
      }
      if (detail.canTransfer) {
        http.expectOne(OPTIONS_URL).flush(optionsOf(free));
      }
      await settle();
    }
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent ?? '';
  const amount = () => el().querySelector<HTMLInputElement>('#ad-amount')!;
  const btn = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === label)!;

  async function type(value: string) {
    amount().value = value;
    amount().dispatchEvent(new Event('input'));
    await settle();
  }

  afterEach(() => http.verify());

  const squash = (node: Element | null) => (node?.innerHTML ?? '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();

  it('shows the summary strip, reason and doctor', async () => {
    await setup();
    expect(text()).toContain('Asha Patil');
    expect(text()).toContain('56 F');
    expect(text()).toContain('IP/26-27/0042');
    expect(text()).toContain('9876543210');
    const tiles = Array.from(el().querySelectorAll('.ipsum > div')).map(squash);
    expect(tiles).toEqual([
      'Bed 101-A General · General ward',
      'Day 2 Planned',
      'Running bill ₹2,650 66% of deposit',
      'Deposit ₹4,000 ₹1,350 left',
    ]);
    expect(text()).toContain('Chest pain');
    expect(text()).toContain('Dr. Meera Kulkarni');
    expect(el().querySelector('.notice.crit')).toBeNull();
    expect(el().querySelector('.notice.info')).toBeNull();
  });

  it('shows the running bill with its note, lines, total and deposit lines', async () => {
    await setup();
    const panel = el().querySelector('.ipbill')!;
    expect(squash(panel.querySelector('.ipbh'))).toBe(
      'Running bill Bed day counted at midnight · General rate ×1.5 on services',
    );
    const rows = Array.from(panel.querySelectorAll('.ipbl')).map(squash);
    expect(rows).toEqual([
      'Bed · General 2 × ₹800 ₹1,600',
      'Nursing care 2 × ₹300 ₹600',
      'CBC ₹450',
      'Total so far ₹2,650',
      'Deposit · UPI · 24 Oct 2026 − ₹4,000',
    ]);
  });

  it('writes half-rupee lines exactly and the lines add up to the total (Semi-private x1.25)', async () => {
    await setup({
      ...DETAIL,
      runningBillPaise: 328750,
      bill: {
        ...DETAIL.bill,
        multiplier: 1.25,
        categoryName: 'Semi-private',
        lines: [
          { name: 'Bed · Semi-private', unitPaise: 80000, qty: 2, amountPaise: 160000 },
          { name: 'Dressing', unitPaise: 43750, qty: 3, amountPaise: 131250 },
          { name: 'Injection', unitPaise: 18750, qty: 2, amountPaise: 37500 },
        ],
        totalPaise: 328750,
      },
    });
    const rows = Array.from(el().querySelectorAll('.ipbl')).map(squash);
    expect(rows.slice(0, 4)).toEqual([
      'Bed · Semi-private 2 × ₹800 ₹1,600',
      'Dressing 3 × ₹437.50 ₹1,312.50',
      'Injection 2 × ₹187.50 ₹375',
      'Total so far ₹3,287.50',
    ]);
    const tiles = Array.from(el().querySelectorAll('.ipsum > div')).map(squash);
    expect(tiles[2]).toBe('Running bill ₹3,287.50 66% of deposit');
    expect(tiles[3]).toBe('Deposit ₹4,000 ₹712.50 left');
    const options = Array.from(el().querySelector<HTMLSelectElement>('#ad-charge')!.options).map(squash);
    expect(options).toEqual(['Extra doctor visit · ₹625', 'CBC · ₹375']);
  });

  it('shows the over amount with paise when the bill is above the deposit', async () => {
    await setup({ ...DETAIL, depositPaise: 200000, bill: { ...DETAIL.bill, totalPaise: 218750 } });
    expect(squash(el().querySelector('.ipsum .over'))).toBe('₹187.50 over');
  });

  it('reads the Day in the strip from dayNumber, the same field as the list and the tile', async () => {
    await setup({ ...DETAIL, dayNumber: 4 });
    const tiles = Array.from(el().querySelectorAll('.ipsum > div')).map(squash);
    expect(tiles[1]).toBe('Day 4 Planned');
  });

  it('shows the same Day under the 24-hour cut, where dayNumber equals the billed bed days', async () => {
    await setup({ ...DETAIL, dayNumber: 2, bill: { ...DETAIL.bill, billDays: 2, bedDayCut: '24h' } });
    expect(squash(el().querySelectorAll('.ipsum > div')[1])).toBe('Day 2 Planned');
  });

  it('gives the running bill table column headers and keeps the heading outside it', async () => {
    await setup();
    const table = el().querySelector('[role=table]')!;
    expect(Array.from(table.querySelectorAll('[role=columnheader]')).map(squash)).toEqual([
      'Item',
      'Quantity and price',
      'Amount',
    ]);
    expect(table.querySelector('.ipbh')).toBeNull();
    expect(table.querySelectorAll(':scope > [role=row]').length).toBe(1 + 3 + 1 + 1);
  });

  it('leaves out the multiplier note when the multiplier is 1 and names the 24-hour rule', async () => {
    await setup({ ...DETAIL, bill: { ...DETAIL.bill, multiplier: 1, bedDayCut: '24h' } });
    expect(squash(el().querySelector('.ipbh small'))).toBe('Bed day counted every 24 hours');
  });

  it('warns when the deposit is low, with the percent and the alert share', async () => {
    await setup({
      ...DETAIL,
      depositLow: true,
      runningBillPaise: 340000,
      bill: { ...DETAIL.bill, totalPaise: 340000, depositPercent: 85 },
    });
    const notice = el().querySelector('.notice.crit')!;
    expect(squash(notice)).toBe(
      'The bill has reached 85% of the deposit (alert at 80%). Ask the relatives for a further deposit.',
    );
    expect(notice.querySelector('app-icon')).not.toBeNull();
  });

  it('says 100% when there is no deposit, and no deposit yet in the strip', async () => {
    await setup({
      ...DETAIL,
      depositLow: true,
      depositPaise: 0,
      deposits: [],
      bill: { ...DETAIL.bill, depositPercent: null },
    });
    expect(el().querySelector('.notice.crit')!.textContent).toContain('reached 100% of the deposit');
    expect(text()).toContain('No deposit yet');
    expect(text()).toContain('₹2,650 over');
  });

  it('shows the over amount in the critical colour when the bill is above the deposit', async () => {
    await setup({ ...DETAIL, depositPaise: 200000, bill: { ...DETAIL.bill, depositPercent: 133 } });
    expect(squash(el().querySelector('.ipsum .over'))).toBe('₹650 over');
  });

  it('shows the insurance notice and no deposit alert for an insurance patient', async () => {
    await setup({ ...DETAIL, payer: 'Insurance', depositState: 'NOT_NEEDED', minimumDepositPaise: undefined });
    expect(el().querySelector('.notice.info')!.textContent).toContain(
      'Insurance patient · pre-authorisation pending with the insurer. Deposit alerts are paused.',
    );
    expect(el().querySelector('.notice.crit')).toBeNull();
  });

  it('offers the services with their price after the multiplier and adds the chosen one', async () => {
    await setup();
    const select = el().querySelector<HTMLSelectElement>('#ad-charge')!;
    expect(Array.from(select.options).map(squash)).toEqual(['Extra doctor visit · ₹750', 'CBC · ₹450']);
    select.value = 'cbc';
    select.dispatchEvent(new Event('change'));
    await settle();
    btn('Add').click();
    const req = http.expectOne(`${URL}/charges`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ serviceKey: 'cbc' });
    const updated: AdmissionDetail = {
      ...DETAIL,
      bill: {
        ...DETAIL.bill,
        totalPaise: 310000,
        lines: [...DETAIL.bill.lines.slice(0, 2), { name: 'CBC', unitPaise: 45000, qty: 2, amountPaise: 90000 }],
      },
    };
    req.flush(updated);
    await settle();
    expect(snack).toHaveBeenCalledWith("CBC added to Asha Patil's bill", 'Close', expect.anything());
    expect(text()).toContain('2 × ₹450');
    expect(text()).toContain('Total so far');
    expect(text()).toContain('₹3,100');
    btn('Close').click();
    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });

  it('adds the first service when the choice is not changed', async () => {
    await setup();
    btn('Add').click();
    const req = http.expectOne(`${URL}/charges`);
    expect(req.request.body).toEqual({ serviceKey: 'visit' });
    req.flush(DETAIL);
  });

  it('shows the server message when the charge is refused', async () => {
    await setup();
    btn('Add').click();
    http.expectOne(`${URL}/charges`).flush(
      { type: 'not-admitted', title: 'Not admitted', status: 409, detail: 'This patient is no longer admitted.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(el().querySelector('.ipact .notice.err')!.textContent).toContain('This patient is no longer admitted.');
    expect(snack).not.toHaveBeenCalled();
    expect(btn('Add').disabled).toBe(false);
  });

  it('hides Add a charge and does not load services when the user cannot add a charge', async () => {
    await setup({ ...DETAIL, canAddCharge: false });
    expect(el().querySelector('#ad-charge')).toBeNull();
    expect(text()).not.toContain('Add a charge');
  });

  it('shows Print interim bill disabled with the reason', async () => {
    await setup();
    const print = btn('Print interim bill');
    expect(print.disabled).toBe(true);
    expect(print.getAttribute('aria-describedby')).toBe('ad-print-hint');
    expect(el().querySelector('#ad-print-hint')!.textContent).toContain('Needs printing, not in this phase');
    expect(text()).not.toContain('Move to another bed');
    expect(text()).not.toContain('Start discharge');
  });

  it('offers the shortfall as the amount when the deposit is due', async () => {
    await setup();
    expect(amount().value).toBe('6000');
    expect(btn('UPI').getAttribute('aria-pressed')).toBe('true');
  });

  it('collects a deposit in paise, updates the sheet and tells the page on close', async () => {
    await setup();
    await type('5,000');
    btn('Cash').click();
    await settle();
    btn('Collect').click();
    const req = http.expectOne(`${URL}/deposits`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ amountPaise: 500000, mode: 'Cash' });
    const updated: AdmissionDetail = {
      ...DETAIL,
      depositPaise: 900000,
      deposits: [
        ...DETAIL.deposits,
        { id: 'd2', amountPaise: 500000, mode: 'Cash', collectedAt: '2026-10-25T05:35:00Z', collectedByName: 'Pooja Gaikwad' },
      ],
    };
    req.flush(updated);
    await settle();
    expect(snack).toHaveBeenCalledWith("₹5,000 deposit received from Asha Patil's family", 'Close', expect.anything());
    expect(text()).toContain('₹9,000');
    expect(amount().value).toBe('');
    btn('Close').click();
    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });

  it('closes with false when nothing was collected', async () => {
    await setup();
    btn('Close').click();
    expect(dialogRef.close).toHaveBeenCalledWith(false);
  });

  it('does not collect an amount below ₹1, with paise or above ₹10,00,000', async () => {
    await setup();
    for (const bad of ['0', '12.5', '1000001', 'abc']) {
      await type(bad);
      expect(btn('Collect').disabled).toBe(true);
    }
    expect(text()).toContain('Enter whole rupees from ₹1 to ₹10,00,000');
    await type('');
    expect(btn('Collect').disabled).toBe(true);
    await type('1000000');
    expect(btn('Collect').disabled).toBe(false);
  });

  it('shows the server message when the deposit is refused', async () => {
    await setup();
    btn('Collect').click();
    http.expectOne(`${URL}/deposits`).flush(
      { type: 'ipd-readonly', title: 'Read-only', status: 409, detail: 'IPD is read-only. Nothing can be changed.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(el().querySelector('.notice.err')!.textContent).toContain('IPD is read-only. Nothing can be changed.');
    expect(snack).not.toHaveBeenCalled();
  });

  it('hides Collect deposit when the user cannot collect', async () => {
    await setup({ ...DETAIL, canCollectDeposit: false });
    expect(el().querySelector('#ad-amount')).toBeNull();
    expect(text()).not.toContain('Collect deposit');
  });

  it('leaves the amount empty when the deposit is not due', async () => {
    await setup({ ...DETAIL, payer: 'Insurance', depositState: 'NOT_NEEDED', minimumDepositPaise: undefined });
    expect(amount().value).toBe('');
  });

  it('shows the error with a retry when the admission cannot be loaded', async () => {
    await setup(null);
    http.expectOne(URL).flush(
      { type: 'not-found', title: 'Not found', status: 404, detail: 'That admission was not found.' },
      { status: 404, statusText: 'Not Found' },
    );
    await settle();
    expect(el().querySelector('[role=alert]')!.textContent).toContain('That admission was not found.');
    btn('Try again').click();
    http.expectOne(URL).flush(DETAIL);
    http.expectOne(SERVICES_URL).flush(SERVICES);
    await settle();
    expect(text()).toContain('Asha Patil');
  });

  describe('Move to another bed (SAH-26)', () => {
    const MOVABLE: AdmissionDetail = {
      ...DETAIL,
      canTransfer: true,
      transferRules: { upgradeNeedsOwner: true, newRateFrom: 'same' },
    };
    const select = () => el().querySelector<HTMLSelectElement>('#ad-bed')!;
    const labels = () => Array.from(select().options).map(squash);
    const move = () => btn('Move');
    const movedTo = (code: string, categoryName: string): AdmissionDetail => ({
      ...MOVABLE,
      bedCode: code,
      categoryName,
    });
    const problem = (status: number, type: string, detail: string) =>
      [{ type, title: type, status, detail }, { status, statusText: 'Error' }] as const;

    it('shows the row after Add a charge with the Available beds in the order the server sends', async () => {
      await setup(MOVABLE);
      expect(text()).toContain('Move to another bed');
      expect(labels()).toEqual([
        '101-10 · General · ₹800',
        '101-B · General · ₹800',
        '101-C · General · ₹800',
        '104-A · Semi-private · ₹1,200',
        '201-A · Private · ₹2,000',
        'ICU-1 · ICU · ₹4,000',
      ]);
      expect(move().disabled).toBe(false);
      expect(move().querySelector('app-icon')).not.toBeNull();
      const labelsInOrder = Array.from(el().querySelectorAll('.ipact label')).map(squash);
      expect(labelsInOrder).toEqual(['Collect deposit', 'Add a charge', 'Move to another bed']);
    });

    it('keeps the order the server sends, even when it is not alphabetical (room creation order)', async () => {
      const serverOrder = [
        bed('b-a', '202-B', 'gen', 'General', 80000),
        bed('b-b', '101-A', 'gen', 'General', 80000),
        bed('b-c', 'ICU-1', 'icu', 'ICU', 400000),
        bed('b-d', '102-A', 'icu', 'ICU', 400000),
      ];
      await setup(MOVABLE, serverOrder);
      expect(labels()).toEqual([
        '202-B · General · ₹800',
        '101-A · General · ₹800',
        'ICU-1 · ICU · ₹4,000',
        '102-A · ICU · ₹4,000',
      ]);
      expect(select().value).toBe('b-a');
    });

    it('shows one option "No free bed" and disables Move when no bed is free', async () => {
      await setup(MOVABLE, []);
      expect(labels()).toEqual(['No free bed']);
      expect(select().disabled).toBe(true);
      expect(move().disabled).toBe(true);
      move().click();
      http.verify();
    });

    it('hides the row when canTransfer is false and does not load the free beds', async () => {
      await setup({ ...MOVABLE, canTransfer: false });
      expect(el().querySelector('#ad-bed')).toBeNull();
      expect(text()).not.toContain('Move to another bed');
    });

    it('hides the row when canTransfer is not sent (Read-only, other role, not admitted)', async () => {
      await setup(DETAIL);
      expect(el().querySelector('#ad-bed')).toBeNull();
      expect(btn('Move')).toBeUndefined();
    });

    it('moves to the chosen bed, reloads the sheet from the answer and tells the page on close', async () => {
      await setup(MOVABLE);
      select().value = 'b-pvt';
      select().dispatchEvent(new Event('change'));
      await settle();
      move().click();
      const req = http.expectOne(`${URL}/transfer`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ toBedId: 'b-pvt' });
      fixture.detectChanges();
      expect(move().disabled).toBe(true);
      req.flush(movedTo('201-A', 'Private'));
      http.expectOne(OPTIONS_URL).flush(optionsOf(FREE_BEDS.filter((b) => b.id !== 'b-pvt')));
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Asha Patil moved from 101-A to 201-A · Private rate from today · 101-A sent for cleaning',
        'Close',
        expect.anything(),
      );
      expect(squash(el().querySelector('.ipsum > div'))).toBe('Bed 201-A Private · General ward');
      expect(labels()).not.toContain('201-A · Private · ₹2,000');
      expect(move().disabled).toBe(false);
      btn('Close').click();
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('sends the first bed when the choice is not changed', async () => {
      await setup(MOVABLE);
      move().click();
      const req = http.expectOne(`${URL}/transfer`);
      expect(req.request.body).toEqual({ toBedId: 'b-gen10' });
      req.flush(movedTo('101-10', 'General'));
      http.expectOne(OPTIONS_URL).flush(optionsOf([]));
    });

    it('says "from tomorrow" when the rule is Next day', async () => {
      await setup({ ...MOVABLE, transferRules: { upgradeNeedsOwner: true, newRateFrom: 'next' } });
      move().click();
      http.expectOne(`${URL}/transfer`).flush(movedTo('101-10', 'General'));
      http.expectOne(OPTIONS_URL).flush(optionsOf([]));
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Asha Patil moved from 101-A to 101-10 · General rate from tomorrow · 101-A sent for cleaning',
        'Close',
        expect.anything(),
      );
    });

    it('ignores a double click: one request while the first is running', async () => {
      await setup(MOVABLE);
      move().click();
      move().click();
      const req = http.expectOne(`${URL}/transfer`);
      await settle();
      expect(move().disabled).toBe(true);
      req.flush(movedTo('101-10', 'General'));
      http.expectOne(OPTIONS_URL).flush(optionsOf([]));
    });

    it('shows the owner-approval message in the sheet, keeps it usable and re-reads the sheet', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).flush(
        ...problem(403, 'owner-approval-needed', "Moving to Private needs the owner's approval. Ask the clinic owner."),
      );
      http.expectOne(URL).flush(MOVABLE);
      await settle();
      expect(el().querySelector('.ipact .notice.err')!.textContent).toContain(
        "Moving to Private needs the owner's approval. Ask the clinic owner.",
      );
      expect(snack).not.toHaveBeenCalled();
      expect(move().disabled).toBe(false);
      expect(labels().length).toBe(6);
      btn('Close').click();
      expect(dialogRef.close).toHaveBeenCalledWith(false);
    });

    it('shows the message on bed-not-free and reloads the free beds, which no longer list that bed', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).flush(
        ...problem(409, 'bed-not-free', 'That bed is no longer free. Pick another.'),
      );
      http.expectOne(OPTIONS_URL).flush(optionsOf(FREE_BEDS.filter((b) => b.id !== 'b-gen10')));
      http.expectOne(URL).flush(MOVABLE);
      await settle();
      expect(el().querySelector('.ipact .notice.err')!.textContent).toContain(
        'That bed is no longer free. Pick another.',
      );
      expect(labels()).not.toContain('101-10 · General · ₹800');
      expect(select().value).toBe('b-gen1');
      expect(move().disabled).toBe(false);
    });

    it('shows a read-only or not-admitted refusal and re-reads the sheet', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).flush(
        ...problem(409, 'not-admitted', 'This patient is no longer admitted.'),
      );
      http.expectOne(URL).flush({ ...MOVABLE, canTransfer: false });
      await settle();
      expect(text()).not.toContain('Move to another bed');
    });

    it('shows the fallback message when the network fails, and the sheet shows the new bed when the move had happened', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).error(new ProgressEvent('error'));
      const reread = http.expectOne(URL);
      expect(el().querySelector('.ipact .notice.err')).toBeNull();
      await settle();
      expect(el().querySelector('.ipact .notice.err')!.textContent).toContain('We could not move the patient');
      expect(move().disabled).toBe(true);
      reread.flush(movedTo('101-10', 'General'));
      http.expectOne(OPTIONS_URL).flush(optionsOf(FREE_BEDS.slice(0, 2)));
      await settle();
      expect(squash(el().querySelector('.ipsum > div'))).toBe('Bed 101-10 General · General ward');
      expect(el().querySelector('.ipact .notice.err')).toBeNull();
      expect(snack).toHaveBeenCalledWith('Asha Patil is now in 101-10', 'Close', expect.anything());
      expect(move().disabled).toBe(false);
      btn('Close').click();
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('keeps Move disabled until the re-read ends; a 400 "already in this bed" then clears when the re-read shows the new bed', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).flush(
        ...problem(400, 'about:blank', 'The patient is already in this bed.'),
      );
      const reread = http.expectOne(URL);
      await settle();
      expect(el().querySelector('.ipact .notice.err')!.textContent).toContain('already in this bed');
      expect(move().disabled).toBe(true);
      move().click();
      http.expectNone(`${URL}/transfer`);
      reread.flush(movedTo('101-10', 'General'));
      http.expectOne(OPTIONS_URL).flush(optionsOf(FREE_BEDS.slice(1)));
      await settle();
      expect(el().querySelector('.ipact .notice.err')).toBeNull();
      expect(snack).toHaveBeenCalledWith('Asha Patil is now in 101-10', 'Close', expect.anything());
      expect(move().disabled).toBe(false);
    });

    it('keeps the message and enables Move when the re-read shows the same bed', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).flush(...problem(500, 'about:blank', 'Try later.'));
      const reread = http.expectOne(URL);
      await settle();
      expect(move().disabled).toBe(true);
      reread.flush(MOVABLE);
      await settle();
      expect(el().querySelector('.ipact .notice.err')!.textContent).toContain('Try later.');
      expect(snack).not.toHaveBeenCalled();
      expect(move().disabled).toBe(false);
    });

    it('enables Move again when the re-read itself fails, and keeps the message', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).error(new ProgressEvent('error'));
      http.expectOne(URL).error(new ProgressEvent('error'));
      await settle();
      expect(el().querySelector('.ipact .notice.err')!.textContent).toContain('We could not move the patient');
      expect(move().disabled).toBe(false);
    });

    it('cancels a free-bed load that is still running when a newer one starts', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).flush(...problem(409, 'bed-not-free', 'That bed is no longer free. Pick another.'));
      const first = http.expectOne(OPTIONS_URL);
      const reread = http.expectOne(URL);
      reread.flush(movedTo('101-10', 'General'));
      const second = http.expectOne(OPTIONS_URL);
      expect(first.cancelled).toBe(true);
      second.flush(optionsOf(FREE_BEDS.slice(2)));
      await settle();
      expect(labels()[0]).toBe('101-C · General · ₹800');
    });

    it('shows the free-bed load error when the beds cannot be loaded', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).flush(movedTo('101-10', 'General'));
      http.expectOne(OPTIONS_URL).error(new ProgressEvent('error'));
      await settle();
      expect(el().querySelector('.ipact .notice.err')!.textContent).toContain('We could not load the free beds');
    });

    it('lets the user move again after a failure', async () => {
      await setup(MOVABLE);
      move().click();
      http.expectOne(`${URL}/transfer`).flush(...problem(500, 'about:blank', 'Try later.'));
      http.expectOne(URL).flush(MOVABLE);
      await settle();
      move().click();
      http.expectOne(`${URL}/transfer`).flush(movedTo('101-10', 'General'));
      http.expectOne(OPTIONS_URL).flush(optionsOf([]));
    });

    it('renders segment lines in the order given, in the same style as the other lines', async () => {
      await setup({
        ...MOVABLE,
        bill: {
          ...DETAIL.bill,
          lines: [
            { name: 'Bed · General (before transfer)', unitPaise: 80000, qty: 2, amountPaise: 160000, segment: true },
            { name: 'Doctor visits · General rate', unitPaise: 50000, qty: 2, amountPaise: 100000, segment: true },
            { name: 'Bed · Private', unitPaise: 200000, qty: 1, amountPaise: 200000 },
            { name: 'Nursing care', unitPaise: 30000, qty: 3, amountPaise: 90000 },
          ],
          totalPaise: 550000,
        },
      });
      const rows = Array.from(el().querySelectorAll('.ipbl')).map(squash);
      expect(rows.slice(0, 5)).toEqual([
        'Bed · General (before transfer) 2 × ₹800 ₹1,600',
        'Doctor visits · General rate 2 × ₹500 ₹1,000',
        'Bed · Private ₹2,000',
        'Nursing care 3 × ₹300 ₹900',
        'Total so far ₹5,500',
      ]);
      const first = el().querySelectorAll('.ipbl')[0];
      expect(first.className).toBe('ipbl');
    });
  });

  describe('discharge (SAH-27)', () => {
    const DISCHARGE_URL = '/api/v1/ipd/admissions/a1/discharge';
    const READY: AdmissionDetail = {
      ...DETAIL,
      canCollectDeposit: false,
      canAddCharge: false,
      canStartDischarge: true,
      status: 'ADMITTED',
      dischargedAt: null,
      discharge: null,
      finalBill: null,
    };
    const SIGNATURE = {
      type: 'normal',
      templateKey: 'general',
      signedByName: 'Dr. Meera Kulkarni',
      signedAt: '2026-10-24T06:00:00Z',
    } as const;
    const SHEET: DischargeSheet = {
      admissionId: 'a1',
      ipNumber: 'IP/26-27/0042',
      patientName: 'Asha Patil',
      bedCode: '101-A',
      dayNumber: 2,
      approvalChain: true,
      types: [{ key: 'normal', label: 'Normal' }],
      templates: [{ key: 'general', label: 'General medicine' }],
      defaultType: 'normal',
      defaultTemplateKey: 'general',
      signature: null,
      canSign: true,
      canDischarge: true,
      canRefund: true,
      preview: {
        diagnosis: 'Chest pain',
        stayDays: 2,
        bedCode: '101-A',
        doctorName: 'Dr. Meera Kulkarni',
        doctorShortName: 'Dr. Meera Kulkarni',
        doctorSpecialty: null,
      },
      bill: { totalPaise: 265000, depositsPaise: 400000, balancePaise: -135000 },
      followUpDays: 7,
      followUpDate: '2026-10-31',
      openEscalation: false,
      signoffBeforeEscalation: false,
    };
    const DONE: AdmissionDetail = {
      ...READY,
      status: 'DISCHARGED',
      canStartDischarge: false,
      dayNumber: 2,
      runningBillPaise: 265000,
      dischargedAt: '2026-10-26T07:00:00Z',
      discharge: {
        type: 'lama',
        templateKey: 'surgical',
        signedByName: 'Dr. Meera Kulkarni',
        signedAt: '2026-10-26T06:00:00Z',
        dischargedByName: 'Pooja Gaikwad',
        dischargedAt: '2026-10-26T07:00:00Z',
        followUpDays: 7,
        followUpDate: '2026-11-02',
      },
      finalBill: {
        lines: [
          { name: 'Bed · General', unitPaise: 80000, qty: 2, amountPaise: 160000 },
          { name: 'CBC', unitPaise: 105000, qty: 1, amountPaise: 105000 },
        ],
        billDays: 2,
        totalPaise: 265000,
        depositsPaise: 400000,
        balancePaise: -135000,
        outcome: 'refunded',
        mode: 'Cash',
        settledByName: 'Pooja Gaikwad',
        settledAt: '2026-10-26T07:00:00Z',
      },
    };

    async function openSheet(sheet: DischargeSheet = SHEET): Promise<void> {
      btn('Start discharge').click();
      await settle();
      http.expectOne(DISCHARGE_URL).flush(sheet);
      await settle();
    }

    const goButton = () => el().querySelector<HTMLButtonElement>('.sheet-f .mat-mdc-unelevated-button')!;

    it('shows Start discharge in the footer, primary, with the door icon', async () => {
      await setup(READY);
      const start = btn('Start discharge');
      expect(start).toBeTruthy();
      expect(start.classList).toContain('mat-mdc-unelevated-button');
      expect(start.querySelector('app-icon')).not.toBeNull();
      expect(el().querySelector('.sheet-f')!.contains(start)).toBe(true);
    });

    it('hides Start discharge when canStartDischarge is false (read-only, role)', async () => {
      await setup({ ...READY, canStartDischarge: false });
      expect(btn('Start discharge')).toBeUndefined();
    });

    it('hides Start discharge when the server sends no flag', async () => {
      await setup({ ...DETAIL, canCollectDeposit: false, canAddCharge: false });
      expect(btn('Start discharge')).toBeUndefined();
    });

    it('opens the Discharge sheet in place and Back returns to the admission sheet', async () => {
      await setup(READY);
      await openSheet();
      expect(el().querySelector('h2')!.textContent).toBe('Discharge Asha Patil');
      expect(text()).toContain('IP/26-27/0042 · bed 101-A · day 2');
      btn('Back').click();
      await settle();
      expect(el().querySelector('h2')!.textContent).not.toContain('Discharge');
      expect(btn('Start discharge')).toBeTruthy();
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('shows the toast and closes with true once discharged', async () => {
      await setup(READY);
      await openSheet({ ...SHEET, signature: SIGNATURE });
      expect(goButton().textContent).toContain('Refund ₹1,350 and discharge');
      goButton().click();
      http.expectOne({ method: 'POST', url: DISCHARGE_URL }).flush(DONE);
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Asha Patil discharged (on request (lama)) · ₹1,350 refunded by Cash · bed 101-A sent for cleaning · follow-up on 2 Nov 2026',
        'Close',
        { duration: 8000 },
      );
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('closes with true after a signature even when the user goes Back and Close', async () => {
      await setup(READY);
      await openSheet();
      const box = el().querySelector<HTMLInputElement>('input[type=checkbox]')!;
      box.checked = true;
      box.dispatchEvent(new Event('change'));
      http.expectOne(DISCHARGE_URL + '/signoff').flush({ ...SHEET, signature: SIGNATURE });
      await settle();
      btn('Back').click();
      await settle();
      btn('Close').click();
      expect(dialogRef.close).toHaveBeenCalledWith(true);
    });

    it('reloads the admission when the patient turns out not to be admitted any more', async () => {
      await setup(READY);
      await openSheet({ ...SHEET, signature: SIGNATURE });
      goButton().click();
      http.expectOne({ method: 'POST', url: DISCHARGE_URL }).flush(
        {
          type: 'https://sahaj.in/problems/not-admitted',
          title: 'x',
          status: 409,
          detail: 'This patient is not admitted any more.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      http.expectOne(URL).flush(DONE);
      await settle();
      expect(text()).toContain('This patient is not admitted any more.');
      expect(el().querySelector<HTMLButtonElement>('.sheet-f .mat-mdc-unelevated-button')!.disabled).toBe(true);
      btn('Back').click();
      await settle();
      expect(text()).toContain('Discharged on');
      expect(btn('Start discharge')).toBeUndefined();
    });

    describe('a discharged admission', () => {
      it('shows the frozen final bill and the discharge facts, with no actions', async () => {
        await setup(DONE);
        expect(text()).toContain('Discharged on 26 Oct 2026, 12:30 PM · On request (LAMA).');
        const tiles = Array.from(el().querySelectorAll('.ipsum > div')).map(squash);
        expect(tiles[2]).toBe('Final bill ₹2,650 Frozen at discharge');
        const rows = Array.from(el().querySelectorAll('.ipbl')).map(squash);
        expect(rows).toEqual([
          'Discharge type On request (LAMA)',
          'Summary template Surgical',
          'Summary signed by 26 Oct 2026, 11:30 AM Dr. Meera Kulkarni',
          'Discharged by 26 Oct 2026, 12:30 PM Pooja Gaikwad',
          'Follow-up OPD review on 2 Nov 2026',
          'Bed · General 2 × ₹800 ₹1,600',
          'CBC ₹1,050',
          'Total charges ₹2,650',
          'Deposits received − ₹4,000',
          'Refunded by Cash Pooja Gaikwad · 26 Oct 2026, 12:30 PM ₹1,350',
        ]);
        expect(btn('Start discharge')).toBeUndefined();
        expect(btn('Print interim bill')).toBeUndefined();
        expect(el().querySelector('#ad-amount')).toBeNull();
        expect(el().querySelector('#ad-charge')).toBeNull();
        expect(el().querySelector('#ad-bed')).toBeNull();
        expect(btn('Close')).toBeTruthy();
      });

      it('shows a collected outcome and a dash for a death follow-up', async () => {
        await setup({
          ...DONE,
          discharge: { ...DONE.discharge!, type: 'death', followUpDate: null },
          finalBill: { ...DONE.finalBill!, balancePaise: 150000, outcome: 'collected', mode: 'UPI' },
        });
        const rows = Array.from(el().querySelectorAll('.ipbl')).map(squash);
        expect(rows[4]).toBe('Follow-up —');
        expect(rows[9]).toBe('Collected by UPI Pooja Gaikwad · 26 Oct 2026, 12:30 PM ₹1,500');
      });

      it('shows Settled for a zero balance', async () => {
        await setup({
          ...DONE,
          finalBill: { ...DONE.finalBill!, balancePaise: 0, outcome: 'settled', mode: null },
        });
        const rows = Array.from(el().querySelectorAll('.ipbl')).map(squash);
        expect(rows[9]).toBe('Settled Pooja Gaikwad · 26 Oct 2026, 12:30 PM ₹0');
      });
    });
  });

  describe('Nursing button (SAH-30)', () => {
    it('shows Nursing when canOpenNursing is true and opens the Nursing sheet for the admission', async () => {
      await setup({ ...DETAIL, canOpenNursing: true });
      const open = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({} as never);
      const nursing = btn('Nursing');
      expect(nursing).toBeTruthy();
      expect(nursing.querySelector('app-icon')).toBeTruthy();
      nursing.click();
      expect(open).toHaveBeenCalledTimes(1);
      const config = open.mock.calls[0][1] as { data: unknown; panelClass: string };
      expect(config.data).toEqual({ admissionId: 'a1' });
      expect(config.panelClass).toBe('sheet-panel');
    });

    it('has no Nursing button when canOpenNursing is false or missing', async () => {
      await setup({ ...DETAIL, canOpenNursing: false });
      expect(btn('Nursing')).toBeUndefined();
    });

    it('has no Nursing button when the field is missing', async () => {
      await setup();
      expect(btn('Nursing')).toBeUndefined();
    });
  });

  describe('EMR button (SAH-31)', () => {
    it('shows EMR with the emr icon when canOpenEmr is true and opens the EMR sheet for the admission', async () => {
      await setup({ ...DETAIL, canOpenEmr: true });
      const open = vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({} as never);
      const emr = btn('EMR');
      expect(emr).toBeTruthy();
      expect(emr.querySelector('app-icon')).toBeTruthy();
      emr.click();
      expect(open).toHaveBeenCalledTimes(1);
      const config = open.mock.calls[0][1] as { data: unknown; panelClass: string; ariaLabelledBy: string };
      expect(config.data).toEqual({ admissionId: 'a1' });
      expect(config.panelClass).toBe('sheet-panel');
      expect(config.ariaLabelledBy).toBe('emr-title');
    });

    it('has no EMR button when canOpenEmr is false', async () => {
      await setup({ ...DETAIL, canOpenEmr: false });
      expect(btn('EMR')).toBeUndefined();
    });

    it('has no EMR button when the field is missing', async () => {
      await setup();
      expect(btn('EMR')).toBeUndefined();
    });

    it('puts EMR after Nursing in the footer', async () => {
      await setup({ ...DETAIL, canOpenNursing: true, canOpenEmr: true });
      const labels = Array.from(el().querySelectorAll('.sheet-f button')).map(squash);
      expect(labels.indexOf('EMR')).toBeGreaterThan(labels.indexOf('Nursing'));
    });
  });

  describe('start view from a patient card (SAH-36, ADR-013 D6)', () => {
    const DISCHARGE_URL = '/api/v1/ipd/admissions/a1/discharge';
    const MOVABLE: AdmissionDetail = { ...DETAIL, canCollectDeposit: false, canAddCharge: false, canTransfer: true };
    const READY: AdmissionDetail = { ...MOVABLE, canTransfer: false, canStartDischarge: true, status: 'ADMITTED' };
    const SHEET_ANSWER: DischargeSheet = {
      admissionId: 'a1',
      ipNumber: 'IP/26-27/0042',
      patientName: 'Asha Patil',
      bedCode: '101-A',
      dayNumber: 2,
      approvalChain: true,
      types: [{ key: 'normal', label: 'Normal' }],
      templates: [{ key: 'general', label: 'General medicine' }],
      defaultType: 'normal',
      defaultTemplateKey: 'general',
      signature: null,
      canSign: true,
      canDischarge: true,
      canRefund: true,
      preview: {
        diagnosis: 'Chest pain',
        stayDays: 2,
        bedCode: '101-A',
        doctorName: 'Dr. Meera Kulkarni',
        doctorShortName: 'Dr. Meera Kulkarni',
        doctorSpecialty: null,
      },
      bill: { totalPaise: 265000, depositsPaise: 400000, balancePaise: -135000 },
      followUpDays: 7,
      followUpDate: '2026-10-31',
      openEscalation: false,
      signoffBeforeEscalation: false,
    };

    async function setupAt(
      startView: 'details' | 'move' | 'discharge' | undefined,
      detail: AdmissionDetail,
      free: AdmissionBedOption[] = FREE_BEDS,
      openWhen: 'before-read' | 'after-read' | 'never' = 'before-read',
    ): Promise<void> {
      opened = new ReplaySubject<void>(1);
      dialogRef = { close: vi.fn(), afterOpened: () => opened };
      snack = vi.fn();
      await TestBed.configureTestingModule({
        imports: [AdmissionDialogComponent],
        providers: [
          { provide: MAT_DIALOG_DATA, useValue: startView ? { id: 'a1', startView } : { id: 'a1' } },
          { provide: MatDialogRef, useValue: dialogRef },
          { provide: MatSnackBar, useValue: { open: snack } },
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
        ],
      }).compileComponents();
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(AdmissionDialogComponent);
      document.body.appendChild(el());
      // Material's focus step at the end of the opening: the first thing in the dialog (the close button).
      opened.subscribe(() => closeButton.focus());
      fixture.detectChanges();
      if (openWhen === 'before-read') {
        await open();
      }
      http.expectOne(URL).flush(detail);
      if (detail.canTransfer) {
        http.expectOne(OPTIONS_URL).flush(optionsOf(free));
      }
      await settle();
      if (openWhen === 'after-read') {
        await open();
      }
    }

    const closeButton = document.createElement('button');

    async function open(): Promise<void> {
      opened.next();
      await settle();
    }

    beforeEach(() => document.body.appendChild(closeButton));
    afterEach(() => {
      el().remove();
      closeButton.remove();
    });

    it('opens on the main view for `details` and for no start view', async () => {
      await setupAt('details', MOVABLE);
      expect(el().querySelector('#ads')!.textContent).toContain('Asha Patil');
      expect(document.activeElement).not.toBe(el().querySelector('#ad-bed'));
    });

    it('focuses the Move row (the bed select) for `move`', async () => {
      await setupAt('move', MOVABLE);
      expect(document.activeElement).toBe(el().querySelector('#ad-bed'));
    });

    it('focuses the bed select when the free beds answer after the dialog opened (F-1)', async () => {
      await setupAt('move', MOVABLE, FREE_BEDS, 'before-read');
      expect(document.activeElement).toBe(el().querySelector('#ad-bed'));
    });

    it('focuses the bed select when the dialog opens after the free beds answered, taking back the focus the dialog set (F-1)', async () => {
      await setupAt('move', MOVABLE, FREE_BEDS, 'after-read');
      expect(document.activeElement).toBe(el().querySelector('#ad-bed'));
    });

    it('does not take the focus while the dialog is still opening, then takes it right after it opened', async () => {
      await setupAt('move', MOVABLE, FREE_BEDS, 'never');
      expect(document.activeElement).not.toBe(el().querySelector('#ad-bed'));
      await open();
      expect(document.activeElement).toBe(el().querySelector('#ad-bed'));
    });

    it('focuses the Move row itself when there is no free bed and the dialog opens after the read (F-1)', async () => {
      await setupAt('move', MOVABLE, [], 'after-read');
      expect(document.activeElement).toBe(el().querySelector('#ad-move-row'));
    });

    it('does not move the focus away from the dialog for details, whichever comes first', async () => {
      await setupAt('details', MOVABLE, FREE_BEDS, 'after-read');
      expect(document.activeElement).toBe(closeButton);
    });

    it('focuses the bed select even when the server answers at once, before the start view could be applied (R36-9)', async () => {
      opened = new ReplaySubject<void>(1);
      dialogRef = { close: vi.fn(), afterOpened: () => opened };
      await TestBed.configureTestingModule({
        imports: [AdmissionDialogComponent],
        providers: [
          { provide: MAT_DIALOG_DATA, useValue: { id: 'a1', startView: 'move' } },
          { provide: MatDialogRef, useValue: dialogRef },
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
          {
            provide: AdmissionsApi,
            useValue: {
              get: () => of(MOVABLE),
              options: () => of(optionsOf(FREE_BEDS)),
              chargeServices: () => of([]),
            },
          },
        ],
      }).compileComponents();
      fixture = TestBed.createComponent(AdmissionDialogComponent);
      document.body.appendChild(el());
      fixture.detectChanges();
      await open();
      expect(document.activeElement).toBe(el().querySelector('#ad-bed'));
    });

    it('focuses the Move row itself when there is no free bed', async () => {
      await setupAt('move', MOVABLE, []);
      expect(document.activeElement).toBe(el().querySelector('#ad-move-row'));
    });

    it('stays on the main view for `move` when the detail says the patient cannot be moved', async () => {
      await setupAt('move', { ...MOVABLE, canTransfer: false });
      expect(el().querySelector('#ad-bed')).toBeNull();
      expect(el().querySelector('#ads')!.textContent).toContain('Asha Patil');
    });

    it('opens straight on the discharge view for `discharge` and makes the same read as Start discharge', async () => {
      await setupAt('discharge', READY);
      http.expectOne(DISCHARGE_URL).flush(SHEET_ANSWER);
      await settle();
      expect(el().querySelector('h2')!.textContent).toBe('Discharge Asha Patil');
    });

    it('stays on the main view for `discharge` when the detail says discharge is not possible any more', async () => {
      await setupAt('discharge', { ...READY, canStartDischarge: false });
      expect(el().querySelector('#ads')!.textContent).toContain('Asha Patil');
      http.expectNone(DISCHARGE_URL);
    });

    it('stays on the main view for `discharge` on an already discharged admission', async () => {
      await setupAt('discharge', { ...READY, canStartDischarge: false, status: 'DISCHARGED' });
      http.expectNone(DISCHARGE_URL);
      expect(el().querySelector('h2')!.textContent).not.toContain('Discharge Asha');
    });
  });
});
