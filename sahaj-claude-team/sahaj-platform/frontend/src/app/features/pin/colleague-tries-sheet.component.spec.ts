import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { ColleagueTriesSheetComponent } from './colleague-tries-sheet.component';
import { ColleagueTriesHistory } from './pin.model';

const URL = '/api/v1/platform/team/m-nurse/pin/colleague-tries';

const HISTORY: ColleagueTriesHistory = {
  membershipId: 'm-nurse',
  asOf: '2026-11-07T06:00:00Z',
  wrongTries7d: 6,
  truncated: false,
  entries: [
    { kind: 'RIGHT_AFTER_WRONG', at: '2026-11-07T05:40:00Z', byMembershipId: 'm-a', byName: 'Sunita Pawar', count: null },
    { kind: 'LOCKED', at: '2026-11-07T05:35:00Z', byMembershipId: null, byName: null, count: null },
    { kind: 'WRONG_TRIES', at: '2026-11-07T05:30:00Z', byMembershipId: 'm-a', byName: 'Sunita Pawar', count: 5 },
    { kind: 'WRONG_TRIES', at: '2026-11-06T05:30:00Z', byMembershipId: 'm-b', byName: 'Former staff member', count: 1 },
  ],
};

describe('ColleagueTriesSheetComponent (SAH-38, D9)', () => {
  let fixture: ComponentFixture<ColleagueTriesSheetComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  function open(): void {
    close = vi.fn();
    TestBed.configureTestingModule({
      imports: [ColleagueTriesSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { id: 'm-nurse', name: 'Meera Patil' } },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ColleagueTriesSheetComponent);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (n: Element | null | undefined) => (n?.textContent ?? '').replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('has the title with the name and loads the history of that member', () => {
    open();
    expect(squash(el().querySelector('h2'))).toBe('Wrong PIN tries · Meera Patil');
    expect(el().querySelector('h2')!.id).toBe('ct-title');
    expect(el().querySelector('[aria-busy=true]')).not.toBeNull();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('GET');
    req.flush(HISTORY);
  });

  it("lists each line with its time in IST, latest first, and the server's names", () => {
    open();
    http.expectOne(URL).flush(HISTORY);
    fixture.detectChanges();
    const rows = Array.from(el().querySelectorAll('[data-testid=ct-entry]')).map((r) =>
      Array.from(r.children).map((c) => squash(c)).join(' | '),
    );
    expect(rows).toEqual([
      'Right PIN entered by Sunita Pawar after wrong tries | 7 Nov 2026, 11:10 AM IST',
      'PIN locked after wrong tries by colleagues | 7 Nov 2026, 11:05 AM IST',
      'Wrong PIN tries by Sunita Pawar: 5 | 7 Nov 2026, 11:00 AM IST',
      'Wrong PIN tries by Former staff member: 1 | 6 Nov 2026, 11:00 AM IST',
    ]);
    expect(el().querySelector('[data-testid=ct-truncated]')).toBeNull();
  });

  it('says so when the newest 100 are shown', () => {
    open();
    http.expectOne(URL).flush({ ...HISTORY, truncated: true });
    fixture.detectChanges();
    expect(squash(el().querySelector('[data-testid=ct-truncated]'))).toBe('Showing the newest 100 entries.');
  });

  it('shows the empty text for no entries', () => {
    open();
    http.expectOne(URL).flush({ ...HISTORY, wrongTries7d: 0, entries: [] });
    fixture.detectChanges();
    expect(squash(el().querySelector('[data-testid=ct-empty]'))).toBe('No wrong tries by colleagues in the last 7 days.');
  });

  it('on a failure shows an error with Try again, never an empty list, and asks again', () => {
    open();
    http
      .expectOne(URL)
      .flush(
        { type: 'https://sahaj.in/problems/forbidden', title: 'Not allowed', status: 403, detail: 'Only the clinic owner can do this.' },
        { status: 403, statusText: 'Forbidden' },
      );
    fixture.detectChanges();
    expect(squash(el().querySelector('[data-testid=ct-error]'))).toContain('Only the clinic owner can do this.');
    expect(el().querySelector('[data-testid=ct-empty]')).toBeNull();
    el().querySelector<HTMLButtonElement>('[data-act=ct-retry]')!.click();
    http.expectOne(URL).flush(HISTORY);
    fixture.detectChanges();
    expect(el().querySelectorAll('[data-testid=ct-entry]').length).toBe(4);
    expect(el().querySelector('[data-testid=ct-error]')).toBeNull();
  });

  it('uses the fixed text when the failure has no problem detail', () => {
    open();
    http.expectOne(URL).error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(squash(el().querySelector('[data-testid=ct-error]'))).toContain('We could not load the wrong tries. Try again.');
  });

  it('closes with Close and with the close button', () => {
    open();
    http.expectOne(URL).flush(HISTORY);
    fixture.detectChanges();
    el().querySelector<HTMLButtonElement>('[data-act=ct-close]')!.click();
    el().querySelector<HTMLButtonElement>('[data-act=ct-x]')!.click();
    expect(close).toHaveBeenCalledTimes(2);
  });
});
