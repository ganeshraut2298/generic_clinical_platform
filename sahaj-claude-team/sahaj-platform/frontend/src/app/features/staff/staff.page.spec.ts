import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../core/shell/shell.store';
import { addDays, istToday } from '../../shared/format/ist-date';
import { Attendance, LeaveView, Roster, StaffSummary } from './staff.model';
import { StaffPage } from './staff.page';

const HRA = '/api/v1/hra';
const HOL = '/api/v1/platform/clinic/holidays';

const today = istToday();
const days = Array.from({ length: 7 }, (_, i) => addDays(today, i));

const summary: StaffSummary = {
  staff: 3,
  doctors: 1,
  others: 2,
  presentToday: 2,
  lateToday: 1,
  leaveRequests: 2,
};

function leaveView(canApprove = true, multi = true): LeaveView {
  const base = {
    membershipId: 'd1',
    roleLabel: 'Doctor',
    typeName: 'Casual leave',
    type: 'CL' as const,
    days: 3,
    askedAt: '2026-11-03T05:35:00Z',
  };
  return {
    types: [
      { key: 'CL', name: 'Casual leave', days: 8 },
      { key: 'SL', name: 'Sick leave', days: 7 },
      { key: 'EL', name: 'Earned leave', days: 15 },
    ],
    requests: [
      {
        ...base,
        id: 'l1',
        name: 'Dr. Asha Kulkarni',
        from: '2026-11-13',
        to: '2026-11-15',
        reason: 'Family function',
        status: 'pending',
        affectedAppointments: 2,
      },
      {
        ...base,
        id: 'l2',
        membershipId: 's1',
        name: 'Meena Patil',
        roleLabel: 'Front desk',
        from: '2026-11-20',
        to: '2026-11-20',
        days: 1,
        status: 'approved',
        affectedAppointments: 0,
      },
      {
        ...base,
        id: 'l3',
        membershipId: 's1',
        name: 'Meena Patil',
        from: '2026-11-25',
        to: '2026-11-26',
        status: 'declined',
      },
    ],
    balances: [
      { membershipId: 'd1', name: 'Dr. Asha Kulkarni', remaining: { CL: 5, SL: 7, EL: 15 } },
      { membershipId: 's1', name: 'Meena Patil', remaining: { CL: 8, SL: 6, EL: 12 } },
    ],
    applicants: multi
      ? [
          { membershipId: 'd1', name: 'Dr. Asha Kulkarni' },
          { membershipId: 's1', name: 'Meena Patil' },
        ]
      : [{ membershipId: 's1', name: 'Meena Patil' }],
    pendingCount: 1,
    canApprove,
  };
}

function roster(published = false): Roster {
  return {
    days,
    shifts: [
      { key: 'M', name: 'Morning', time: '08:00 – 14:00' },
      { key: 'E', name: 'Evening', time: '14:00 – 20:00' },
      { key: 'N', name: 'Night', time: '20:00 – 08:00' },
      { key: 'X', name: 'Off', time: '' },
    ],
    published,
    rows: [
      {
        membershipId: 'd1',
        name: 'Dr. Asha Kulkarni',
        roleLabel: 'Doctor',
        doctor: true,
        cells: days.map((date, i) => ({
          date,
          kind: i === 2 ? ('LEAVE' as const) : ('OPD' as const),
        })),
      },
      {
        membershipId: 's1',
        name: 'Meena Patil',
        roleLabel: 'Front desk',
        doctor: false,
        cells: days.map((date) => ({ date, kind: 'SHIFT' as const, shift: 'M' as const })),
      },
    ],
  };
}

