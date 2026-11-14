import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { Subject, of } from 'rxjs';
import { ApiError } from '../../core/http/problem-detail';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { EmergencyComponent } from './emergency/emergency.component';
import { ShellStore } from '../../core/shell/shell.store';
import { EmrAccess } from '../emr/emr-access.service';
import { AdmissionDetail, AdmissionList, AdmittedPatientRow, BedBoard } from './ipd.model';
import { EmrSheetComponent } from './emr/emr-sheet.component';
import { EmergencyAccessSheetComponent } from './emergency-access/emergency-access-sheet.component';
import { ScopeRefusals } from './ipd-scope-refusal';
import { resolvedStandard } from './card/card-setup.fixtures';
import { IpdPage } from './ipd.page';
import { NursingSheetComponent } from './nursing/nursing-sheet.component';
import { PharmacistStore } from './pharmacist/pharmacist.store';
import { TOKEN_A, TOKEN_B, boardOf, pageOf } from './safety/safety.fixtures';
import { AdmissionSafety, SafetyBannerItem } from './safety/safety.model';

const IPD = '/api/v1/ipd';

const ROW: AdmittedPatientRow = {
  id: 'a1',
  ipNumber: 'IP/26-27/0042',
  patientId: 'p1',
  patientName: 'Asha Patil',
  age: 56,
  sex: 'F',
  doctorName: 'Dr. Meera',
  bedCode: '101-A',
  categoryName: 'General',
  wardName: 'General ward',
  admissionType: 'emergency',
  payer: 'Self',
  dayNumber: 3,
  depositPaise: 1000000,
  minimumDepositPaise: 500000,
  runningBillPaise: 300000,
  depositLow: false,
  depositState: 'OK',
  admittedAt: '2026-10-24T05:35:00Z',
  uhid: 'PT-000042',
  wardId: 'w-general',
  roomName: 'Room 1',
};

const EMPTY_BOARD: BedBoard = {
  state: 'on',
  roomLevel: true,
  canAdmit: true,
  canChangeBeds: true,
  counts: { free: 0, occ: 0, clean: 0, res: 0, blk: 0 },
  wards: [],
};

function list(partial: Partial<AdmissionList> = {}): AdmissionList {
  return {
    bedsTotal: 8,
    bedsOccupied: 2,
    bedsAvailable: 6,
    canAdmit: true,
    depositAlerts: 1,
    alertPercent: 80,
    emergency: { writable: true, queue: 3, overTarget: 1 },
    safety: null,
    asOf: '2026-10-24T07:30:00Z',
    wards: [{ id: 'w-general', name: 'General ward' }],
    card: {
      nursing: true,
      emr: true,
      transfer: true,
      discharge: true,
      deposit: true,
      depositLowFlag: true,
      visibleGroups: ['patient', 'contact', 'clinical', 'vitals', 'billing'],
    },
    cardSetup: resolvedStandard(),
    scope: { kind: 'ALL', visible: 3, admitted: 3, canOpenEmergencyAccess: false },
    items: [
      ROW,
      { ...ROW, id: 'a2', patientName: 'Ravi Shah', payer: 'Insurance', depositState: 'NOT_NEEDED', depositPaise: 0, runningBillPaise: 450000, admissionType: 'planned', dayNumber: 1 },
      { ...ROW, id: 'a3', patientName: 'Sunita More', depositState: 'DUE', depositPaise: 100000, runningBillPaise: 90000, depositLow: true, admissionType: 'planned', dayNumber: 2 },
    ],
    ...partial,
  };
}

