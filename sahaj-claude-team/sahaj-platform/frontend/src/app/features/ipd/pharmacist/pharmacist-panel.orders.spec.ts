import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { DETAILS, PATIENT, checkOf, hardStop, problem } from '../emr/emr.fixtures';
import { PharmacistPanelComponent } from './pharmacist-panel.component';
import { checksOf, rowOf, verifiedResult } from './pharmacist.fixtures';
import { PharmacistChecks, PharmacistReviewPage, PharmacistReviewRow } from './pharmacist.model';
import { PharmacistStore } from './pharmacist.store';
import { QuerySheetComponent } from './query-sheet.component';

const LIST = '/api/v1/ipd/pharmacist-checks';
const REVIEWS = '/api/v1/ipd/pharmacist-reviews';

const review = (over: Partial<PharmacistReviewRow> = {}): PharmacistReviewRow => ({
  id: 'r1',
  orderId: 'o7',
  admissionId: 'a1',
  orderVersion: 1,
  drugKey: 'ondan',
  drugName: 'Inj. Ondansetron 4 mg',
  details: { ...DETAILS, kind: 'ONCE', priority: 'STAT', doseAmount: 4, doseUnit: 'MG', routeKey: 'IV', routeLabel: 'IV', frequencyKey: null, frequencyLabel: 'STAT' },
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

const pageOf = (items: PharmacistReviewRow[], over: Partial<PharmacistReviewPage> = {}): PharmacistReviewPage => ({
  asOf: '2026-11-06T05:47:00Z',
  open: items.length,
  items,
  nextCursor: null,
  ...over,
});

describe('PharmacistPanelComponent: orders of SAH-76', () => {
  let fixture: ComponentFixture<PharmacistPanelComponent>;
  let http: HttpTestingController;
  let store: PharmacistStore;
  let snack: ReturnType<typeof vi.fn>;
  let dialog: { open: ReturnType<typeof vi.fn> };

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function open(checks: PharmacistChecks, dialogResult: unknown = undefined): Promise<void> {
    snack = vi.fn();
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(dialogResult) })) };
    await TestBed.configureTestingModule({
      imports: [PharmacistPanelComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        PharmacistStore,
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(PharmacistStore);
    fixture = TestBed.createComponent(PharmacistPanelComponent);
    fixture.detectChanges();
    http.expectOne((r) => r.url === LIST).flush(checks);
    await settle();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);

  afterEach(() => http.verify());

  describe('a waiting row', () => {
    it('shows the dose, route, start and duration, and the STAT tag', async () => {
      const details = { ...DETAILS, priority: 'STAT' as const, kind: 'ONCE' as const, frequencyKey: null, frequencyLabel: 'STAT' };
      await open(checksOf({ items: [rowOf({ details })] }));
      expect(squash(q('[data-testid=ph-details]'))).toBe('1 tablet · Oral · STAT · from 6 Nov, 10:00 AM');
      expect(squash(q('[data-testid=ph-stat]'))).toBe('STAT');
      expect(q('[data-testid=ph-urgent]')).toBeNull();
    });

    it('shows the amber Urgent tag', async () => {
      await open(checksOf({ items: [rowOf({ details: { ...DETAILS, priority: 'URGENT' } })] }));
      expect(squash(q('[data-testid=ph-urgent]'))).toBe('Urgent');
    });

    it('shows no tag and no change line for a routine first version', async () => {
      await open(checksOf());
      expect(q('[data-testid=ph-stat]')).toBeNull();
      expect(q('[data-testid=ph-change]')).toBeNull();
      expect(q('[data-testid=ph-change-reason]')).toBeNull();
    });

    it('shows the change lines and the doctor reason of a changed version', async () => {
      await open(
        checksOf({
          items: [
            rowOf({
              orderVersion: 2,
              changes: [{ field: 'dose', label: 'Dose', before: '1 tablet', after: '2 tablet' }],
              changeReason: 'Not enough effect',
            }),
          ],
        }),
      );
      expect(squash(q('[data-testid=ph-change]'))).toBe('Changed: Dose: 1 tablet → 2 tablet');
      expect(squash(q('[data-testid=ph-change-reason]'))).toBe("Doctor's reason for the change: Not enough effect");
    });

    it('shows the doctor reply after a query', async () => {
      const query = {
        question: 'Right dose?',
        askedByName: 'Rahul Deshmukh',
        askedAt: '2026-11-06T05:00:00Z',
        reply: 'Yes, 650 mg.',
        repliedByName: 'Dr. Meera Kulkarni',
        repliedAt: '2026-11-06T05:20:00Z',
      };
      await open(checksOf({ items: [rowOf({ query })] }));
      expect(squash(q('[data-testid=ph-reply]'))).toBe("Doctor's reply: Yes, 650 mg.");
      expect(q('[data-testid=ph-question]')).toBeNull();
    });

    it('has Reject, Query doctor and Verify, in that order', async () => {
      await open(checksOf());
      const buttons = Array.from(el().querySelectorAll('.sfrow .sfacts button')).map((b) => squash(b));
      expect(buttons).toEqual(['Reject', 'Query doctor', 'Verify']);
    });
  });

  describe('a Queried row', () => {
    const queried = () =>
      rowOf({
        status: 'QUERIED',
        canVerify: false,
        verifyLockReason: null,
        canQuery: false,
        query: {
          question: 'Right dose?',
          askedByName: 'Rahul Deshmukh',
          askedAt: '2026-11-06T05:00:00Z',
          reply: null,
          repliedByName: null,
          repliedAt: null,
        },
      });

    it('shows the pill Queried and the question, and no Verify and no Query doctor', async () => {
      await open(checksOf({ items: [queried()], queried: 1, waiting: 0 }));
      expect(squash(q('[data-testid=ph-queried]'))).toBe('Queried');
      expect(squash(q('[data-testid=ph-question]'))).toBe('Your query: Right dose?');
      const buttons = Array.from(el().querySelectorAll('.sfrow .sfacts button')).map((b) => squash(b));
      expect(buttons).toEqual(['Reject']);
    });
  });

  describe('Query doctor', () => {
    it('opens the sheet with the order and its version, and shows the answer and the toast', async () => {
      const answer = {
        ...verifiedResult({ outcome: 'QUERIED' }),
        checks: checksOf({ items: [], waiting: 0, queried: 1 }),
      };
      await open(checksOf({ items: [rowOf({ orderVersion: 3 })] }), answer);
      q<HTMLButtonElement>('[data-act=sfquery]')!.click();
      await settle();
      expect(dialog.open).toHaveBeenCalledWith(
        QuerySheetComponent,
        expect.objectContaining({
          data: expect.objectContaining({ orderId: 'o1', orderVersion: 3, drugName: 'Tab. Paracetamol 650 mg' }),
        }),
      );
      expect(snack.mock.calls[0][0]).toBe('Tab. Paracetamol 650 mg queried. The doctor sees your question on the EMR.');
      expect(store.items()).toHaveLength(0);
      expect(store.waiting()).toBe(0);
    });

    it('offers no button when the server says the order cannot be queried (the orderer)', async () => {
      await open(checksOf({ items: [rowOf({ canQuery: false })] }));
      expect(q('[data-act=sfquery]')).toBeNull();
    });

    it('a row whose current version the caller wrote: Verify off with the SELF_CHANGED hint, no Query doctor', async () => {
      await open(
        checksOf({ items: [rowOf({ canVerify: false, canQuery: false, verifyLockReason: 'SELF_CHANGED' })] }),
      );
      expect(q<HTMLButtonElement>('[data-act=sfver]')!.disabled).toBe(true);
      expect(q('[data-act=sfquery]')).toBeNull();
      expect(squash(q('[data-testid=ph-hint]'))).toBe(
        'The doctor who changed this order cannot verify it. Ask the pharmacist.',
      );
    });
  });

  describe('Review after dose', () => {
    it('is not there while nothing is open', async () => {
      await open(checksOf());
      expect(q('[data-testid=review-list]')).toBeNull();
    });

    it('lists the STAT doses given before the check, above the waiting orders, with the heading and the count', async () => {
      await open(checksOf({ reviewsOpen: 1 }));
      http.expectOne((r) => r.url === REVIEWS).flush(pageOf([review()]));
      await settle();
      expect(squash(q('#ph-rv-h'))).toBe('Review after dose (1)');
      expect(squash(q('[data-testid=rv-given]'))).toBe('Given before pharmacist check at 11:05 AM by Sr. Anjali Pawar');
      expect(squash(q('[data-testid=rv-title]'))).toBe('Inj. Ondansetron 4 mg · 4 mg · IV · STAT · from 6 Nov, 10:00 AM');
      const buttons = Array.from(el().querySelectorAll('[data-review] .sfacts button')).map((b) => squash(b));
      expect(buttons).toEqual(['Query doctor', 'Mark reviewed']);
      const list = q('[data-testid=review-list]')!;
      const waiting = q('[data-order]')!;
      expect(list.compareDocumentPosition(waiting) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('shows a hard stop in red and offers Query doctor only', async () => {
      await open(checksOf({ reviewsOpen: 1 }));
      http
        .expectOne((r) => r.url === REVIEWS)
        .flush(
          pageOf([review({ canMarkReviewed: false, check: checkOf({ hardStops: [hardStop('Documented allergy: x')] }) })]),
        );
      await settle();
      expect(squash(q('[data-testid=rv-hardstop]'))).toBe('Documented allergy: x');
      const buttons = Array.from(el().querySelectorAll('[data-review] .sfacts button')).map((b) => squash(b));
      expect(buttons).toEqual(['Query doctor']);
      expect(squash(q('[data-testid=rv-hint]'))).toContain('Query the doctor');
    });

    it('Mark reviewed posts with no body, shows the page now, and asks the panel to read its counts', async () => {
      await open(checksOf({ reviewsOpen: 1 }));
      http.expectOne((r) => r.url === REVIEWS).flush(pageOf([review()]));
      await settle();
      q<HTMLButtonElement>('[data-act=rv-done]')!.click();
      await settle();
      const req = http.expectOne(`${REVIEWS}/r1/mark-reviewed`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBeNull();
      expect(q<HTMLButtonElement>('[data-act=rv-done]')!.disabled).toBe(true);
      req.flush(pageOf([]));
      await settle();
      expect(snack.mock.calls[0][0]).toBe('Inj. Ondansetron 4 mg marked reviewed.');
      expect(squash(q('[data-testid=review-empty]'))).toBe('No doses to review.');
      http.expectOne((r) => r.url === LIST).flush(checksOf({ reviewsOpen: 0 }));
      await settle();
      expect(q('[data-testid=review-list]')).toBeNull();
    });

    it('shows the server text and reads the list again when Mark reviewed meets a 409', async () => {
      await open(checksOf({ reviewsOpen: 1 }));
      http.expectOne((r) => r.url === REVIEWS).flush(pageOf([review()]));
      await settle();
      q<HTMLButtonElement>('[data-act=rv-done]')!.click();
      await settle();
      http
        .expectOne(`${REVIEWS}/r1/mark-reviewed`)
        .flush(problem('order-blocked', 409, 'Hard stop on this order. Query the doctor.'), {
          status: 409,
          statusText: 'Conflict',
        });
      await settle();
      expect(snack.mock.calls[0][0]).toBe('Hard stop on this order. Query the doctor.');
      http.expectOne((r) => r.url === REVIEWS).flush(pageOf([review({ canMarkReviewed: false })]));
      http.expectOne((r) => r.url === LIST).flush(checksOf({ reviewsOpen: 1 }));
      await settle();
    });

    it('shows a load error with Try again and keeps the waiting orders', async () => {
      await open(checksOf({ reviewsOpen: 1 }));
      http.expectOne((r) => r.url === REVIEWS).flush(problem('server-error', 500, 'Try later.'), {
        status: 500,
        statusText: 'x',
      });
      await settle();
      expect(squash(q('[data-testid=review-error]'))).toContain('Try later.');
      expect(q('[data-order]')).not.toBeNull();
      q<HTMLButtonElement>('[data-act=rv-retry]')!.click();
      await settle();
      http.expectOne((r) => r.url === REVIEWS).flush(pageOf([review()]));
      await settle();
      expect(q('[data-review]')).not.toBeNull();
    });
  });
});
