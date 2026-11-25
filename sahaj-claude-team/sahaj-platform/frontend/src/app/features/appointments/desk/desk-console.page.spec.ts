import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { addDays, istToday } from '../../../shared/format/ist-date';
import { DEFAULT_BOOKING_RULES } from '../appointments.model';
import { DESK_IDLE_MS, DESK_POLL_MS, DeskConsolePage } from './desk-console.page';
import { DeskDaySlots, DeskSnapshot } from './desk.model';
import { makeCard, makeDoctor } from './desk.testing';

const BASE = '/api/v1/opd/desk';
const TODAY = istToday();

function days(freeToday = 7) {
  return Array.from({ length: 7 }, (_, i) => ({
    date: addDays(TODAY, i),
    freeCount: i === 0 ? freeToday : 8,
    working: true,
    onLeave: false,
  }));
}

const DOCTORS = [
  makeDoctor({ doctorMembershipId: 'd1', name: 'Dr. Meera Kulkarni', department: 'General medicine', days: days() }),
  makeDoctor({ doctorMembershipId: 'd2', name: 'Dr. Asha Rao', department: 'Pediatrics', days: days(0), nextFree: null }),
];

function snapshot(overrides: Partial<DeskSnapshot> = {}): DeskSnapshot {
  return {
    generatedAt: new Date().toISOString(),
    today: TODAY,
    bookingRules: { ...DEFAULT_BOOKING_RULES, closeBeforeHours: 2, noShowAfterMinutes: 20 },
    followUpFee: 'half',
    counters: { booked: 3, waiting: 2, inConsult: 1, done: 4, freeToday: 9 },
    doctors: { total: 2, items: DOCTORS },
    board: {
      date: TODAY,
      truncated: false,
      cards: [
        makeCard({ key: 'c1', patientName: 'Rekha Shinde', doctorMembershipId: 'd1', time: '10:30' }),
        makeCard({
          key: 'c2',
          patientName: 'Mahesh Gaikwad',
          doctorMembershipId: 'd2',
          doctorName: 'Dr. Asha Rao',
          column: 'WAITING',
          tokenNo: 8,
          primaryAction: 'START_CONSULTATION',
        }),
      ],
    },
    followUpsToBook: {
      total: 1,
      items: [
        {
          followUpId: 'f1',
          patientId: 'p9',
          patientName: 'Sunita Pawar',
          doctorMembershipId: 'd2',
          doctorName: 'Dr. Asha Rao',
          dueDate: TODAY,
          status: 'DUE',
          daysLate: 0,
          reason: 'Review',
        },
      ],
    },
    selected: null,
    ...overrides,
  };
}

const SLOTS: DeskDaySlots = {
  doctorMembershipId: 'd1',
  date: TODAY,
  working: true,
  onLeave: false,
  slotMinutes: 15,
  closeBeforeHours: 1,
  freeCount: 2,
  groups: [{ label: 'MORNING', slots: [{ time: '10:30', insideCutOff: false }, { time: '10:45', insideCutOff: true }] }],
  nextFreeDate: null,
};

