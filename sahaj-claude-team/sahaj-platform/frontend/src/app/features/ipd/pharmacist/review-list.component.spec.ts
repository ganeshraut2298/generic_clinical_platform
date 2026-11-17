import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { DETAILS, PATIENT, checkOf, hardStop, problem, warning } from '../emr/emr.fixtures';
import { PharmacistActionResult, PharmacistReviewPage, PharmacistReviewRow } from './pharmacist.model';
import { verifiedResult } from './pharmacist.fixtures';
import { ReviewListComponent } from './review-list.component';

const REVIEWS = '/api/v1/ipd/pharmacist-reviews';
const AS_OF = '2026-11-06T05:35:00Z';

const reviewRow = (over: Partial<PharmacistReviewRow> = {}): PharmacistReviewRow => ({
  id: 'r1',
  orderId: 'o1',
  admissionId: 'a1',
  orderVersion: 1,
  drugKey: 'pcm',
  drugName: 'Inj. Ondansetron 4 mg',
  details: { ...DETAILS, kind: 'ONCE', frequencyKey: null, frequencyLabel: 'STAT', priority: 'STAT' },
  patient: PATIENT,
  doseId: 'd1',
  givenAt: '2026-11-06T05:35:00Z',
  givenByName: 'Sr. Anjali Pawar',
  orderStatus: 'PENDING',
  check: checkOf(),
  canMarkReviewed: true,
  canQuery: true,
  ...over,
});

const pageOf = (items: PharmacistReviewRow[] = [reviewRow()], over: Partial<PharmacistReviewPage> = {}): PharmacistReviewPage => ({
  asOf: AS_OF,
  open: items.length,
  items,
  nextCursor: null,
  ...over,
});

