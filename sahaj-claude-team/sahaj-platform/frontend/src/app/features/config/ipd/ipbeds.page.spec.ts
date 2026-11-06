import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { IpdState, ModulesView } from '../modules/modules.model';
import { IpdLayout } from './ipd.model';
import { IpBedsPage } from './ipbeds.page';

const MODULES = '/api/v1/platform/modules';
const IPD = '/api/v1/ipd';

const modules = (state: IpdState): ModulesView => ({
  plan: 'PRO',
  ipd: { state, requested: false },
  hra: { on: false },
  ipdTrialAvailable: false,
  menuCounts: { simple: 7, advanced: 14 },
});

function layout(over: Partial<IpdLayout> = {}): IpdLayout {
  const bedA = { id: 'b1', code: '101-A', categoryKey: 'gen' as const, status: 'free' as const };
  const bedB = { id: 'b2', code: '101-B', categoryKey: 'icu' as const, status: 'occ' as const };
  return {
    state: 'on',
    roomLevel: true,
    bedLimit: 25,
    bedCount: 2,
    wards: [
      {
        id: 'w1',
        name: 'General ward',
        station: 'Nursing station 1',
        bedCount: 2,
        rooms: [{ id: 'r1', name: 'Room 101', beds: [bedA, bedB] }],
        beds: [bedA, bedB],
      },
    ],
    categories: [],
    charges: { bedDayCut: 'midnight', nursingPaise: 30000, visitPaise: 50000 },
    ...over,
  };
}

