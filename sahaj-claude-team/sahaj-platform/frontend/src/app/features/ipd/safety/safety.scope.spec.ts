import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmergencyAccessReviewPage } from '../emergency-access/emergency-access.model';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { AS_OF, ROW, asNurse, boardOf, eventOf, pageOf, problem } from './safety.fixtures';
import { SafetyComponent } from './safety.component';
import { SafetyBoard, SafetyCounts } from './safety.model';

const IPD = '/api/v1/ipd';
const BOARD = `${IPD}/safety`;
const EVENTS = `${IPD}/clinical-events`;

const reviewPage = (waiting: number): EmergencyAccessReviewPage => ({
  asOf: AS_OF,
  waiting,
  nextCursor: null,
  items: Array.from({ length: waiting }, (_, i) => ({
    grantId: `g${i + 1}`,
    admissionId: `a${i + 1}`,
    staffName: 'Meera Joshi',
    patientName: 'Asha Patil',
    bedCode: '101-A',
    reasonKey: 'DETERIORATING' as const,
    reasonLabel: 'Patient deteriorating, rapid response',
    openedAt: '2026-11-05T05:35:00Z',
    expiresAt: '2026-11-05T06:05:00Z',
    admissionStatus: 'ADMITTED' as const,
  })),
});

/** A NURSE board: view NURSE, the tile "Your patients". */
const nurseBoard = (over: Partial<SafetyBoard> = {}): SafetyBoard =>
  boardOf({ view: 'NURSE', yourPatients: 2, escalations: [asNurse(ROW)], ...over });

