import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { count, listOf, noticeOf, summaryOf } from '../orders/order-notices.fixtures';
import { NursingSheetComponent } from './nursing-sheet.component';
import { NursingSheet, SheetTab, sheetTabAfterKey } from './nursing.model';

const SHEET_URL = '/api/v1/ipd/admissions/a1/nursing';
const LIST_URL = '/api/v1/ipd/admissions/a1/order-notices';
const SUMMARY_URL = '/api/v1/ipd/order-notices/summary';
const AS_OF = '2026-11-05T10:00:00Z';

const SHEET: NursingSheet = {
  admissionId: 'a1',
  patientName: 'Asha Bendre',
  bedCode: '202',
  status: 'ADMITTED',
  state: 'on',
  canRecord: true,
  readOnlyReason: null,
  asOf: AS_OF,
  latest: null,
  vitalsDue: false,
  doseCount: 0,
  recentNotes: [],
  ordersToAcknowledge: count(0),
  vitalsDueAt: null,
  vitalsIntervalMinutes: 240,
};

describe('NursingSheetComponent: the tab Orders (SAH-76-3)', () => {
  let fixture: ComponentFixture<NursingSheetComponent>;
  let http: HttpTestingController;

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function setup(sheet: NursingSheet = SHEET, tab?: SheetTab, summary = summaryOf()): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [NursingSheetComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: { admissionId: 'a1', ...(tab ? { tab } : {}) } },
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NursingSheetComponent);
    fixture.detectChanges();
    http.expectOne(SUMMARY_URL).flush(summary);
    http.expectOne(SHEET_URL).flush(sheet);
    await settle();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const badge = () => q('[data-testid=nursing-orders-count]');

  afterEach(() => http.verify());

  it('is the third tab, after Medicines (MAR), with the words "Orders"', async () => {
    await setup();
    const tabs = Array.from(el().querySelectorAll('[role=tab]')).map((t) => squash(t));
    expect(tabs).toEqual(['Vitals and notes', 'Medicines (MAR)', 'Orders']);
    expect(q('#nv-tab-ord')!.getAttribute('aria-selected')).toBe('false');
  });

  it('shows no count when nothing is waiting', async () => {
    await setup();
    expect(badge()).toBeNull();
  });

  it('shows the amber count the sheet carries', async () => {
    await setup(
      { ...SHEET, ordersToAcknowledge: count(2) },
      undefined,
      summaryOf({ byAdmission: [{ admissionId: 'a1', count: 2, stat: 0, overdue: 0, statDueBy: null }] }),
    );
    expect(squash(badge())).toBe('2');
    expect(badge()!.classList).toContain('amber');
    expect(badge()!.getAttribute('aria-label')).toBe('2 to acknowledge');
  });

  it('shows the count in red when a STAT order is waiting', async () => {
    await setup(
      { ...SHEET, ordersToAcknowledge: count(2, 1) },
      undefined,
      summaryOf({ byAdmission: [{ admissionId: 'a1', count: 2, stat: 1, overdue: 0, statDueBy: null }] }),
    );
    expect(badge()!.classList).toContain('stat');
    expect(badge()!.classList).not.toContain('amber');
  });

  it('follows the 30-second counts poll for this patient over the count the sheet carried', async () => {
    await setup(
      { ...SHEET, ordersToAcknowledge: count(2) },
      undefined,
      summaryOf({ byAdmission: [{ admissionId: 'a1', count: 5, stat: 1, overdue: 0, statDueBy: null }] }),
    );
    expect(squash(badge())).toBe('5');
    expect(badge()!.classList).toContain('stat');
  });

  it('opens the list when the tab is chosen: the Orders panel, its subtitle with the bed and a Done button', async () => {
    await setup({ ...SHEET, ordersToAcknowledge: count(1) });
    q<HTMLButtonElement>('#nv-tab-ord')!.click();
    await settle();
    http.expectOne(LIST_URL).flush(listOf([noticeOf()]));
    await settle();
    expect(q('#nv-tab-ord')!.getAttribute('aria-selected')).toBe('true');
    expect(q('#nv-panel-ord')!.getAttribute('aria-labelledby')).toBe('nv-tab-ord');
    expect(q('[data-testid=orders-tab]')).not.toBeNull();
    expect(squash(q('[data-testid=nursing-subtitle]'))).toBe('Bed 202');
    expect(Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b))).toEqual(['Done']);
    expect(q('[data-act=orders-done]')).not.toBeNull();
  });

  it('opens on the Orders tab when the Safety panel asks for it', async () => {
    await setup(SHEET, 'ord');
    http.expectOne(LIST_URL).flush(listOf([noticeOf()]));
    await settle();
    expect(q('#nv-tab-ord')!.getAttribute('aria-selected')).toBe('true');
    expect(q('#nv-panel-vit')).toBeNull();
  });

  it('drops the count on the tab when the list says the order was acknowledged', async () => {
    await setup({ ...SHEET, ordersToAcknowledge: count(1) }, 'ord');
    http.expectOne(LIST_URL).flush(listOf([noticeOf()]));
    await settle();
    expect(squash(badge())).toBe('1');
    q<HTMLButtonElement>('[data-act=ordack]')!.click();
    await settle();
    http
      .expectOne('/api/v1/ipd/order-notices/n1/acknowledge')
      .flush(listOf([noticeOf({ acknowledgedAt: '2026-11-05T10:01:00Z', acknowledgedByName: 'Sr. Anjali Pawar' })]));
    await settle();
    expect(badge()).toBeNull();
  });

  it('moves between the three tabs with the arrow keys, Home and End', () => {
    expect(sheetTabAfterKey('ArrowRight', 'mar')).toBe('ord');
    expect(sheetTabAfterKey('ArrowRight', 'ord')).toBe('vit');
    expect(sheetTabAfterKey('ArrowLeft', 'vit')).toBe('ord');
    expect(sheetTabAfterKey('Home', 'ord')).toBe('vit');
    expect(sheetTabAfterKey('End', 'vit')).toBe('ord');
    expect(sheetTabAfterKey('ArrowDown', 'vit')).toBeNull();
  });

  describe('the vitals due line in the tab Vitals and notes (SAH-76-6, placed here)', () => {
    it('shows "Next vitals due" from the sheet', async () => {
      await setup({ ...SHEET, vitalsDueAt: '2026-11-05T12:00:00Z', vitalsIntervalMinutes: 240 });
      expect(squash(q('[data-testid=vitals-due]'))).toBe('Next vitals due 5:30 PM');
    });
  });
});
