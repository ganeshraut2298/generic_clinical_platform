import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { DEFAULT_WORKFLOW_STEPS } from '../../workflow/workflow.model';
import { TodaySummary } from './today.model';
import { TodayPage } from './today.page';

const OPD = '/api/v1/opd';
const BILLING = '/api/v1/billing';
const PLATFORM = '/api/v1/platform';

const SUMMARY: TodaySummary = {
  patientsTotal: 12,
  newCount: 7,
  followUpCount: 5,
  waitingCount: 3,
  inOpdCount: 6,
  longestWaitMinutes: 22,
  followUpsDue: 4,
  followUpsReturned: 2,
  followUpsMissed: 3,
  doctors: [
    {
      doctorMembershipId: 'd1',
      doctorName: 'Dr. Meera Rao',
      nowWithDoctor: { visitId: 'v2', tokenNo: 2, patientName: 'Seema Kadam', age: 41, sex: 'F', visitType: 'FOLLOW_UP' },
      nextTokens: [5, 6],
    },
    { doctorMembershipId: 'd2', doctorName: 'Dr. Anil Joshi', nowWithDoctor: null, nextTokens: [] },
  ],
  myTasks: [
    { visitId: 'v1', tokenNo: 1, patientName: 'Rohit Patil', stage: 'WAITING', doctorName: 'Dr. Rao', waitMinutes: 9 },
  ],
};