describe('DeskConsolePage', () => {
  let fixture: ComponentFixture<DeskConsolePage>;
  let http: HttpTestingController;
  let visibility: DocumentVisibilityState;

  function setVisibility(state: DocumentVisibilityState) {
    visibility = state;
    document.dispatchEvent(new Event('visibilitychange'));
  }

  async function open(role: Role = 'FRONT_DESK', snap: DeskSnapshot = snapshot()) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role, clinic: { name: 'Shree Sai Clinic' } }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeskConsolePage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/snapshot`).flush(snap);
    // First doctor is selected and its day is read.
    http.expectOne((r) => r.url === `${BASE}/doctors/d1/day-slots`).flush(SLOTS);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const buttons = (view: HTMLElement) => Array.from(view.querySelectorAll('button'));
  const links = (view: HTMLElement) => Array.from(view.querySelectorAll('a'));

  beforeEach(() => {
    visibility = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    http.verify();
  });

  it('shows the title, clinic, IST date and the five counters in order', async () => {
    const view = await open();
    expect(view.querySelector('h1')!.textContent).toBe('Front desk console');
    expect(view.querySelector('[data-testid="header-when"]')!.textContent).toContain('Shree Sai Clinic');
    expect(view.querySelector('[data-testid="header-when"]')!.textContent).toMatch(/\d{1,2} \w{3} \d{4} · \d{1,2}:\d{2} [AP]M/);
    const counters = Array.from(view.querySelectorAll('.counter')).map((c) => `${c.querySelector('.num')!.textContent} ${c.querySelector('.faint')!.textContent}`);
    expect(counters).toEqual(['3 Booked', '2 Waiting', '1 In consult', '4 Done', '9 Free today']);
  });

  it('keeps the counters when searching or changing the board scope', async () => {
    const view = await open();
    const input = view.querySelector('#desk-search') as HTMLInputElement;
    input.value = 'mahesh';
    input.dispatchEvent(new Event('input'));
    buttons(view).find((b) => b.textContent!.includes('Selected doctor'))!.click();
    fixture.detectChanges();
    expect(Array.from(view.querySelectorAll('.counter .num')).map((n) => n.textContent)).toEqual(['3', '2', '1', '4', '9']);
  });

  it('shows N of M, filters by department, and the search narrows doctors and cards together', async () => {
    const view = await open();
    expect(view.querySelector('[data-testid="rail-count"]')!.textContent).toBe('2 of 2');
    const select = view.querySelector('#desk-dept') as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['All departments', 'General medicine', 'Pediatrics']);
    select.value = 'Pediatrics';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(view.querySelector('[data-testid="rail-count"]')!.textContent).toBe('1 of 2');

    const input = view.querySelector('#desk-search') as HTMLInputElement;
    input.value = 'rekha';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    // No doctor matches "rekha": rail not narrowed; board narrowed to the patient.
    expect(view.querySelector('[data-testid="rail-count"]')!.textContent).toBe('1 of 2');
    expect(view.querySelector('.board')!.textContent).toContain('Rekha Shinde');
    expect(view.querySelector('.board')!.textContent).not.toContain('Mahesh Gaikwad');
  });

  it('maps cards to columns and shows the token and one action per card for staff', async () => {
    const view = await open('FRONT_DESK');
    const headings = Array.from(view.querySelectorAll('.board h3')).map((h) => h.textContent!.replace(/\s+/g, ' ').trim());
    expect(headings).toEqual(['Booked 1', 'Waiting 1', 'In consultation 0', 'Done 0']);
    expect(view.querySelector('.board')!.textContent).toContain('T-8');
    expect(view.querySelector('.board')!.textContent).toContain('Nobody here');
    const actions = Array.from(view.querySelectorAll('.board .act')).map((b) => b.textContent!.trim());
    expect(actions).toEqual(['Check in · token', 'Mark no-show', 'Start consultation']);
    expect(Array.from(view.querySelectorAll('.board .act')).every((b) => !(b as HTMLButtonElement).disabled)).toBe(true);
  });

  it('shows the Selected doctor scope as pressed and narrows the cards', async () => {
    const view = await open();
    const toggle = buttons(view).find((b) => b.textContent!.includes('Selected doctor'))!;
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(view.querySelector('.board')!.textContent).toContain('Rekha Shinde');
    expect(view.querySelector('.board')!.textContent).not.toContain('Mahesh Gaikwad');
  });

  it('hides actions, Slot settings, Change rules and Find slot from a nurse', async () => {
    const view = await open('NURSE');
    expect(view.querySelectorAll('.board .act')).toHaveLength(0);
    expect(links(view).some((a) => a.textContent!.includes('Slot settings'))).toBe(false);
    expect(links(view).some((a) => a.textContent!.includes('Change rules'))).toBe(false);
    expect(buttons(view).some((b) => b.textContent!.includes('Find slot'))).toBe(false);
    expect(view.textContent).toContain('Sunita Pawar');
  });

  it('gives Change rules to the owner only and Slot settings to owner and front desk', async () => {
    const front = await open('FRONT_DESK');
    expect(links(front).some((a) => a.textContent!.includes('Slot settings'))).toBe(true);
    expect(links(front).some((a) => a.textContent!.includes('Change rules'))).toBe(false);
    TestBed.resetTestingModule();

    const owner = await open('OWNER');
    expect(links(owner).some((a) => a.textContent!.includes('Change rules'))).toBe(true);
    expect(links(owner).find((a) => a.textContent!.includes('Change rules'))!.getAttribute('href')).toBe('/app/config/slots');
  });

  it('draws the rule chips from the saved values and disables the WhatsApp ones', async () => {
    const view = await open();
    const footer = view.querySelector('.dfoot')!.textContent!;
    expect(footer).toContain('Booking rules');
    expect(footer).toContain('Booking closes 2 h before');
    expect(footer).toContain('No-show after 20 min');
    expect(footer).toContain('WhatsApp reminder 2 h before · Needs WhatsApp (not in this phase)');
    expect(footer).toContain('Booking by staff only · Needs WhatsApp (not in this phase)');
  });

  it('lists follow-ups to book and starts a find-slot for the doctor', async () => {
    const view = await open('OWNER');
    expect(view.textContent).toContain('Next actions');
    expect(view.textContent).toContain('Follow-ups to book · 1');
    expect(view.textContent).toContain('Due today');
    buttons(view).find((b) => b.textContent!.includes('Find slot'))!.click();
    http.expectOne((r) => r.url === `${BASE}/doctors/d2/day-slots`).flush({ ...SLOTS, doctorMembershipId: 'd2', freeCount: 0, groups: [] });
    fixture.detectChanges();
    expect(view.textContent).toContain('Pick a slot');
    buttons(view).find((b) => b.textContent!.trim() === 'Close')!.click();
    fixture.detectChanges();
    expect(view.textContent).toContain('Next actions');
  });

  it('says "All follow-ups are booked." when none are left', async () => {
    const view = await open('OWNER', snapshot({ followUpsToBook: { total: 0, items: [] } }));
    expect(view.textContent).toContain('All follow-ups are booked.');
  });

  it('shows the finder with Morning slots and the cut-off note, and opens Confirm booking for staff', async () => {
    const view = await open('FRONT_DESK');
    expect(view.querySelector('.finder')!.textContent).toContain('Consultation ₹400 · Follow-up ₹200');
    expect(view.querySelector('.finder')!.textContent).toContain('Morning');
    expect(view.querySelector('.finder')!.textContent).toContain('Inside the booking cut-off');
    const slot = buttons(view).find((b) => b.textContent!.includes('10:30 AM'))!;
    slot.click();
    fixture.detectChanges();
    expect(view.textContent).toContain('Confirm booking');
    expect(slot.getAttribute('aria-pressed')).toBe('true');
  });

  it('does not open booking when a nurse clicks a slot', async () => {
    const view = await open('NURSE');
    buttons(view).find((b) => b.textContent!.includes('10:30 AM'))!.click();
    fixture.detectChanges();
    expect(view.textContent).not.toContain('Confirm booking');
  });

  it('reads a new day when a day button is pressed', async () => {
    const view = await open();
    const tomorrow = addDays(TODAY, 1);
    const day = buttons(view).filter((b) => b.classList.contains('daybtn'))[1];
    day.click();
    const req = http.expectOne((r) => r.url === `${BASE}/doctors/d1/day-slots`);
    expect(req.request.params.get('date')).toBe(tomorrow);
    req.flush({ ...SLOTS, date: tomorrow, freeCount: 0, groups: [], nextFreeDate: addDays(TODAY, 2) });
    fixture.detectChanges();
    expect(view.querySelector('.nobox')!.textContent).toContain('Next free day');
  });

  it('shows Try again when the day read fails and keeps the old slots', async () => {
    const view = await open();
    buttons(view).filter((b) => b.classList.contains('daybtn'))[1].click();
    http.expectOne((r) => r.url === `${BASE}/doctors/d1/day-slots`).flush({}, { status: 500, statusText: 'Server error' });
    fixture.detectChanges();
    expect(view.querySelector('.finder .notice')!.textContent).toContain('Try again');
    expect(view.querySelector('.finder')!.textContent).toContain('10:30 AM');
  });

  describe('polling', () => {
    it('refreshes every 20 seconds while visible and sends the selection', async () => {
      vi.useFakeTimers();
      const view = await open();
      vi.advanceTimersByTime(DESK_POLL_MS);
      const req = http.expectOne((r) => r.url === `${BASE}/snapshot`);
      expect(req.request.params.get('doctorMembershipId')).toBe('d1');
      expect(req.request.params.get('date')).toBe(TODAY);
      req.flush(snapshot({ counters: { booked: 4, waiting: 2, inConsult: 1, done: 4, freeToday: 8 }, selected: SLOTS }));
      fixture.detectChanges();
      expect(view.querySelector('.counter .num')!.textContent).toBe('4');
    });

    it('pauses while hidden and refreshes at once when the tab is visible again', async () => {
      vi.useFakeTimers();
      await open();
      visibility = 'hidden';
      vi.advanceTimersByTime(DESK_POLL_MS * 3);
      http.expectNone((r) => r.url === `${BASE}/snapshot`);
      setVisibility('visible');
      http.expectOne((r) => r.url === `${BASE}/snapshot`).flush(snapshot());
    });

    it('never runs two reads at once', async () => {
      vi.useFakeTimers();
      await open();
      vi.advanceTimersByTime(DESK_POLL_MS);
      const first = http.expectOne((r) => r.url === `${BASE}/snapshot`);
      vi.advanceTimersByTime(DESK_POLL_MS);
      http.expectNone((r) => r.url === `${BASE}/snapshot`);
      first.flush(snapshot());
    });

    it('keeps the last data and shows the refresh message when a poll fails, then recovers', async () => {
      vi.useFakeTimers();
      const view = await open();
      vi.advanceTimersByTime(DESK_POLL_MS);
      http.expectOne((r) => r.url === `${BASE}/snapshot`).flush({}, { status: 503, statusText: 'Busy' });
      fixture.detectChanges();
      expect(view.textContent).toContain('Could not refresh. Showing the last update.');
      expect(view.textContent).toContain('Rekha Shinde');
      vi.advanceTimersByTime(DESK_POLL_MS);
      http.expectOne((r) => r.url === `${BASE}/snapshot`).flush(snapshot());
      fixture.detectChanges();
      expect(view.textContent).not.toContain('Could not refresh');
    });

    it('goes back to today when the console stays open past midnight', async () => {
      vi.useFakeTimers();
      await open();
      const page = fixture.componentInstance as unknown as { selectedDate: { set(v: string): void; (): string } };
      page.selectedDate.set(addDays(TODAY, -1));
      vi.advanceTimersByTime(DESK_POLL_MS);
      expect(http.expectOne((r) => r.url === `${BASE}/snapshot`).request.params.get('date')).toBe(TODAY);
    });

    it('goes back to today when the date is beyond the seven-day window', async () => {
      vi.useFakeTimers();
      await open();
      const page = fixture.componentInstance as unknown as { selectedDate: { set(v: string): void } };
      page.selectedDate.set(addDays(TODAY, 7));
      vi.advanceTimersByTime(DESK_POLL_MS);
      expect(http.expectOne((r) => r.url === `${BASE}/snapshot`).request.params.get('date')).toBe(TODAY);
    });

    it('keeps a date inside today to today plus six', async () => {
      vi.useFakeTimers();
      await open();
      const page = fixture.componentInstance as unknown as { selectedDate: { set(v: string): void } };
      page.selectedDate.set(addDays(TODAY, 6));
      vi.advanceTimersByTime(DESK_POLL_MS);
      expect(http.expectOne((r) => r.url === `${BASE}/snapshot`).request.params.get('date')).toBe(addDays(TODAY, 6));
    });

    it('stops polling when the page is destroyed', async () => {
      vi.useFakeTimers();
      await open();
      fixture.destroy();
      vi.advanceTimersByTime(DESK_POLL_MS * 2);
      setVisibility('visible');
      http.expectNone((r) => r.url === `${BASE}/snapshot`);
    });
  });

  describe('idle pause (DEC-098 F2)', () => {
    const MINUTE = 60_000;

    /** Moves the clock in poll-sized steps and answers every poll that fires, as the real server would. */
    function run(ms: number) {
      for (let t = 0; t < ms; t += DESK_POLL_MS) {
        vi.advanceTimersByTime(DESK_POLL_MS);
        http.match((r) => r.url === `${BASE}/snapshot`).forEach((r) => r.flush(snapshot()));
      }
      fixture.detectChanges();
    }

    it('keeps the console while the user is active and pauses 15 minutes after the last input', async () => {
      vi.useFakeTimers();
      const view = await open();
      run(14 * MINUTE);
      document.dispatchEvent(new Event('keydown'));
      run(14 * MINUTE);
      expect(view.querySelector('[data-testid="idle-screen"]')).toBeNull();
      run(2 * MINUTE);
      expect(view.querySelector('[data-testid="idle-screen"]')).not.toBeNull();
    });

    it('hides every patient name behind a Continue button and stops polling', async () => {
      vi.useFakeTimers();
      const view = await open();
      expect(view.textContent).toContain('Rekha Shinde');
      run(DESK_IDLE_MS);
      expect(view.querySelector('[data-testid="idle-screen"]')).not.toBeNull();
      expect(view.querySelector('[data-testid="idle-continue"]')!.textContent!.trim()).toBe('Continue');
      for (const name of ['Rekha Shinde', 'Mahesh Gaikwad', 'Sunita Pawar']) {
        expect(view.textContent).not.toContain(name);
      }
      expect(view.querySelector('app-desk-flow-board')).toBeNull();
      expect(view.querySelector('app-desk-doctor-rail')).toBeNull();
      expect(view.querySelector('app-desk-slot-finder')).toBeNull();
      expect(view.querySelector('app-desk-action-panel')).toBeNull();
      expect(view.querySelector('#desk-search')).toBeNull();
      vi.advanceTimersByTime(DESK_POLL_MS * 3);
      http.expectNone((r) => r.url === `${BASE}/snapshot`);
      setVisibility('visible');
      http.expectNone((r) => r.url === `${BASE}/snapshot`);
    });

    it('stays paused on any input until Continue is pressed, then shows the console and refreshes at once', async () => {
      vi.useFakeTimers();
      const view = await open();
      run(DESK_IDLE_MS);
      document.dispatchEvent(new Event('mousemove'));
      document.dispatchEvent(new Event('touchstart'));
      fixture.detectChanges();
      expect(view.querySelector('[data-testid="idle-screen"]')).not.toBeNull();
      (view.querySelector('[data-testid="idle-continue"]') as HTMLButtonElement).click();
      http.expectOne((r) => r.url === `${BASE}/snapshot`).flush(snapshot());
      fixture.detectChanges();
      expect(view.querySelector('[data-testid="idle-screen"]')).toBeNull();
      expect(view.textContent).toContain('Rekha Shinde');
      // Polling is back, and the next pause is 15 minutes after Continue.
      vi.advanceTimersByTime(DESK_POLL_MS);
      http.expectOne((r) => r.url === `${BASE}/snapshot`).flush(snapshot());
    });
  });
});
