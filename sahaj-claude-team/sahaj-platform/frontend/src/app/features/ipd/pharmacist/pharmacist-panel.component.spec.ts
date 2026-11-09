import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NEVER, Subject, of } from 'rxjs';
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
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
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

  describe('a refused read (SAH-40)', () => {
    const SUMMARY = `${LIST}/summary`;
    const refuseByPoll = async () => {
      store.pollSummary();
      http.expectOne(SUMMARY).flush(problem('forbidden', 403, 'You cannot see the pharmacist checks.'), {
        status: 403,
        statusText: 'Forbidden',
      });
      await settle();
    };

    it('removes the rows and shows the notice with Try again, never "No orders waiting." (AC 12, 15, 17)', async () => {
      await open(checksOf({ items: [rowOf(), rowOf({ orderId: 'o2' })], waiting: 2, nextCursor: 'c1' }));
      expect(rows()).toHaveLength(2);
      await refuseByPoll();
      expect(rows()).toHaveLength(0);
      expect(q('[data-testid=pharmacist-empty]')).toBeNull();
      expect(el().textContent).not.toContain('No orders waiting.');
      expect(el().textContent).not.toContain('Show more orders');
      const notice = q('[data-testid=pharmacist-error]')!;
      expect(notice.getAttribute('role')).toBe('alert');
      expect(squash(notice)).toContain('You cannot see the pharmacist checks.');
      expect(squash(notice)).toContain('Try again');
    });

    it('Try again loads like a first load and shows the rows when access is back (AC 21)', async () => {
      await open();
      await refuseByPoll();
      q<HTMLButtonElement>('[data-act=ph-retry]')!.click();
      fixture.detectChanges();
      expect(el().textContent).toContain('Loading the pharmacist checks');
      http.expectOne((r) => r.url === LIST).flush(checksOf());
      await settle();
      expect(rows()).toHaveLength(1);
      expect(q('[data-testid=pharmacist-error]')).toBeNull();
    });

    it('moves focus to the notice when the focused row button is gone (AC 23)', async () => {
      await open();
      const button = q<HTMLButtonElement>('.sfrow button')!;
      button.focus();
      expect(document.activeElement).toBe(button);
      await refuseByPoll();
      expect(document.activeElement).toBe(q('[data-testid=pharmacist-error]'));
    });

    describe('an open sheet (review fix 2)', () => {
      let closed: Subject<undefined>;
      let sheetClose: ReturnType<typeof vi.fn>;

      async function withSheet(selector: string): Promise<{ data: { onConflict: () => void } }> {
        await open();
        closed = new Subject<undefined>();
        sheetClose = vi.fn(() => closed.next(undefined));
        dialog.open.mockReturnValue({ afterClosed: () => closed, close: sheetClose });
        q<HTMLButtonElement>(selector)!.click();
        expect(dialog.open).toHaveBeenCalledTimes(1);
        return dialog.open.mock.calls[0][1] as { data: { onConflict: () => void } };
      }

      for (const [name, selector] of [
        ['Verify', '[data-act=sfver]'],
        ['Reject', '[data-act=sfrej]'],
      ] as const) {
        it(`closes the ${name} sheet when the counts poll is refused`, async () => {
          await withSheet(selector);
          expect(sheetClose).not.toHaveBeenCalled();
          await refuseByPoll();
          expect(sheetClose).toHaveBeenCalledTimes(1);
          expect(rows()).toHaveLength(0);
        });
      }

      it('closes the Verify sheet when a reload is refused (403 module-off)', async () => {
        await withSheet('[data-act=sfver]');
        store.load();
        http.expectOne((r) => r.url === LIST).flush(problem('module-off', 403, 'IPD is off.'), {
          status: 403,
          statusText: 'Forbidden',
        });
        await settle();
        expect(sheetClose).toHaveBeenCalledTimes(1);
      });

      it('closes the Verify sheet when Show more orders is refused', async () => {
        await open(checksOf({ nextCursor: 'c1' }));
        closed = new Subject<undefined>();
        sheetClose = vi.fn(() => closed.next(undefined));
        dialog.open.mockReturnValue({ afterClosed: () => closed, close: sheetClose });
        q<HTMLButtonElement>('[data-act=sfver]')!.click();
        store.loadMore();
        http.expectOne((r) => r.url === LIST).flush(problem('forbidden', 403, 'No.'), {
          status: 403,
          statusText: 'Forbidden',
        });
        await settle();
        expect(sheetClose).toHaveBeenCalledTimes(1);
      });

      it('does not close a sheet on a failed refresh that is not a refusal', async () => {
        await withSheet('[data-act=sfver]');
        store.load();
        http.expectOne((r) => r.url === LIST).flush(problem('server-error', 500, 'x'), {
          status: 500,
          statusText: 'x',
        });
        await settle();
        expect(sheetClose).not.toHaveBeenCalled();
      });

      it('a conflict callback does not read the list while refused, and reads it otherwise', async () => {
        const config = await withSheet('[data-act=sfver]');
        config.data.onConflict();
        http.expectOne((r) => r.url === LIST).flush(checksOf());
        await refuseByPoll();
        config.data.onConflict();
        http.expectNone((r) => r.url === LIST);
        expect(rows()).toHaveLength(0);
      });

      it('the Reject conflict callback does not read the list while refused', async () => {
        const config = await withSheet('[data-act=sfrej]');
        await refuseByPoll();
        config.data.onConflict();
        http.expectNone((r) => r.url === LIST);
      });

      it('Try again after the refusal reads again and the panel works', async () => {
        await withSheet('[data-act=sfver]');
        await refuseByPoll();
        q<HTMLButtonElement>('[data-act=ph-retry]')!.click();
        http.expectOne((r) => r.url === LIST).flush(checksOf());
        await settle();
        expect(rows()).toHaveLength(1);
      });

      it('moves focus to the notice when the focus was inside the sheet', async () => {
        await withSheet('[data-act=sfver]');
        await refuseByPoll();
        expect(document.activeElement).toBe(q('[data-testid=pharmacist-error]'));
      });
    });

    it('dismisses the toast of an earlier success (review fix 5)', async () => {
      await open();
      const dismiss = TestBed.inject(MatSnackBar).dismiss as ReturnType<typeof vi.fn>;
      expect(dismiss).not.toHaveBeenCalled();
      await refuseByPoll();
      expect(dismiss).toHaveBeenCalledTimes(1);
    });

    it('moves focus to the notice on a repeated refusal when Try again was replaced by the Loading text (review fix 3)', async () => {
      await open();
      await refuseByPoll();
      const retry = q<HTMLButtonElement>('[data-act=ph-retry]')!;
      retry.focus();
      retry.click();
      await settle();
      expect(document.activeElement).toBe(document.body);
      http.expectOne((r) => r.url === LIST).flush(problem('forbidden', 403, 'No.'), {
        status: 403,
        statusText: 'Forbidden',
      });
      await settle();
      expect(document.activeElement).toBe(q('[data-testid=pharmacist-error]'));
    });

    it('leaves focus alone when it was not inside the panel', async () => {
      await open();
      const outside = document.createElement('button');
      document.body.appendChild(outside);
      outside.focus();
      await refuseByPoll();
      expect(document.activeElement).toBe(outside);
      outside.remove();
    });
  });

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
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
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

  describe('an open sheet when the panel goes (SAH-40 D-1)', () => {
    for (const [name, selector] of [
      ['Verify', '[data-act=sfver]'],
      ['Reject', '[data-act=sfrej]'],
    ] as const) {
      it(`closes the ${name} sheet when the panel is destroyed`, async () => {
        await open();
        const close = vi.fn();
        dialog.open.mockReturnValue({ afterClosed: () => NEVER, close });
        q<HTMLButtonElement>(selector)!.click();
        expect(close).not.toHaveBeenCalled();
        fixture.destroy();
        expect(close).toHaveBeenCalledTimes(1);
      });
    }

    it('closes nothing on destroy once the sheet is closed', async () => {
      await open();
      const close = vi.fn();
      const closed = new Subject<undefined>();
      dialog.open.mockReturnValue({ afterClosed: () => closed, close });
      q<HTMLButtonElement>('[data-act=sfver]')!.click();
      closed.next(undefined);
      fixture.destroy();
      expect(close).not.toHaveBeenCalled();
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

  it('shows a dash, never 0, in the normal colour before anything is read or after a refusal (SAH-40)', () => {
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(squash(el.querySelector('.eyebrow'))).toBe('Pharmacist checks');
    expect(squash(el.querySelector('.v'))).toBe('—');
    expect(el.querySelector('.v')!.classList).not.toContain('warn');
    expect(squash(el.querySelector('.s'))).toBe('Orders waiting before the first dose');
  });

  it('shows 0 once the server says nothing waits', () => {
    store.apply(checksOf({ items: [], waiting: 0 }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(squash(el.querySelector('.v'))).toBe('0');
    expect(el.querySelector('.v')!.classList).not.toContain('warn');
    store.clear();
    fixture.detectChanges();
    expect(squash(el.querySelector('.v'))).toBe('—');
  });

  it('shows the number in the warn colour above 0', () => {
    store.apply(checksOf({ waiting: 3 }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(squash(el.querySelector('.v'))).toBe('3');
    expect(el.querySelector('.v')!.classList).toContain('warn');
  });
});