describe('TodayPage', () => {
  let fixture: ComponentFixture<TodayPage>;
  let http: HttpTestingController;

  async function open(
    role: Role,
    options: { billing?: 'fail' | { collectedPaise: number; billsPending: number }; summary?: TodaySummary; setupComplete?: boolean; name?: string } = {},
  ) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        {
          provide: ShellStore,
          useValue: {
            me: () => ({
              userId: 'u1',
              name: options.name ?? 'Asha Patil',
              role,
              menu: ['today', 'queue', 'billing'],
              clinic: { name: 'Clinic', setupComplete: options.setupComplete ?? true, setupPercent: 60 },
              preferences: {},
            }),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TodayPage);
    fixture.detectChanges();
    if (options.setupComplete === false) {
      http.expectOne(`${PLATFORM}/setup/overview`).flush({
        percent: 60,
        checks: [
          { key: 'logo', label: 'Add a logo', done: false },
          { key: 'profile', label: 'Clinic created', done: true },
        ],
      });
    }
    flushLoad(options.summary ?? SUMMARY, options.billing ?? { collectedPaise: 12500000, billsPending: 2 });
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function flushLoad(summary: TodaySummary, billing: 'fail' | { collectedPaise: number; billsPending: number }) {
    http.expectOne(`${OPD}/today-summary`).flush(summary);
    const b = http.expectOne(`${BILLING}/today-summary`);
    if (billing === 'fail') {
      b.flush({ title: 'x', status: 500, type: 'about:blank' }, { status: 500, statusText: 'Server Error' });
    } else {
      b.flush(billing);
    }
    http
      .expectOne(`${OPD}/workflow/current`)
      .flush({ versionNo: 2, steps: DEFAULT_WORKFLOW_STEPS, publishedAt: '2026-11-01T05:00:00Z' });
  }

  afterEach(() => http.verify());

  const text = (el: HTMLElement, sel: string) => {
    const node = el.querySelector(sel);
    if (!node) {
      return undefined;
    }
    const parts = node.classList.contains('kpi') ? Array.from(node.children).map((c) => c.textContent ?? '') : [node.textContent ?? ''];
    return parts.join(' ').replace(/\s+/g, ' ').trim();
  };
  const buttons = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('.actions a, .actions button')).map((b) => b.textContent!.replace(/\s+/g, ' ').trim());

  it('greets with the first name and states the fee line', async () => {
    const el = await open('OWNER');
    expect(text(el, 'h1')).toMatch(/^Good (morning|afternoon|evening), Asha$/);
    expect(text(el, '.lead')).toBe('Fee is collected after the consultation.');
  });

  it('greets without a name while the saved name is the placeholder Owner', async () => {
    const el = await open('OWNER', { name: 'Owner' });
    expect(text(el, 'h1')).toMatch(/^Good (morning|afternoon|evening)$/);
  });

  it('shows the four tiles with the new/follow-up split and rupee format', async () => {
    const el = await open('OWNER');
    expect(text(el, '[data-kpi=patients]')).toBe('Patients today 12 7 new · 5 follow-up');
    expect(text(el, '[data-kpi=waiting]')).toBe('Waiting now 3 6 in OPD · longest 22 min');
    expect(text(el, '[data-kpi=collected]')).toBe('Collected ₹1,25,000 2 bills pending');
    expect(text(el, '[data-kpi=followups]')).toBe('Follow-ups due 6 2 returned · 4 still to come');
  });

  it('shows a dash only in Collected when billing fails', async () => {
    const el = await open('OWNER', { billing: 'fail' });
    expect(text(el, '[data-kpi=collected]')).toContain('—');
    expect(text(el, '[data-kpi=patients]')).toContain('12');
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('shows a doctor in consult and a free room', async () => {
    const el = await open('OWNER');
    const cards = Array.from(el.querySelectorAll('.dcard')).map((c) => c.textContent!.replace(/\s+/g, ' '));
    expect(cards[0]).toContain('MR');
    expect(cards[0]).toContain('T-2');
    expect(cards[0]).toContain('Seema Kadam');
    expect(cards[0]).toContain('41 F · Follow-up');
    expect(cards[0]).toContain('Next in line · 2');
    expect(cards[0]).toContain('T-5');
    expect(cards[1]).toContain('Room free');
    expect(cards[1]).toContain('No one waiting');
    expect(el.textContent).not.toContain('Average wait');
  });

  it('shows Book appointment and New token to the front desk but neither to a doctor', async () => {
    let el = await open('FRONT_DESK');
    expect(buttons(el)).toEqual(['Book appointment', 'New token']);
    TestBed.resetTestingModule();
    el = await open('DOCTOR');
    expect(buttons(el)).toEqual([]);
  });

  it('shows only New token to a nurse', async () => {
    const el = await open('NURSE');
    expect(buttons(el)).toEqual(['New token']);
  });

  it('shows the payment and missed follow-up alerts to the owner only when above zero', async () => {
    let el = await open('OWNER');
    const alerts = text(el, '[aria-label=Alerts]')!;
    expect(alerts).toContain('2 visits waiting for payment');
    expect(alerts).toContain('Open billing');
    expect(alerts).toContain('3 follow-ups missed');
    expect(alerts).toContain('Review');
    TestBed.resetTestingModule();
    el = await open('OWNER', {
      billing: { collectedPaise: 0, billsPending: 0 },
      summary: { ...SUMMARY, followUpsMissed: 0 },
    });
    expect(text(el, '[aria-label=Alerts]')).toContain('No alerts for your role.');
  });

  it('shows no alerts to a nurse', async () => {
    const el = await open('NURSE');
    expect(text(el, '[aria-label=Alerts]')).toContain('No alerts for your role.');
  });

  it('shows the empty tasks text with the role label', async () => {
    const el = await open('NURSE', { summary: { ...SUMMARY, myTasks: [] } });
    expect(text(el, '[aria-label="My tasks"]')).toContain('Nothing waiting on nurse right now.');
  });

  it('moves a task forward and refreshes the summary', async () => {
    const el = await open('FRONT_DESK');
    const row = el.querySelector('[aria-label="My tasks"] .li')!;
    expect(row.textContent).toContain('T-1');
    expect(row.textContent).toContain('Rohit Patil');
    expect(row.textContent!.replace(/\s+/g, ' ')).toContain('Waiting · Dr. Rao · 9 min');
    (row.querySelector('button') as HTMLButtonElement).click();
    const req = http.expectOne(`${OPD}/visits/v1/stage`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ stage: 'VITALS' });
    req.flush({});
    flushLoad({ ...SUMMARY, myTasks: [] }, { collectedPaise: 0, billsPending: 0 });
    fixture.detectChanges();
    expect(text(el, '[aria-label="My tasks"]')).toContain('Nothing waiting on front desk right now.');
  });

  it('shows the setup pill and checklist to an owner with incomplete setup', async () => {
    const el = await open('OWNER', { setupComplete: false });
    expect(text(el, '.pill.p-warn')).toBe('Setup 60% done');
    expect(text(el, '[aria-label="Getting started"]')).toContain('Add a logo');
  });

  it('shows an alert when the summary cannot load', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u', name: 'A', role: 'OWNER', menu: [], clinic: { name: 'C' }, preferences: {} }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TodayPage);
    fixture.detectChanges();
    http.expectOne(`${BILLING}/today-summary`).flush({ collectedPaise: 0, billsPending: 0 });
    http.expectOne(`${OPD}/workflow/current`).flush({ versionNo: 1, steps: DEFAULT_WORKFLOW_STEPS, publishedAt: '2026-11-01T05:00:00Z' });
    http.expectOne(`${OPD}/today-summary`).flush({ title: 'x', status: 500, type: 'about:blank' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]')).not.toBeNull();
  });
});
