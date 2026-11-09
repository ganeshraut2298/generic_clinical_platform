import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { checksOf } from '../pharmacist/pharmacist.fixtures';
import { PharmacistStore } from '../pharmacist/pharmacist.store';
import { SafetyComponent } from './safety.component';
import { boardOf, eventOf, pageOf, problem } from './safety.fixtures';

const IPD = '/api/v1/ipd';

/** SAH-31: the owner's Safety tab also shows the tile and the panel of the pharmacist check. */
describe('SafetyComponent with the pharmacist check', () => {
  let fixture: ComponentFixture<SafetyComponent>;
  let http: HttpTestingController;
  let store: PharmacistStore;

  async function open(show: boolean): Promise<void> {
    await TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        PharmacistStore,
        { provide: MatSnackBar, useValue: { open: vi.fn(), dismiss: vi.fn() } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(PharmacistStore);
    fixture = TestBed.createComponent(SafetyComponent);
    fixture.componentRef.setInput('showPharmacist', show);
    fixture.detectChanges();
    http.expectOne(`${IPD}/safety`).flush(boardOf());
    http.expectOne((r) => r.url === `${IPD}/clinical-events`).flush(pageOf([eventOf()]));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const checksReq = () => http.expectOne((r) => r.url === `${IPD}/pharmacist-checks`);

  afterEach(() => http.verify());

  it('shows the tile right after "Escalations open" and the panel between the escalations and the audit trail', async () => {
    await open(true);
    checksReq().flush(checksOf({ waiting: 2 }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const tiles = Array.from(el().querySelectorAll('.kpis > *')).map((n) => n.getAttribute('data-kpi') ?? n.tagName.toLowerCase());
    expect(tiles).toEqual(['escalations', 'app-pharmacist-tile', 'doses']);
    expect(el().querySelector('[data-kpi=pharmacist] .eyebrow')!.textContent).toBe('Pharmacist checks');
    const blocks = Array.from(el().querySelectorAll('.safety > *')).map((n) => n.tagName.toLowerCase());
    expect(blocks).toEqual(['div', 'section', 'section', 'app-pharmacist-panel', 'app-medicine-round', 'section']);
    expect(store.panelShown()).toBe(true);
    expect(store.waiting()).toBe(2);
  });

  it('shows neither the tile nor the panel, and reads nothing, for a doctor or a nurse', async () => {
    await open(false);
    expect(el().querySelector('[data-kpi=pharmacist]')).toBeNull();
    expect(el().querySelector('app-pharmacist-panel')).toBeNull();
    http.expectNone((r) => r.url === `${IPD}/pharmacist-checks`);
  });

  describe('the Safety board is refused (SAH-40, D-1)', () => {
    const FORBIDDEN = () => problem('forbidden', 403, 'You cannot see the Safety board.');
    const REFUSED = { status: 403, statusText: 'Forbidden' };

    it('clears the pharmacist rows held by the page when a reload is refused', async () => {
      await open(true);
      checksReq().flush(checksOf({ waiting: 2 }));
      fixture.detectChanges();
      expect(store.items().length).toBeGreaterThan(0);
      (fixture.componentInstance as unknown as { refresh(): void }).refresh();
      http.expectOne(`${IPD}/safety`).flush(FORBIDDEN(), REFUSED);
      expect(http.expectOne((r) => r.url === `${IPD}/clinical-events`).cancelled).toBe(true);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(store.items()).toEqual([]);
      expect(store.meta()).toBeNull();
      expect(store.waiting()).toBeNull();
      expect(store.nextCursor()).toBeNull();
      expect(el().querySelector('app-pharmacist-panel')).toBeNull();
      expect(el().querySelector('.notice.err')).not.toBeNull();
    });

    it('clears them when the first load is refused', async () => {
      await TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          PharmacistStore,
          { provide: MatSnackBar, useValue: { open: vi.fn(), dismiss: vi.fn() } },
          { provide: MatDialog, useValue: { open: vi.fn() } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      store = TestBed.inject(PharmacistStore);
      store.items.set(checksOf().items);
      store.waiting.set(3);
      fixture = TestBed.createComponent(SafetyComponent);
      fixture.componentRef.setInput('showPharmacist', true);
      fixture.detectChanges();
      http.expectOne(`${IPD}/safety`).flush(FORBIDDEN(), REFUSED);
      expect(http.expectOne((r) => r.url === `${IPD}/clinical-events`).cancelled).toBe(true);
      expect(store.items()).toEqual([]);
      expect(store.waiting()).toBeNull();
    });

    it('keeps the pharmacist rows on a 5xx reload (the old board stays)', async () => {
      await open(true);
      checksReq().flush(checksOf({ waiting: 2 }));
      fixture.detectChanges();
      (fixture.componentInstance as unknown as { refresh(): void }).refresh();
      http.expectOne(`${IPD}/safety`).flush(problem('server-error', 500, 'x'), { status: 500, statusText: 'x' });
      http.expectOne((r) => r.url === `${IPD}/clinical-events`);
      expect(store.items().length).toBeGreaterThan(0);
      expect(store.waiting()).toBe(2);
    });
  });
});
