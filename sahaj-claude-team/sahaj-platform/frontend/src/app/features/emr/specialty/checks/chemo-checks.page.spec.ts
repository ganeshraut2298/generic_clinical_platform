import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { problemDetailInterceptor } from '../../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../../core/shell/shell.store';
import { chemoCheckerGuard } from '../../emr.guards';
import { ChemoCheckSummary } from '../specialty.model';
import { ChemoChecksPage } from './chemo-checks.page';

const EMR = '/api/v1/opd/emr/chemo-checks';
const FP = 'c'.repeat(64);

const summary = (over: Partial<ChemoCheckSummary> = {}): ChemoCheckSummary => ({
  orderId: 'o1',
  patientName: 'Test Patient',
  uhid: 'U-000042',
  age: 48,
  sex: 'F',
  prescriberName: 'Dr Test',
  protocolLabel: 'AC',
  levelPct: 90,
  reductionReason: 'Neuropathy',
  drugs: [{ name: 'Doxorubicin', calculatedMg: 99.6, roundedMg: 100, finalMg: 110, moreThan5Pct: true }],
  bsa: { used: 1.66, capped: false, source: 'BSA from today’s weight' },
  labsDrawnAt: '2026-11-12T03:10:00Z',
  decision: 'GIVE_REDUCED',
  checks: [
    { key: 'FIT_TO_TREAT', passed: true, detail: 'Counts, organ tests OK · no fever' },
    { key: 'ALLERGIES_REVIEWED', passed: false, detail: 'Confirm with patient' },
  ],
  secondCheck: { state: 'WAITING' },
  fingerprint: FP,
  canConfirm: true,
  ...over,
});

describe('Second checks page (SAH-66-3, way a)', () => {
  let http: HttpTestingController;
  let el: HTMLElement;
  let fixture: ReturnType<typeof TestBed.createComponent<ChemoChecksPage>>;

  function boot(): void {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ChemoChecksPage);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const list = (items: object[] = [{ orderId: 'o1', patientName: 'Test Patient', uhid: 'U-000042', age: 48, sex: 'F', prescriberName: 'Dr Test', protocolLabel: 'AC', namedAt: '2026-11-12T05:00:00Z' }]) => ({ items, total: items.length, page: 0, pageSize: 20 });
  const press = (selector: string): void => {
    (el.querySelector(selector) as HTMLElement).click();
    fixture.detectChanges();
  };

  afterEach(() => http.verify());

  it('lists the orders that name the signed-in checker, with whose order it is', () => {
    boot();
    http.expectOne(EMR).flush(list());
    fixture.detectChanges();
    expect(el.textContent).toContain('Test Patient');
    expect(el.textContent).toContain('U-000042 · 48 y · F');
    expect(el.textContent).toContain('Prescribed by Dr Test · AC · named 12 Nov 2026, 10:30 AM');
  });

  it('says so when nothing is waiting', () => {
    boot();
    http.expectOne(EMR).flush(list([]));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="checks-empty"]')?.textContent).toBe('Nothing is waiting for your second check.');
  });

  it('opens a read-only summary with the doses, flags a dose more than 5 % from calculated and confirms with the fingerprint it read', () => {
    boot();
    http.expectOne(EMR).flush(list());
    fixture.detectChanges();
    press('button[data-act="open-check"]');
    http.expectOne(`${EMR}/o1`).flush(summary());
    fixture.detectChanges();
    expect(el.textContent).toContain('AC at 90% · Neuropathy');
    expect(el.textContent).toContain('Decision: Give, reduce dose');
    expect(el.textContent).toContain('More than 5% from calculated');
    expect(el.textContent).toContain('Fit to treat today');
    expect(el.textContent).toContain('Doses cannot be edited here.');
    expect(el.querySelector('input')).toBeNull();
    press('button[data-act="confirm-check"]');
    const req = http.expectOne(`${EMR}/o1/confirm`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ fingerprint: FP });
    req.flush(summary({ secondCheck: { state: 'CONFIRMED', via: 'SIGN_IN', confirmedAt: '2026-11-12T05:35:00Z' }, canConfirm: false }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="checks-confirmed"]')?.textContent).toContain('Second check confirmed, 12 Nov 2026, 11:05 AM.');
    expect(el.querySelector('button[data-act="confirm-check"]')).toBeNull();
  });

  it('cannot confirm while a safety check is red', () => {
    boot();
    http.expectOne(EMR).flush(list());
    fixture.detectChanges();
    press('button[data-act="open-check"]');
    http.expectOne(`${EMR}/o1`).flush(summary({ canConfirm: false }));
    fixture.detectChanges();
    expect((el.querySelector('button[data-act="confirm-check"]') as HTMLButtonElement).disabled).toBe(true);
    expect(el.textContent).toContain('A safety check is still red');
  });

  it('reads the order again when it changed after the checker read it', () => {
    boot();
    http.expectOne(EMR).flush(list());
    fixture.detectChanges();
    press('button[data-act="open-check"]');
    http.expectOne(`${EMR}/o1`).flush(summary());
    fixture.detectChanges();
    press('button[data-act="confirm-check"]');
    http.expectOne(`${EMR}/o1/confirm`).flush({ type: 'https://sahaj.in/problems/chemo-order-changed', title: 'Changed', status: 409, detail: 'The order changed.' }, { status: 409, statusText: 'Conflict' });
    http.expectOne(`${EMR}/o1`).flush(summary({ fingerprint: 'd'.repeat(64) }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="checks-error"]')?.textContent).toBe('The order changed after you read it. Read it again before you confirm.');
  });

  it('shows the server’s words when the caller is not the named checker', () => {
    boot();
    http.expectOne(EMR).flush(list());
    fixture.detectChanges();
    press('button[data-act="open-check"]');
    http.expectOne(`${EMR}/o1`).flush({ type: 'https://sahaj.in/problems/not-named-checker', title: 'Not you', status: 403, detail: 'This order names someone else.' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="checks-error"]')?.textContent).toBe('This order names someone else.');
  });
});

describe('chemoCheckerGuard', () => {
  function run(role: string | undefined): boolean | string {
    TestBed.configureTestingModule({ providers: [{ provide: ShellStore, useValue: { me: () => (role ? { role } : null) } }] });
    const result = TestBed.runInInjectionContext(() => chemoCheckerGuard({} as never, {} as never));
    return result === true ? true : TestBed.inject(Router).serializeUrl(result as never);
  }

  it('lets nurses, pharmacists, doctors, visiting doctors and the owner in', () => {
    for (const role of ['NURSE', 'PHARMACIST', 'DOCTOR', 'VISITING_DOCTOR', 'OWNER']) {
      expect(run(role)).toBe(true);
      TestBed.resetTestingModule();
    }
  });

  it('sends front desk, billing and counsellor to the not-allowed page', () => {
    for (const role of ['FRONT_DESK', 'BILLING', 'COUNSELLOR', undefined]) {
      expect(run(role)).toBe('/app/not-allowed');
      TestBed.resetTestingModule();
    }
  });
});