describe('IpdPage', () => {
  let fixture: ComponentFixture<IpdPage>;
  let http: HttpTestingController;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let snack: ReturnType<typeof vi.fn>;

  function setup(
    role = 'FRONT_DESK',
    dialogResult: unknown = null,
    menu: string[] = ['today', 'ipd'],
    routeData: Record<string, unknown> = {},
  ): HTMLElement {
    snack = vi.fn();
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(dialogResult) })) };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { data: routeData } } },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: signal({ role }), menu: signal(menu) } },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
        { provide: EmrAccess, useValue: { ensureLoaded: () => undefined, canOpen: () => false } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IpdPage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const listUrl = (r: { url: string }) => r.url === `${IPD}/admissions`;

  async function loadOk(data: AdmissionList = list(), openAdmitted = true): Promise<void> {
    http.expectOne(listUrl).flush(data);
    http.expectOne(`${IPD}/layout`).flush({ charges: { bedDayCut: 'midnight', nursingPaise: 0, visitPaise: 0 } });
    http.expectOne(`${IPD}/admission-rules`).flush({ alertPercent: 80 });
    await settle();
    const tab = button(fixture.nativeElement as HTMLElement, 'Admitted');
    if (openAdmitted && tab) {
      tab.click();
      await settle();
    }
  }

  /** SAH-36: the Admitted tab opens as Cards; the table of SAH-23 is one tap away. */
  async function showTable(view: HTMLElement): Promise<void> {
    view.querySelector<HTMLElement>('[data-view=table]')!.click();
    await settle();
  }

  function button(view: HTMLElement, text: string): HTMLElement | undefined {
    return Array.from(view.querySelectorAll<HTMLElement>('button, a')).find((b) =>
      b.textContent!.includes(text),
    );
  }

  afterEach(() => {
    http.match((r) => r.url === `${IPD}/board`).filter((r) => !r.cancelled).forEach((r) => r.flush(EMPTY_BOARD));
    http.verify();
  });

  it('shows the tiles, the lead and the Admitted list', async () => {
    const view = setup();
    await loadOk();
    await showTable(view);
    expect(view.querySelector('h1')!.textContent).toContain('Beds and admissions');
    expect(view.querySelector('.lead')!.textContent).toContain('Bed and nursing charges post at midnight');
    expect(view.querySelector('.lead')!.textContent).toContain('deposit alert at 80% of the deposit');
    expect(view.querySelector('[data-kpi=occupied]')!.textContent).toContain('2 / 8');
    expect(view.querySelector('[data-kpi=occupied]')!.textContent).toContain('25% occupancy · 6 available');
    expect(view.querySelector('[data-kpi=available]')).toBeNull();
    const rows = view.querySelectorAll('.iptr:not(.ipth)');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('IP/26-27/0042 · 56 F · Emergency · day 3');
    expect(rows[0].textContent).toContain('101-A');
    expect(rows[0].textContent).toContain('General');
    expect(rows[0].textContent).toContain('Dr. Meera');
    expect(rows[0].textContent).toContain('₹3,000');
    expect(rows[0].textContent).toContain('₹10,000');
    expect(rows[0].textContent).toContain('Deposit OK');
    expect(rows[1].textContent).toContain('₹4,500');
    expect(rows[1].textContent).toContain('Insurance');
    expect(rows[1].textContent).not.toContain('Deposit OK');
    expect(rows[2].textContent).toContain('₹900');
    expect(rows[2].textContent).toContain('Deposit low');
    expect(rows[2].textContent).not.toContain('Deposit due');
    const heads = Array.from(view.querySelectorAll('.ipth [role=columnheader]')).map((h) => h.textContent!.trim());
    expect(heads.slice(0, 5)).toEqual(['Patient', 'Bed', 'Doctor', 'Running bill', 'Deposit']);
  });

  it('shows the Deposit alerts tile with the alert percent', async () => {
    const view = setup();
    await loadOk(list({ depositAlerts: 2, alertPercent: 70 }), false);
    const tile = view.querySelector('[data-kpi=alerts]')!;
    expect(tile.textContent).toContain('Deposit alerts');
    expect(tile.textContent).toContain('2');
    expect(tile.textContent).toContain('Running bill over 70% of deposit');
  });

  it('opens on the Bed board tab and switches tabs with the keyboard', async () => {
    const view = setup();
    await loadOk(list({ canAdmit: false, emergency: { writable: false, queue: 0, overTarget: 0 } }), false);
    const tabs = Array.from(view.querySelectorAll<HTMLElement>('[role=tab]'));
    expect(tabs.map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false']);
    expect(tabs.map((t) => t.getAttribute('tabindex'))).toEqual(['0', '-1']);
    expect(tabs[1].textContent).toContain('3');
    expect(view.querySelector('app-bed-board')).not.toBeNull();
    expect(view.querySelector('app-admitted-panel')).toBeNull();
    expect(view.querySelector('[role=tabpanel]')!.getAttribute('aria-labelledby')).toBe('ipd-tab-board');
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await settle();
    expect(view.querySelector('app-admitted-panel')).not.toBeNull();
    expect(view.querySelector('[role=tabpanel]')!.getAttribute('aria-labelledby')).toBe('ipd-tab-admitted');
    tabs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await settle();
    expect(view.querySelector('app-bed-board')).not.toBeNull();
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    await settle();
    expect(view.querySelector('app-admitted-panel')).not.toBeNull();
    tabs[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await settle();
    expect(view.querySelector('app-bed-board')).not.toBeNull();
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));
    await settle();
    expect(view.querySelector('app-bed-board')).not.toBeNull();
  });

  it('shows the empty list message', async () => {
    const view = setup();
    await loadOk(list({ items: [], bedsOccupied: 0, bedsAvailable: 8 }));
    expect(view.textContent).toContain('No one is admitted right now.');
  });

  it('asks the owner to add wards and beds when there are no beds', async () => {
    const view = setup('OWNER');
    await loadOk(list({ bedsTotal: 0, bedsOccupied: 0, bedsAvailable: 0, items: [] }));
    expect(view.textContent).toContain('Add your wards and beds to start');
    expect(view.textContent).toContain('IPD is on. Add wards, rooms and beds with their categories and daily charges.');
    const link = button(view, 'Add wards and beds') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/app/config/ipbeds');
    expect(button(view, 'Admit patient')).toBeUndefined();
  });

  it('does not offer the Add wards link to other roles', async () => {
    const view = setup('FRONT_DESK');
    await loadOk(list({ bedsTotal: 0, bedsOccupied: 0, bedsAvailable: 0, items: [] }));
    expect(view.textContent).toContain('Add your wards and beds to start');
    expect(button(view, 'Add wards and beds')).toBeUndefined();
  });

  it('hides Admit patient when the server says the user cannot admit', async () => {
    const view = setup('DOCTOR');
    await loadOk(list({ canAdmit: false }));
    expect(button(view, 'Admit patient')).toBeUndefined();
    await showTable(view);
    expect(button(view, 'Open')).toBeDefined();
  });

  it('shows the lock notice with a link for the owner when the module is off', async () => {
    const view = setup('OWNER');
    http
      .expectOne(listUrl)
      .flush({ type: 'module-off', title: 'Module off', status: 403 }, { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(view.textContent).toContain('not on for your clinic');
    expect((button(view, 'See plan and modules') as HTMLAnchorElement).getAttribute('href')).toBe(
      '/app/config/modules',
    );
    expect(view.querySelector('.iptbl')).toBeNull();
  });

  it('hides the link from non-owners on the lock notice', async () => {
    const view = setup('NURSE');
    http
      .expectOne(listUrl)
      .flush({ type: 'module-off', title: 'Module off', status: 403 }, { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(view.textContent).toContain('not on for your clinic');
    expect(button(view, 'See plan and modules')).toBeUndefined();
  });

  it('shows an error with a retry for other failures', async () => {
    const view = setup();
    http
      .expectOne(listUrl)
      .flush({ type: 'x', title: 'x', status: 500, detail: 'Server trouble' }, { status: 500, statusText: 'Error' });
    await settle();
    expect(view.querySelector('[role=alert]')!.textContent).toContain('Server trouble');
    button(view, 'Try again')!.click();
    http.expectOne(listUrl).flush(list());
    http.expectOne(`${IPD}/layout`).flush({ charges: { bedDayCut: '24h', nursingPaise: 0, visitPaise: 0 } });
    http.expectOne(`${IPD}/admission-rules`).flush({ alertPercent: 70 });
    await settle();
    expect(view.querySelector('.lead')!.textContent).toContain('every 24 hours');
  });

  it('keeps the plain lead when the lead parts fail', async () => {
    const view = setup();
    http.expectOne(listUrl).flush(list());
    http.expectOne(`${IPD}/layout`).flush({}, { status: 500, statusText: 'Error' });
    http.expectOne(`${IPD}/admission-rules`).flush({}, { status: 500, statusText: 'Error' });
    await settle();
    expect(view.querySelector('.lead')!.textContent!.trim()).toBe('Beds and admissions');
  });

  it('D-11: holds room for the finished lead until both lead calls have answered, and keeps the text readable', async () => {
    const view = setup();
    // the plain lead is in the page from the first paint, so a screen reader is never left without it
    expect(view.querySelector('.lead')!.textContent!.trim()).toBe('Beds and admissions');
    expect(view.querySelector('.lead')!.classList.contains('reserve')).toBe(true);
    expect(view.querySelector('.lead')!.closest('[aria-hidden=true]')).toBeNull();
    http.expectOne(listUrl).flush(list());
    await settle();
    expect(view.querySelector('.lead')!.classList.contains('reserve')).toBe(true);
    http.expectOne(`${IPD}/layout`).flush({ charges: { bedDayCut: 'midnight', nursingPaise: 0, visitPaise: 0 } });
    await settle();
    expect(view.querySelector('.lead')!.classList.contains('reserve')).toBe(true);
    http.expectOne(`${IPD}/admission-rules`).flush({ alertPercent: 80 });
    await settle();
    expect(view.querySelector('.lead')!.classList.contains('reserve')).toBe(false);
    expect(view.querySelector('.lead')!.textContent).toContain('deposit alert at 80% of the deposit');
    expect(view.querySelector('.sizer')).toBeNull();
  });

  it('D-11: sizes the pending head for the finished words: the mockup defaults for a part not answered, the real part once it has', async () => {
    const midnight80 =
      'Beds and admissions · Bed and nursing charges post at midnight · deposit alert at 80% of the deposit';
    const view = setup();
    expect(view.querySelector('.sizer')!.getAttribute('data-like')).toBe(midnight80);
    http.expectOne(listUrl).flush(list());
    http.expectOne(`${IPD}/layout`).flush({ charges: { bedDayCut: '24h', nursingPaise: 0, visitPaise: 0 } });
    await settle();
    expect(view.querySelector('.sizer')!.getAttribute('data-like')).toBe(
      'Beds and admissions · Bed and nursing charges post every 24 hours · deposit alert at 80% of the deposit',
    );
    // the sizer is not part of the page text, so waiting for the real words still waits
    expect(view.querySelector('app-page-head')!.textContent).not.toContain('deposit alert');
    http.expectOne(`${IPD}/admission-rules`).flush({ alertPercent: 65 });
    await settle();
    expect(view.querySelector('.sizer')).toBeNull();
    expect(view.querySelector('.lead')!.textContent).toContain('every 24 hours · deposit alert at 65% of the deposit');
  });

  it('D-11: lets the room go when the lead calls fail', async () => {
    const view = setup();
    http.expectOne(listUrl).flush(list());
    http.expectOne(`${IPD}/layout`).flush({}, { status: 500, statusText: 'Error' });
    http.expectOne(`${IPD}/admission-rules`).flush({}, { status: 500, statusText: 'Error' });
    await settle();
    expect(view.querySelector('.lead')!.classList.contains('reserve')).toBe(false);
  });

  it('D-11: reserves nothing when the list fails to load', async () => {
    const view = setup();
    http
      .expectOne(listUrl)
      .flush({ type: 'x', title: 'x', status: 500, detail: 'Server trouble' }, { status: 500, statusText: 'Error' });
    await settle();
    expect(view.querySelector('.lead')!.classList.contains('reserve')).toBe(false);
  });

  it('toasts and reloads the list after an admission', async () => {
    const admitted = { ...ROW, depositPaise: 1000000 } as unknown as AdmissionDetail;
    const view = setup('FRONT_DESK', admitted);
    await loadOk();
    button(view, 'Admit patient')!.click();
    expect(dialog.open).toHaveBeenCalled();
    expect(snack).toHaveBeenCalledWith(
      'Asha Patil admitted to bed 101-A · IP/26-27/0042 · deposit ₹10,000 received',
      'Close',
      expect.anything(),
    );
    http.expectOne(listUrl).flush(list());
    await settle();
  });

  it('lets the newest reload win: an older read still on its way is cancelled and cannot overwrite it (R36-3)', async () => {
    const admitted = { ...ROW, depositPaise: 0 } as unknown as AdmissionDetail;
    const view = setup('FRONT_DESK', admitted);
    await loadOk();
    button(view, 'Admit patient')!.click();
    button(view, 'Admit patient')!.click();
    const reads = http.match(listUrl);
    expect(reads.length).toBe(2);
    expect(reads[0].cancelled).toBe(true);
    expect(reads[1].cancelled).toBe(false);
    reads[1].flush(list({ asOf: '2026-10-24T07:31:00Z' }));
    await settle();
    expect(view.querySelector('[data-testid=admitted-stale]')).toBeNull();
  });

  it('a failed newest reload shows the stale line and a later good one clears it (R36-3)', async () => {
    const admitted = { ...ROW, depositPaise: 0 } as unknown as AdmissionDetail;
    const view = setup('FRONT_DESK', admitted);
    await loadOk();
    button(view, 'Admit patient')!.click();
    http.expectOne(listUrl).flush({}, { status: 500, statusText: 'Server Error' });
    await settle();
    expect(view.querySelector('[data-testid=admitted-stale]')).not.toBeNull();
    button(view, 'Admit patient')!.click();
    http.expectOne(listUrl).flush(list());
    await settle();
    expect(view.querySelector('[data-testid=admitted-stale]')).toBeNull();
  });

  it('says the deposit is due today when none was taken', async () => {
    const admitted = { ...ROW, depositPaise: 0 } as unknown as AdmissionDetail;
    const view = setup('FRONT_DESK', admitted);
    await loadOk();
    button(view, 'Admit patient')!.click();
    expect(snack.mock.calls[0][0]).toBe('Asha Patil admitted to bed 101-A · IP/26-27/0042 · deposit due today');
    http.expectOne(listUrl).flush(list());
  });

  it('opens the admission sheet and reloads only when something changed', async () => {
    const view = setup('FRONT_DESK', true);
    await loadOk();
    await showTable(view);
    button(view, 'Open')!.click();
    expect(dialog.open.mock.calls[0][1].data).toEqual({ id: 'a1' });
    http.expectOne(listUrl).flush(list());
    await settle();
  });

  it('does not reload when the admission sheet closes unchanged', async () => {
    const view = setup('FRONT_DESK', false);
    await loadOk();
    await showTable(view);
    button(view, 'Open')!.click();
    await settle();
  });

  describe('Going home today (SAH-27)', () => {
    it('shows the fourth tile with discharged plus planned and the text', async () => {
      const view = setup();
      await loadOk(list({ dischargedToday: 2, goingHomeToday: 1 }), false);
      const tile = view.querySelector('[data-kpi=going-home]')!;
      expect(tile.querySelector('.eyebrow')!.textContent).toBe('Going home today');
      expect(tile.querySelector('.v')!.textContent!.trim()).toBe('3');
      expect(tile.querySelector('.s')!.textContent).toBe('2 discharged · 1 planned');
      expect(view.querySelectorAll('.kpi').length).toBe(4);
    });

    it('shows 0 when the server sends no counters', async () => {
      const view = setup();
      await loadOk(list(), false);
      const tile = view.querySelector('[data-kpi=going-home]')!;
      expect(tile.querySelector('.v')!.textContent!.trim()).toBe('0');
      expect(tile.querySelector('.s')!.textContent).toBe('0 discharged · 0 planned');
    });

    it('shows the teal pill instead of the payer and Deposit pills, and only on the flagged row', async () => {
      const view = setup();
      const data = list();
      data.items[0] = { ...data.items[0], goingHomeToday: true };
      data.items[1] = { ...data.items[1], goingHomeToday: true };
      await loadOk(data);
      await showTable(view);
      const rows = view.querySelectorAll('.iptr:not(.ipth)');
      const first = Array.from(rows[0].querySelectorAll('.pill')).map((p) => p.textContent!.trim());
      expect(first).toEqual(['Going home today']);
      expect(rows[0].querySelector('.pill.p-teal')!.textContent!.trim()).toBe('Going home today');
      const second = Array.from(rows[1].querySelectorAll('.pill')).map((p) => p.textContent!.trim());
      expect(second).toEqual(['Going home today']);
      const third = Array.from(rows[2].querySelectorAll('.pill')).map((p) => p.textContent!.trim());
      expect(third).toEqual(['Deposit low']);
    });
  });

  describe('Emergency (SAH-28)', () => {
    const QUEUE_URL = `${IPD}/emergency`;
    const queue = (over: Record<string, unknown> = {}) => ({
      state: 'on',
      scale: 3,
      levels: [
        { level: 1, name: 'Red', description: 'Immediate', tone: 'crit', targetMinutes: 5 },
        { level: 2, name: 'Yellow', description: 'Urgent', tone: 'warn', targetMinutes: 30 },
        { level: 3, name: 'Green', description: 'Can wait', tone: 'good', targetMinutes: 120 },
      ],
      defaultLevel: 2,
      unknownPatientRegistration: true,
      baysTotal: 2,
      baysFree: 1,
      counts: { writable: true, queue: 0, overTarget: 0 },
      cases: [],
      canRegister: true,
      canRetriage: true,
      canAct: true,
      canAdmit: true,
      asOf: '2026-11-05T05:35:00Z',
      ...over,
    });
    const tabs = (view: HTMLElement) =>
      Array.from(view.querySelectorAll('[role=tab]')).map((t) =>
        t.textContent!.replace(/\s+/g, ' ').trim().replace(/(\D)(\d+)$/, '$1 $2'),
      );

    it('puts the Emergency tile second, with the count and "N over target time" in the critical colour', async () => {
      const view = setup();
      await loadOk(list(), false);
      const kpis = Array.from(view.querySelectorAll('.kpi')).map((k) => k.getAttribute('data-kpi'));
      expect(kpis).toEqual(['occupied', 'emergency', 'alerts', 'going-home']);
      const tile = view.querySelector('[data-kpi=emergency]')!;
      expect(tile.querySelector('.eyebrow')!.textContent).toBe('Emergency');
      expect(tile.querySelector('.v')!.textContent!.trim()).toBe('3');
      expect(tile.querySelector('.s')!.textContent!.trim()).toBe('1 over target time');
      expect(tile.querySelector('.s b.crit')).not.toBeNull();
    });

    it('says "All within target time" when nothing is over target', async () => {
      const view = setup();
      await loadOk(list({ emergency: { writable: true, queue: 2, overTarget: 0 } }), false);
      const tile = view.querySelector('[data-kpi=emergency]')!;
      expect(tile.querySelector('.s')!.textContent!.trim()).toBe('All within target time');
      expect(tile.querySelector('.s b')).toBeNull();
    });

    it('shows a dash on the tile and hides the tab and the header button when IPD is read-only', async () => {
      const view = setup();
      await loadOk(list({ emergency: { writable: false, queue: 0, overTarget: 0 } }), false);
      expect(view.querySelector('[data-kpi=emergency] .v')!.textContent!.trim()).toBe('—');
      expect(tabs(view)).toEqual(['Bed board', 'Admitted 3', 'Admission requests']);
      expect(view.querySelector('[data-act=open-emergency]')).toBeNull();
    });

    it('shows the Emergency tab with the count after Admitted, and the header button Emergency', async () => {
      const view = setup();
      await loadOk(list(), false);
      expect(tabs(view)).toEqual(['Bed board', 'Admitted 3', 'Admission requests', 'Emergency 3']);
      const header = view.querySelector('.pagehead .actions')!;
      expect(Array.from(header.querySelectorAll('button')).map((b) => b.textContent!.trim())).toEqual([
        'Emergency',
        'Admit patient',
      ]);
    });

    it('opens the tab inside the page when the user has no Emergency menu item', async () => {
      const view = setup('DOCTOR', null, ['today', 'ipd']);
      await loadOk(list(), false);
      view.querySelector<HTMLElement>('[data-act=open-emergency]')!.click();
      await settle();
      http.expectOne(QUEUE_URL).flush(queue());
      await settle();
      expect(view.querySelector('h1')!.textContent).toBe('Emergency');
      expect(view.querySelector('.lead')!.textContent).toBe('Triage first. Admit, treat and release, or refer out.');
      expect(view.querySelector('#ipd-tab-er')!.getAttribute('aria-selected')).toBe('true');
      expect(view.querySelector('[data-act=open-emergency]')).toBeNull();
    });

    it('goes to the Emergency page when the user has the menu item', async () => {
      const view = setup('NURSE', null, ['today', 'emergency', 'ipd']);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      await loadOk(list(), false);
      view.querySelector<HTMLElement>('[data-act=open-emergency]')!.click();
      expect(navigate).toHaveBeenCalledWith('/app/emergency');
    });

    it('opens on the Emergency tab for the route /app/emergency and hides the button there', async () => {
      const view = setup('NURSE', null, ['today', 'emergency', 'ipd'], { tab: 'er' });
      await loadOk(list(), false);
      http.expectOne(QUEUE_URL).flush(queue());
      await settle();
      expect(view.querySelector('h1')!.textContent).toBe('Emergency');
      expect(view.querySelector('[data-act=open-emergency]')).toBeNull();
      expect(view.querySelector('app-emergency')).not.toBeNull();
    });

    it('falls back to the Bed board when the route asks for Emergency but IPD is read-only', async () => {
      const view = setup('NURSE', null, ['today', 'ipd'], { tab: 'er' });
      await loadOk(list({ emergency: { writable: false, queue: 0, overTarget: 0 } }), false);
      expect(view.querySelector('h1')!.textContent).toBe('Beds and admissions');
      expect(view.querySelector('app-emergency')).toBeNull();
    });

    describe('with no wards and beds yet', () => {
      const NO_BEDS = { bedsTotal: 0, bedsOccupied: 0, bedsAvailable: 0, items: [] };

      it('shows the queue and the register form on /app/emergency', async () => {
        const view = setup('NURSE', null, ['today', 'emergency', 'ipd'], { tab: 'er' });
        await loadOk(list(NO_BEDS), false);
        http.expectOne(QUEUE_URL).flush(queue());
        await settle();
        expect(view.querySelector('h1')!.textContent).toBe('Emergency');
        expect(view.querySelector('app-emergency')).not.toBeNull();
        expect(view.textContent).not.toContain('Add your wards and beds to start');
        expect(view.querySelector('[data-act=open-emergency]')).toBeNull();
      });

      it('still shows the setup message on the Bed board tab and the Admitted tab', async () => {
        const view = setup('OWNER', null, ['today', 'emergency', 'ipd'], { tab: 'er' });
        await loadOk(list(NO_BEDS), false);
        http.expectOne(QUEUE_URL).flush(queue());
        await settle();
        view.querySelector<HTMLElement>('#ipd-tab-board')!.click();
        await settle();
        expect(view.querySelector('app-emergency')).toBeNull();
        expect(view.textContent).toContain('Add your wards and beds to start');
        view.querySelector<HTMLElement>('#ipd-tab-admitted')!.click();
        await settle();
        expect(view.textContent).toContain('Add your wards and beds to start');
        expect(view.querySelector('app-bed-board')).toBeNull();
      });

      it('shows only the setup message on /app/ipd, with the header Emergency button that opens the queue', async () => {
        const view = setup('DOCTOR', null, ['today', 'ipd']);
        await loadOk(list(NO_BEDS), false);
        expect(view.textContent).toContain('Add your wards and beds to start');
        expect(view.querySelector('[role=tab]')).toBeNull();
        expect(button(view, 'Admit patient')).toBeUndefined();
        view.querySelector<HTMLElement>('[data-act=open-emergency]')!.click();
        await settle();
        http.expectOne(QUEUE_URL).flush(queue());
        await settle();
        expect(view.querySelector('app-emergency')).not.toBeNull();
        expect(view.textContent).not.toContain('Add your wards and beds to start');
      });

      it('shows the setup message on /app/emergency when IPD is read-only', async () => {
        const view = setup('NURSE', null, ['today', 'ipd'], { tab: 'er' });
        await loadOk(
          list({ ...NO_BEDS, emergency: { writable: false, queue: 0, overTarget: 0 } }),
          false,
        );
        expect(view.querySelector('app-emergency')).toBeNull();
        expect(view.textContent).toContain('Add your wards and beds to start');
      });
    });

    it('moves to the Emergency tab with the End key', async () => {
      const view = setup();
      await loadOk(list(), false);
      view
        .querySelector<HTMLElement>('#ipd-tab-board')!
        .dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
      await settle();
      http.expectOne(QUEUE_URL).flush(queue());
      await settle();
      expect(view.querySelector('#ipd-tab-er')!.getAttribute('aria-selected')).toBe('true');
    });

    it('updates the tile and the tab count from the queue answers', async () => {
      const view = setup();
      await loadOk(list(), false);
      view.querySelector<HTMLElement>('#ipd-tab-er')!.click();
      await settle();
      http.expectOne(QUEUE_URL).flush(queue({ counts: { writable: true, queue: 5, overTarget: 0 } }));
      await settle();
      expect(view.querySelector('[data-kpi=emergency] .v')!.textContent!.trim()).toBe('5');
      expect(view.querySelector('#ipd-tab-er .c')!.textContent).toBe('5');
    });
  });

  describe('Safety (SAH-33)', () => {
    const SUMMARY = `${IPD}/safety/summary`;
    const BOARD = `${IPD}/safety`;
    const EVENTS = `${IPD}/clinical-events`;
    const LATE = [
      { escalationId: 'e1', admissionId: 'a1', patientName: 'Asha Patil', ewsScore: 8 },
      { escalationId: 'e2', admissionId: 'a3', patientName: 'Sunita More', ewsScore: 6 },
    ];
    const safety = (
      open: number,
      pastTarget: SafetyBannerItem[] = [],
      changeToken = TOKEN_A,
    ): AdmissionSafety => ({
      counts: {
        open,
        pastTarget: pastTarget.length,
        dosesDue: 0,
        dosesLate: 0,
        changeToken,
        asOf: '2026-11-05T05:47:00Z',
      },
      pastTarget,
    });
    const tabs = (view: HTMLElement) =>
      Array.from(view.querySelectorAll<HTMLElement>('[role=tab]')).map((t) =>
        t.textContent!.replace(/\s+/g, ' ').trim().replace(/(\D)(\d+)$/, '$1 $2'),
      );

    afterEach(() => vi.useRealTimers());

    function flushSafetyTab(): void {
      http.expectOne(BOARD).flush(boardOf());
      http.expectOne((r) => r.url === EVENTS).flush(pageOf());
    }

    it('has no Safety tab when the server sent no safety block (FRONT_DESK, BILLING and the like)', async () => {
      const view = setup('FRONT_DESK');
      await loadOk(list({ safety: null }), false);
      expect(tabs(view)).toEqual(['Bed board', 'Admitted 3', 'Admission requests', 'Emergency 3']);
    });

    it('has no Safety tab when nobody is admitted, even with a safety block', async () => {
      const view = setup('NURSE');
      await loadOk(list({ safety: safety(0), items: [] }), false);
      expect(tabs(view)).toEqual(['Bed board', 'Admitted 0', 'Admission requests', 'Emergency 3']);
    });

    it('shows Safety as the last tab, after Emergency, with the open count', async () => {
      const view = setup('DOCTOR');
      await loadOk(list({ safety: safety(2, LATE) }), false);
      expect(tabs(view)).toEqual(['Bed board', 'Admitted 3', 'Admission requests', 'Emergency 3', 'Safety 2']);
      expect(view.querySelector('#ipd-tab-safety')!.getAttribute('role')).toBe('tab');
    });

    describe('late doses in the tab number (SAH-35)', () => {
      const withDoses = (open: number, dosesDue: number, dosesLate: number, changeToken = TOKEN_A): AdmissionSafety => ({
        counts: { ...safety(open).counts, dosesDue, dosesLate, changeToken },
        pastTarget: [],
      });

      it('adds the late doses to the open escalations and not the doses that are only due', async () => {
        const view = setup('NURSE');
        await loadOk(list({ safety: withDoses(2, 5, 3) }), false);
        expect(view.querySelector('#ipd-tab-safety .c')!.textContent).toBe('5');
      });

      it('is hidden when there are no escalations and no late doses, even with doses due', async () => {
        const view = setup('NURSE');
        await loadOk(list({ safety: withDoses(0, 4, 0) }), false);
        expect(view.querySelector('#ipd-tab-safety .c')).toBeNull();
      });

      it('shows the late doses alone', async () => {
        const view = setup('DOCTOR');
        await loadOk(list({ safety: withDoses(0, 0, 2) }), false);
        expect(view.querySelector('#ipd-tab-safety .c')!.textContent).toBe('2');
      });

      it('follows the counts of the board answer once the Safety tab is open', async () => {
        const view = setup('NURSE');
        await loadOk(list({ safety: withDoses(0, 0, 9) }), false);
        view.querySelector<HTMLElement>('#ipd-tab-safety')!.click();
        await settle();
        flushSafetyTab(); // boardOf(): one open escalation, one late dose, one due dose
        await settle();
        expect(view.querySelector('#ipd-tab-safety .c')!.textContent).toBe('2');
      });

      it('the counts-only poll reloads the list when only a dose number differs', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('NURSE');
        await loadOk(list({ safety: withDoses(0, 1, 0) }), false);
        vi.advanceTimersByTime(60_000);
        await settle();
        http
          .expectOne(SUMMARY)
          .flush({ open: 0, pastTarget: 0, dosesDue: 0, dosesLate: 1, changeToken: TOKEN_A, asOf: '2026-11-05T05:48:00Z' });
        await settle();
        http.expectOne(listUrl).flush(list({ safety: withDoses(0, 0, 1) }));
        await settle();
        expect(view.querySelector('#ipd-tab-safety .c')!.textContent).toBe('1');
      });

      it('the counts-only poll leaves the list alone when the dose numbers and the token are the same', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        setup('NURSE');
        await loadOk(list({ safety: withDoses(0, 1, 1) }), false);
        vi.advanceTimersByTime(60_000);
        await settle();
        http
          .expectOne(SUMMARY)
          .flush({ open: 0, pastTarget: 0, dosesDue: 1, dosesLate: 1, changeToken: TOKEN_A, asOf: '2026-11-05T05:48:00Z' });
        await settle();
        http.expectNone(listUrl);
      });
    });

    it('hides the count at 0 and still shows Safety after Admitted when Emergency is off', async () => {
      const view = setup('NURSE');
      await loadOk(list({ safety: safety(0), emergency: { writable: false, queue: 0, overTarget: 0 } }), false);
      expect(tabs(view)).toEqual(['Bed board', 'Admitted 3', 'Admission requests', 'Safety']);
      expect(view.querySelector('#ipd-tab-safety .c')).toBeNull();
    });

    it('opens the Safety board on click and follows the counts of the board answer', async () => {
      const view = setup('DOCTOR');
      await loadOk(list({ safety: safety(2, LATE) }), false);
      view.querySelector<HTMLElement>('#ipd-tab-safety')!.click();
      await settle();
      flushSafetyTab();
      await settle();
      expect(view.querySelector('#ipd-tab-safety')!.getAttribute('aria-selected')).toBe('true');
      expect(view.querySelector('app-safety')).not.toBeNull();
      expect(view.querySelector('[data-testid=safety-board]')).not.toBeNull();
      // One open escalation plus the one late dose of the board answer (the due dose is not added).
      expect(view.querySelector('#ipd-tab-safety .c')!.textContent).toBe('2');
    });

    it('moves to the Safety tab with the End key', async () => {
      const view = setup('DOCTOR');
      await loadOk(list({ safety: safety(2, LATE), emergency: { writable: false, queue: 0, overTarget: 0 } }), false);
      view
        .querySelector<HTMLElement>('#ipd-tab-board')!
        .dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
      await settle();
      flushSafetyTab();
      await settle();
      expect(view.querySelector('#ipd-tab-safety')!.getAttribute('aria-selected')).toBe('true');
    });

    describe('banner on the Admitted tab', () => {
      it('shows the red notice with the names and scores and the button Open safety board', async () => {
        const view = setup('DOCTOR');
        await loadOk(list({ safety: safety(2, LATE) }));
        const banner = view.querySelector('[data-testid=safety-banner]')!;
        expect(banner.className).toContain('notice crit');
        expect(banner.textContent!.replace(/\s+/g, ' ').trim()).toBe(
          'Asha Patil · EWS 8, Sunita More · EWS 6 escalated and not seen by a doctor within target. Open safety board',
        );
        expect(banner.querySelector('b')!.textContent).toBe('Asha Patil · EWS 8, Sunita More · EWS 6');
        expect(banner.querySelector('[data-act=open-safety]')!.textContent!.trim()).toBe('Open safety board');
        expect(banner.nextElementSibling!.tagName).toBe('APP-ADMITTED-PANEL');
      });

      it('is not shown when nothing is past target', async () => {
        const view = setup('DOCTOR');
        await loadOk(list({ safety: safety(1, []) }));
        expect(view.querySelector('[data-testid=safety-banner]')).toBeNull();
      });

      it('is not shown without a safety block', async () => {
        const view = setup('FRONT_DESK');
        await loadOk(list({ safety: null }));
        expect(view.querySelector('[data-testid=safety-banner]')).toBeNull();
      });

      it('Open safety board switches to the Safety tab', async () => {
        const view = setup('NURSE');
        await loadOk(list({ safety: safety(2, LATE) }));
        view.querySelector<HTMLElement>('[data-act=open-safety]')!.click();
        await settle();
        flushSafetyTab();
        await settle();
        expect(view.querySelector('#ipd-tab-safety')!.getAttribute('aria-selected')).toBe('true');
        expect(view.querySelector('[data-testid=safety-banner]')).toBeNull();
      });

      it('moves focus to the Safety tab when Open safety board is pressed (the button leaves the page)', async () => {
        const view = setup('NURSE');
        await loadOk(list({ safety: safety(2, LATE) }));
        view.querySelector<HTMLElement>('[data-act=open-safety]')!.click();
        await settle();
        flushSafetyTab();
        await settle();
        expect(document.activeElement).toBe(view.querySelector('#ipd-tab-safety'));
      });

      it('is not on the other tabs', async () => {
        const view = setup('NURSE');
        await loadOk(list({ safety: safety(2, LATE) }), false);
        expect(view.querySelector('[data-testid=safety-banner]')).toBeNull();
      });
    });

    describe('60-second summary poll (counts only)', () => {
      const advance = async (ms: number) => {
        vi.advanceTimersByTime(ms);
        await settle();
      };

      it('reads the summary every 60 seconds and leaves the list alone when the counts are the same', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        setup('DOCTOR');
        await loadOk(list({ safety: safety(2, LATE) }), false);
        await advance(59_000);
        http.expectNone(SUMMARY);
        await advance(1_000);
        http.expectOne(SUMMARY).flush({ open: 2, pastTarget: 2, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: '2026-11-05T05:48:00Z' });
        await settle();
        http.expectNone(listUrl);
      });

      it('reloads the list when the open or past-target count differs, so the tab number and banner follow', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list({ safety: safety(2, LATE) }), false);
        await advance(60_000);
        http.expectOne(SUMMARY).flush({ open: 3, pastTarget: 2, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: '2026-11-05T05:48:00Z' });
        await settle();
        http.expectOne(listUrl).flush(list({ safety: safety(3, LATE) }));
        await settle();
        expect(view.querySelector('#ipd-tab-safety .c')!.textContent).toBe('3');
      });

      it('reloads the list when only the change token differs (one closes, another becomes overdue)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list({ safety: safety(2, LATE) }), false);
        await advance(60_000);
        http
          .expectOne(SUMMARY)
          .flush({ open: 2, pastTarget: 2, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_B, asOf: '2026-11-05T05:48:00Z' });
        await settle();
        const NEW_LATE = [{ ...LATE[0], patientName: 'Ravi Shah', ewsScore: 9 }, LATE[1]];
        http.expectOne(listUrl).flush(list({ safety: safety(2, NEW_LATE, TOKEN_B) }));
        await settle();
        view.querySelector<HTMLElement>('#ipd-tab-admitted')!.click();
        await settle();
        expect(view.querySelector('[data-testid=safety-banner] b')!.textContent).toBe(
          'Ravi Shah · EWS 9, Sunita More · EWS 6',
        );
      });

      describe('hidden browser tab', () => {
        const hide = (hidden: boolean) => {
          Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
          document.dispatchEvent(new Event('visibilitychange'));
        };
        afterEach(() => {
          delete (document as unknown as Record<string, unknown>)['hidden'];
        });

        it('does not read the summary while the tab is hidden and reads it at once when it shows again', async () => {
          vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
          setup('DOCTOR');
          await loadOk(list({ safety: safety(2, LATE) }), false);
          hide(true);
          await advance(180_000);
          http.expectNone(SUMMARY);
          hide(false);
          await settle();
          http
            .expectOne(SUMMARY)
            .flush({ open: 2, pastTarget: 2, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: '2026-11-05T05:48:00Z' });
          await settle();
          await advance(60_000);
          http
            .expectOne(SUMMARY)
            .flush({ open: 2, pastTarget: 2, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: '2026-11-05T05:49:00Z' });
          await settle();
        });
      });

      it('does not poll while the Safety tab is open: the board reloads itself', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list({ safety: safety(2, LATE) }), false);
        view.querySelector<HTMLElement>('#ipd-tab-safety')!.click();
        await settle();
        flushSafetyTab();
        await settle();
        await advance(60_000);
        http.expectNone(SUMMARY);
        flushSafetyTab();
        await settle();
      });

      it('does not poll without a Safety tab', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        setup('FRONT_DESK');
        await loadOk(list({ safety: null }), false);
        await advance(120_000);
        http.expectNone(SUMMARY);
      });

      it('ignores a failed poll and tries again at the next tick', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list({ safety: safety(2, LATE) }), false);
        await advance(60_000);
        http.expectOne(SUMMARY).flush({}, { status: 500, statusText: 'Server Error' });
        await settle();
        expect(view.querySelector('#ipd-tab-safety')).not.toBeNull();
        await advance(60_000);
        http.expectOne(SUMMARY).flush({ open: 2, pastTarget: 2, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: '2026-11-05T05:49:00Z' });
        await settle();
      });

      describe('after the list was refused (SAH-40 R-1)', () => {
        const forbidden = {
          body: { type: 'https://sahaj.in/problems/forbidden', title: 'x', status: 403, detail: 'No access.' },
          init: { status: 403, statusText: 'Forbidden' },
        };
        const counts = (open: number) => ({
          open,
          pastTarget: 2,
          dosesDue: 0,
          dosesLate: 0,
          changeToken: TOKEN_A,
          asOf: '2026-11-05T05:48:00Z',
        });

        /**
         * The Bed board tab: the 60-second summary poll is on its way when the list read (started by
         * leaving the Safety tab) is refused. The poll answers after the refusal, with other counts.
         */
        async function refusedWithPollInFlight(): Promise<{ view: HTMLElement; poll: TestRequest }> {
          vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
          const view = setup('DOCTOR');
          await loadOk(list({ safety: safety(2, LATE) }), false);
          await advance(60_000);
          const poll = http.expectOne(SUMMARY);
          const page = fixture.componentInstance as unknown as { selectTab(tab: string): void };
          page.selectTab('safety');
          page.selectTab('board');
          await settle();
          http.match((r) => r.url === BOARD || r.url === EVENTS);
          http.expectOne(listUrl).flush(forbidden.body, forbidden.init);
          await settle();
          expect(view.querySelector('.notice.err')!.textContent).toContain('No access.');
          return { view, poll };
        }

        it('a poll answered after the refusal does not read the list or bring it back', async () => {
          const { view, poll } = await refusedWithPollInFlight();
          poll.flush(counts(3));
          await settle();
          http.expectNone(listUrl);
          await advance(180_000);
          http.expectNone(SUMMARY);
          http.expectNone(listUrl);
          expect(view.textContent).not.toContain('Asha Patil');
          expect(view.querySelector('.notice.err')).not.toBeNull();
        });

        it('Try again reads the list and the poll runs again', async () => {
          const { view, poll } = await refusedWithPollInFlight();
          poll.flush(counts(3));
          await settle();
          button(view, 'Try again')!.click();
          await settle();
          http.expectOne(listUrl).flush(list({ safety: safety(2, LATE) }));
          await settle();
          expect(view.querySelector('.notice.err')).toBeNull();
          await advance(60_000);
          http.expectOne(SUMMARY).flush(counts(3));
          await settle();
          http.expectOne(listUrl).flush(list({ safety: safety(3, LATE) }));
          await settle();
        });

        it('Try again met by another refusal keeps the page closed', async () => {
          const { view, poll } = await refusedWithPollInFlight();
          poll.flush(counts(3));
          button(view, 'Try again')!.click();
          await settle();
          http.expectOne(listUrl).flush(forbidden.body, forbidden.init);
          await settle();
          await advance(120_000);
          http.expectNone(SUMMARY);
          http.expectNone(listUrl);
        });
      });

      it('reads the list again when the user leaves the Safety tab, so the banner is current', async () => {
        const view = setup('DOCTOR');
        await loadOk(list({ safety: safety(2, LATE) }), false);
        view.querySelector<HTMLElement>('#ipd-tab-safety')!.click();
        await settle();
        flushSafetyTab();
        await settle();
        view.querySelector<HTMLElement>('#ipd-tab-admitted')!.click();
        await settle();
        http.expectOne(listUrl).flush(list({ safety: safety(0) }));
        await settle();
        expect(view.querySelector('[data-testid=safety-banner]')).toBeNull();
        expect(view.querySelector('#ipd-tab-safety .c')).toBeNull();
      });
    });
  });

  describe('Admitted patient cards (SAH-36)', () => {
    const SUMMARY = `${IPD}/safety/summary`;
    const LATE = [{ escalationId: 'e1', admissionId: 'a1', patientName: 'Asha Patil', ewsScore: 8 }];
    const safety = (): AdmissionSafety => ({
      counts: { open: 1, pastTarget: 1, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: '2026-10-24T07:30:00Z' },
      pastTarget: LATE,
    });
    const names = (view: HTMLElement) =>
      Array.from(view.querySelectorAll('[data-testid=card-name]')).map((n) => n.textContent!.trim());
    const advance = async (ms: number) => {
      vi.advanceTimersByTime(ms);
      await settle();
    };

    afterEach(() => vi.useRealTimers());

    it('opens the Admitted tab as Cards, 3 per row, with the toolbar under the red banner', async () => {
      const view = setup('DOCTOR');
      await loadOk(list({ safety: safety() }));
      const panel = view.querySelector('[role=tabpanel]')!;
      const order = Array.from(panel.querySelectorAll('[data-testid=safety-banner], [data-testid=admitted-toolbar], [data-testid=admitted-cards]')).map(
        (e) => e.getAttribute('data-testid'),
      );
      expect(order).toEqual(['safety-banner', 'admitted-toolbar', 'admitted-cards']);
      expect(view.querySelector('[data-testid=admitted-cards]')!.classList.contains('cols-3')).toBe(true);
      expect(names(view)).toEqual(['Asha Patil', 'Ravi Shah', 'Sunita More']);
      expect(view.querySelector('.iptbl')).toBeNull();
    });

    it('keeps the Admitted tab number at all admitted patients while a filter is on', async () => {
      const view = setup();
      await loadOk();
      const search = view.querySelector<HTMLInputElement>('[data-testid=admitted-search]')!;
      search.value = 'asha';
      search.dispatchEvent(new Event('input', { bubbles: true }));
      await settle();
      expect(names(view)).toEqual(['Asha Patil']);
      expect(view.querySelector('#ipd-tab-admitted .c')!.textContent).toBe('3');
    });

    it('keeps the choices when the user goes to another tab and back', async () => {
      const view = setup();
      await loadOk();
      view.querySelector<HTMLElement>('[data-view=table]')!.click();
      await settle();
      view.querySelector<HTMLElement>('#ipd-tab-board')!.click();
      await settle();
      http
        .match((r) => r.url === `${IPD}/board`)
        .filter((r) => !r.cancelled)
        .forEach((r) => r.flush(EMPTY_BOARD));
      view.querySelector<HTMLElement>('#ipd-tab-admitted')!.click();
      await settle();
      expect(view.querySelector('.iptbl')).not.toBeNull();
    });

    it('opens the admission sheet from the name on its main view', async () => {
      const view = setup('FRONT_DESK', false);
      await loadOk();
      view.querySelector<HTMLElement>('[data-testid=card-name]')!.click();
      expect(dialog.open.mock.calls[0][1].data).toEqual({ id: 'a1' });
    });

    it('opens the admission sheet at Move for Transfer and at the discharge view for Discharge, in memory only', async () => {
      const view = setup('OWNER', false);
      await loadOk();
      const router = TestBed.inject(Router);
      const url = router.url;
      view.querySelector<HTMLElement>('app-patient-card [data-act=transfer]')!.click();
      expect(dialog.open.mock.calls[0][1].data).toEqual({ id: 'a1', startView: 'move' });
      view.querySelector<HTMLElement>('app-patient-card [data-act=discharge]')!.click();
      expect(dialog.open.mock.calls[1][1].data).toEqual({ id: 'a1', startView: 'discharge' });
      expect(router.url).toBe(url);
    });

    it('opens the sheet at the Running bill and deposit part for the Deposit action, with autoFocus off, when the clinic switched it on (SAH-37 AC40)', async () => {
      const view = setup('OWNER', false);
      const withDeposit = list();
      withDeposit.cardSetup = {
        ...resolvedStandard(),
        actions: ['nursing', 'emr', 'transfer', 'discharge', 'deposit'],
      };
      await loadOk(withDeposit);
      view.querySelector<HTMLElement>('app-patient-card [data-act=deposit]')!.click();
      expect(dialog.open.mock.calls[0][1].data).toEqual({ id: 'a1', startView: 'deposit' });
      expect(dialog.open.mock.calls[0][1].autoFocus).toBe(false);
    });

    it('shows Customize card to the owner, and it opens Clinic setup → Patient card (SAH-37 AC62)', async () => {
      const owner = setup('OWNER', false);
      await loadOk();
      const link = owner.querySelector<HTMLAnchorElement>('[data-testid=customize-card]')!;
      expect(link.textContent!.trim()).toBe('Customize card');
      expect(link.getAttribute('href')).toBe('/app/config/ipcard');
    });

    it.each(['NURSE', 'DOCTOR', 'FRONT_DESK'])('has no Customize card button at all for %s', async (role) => {
      const view = setup(role, false);
      await loadOk();
      expect(view.querySelector('[data-testid=customize-card]')).toBeNull();
      expect(view.textContent).not.toContain('Customize card');
    });

    it('opens the sheet with autoFocus off only for Transfer (F-1), and keeps restoreFocus and the default focus for the others', async () => {
      const view = setup('OWNER', false);
      await loadOk();
      view.querySelector<HTMLElement>('[data-testid=card-name]')!.click();
      view.querySelector<HTMLElement>('app-patient-card [data-act=transfer]')!.click();
      view.querySelector<HTMLElement>('app-patient-card [data-act=discharge]')!.click();
      const [details, move, discharge] = dialog.open.mock.calls.map((c) => c[1]);
      expect(move.autoFocus).toBe(false);
      expect(move.restoreFocus).toBeUndefined();
      expect('autoFocus' in details).toBe(false);
      expect('autoFocus' in discharge).toBe(false);
    });

    it('reloads the cards after a sheet closed with a change, and not after an unchanged close', async () => {
      const view = setup('OWNER', true);
      await loadOk();
      view.querySelector<HTMLElement>('app-patient-card [data-act=transfer]')!.click();
      http.expectOne(listUrl).flush(list());
      http
        .match((r) => r.url === `${IPD}/board`)
        .filter((r) => !r.cancelled)
        .forEach((r) => r.flush(EMPTY_BOARD));
      await settle();
    });

    it('opens the Nursing sheet for the admission and reloads when it saved', async () => {
      const view = setup('NURSE', true);
      await loadOk();
      view.querySelector<HTMLElement>('app-patient-card [data-act=nursing]')!.click();
      const [component, config] = dialog.open.mock.calls[0];
      expect(component).toBe(NursingSheetComponent);
      expect(config.data).toEqual({ admissionId: 'a1' });
      expect(config.panelClass).toBe('sheet-panel');
      http.expectOne(listUrl).flush(list());
      await settle();
    });

    it('opens the EMR sheet for the admission and does not reload', async () => {
      const view = setup('DOCTOR', undefined);
      await loadOk();
      view.querySelector<HTMLElement>('app-patient-card [data-act=emr]')!.click();
      const [component, config] = dialog.open.mock.calls[0];
      expect(component).toBe(EmrSheetComponent);
      expect(config.data).toEqual({ admissionId: 'a1' });
      await settle();
    });

    describe('60-second reload of the Admitted tab', () => {
      it('reads the whole list every 60 seconds and not the counts-only summary, keeping the filters', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list({ safety: safety() }));
        const search = view.querySelector<HTMLInputElement>('[data-testid=admitted-search]')!;
        search.value = 'sunita';
        search.dispatchEvent(new Event('input', { bubbles: true }));
        view.querySelector<HTMLElement>('[data-cols="4"]')!.click();
        await settle();
        await advance(59_000);
        http.expectNone(listUrl);
        await advance(1_000);
        http.expectNone(SUMMARY);
        const second = list({ safety: safety(), asOf: '2026-10-24T07:31:00Z' });
        second.items[2] = { ...second.items[2], goingHomeToday: true };
        http.expectOne(listUrl).flush(second);
        await settle();
        expect(names(view)).toEqual(['Sunita More']);
        expect(view.querySelector<HTMLInputElement>('[data-testid=admitted-search]')!.value).toBe('sunita');
        expect(view.querySelector('[data-testid=admitted-cards]')!.classList.contains('cols-4')).toBe(true);
        expect(view.querySelector('[data-flag=home]')).not.toBeNull();
        expect(view.querySelector('[data-testid=admitted-stale]')).toBeNull();
      });

      it('keeps the last answer with "Could not refresh. Showing data from <time>." when a reload fails, and clears it after a good one', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list());
        await advance(60_000);
        http.expectOne(listUrl).flush({}, { status: 500, statusText: 'Server Error' });
        await settle();
        expect(names(view).length).toBe(3);
        expect(view.querySelector('[data-testid=admitted-stale]')!.textContent).toContain(
          'Could not refresh. Showing data from 1:00 PM.',
        );
        await advance(60_000);
        http.expectOne(listUrl).flush(list({ asOf: '2026-10-24T07:32:00Z' }));
        await settle();
        expect(view.querySelector('[data-testid=admitted-stale]')).toBeNull();
      });

      it('a 403 on the 60-second reload clears the list and shows the error notice of the first load (G-2)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list());
        expect(names(view).length).toBe(3);
        await advance(60_000);
        http
          .expectOne(listUrl)
          .flush(
            { type: 'https://sahaj.in/problems/forbidden', title: 'Forbidden', status: 403, detail: 'You cannot see this.' },
            { status: 403, statusText: 'Forbidden' },
          );
        await settle();
        expect(names(view)).toEqual([]);
        expect(view.querySelector('[data-testid=admitted-stale]')).toBeNull();
        expect(view.querySelector('.notice.err')!.textContent).toContain('You cannot see this.');
        expect(view.querySelector('.iptbl')).toBeNull();
      });

      it('a 403 module-off on the 60-second reload clears the list and shows the lock notice (G-2)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list());
        await advance(60_000);
        http
          .expectOne(listUrl)
          .flush(
            { type: 'https://sahaj.in/problems/module-off', title: 'Module off', status: 403, detail: 'off' },
            { status: 403, statusText: 'Forbidden' },
          );
        await settle();
        expect(names(view)).toEqual([]);
        expect(view.textContent).toContain('not on for your clinic');
        await advance(60_000);
        http.expectNone(listUrl);
      });

      it('a network error on the reload keeps the list with the stale line (G-2)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('DOCTOR');
        await loadOk(list());
        await advance(60_000);
        http.expectOne(listUrl).error(new ProgressEvent('error'));
        await settle();
        expect(names(view).length).toBe(3);
        expect(view.querySelector('[data-testid=admitted-stale]')).not.toBeNull();
      });

      it('does not reload while the browser tab is hidden and reloads once when it shows again', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        setup('DOCTOR');
        await loadOk(list());
        const hide = (hidden: boolean) => {
          Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
          document.dispatchEvent(new Event('visibilitychange'));
        };
        try {
          hide(true);
          await advance(180_000);
          http.expectNone(listUrl);
          hide(false);
          await settle();
          http.expectOne(listUrl).flush(list());
          await settle();
        } finally {
          delete (document as unknown as Record<string, unknown>)['hidden'];
        }
      });

      it('does not start a second reload while one is still on its way', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        setup('DOCTOR');
        await loadOk(list());
        await advance(60_000);
        const pending = http.expectOne(listUrl);
        await advance(60_000);
        http.expectNone(listUrl);
        pending.flush(list());
        await settle();
      });

      it('does not reload the list from the Admitted timer on the Bed board, where the summary poll runs', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        setup('DOCTOR');
        await loadOk(list({ safety: safety() }), false);
        await advance(60_000);
        http.expectOne(SUMMARY).flush({ open: 1, pastTarget: 1, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: '2026-10-24T07:31:00Z' });
        await settle();
        http.expectNone(listUrl);
      });
    });
  });

  describe('Pharmacist checks (SAH-31)', () => {
    const SUMMARY = `${IPD}/pharmacist-checks/summary`;
    const CHECKS = `${IPD}/pharmacist-checks`;
    const SAFETY_SUMMARY = `${IPD}/safety/summary`;
    const BOARD = `${IPD}/safety`;
    const EVENTS = `${IPD}/clinical-events`;
    const AS_OF = '2026-11-06T05:47:00Z';
    const open = (open: number): AdmissionSafety => ({
      counts: { open, pastTarget: 0, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: AS_OF },
      pastTarget: [],
    });
    const summary = (waiting: number, changeToken = TOKEN_A) => ({ waiting, changeToken, asOf: AS_OF });
    const tabs = (view: HTMLElement) =>
      Array.from(view.querySelectorAll<HTMLElement>('[role=tab]')).map((t) =>
        t.textContent!.replace(/\s+/g, ' ').trim().replace(/(\D)(\d+)$/, '$1 $2'),
      );
    const checks = (waiting: number, items: unknown[] = []) => ({
      state: 'on',
      asOf: AS_OF,
      waiting,
      verifierName: 'Rahul Deshmukh',
      rejectReasons: [],
      items,
      nextCursor: null,
    });
    const advance = async (ms: number) => {
      vi.advanceTimersByTime(ms);
      await settle();
    };

    afterEach(() => vi.useRealTimers());

    describe('owner', () => {
      it('adds the orders waiting to the open escalations on the Safety tab', async () => {
        const view = setup('OWNER');
        await loadOk(list({ safety: open(2) }), false);
        http.expectOne(SUMMARY).flush(summary(3));
        await settle();
        expect(tabs(view)).toEqual(['Bed board', 'Admitted 3', 'Admission requests', 'Emergency 3', 'Safety 5']);
      });

      it('shows the waiting number alone when no escalation is open, and hides it at 0', async () => {
        const view = setup('OWNER');
        await loadOk(list({ safety: open(0) }), false);
        http.expectOne(SUMMARY).flush(summary(4));
        await settle();
        expect(tabs(view)[4]).toBe('Safety 4');
      });

      it('hides the number when both are 0', async () => {
        const view = setup('OWNER');
        await loadOk(list({ safety: open(0) }), false);
        http.expectOne(SUMMARY).flush(summary(0));
        await settle();
        expect(view.querySelector('#ipd-tab-safety .c')).toBeNull();
      });

      it('shows the tile after Escalations open and the panel after the escalation panel, before the audit trail', async () => {
        const view = setup('OWNER');
        await loadOk(list({ safety: open(0) }), false);
        http.expectOne(SUMMARY).flush(summary(1));
        await settle();
        view.querySelector<HTMLElement>('#ipd-tab-safety')!.click();
        await settle();
        http.expectOne(BOARD).flush(boardOf());
        http.expectOne((r) => r.url === EVENTS).flush(pageOf());
        await settle();
        http.expectOne((r) => r.url === CHECKS).flush(checks(1));
        await settle();
        const tiles = Array.from(view.querySelectorAll('.safety .kpis > *')).map((n) => n.getAttribute('data-kpi') ?? n.tagName.toLowerCase());
        expect(tiles).toEqual(['escalations', 'app-pharmacist-tile', 'doses']);
        expect(view.querySelector('[data-kpi=pharmacist] .v')!.textContent).toBe('1');
        const panels = Array.from(view.querySelectorAll('.safety > *')).map((n) => n.tagName.toLowerCase());
        expect(panels).toEqual(['div', 'section', 'section', 'app-pharmacist-panel', 'app-medicine-round', 'section']);
        expect(view.querySelector('#sf-esc-h')).not.toBeNull();
        expect(view.querySelector('#ph-h')).not.toBeNull();
        expect(view.querySelector('#sf-aud-h')).not.toBeNull();
      });

      it('reads the waiting orders every 60 seconds with the escalation counts, and follows a change', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('OWNER');
        await loadOk(list({ safety: open(2) }), false);
        http.expectOne(SUMMARY).flush(summary(3));
        await settle();
        await advance(60_000);
        http.expectOne(SAFETY_SUMMARY).flush({ open: 2, pastTarget: 0, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: AS_OF });
        http.expectOne(SUMMARY).flush(summary(4));
        await settle();
        expect(tabs(view)[4]).toBe('Safety 6');
      });

      it('does not read the waiting orders while the browser tab is hidden, and reads them at once when it shows again', async () => {
        const hide = (hidden: boolean) => {
          Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
          document.dispatchEvent(new Event('visibilitychange'));
        };
        try {
          vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
          setup('OWNER');
          await loadOk(list({ safety: open(2) }), false);
          http.expectOne(SUMMARY).flush(summary(3));
          await settle();
          hide(true);
          await advance(180_000);
          http.expectNone(SUMMARY);
          hide(false);
          await settle();
          http.expectOne(SUMMARY).flush(summary(3));
          http.expectOne(SAFETY_SUMMARY).flush({ open: 2, pastTarget: 0, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: AS_OF });
          await settle();
        } finally {
          delete (document as unknown as Record<string, unknown>)['hidden'];
        }
      });

      it('has no pharmacist call when there is no Safety tab (nobody admitted)', async () => {
        setup('OWNER');
        await loadOk(list({ safety: open(0), items: [] }), false);
        http.expectNone(SUMMARY);
      });
    });

    describe('doctor and nurse', () => {
      it('see no tile, no panel and make no pharmacist call', async () => {
        const view = setup('DOCTOR');
        await loadOk(list({ safety: open(1) }), false);
        http.expectNone(SUMMARY);
        view.querySelector<HTMLElement>('#ipd-tab-safety')!.click();
        await settle();
        http.expectOne(BOARD).flush(boardOf());
        http.expectOne((r) => r.url === EVENTS).flush(pageOf());
        await settle();
        expect(view.querySelector('[data-kpi=pharmacist]')).toBeNull();
        expect(view.querySelector('app-pharmacist-panel')).toBeNull();
        http.expectNone((r) => r.url === CHECKS);
      });
    });

    describe('refused reads (SAH-40)', () => {
      const QUEUE_URL = `${IPD}/emergency`;
      const forbidden = (slug = 'forbidden') => ({
        body: { type: `https://sahaj.in/problems/${slug}`, title: 'x', status: 403, detail: 'No access.' },
        init: { status: 403, statusText: 'Forbidden' },
      });
      const refuse = (req: TestRequest, slug = 'forbidden') => {
        const f = forbidden(slug);
        req.flush(f.body, f.init);
      };
      const erQueue = (over: Record<string, unknown> = {}) => ({
        state: 'on',
        scale: 3,
        levels: [{ level: 1, name: 'Red', description: 'Immediate', tone: 'crit', targetMinutes: 5 }],
        defaultLevel: 1,
        unknownPatientRegistration: true,
        baysTotal: 2,
        baysFree: 1,
        counts: { writable: true, queue: 7, overTarget: 2 },
        cases: [
          {
            id: 'c1', displayName: 'Meena Joshi', age: 40, sex: 'F', complaint: 'Fall from height', identityPending: false,
            level: 1, levelName: 'Red', bay: 'ER-1', status: 'WAITING', arrivedAt: '2026-11-05T05:00:00Z',
            waitingMinutes: 2, targetMinutes: 5, overTarget: false, seenAt: null,
          },
        ],
        canRegister: true,
        canRetriage: true,
        canAct: true,
        canAdmit: true,
        asOf: '2026-11-05T05:35:00Z',
        ...over,
      });
      const store = () => fixture.debugElement.injector.get(PharmacistStore);

      async function openEmergency(role = 'NURSE') {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup(role, null, ['today', 'emergency', 'ipd'], { tab: 'er' });
        await loadOk(list({ emergency: { writable: true, queue: 3, overTarget: 1 } }), false);
        http.expectOne(QUEUE_URL).flush(erQueue());
        await settle();
        return view;
      }

      it('Emergency 403 forbidden: the queue goes, the tile and the tab follow the Admitted list answer (AC 4)', async () => {
        const view = await openEmergency();
        expect(view.querySelector('[data-kpi=emergency] .v')!.textContent!.trim()).toBe('7');
        expect(view.textContent).toContain('Meena Joshi');
        await advance(60_000);
        refuse(http.expectOne(QUEUE_URL));
        await settle();
        expect(view.textContent).not.toContain('Meena Joshi');
        expect(view.querySelector('app-emergency .notice.err')!.textContent).toContain('No access.');
        expect(view.querySelector('[data-kpi=emergency] .v')!.textContent!.trim()).toBe('3');
        expect(tabs(view)).toContain('Emergency 3');
        http.expectNone(listUrl);
      });

      it('Emergency 403 module-off: the lock page shows and nothing of the page stays (AC 2)', async () => {
        const view = await openEmergency('OWNER');
        http.match((r) => r.url === SUMMARY || r.url === CHECKS);
        await advance(60_000);
        refuse(http.expectOne(QUEUE_URL), 'module-off');
        await settle();
        expect(view.querySelector('.lockpage h2')!.textContent).toContain('In-patient care (IPD) is not on for your clinic');
        expect(view.textContent).not.toContain('Meena Joshi');
        expect(view.querySelector('[role=tab]')).toBeNull();
        expect(view.querySelector('[data-kpi=emergency]')).toBeNull();
        expect(view.querySelector('a[href="/app/config/modules"]')).not.toBeNull();
        await advance(180_000);
        http.expectNone(listUrl);
        http.expectNone(QUEUE_URL);
      });

      describe('focus when Emergency reports IPD off (SAH-40 R-2)', () => {
        const moduleOff = () =>
          new ApiError({ type: 'https://sahaj.in/problems/module-off', title: 'x', status: 403, detail: 'off' });
        const emit = (focusWasInside: boolean) => {
          const er = fixture.debugElement.query(By.directive(EmergencyComponent));
          (er.componentInstance as EmergencyComponent).refused.emit({ error: moduleOff(), focusWasInside });
        };

        it('moves focus to the lock heading when the Emergency sheet had it (the sheet is already closed)', async () => {
          const view = await openEmergency('OWNER');
          http.match((r) => r.url === SUMMARY || r.url === CHECKS);
          (document.activeElement as HTMLElement | null)?.blur();
          expect(document.activeElement).toBe(document.body);
          emit(true);
          await settle();
          expect(view.querySelector('.lockpage')).not.toBeNull();
          expect(document.activeElement).toBe(view.querySelector('#ipd-lock-h'));
        });

        it('leaves focus alone when it was not on the page or its sheet', async () => {
          const view = await openEmergency('OWNER');
          http.match((r) => r.url === SUMMARY || r.url === CHECKS);
          (document.activeElement as HTMLElement | null)?.blur();
          emit(false);
          await settle();
          expect(view.querySelector('.lockpage')).not.toBeNull();
          expect(document.activeElement).toBe(document.body);
        });
      });

      it('Emergency 403 module-off drops the list held by the page (AC 2, 4)', async () => {
        const view = await openEmergency();
        await advance(60_000);
        refuse(http.expectOne(QUEUE_URL), 'module-off');
        await settle();
        expect(view.querySelector('.lockpage')).not.toBeNull();
        expect(view.querySelector('app-admitted-panel')).toBeNull();
        expect(view.querySelector('.kpis')).toBeNull();
      });

      it('an Admitted-list refusal drops the pharmacist rows, not only hides them (AC 20)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('OWNER');
        await loadOk(list({ safety: open(2) }), true);
        http.expectOne(SUMMARY).flush(summary(3));
        await settle();
        expect(store().waiting()).toBe(3);
        await advance(60_000);
        http.expectOne(SUMMARY).flush(summary(3));
        refuse(http.expectOne(listUrl));
        await settle();
        expect(view.querySelector('.notice.err')).not.toBeNull();
        expect(store().waiting()).toBeNull();
        expect(store().items()).toEqual([]);
        expect(store().meta()).toBeNull();
      });

      it('a pharmacist counts-poll module-off on the owner page clears the list and locks the page (AC 13, 15)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('OWNER');
        await loadOk(list({ safety: open(2) }), true);
        http.expectOne(SUMMARY).flush(summary(3));
        await settle();
        await advance(60_000);
        http.match(listUrl);
        refuse(http.expectOne(SUMMARY), 'module-off');
        await settle();
        expect(view.querySelector('.lockpage')).not.toBeNull();
        expect(view.textContent).not.toContain('Asha Patil');
        expect(view.querySelector('app-admitted-panel')).toBeNull();
        await advance(180_000);
        http.expectNone(listUrl);
      });

      it('owner: a counts-poll 403 hides the Safety tab number and shows "—" in the tile, never 0 (AC 18)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('OWNER');
        await loadOk(list({ safety: open(2) }), false);
        http.expectOne(SUMMARY).flush(summary(3));
        await settle();
        view.querySelector<HTMLElement>('#ipd-tab-safety')!.click();
        await settle();
        http.expectOne(BOARD).flush(boardOf());
        http.expectOne((r) => r.url === EVENTS).flush(pageOf());
        await settle();
        http.expectOne((r) => r.url === CHECKS).flush(checks(3, []));
        await settle();
        expect(tabs(view)[4]).toBe('Safety 5');
        expect(view.querySelector('[data-kpi=pharmacist] .v')!.textContent).toBe('3');

        await advance(60_000);
        http.expectOne(BOARD).flush(boardOf());
        http.expectOne((r) => r.url === EVENTS).flush(pageOf());
        refuse(http.expectOne(SUMMARY));
        await settle();
        expect(tabs(view)[4]).toBe('Safety');
        expect(view.querySelector('#ipd-tab-safety .c')).toBeNull();
        expect(view.querySelector('[data-kpi=pharmacist] .v')!.textContent).toBe('—');
        expect(view.querySelector('[data-testid=pharmacist-error]')!.textContent).toContain('No access.');
        expect(view.querySelector('[data-testid=pharmacist-empty]')).toBeNull();

        await advance(180_000);
        http.expectNone(SUMMARY);
        http.expectNone((r) => r.url === CHECKS);
        http.match(BOARD);
        http.match((r) => r.url === EVENTS);
      });

      it('pharmacist: a counts-poll 403 shows the refusal, not an empty tab (AC 22)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('PHARMACIST', null, ['today', 'ipd']);
        await settle();
        http.expectOne((r) => r.url === CHECKS).flush(checks(2, []));
        await settle();
        expect(tabs(view)).toEqual(['Safety 2']);
        await advance(60_000);
        refuse(http.expectOne(SUMMARY));
        await settle();
        expect(tabs(view)).toEqual(['Safety']);
        expect(view.querySelector('[data-testid=pharmacist-error]')).not.toBeNull();
        expect(view.querySelector('[data-testid=pharmacist-empty]')).toBeNull();
        expect(view.querySelector('[data-kpi=pharmacist] .v')!.textContent).toBe('—');
        await advance(120_000);
        http.expectNone(SUMMARY);
        http.expectNone((r) => r.url === CHECKS);
      });

      it('pharmacist: a counts-poll module-off shows the lock page (AC 13, 22)', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('PHARMACIST', null, ['today', 'ipd']);
        await settle();
        http.expectOne((r) => r.url === CHECKS).flush(checks(2, []));
        await settle();
        await advance(60_000);
        refuse(http.expectOne(SUMMARY), 'module-off');
        await settle();
        expect(view.querySelector('.lockpage h2')!.textContent).toContain('In-patient care (IPD) is not on for your clinic');
        expect(view.querySelector('[role=tab]')).toBeNull();
      });

      describe('sheets and toasts when the page is refused (review fixes 3, 4, 5, 6, 8)', () => {
        interface Sheets {
          admit(bedId?: string): void;
          openNursing(id: string): void;
          openEmr(id: string): void;
          openAdmission(id: string): void;
          afterAdmitted(a: AdmissionDetail): void;
        }
        let closes: ReturnType<typeof vi.fn>[];
        const sheets = () => fixture.componentInstance as unknown as Sheets;
        const dismiss = () => TestBed.inject(MatSnackBar).dismiss as ReturnType<typeof vi.fn>;

        /** The Admitted tab with four page sheets open: Admit, Nursing, EMR and the Admission sheet. */
        async function withOpenSheets(): Promise<HTMLElement> {
          vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
          const view = setup('OWNER');
          await loadOk(list(), true);
          closes = [];
          dialog.open.mockImplementation(() => {
            const closed = new Subject<undefined>();
            const close = vi.fn(() => closed.next(undefined));
            closes.push(close);
            return { afterClosed: () => closed, close };
          });
          sheets().admit();
          sheets().openNursing('a1');
          sheets().openEmr('a1');
          sheets().openAdmission('a1');
          expect(closes).toHaveLength(4);
          return view;
        }

        it('IPD off (403 module-off on the list reload) closes every sheet the page opened and dismisses the toast', async () => {
          const view = await withOpenSheets();
          await advance(60_000);
          refuse(http.expectOne(listUrl), 'module-off');
          await settle();
          expect(view.querySelector('.lockpage')).not.toBeNull();
          for (const close of closes) {
            expect(close).toHaveBeenCalledTimes(1);
          }
          expect(dismiss()).toHaveBeenCalled();
        });

        it('another refusal of the list (403 forbidden) closes every sheet and dismisses the toast', async () => {
          const view = await withOpenSheets();
          await advance(60_000);
          refuse(http.expectOne(listUrl));
          await settle();
          expect(view.querySelector('.notice.err')).not.toBeNull();
          for (const close of closes) {
            expect(close).toHaveBeenCalledTimes(1);
          }
          expect(dismiss()).toHaveBeenCalled();
        });

        it('a pharmacist counts-poll module-off locks the page and closes the sheets too', async () => {
          vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
          const view = setup('OWNER');
          await loadOk(list({ safety: open(2) }), true);
          http.expectOne(SUMMARY).flush(summary(3));
          await settle();
          const closed = new Subject<undefined>();
          const close = vi.fn(() => closed.next(undefined));
          dialog.open.mockReturnValue({ afterClosed: () => closed, close });
          sheets().openNursing('a1');
          await advance(60_000);
          http.match(listUrl);
          refuse(http.expectOne(SUMMARY), 'module-off');
          await settle();
          expect(view.querySelector('.lockpage')).not.toBeNull();
          expect(close).toHaveBeenCalledTimes(1);
        });

        it('does not close a sheet that the user already closed', async () => {
          await withOpenSheets();
          const [first] = closes;
          (first as unknown as () => void)();
          expect(first).toHaveBeenCalledTimes(1);
          await advance(60_000);
          refuse(http.expectOne(listUrl), 'module-off');
          await settle();
          expect(first).toHaveBeenCalledTimes(1);
          expect(closes[1]).toHaveBeenCalledTimes(1);
        });

        it('a sheet that saves after the page locked does not read the list again (review fix 4)', async () => {
          await withOpenSheets();
          await advance(60_000);
          refuse(http.expectOne(listUrl), 'module-off');
          await settle();
          sheets().afterAdmitted({
            patientName: 'Asha Patil',
            bedCode: '101-A',
            ipNumber: 'IP/26-27/0042',
            depositPaise: 0,
          } as AdmissionDetail);
          http.expectNone(listUrl);
        });

        it('a Safety board refusal hides the Safety tab number until Try again (review fix 6)', async () => {
          vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
          const view = setup('OWNER');
          await loadOk(list({ safety: open(2) }), false);
          http.expectOne(SUMMARY).flush(summary(3));
          await settle();
          view.querySelector<HTMLElement>('#ipd-tab-safety')!.click();
          await settle();
          http.expectOne(BOARD).flush(boardOf());
          http.expectOne((r) => r.url === EVENTS).flush(pageOf());
          await settle();
          http.expectOne((r) => r.url === CHECKS).flush(checks(3, []));
          await settle();
          expect(tabs(view)[4]).toBe('Safety 5');

          await advance(60_000);
          http.expectOne(SUMMARY).flush(summary(3));
          refuse(http.expectOne(BOARD));
          http.expectOne((r) => r.url === EVENTS);
          await settle();
          expect(tabs(view)[4]).toBe('Safety');
          expect(view.querySelector('#ipd-tab-safety .c')).toBeNull();

          view.querySelector<HTMLElement>('app-safety .notice.err button')!.click();
          await settle();
          http.expectOne(BOARD).flush(boardOf());
          http.expectOne((r) => r.url === EVENTS).flush(pageOf());
          await settle();
          http.expectOne((r) => r.url === CHECKS).flush(checks(3, []));
          await settle();
          expect(tabs(view)[4]).toBe('Safety 5');
        });

        it('after Try again on the page the pharmacist count is read at once, not a minute later (review fix 8)', async () => {
          vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
          const view = setup('OWNER');
          await loadOk(list({ safety: open(2) }), true);
          http.expectOne(SUMMARY).flush(summary(3));
          await settle();
          await advance(60_000);
          refuse(http.expectOne(listUrl));
          http.match(SUMMARY);
          await settle();
          view.querySelector<HTMLElement>('.notice.err button')!.click();
          http.expectOne(listUrl).flush(list({ safety: open(2) }));
          await settle();
          http.expectOne(SUMMARY).flush(summary(3));
        });
      });

      it('the tile shows "—" before the first count is read, never 0', async () => {
        const view = setup('PHARMACIST', null, ['today', 'ipd']);
        await settle();
        expect(view.querySelector('[data-kpi=pharmacist] .v')!.textContent).toBe('—');
        http.expectOne((r) => r.url === CHECKS).flush(checks(0, []));
        await settle();
        expect(view.querySelector('[data-kpi=pharmacist] .v')!.textContent).toBe('0');
      });
    });

    describe('pharmacist', () => {
      it('sees only the Safety tab with the tile and the panel, and never reads the admissions or the escalations', async () => {
        const view = setup('PHARMACIST', null, ['today', 'ipd']);
        await settle();
        http.expectNone(listUrl);
        http.expectNone(`${IPD}/layout`);
        http.expectNone(BOARD);
        http.expectNone(SAFETY_SUMMARY);
        http.expectOne((r) => r.url === CHECKS).flush(checks(2));
        await settle();
        expect(tabs(view)).toEqual(['Safety 2']);
        expect(view.querySelector('h1')!.textContent).toBe('Beds and admissions');
        expect(view.querySelector('.lead')).toBeNull();
        expect(view.querySelector('.kpi[data-kpi=occupied]')).toBeNull();
        expect(view.querySelector('[data-kpi=pharmacist] .v')!.textContent).toBe('2');
        expect(view.querySelector('#ph-h')).not.toBeNull();
        expect(view.querySelector('#ipd-tab-safety')!.getAttribute('aria-selected')).toBe('true');
        expect(Array.from(view.querySelectorAll('.pagehead button, .pagehead a'))).toHaveLength(0);
      });

      it('shows the tab with no number and "No orders waiting." when nothing waits', async () => {
        const view = setup('PHARMACIST', null, ['today', 'ipd']);
        await settle();
        http.expectOne((r) => r.url === CHECKS).flush(checks(0));
        await settle();
        expect(tabs(view)).toEqual(['Safety']);
        expect(view.querySelector('[data-testid=pharmacist-empty]')!.textContent).toBe('No orders waiting.');
      });

      it('reads the counts every 60 seconds and reloads the panel when the count changes', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        const view = setup('PHARMACIST', null, ['today', 'ipd']);
        await settle();
        http.expectOne((r) => r.url === CHECKS).flush(checks(1));
        await settle();
        await advance(60_000);
        http.expectOne(SUMMARY).flush(summary(2));
        await settle();
        http.expectOne((r) => r.url === CHECKS).flush(checks(2));
        await settle();
        expect(tabs(view)).toEqual(['Safety 2']);
      });

      it('shows the IPD lock notice when IPD is off', async () => {
        const view = setup('PHARMACIST', null, ['today', 'ipd']);
        await settle();
        http.expectOne((r) => r.url === CHECKS).flush(
          { type: 'https://sahaj.in/problems/module-off', title: 'x', status: 403, detail: 'off' },
          { status: 403, statusText: 'Forbidden' },
        );
        await settle();
        expect(view.querySelector('.lockpage h2')!.textContent).toContain('In-patient care (IPD) is not on for your clinic');
        expect(view.querySelector('[role=tab]')).toBeNull();
      });
    });
  });

  describe('ward scope and break-glass (SAH-34)', () => {
    const WARDS = { kind: 'WARDS' as const, visible: 1, admitted: 12, hasWard: true, canOpenEmergencyAccess: true };
    const counts = (over: Partial<AdmissionSafety['counts']> = {}): AdmissionSafety => ({
      counts: { open: 0, pastTarget: 0, dosesDue: 0, dosesLate: 0, changeToken: TOKEN_A, asOf: '2026-11-05T05:47:00Z', ...over },
      pastTarget: [],
    });
    const scoped = (partial: Partial<AdmissionList> = {}): AdmissionList =>
      list({ scope: WARDS, items: [{ ...ROW, mine: true }], ...partial });
    const tabs = (view: HTMLElement) =>
      Array.from(view.querySelectorAll<HTMLElement>('[role=tab]')).map((t) =>
        t.textContent!.replace(/\s+/g, ' ').trim().replace(/(\D)(\d+)$/, '$1 $2'),
      );
    const GRANT = {
      id: 'g1',
      admissionId: 'a9',
      patientName: 'Ravi Shah',
      bedCode: '202-B',
      reasonKey: 'DETERIORATING' as const,
      reasonLabel: 'Patient deteriorating, rapid response',
      openedAt: '2026-10-24T05:35:00Z',
      expiresAt: '2026-10-24T06:05:00Z',
    };

    describe('the Admitted tab number and the chip (AC 26, 31, 40, 41)', () => {
      it('shows N (what she can see) on the Admitted tab, not M, and the chip with N of M', async () => {
        const view = setup('NURSE');
        await loadOk(scoped());
        expect(tabs(view)).toContain('Admitted 1');
        const chip = view.querySelector('[data-testid=scope-chip]')!;
        expect(chip.textContent).toContain('Patients in their wards · 1 of 12');
      });

      it('shows the number of the rows for a user whose scope is all, and no chip', async () => {
        const view = setup('NURSE');
        await loadOk(list({ scope: { kind: 'ALL', visible: 3, admitted: 3, hasWard: true, canOpenEmergencyAccess: false } }));
        expect(tabs(view)).toContain('Admitted 3');
        expect(view.querySelector('[data-testid=scope-chip]')).toBeNull();
      });

      it('draws no chip before the first answer', () => {
        const view = setup('NURSE');
        expect(view.querySelector('[data-testid=scope-chip]')).toBeNull();
        http.expectOne(listUrl);
      });

      it('keeps the Safety tab for a scoped nurse with no ward (the mockup counts the clinic, M), so she reaches the board and its note', async () => {
        const view = setup('NURSE');
        await loadOk(
          scoped({ items: [], scope: { ...WARDS, visible: 0, hasWard: false }, safety: counts() }),
          false,
        );
        expect(tabs(view)).toEqual(['Bed board', 'Admitted 0', 'Admission requests', 'Emergency 3', 'Safety']);
      });

      it('has no Safety tab when nobody at all is admitted', async () => {
        const view = setup('NURSE');
        await loadOk(
          scoped({ items: [], scope: { ...WARDS, visible: 0, admitted: 0 }, safety: counts() }),
          false,
        );
        expect(tabs(view)).toEqual(['Bed board', 'Admitted 0', 'Admission requests', 'Emergency 3']);
      });

      it('passes the signed-in role to the list (My patients is for OWNER and DOCTOR, a nurse with a ward, never FRONT_DESK)', async () => {
        const nurse = setup('NURSE');
        await loadOk(scoped());
        expect(nurse.querySelector('[data-testid=my-patients]')).not.toBeNull();
      });

      it('does not show My patients to a front desk user', async () => {
        const view = setup('FRONT_DESK');
        await loadOk(list());
        expect(view.querySelector('[data-testid=my-patients]')).toBeNull();
      });
    });

    describe('the owner tab number (AC 71)', () => {
      it('adds the emergency access waiting for review to the Safety tab number', async () => {
        const view = setup('OWNER');
        await loadOk(list({ safety: counts({ open: 1, emergencyAccessWaiting: 2 }) }), false);
        http.expectOne(`${IPD}/pharmacist-checks/summary`).flush({ waiting: 3, changeToken: TOKEN_A, asOf: '2026-11-06T05:47:00Z' });
        await settle();
        // open 1 + pharmacist 3 + emergency access 2
        expect(tabs(view)).toContain('Safety 6');
      });

      it('does not add anything for a nurse', async () => {
        const view = setup('NURSE');
        await loadOk(scoped({ safety: counts({ open: 1 }) }), false);
        expect(tabs(view)).toContain('Safety 1');
      });
    });

    describe('Emergency access (AC 44, 51, 54)', () => {
      it('opens the sheet from the button on the chip', async () => {
        const view = setup('NURSE');
        await loadOk(scoped());
        view.querySelector<HTMLButtonElement>('[data-testid=emergency-access-button]')!.click();
        await settle();
        expect(dialog.open).toHaveBeenCalledTimes(1);
        expect(dialog.open.mock.calls[0][0]).toBe(EmergencyAccessSheetComponent);
        expect(dialog.open.mock.calls[0][1]).toEqual(
          expect.objectContaining({ panelClass: 'sheet-panel', ariaLabelledBy: 'eg-title' }),
        );
      });

      it('after a grant: the toast names the patient and says the owner will see it on the Safety board, the search is cleared and the list reads again', async () => {
        const view = setup('NURSE', { kind: 'granted', grant: GRANT });
        await loadOk(scoped());
        const search = view.querySelector<HTMLInputElement>('[data-testid=admitted-search]')!;
        search.value = 'asha';
        search.dispatchEvent(new Event('input', { bubbles: true }));
        await settle();
        expect(search.value).toBe('asha');
        view.querySelector<HTMLButtonElement>('[data-testid=emergency-access-button]')!.click();
        await settle();
        expect(snack).toHaveBeenCalledWith(
          'Emergency access to Ravi Shah for 30 minutes. The owner will see this on the Safety board.',
          'Close',
          expect.anything(),
        );
        expect(view.querySelector<HTMLInputElement>('[data-testid=admitted-search]')!.value).toBe('');
        http.expectOne(listUrl).flush(scoped());
        await settle();
      });

      it('after "already in your list": says so and reads the list again, without the grant toast', async () => {
        const view = setup('NURSE', { kind: 'in-scope', message: 'This patient is already in your list.' });
        await loadOk(scoped());
        view.querySelector<HTMLButtonElement>('[data-testid=emergency-access-button]')!.click();
        await settle();
        expect(snack).toHaveBeenCalledWith('This patient is already in your list.', 'Close', expect.anything());
        http.expectOne(listUrl).flush(scoped());
        await settle();
      });

      it('after a 403 inside the sheet: reads the list again with no toast', async () => {
        const view = setup('NURSE', { kind: 'reload' });
        await loadOk(scoped());
        view.querySelector<HTMLButtonElement>('[data-testid=emergency-access-button]')!.click();
        await settle();
        expect(snack).not.toHaveBeenCalled();
        http.expectOne(listUrl).flush(list());
        await settle();
        expect(view.querySelector('[data-testid=scope-chip]')).toBeNull();
      });

      it('does nothing when the sheet is cancelled', async () => {
        const view = setup('NURSE', undefined);
        await loadOk(scoped());
        view.querySelector<HTMLButtonElement>('[data-testid=emergency-access-button]')!.click();
        await settle();
        expect(snack).not.toHaveBeenCalled();
        http.expectNone(listUrl);
      });
    });

    describe('403 outside-scope on a sheet (AC 38, 39)', () => {
      const refuseOne = async (write: boolean, row: AdmittedPatientRow = { ...ROW, mine: true }) => {
        const view = setup('NURSE');
        await loadOk(scoped({ items: [row, { ...ROW, id: 'a2', patientName: 'Ravi Shah', mine: true }] }));
        TestBed.inject(ScopeRefusals).report('a1', write);
        await settle();
        return view;
      };

      it('says "This patient is not in your wards.", drops that patient at once and reads the list again', async () => {
        const view = await refuseOne(false);
        expect(snack).toHaveBeenCalledWith('This patient is not in your wards.', 'Close', expect.anything());
        const names = Array.from(view.querySelectorAll('[data-testid=card-name]')).map((n) => n.textContent!.trim());
        expect(names).toEqual(['Ravi Shah']);
        http.expectOne(listUrl).flush(scoped({ items: [{ ...ROW, id: 'a2', patientName: 'Ravi Shah', mine: true }] }));
        await settle();
        expect(view.textContent).not.toContain('Asha Patil');
      });

      it('adds "Nothing was saved." after a save', async () => {
        await refuseOne(true);
        expect(snack).toHaveBeenCalledWith('This patient is not in your wards. Nothing was saved.', 'Close', expect.anything());
        http.expectOne(listUrl).flush(scoped());
        await settle();
      });

      it('says the emergency access has ended for a patient she had through a grant', async () => {
        await refuseOne(false, { ...ROW, mine: false, emergencyAccess: { expiresAt: '2026-10-24T06:05:00Z' } });
        expect(snack).toHaveBeenCalledWith('Your emergency access to this patient has ended.', 'Close', expect.anything());
        http.expectOne(listUrl).flush(scoped());
        await settle();
      });

      it('does not lock the page: the tabs and the list stay (it is neither forbidden nor module-off)', async () => {
        const view = await refuseOne(true);
        expect(view.querySelector('.lockpage')).toBeNull();
        expect(view.querySelector('[role=tab]')).not.toBeNull();
        http.expectOne(listUrl).flush(scoped());
        await settle();
        expect(view.querySelector('[data-testid=admitted-cards]')).not.toBeNull();
      });

      it('moves focus to the tab panel once the page has rendered, when the focus fell to the page body', async () => {
        const view = setup('NURSE');
        await loadOk(scoped({ items: [{ ...ROW, mine: true }] }));
        (document.activeElement as HTMLElement | null)?.blur();
        expect(document.activeElement).toBe(document.body);
        TestBed.inject(ScopeRefusals).report('a1', false);
        await settle();
        expect(document.activeElement).toBe(view.querySelector('#ipd-panel'));
        http.expectOne(listUrl).flush(scoped());
        await settle();
      });

      it('leaves the focus alone when it is already on a control', async () => {
        setup('NURSE');
        await loadOk(scoped({ items: [{ ...ROW, mine: true }] }));
        const other = document.createElement('button');
        document.body.appendChild(other);
        other.focus();
        TestBed.inject(ScopeRefusals).report('a1', false);
        await settle();
        expect(document.activeElement).toBe(other);
        other.remove();
        http.expectOne(listUrl).flush(scoped());
        await settle();
      });

      it('keeps the search, the ward, the view and My patients through the reload (AC 40)', async () => {
        const view = setup('NURSE');
        await loadOk(scoped({ items: [{ ...ROW, mine: true }, { ...ROW, id: 'a2', patientName: 'Ravi Shah', mine: true }] }));
        view.querySelector<HTMLButtonElement>('[data-testid=my-patients]')!.click();
        await settle();
        TestBed.inject(ScopeRefusals).report('a2', false);
        await settle();
        http.expectOne(listUrl).flush(scoped());
        await settle();
        expect(view.querySelector('[data-testid=my-patients]')!.getAttribute('aria-pressed')).toBe('true');
      });
    });

    describe('403 on the list itself still clears the page (SAH-40)', () => {
      it('clears the list on forbidden', async () => {
        const view = setup('NURSE');
        await loadOk(scoped());
        const page = fixture.componentInstance as unknown as { refreshCounts(): void };
        page.refreshCounts();
        http
          .expectOne(listUrl)
          .flush(
            { type: 'https://sahaj.in/problems/forbidden', title: 'Forbidden', status: 403, detail: 'No.' },
            { status: 403, statusText: 'Forbidden' },
          );
        await settle();
        expect(view.querySelector('[data-testid=scope-chip]')).toBeNull();
        expect(view.textContent).not.toContain('Asha Patil');
      });
    });
  });
});