const CONFLICT = { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' } };

describe('IpBedsPage', () => {
  let fixture: ComponentFixture<IpBedsPage>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;

  async function open(role: Role, state: IpdState, data: IpdLayout | null = layout()) {
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ role }) } },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IpBedsPage);
    fixture.detectChanges();
    http.expectOne(MODULES).flush(modules(state));
    fixture.detectChanges();
    await fixture.whenStable();
    if (state !== 'off' && data) {
      http.expectOne(`${IPD}/layout`).flush(data);
      await fixture.whenStable();
      fixture.detectChanges();
    }
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const text = () => el().textContent ?? '';
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel)!;
  const byLabel = (label: string) => q<HTMLButtonElement>(`[aria-label="${label}"]`);
  const buttonWith = (label: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.includes(label))!;

  afterEach(() => http.verify());

  it('shows the pill, wards, rooms, chips and the footnote', async () => {
    await open('OWNER', 'on');
    expect(text()).toContain('2 of 25 beds on your plan');
    expect(q('.pill').classList).toContain('p-teal');
    expect(text()).toContain('General ward');
    expect(text()).toContain('Nursing station 1 · 2 beds');
    expect(text()).toContain('Room 101');
    expect(byLabel('Category for bed 101-A')).toBeTruthy();
    expect(byLabel('Remove bed 101-A')).toBeTruthy();
    expect(text()).toContain('Stored as one location tree');
  });

  it('turns the pill to warning at the bed limit', async () => {
    await open('OWNER', 'on', layout({ bedCount: 25 }));
    expect(q('.pill').classList).toContain('p-warn');
  });

  it('shows the empty state with no wards', async () => {
    await open('OWNER', 'on', layout({ wards: [], bedCount: 0 }));
    expect(text()).toContain('No wards yet. Add your first ward below.');
  });

  it('asks for a ward name first and sends nothing', async () => {
    await open('OWNER', 'on');
    buttonWith('Add ward').click();
    fixture.detectChanges();
    expect(text()).toContain('Type a ward name first');
    expect(snack).toHaveBeenCalledWith('Type a ward name first', 'Close', expect.anything());
  });

  it('adds a ward and shows the toast', async () => {
    await open('OWNER', 'on');
    const input = q<HTMLInputElement>('#new-ward');
    input.value = '  ICU  ';
    buttonWith('Add ward').click();
    const req = http.expectOne(`${IPD}/wards`);
    expect(req.request.body).toEqual({ name: 'ICU' });
    req.flush(layout(), { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expect(snack).toHaveBeenCalledWith('ICU added. Now add its beds.', 'Close', expect.anything());
    expect(input.value).toBe('');
  });

  it('shows the API message when the bed limit is reached', async () => {
    await open('OWNER', 'on');
    byLabel('Add a bed to Room 101 in General ward').click();
    const req = http.expectOne(`${IPD}/beds`);
    expect(req.request.body).toEqual({ wardId: 'w1', roomId: 'r1' });
    req.flush(
      {
        type: 'bed-limit',
        title: 'Bed limit',
        status: 409,
        detail: 'Your plan allows 25 beds. Ask Sahaj to raise the limit.',
      },
      CONFLICT,
    );
    await fixture.whenStable();
    expect(snack).toHaveBeenCalledWith(
      'Your plan allows 25 beds. Ask Sahaj to raise the limit.',
      'Close',
      expect.anything(),
    );
  });

  it('adds a room, changes a category and removes a bed', async () => {
    await open('OWNER', 'on');
    byLabel('Add a room to General ward').click();
    http.expectOne(`${IPD}/wards/w1/rooms`).flush(layout());
    await fixture.whenStable();

    const select = q<HTMLSelectElement>('[aria-label="Category for bed 101-A"]');
    select.value = 'pvt';
    select.dispatchEvent(new Event('change'));
    const put = http.expectOne(`${IPD}/beds/b1`);
    expect(put.request.body).toEqual({ categoryKey: 'pvt' });
    put.flush(layout());
    await fixture.whenStable();

    byLabel('Remove bed 101-A').click();
    const del = http.expectOne(`${IPD}/beds/b1`);
    expect(del.request.method).toBe('DELETE');
    del.flush(layout());
  });

  it('disables removing an occupied bed with a hint', async () => {
    await open('OWNER', 'on');
    const remove = byLabel('Remove bed 101-B');
    expect(remove.disabled).toBe(true);
    expect(remove.getAttribute('title')).toBe('A patient is in this bed');
  });

  it('switches rooms off with the switch and shows beds under the ward', async () => {
    await open('OWNER', 'on');
    const sw = byLabel('Use rooms');
    expect(sw.getAttribute('aria-checked')).toBe('true');
    sw.click();
    const req = http.expectOne(`${IPD}/room-level`);
    expect(req.request.body).toEqual({ on: false });
    req.flush(layout({ roomLevel: false }));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(byLabel('Use rooms').getAttribute('aria-checked')).toBe('false');
    expect(text()).not.toContain('Room 101');
    expect(byLabel('Add a bed to General ward')).toBeTruthy();
    expect(byLabel('Remove bed 101-A')).toBeTruthy();
  });

  it('is read-only for a non-owner', async () => {
    await open('FRONT_DESK', 'on');
    expect(text()).toContain('Only the owner can change the wards, rooms and beds');
    expect(byLabel('Use rooms').disabled).toBe(true);
    expect(q<HTMLSelectElement>('[aria-label="Category for bed 101-A"]').disabled).toBe(true);
    expect(q<HTMLInputElement>('#new-ward').disabled).toBe(true);
  });

  it('is read-only for everyone when IPD is read-only', async () => {
    await open('OWNER', 'readonly', layout({ state: 'readonly' }));
    expect(text()).toContain('IPD is read-only. Nothing can be changed.');
    expect(byLabel('Use rooms').disabled).toBe(true);
    expect(buttonWith('Add ward').disabled).toBe(true);
  });

  it('shows a notice with a link to Plan and modules when IPD is off', async () => {
    await open('OWNER', 'off');
    expect(text()).toContain('not on for your clinic');
    expect(q('a').textContent).toContain('Plan and modules');
    expect(text()).not.toContain('Add ward');
  });

  it('shows an error when the layout cannot be loaded', async () => {
    await open('OWNER', 'on', null);
    http.expectOne(`${IPD}/layout`).flush('x', { status: 500, statusText: 'Error' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(q('[role="alert"]').textContent).toContain('We could not load the IPD setup');
  });
});
