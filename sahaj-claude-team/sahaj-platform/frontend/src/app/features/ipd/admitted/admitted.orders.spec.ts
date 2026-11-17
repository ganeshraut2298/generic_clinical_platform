import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { count, summaryOf } from '../orders/order-notices.fixtures';
import { NoticeCount } from '../orders/order.model';
import {
  ABILITIES_FRONT_DESK,
  ABILITIES_NURSE,
  AS_OF,
  CLINICAL_QUIET,
  ROW,
  rowOf,
  withoutClinical,
} from './admitted.fixtures';
import { cardFlags, foldFlags } from './admitted.model';
import { PatientCardComponent } from './patient-card.component';

const withOrders = (orders: NoticeCount | undefined, over = {}) =>
  rowOf({ clinical: { ...CLINICAL_QUIET, ...(orders ? { ordersToAcknowledge: orders } : {}), ...over } });

describe('the card flag "to acknowledge" (SAH-76-3)', () => {
  it('shows "<count> to acknowledge" in amber for ordinary orders', () => {
    const flags = cardFlags(withOrders(count(3)), ABILITIES_NURSE);
    const flag = flags.find((f) => f.kind === 'orders')!;
    expect(flag.text).toBe('3 to acknowledge');
    expect(flag.tone).toBe('warn');
    expect(flag.icon).toBe('emr');
    expect(flag.title).toBe('3 to acknowledge');
  });

  it('turns red, and says so in the tooltip, when any open order is STAT', () => {
    const flag = cardFlags(withOrders(count(3, 1)), ABILITIES_NURSE).find((f) => f.kind === 'orders')!;
    expect(flag.tone).toBe('crit');
    expect(flag.title).toBe('3 to acknowledge, including STAT');
  });

  it('is hidden at 0 and when the server sends no count', () => {
    expect(cardFlags(withOrders(count(0)), ABILITIES_NURSE).some((f) => f.kind === 'orders')).toBe(false);
    expect(cardFlags(withOrders(undefined), ABILITIES_NURSE).some((f) => f.kind === 'orders')).toBe(false);
  });

  it('is not built for a role without clinical flags (front desk)', () => {
    expect(cardFlags(withoutClinical(ROW), ABILITIES_FRONT_DESK).some((f) => f.kind === 'orders')).toBe(false);
  });

  it('comes right after the escalation flag, before the allergy flag, and is never folded away', () => {
    const row = withOrders(count(2), {
      escalation: {
        id: 'e1',
        status: 'OPEN',
        ewsScore: 7,
        ewsRisk: 'HIGH',
        targetMinutes: 15,
        waitingMinutes: 4,
        pastTarget: false,
      },
      allergy: { status: 'RECORDED', text: 'Penicillin' },
    });
    const flags = cardFlags(row, ABILITIES_NURSE);
    expect(flags.map((f) => f.kind).slice(0, 3)).toEqual(['escalation', 'orders', 'allergy']);
    expect(foldFlags(flags).folded.some((f) => f.kind === 'orders')).toBe(false);
  });
});

describe('PatientCardComponent: the badge follows the counts poll (SAH-76-3)', () => {
  let fixture: ComponentFixture<PatientCardComponent>;
  let http: HttpTestingController;

  function render(row = withOrders(count(1)), preview = false): HTMLElement {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PatientCardComponent);
    fixture.componentRef.setInput('row', row);
    fixture.componentRef.setInput('card', ABILITIES_NURSE);
    fixture.componentRef.setInput('asOf', AS_OF);
    fixture.componentRef.setInput('preview', preview);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }
  const flagText = (el: HTMLElement) => el.querySelector('[data-flag=orders]')?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
  const SUMMARY = '/api/v1/ipd/order-notices/summary';

  afterEach(() => http.verify());

  it('shows the count the list carried until a poll answers, then the polled count', async () => {
    const el = render();
    http.expectOne(SUMMARY).flush(summaryOf({ byAdmission: [{ admissionId: 'a1', count: 4, stat: 1, overdue: 0, statDueBy: null }] }));
    // Before the answer was applied the card showed 1; now it shows 4, red.
    await fixture.whenStable();
    fixture.detectChanges();
    expect(flagText(el)).toBe('4 to acknowledge');
    expect(el.querySelector('[data-flag=orders]')!.classList).toContain('crit');
  });

  it('removes the flag when the poll says nothing is waiting any more', async () => {
    const el = render();
    http.expectOne(SUMMARY).flush(summaryOf());
    await fixture.whenStable();
    fixture.detectChanges();
    expect(flagText(el)).toBeNull();
  });

  it('shows the count the list carried while no poll has answered', () => {
    const el = render();
    expect(flagText(el)).toBe('1 to acknowledge');
    http.expectOne(SUMMARY);
  });

  it('makes no request for a card without the badge field (front desk) or for the setup preview', () => {
    render(withoutClinical(ROW));
    http.expectNone(SUMMARY);
    TestBed.resetTestingModule();
    render(withOrders(count(2)), true);
    http.expectNone(SUMMARY);
  });
});
