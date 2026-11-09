import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { ownerView, standardSetup } from '../../ipd/card/card-setup.fixtures';
import { IpdAccess } from '../ipd/ipd-access';
import { ModulesView } from '../modules/modules.model';
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
    expect(tiles.length).toBe(11);
    const fees = tiles.find((t) => t.textContent?.includes('Fees and billing'))!;
    expect(fees.textContent).toContain('1 to do');
    expect(fees.textContent).toContain('Fee after consultation');
    expect(tiles.find((t) => t.textContent?.includes('Clinic profile'))!.textContent).toContain('Ravet, Pune');
  });

  it('tells a non-owner that progress is for the owner, and still shows the tiles', async () => {
    const el = await open('FRONT_DESK');
    expect(el.textContent).toContain('Only the owner sees setup progress');
    expect(el.querySelector('.ring')).toBeNull();
    expect(el.querySelectorAll('.tile').length).toBe(11);
  });

  it('shows the Patient card tile line, with " · draft" for the owner, once IPD is available (SAH-37 AC15)', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role: 'OWNER' }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(IpdAccess).set({ ipd: { state: 'on' } } as ModulesView);
    fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    http.expectOne(`${PLATFORM}/setup/overview`).flush(OVERVIEW);
    http.expectOne((r) => r.url === `${PLATFORM}/audit-events`).flush([]);
    answerTiles();
    fixture.detectChanges();
    const saved = { ...standardSetup(), layout: 'compact' as const };
    http.expectOne('/api/v1/ipd/card-setup').flush(
      ownerView({
        draft: { revision: 1, baseVersion: 1, setup: saved, savedAt: '2026-11-09T05:35:00Z', savedByName: 'Dr. Owner', changedParts: ['layout'] },
      }),
    );
    fixture.detectChanges();
    await fixture.whenStable();
    const tile = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.tile')).find((t) =>
      t.textContent?.includes('Patient card'),
    )!;
    expect(tile.textContent).toContain('Standard · Sahaj default · v1 · draft');
  });

  function openWithIpd(role: Role): void {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(IpdAccess).set({ ipd: { state: 'on' } } as ModulesView);
    fixture = TestBed.createComponent(OverviewPage);
    fixture.detectChanges();
    if (role === 'OWNER') {
      http.expectOne(`${PLATFORM}/setup/overview`).flush(OVERVIEW);
      http.expectOne((r) => r.url === `${PLATFORM}/audit-events`).flush([]);
    }
    answerTiles();
    fixture.detectChanges();
  }

  it.each(['FRONT_DESK', 'NURSE', 'DOCTOR'] as Role[])(
    'reads the card setup for the tile when the role is %s and IPD is available (code review 37 #6)',
    async (role) => {
      openWithIpd(role);
      http.expectOne('/api/v1/ipd/card-setup').flush(ownerView());
      await fixture.whenStable();
    },
  );

  it.each(['VISITING_DOCTOR', 'BILLING', 'PHARMACIST', 'COUNSELLOR'] as Role[])(
    'does not call GET /card-setup for the role %s, which the server refuses (code review 37 #6)',
    async (role) => {
      openWithIpd(role);
      await fixture.whenStable();
      http.expectNone('/api/v1/ipd/card-setup');
    },
  );

  it('stops waiting for the card setup when the page is left (code review 37 #6)', async () => {
    openWithIpd('OWNER');
    const req = http.expectOne('/api/v1/ipd/card-setup');
    fixture.destroy();
    expect(req.cancelled).toBe(true);
  });

  it('asks nothing about the Patient card while IPD is not known to be available', async () => {
    await open('OWNER');
    http.expectNone('/api/v1/ipd/card-setup');
  });
});
