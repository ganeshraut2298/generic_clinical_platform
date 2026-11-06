import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ResponseSheetComponent, ResponseSheetData } from './response-sheet.component';
import { ACTIONS, AS_OF, ROW, problem } from './safety.fixtures';
import { EscalationRow } from './safety.model';

const URL = '/api/v1/ipd/escalations/e1/respond';

describe('ResponseSheetComponent', () => {
  let fixture: ComponentFixture<ResponseSheetComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let ref: { close: ReturnType<typeof vi.fn>; disableClose?: boolean };
  let onConflict: ReturnType<typeof vi.fn<() => void>>;
  let live: ReturnType<typeof signal<EscalationRow | null>>;

  async function open(row: EscalationRow = ROW): Promise<void> {
    close = vi.fn();
    ref = { close };
    onConflict = vi.fn<() => void>();
    live = signal<EscalationRow | null>(row);
    const data: ResponseSheetData = { row, actions: ACTIONS, live, onConflict };
    await TestBed.configureTestingModule({
      imports: [ResponseSheetComponent],
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ResponseSheetComponent);
    fixture.detectChanges();
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const select = () => el().querySelector<HTMLSelectElement>('#rs-action')!;
  const note = () => el().querySelector<HTMLTextAreaElement>('#rs-note')!;
  const submitButton = () => el().querySelector<HTMLButtonElement>('[data-act=response-close]')!;

  async function type(text: string): Promise<void> {
    note().value = text;
    note().dispatchEvent(new Event('input'));
    await settle();
  }

  async function submit(): Promise<void> {
    submitButton().click();
    await settle();
  }

  const okResult = { asOf: AS_OF, replayed: false, escalation: { ...ROW, status: 'DONE' as const } };

  afterEach(() => http.verify());

  it('shows the title, the subtitle, the select, the text area and the footer as the mockup', async () => {
    await open();
    expect(el().querySelector('h2#rs-title')!.textContent).toBe('Response · Asha Patil');
    expect(squash(el().querySelector('[data-testid=response-subtitle]'))).toBe(
      'EWS 8 (high) · raised 17 min ago by Priya Nair',
    );
    expect(squash(el().querySelector('label[for=rs-action]'))).toBe('What was done');
    expect(Array.from(select().options).map((o) => o.textContent)).toEqual([
      'Reviewed at the bedside',
      'Treatment changed',
      'Moved to ICU or HDU',
      'Rapid response team called',
      'Reviewed, no change needed',
    ]);
    expect(select().value).toBe('BEDSIDE_REVIEW');
    expect(squash(el().querySelector('label[for=rs-note]'))).toBe('Plan and next vitals');
    expect(note().rows).toBe(3);
    expect(note().placeholder).toBe('For example: oxygen increased to 6 L, repeat vitals in 30 minutes');
    const footer = Array.from(el().querySelectorAll('.sheet-f button')).map((b) => squash(b));
    expect(footer).toEqual(['Cancel', 'Close escalation']);
    expect(submitButton().querySelector('app-icon')).not.toBeNull();
  });

  it('refuses an empty plan with the mockup message, sends nothing and keeps the sheet open', async () => {
    await open();
    await type('   ');
    await submit();
    expect(squash(el().querySelector('#rs-note-err'))).toBe('Write the plan and when vitals are next due.');
    expect(note().getAttribute('aria-invalid')).toBe('true');
    expect(note().getAttribute('aria-describedby')).toBe('rs-note-err rs-note-count');
    http.expectNone(URL);
    expect(close).not.toHaveBeenCalled();
  });

  it('refuses a plan of more than 1,000 characters and control characters before sending', async () => {
    await open();
    await type('a'.repeat(1001));
    await submit();
    expect(squash(el().querySelector('#rs-note-err'))).toBe('Keep the plan to 1,000 characters.');
    await type(`bad${String.fromCharCode(7)}bell`);
    await submit();
    expect(squash(el().querySelector('#rs-note-err'))).toBe('Plan cannot contain control or text-direction characters');
    http.expectNone(URL);
  });

  it('clears the plan message when the user types again', async () => {
    await open();
    await submit();
    expect(el().querySelector('#rs-note-err')).not.toBeNull();
    await type('Oxygen increased');
    expect(el().querySelector('#rs-note-err')).toBeNull();
  });

  it('sends the version, the chosen action and the plan, then closes with the answer', async () => {
    await open();
    select().value = 'NO_CHANGE';
    select().dispatchEvent(new Event('change'));
    await type('Oxygen to 6 L, repeat vitals in 30 minutes');
    await submit();
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({
      version: 1,
      action: 'NO_CHANGE',
      note: 'Oxygen to 6 L, repeat vitals in 30 minutes',
    });
    req.flush(okResult);
    await settle();
    expect(close).toHaveBeenCalledWith(okResult);
  });

  it('sends the first action when nothing was changed', async () => {
    await open();
    await type('Plan');
    await submit();
    expect(http.expectOne(URL).request.body.action).toBe('BEDSIDE_REVIEW');
  });

  it('guards a double tap: one request while it runs', async () => {
    await open();
    await type('Plan');
    submitButton().click();
    submitButton().click();
    await settle();
    expect(submitButton().disabled).toBe(true);
    http.expectOne(URL).flush(okResult);
    await settle();
  });

  it('cancels with no result', async () => {
    await open();
    el().querySelector<HTMLButtonElement>('[data-act=response-cancel]')!.click();
    expect(close).toHaveBeenCalledWith();
  });

  describe('closing (SAH-33 review fix, code review 4)', () => {
    const cancelButton = () => el().querySelector<HTMLButtonElement>('[data-act=response-cancel]')!;
    const xButton = () => el().querySelector<HTMLButtonElement>('[data-act=response-x]')!;
    const confirm = () => el().querySelector('[data-testid=response-discard]');
    const later = () => new Promise<void>((resolve) => setTimeout(resolve, 5));

    it('lets Esc and the backdrop close the sheet while nothing is typed', async () => {
      await open();
      expect(ref.disableClose).toBe(false);
    });

    it('blocks Esc and the backdrop once a plan is typed, and allows them again when it is emptied', async () => {
      await open();
      await type('Oxygen to 6 L');
      expect(ref.disableClose).toBe(true);
      await type('   ');
      expect(ref.disableClose).toBe(false);
    });

    it('disables Cancel and the x button while the request runs, and blocks Esc and the backdrop', async () => {
      await open();
      await type('Plan');
      await submit();
      expect(cancelButton().disabled).toBe(true);
      expect(xButton().disabled).toBe(true);
      expect(ref.disableClose).toBe(true);
      cancelButton().click();
      expect(confirm()).toBeNull();
      expect(close).not.toHaveBeenCalled();
      http.expectOne(URL).flush(okResult);
      await settle();
      expect(close).toHaveBeenCalledWith(okResult);
    });

    it('keeps Esc blocked while the request runs even when the plan is emptied meanwhile', async () => {
      await open();
      await type('Plan');
      await submit();
      await type('');
      expect(ref.disableClose).toBe(true);
      http.expectOne(URL).flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
      await settle();
      expect(cancelButton().disabled).toBe(false);
      expect(ref.disableClose).toBe(false);
    });

    for (const [name, button] of [
      ['Cancel', cancelButton],
      ['the x button', xButton],
    ] as const) {
      it(`asks "Discard the plan you typed?" before ${name} closes a sheet with a typed plan`, async () => {
        await open();
        await type('Oxygen to 6 L');
        button().click();
        await settle();
        expect(close).not.toHaveBeenCalled();
        expect(squash(confirm())).toBe('Discard the plan you typed? Keep editing Discard plan');
        await later();
        expect(document.activeElement?.id).toBe('rs-discard');
      });
    }

    it('Keep editing hides the question and returns to the plan with the text intact', async () => {
      await open();
      await type('Oxygen to 6 L');
      cancelButton().click();
      await settle();
      el().querySelector<HTMLButtonElement>('[data-act=response-keep]')!.click();
      await settle();
      await later();
      expect(confirm()).toBeNull();
      expect(note().value).toBe('Oxygen to 6 L');
      expect(document.activeElement).toBe(note());
      expect(close).not.toHaveBeenCalled();
    });

    it('Discard plan closes with no result', async () => {
      await open();
      await type('Oxygen to 6 L');
      cancelButton().click();
      await settle();
      el().querySelector<HTMLButtonElement>('[data-act=response-discard]')!.click();
      expect(close).toHaveBeenCalledWith();
    });

    it('closes at once with Cancel when nothing is typed', async () => {
      await open();
      xButton().click();
      expect(confirm()).toBeNull();
      expect(close).toHaveBeenCalledWith();
    });
  });

  describe('plan counter and limit (security review I6)', () => {
    const counter = () => squash(el().querySelector('[data-testid=plan-counter]'));

    it('shows 0 / 1,000 at first and follows the typing', async () => {
      await open();
      expect(counter()).toBe('0 / 1,000');
      await type('Oxygen');
      expect(counter()).toBe('6 / 1,000');
    });

    it('counts code points, not UTF-16 units', async () => {
      await open();
      await type('😀😀');
      expect(counter()).toBe('2 / 1,000');
    });

    it('past the limit shows the contract message and the counter in the over state, at once', async () => {
      await open();
      await type('a'.repeat(1000));
      expect(el().querySelector('#rs-note-err')).toBeNull();
      expect(el().querySelector('[data-testid=plan-counter]')!.classList).not.toContain('over');
      await type('a'.repeat(1001));
      expect(counter()).toBe('1,001 / 1,000');
      expect(el().querySelector('[data-testid=plan-counter]')!.classList).toContain('over');
      expect(squash(el().querySelector('#rs-note-err'))).toBe('Keep the plan to 1,000 characters.');
      await submit();
      http.expectNone(URL);
    });

    it('a plan of exactly 1,000 emoji is sent', async () => {
      await open();
      await type('😀'.repeat(1000));
      await submit();
      http.expectOne(URL).flush(okResult);
      await settle();
    });
  });

  it('shows the field messages of a 400 and keeps the typed text', async () => {
    await open();
    await type('Plan');
    await submit();
    http
      .expectOne(URL)
      .flush(
        problem('validation', 400, 'Write the plan and when vitals are next due.', [
          { field: 'note', message: 'Write the plan and when vitals are next due.' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();
    expect(squash(el().querySelector('#rs-note-err'))).toBe('Write the plan and when vitals are next due.');
    expect(note().value).toBe('Plan');
    expect(close).not.toHaveBeenCalled();
  });

  it('shows "Choose what was done" for an action field error and a reload message for the version', async () => {
    await open();
    await type('Plan');
    await submit();
    http.expectOne(URL).flush(
      problem('validation', 400, 'Reload the page and try again.', [
        { field: 'version', message: 'Reload the page and try again.' },
        { field: 'action', message: 'Anything' },
      ]),
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(squash(el().querySelector('#rs-action-err'))).toBe('Choose what was done');
    expect(squash(el().querySelector('[data-testid=response-failure]'))).toBe('Reload the page and try again.');
  });

  it('on a 5xx or a lost connection keeps the sheet, the action and the typed plan, and allows a retry', async () => {
    await open();
    select().value = 'RAPID_RESPONSE';
    select().dispatchEvent(new Event('change'));
    await type('Rapid response team on the way');
    await submit();
    http.expectOne(URL).flush(problem('server-error', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
    await settle();
    expect(squash(el().querySelector('[data-testid=response-failure]'))).toBe('Try later.');
    expect(note().value).toBe('Rapid response team on the way');
    expect(select().value).toBe('RAPID_RESPONSE');
    expect(submitButton().disabled).toBe(false);

    await submit();
    http.expectOne(URL).error(new ProgressEvent('error'));
    await settle();
    expect(squash(el().querySelector('[data-testid=response-failure]'))).toBe(
      'We could not record the response. What you typed is kept. Try again.',
    );
    await submit();
    http.expectOne(URL).flush({ ...okResult, replayed: true });
    await settle();
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('on 403 keeps the sheet and shows the message', async () => {
    await open();
    await type('Plan');
    await submit();
    http
      .expectOne(URL)
      .flush(problem('forbidden', 403, 'Only a doctor can do this.'), { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(squash(el().querySelector('[data-testid=response-failure]'))).toBe('Only a doctor can do this.');
    expect(onConflict).not.toHaveBeenCalled();
  });

  describe('409', () => {
    async function conflict(slug: string, detail: string): Promise<void> {
      await type('Plan');
      await submit();
      http.expectOne(URL).flush(problem(slug, 409, detail), { status: 409, statusText: 'Conflict' });
      await settle();
    }

    it('escalation-changed: shows the message, asks for a reload and, once reloaded, shows the new score and sends the new version', async () => {
      await open();
      await conflict('escalation-changed', 'The score is now EWS 9 (high). Review it and try again.');
      expect(squash(el().querySelector('[data-testid=response-failure]'))).toBe(
        'The score is now EWS 9 (high). Review it and try again.',
      );
      expect(onConflict).toHaveBeenCalledTimes(1);
      expect(note().value).toBe('Plan');
      expect(squash(el().querySelector('[data-testid=response-subtitle]'))).toContain('EWS 8 (high)');

      live.set({ ...ROW, ewsScore: 9, version: 2 });
      await settle();
      expect(squash(el().querySelector('[data-testid=response-subtitle]'))).toContain('EWS 9 (high)');
      expect(submitButton().disabled).toBe(false);

      await submit();
      expect(http.expectOne(URL).request.body.version).toBe(2);
    });

    it('does not take a reload of the board for the new version before a conflict (version as the sheet opened)', async () => {
      await open();
      live.set({ ...ROW, ewsScore: 9, version: 2 });
      await settle();
      expect(squash(el().querySelector('[data-testid=response-subtitle]'))).toContain('EWS 8 (high)');
      await type('Plan');
      await submit();
      expect(http.expectOne(URL).request.body.version).toBe(1);
    });

    it('escalation-state (already closed): the board shows it closed, so Close escalation goes off', async () => {
      await open();
      await conflict('escalation-state', 'This escalation is already closed.');
      expect(squash(el().querySelector('[data-testid=response-failure]'))).toBe('This escalation is already closed.');
      live.set({ ...ROW, status: 'DONE', version: 3, canRespond: false, canAcknowledge: false });
      await settle();
      expect(submitButton().disabled).toBe(true);
    });
  });

  it('on 404 locks the sheet and asks for a reload', async () => {
    await open();
    await type('Plan');
    await submit();
    http
      .expectOne(URL)
      .flush(problem('not-found', 404, 'This patient is not admitted any more.'), { status: 404, statusText: 'Not Found' });
    await settle();
    expect(squash(el().querySelector('[data-testid=response-failure]'))).toBe('This patient is not admitted any more.');
    expect(submitButton().disabled).toBe(true);
    expect(onConflict).toHaveBeenCalledTimes(1);
  });
});
