import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { OverviewPage } from './overview.page';

const PLATFORM = '/api/v1/platform';
const OPD = '/api/v1/opd';

const OVERVIEW = {
  percent: 40,
  checks: [
    { key: 'profile', label: 'Clinic name, address and phone', why: 'Printed on every case paper and bill', done: true },
    { key: 'services', label: 'Service price list', why: 'Stops prices being typed by hand', done: false },
    { key: 'abdm', label: 'ABDM facility ID', done: false, optional: true },
  ],
  lastChange: { id: 'a1', actorName: 'Dr. Asha', action: 'Service added', at: '2026-10-24T05:35:00Z' },
};

describe('OverviewPage', () => {
  let fixture: ComponentFixture<OverviewPage>;
  let http: HttpTestingController;

  function answerTiles() {
    http.expectOne(`${PLATFORM}/clinic/profile`).flush({ name: 'Shree Sai', area: 'Ravet, Pune' });
    http.expectOne(`${PLATFORM}/clinic/holidays`).flush([]);
    http.expectOne(`${PLATFORM}/specialties`).flush([]);
    http.expectOne(`${PLATFORM}/team`).flush([]);
    http.expectOne(`${OPD}/fee-settings`).flush({ feeTiming: 'after', followUpDays: 6, followUpFee: 'free', discountLimits: {}, upiOnBill: true });
    http.expectOne(`${OPD}/case-paper-settings`).flush({ size: 'A5', preprinted: false, caps: false, sections: { rx: true } });
  }

  async function open(role: Role) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    if (role === 'OWNER') {
      http.expectOne(`${PLATFORM}/setup/overview`).flush(OVERVIEW);
      http.expectOne((r) => r.url === `${PLATFORM}/audit-events`).flush([
        { id: 'a1', actorName: 'Dr. Asha', action: 'Service added', entityType: 'Service', at: '2026-10-24T05:35:00Z' },
      ]);
    } else {
      http.expectNone(`${PLATFORM}/setup/overview`);
    }
    answerTiles();
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('shows the percentage, what is left, and a Fix link to the right section', async () => {
    const el = await open('OWNER');
    expect(el.querySelector('.ring')?.textContent).toContain('40%');
    expect(el.textContent).toContain('1 thing left to finish');
    const fix = el.querySelector<HTMLAnchorElement>('a.fix[aria-label="Fix: Service price list"]')!;
    expect(fix.getAttribute('href')).toContain('fees');
    expect(fix.getAttribute('href')).toContain('#f-serv');
    expect(el.querySelector('a.fix[aria-label="Add: ABDM facility ID"]')).toBeTruthy();
    expect(el.querySelector('a.fix[aria-label="Fix: Clinic name, address and phone"]')).toBeNull();
  });

  it('shows the last change and the change history without an Undo button', async () => {
    const el = await open('OWNER');
    expect(el.textContent).toMatch(/Last change: Service added +by Dr/);
    expect(el.querySelector('.audit')?.textContent).toContain('24 Oct 2026, 11:05 AM');
    expect(el.textContent).not.toContain('Undo');
  });

  it('shows a tile for each area with a line from its own data and a to-do count', async () => {
    const el = await open('OWNER');
    const tiles = Array.from(el.querySelectorAll('.tile'));
    expect(tiles.length).toBe(10);
    const fees = tiles.find((t) => t.textContent?.includes('Fees and billing'))!;
    expect(fees.textContent).toContain('1 to do');
    expect(fees.textContent).toContain('Fee after consultation');
    expect(tiles.find((t) => t.textContent?.includes('Clinic profile'))!.textContent).toContain('Ravet, Pune');
  });

  it('tells a non-owner that progress is for the owner, and still shows the tiles', async () => {
    const el = await open('FRONT_DESK');
    expect(el.textContent).toContain('Only the owner sees setup progress');
    expect(el.querySelector('.ring')).toBeNull();
    expect(el.querySelectorAll('.tile').length).toBe(10);
  });
});