describe('ReviewListComponent (SAH-76-2, Review after dose)', () => {
  let fixture: ComponentFixture<ReviewListComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let dialogOpen: ReturnType<typeof vi.fn>;
  let dialogClosed: Subject<PharmacistActionResult | undefined>;
  let changed: number;
  let queried: PharmacistActionResult[];

  const readFirst = (url = REVIEWS) =>
    http.expectOne((r) => r.url === url && r.params.get('limit') === '50' && !r.params.has('cursor'));

  async function open(page: PharmacistReviewPage | null = pageOf(), count = 1): Promise<void> {
    snack = vi.fn();
    changed = 0;
    queried = [];
    dialogClosed = new Subject<PharmacistActionResult | undefined>();
    dialogOpen = vi.fn(() => ({ afterClosed: () => dialogClosed.asObservable() }));
    await TestBed.configureTestingModule({
      imports: [ReviewListComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: { open: dialogOpen } },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReviewListComponent);
    fixture.componentRef.setInput('open', count);
    fixture.componentInstance.changed.subscribe(() => (changed += 1));
    fixture.componentInstance.queried.subscribe((a) => queried.push(a));
    fixture.detectChanges();
    if (page) {
      readFirst().flush(page);
      await settle();
    }
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el().querySelectorAll<HTMLElement>(sel));
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  describe('reading', () => {
    it('shows a loading line, then the heading with the open count', async () => {
      await open(null, 2);
      expect(squash(q('[role=status]'))).toBe('Loading the doses to review');
      readFirst().flush(pageOf([reviewRow(), reviewRow({ id: 'r2' })]));
      await settle();
      expect(squash(q('#ph-rv-h'))).toBe('Review after dose (2)');
    });

    it('says "No doses to review." when the list is empty', async () => {
      await open(pageOf([]), 0);
      expect(squash(q('[data-testid=review-empty]'))).toBe('No doses to review.');
    });

    it('shows the plain error with Try again, and reads again on Try again', async () => {
      await open(null);
      readFirst().flush(problem('internal', 500, 'Something went wrong.'), { status: 500, statusText: 'x' });
      await settle();
      expect(squash(q('[data-testid=review-error]'))).toContain('Something went wrong.');
      q<HTMLButtonElement>('[data-act=rv-retry]')!.click();
      readFirst().flush(pageOf());
      await settle();
      expect(q('[data-testid=review-error]')).toBeNull();
      expect(q('[data-testid=rv-title]')).not.toBeNull();
    });

    it('says the plain text when the error has no detail', async () => {
      await open(null);
      readFirst().error(new ProgressEvent('error'));
      await settle();
      expect(squash(q('[data-testid=review-error]'))).toContain('We could not load the doses to review. Try again.');
    });

    it('reads the list again when the count the panel holds changes', async () => {
      await open(pageOf(), 1);
      fixture.componentRef.setInput('open', 2);
      await settle();
      readFirst().flush(pageOf([reviewRow(), reviewRow({ id: 'r2' })]));
      await settle();
      expect(qa('[data-testid=rv-title]').length).toBe(2);
    });
  });

  describe('a row', () => {
    it('shows the medicine and order line, the patient, who gave it and when', async () => {
      await open();
      expect(squash(q('[data-testid=rv-title]'))).toContain('Inj. Ondansetron 4 mg · ');
      expect(squash(q('[data-testid=rv-patient]'))).toBe('Asha Patil · bed 101-A · Penicillin (rash)');
      expect(squash(q('[data-testid=rv-given]'))).toBe('Given before pharmacist check at 11:05 AM by Sr. Anjali Pawar');
    });

    it('says "Allergies not recorded" when the patient has no allergy text', async () => {
      await open(pageOf([reviewRow({ patient: { ...PATIENT, allergyText: null, allergyStatus: 'NOT_RECORDED' } })]));
      expect(squash(q('[data-testid=rv-patient]'))).toBe('Asha Patil · bed 101-A · Allergies not recorded');
    });

    it('lists the checks run now: warnings with their severity word, hard stops in red', async () => {
      await open(
        pageOf([
          reviewRow({
            check: checkOf({
              hardStops: [hardStop('Penicillin allergy.')],
              warnings: [warning('Interacts with heparin.', 'MAJOR'), warning('Taken with food.', 'MODERATE')],
            }),
            canMarkReviewed: false,
          }),
        ]),
      );
      expect(qa('[data-testid=rv-hardstop]').map((n) => squash(n))).toEqual(['Penicillin allergy.']);
      expect(qa('[data-testid=rv-warning]').map((n) => squash(n))).toEqual([
        'Major: Interacts with heparin.',
        'Moderate: Taken with food.',
      ]);
    });

    it('gives both buttons names that include the medicine and the patient', async () => {
      await open();
      expect(q('[data-act=rv-done]')!.getAttribute('aria-label')).toBe(
        'Mark Inj. Ondansetron 4 mg reviewed for Asha Patil',
      );
      expect(q('[data-act=rv-query]')!.getAttribute('aria-label')).toBe(
        'Query doctor about Inj. Ondansetron 4 mg for Asha Patil',
      );
    });

    it('a hard stop replaces Mark reviewed with the hint, and Query doctor stays', async () => {
      await open(pageOf([reviewRow({ check: checkOf({ hardStops: [hardStop('Penicillin allergy.')] }), canMarkReviewed: false })]));
      expect(q('[data-act=rv-done]')).toBeNull();
      expect(squash(q('[data-testid=rv-hint]'))).toBe('Hard stop: the doctor must act. Query the doctor.');
      expect(q('[data-act=rv-query]')).not.toBeNull();
    });

    it('shows only the buttons the server allows (canQuery false, canMarkReviewed false)', async () => {
      await open(pageOf([reviewRow({ canMarkReviewed: false, canQuery: false })]));
      expect(q('[data-act=rv-done]')).toBeNull();
      expect(q('[data-act=rv-query]')).toBeNull();
      expect(q('[data-testid=rv-hint]')).toBeNull();
    });
  });

  describe('Mark reviewed', () => {
    it('sends no body, shows the answer page and the toast, and tells the panel', async () => {
      await open(pageOf([reviewRow(), reviewRow({ id: 'r2', drugName: 'Inj. Pantoprazole 40 mg' })]), 2);
      q<HTMLButtonElement>('[data-act=rv-done]')!.click();
      fixture.detectChanges();
      expect(q<HTMLButtonElement>('[data-act=rv-done]')!.disabled).toBe(true);
      const req = http.expectOne(`${REVIEWS}/r1/mark-reviewed`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      req.flush(pageOf([reviewRow({ id: 'r2', drugName: 'Inj. Pantoprazole 40 mg' })]));
      await settle();
      expect(snack).toHaveBeenCalledWith('Inj. Ondansetron 4 mg marked reviewed.', 'Close', expect.anything());
      expect(changed).toBe(1);
      expect(qa('[data-testid=rv-title]').length).toBe(1);
    });

    it('a second tap while it runs sends nothing more', async () => {
      await open();
      const button = q<HTMLButtonElement>('[data-act=rv-done]')!;
      button.click();
      fixture.detectChanges();
      button.click();
      http.expectOne(`${REVIEWS}/r1/mark-reviewed`).flush(pageOf([]));
    });

    it('a 409 (someone else did it) shows the text, reads the list again and tells the panel', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=rv-done]')!.click();
      http
        .expectOne(`${REVIEWS}/r1/mark-reviewed`)
        .flush(problem('review-closed', 409, 'This dose is already reviewed.'), { status: 409, statusText: 'x' });
      expect(snack).toHaveBeenCalledWith('This dose is already reviewed.', 'Close', expect.anything());
      expect(changed).toBe(1);
      readFirst().flush(pageOf([]));
      await settle();
      expect(q('[data-testid=review-empty]')).not.toBeNull();
    });

    it('a lost connection shows the plain text and keeps the row', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=rv-done]')!.click();
      http.expectOne(`${REVIEWS}/r1/mark-reviewed`).error(new ProgressEvent('error'));
      await settle();
      expect(snack).toHaveBeenCalledWith('We could not mark that dose reviewed. Try again.', 'Close', expect.anything());
      expect(changed).toBe(0);
      expect(q('[data-testid=rv-title]')).not.toBeNull();
      expect(q<HTMLButtonElement>('[data-act=rv-done]')!.disabled).toBe(false);
    });
  });

  describe('Query doctor', () => {
    it('opens the query sheet for the order and version, with the patient and UHID as the subtitle', async () => {
      await open(pageOf([reviewRow({ orderVersion: 3 })]));
      q<HTMLButtonElement>('[data-act=rv-query]')!.click();
      expect(dialogOpen).toHaveBeenCalledTimes(1);
      const data = (dialogOpen.mock.calls[0] as unknown[])[1] as { data: Record<string, unknown> };
      expect(data.data).toMatchObject({
        orderId: 'o1',
        orderVersion: 3,
        drugName: 'Inj. Ondansetron 4 mg',
        subtitle: 'Asha Patil · UH-0001',
      });
    });

    it('after a question: the toast, the queue answer to the panel, and the list is read again', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=rv-query]')!.click();
      const answer = verifiedResult({ outcome: 'QUERIED' });
      dialogClosed.next(answer);
      expect(snack).toHaveBeenCalledWith(
        'Tab. Paracetamol 650 mg queried. The doctor sees your question on the EMR.',
        'Close',
        expect.anything(),
      );
      expect(queried).toEqual([answer]);
      readFirst().flush(pageOf([]));
      await settle();
    });

    it('closing the sheet without a question changes nothing', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=rv-query]')!.click();
      dialogClosed.next(undefined);
      expect(snack).not.toHaveBeenCalled();
      expect(queried).toEqual([]);
    });

    it('a conflict seen by the sheet reads the list again and tells the panel', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=rv-query]')!.click();
      const data = ((dialogOpen.mock.calls[0] as unknown[])[1] as { data: { onConflict: () => void } }).data;
      data.onConflict();
      expect(changed).toBe(1);
      readFirst().flush(pageOf());
    });
  });

  describe('Show more doses', () => {
    it('is offered only with a next cursor, reads the next page and adds it without repeating a row', async () => {
      await open(pageOf([reviewRow()], { nextCursor: 'c1', open: 2 }), 2);
      const more = q<HTMLButtonElement>('[data-act=rv-more]')!;
      expect(squash(more)).toBe('Show more doses');
      more.click();
      const req = http.expectOne((r) => r.url === REVIEWS && r.params.get('cursor') === 'c1');
      req.flush(pageOf([reviewRow(), reviewRow({ id: 'r2' })], { open: 2 }));
      await settle();
      expect(qa('[role=listitem]').map((n) => n.getAttribute('data-review'))).toEqual(['r1', 'r2']);
      expect(q('[data-act=rv-more]')).toBeNull();
    });

    it('a failed page shows the plain text in a toast and keeps what is on screen', async () => {
      await open(pageOf([reviewRow()], { nextCursor: 'c1' }));
      q<HTMLButtonElement>('[data-act=rv-more]')!.click();
      http.expectOne((r) => r.params.get('cursor') === 'c1').error(new ProgressEvent('error'));
      await settle();
      expect(snack).toHaveBeenCalledWith('We could not load the doses to review. Try again.', 'Close', expect.anything());
      expect(qa('[role=listitem]').length).toBe(1);
    });

    it('is not offered when there is no next cursor', async () => {
      await open();
      expect(q('[data-act=rv-more]')).toBeNull();
    });
  });
});
