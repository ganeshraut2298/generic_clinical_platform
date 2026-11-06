import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { addDays, istToday } from '../../../shared/format/ist-date';
import { RevenueRange, RevenueReport } from '../revenue.model';
import { RevenuePage } from './revenue.page';

const URL = '/api/v1/billing/revenue';

function report(over: Partial<RevenueReport> = {}): RevenueReport {
  const today = istToday();
  return {
    range: 'WEEK',
    collectedPaise: 12500000,
    collectedChangePercent: 12,
    billedPaise: 15000000,
    patients: 30,
    averageBillPaise: 50000,
    duePaise: 80000,
    atCounterPaise: 20000,
    discountPaise: 30000,
    refundPaise: 10000,
    cashInDrawerPaise: 450000,
    days: [-2, -1, 0].map((n) => ({ date: addDays(today, n), collectedPaise: 100000 })),
    modes: [
      { mode: 'CASH', amountPaise: 2500000 },
      { mode: 'UPI', amountPaise: 7500000 },
      { mode: 'CARD', amountPaise: 2500000 },
    ],
    doctors: [
      { doctorMembershipId: 'm1', doctorName: 'Dr. Asha Kulkarni', patients: 10, collectedPaise: 8000000 },
      { doctorMembershipId: 'm2', doctorName: 'Dr. Rohan Mehta', patients: 5, collectedPaise: 4500000 },
    ],
    topServices: [
      { name: 'Consultation', billedPaise: 9000000 },
      { name: 'ECG', billedPaise: 3000000 },
    ],
    dues: [
      {
        visitId: 'v1',
        patientName: 'Prakash Salunke',
        mobile: '98220 00001',
        doctorName: 'Dr. Asha Kulkarni',
        date: '2026-10-21',
        balancePaise: 35000,
      },
    ],
    adjustments: [
      { kind: 'DISCOUNT', patientName: 'Ashok Pawar', amountPaise: 12000, detail: '10% · Senior citizen', date: '2026-10-23' },
      { kind: 'REFUND', patientName: 'Meena Kale', amountPaise: 45000, detail: 'Procedure not done · CASH · by Dr. Meera', date: '2026-10-22' },
    ],
    ...over,
  };
}