describe('SafetyComponent: ward scope and emergency access review (SAH-34)', () => {
  let fixture: ComponentFixture<SafetyComponent>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let counts: SafetyCounts[];
  let bus: ScopeRefusals;

  async function open(board: SafetyBoard, events = pageOf([eventOf()])): Promise<void> {
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    bus = TestBed.inject(ScopeRefusals);
    fixture = TestBed.createComponent(SafetyComponent);
    counts = [];
    fixture.componentInstance.counts.subscribe((c) => counts.push(c));
    fixture.detectChanges();
    http.expectOne(BOARD).flush(board);
    eventsReq().flush(events);
    await settle();
  }

  const eventsReq = (): TestRequest => http.expectOne((r) => r.url === EVENTS);

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const tile = (name: string) => q(`[data-kpi=${name}]`);
  /** The three parts of a tile: the title, the number and the small text. */
  const parts = (name: string) =>
    Array.from(tile(name)!.querySelectorAll('.eyebrow, .v, .s')).map((n) => squash(n));

  afterEach(() => http.verify());

  describe('the scope note above the tiles (AC 37)', () => {
    it('says "This board shows your wards only (N of M patients)." for a scoped nurse', async () => {
      await open(nurseBoard({ scope: { kind: 'WARDS', visible: 2, admitted: 12, hasWard: true } }));
      expect(squash(q('[data-testid=safety-scope-note]'))).toBe('This board shows your wards only (2 of 12 patients).');
      const first = el().querySelector('.safety > *:first-child')!;
      expect(first.getAttribute('data-testid')).toBe('safety-scope-note');
      expect(first.nextElementSibling!.className).toContain('notice');
      expect(q('.safety > .kpis')).not.toBeNull();
    });

    it('says "No wards are assigned to you, so this board shows no patients." for a nurse with no ward', async () => {
      await open(
        nurseBoard({
          scope: { kind: 'WARDS', visible: 0, admitted: 12, hasWard: false },
          yourPatients: 0,
          escalations: [],
        }),
      );
      expect(squash(q('[data-testid=safety-scope-note]'))).toBe(
        'No wards are assigned to you, so this board shows no patients.',
      );
      expect(squash(q('.empty'))).toContain('No escalations.');
    });

    it('shows no note for the scope all (a nurse with the switch off, OWNER, DOCTOR)', async () => {
      await open(nurseBoard({ scope: { kind: 'ALL', visible: 12, admitted: 12, hasWard: false } }));
      expect(q('[data-testid=safety-scope-note]')).toBeNull();
      fixture.destroy();
      TestBed.resetTestingModule();
      await open(boardOf());
      expect(q('[data-testid=safety-scope-note]')).toBeNull();
    });
  });

  describe('the tile "Your patients" (AC 16)', () => {
    it('reads "In your wards" for the scope wards', async () => {
      await open(nurseBoard({ scope: { kind: 'WARDS', visible: 2, admitted: 12, hasWard: true } }));
      expect(parts('your-patients')).toEqual(['Your patients', '2', 'In your wards']);
    });

    it('reads "All admitted patients" while the switch is off', async () => {
      await open(nurseBoard({ yourPatients: 12, scope: { kind: 'ALL', visible: 12, admitted: 12, hasWard: false } }));
      expect(parts('your-patients')).toEqual(['Your patients', '12', 'All admitted patients']);
    });

    it('keeps "In your wards" when the answer has no scope (the mockup text)', async () => {
      await open(nurseBoard());
      expect(parts('your-patients')).toEqual(['Your patients', '2', 'In your wards']);
    });
  });

  describe('escalations past target in other wards (AC 20, 37; x-needs-human)', () => {
    const scoped = { kind: 'WARDS' as const, visible: 2, admitted: 12, hasWard: true };

    it('shows the red notice with the count and no names', async () => {
      await open(nurseBoard({ scope: scoped, otherWardsPastTarget: 2 }));
      expect(squash(q('[data-testid=safety-other-wards]'))).toBe(
        '2 escalations past the response target in other wards. Tell the doctor now, or use Emergency access if you must act.',
      );
      expect(q('[data-testid=safety-other-wards]')!.getAttribute('role')).toBe('status');
    });

    it('says "1 escalation" for one', async () => {
      await open(nurseBoard({ scope: scoped, otherWardsPastTarget: 1 }));
      expect(squash(q('[data-testid=safety-other-wards]'))).toContain('1 escalation past the response target in other wards.');
    });

    it('is absent at 0 and when the server does not send it', async () => {
      await open(nurseBoard({ scope: scoped, otherWardsPastTarget: 0 }));
      expect(q('[data-testid=safety-other-wards]')).toBeNull();
      fixture.destroy();
      TestBed.resetTestingModule();
      await open(nurseBoard({ scope: scoped }));
      expect(q('[data-testid=safety-other-wards]')).toBeNull();
    });

    it('is not part of the tile, the rows or the number the page takes from the counts', async () => {
      await open(nurseBoard({ scope: scoped, otherWardsPastTarget: 3 }));
      expect(counts[0].open).toBe(1);
      expect(parts('escalations')[1]).toBe('1');
    });
  });

  describe('the owner: tile, panel and audit entry (story 34F)', () => {
    it('shows the fourth tile "Emergency access" with N and "Waiting for your review", amber above 0', async () => {
      await open(boardOf({ emergencyAccess: reviewPage(2) }));
      expect(parts('emergency-access')).toEqual(['Emergency access', '2', 'Waiting for your review']);
      expect(q('[data-testid=emergency-access-waiting]')!.classList.contains('warn')).toBe(true);
      expect(tile('your-patients')).toBeNull();
      const tiles = Array.from(el().querySelectorAll('.kpis .kpi')).map((k) => k.getAttribute('data-kpi'));
      expect(tiles[tiles.length - 1]).toBe('emergency-access');
    });

    it('puts the panel below the Medicine round and above the Clinical audit trail', async () => {
      await open(boardOf({ emergencyAccess: reviewPage(1) }));
      const blocks = Array.from(el().querySelectorAll('.safety > *')).map((n) =>
        n.tagName === 'APP-MEDICINE-ROUND' ? 'round' : n.tagName === 'APP-EMERGENCY-REVIEW' ? 'review' : n.className.split(' ')[0],
      );
      expect(blocks).toEqual(['notice', 'kpis', 'panel', 'round', 'review', 'panel']);
      expect(squash(q('app-emergency-review h2'))).toBe('Emergency access to review');
    });

    it('shows the tile at 0 without amber, and no panel', async () => {
      await open(boardOf({ emergencyAccess: reviewPage(0) }));
      expect(parts('emergency-access')).toEqual(['Emergency access', '0', 'Waiting for your review']);
      expect(q('[data-testid=emergency-access-waiting]')!.classList.contains('warn')).toBe(false);
      expect(q('app-emergency-review')).toBeNull();
    });

    it('shows neither tile nor panel when the server sends no emergencyAccess (DOCTOR, NURSE)', async () => {
      await open(boardOf());
      expect(tile('emergency-access')).toBeNull();
      expect(q('app-emergency-review')).toBeNull();
      fixture.destroy();
      TestBed.resetTestingModule();
      await open(nurseBoard());
      expect(tile('emergency-access')).toBeNull();
      expect(q('app-emergency-review')).toBeNull();
    });

    it('reads the board again after Mark reviewed, so the tile and the panel follow the server', async () => {
      await open(boardOf({ emergencyAccess: reviewPage(2) }));
      q<HTMLButtonElement>('[data-grant=g1] [data-testid=mark-reviewed]')!.click();
      await settle();
      http.expectOne(`${IPD}/emergency-access/g1/review`).flush({ grantId: 'g1', reviewedAt: AS_OF, waiting: 1 });
      await settle();
      http.expectOne(BOARD).flush(boardOf({ emergencyAccess: reviewPage(1) }));
      eventsReq().flush(pageOf([eventOf()]));
      await settle();
      expect(parts('emergency-access')).toEqual(['Emergency access', '1', 'Waiting for your review']);
      expect(el().querySelectorAll('[data-testid=review-row]').length).toBe(1);
    });

    it('clears the board when the review is refused with 403 (SAH-40)', async () => {
      await open(boardOf({ emergencyAccess: reviewPage(1) }));
      q<HTMLButtonElement>('[data-grant=g1] [data-testid=mark-reviewed]')!.click();
      await settle();
      http
        .expectOne(`${IPD}/emergency-access/g1/review`)
        .flush(problem('forbidden', 403, 'Only the owner can review.'), { status: 403, statusText: 'Forbidden' });
      await settle();
      expect(q('app-emergency-review')).toBeNull();
      expect(q('[data-testid=safety-board]')).toBeNull();
      expect(squash(q('.notice.err'))).toContain('Only the owner can review.');
    });

    it('shows the review entry of the audit trail as "Reviewed emergency access by <staff>" and the opening entry as stored', async () => {
      await open(
        boardOf(),
        pageOf([
          eventOf({ id: 'x2', kind: 'bg', text: 'Reviewed emergency access', grantStaffName: 'Meera Joshi' }),
          eventOf({ id: 'x3', kind: 'bg', text: 'Emergency access opened · Patient deteriorating, rapid response' }),
        ]),
      );
      const titles = Array.from(el().querySelectorAll('.sfai b')).map((b) => squash(b));
      expect(titles).toEqual([
        'Reviewed emergency access by Meera Joshi',
        'Emergency access opened · Patient deteriorating, rapid response',
      ]);
      expect(el().querySelector('.sfai .ti app-icon')).not.toBeNull();
    });
  });

  describe('a refusal for one patient (AC 17, 38)', () => {
    const NURSE_ROW = asNurse({ ...ROW, pastTarget: true });

    it('Call senior for a patient outside her wards: no notice of failure, the page is told, and the board reads again', async () => {
      await open(nurseBoard({ escalations: [NURSE_ROW], scope: { kind: 'WARDS', visible: 1, admitted: 12, hasWard: true } }));
      const seen: { admissionId: string; write: boolean }[] = [];
      bus.refusals.subscribe((r) => seen.push(r));
      q<HTMLButtonElement>('[data-esc=e1] [data-act=sfup]')!.click();
      await settle();
      http
        .expectOne(`${IPD}/escalations/e1/call-senior`)
        .flush(problem('outside-scope', 403, 'This patient is not in your wards.'), {
          status: 403,
          statusText: 'Forbidden',
        });
      await settle();
      expect(seen).toEqual([{ admissionId: 'a1', write: true }]);
      expect(q('[data-testid=safety-action-notice]')).toBeNull();
      http.expectOne(BOARD).flush(nurseBoard({ escalations: [], scope: { kind: 'WARDS', visible: 0, admitted: 12, hasWard: false } }));
      eventsReq().flush(pageOf([]));
      await settle();
      expect(q('[data-esc=e1]')).toBeNull();
      expect(q('[data-testid=safety-board]')).not.toBeNull();
    });

    it('reads the board again when a sheet elsewhere meets a refusal (the Medicine round Give, for example)', async () => {
      await open(nurseBoard());
      bus.report('a2', false);
      await settle();
      http.expectOne(BOARD).flush(nurseBoard());
      eventsReq().flush(pageOf([eventOf()]));
      await settle();
      expect(q('[data-testid=safety-board]')).not.toBeNull();
    });
  });
});