function attendance(canMark: boolean): Attendance {
  return {
    date: today,
    lateAfterMinutes: 15,
    canMark,
    rows: [
      {
        membershipId: 's1',
        name: 'Meena Patil',
        roleLabel: 'Front desk',
        status: 'in',
        onLeave: false,
        markedTime: '08:52',
      },
      {
        membershipId: 's2',
        name: 'Ravi More',
        roleLabel: 'Nurse',
        onLeave: false,
        status: null,
        markedTime: null,
      },
      {
        membershipId: 's3',
        name: 'Sunil Rao',
        roleLabel: 'Lab',
        onLeave: true,
        status: null,
        markedTime: null,
      },
    ],
  };
}

describe('StaffPage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<StaffPage>;

  function setup(role: string, menu: string[]): HTMLElement {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: signal({ role }), menu: signal(menu) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StaffPage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function loadOk(
    opts: { canMark?: boolean; holidays?: unknown[]; leave?: LeaveView } = {},
  ): Promise<void> {
    http.expectOne(`${HRA}/summary`).flush(summary);
    http.expectOne(`${HRA}/roster`).flush(roster());
    http.expectOne(`${HRA}/attendance`).flush(attendance(opts.canMark ?? true));
    http.expectOne(`${HRA}/leave`).flush(opts.leave ?? leaveView());
    http.expectOne(HOL).flush(opts.holidays ?? []);
    await settle();
  }

  function field(view: HTMLElement, id: string): HTMLInputElement & HTMLSelectElement {
    return view.querySelector('#' + id) as HTMLInputElement & HTMLSelectElement;
  }

  function type(el: HTMLInputElement | HTMLSelectElement, value: string): void {
    el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function tabs(view: HTMLElement): HTMLElement[] {
    return Array.from(view.querySelectorAll('[role=tab]')) as HTMLElement[];
  }

  function buttonWith(view: HTMLElement, text: string): HTMLButtonElement {
    return Array.from(view.querySelectorAll('button')).find((b) =>
      b.textContent!.includes(text),
    ) as HTMLButtonElement;
  }

  afterEach(() => http.verify());

  it('decides the lock page from the API, not the menu', async () => {
    const view = setup('OWNER', ['today']);
    await loadOk();
    expect(view.textContent).not.toContain('Staff, timings and leave is an add-on');
  });

  it('shows the lock page when the API says module-off even with staff in the menu', async () => {
    const view = setup('OWNER', ['today', 'staff']);
    http
      .expectOne(`${HRA}/summary`)
      .flush(
        { type: 'module-off', title: 'Module off', status: 403 },
        { status: 403, statusText: 'Forbidden' },
      );
    await settle();
    expect(view.textContent).toContain('Staff, timings and leave is an add-on');
    expect(view.textContent).toContain('See plan and modules');
  });

  it('shows the lock page on module-off and hides the button for non-owners', async () => {
    const view = setup('NURSE', ['today', 'staff']);
    http
      .expectOne(`${HRA}/summary`)
      .flush(
        { type: 'module-off', title: 'Module off', status: 403 },
        { status: 403, statusText: 'Forbidden' },
      );
    await settle();
    expect(view.textContent).toContain('Staff, timings and leave is an add-on');
    expect(view.textContent).not.toContain('See plan and modules');
  });

  it('shows tiles and the next holiday from upcoming clinic holidays only', async () => {
    const view = setup('OWNER', ['staff']);
    await loadOk({
      holidays: [
        { id: 'h0', date: addDays(today, -3), name: 'Old day', type: 'CLOSED' },
        { id: 'h1', date: addDays(today, 10), name: 'Diwali', type: 'CLOSED' },
      ],
    });
    const text = (k: string) =>
      view.querySelector(`[data-kpi=${k}]`)!.textContent!.replace(/\s+/g, ' ').trim();
    expect(text('staff')).toContain('1 doctors · 2 staff');
    expect(text('present')).toContain('1 late');
    expect(text('holiday')).toContain('Diwali');
    expect(text('leave')).toContain('1');
    expect(text('leave')).toContain('Waiting for the owner');
  });

  it('cycles an owner roster cell with PUT and keeps doctor cells read-only', async () => {
    const view = setup('OWNER', ['staff']);
    await loadOk();
    expect(view.querySelectorAll('tbody button.rc').length).toBe(7);
    expect(view.textContent).toContain('OPD');
    (view.querySelector('tbody button.rc') as HTMLButtonElement).click();
    const req = http.expectOne(`${HRA}/roster/cells`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ membershipId: 's1', date: today, shift: 'E' });
    req.flush(roster());
    await settle();
  });

  it('disables roster cells and Publish for non-owners', async () => {
    const view = setup('FRONT_DESK', ['staff']);
    await loadOk();
    expect((view.querySelector('tbody button.rc') as HTMLButtonElement).disabled).toBe(true);
    expect(buttonWith(view, 'Publish roster').disabled).toBe(true);
  });

  it('publishes the roster and shows the pill', async () => {
    const view = setup('OWNER', ['staff']);
    await loadOk();
    buttonWith(view, 'Publish roster').click();
    http.expectOne(`${HRA}/roster/publish`).flush(roster(true));
    await settle();
    expect(view.textContent).toContain('Published to staff');
  });

  it('supports arrow-key tab navigation with roving tabindex', async () => {
    const view = setup('OWNER', ['staff']);
    await loadOk();
    const all = tabs(view);
    expect(all.map((t) => t.getAttribute('tabindex'))).toEqual(['0', '-1', '-1', '-1']);
    all[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await settle();
    expect(all[1].getAttribute('aria-selected')).toBe('true');
    expect(view.querySelector('[role=tabpanel]')!.getAttribute('aria-labelledby')).toBe(
      'st-tab-leave',
    );
  });

  it('lists attendance and marks a person', async () => {
    const view = setup('OWNER', ['staff']);
    await loadOk();
    tabs(view)[2].click();
    await settle();
    expect(view.textContent).toContain('in at 8:52 AM');
    expect(view.textContent).toContain('On leave');
    expect(view.querySelectorAll('.seg').length).toBe(2);
    expect(view.textContent).toContain('Not marked');
    const late = view.querySelectorAll('.seg')[1].querySelectorAll('button')[1];
    late.click();
    const req = http.expectOne(`${HRA}/attendance/s2`);
    expect(req.request.body).toEqual({ status: 'late' });
    req.flush(attendance(true));
    http.expectOne(`${HRA}/summary`).flush(summary);
    await settle();
  });

  it('does not render attendance buttons for read-only roles', async () => {
    const view = setup('NURSE', ['staff']);
    await loadOk({ canMark: false });
    tabs(view)[2].click();
    await settle();
    expect(view.querySelectorAll('.seg').length).toBe(0);
  });

  it('derives switch state from clinic holidays and adds or removes by date', async () => {
    const view = setup('OWNER', ['staff']);
    await loadOk({
      holidays: [{ id: 'h9', date: '2026-12-25', name: 'Christmas', type: 'CLOSED' }],
    });
    tabs(view)[3].click();
    await settle();
    const switches = Array.from(view.querySelectorAll('[role=switch]')) as HTMLButtonElement[];
    expect(switches.length).toBe(5);
    expect(switches[0].getAttribute('aria-checked')).toBe('true');
    expect(switches[1].getAttribute('aria-checked')).toBe('false');
    expect(switches[0].getAttribute('aria-label')).toBe('Clinic closed on Christmas');

    switches[1].click();
    const add = http.expectOne(HOL);
    expect(add.request.method).toBe('POST');
    expect(add.request.body).toEqual({ date: '2027-01-26', name: 'Republic Day', type: 'CLOSED' });
    add.flush({ id: 'h10', date: '2027-01-26', name: 'Republic Day', type: 'CLOSED' });
    http
      .expectOne(HOL)
      .flush([{ id: 'h9', date: '2026-12-25', name: 'Christmas', type: 'CLOSED' }]);
    http.expectOne(`${HRA}/roster`).flush(roster());
    await settle();

    switches[0].click();
    const del = http.expectOne(`${HOL}/h9`);
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    http.expectOne(HOL).flush([]);
    http.expectOne(`${HRA}/roster`).flush(roster());
    await settle();
  });

  it('disables the public holiday switches for non-owners', async () => {
    const view = setup('FRONT_DESK', ['staff']);
    await loadOk();
    tabs(view)[3].click();
    await settle();
    const switches = Array.from(view.querySelectorAll('[role=switch]')) as HTMLButtonElement[];
    expect(switches.every((s) => s.disabled)).toBe(true);
  });

  describe('Leave (SAH-21)', () => {
    async function openLeave(role: string, view: LeaveView): Promise<HTMLElement> {
      const el = setup(role, ['staff']);
      await loadOk({ leave: view });
      tabs(el)[1].click();
      await settle();
      return el;
    }

    it('shows the pending badge to the owner only', async () => {
      const owner = setup('OWNER', ['staff']);
      await loadOk();
      expect(tabs(owner)[1].textContent).toContain('1');
      fixture.destroy();
      TestBed.resetTestingModule();
      const desk = setup('FRONT_DESK', ['staff']);
      await loadOk({ leave: leaveView(false, false) });
      expect(tabs(desk)[1].querySelector('.c')).toBeNull();
    });

    it('opens the tab from the header button', async () => {
      const view = setup('OWNER', ['staff']);
      await loadOk();
      buttonWith(view, 'Apply for leave').click();
      await settle();
      expect(tabs(view)[1].getAttribute('aria-selected')).toBe('true');
      expect(view.textContent).toContain('You approve leave.');
    });

    it('lists requests with dates, warning, asked time and pills', async () => {
      const view = await openLeave('OWNER', leaveView());
      const text = view.textContent!.replace(/\s+/g, ' ');
      expect(text).toContain('Dr. Asha Kulkarni · Casual leave');
      expect(text).toContain('13 Nov 2026 to 15 Nov 2026');
      expect(text).toContain('Family function');
      expect(text).toContain('asked 3 Nov 2026, 11:05 AM IST');
      expect(text).toContain('2 booked appointments on those days');
      expect(text).toContain('20 Nov 2026');
      expect(text).not.toContain('20 Nov 2026 to');
      expect(text).toContain('Approved');
      expect(text).toContain('Declined');
      expect(text).toContain('CL casual leave (8 a year)');
    });

    it('shows Waiting for owner and no buttons to other roles', async () => {
      const view = await openLeave('FRONT_DESK', leaveView(false, false));
      expect(view.textContent).toContain('The owner approves leave.');
      expect(view.textContent).toContain('Waiting for owner');
      expect(buttonWith(view, 'Approve')).toBeUndefined();
      expect(field(view, 'lv-who')).toBeNull();
      expect(view.querySelector('.lvform')!.textContent).toContain('Meena Patil');
    });

    it('shows the empty text', async () => {
      const empty = { ...leaveView(), requests: [] };
      const view = await openLeave('OWNER', empty);
      expect(view.textContent).toContain('No leave requests.');
    });

    it('approves a request and refreshes the tile', async () => {
      const view = await openLeave('OWNER', leaveView());
      buttonWith(view, 'Approve').click();
      const req = http.expectOne(`${HRA}/leave/l1/approve`);
      expect(req.request.method).toBe('POST');
      req.flush({ ...leaveView(), pendingCount: 0 });
      http.expectOne(`${HRA}/roster`).flush(roster());
      http.expectOne(`${HRA}/attendance`).flush(attendance(true));
      http.expectOne(`${HRA}/summary`).flush({ ...summary, leaveRequests: 0 });
      await settle();
      expect(view.querySelector('[data-kpi=leave]')!.textContent).toContain('0');
    });

    it('declines and shows the 409 message, then reloads', async () => {
      const view = await openLeave('OWNER', leaveView());
      buttonWith(view, 'Decline').click();
      http.expectOne(`${HRA}/leave/l1/decline`).flush(
        {
          type: 'conflict',
          title: 'Conflict',
          status: 409,
          detail: 'This request was already decided.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      http.expectOne(`${HRA}/leave`).flush(leaveView());
      await settle();
      expect(view.textContent).toContain('This request was already decided.');
    });

    it('shows N left from the chosen person and updates when the person changes', async () => {
      const view = await openLeave('OWNER', leaveView());
      const opts = () =>
        Array.from(field(view, 'lv-type').options).map((o) => o.textContent!.trim());
      expect(opts()[0]).toBe('Casual leave · 5 left');
      type(field(view, 'lv-who'), 's1');
      await settle();
      expect(opts()[0]).toBe('Casual leave · 8 left');
      expect(opts()[1]).toBe('Sick leave · 6 left');
    });

    it('sends a request for another person and maps a 409', async () => {
      const view = await openLeave('OWNER', leaveView());
      type(field(view, 'lv-who'), 's1');
      type(field(view, 'lv-type'), 'SL');
      type(field(view, 'lv-from'), addDays(today, 2));
      type(field(view, 'lv-to'), addDays(today, 3));
      type(field(view, 'lv-reason'), '  Fever ');
      await settle();
      (view.querySelector('.lvform') as HTMLFormElement).dispatchEvent(new Event('submit'));
      const req = http.expectOne(`${HRA}/leave`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        membershipId: 's1',
        type: 'SL',
        from: addDays(today, 2),
        to: addDays(today, 3),
        reason: 'Fever',
      });
      req.flush(
        {
          type: 'conflict',
          title: 'Conflict',
          status: 409,
          detail: 'That person already has leave on some of these days.',
        },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(view.textContent).toContain('That person already has leave on some of these days.');
    });

    it('sends for yourself without a membership id and refreshes on success', async () => {
      const view = await openLeave('FRONT_DESK', leaveView(false, false));
      (view.querySelector('.lvform') as HTMLFormElement).dispatchEvent(new Event('submit'));
      const req = http.expectOne(`${HRA}/leave`);
      expect(req.request.body).toEqual({ type: 'CL', from: today, to: today });
      req.flush(leaveView(false, false), { status: 201, statusText: 'Created' });
      http.expectOne(`${HRA}/roster`).flush(roster());
      http.expectOne(`${HRA}/attendance`).flush(attendance(true));
      http.expectOne(`${HRA}/summary`).flush(summary);
      await settle();
    });

    it('keeps To within From and 60 days and blocks an invalid range', async () => {
      const view = await openLeave('OWNER', leaveView());
      type(field(view, 'lv-from'), addDays(today, 5));
      await settle();
      expect(field(view, 'lv-to').value).toBe(addDays(today, 5));
      expect(field(view, 'lv-to').min).toBe(addDays(today, 5));
      expect(field(view, 'lv-to').max).toBe(addDays(today, 65));
      expect(field(view, 'lv-from').min).toBe(today);
      type(field(view, 'lv-to'), addDays(today, 4));
      await settle();
      (view.querySelector('.lvform') as HTMLFormElement).dispatchEvent(new Event('submit'));
      http.expectNone(`${HRA}/leave`);
      await settle();
      expect(view.textContent).toContain('To must not be before From.');
    });

    it('shows Leave in the roster and On leave in attendance without buttons', async () => {
      const view = setup('OWNER', ['staff']);
      await loadOk();
      const cells = Array.from(view.querySelectorAll('tbody span.rc')) as HTMLElement[];
      expect(cells.filter((c) => c.textContent!.trim() === 'Leave').length).toBe(1);
      expect(cells.find((c) => c.classList.contains('lv'))).toBeTruthy();
    });
  });
});
