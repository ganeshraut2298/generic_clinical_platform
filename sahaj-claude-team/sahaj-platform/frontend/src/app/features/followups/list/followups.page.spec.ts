import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { FollowUp, FollowUpList, FollowUpStatus } from '../followups.model';
import { FollowUpsPage } from './followups.page';

const URL = '/api/v1/opd/follow-ups';

function fu(status: FollowUpStatus, over: Partial<FollowUp> = {}): FollowUp {
  return {
    id: `f-${status}`,
    patientId: 'p1',
    patientName: 'Rekha Shinde',
    mobile: '98220 00001',
    doctorMembershipId: 'm1',
    doctorName: 'Dr. Asha Kulkarni',
    dueDate: '2026-10-24',
    reason: 'BP review',
    status,
    sourceVisitId: 'v1',
    sourceAdmissionId: null,
    ...over,
  };
}

function list(items: FollowUp[]): FollowUpList {
  return { counts: { due: 1, missed: 2, upcoming: 3, returned: 4 }, items };
}

describe('FollowUpsPage', () => {
  let http: HttpTestingController;

  async function open(): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(FollowUpsPage);
    fixture.detectChanges();
    (globalThis as { __fx?: unknown }).__fx = fixture;
    return fixture.nativeElement as HTMLElement;
  }

  async function flush(status: string, body: FollowUpList): Promise<HTMLElement> {
    http.expectOne((r) => r.url === URL && r.params.get('status') === status).flush(body);
    const fixture = (
      globalThis as unknown as {
        __fx: { detectChanges(): void; whenStable(): Promise<void>; nativeElement: HTMLElement };
      }
    ).__fx;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  afterEach(() => http.verify());

  it('loads Due today by default with counts on the tabs and the lead text', async () => {
    const view = await open();
    await flush('DUE', list([fu('DUE')]));
    expect(view.textContent).toContain(
      'Follow-ups are created when the doctor sets the next visit.',
    );
    const tabs = Array.from(view.querySelectorAll('[role="tab"]')).map((t) =>
      t.textContent!.replace(/\s+/g, ' ').trim(),
    );
    expect(tabs).toEqual(['Due today1', 'Missed2', 'Upcoming3', 'Returned4']);
    expect(view.querySelector('.pill')!.textContent).toContain('Today');
    expect(view.textContent!.replace(/\s+/g, ' ')).toContain('BP review · Dr. Asha · 98220 00001');
    expect(view.textContent).not.toContain('allowance used');
  });

  it('requests the matching status when a tab is chosen', async () => {
    const view = await open();
    await flush('DUE', list([]));
    (view.querySelectorAll('[role="tab"]')[1] as HTMLButtonElement).click();
    const done = await flush('MISSED', list([fu('MISSED', { daysLate: 3 })]));
    expect(done.querySelector('.pill')!.textContent).toContain('3 days late');
    expect(done.querySelector('.sev.crit')).toBeTruthy();
  });

  it('uses roving tabindex, tabpanel labelling, arrow keys and a text status for the dot', async () => {
    const view = await open();
    const first = await flush('DUE', list([fu('DUE')]));
    const tabs = Array.from(first.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
    expect(tabs.map((t) => t.getAttribute('tabindex'))).toEqual(['0', '-1', '-1', '-1']);
    expect(tabs[0].getAttribute('aria-controls')).toBe('fu-panel');
    const panel = first.querySelector('[role="tabpanel"]')!;
    expect(panel.getAttribute('aria-labelledby')).toBe(tabs[0].id);
    expect(first.querySelector('.sev .sr-only')!.textContent).toBe('Due today');
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    const next = await flush('MISSED', list([]));
    const after = Array.from(next.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
    expect(after.map((t) => t.getAttribute('tabindex'))).toEqual(['-1', '0', '-1', '-1']);
    expect(next.querySelector('[role="tabpanel"]')!.getAttribute('aria-labelledby')).toBe(
      after[1].id,
    );
    expect(view).toBeTruthy();
  });

  it('shows the due date for upcoming and Came back for returned, with no Remind on returned', async () => {
    const view = await open();
    await flush('DUE', list([]));
    (view.querySelectorAll('[role="tab"]')[2] as HTMLButtonElement).click();
    const up = await flush('UPCOMING', list([fu('UPCOMING')]));
    expect(up.querySelector('.pill')!.textContent).toContain('24 Oct 2026');
    (up.querySelectorAll('[role="tab"]')[3] as HTMLButtonElement).click();
    const ret = await flush('RETURNED', list([fu('RETURNED')]));
    expect(ret.querySelector('.pill')!.textContent).toContain('Came back');
    expect(Array.from(ret.querySelectorAll('.li button')).length).toBe(0);
  });

  it('shows the WhatsApp actions disabled with the note', async () => {
    const view = await open();
    const done = await flush('DUE', list([fu('DUE')]));
    const buttons = Array.from(done.querySelectorAll('button')).filter((b) =>
      /Remind/.test(b.textContent!),
    );
    expect(buttons.length).toBe(2);
    expect(buttons.every((b) => b.disabled)).toBe(true);
    expect(view.textContent).toContain('Needs WhatsApp (not in this phase)');
  });

  it('shows the empty state', async () => {
    await open();
    const done = await flush('DUE', list([]));
    expect(done.textContent).toContain('No follow-ups in this list.');
  });

  it('shows an error when loading fails', async () => {
    await open();
    http
      .expectOne((r) => r.url === URL)
      .flush({ type: 'x', title: 'x', status: 500 }, { status: 500, statusText: 'err' });
    const fixture = (
      globalThis as unknown as { __fx: { detectChanges(): void; nativeElement: HTMLElement } }
    ).__fx;
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
  });

  it('lists a follow-up added by an IPD discharge like any other (no visit, an admission id)', async () => {
    const view = await open();
    await flush(
      'DUE',
      list([
        fu('DUE', {
          sourceVisitId: null,
          sourceAdmissionId: 'a1',
          reason: 'Review after discharge · Chest pain',
        }),
      ]),
    );
    const text = view.textContent!.replace(/\s+/g, ' ');
    expect(text).toContain('Rekha Shinde');
    expect(text).toContain('Review after discharge · Chest pain · Dr. Asha');
    expect(view.querySelector('.pill')!.textContent).toContain('Today');
  });
});
