import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { hardStop, problem, warning, checkOf } from '../emr/emr.fixtures';
import { PharmacistPanelComponent } from './pharmacist-panel.component';
import { PharmacistTileComponent } from './pharmacist-tile.component';
import { checksOf, rowOf, verifiedResult } from './pharmacist.fixtures';
import { PharmacistActionResult, PharmacistChecks } from './pharmacist.model';
import { PharmacistStore } from './pharmacist.store';
import { RejectSheetComponent } from './reject-sheet.component';
import { VerifySheetComponent } from './verify-sheet.component';

const LIST = '/api/v1/ipd/pharmacist-checks';

describe('PharmacistPanelComponent', () => {
  let fixture: ComponentFixture<PharmacistPanelComponent>;
  let http: HttpTestingController;
  let store: PharmacistStore;
  let snack: ReturnType<typeof vi.fn>;
  let dialog: { open: ReturnType<typeof vi.fn> };

  async function open(
    first: PharmacistChecks | { status: number; body: object } = checksOf(),
    dialogResult: unknown = undefined,
  ): Promise<void> {
    snack = vi.fn();
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(dialogResult) })) };
    await TestBed.configureTestingModule({
      imports: [PharmacistPanelComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        PharmacistStore,
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(PharmacistStore);
    fixture = TestBed.createComponent(PharmacistPanelComponent);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === LIST);
    if ('status' in first && 'body' in first) {
      req.flush(first.body, { status: first.status, statusText: 'x' });
    } else {
      req.flush(first);
    }
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const q = <T extends HTMLElement>(sel: string) => el().querySelector<T>(sel);
  const rows = () => Array.from(el().querySelectorAll<HTMLElement>('.sfrow'));
  const tick = () => new Promise<void>((r) => setTimeout(r, 0));

  afterEach(() => http.verify());

  it('has the title, the subtitle and loads the first page when it opens', async () => {
    await open();
    expect(squash(q('h2#ph-h'))).toBe('Pharmacist check');
    expect(squash(q('.sfph small'))).toBe(
      'Every new order is checked before the first dose. The person who ordered cannot verify it.',
    );
    expect(store.panelShown()).toBe(true);
  });

  it('shows "Loading" before the first answer', async () => {
    snack = vi.fn();
    await TestBed.configureTestingModule({
      imports: [PharmacistPanelComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        PharmacistStore,
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PharmacistPanelComponent);
    fixture.detectChanges();
    expect(squash(q('[role=status]'))).toBe('Loading the pharmacist checks');
    http.expectOne((r) => r.url === LIST).flush(checksOf());
  });

  it('says "No orders waiting." when there are none', async () => {
    await open(checksOf({ items: [], waiting: 0 }));
    expect(squash(q('[data-testid=pharmacist-empty]'))).toBe('No orders waiting.');
    expect(rows()).toHaveLength(0);
  });

  describe('a row (mockup pendRow)', () => {
    it('has the bold title, the small line, the buttons and nothing else for a clean order', async () => {
      await open();
      const row = rows()[0];
      expect(squash(row.querySelector('[data-testid=ph-title]'))).toBe('Tab. Paracetamol 650 mg · Once daily');
      expect(squash(row.querySelector('[data-testid=ph-line]'))).toBe(
        'Asha Patil · bed 101-A · Penicillin (rash) · ordered by Dr. Meera Kulkarni at 11:05 AM',
      );
      expect(row.querySelector('[data-testid=ph-hardstop]')).toBeNull();
      expect(row.querySelector('[data-testid=ph-warning]')).toBeNull();
      expect(row.querySelector('[data-testid=ph-reason]')).toBeNull();
      expect(Array.from(row.querySelectorAll('.sfacts button')).map((b) => squash(b))).toEqual(['Reject', 'Verify']);
      expect(row.querySelector<HTMLButtonElement>('[data-act=sfver]')!.disabled).toBe(false);
    });

    it('lists the hard stops, the warnings with their severity, and the doctor\'s reason', async () => {
      await open(
        checksOf({
          items: [
            rowOf({
              highAlert: 'Anticoagulant',
              overrideReason: { key: 'CARDIOLOGY_STENT', label: 'Recent coronary stent, cardiology advised' },
              check: checkOf({
                hardStops: [hardStop('Documented allergy: Penicillin (rash). X is a penicillin.')],
                warnings: [warning('Bleeding risk: heparin with aspirin'), warning('Allergies are not recorded.', 'MODERATE')],
              }),
              canVerify: false,
              verifyLockReason: 'HARD_STOP',
            }),
          ],
        }),
      );
      const row = rows()[0];
      expect(squash(row.querySelector('[data-testid=ph-hardstop]'))).toBe(
        'Documented allergy: Penicillin (rash). X is a penicillin.',
      );
      expect(Array.from(row.querySelectorAll('[data-testid=ph-warning]')).map((w) => squash(w))).toEqual([
        'Major: Bleeding risk: heparin with aspirin',
        'Moderate: Allergies are not recorded.',
      ]);
      expect(squash(row.querySelector('[data-testid=ph-reason]'))).toBe(
        "Doctor's reason: Recent coronary stent, cardiology advised",
      );
      expect(squash(row.querySelector('.sfmn .pill'))).toBe('High-alert');
    });

    it.each([
      ['HARD_STOP', 'Hard stop: reject this order'],
      ['SELF', 'The doctor who ordered this cannot verify it. Ask the pharmacist.'],
      ['IPD_READONLY', 'IPD is read-only. Nothing can be changed.'],
    ] as const)('turns Verify off with the hint for %s', async (reason, hint) => {
      await open(checksOf({ items: [rowOf({ canVerify: false, verifyLockReason: reason })] }));
      const verify = q<HTMLButtonElement>('[data-act=sfver]')!;
      expect(verify.disabled).toBe(true);
      expect(squash(q('[data-testid=ph-hint]'))).toBe(hint);
      expect(verify.getAttribute('aria-describedby')).toBe(q('[data-testid=ph-hint]')!.id);
      verify.click();
      expect(dialog.open).not.toHaveBeenCalled();
    });

    it('hides Reject when the row cannot be rejected', async () => {
      await open(checksOf({ items: [rowOf({ canReject: false })] }));
      expect(q('[data-act=sfrej]')).toBeNull();
    });

    it('gives each button a name that says which order', async () => {
      await open();
      expect(q('[data-act=sfver]')!.getAttribute('aria-label')).toBe('Verify Tab. Paracetamol 650 mg for Asha Patil');
      expect(q('[data-act=sfrej]')!.getAttribute('aria-label')).toBe('Reject Tab. Paracetamol 650 mg for Asha Patil');
    });
  });

  describe('Show more orders', () => {
    it('shows the button while the server sends a cursor and loads the next page', async () => {
      await open(checksOf({ items: [rowOf()], waiting: 2, nextCursor: 'c1' }));
      const more = q<HTMLButtonElement>('[data-act=ph-more]')!;
      expect(squash(more)).toBe('Show more orders');
      more.click();
      await settle();
      expect(more.disabled).toBe(true);
      const req = http.expectOne((r) => r.url === LIST);
      expect(req.request.params.get('cursor')).toBe('c1');
      req.flush(checksOf({ items: [rowOf({ orderId: 'o2' })], waiting: 2, nextCursor: null }));
      await settle();
      expect(rows()).toHaveLength(2);
      expect(q('[data-act=ph-more]')).toBeNull();
    });

    it('shows an error and keeps the button when a page fails', async () => {
      await open(checksOf({ items: [rowOf()], waiting: 2, nextCursor: 'c1' }));
      q<HTMLButtonElement>('[data-act=ph-more]')!.click();
      await settle();
      http.expectOne((r) => r.url === LIST).flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'x' });
      await settle();
      expect(squash(q('[data-testid=pharmacist-more-error]'))).toBe('Try later.');
      expect(q('[data-act=ph-more]')).not.toBeNull();
    });

    it('has no button on the last page', async () => {
      await open();
      expect(q('[data-act=ph-more]')).toBeNull();
    });
  });

  describe('failures', () => {
    it('shows the error with Try again, and reads again', async () => {
      await open({ status: 500, body: problem('server-error', 500, 'Try later.') });
      expect(squash(q('[data-testid=pharmacist-error]'))).toContain('Try later.');
      q<HTMLButtonElement>('[data-act=ph-retry]')!.click();
      await settle();
      http.expectOne((r) => r.url === LIST).flush(checksOf());
      await settle();
      expect(rows()).toHaveLength(1);
    });

    it('keeps the rows and says when the last good answer was after a failed refresh', async () => {
      await open();
      store.load();
      http.expectOne((r) => r.url === LIST).error(new ProgressEvent('error'));
      await settle();
      expect(squash(q('[data-testid=pharmacist-stale]'))).toBe('Could not refresh. Showing data from 11:17 AM.');
      expect(rows()).toHaveLength(1);
    });
  });

  describe('Verify and Reject open the sheets', () => {
    it('opens the Verify sheet with the row, the verifier and the reload callback', async () => {
      await open();
      q<HTMLButtonElement>('[data-act=sfver]')!.click();
      expect(dialog.open).toHaveBeenCalledTimes(1);
      const [component, config] = dialog.open.mock.calls[0];
      expect(component).toBe(VerifySheetComponent);
      expect(config.ariaLabelledBy).toBe('sf-vh');
      // The sheet places the focus itself; Material's own first focus is off (the trap stays on).
      expect(config.autoFocus).toBe(false);
      expect(config.data.row.orderId).toBe('o1');
      expect(config.data.verifierName).toBe('Rahul Deshmukh');
      expect(config.data.live()?.orderId).toBe('o1');
      config.data.onConflict();
      http.expectOne((r) => r.url === LIST).flush(checksOf({ items: [] }));
      expect(config.data.live()).toBeNull();
    });

    it('applies the answer, toasts "verified by" and moves the focus to the heading', async () => {
      const answer: PharmacistActionResult = verifiedResult();
      await open(checksOf(), answer);
      q<HTMLButtonElement>('[data-act=sfver]')!.click();
      await settle();
      await tick();
      expect(snack.mock.calls[0][0]).toBe('Tab. Paracetamol 650 mg verified by Rahul Deshmukh. It is now on the MAR.');
      expect(store.items()).toEqual([]);
      expect(store.waiting()).toBe(0);
      expect(document.activeElement?.id).toBe('ph-h');
    });

    it('does nothing more when the sheet is cancelled', async () => {
      await open(checksOf(), undefined);
      q<HTMLButtonElement>('[data-act=sfver]')!.click();
      await settle();
      expect(snack).not.toHaveBeenCalled();
      expect(rows()).toHaveLength(1);
    });

    it('opens the Reject sheet with the four reasons and toasts the reason on success', async () => {
      const answer = verifiedResult({
        outcome: 'REJECTED',
        rejectReason: { key: 'INTERACTION_RISK', label: 'Interaction risk too high' },
      });
      await open(checksOf(), answer);
      q<HTMLButtonElement>('[data-act=sfrej]')!.click();
      await settle();
      const [component, config] = dialog.open.mock.calls[0];
      expect(component).toBe(RejectSheetComponent);
      expect(config.ariaLabelledBy).toBe('sf-rj-title');
      expect(config.data.reasons.map((r: { label: string }) => r.label)).toEqual([
        'Allergy conflict',
        'Interaction risk too high',
        'Wrong dose or frequency',
        'Duplicate therapy',
      ]);
      expect(snack.mock.calls[0][0]).toBe('Order rejected · Interaction risk too high.');
    });
  });

  it('marks the panel as off screen when it is destroyed', async () => {
    await open();
    fixture.destroy();
    expect(store.panelShown()).toBe(false);
  });
});

describe('PharmacistTileComponent', () => {
  let fixture: ComponentFixture<PharmacistTileComponent>;
  let store: PharmacistStore;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PharmacistTileComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), PharmacistStore],
    }).compileComponents();
    store = TestBed.inject(PharmacistStore);
    fixture = TestBed.createComponent(PharmacistTileComponent);
  });

  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it('shows 0 in the normal colour before anything is read', () => {
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(squash(el.querySelector('.eyebrow'))).toBe('Pharmacist checks');
    expect(squash(el.querySelector('.v'))).toBe('0');
    expect(el.querySelector('.v')!.classList).not.toContain('warn');
    expect(squash(el.querySelector('.s'))).toBe('Orders waiting before the first dose');
  });

  it('shows the number in the warn colour above 0', () => {
    store.apply(checksOf({ waiting: 3 }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(squash(el.querySelector('.v'))).toBe('3');
    expect(el.querySelector('.v')!.classList).toContain('warn');
  });
});