describe('RevenuePage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<RevenuePage>;

  function open(): HTMLElement {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RevenuePage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<HTMLElement> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function flush(range: RevenueRange, body: RevenueReport): Promise<HTMLElement> {
    http.expectOne((r) => r.url === URL && r.params.get('range') === range).flush(body);
    return settle();
  }

  /** Text of every text node, separated by a space so adjacent elements do not run together. */
  const text = (el: Element): string => {
    const parts: string[] = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      parts.push(walker.currentNode.textContent!.trim());
    }
    return parts.filter(Boolean).join(' ');
  };

  afterEach(() => http.verify());

  it('loads Today by default, shows a skeleton while loading and the lead text', async () => {
    const view = open();
    expect(view.querySelector('[aria-busy="true"]')).toBeTruthy();
    const done = await flush('TODAY', report());
    expect(done.querySelector('[aria-busy="true"]')).toBeNull();
    expect(text(done)).toContain('Everything collected, billed and still due today, in one place.');
    const pressed = Array.from(done.querySelectorAll('.seg button')).filter(
      (b) => b.getAttribute('aria-pressed') === 'true',
    );
    expect(pressed.map((b) => text(b))).toEqual(['Today']);
  });

  it('requests the chosen range and updates the lead', async () => {
    const view = open();
    await flush('TODAY', report());
    (view.querySelectorAll('.seg button')[2] as HTMLButtonElement).click();
    const done = await flush('MONTH', report({ range: 'MONTH' }));
    expect(text(done)).toContain('in the last 30 days');
  });

  it('shows the four tiles with the change arrow', async () => {
    open();
    const done = await flush('TODAY', report());
    const tiles = Array.from(done.querySelectorAll('.kpi')).map((t) => text(t));
    expect(tiles[0]).toBe('Collected ₹1,25,000 ▲ 12% vs previous period');
    expect(tiles[1]).toBe('Billed ₹1,50,000 30 patients · avg ₹500');
    expect(tiles[2]).toBe('Still due ₹800 plus ₹200 at the counter now');
    expect(tiles[3]).toBe('Discounts and refunds ₹400 ₹300 discounts · ₹100 refunds');
  });

  it('shows a down arrow and omits the change when null', async () => {
    open();
    let done = await flush('TODAY', report({ collectedChangePercent: -7 }));
    expect(text(done.querySelector('.kpi')!)).toContain('▼ 7%');
    expect(done.querySelector('.chg.down')).toBeTruthy();

    const view = done;
    (view.querySelectorAll('.seg button')[1] as HTMLButtonElement).click();
    done = await flush('WEEK', report({ collectedChangePercent: null, atCounterPaise: 0 }));
    const tiles = Array.from(done.querySelectorAll('.kpi')).map((t) => text(t));
    expect(tiles[0]).not.toMatch(/[▲▼]/);
    expect(tiles[0]).toContain('Cash, UPI and card');
    expect(tiles[2]).toContain('Unpaid balances on closed bills');
  });

  it('draws one titled bar per day with today highlighted', async () => {
    open();
    const done = await flush('TODAY', report());
    const bars = done.querySelectorAll('svg.chart rect.bar');
    expect(bars.length).toBe(3);
    expect(done.querySelectorAll('svg.chart rect.bar.today').length).toBe(1);
    const labels = Array.from(done.querySelectorAll('svg.chart g[role="img"]')).map((g) => g.getAttribute('aria-label'));
    expect(labels[2]).toBe('Today: ₹1,000 collected');
    expect(done.querySelector('svg.chart title')).toBeTruthy();
  });

  it('shows payment modes with percent, cash in drawer and the Billing link', async () => {
    open();
    const done = await flush('TODAY', report());
    const rows = Array.from(done.querySelectorAll('.stack + .list .li')).map((r) => text(r));
    expect(rows).toEqual(['Cash 20% ₹25,000', 'UPI 60% ₹75,000', 'Card 20% ₹25,000']);
    expect(text(done.querySelector('.drawer')!)).toContain('Cash in drawer today: ₹4,500');
    const link = Array.from(done.querySelectorAll('a')).find((a) => text(a) === 'Close the day')!;
    expect(link.getAttribute('href')).toBe('/app/billing');
  });

  it('shows doctors, services, dues and adjustments', async () => {
    open();
    const done = await flush('TODAY', report());
    const rows = Array.from(done.querySelectorAll('.rtab tbody tr')).map((r) => text(r));
    expect(rows[0]).toBe('Dr. Asha Kulkarni 10 ₹80,000 ₹8,000 64%');
    expect(rows.length).toBe(2);
    expect(done.querySelectorAll('.rbar').length).toBe(2);
    expect(text(done)).toContain('Consultation');
    expect(text(done)).toContain('Prakash Salunke');
    expect(text(done)).toContain('21 Oct 2026 · Dr. Asha Kulkarni · 98220 00001');
    expect(text(done)).toContain('10% · Senior citizen');
    expect(text(done)).toContain('− ₹450');
  });

  it('shows the empty states', async () => {
    open();
    const done = await flush(
      'TODAY',
      report({ days: [], doctors: [], topServices: [], dues: [], adjustments: [] }),
    );
    const t = text(done);
    expect(t).toContain('No collections in this period.');
    expect(t).toContain('No doctor figures in this period.');
    expect(t).toContain('No services billed in this period.');
    expect(t).toContain('No dues. Every closed bill is fully paid.');
    expect(t).toContain('No discounts or refunds in this period.');
  });

  it('keeps Remind and Export disabled with their notes', async () => {
    open();
    const done = await flush('TODAY', report());
    const buttons = Array.from(done.querySelectorAll('button')).filter((b) => /Remind|Export to Excel/.test(b.textContent!));
    expect(buttons.length).toBe(2);
    expect(buttons.every((b) => b.disabled)).toBe(true);
    expect(text(done)).toContain('Needs WhatsApp (not in this phase)');
    expect(text(done)).toContain('Shown for layout in this phase');
  });

  it('shows the owner-only message on 403', async () => {
    open();
    http
      .expectOne((r) => r.url === URL)
      .flush({ type: 'x', title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });
    const done = await settle();
    expect(text(done.querySelector('[role="alert"]')!)).toBe(
      'Revenue is visible to the owner only. The owner can give access in Clinic setup › Team and access.',
    );
    expect(done.querySelector('.seg')).toBeNull();
  });

  it('shows an alert when loading fails', async () => {
    open();
    http
      .expectOne((r) => r.url === URL)
      .flush({ type: 'x', title: 'x', status: 500 }, { status: 500, statusText: 'err' });
    const done = await settle();
    expect(text(done.querySelector('[role="alert"]')!)).toContain('We could not load the revenue report.');
  });
});
