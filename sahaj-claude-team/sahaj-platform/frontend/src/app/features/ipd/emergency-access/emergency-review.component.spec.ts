import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError } from '../../../core/http/problem-detail';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmergencyReviewComponent } from './emergency-review.component';
import { EmergencyAccessReviewPage, EmergencyAccessReviewRow } from './emergency-access.model';

const BASE = '/api/v1/ipd/emergency-access';
const AS_OF = '2026-10-24T07:30:00Z'; // 1:00 PM IST on 24 Oct 2026

const rowOf = (n: number, over: Partial<EmergencyAccessReviewRow> = {}): EmergencyAccessReviewRow => ({
  grantId: `g${n}`,
  admissionId: `a${n}`,
  staffName: `Meera Joshi ${n}`,
  patientName: `Asha Patil ${n}`,
  bedCode: `10${n}-A`,
  reasonKey: 'DETERIORATING',
  reasonLabel: 'Patient deteriorating, rapid response',
  openedAt: '2026-10-24T05:35:00Z', // 11:05 AM IST
  expiresAt: '2026-10-24T06:05:00Z',
  admissionStatus: 'ADMITTED',
  ...over,
});

const pageOf = (rows: EmergencyAccessReviewRow[], waiting = rows.length, nextCursor: string | null = null) =>
  ({ asOf: AS_OF, waiting, items: rows, nextCursor }) satisfies EmergencyAccessReviewPage;

const problem = (slug: string, status: number, detail: string) => ({
  type: `https://sahaj.in/problems/${slug}`,
  title: slug,
  status,
  detail,
});

