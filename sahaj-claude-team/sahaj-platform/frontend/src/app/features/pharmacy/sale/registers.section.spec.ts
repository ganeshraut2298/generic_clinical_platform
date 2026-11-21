import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { PharmacyContext } from '../pharmacy.model';
import { activityPage, h1Page, licences, roleReadout, scheduleXRegister } from './compliance.fixtures';
import { RegistersSection } from './registers.section';
import { saleContext } from './sale.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

describe('RegistersSection', () => {
  let fixture: ComponentFixture<RegistersSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(RegistersSection);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('draws the H1 register with its chip, the Schedule X register with the physical column, and the licences', async () => {
    const el = await open(saleContext({ readRegisters: true }, ['INVENTORY', 'PHARMACY', 'REGISTERS', 'COMPLIANCE']));
    http.expectOne((r) => r.url === `${S}/registers/h1`).flush(h1Page());
    http.expectOne((r) => r.url === `${S}/registers/schedule-x`).flush(scheduleXRegister());
    http.expectOne(`${BASE}/compliance/licences`).flush(licences());
    http.expectOne(`${BASE}/compliance/role-readout`).flush(roleReadout());
    http.expectOne((r) => r.url === `${BASE}/activity`).flush(activityPage());
    await settle();
    expect(text(el, 'h1-chip')).toBe('23 entries today · 0 missing prescriber');
    expect(text(el, 'h1-row-h1-1')).toContain('Dr. Test Prescriber · MMC-TEST-1');
    expect(text(el, 'h1-row-h1-1')).toContain('e-Rx');
    expect(text(el, 'x-physical-x-1')).toBe('34 ✓');
    expect(text(el, 'x-physical-x-2')).toBe('101 · review');
    expect(text(el, 'x-physical-x-3')).toBe('Not counted');
    expect(text(el, 'x-chip')).toBe('1 to review');
    expect(text(el, 'licence-expired')).toBe('Schedule X licence (20F / 21F) has expired.');
    expect(text(el, 'licence-RETAIL_DRUG_LICENCE-0')).toContain('41 days');
    expect(text(el, 'licence-PHARMACIST-3')).toContain('TEST-REG-1');
  });

  it('sends the date range and the prescriber filter and reloads from the first page', async () => {
    const el = await open(saleContext({ readRegisters: true }, ['INVENTORY', 'PHARMACY', 'REGISTERS']));
    http.expectOne((r) => r.url === `${S}/registers/h1`).flush(h1Page());
    http.expectOne((r) => r.url === `${S}/registers/schedule-x`).flush(scheduleXRegister());
    await settle();
    const prescriber = q(el, 'h1-prescriber') as HTMLInputElement;
    prescriber.value = 'Mehta';
    prescriber.dispatchEvent(new Event('input'));
    q(el, 'h1-search')?.click();
    const req = http.expectOne((r) => r.url === `${S}/registers/h1`);
    expect(req.request.params.get('prescriber')).toBe('Mehta');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(h1Page({ items: [], total: 0 }));
    await settle();
    expect(text(el, 'h1-empty')).toBe('No Schedule H1 entries in this range.');
    expect(q(el, 'licences')).toBeNull();
  });

  it('shows a retry when the register cannot be read', async () => {
    const el = await open(saleContext({ readRegisters: true }, ['INVENTORY', 'PHARMACY', 'REGISTERS']));
    http
      .expectOne((r) => r.url === `${S}/registers/h1`)
      .flush({ type: 'https://sahaj.in/problems/database-busy', title: 'Busy', status: 503 }, { status: 503, statusText: 'Unavailable' });
    http.expectOne((r) => r.url === `${S}/registers/schedule-x`).flush(scheduleXRegister());
    await settle();
    expect(q(el, 'h1-error')).toBeTruthy();
  });

  it('says a cashier cannot read the registers and calls nothing', async () => {
    const el = await open(saleContext({ readRegisters: false }, ['INVENTORY', 'PHARMACY']));
    await settle();
    expect(q(el, 'registers-no-access')).toBeTruthy();
    expect(q(el, 'licences')).toBeNull();
  });
});
