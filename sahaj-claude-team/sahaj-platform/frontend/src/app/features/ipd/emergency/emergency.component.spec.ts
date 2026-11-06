import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { AdmissionDetail, AdmissionOptions } from '../ipd.model';
import { EmergencyBadgeStore } from './emergency-badge.store';
import { EmergencyComponent } from './emergency.component';
import { EmergencyCase, EmergencyCounts, EmergencyHandoff, EmergencyQueue } from './emergency.model';

const IPD = '/api/v1/ipd';
const QUEUE = `${IPD}/emergency`;

const LEVELS3: EmergencyQueue['levels'] = [
  { level: 1, name: 'Red', description: 'Immediate', tone: 'crit', targetMinutes: 5 },
  { level: 2, name: 'Yellow', description: 'Urgent', tone: 'warn', targetMinutes: 30 },
  { level: 3, name: 'Green', description: 'Can wait', tone: 'good', targetMinutes: 120 },
];

const caseOf = (over: Partial<EmergencyCase> = {}): EmergencyCase => ({
  id: 'c1',
  displayName: 'Asha Patil',
  age: 56,
  sex: 'F',
  complaint: 'Chest pain for 1 hour',
  identityPending: false,
  level: 1,
  levelName: 'Red',
  bay: 'ER-1',
  status: 'WAITING',
  arrivedAt: '2026-11-05T05:00:00Z',
  waitingMinutes: 2,
  targetMinutes: 5,
  overTarget: false,
  seenAt: null,
  ...over,
});

const queueOf = (over: Partial<EmergencyQueue> = {}): EmergencyQueue => ({
  state: 'on',
  scale: 3,
  levels: LEVELS3,
  defaultLevel: 2,
  unknownPatientRegistration: true,
  baysTotal: 2,
  baysFree: 1,
  counts: { writable: true, queue: 1, overTarget: 0 },
  cases: [caseOf()],
  canRegister: true,
  canRetriage: true,
  canAct: true,
  canAdmit: true,
  asOf: '2026-11-05T05:02:00Z',
  ...over,
});

const problem = (slug: string, status: number, detail: string) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
});