describe('EmergencyReviewComponent (SAH-34 story 34F)', () => {
  let fixture: ComponentFixture<EmergencyReviewComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let changed: number;
  let refused: ApiError[];

  function mount(page: EmergencyAccessReviewPage): HTMLElement {
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmergencyReviewComponent);
    fixture.componentRef.setInput('page', page);
    changed = 0;
    refused = [];
    fixture.componentInstance.changed.subscribe(() => (changed += 1));
    fixture.componentInstance.refused.subscribe((e) => refused.push(e));
    document.body.appendChild(fixture.nativeElement as HTMLElement);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    (fixture.nativeElement as HTMLElement).remove();
  });

  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const rows = (view: HTMLElement) => Array.from(view.querySelectorAll<HTMLElement>('[data-testid=review-row]'));
  const buttonOf = (view: HTMLElement, grant: string) =>
    view.querySelector<HTMLButtonElement>(`[data-grant=${grant}] [data-testid=mark-reviewed]`)!;

  describe('rows (AC 65, 66)', () => {
    it('has the key icon, the title, the subtitle and one row per grant, in the order of the server', () => {
      const view = mount(pageOf([rowOf(1), rowOf(2)]));
      expect(squash(view.querySelector('h2'))).toBe('Emergency access to review');
      expect(view.querySelector('h2 app-icon')).not.toBeNull();
      expect(squash(view.querySelector('.sfph small'))).toBe(
        'Staff opened patients outside their wards. Check each reason.',
      );
      expect(rows(view).length).toBe(2);
    });

    it('reads "<staff> opened <patient> · bed <code>" and the small line with the reason, time and "outside their wards"', () => {
      const view = mount(pageOf([rowOf(1)]));
      expect(squash(rows(view)[0].querySelector('b'))).toBe('Meera Joshi 1 opened Asha Patil 1 · bed 101-A');
      expect(squash(rows(view)[0].querySelector('small'))).toBe(
        'Patient deteriorating, rapid response · 11:05 AM · outside their wards',
      );
    });

    it('shows "24 Oct, 11:05 AM" for a grant of an earlier day, and two grants on one patient as two rows', () => {
      const view = mount(
        pageOf([
          rowOf(1, { openedAt: '2026-10-23T05:35:00Z' }),
          rowOf(2, { admissionId: 'a1', patientName: 'Asha Patil 1', staffName: 'Rupali More' }),
        ]),
      );
      expect(squash(rows(view)[0].querySelector('small'))).toContain('23 Oct, 11:05 AM');
      expect(rows(view).length).toBe(2);
    });

    it('keeps a grant that has expired, whose patient went home, or whose staff member left (AC 67): the row says nothing more', () => {
      const view = mount(pageOf([rowOf(1, { admissionStatus: 'DISCHARGED', staffName: 'Team member' })]));
      expect(rows(view).length).toBe(1);
      expect(squash(rows(view)[0].querySelector('b'))).toBe('Team member opened Asha Patil 1 · bed 101-A');
      expect(squash(rows(view)[0])).not.toContain('Discharged');
    });

    it('names each Mark reviewed button for a screen reader and keeps it at least 44 px (class only; size is in the styles)', () => {
      const view = mount(pageOf([rowOf(1)]));
      const button = buttonOf(view, 'g1');
      expect(squash(button)).toBe('Mark reviewed');
      expect(button.getAttribute('aria-label')).toBe('Mark reviewed: Meera Joshi 1 opened Asha Patil 1');
      expect(button.querySelector('app-icon')).not.toBeNull();
    });

    it('has no comment, outcome, history, filter or export (AC 77)', () => {
      const view = mount(pageOf([rowOf(1)]));
      expect(view.querySelector('textarea, input, select')).toBeNull();
      expect(squash(view)).not.toMatch(/comment|export|not ok|history/i);
    });
  });

  describe('Mark reviewed (AC 68, 69)', () => {
    it('sends one POST with no body, turns the button off while it runs, then drops the row and says "Marked as reviewed"', async () => {
      const view = mount(pageOf([rowOf(1), rowOf(2)]));
      buttonOf(view, 'g1').click();
      await settle();
      const req = http.expectOne({ method: 'POST', url: `${BASE}/g1/review` });
      expect(req.request.body).toBeNull();
      expect(buttonOf(view, 'g1').getAttribute('aria-disabled')).toBe('true');
      expect(buttonOf(view, 'g2').getAttribute('aria-disabled')).toBe('true');
      expect(rows(view).length).toBe(2);
      req.flush({ grantId: 'g1', reviewedAt: AS_OF, waiting: 1 });
      await settle();
      expect(rows(view).length).toBe(1);
      expect(rows(view)[0].getAttribute('data-grant')).toBe('g2');
      expect(snack).toHaveBeenCalledWith('Marked as reviewed', 'Close', expect.anything());
      expect(changed).toBe(1);
    });

    it('moves the focus to the next row button, or to the audit trail heading when none is left', async () => {
      const heading = document.createElement('h2');
      heading.id = 'sf-aud-h';
      heading.tabIndex = -1;
      document.body.appendChild(heading);
      const view = mount(pageOf([rowOf(1)]));
      buttonOf(view, 'g1').click();
      await settle();
      http.expectOne(`${BASE}/g1/review`).flush({ grantId: 'g1', reviewedAt: AS_OF, waiting: 0 });
      await settle();
      expect(document.activeElement).toBe(heading);
      heading.remove();
    });

    it('does not claim it was reviewed when the call fails: the row stays and the error is shown', async () => {
      const view = mount(pageOf([rowOf(1)]));
      buttonOf(view, 'g1').click();
      await settle();
      http
        .expectOne(`${BASE}/g1/review`)
        .flush(problem('server-error', 500, 'Boom.'), { status: 500, statusText: 'Server Error' });
      await settle();
      expect(rows(view).length).toBe(1);
      expect(squash(view.querySelector('[data-testid=review-notice]'))).toBe(
        'We could not mark that as reviewed. Try again.',
      );
      expect(snack).not.toHaveBeenCalled();
      expect(changed).toBe(0);
      expect(buttonOf(view, 'g1').getAttribute('aria-disabled')).not.toBe('true');
    });

    it('says "Already reviewed." on 409 already-reviewed and asks the board to read again', async () => {
      const view = mount(pageOf([rowOf(1)]));
      buttonOf(view, 'g1').click();
      await settle();
      http
        .expectOne(`${BASE}/g1/review`)
        .flush(problem('already-reviewed', 409, 'Already reviewed.'), { status: 409, statusText: 'Conflict' });
      await settle();
      expect(snack).toHaveBeenCalledWith('Already reviewed.', 'Close', expect.anything());
      expect(changed).toBe(1);
      expect(rows(view).length).toBe(1);
    });

    it('hands a 403 to the board (the data clears, SAH-40)', async () => {
      const view = mount(pageOf([rowOf(1)]));
      buttonOf(view, 'g1').click();
      await settle();
      http
        .expectOne(`${BASE}/g1/review`)
        .flush(problem('forbidden', 403, 'Only the owner can review.'), { status: 403, statusText: 'Forbidden' });
      await settle();
      expect(refused.length).toBe(1);
      expect(refused[0].status).toBe(403);
    });
  });

  describe('Show 25 more (AC 72)', () => {
    const first25 = Array.from({ length: 25 }, (_, i) => rowOf(i + 1));

    it('is not shown without a next cursor', () => {
      const view = mount(pageOf(first25, 25));
      expect(view.querySelector('[data-testid=show-more-reviews]')).toBeNull();
    });

    it('reads the next page with the cursor, adds its rows below, and hides the button at the end', async () => {
      const view = mount(pageOf(first25, 30, 'cur1'));
      const more = view.querySelector<HTMLButtonElement>('[data-testid=show-more-reviews]')!;
      expect(squash(more)).toBe('Show 25 more');
      more.click();
      await settle();
      const req = http.expectOne((r) => r.url === BASE);
      expect(req.request.params.get('cursor')).toBe('cur1');
      expect(req.request.params.get('limit')).toBe('25');
      req.flush(pageOf([rowOf(26), rowOf(27)], 30, null));
      await settle();
      expect(rows(view).length).toBe(27);
      expect(view.querySelector('[data-testid=show-more-reviews]')).toBeNull();
    });

    it('keeps the rows already drawn and says so when the next page fails', async () => {
      const view = mount(pageOf(first25, 30, 'cur1'));
      view.querySelector<HTMLButtonElement>('[data-testid=show-more-reviews]')!.click();
      await settle();
      http
        .expectOne((r) => r.url === BASE)
        .flush(problem('server-error', 500, 'Boom.'), { status: 500, statusText: 'Server Error' });
      await settle();
      expect(rows(view).length).toBe(25);
      expect(squash(view.querySelector('[data-testid=review-notice]'))).toBe('We could not load more. Try again.');
      expect(view.querySelector('[data-testid=show-more-reviews]')).not.toBeNull();
    });

    it('starts again from the server when the board gives a new first page', async () => {
      const view = mount(pageOf(first25, 30, 'cur1'));
      view.querySelector<HTMLButtonElement>('[data-testid=show-more-reviews]')!.click();
      await settle();
      http.expectOne((r) => r.url === BASE).flush(pageOf([rowOf(26)], 30, null));
      await settle();
      expect(rows(view).length).toBe(26);
      fixture.componentRef.setInput('page', pageOf([rowOf(2), rowOf(3)], 2));
      await settle();
      expect(rows(view).map((r) => r.getAttribute('data-grant'))).toEqual(['g2', 'g3']);
    });
  });
});
