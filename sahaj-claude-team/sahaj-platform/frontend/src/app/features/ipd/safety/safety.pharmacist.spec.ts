import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { checksOf } from '../pharmacist/pharmacist.fixtures';
import { PharmacistStore } from '../pharmacist/pharmacist.store';
import { SafetyComponent } from './safety.component';
import { boardOf, eventOf, pageOf } from './safety.fixtures';

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
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
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
});