describe('EmergencyComponent', () => {
  let fixture: ComponentFixture<EmergencyComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let counts: EmergencyCounts[];
  let admitted: AdmissionDetail[];

  async function open(first: EmergencyQueue | 'fail' = queueOf(), dialogResult: unknown = null) {
    snack = vi.fn();
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(dialogResult) })) };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snack } },
        { provide: MatDialog, useValue: dialog },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmergencyComponent);
    counts = [];
    admitted = [];
    fixture.componentInstance.counts.subscribe((c) => counts.push(c));
    fixture.componentInstance.admitted.subscribe((a) => admitted.push(a));
    fixture.detectChanges();
    const req = http.expectOne(QUEUE);
    if (first === 'fail') {
      req.flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
    } else {
      req.flush(first);
    }
    await settle();
  }

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => (el().textContent ?? '').replace(/\s+/g, ' ');
  const cards = () => Array.from(el().querySelectorAll<HTMLElement>('.ercard'));
  const card = (i = 0) => cards()[i];
  const act = (name: string, i = 0) => card(i).querySelector<HTMLButtonElement>(`[data-act=${name}]`);
  /** The visible label of each button (the hidden patient suffix is left out). */
  const labels = (i = 0) =>
    Array.from(card(i).querySelectorAll('.qact button')).map((b) => {
      const copy = b.cloneNode(true) as HTMLElement;
      copy.querySelectorAll('.sr-only').forEach((n) => n.remove());
      return copy.textContent!.replace(/\s+/g, ' ').trim();
    });
  const fullLabels = (i = 0) =>
    Array.from(card(i).querySelectorAll('.qact button')).map((b) => b.textContent!.replace(/\s+/g, ' ').trim());

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('shows the Emergency queue panel with the note, and the empty text', async () => {
    await open(queueOf({ cases: [], counts: { writable: true, queue: 0, overTarget: 0 } }));
    expect(el().querySelector('h2#er-queue-h')!.textContent).toBe('Emergency queue');
    expect(el().querySelector('[data-testid=er-note]')!.textContent).toBe(
      'Most urgent first, then longest waiting · bays free 1 of 2',
    );
    expect(text()).toContain('No one in emergency right now.');
    expect(cards().length).toBe(0);
  });

  it('shows the Quick registration panel on the left only when the server says canRegister', async () => {
    await open(queueOf());
    expect(el().querySelector('app-emergency-register')).not.toBeNull();
    TestBed.resetTestingModule();
    await open(queueOf({ canRegister: false }));
    expect(el().querySelector('app-emergency-register')).toBeNull();
    expect(el().querySelector('.ergrid > section:only-child')).not.toBeNull();
  });

  describe('card', () => {
    it('shows level name and description in the level colour, name, age, sex, bay, complaint', async () => {
      await open();
      const c = card();
      expect(c.classList).toContain('tri-crit');
      expect(c.querySelector('.erl b')!.textContent).toBe('Red');
      expect(c.querySelector('.erl small')!.textContent).toBe('Immediate');
      expect(c.querySelector('.erhead b')!.textContent).toBe('Asha Patil');
      expect(c.querySelector('.erhead .faint')!.textContent).toBe('56 F · ER-1');
      expect(c.querySelector('.complaint')!.textContent).toBe('Chest pain for 1 hour');
    });

    it('uses warning and good colours for the middle and last levels, and "?" for no age', async () => {
      await open(
        queueOf({
          cases: [
            caseOf({ id: 'c2', level: 2, levelName: 'Yellow', bay: null, age: null, sex: 'M' }),
            caseOf({ id: 'c3', level: 3, levelName: 'Green', bay: null }),
          ],
        }),
      );
      expect(card(0).classList).toContain('tri-warn');
      expect(card(0).querySelector('.erhead .faint')!.textContent).toBe('? M');
      expect(card(1).classList).toContain('tri-good');
    });

    it('shows a neutral waiting pill inside the target time', async () => {
      await open(queueOf({ cases: [caseOf({ waitingMinutes: 4 })] }));
      const pill = card().querySelector('.pill')!;
      expect(pill.textContent!.trim()).toBe('Waiting 4 min · target 5 min');
      expect(pill.classList).toContain('p-wait');
      expect(card().classList).not.toContain('breach');
    });

    it('shows the critical pill with the alert icon and highlights the card past the target', async () => {
      await open(
        queueOf({ cases: [caseOf({ waitingMinutes: 38, targetMinutes: 30, overTarget: true })] }),
      );
      const pill = card().querySelector('.pill')!;
      expect(pill.textContent!.trim()).toBe('Waiting 38 min · target 30 min');
      expect(pill.classList).toContain('p-crit');
      expect(pill.querySelector('app-icon')).not.toBeNull();
      expect(card().classList).toContain('breach');
    });

    it('says "target now" when the target is 0', async () => {
      await open(queueOf({ cases: [caseOf({ waitingMinutes: 1, targetMinutes: 0, overTarget: true })] }));
      expect(card().querySelector('.pill')!.textContent!.trim()).toBe('Waiting 1 min · target now');
    });

    it('shows "Seen by doctor" once seen, never the waiting pill, and Identity pending on an unknown patient', async () => {
      await open(
        queueOf({
          cases: [
            caseOf({
              status: 'SEEN',
              seenAt: '2026-11-05T05:03:00Z',
              waitingMinutes: 50,
              displayName: 'Unknown male, about 40',
              identityPending: true,
            }),
          ],
        }),
      );
      const pills = Array.from(card().querySelectorAll('.pill')).map((p) => p.textContent!.trim());
      expect(pills).toEqual(['Seen by doctor', 'Identity pending']);
      expect(card().querySelector('.pill.p-good')).not.toBeNull();
      expect(card().querySelector('.pill.p-warn')).not.toBeNull();
      expect(card().classList).not.toContain('breach');
    });

    it('keeps the order the server sends', async () => {
      await open(
        queueOf({
          cases: [caseOf({ id: 'c1', displayName: 'First' }), caseOf({ id: 'c2', displayName: 'Second', level: 2, levelName: 'Yellow' })],
        }),
      );
      expect(cards().map((c) => c.querySelector('.erhead b')!.textContent)).toEqual(['First', 'Second']);
    });
  });

  describe('actions by flags', () => {
    const ALL = ['Doctor has seen', 'Admit', 'Treat and release', 'Refer out'];

    it('shows every action for a WAITING case when all flags are on, with the level list between', async () => {
      await open();
      expect(labels()).toEqual(['Doctor has seen', 'Admit', 'Treat and release', 'Refer out']);
      const select = card().querySelector<HTMLSelectElement>('select')!;
      expect(select.getAttribute('aria-label')).toBe('Triage level for Asha Patil');
      expect(Array.from(select.options).map((o) => o.textContent)).toEqual(['Red', 'Yellow', 'Green']);
      expect(select.value).toBe('1');
      expect(act('admit')!.querySelector('app-icon')).not.toBeNull();
    });

    it('adds the patient name to each button for screen readers, after the unchanged visible label', async () => {
      await open(queueOf({ cases: [caseOf(), caseOf({ id: 'c2', displayName: 'Ravi Kumar' })] }));
      expect(fullLabels(0)).toEqual([
        'Doctor has seen for Asha Patil',
        'Admit for Asha Patil',
        'Treat and release for Asha Patil',
        'Refer out for Asha Patil',
      ]);
      expect(fullLabels(1)).toEqual([
        'Doctor has seen for Ravi Kumar',
        'Admit for Ravi Kumar',
        'Treat and release for Ravi Kumar',
        'Refer out for Ravi Kumar',
      ]);
      expect(labels(0)).toEqual(['Doctor has seen', 'Admit', 'Treat and release', 'Refer out']);
      const hidden = card(0).querySelectorAll('.qact button .sr-only');
      expect(hidden.length).toBe(4);
    });

    it('never puts the patient name in a title or tooltip, and uses a label for a missing name', async () => {
      await open(queueOf({ cases: [caseOf({ displayName: '  ' })] }));
      expect(el().querySelectorAll('[title]').length).toBe(0);
      expect(fullLabels()[0]).toBe('Doctor has seen for unknown patient');
      expect(act('admit')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Admit for unknown patient');
    });

    it('does not offer Doctor has seen on a case that is already seen', async () => {
      await open(queueOf({ cases: [caseOf({ status: 'SEEN' })] }));
      expect(labels()).toEqual(['Admit', 'Treat and release', 'Refer out']);
    });

    const matrix: [string, Partial<EmergencyQueue>, string[], boolean][] = [
      ['front desk: re-triage and admit only', { canAct: false }, ['Admit'], true],
      ['doctor: act and re-triage, no admit', { canAdmit: false }, ['Doctor has seen', 'Treat and release', 'Refer out'], true],
      ['nurse or owner: everything', {}, ALL, true],
      ['read-only: nothing', { canAct: false, canAdmit: false, canRetriage: false }, [], false],
      ['no re-triage flag: no level list', { canRetriage: false }, ALL, false],
    ];
    it.each(matrix)('%s', async (_name, flags, expected, hasSelect) => {
      await open(queueOf(flags));
      expect(labels()).toEqual(expected);
      expect(card().querySelector('select') !== null).toBe(hasSelect);
      if (expected.length === 0 && !hasSelect) {
        expect(card().querySelector('.qact')).toBeNull();
      }
    });
  });

  describe('doing something', () => {
    it('marks the case seen, replaces the queue from the answer and toasts', async () => {
      await open();
      act('seen')!.click();
      const req = http.expectOne(`${QUEUE}/cases/c1/seen`);
      expect(req.request.method).toBe('POST');
      const seen = caseOf({ status: 'SEEN', seenAt: '2026-11-05T05:03:00Z' });
      req.flush(queueOf({ cases: [seen], actedCase: seen, counts: { writable: true, queue: 1, overTarget: 0 } }));
      await settle();
      expect(snack).toHaveBeenCalledWith('Asha Patil seen by the duty doctor', 'Close', expect.anything());
      expect(card().querySelector('.pill')!.textContent!.trim()).toBe('Seen by doctor');
      expect(act('seen')).toBeNull();
      expect(counts.at(-1)).toEqual({ writable: true, queue: 1, overTarget: 0 });
    });

    it('re-triages from the level list, toasts the new level name, and does nothing for the same level', async () => {
      await open();
      const select = card().querySelector<HTMLSelectElement>('select')!;
      select.value = '1';
      select.dispatchEvent(new Event('change'));
      http.expectNone(`${QUEUE}/cases/c1/level`);

      select.value = '2';
      select.dispatchEvent(new Event('change'));
      const req = http.expectOne(`${QUEUE}/cases/c1/level`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ level: 2 });
      const moved = caseOf({ level: 2, levelName: 'Yellow', bay: 'ER-1' });
      req.flush(queueOf({ cases: [moved], actedCase: moved }));
      await settle();
      expect(snack).toHaveBeenCalledWith('Asha Patil re-triaged to Yellow · logged', 'Close', expect.anything());
      expect(card().classList).toContain('tri-warn');
    });

    it('puts the level list back when the change is refused', async () => {
      await open();
      const select = card().querySelector<HTMLSelectElement>('select')!;
      select.value = '3';
      select.dispatchEvent(new Event('change'));
      http
        .expectOne(`${QUEUE}/cases/c1/level`)
        .flush(problem('validation', 400, 'Choose a triage level'), { status: 400, statusText: 'Bad Request' });
      await settle();
      expect(select.value).toBe('1');
      expect(snack).toHaveBeenCalledWith('Choose a triage level', 'Close', expect.anything());
    });

    it('treats and releases: the case leaves the queue and the toast uses the acted case', async () => {
      await open();
      act('release')!.click();
      const released = caseOf({ status: 'RELEASED' });
      http.expectOne(`${QUEUE}/cases/c1/release`).flush(
        queueOf({ cases: [], actedCase: released, counts: { writable: true, queue: 0, overTarget: 0 }, baysFree: 2 }),
      );
      await settle();
      expect(snack).toHaveBeenCalledWith('Asha Patil treated and released', 'Close', expect.anything());
      expect(cards().length).toBe(0);
      expect(text()).toContain('No one in emergency right now.');
      expect(text()).toContain('bays free 2 of 2');
    });

    it('refers out', async () => {
      await open();
      act('refer')!.click();
      http.expectOne(`${QUEUE}/cases/c1/refer`).flush(
        queueOf({ cases: [], actedCase: caseOf({ status: 'REFERRED' }) }),
      );
      await settle();
      expect(snack).toHaveBeenCalledWith('Asha Patil referred out', 'Close', expect.anything());
    });

    it('switches the buttons off while an action runs, so a double tap sends one request', async () => {
      await open();
      act('release')!.click();
      fixture.detectChanges();
      expect(act('release')!.disabled).toBe(true);
      act('release')!.click();
      http.expectOne(`${QUEUE}/cases/c1/release`).flush(queueOf({ cases: [], actedCase: caseOf({ status: 'RELEASED' }) }));
      await settle();
    });

    it('says the patient is no longer in the queue on 409 case-closed and reloads', async () => {
      await open();
      act('release')!.click();
      http.expectOne(`${QUEUE}/cases/c1/release`).flush(
        problem('case-closed', 409, 'This patient is no longer in the queue.'),
        { status: 409, statusText: 'Conflict' },
      );
      expect(snack).toHaveBeenCalledWith('This patient is no longer in the queue.', 'Close', expect.anything());
      http.expectOne(QUEUE).flush(queueOf({ cases: [], counts: { writable: true, queue: 0, overTarget: 0 } }));
      await settle();
      expect(cards().length).toBe(0);
    });

    it('uses the standard message when a closed-case answer has no text', async () => {
      await open();
      act('seen')!.click();
      http.expectOne(`${QUEUE}/cases/c1/seen`).flush(
        { type: 'https://sahaj.in/problems/case-closed', title: 'x', status: 409 },
        { status: 409, statusText: 'Conflict' },
      );
      expect(snack).toHaveBeenCalledWith('This patient is no longer in the queue.', 'Close', expect.anything());
      http.expectOne(QUEUE).flush(queueOf());
    });

    it('reloads on 409 ipd-readonly so the buttons go away', async () => {
      await open();
      act('seen')!.click();
      http.expectOne(`${QUEUE}/cases/c1/seen`).flush(
        problem('ipd-readonly', 409, 'IPD is read-only. Nothing new can be added.'),
        { status: 409, statusText: 'Conflict' },
      );
      expect(snack).toHaveBeenCalledWith('IPD is read-only. Nothing new can be added.', 'Close', expect.anything());
      http
        .expectOne(QUEUE)
        .flush(queueOf({ state: 'readonly', canAct: false, canAdmit: false, canRetriage: false, canRegister: false }));
      await settle();
      expect(card().querySelector('.qact')).toBeNull();
      expect(el().querySelector('app-emergency-register')).toBeNull();
    });

    it('shows a toast on a server error and keeps the queue as it is', async () => {
      await open();
      act('refer')!.click();
      http.expectOne(`${QUEUE}/cases/c1/refer`).flush(problem('server-error', 500, 'Try later.'), {
        status: 500,
        statusText: 'Server Error',
      });
      await settle();
      expect(snack).toHaveBeenCalledWith('Try later.', 'Close', expect.anything());
      expect(cards().length).toBe(1);
    });
  });

  describe('registration panel', () => {
    it('toasts "name added to the emergency queue · level" with the bay and shows the new card', async () => {
      await open(queueOf({ cases: [] }));
      const input = (id: string) => el().querySelector<HTMLInputElement>(id)!;
      const type = (id: string, value: string) => {
        input(id).value = value;
        input(id).dispatchEvent(new Event('input'));
      };
      type('#er-name', 'Ravi Shah');
      type('#er-complaint', 'Fever');
      el().querySelector('form')!.dispatchEvent(new Event('submit'));
      const req = http.expectOne(`${QUEUE}/cases`);
      const created = caseOf({ id: 'c9', displayName: 'Ravi Shah', level: 1, levelName: 'Red', bay: 'ER-2' });
      req.flush(queueOf({ cases: [created], actedCase: created }), { status: 201, statusText: 'Created' });
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Ravi Shah added to the emergency queue · Red · bay ER-2',
        'Close',
        expect.anything(),
      );
      expect(cards().length).toBe(1);
    });

    it('shows no toast, clears the form and applies the queue when actedCase is a reference', async () => {
      await open(queueOf({ cases: [] }));
      const type = (id: string, value: string) => {
        const input = el().querySelector<HTMLInputElement>(id)!;
        input.value = value;
        input.dispatchEvent(new Event('input'));
      };
      type('#er-name', 'Ravi Shah');
      type('#er-complaint', 'Fever');
      el().querySelector('form')!.dispatchEvent(new Event('submit'));
      const other = caseOf({ id: 'c7', displayName: 'Someone Else' });
      http
        .expectOne(`${QUEUE}/cases`)
        .flush(queueOf({ cases: [other], actedCase: { id: 'c9', level: 1, status: 'RELEASED' } }), {
          status: 200,
          statusText: 'OK',
        });
      await settle();
      expect(snack).not.toHaveBeenCalled();
      expect(cards().length).toBe(1);
      expect(el().querySelector<HTMLInputElement>('#er-name')!.value).toBe('');
      expect(el().querySelector<HTMLInputElement>('#er-complaint')!.value).toBe('');
    });

    it('uses the card in hand for a toast when an action answers with a reference', async () => {
      await open();
      act('seen')!.click();
      http
        .expectOne(`${QUEUE}/cases/c1/seen`)
        .flush(queueOf({ actedCase: { id: 'c1', level: 1, status: 'SEEN' } }));
      await settle();
      expect(snack).toHaveBeenCalledTimes(1);
      expect(snack.mock.calls[0][0]).not.toContain('undefined');
    });

    it('reloads the queue when registration answers 409', async () => {
      await open(queueOf({ cases: [] }));
      const name = el().querySelector<HTMLInputElement>('#er-name')!;
      name.value = 'Ravi Shah';
      name.dispatchEvent(new Event('input'));
      const complaint = el().querySelector<HTMLInputElement>('#er-complaint')!;
      complaint.value = 'Fever';
      complaint.dispatchEvent(new Event('input'));
      el().querySelector('form')!.dispatchEvent(new Event('submit'));
      http.expectOne(`${QUEUE}/cases`).flush(problem('ipd-readonly', 409, 'IPD is read-only.'), {
        status: 409,
        statusText: 'Conflict',
      });
      http.expectOne(QUEUE).flush(queueOf({ cases: [], canRegister: false }));
      await settle();
      expect(el().querySelector('app-emergency-register')).toBeNull();
    });
  });

  describe('loading and refresh', () => {
    it('shows the load error with Try again', async () => {
      await open('fail');
      expect(text()).toContain('Try later.');
      el().querySelector<HTMLButtonElement>('.notice button')!.click();
      http.expectOne(QUEUE).flush(queueOf());
      await settle();
      expect(cards().length).toBe(1);
    });

    it('gives the page the counts of every answer (the menu badge is fed only while it polls)', async () => {
      await open(queueOf({ counts: { writable: true, queue: 4, overTarget: 1 } }));
      expect(counts).toEqual([{ writable: true, queue: 4, overTarget: 1 }]);
      expect(TestBed.inject(EmergencyBadgeStore).counts()).toBeNull();
    });

    it('reloads every 60 seconds while open and keeps the screen when a reload fails', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open();
      vi.advanceTimersByTime(59_000);
      http.expectNone(QUEUE);
      vi.advanceTimersByTime(1_000);
      http.expectOne(QUEUE).flush(queueOf({ cases: [caseOf({ waitingMinutes: 9, overTarget: true })] }));
      await settle();
      expect(card().querySelector('.pill')!.textContent!.trim()).toBe('Waiting 9 min · target 5 min');
      expect(card().classList).toContain('breach');

      vi.advanceTimersByTime(60_000);
      http.expectOne(QUEUE).flush(problem('server-error', 500, 'x'), { status: 500, statusText: 'Server Error' });
      await settle();
      expect(cards().length).toBe(1);
      expect(el().querySelector('.notice.err')).toBeNull();

      vi.advanceTimersByTime(60_000);
      http.expectOne(QUEUE).flush(queueOf({ cases: [] }));
    });

    describe('a failed reload', () => {
      const STALE = 'Could not refresh the queue. Last updated 10:32 AM IST';
      const stale = () => el().querySelector('[data-testid=er-stale]');
      const fail500 = () =>
        http.expectOne(QUEUE).flush(problem('server-error', 500, 'x'), { status: 500, statusText: 'Server Error' });

      it('shows no notice while reloads work', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open();
        expect(stale()).toBeNull();
        vi.advanceTimersByTime(60_000);
        http.expectOne(QUEUE).flush(queueOf());
        await settle();
        expect(stale()).toBeNull();
      });

      it('keeps the old queue and shows a warning with the last good time in IST', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open();
        vi.advanceTimersByTime(60_000);
        fail500();
        await settle();
        expect(cards().length).toBe(1);
        expect(stale()!.textContent!.replace(/\s+/g, ' ').trim()).toBe(STALE);
        expect(stale()!.classList).toContain('warn');
        expect(stale()!.getAttribute('role')).toBe('status');
        expect(el().querySelector('.notice.err')).toBeNull();
        expect(snack).not.toHaveBeenCalled();
      });

      it('keeps the time of the last good answer through repeated failures', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open();
        vi.advanceTimersByTime(60_000);
        fail500();
        vi.advanceTimersByTime(60_000);
        fail500();
        await settle();
        expect(stale()!.textContent).toContain('Last updated 10:32 AM IST');
      });

      it('clears the warning on the next good reload and uses its time later', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open();
        vi.advanceTimersByTime(60_000);
        fail500();
        await settle();
        expect(stale()).not.toBeNull();

        vi.advanceTimersByTime(60_000);
        http.expectOne(QUEUE).flush(queueOf({ asOf: '2026-11-05T05:04:00Z' }));
        await settle();
        expect(stale()).toBeNull();

        vi.advanceTimersByTime(60_000);
        fail500();
        await settle();
        expect(stale()!.textContent).toContain('Last updated 10:34 AM IST');
      });

      it('clears the warning when an action answers with the queue', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open();
        vi.advanceTimersByTime(60_000);
        fail500();
        await settle();
        act('seen')!.click();
        http
          .expectOne(`${QUEUE}/cases/c1/seen`)
          .flush(queueOf({ actedCase: caseOf({ status: 'SEEN' }), asOf: '2026-11-05T05:05:00Z' }));
        await settle();
        expect(stale()).toBeNull();
      });

      it('shows the notice without a time when the answer has no usable time', async () => {
        vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
        await open(queueOf({ asOf: '' }));
        vi.advanceTimersByTime(60_000);
        fail500();
        await settle();
        expect(stale()!.textContent!.trim()).toBe('Could not refresh the queue.');
      });
    });

    it('stops reloading when the page is closed', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      await open();
      fixture.destroy();
      vi.advanceTimersByTime(180_000);
      http.expectNone(QUEUE);
    });
  });

  describe('Admit hand-off', () => {
    const HANDOFF: EmergencyHandoff = {
      caseId: 'c1',
      displayName: 'Asha Patil',
      age: 56,
      sex: 'F',
      mobile: '9876543210',
      complaint: 'Chest pain for 1 hour',
      identityPending: false,
      level: 1,
      levelName: 'Red',
      suggestedBedId: 'b3',
    };
    const options = (handoff: EmergencyHandoff | null): AdmissionOptions =>
      ({ doctors: [], beds: [], rules: {}, emergencyCase: handoff }) as unknown as AdmissionOptions;
    const optionsReq = () => http.expectOne((r) => r.url === `${IPD}/admission-options`);

    it('reads the options with the case id and opens the sheet with the hand-off data', async () => {
      await open();
      act('admit')!.click();
      const req = optionsReq();
      expect(req.request.params.get('emergencyCaseId')).toBe('c1');
      const data = options(HANDOFF);
      req.flush(data);
      await settle();
      expect(dialog.open).toHaveBeenCalledTimes(1);
      const config = dialog.open.mock.calls[0][1];
      expect(config.data.emergencyCaseId).toBe('c1');
      expect(config.data.options).toEqual(data);
      expect(config.panelClass).toBe('sheet-panel');
      expect(typeof config.data.onCaseClosed).toBe('function');
    });

    it('shows the "No free bed" message and does not open the sheet when there is no suggested bed', async () => {
      await open();
      act('admit')!.click();
      optionsReq().flush(options({ ...HANDOFF, suggestedBedId: null }));
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'No free bed. Mark a cleaned bed ready, or transfer a patient.',
        'Close',
        expect.anything(),
      );
      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('leaves the case in the queue when the sheet is cancelled', async () => {
      await open(queueOf(), null);
      act('admit')!.click();
      optionsReq().flush(options(HANDOFF));
      await settle();
      expect(admitted).toEqual([]);
      http.expectNone(QUEUE);
      expect(cards().length).toBe(1);
    });

    it('tells the page about the admission and reloads the queue when the sheet saved', async () => {
      const admission = { id: 'a1', patientName: 'Asha Patil' } as AdmissionDetail;
      await open(queueOf(), admission);
      act('admit')!.click();
      optionsReq().flush(options(HANDOFF));
      http.expectOne(QUEUE).flush(queueOf({ cases: [], counts: { writable: true, queue: 0, overTarget: 0 } }));
      await settle();
      expect(admitted).toEqual([admission]);
      expect(cards().length).toBe(0);
    });

    it('reloads the queue when the sheet reports that the case was closed meanwhile', async () => {
      await open();
      act('admit')!.click();
      optionsReq().flush(options(HANDOFF));
      await settle();
      dialog.open.mock.calls[0][1].data.onCaseClosed();
      http.expectOne(QUEUE).flush(queueOf({ cases: [] }));
      await settle();
      expect(cards().length).toBe(0);
    });

    it('shows the message and reloads when the options say the case is closed (409)', async () => {
      await open();
      act('admit')!.click();
      optionsReq().flush(problem('case-closed', 409, 'This patient is no longer in the queue.'), {
        status: 409,
        statusText: 'Conflict',
      });
      expect(snack).toHaveBeenCalledWith('This patient is no longer in the queue.', 'Close', expect.anything());
      expect(dialog.open).not.toHaveBeenCalled();
      http.expectOne(QUEUE).flush(queueOf({ cases: [] }));
      await settle();
    });
  });
});
