import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { Observable, of } from 'rxjs';
import { problemDetailInterceptor } from '../../../../core/http/problem-detail.interceptor';
import { Role } from '../../../../core/platform/platform.model';
import { ShellStore } from '../../../../core/shell/shell.store';
import { listOf } from '../../../ipd/admitted/admitted.fixtures';
import { ownerView, standardSetup, staffView } from '../../../ipd/card/card-setup.fixtures';
import {
  CardDraft,
  CardSetup,
  CardSetupView,
  CardVersion,
} from '../../../ipd/card/card-setup.model';
import { AdmissionList } from '../../../ipd/ipd.model';
import { IpdState } from '../../modules/modules.model';
import { IpCardPage } from './ipcard.page';

const CARD = '/api/v1/ipd/card-setup';
const PROBLEM = 'https://sahaj.in/problems/';

const problem = (slug: string, status: number, detail: string) => ({
  type: PROBLEM + slug,
  title: slug,
  status,
  detail,
});
const AT = '2026-11-09T05:35:00Z'; // 9 Nov 2026, 11:05 AM IST

function draftOf(
  setup: CardSetup,
  revision = 1,
  parts: CardDraft['changedParts'] = ['layout'],
): CardDraft {
  return {
    revision,
    baseVersion: 1,
    setup,
    savedAt: AT,
    savedByName: 'Dr. Owner',
    changedParts: parts,
  };
}

function published(
  setup: CardSetup,
  version: number,
  parts: CardVersion['changedParts'],
): CardSetupView {
  const v1: CardVersion = {
    version: 1,
    summary: 'Sahaj standard patient card (default)',
    isStandard: true,
    changedParts: [],
    publishedAt: null,
    publishedByName: null,
    live: false,
  };
  const mine: CardVersion = {
    version,
    summary: 'Changed: ' + parts.join(', '),
    isStandard: false,
    changedParts: parts,
    publishedAt: AT,
    publishedByName: 'Dr. Owner',
    live: true,
  };
  return ownerView({
    live: { version, isStandard: false, setup, publishedAt: AT, publishedByName: 'Dr. Owner' },
    draft: null,
    versions: [mine, v1],
  });
}

describe('IpCardPage', () => {
  let fixture: ComponentFixture<IpCardPage>;
  let http: HttpTestingController;
  let snack: ReturnType<typeof vi.fn>;
  let dialogOpen: ReturnType<typeof vi.fn>;
  let leaveAnswer: boolean;

  interface Options {
    state?: IpdState;
    view?: CardSetupView | 'fail' | 'forbidden';
    patients?: AdmissionList | 'fail' | 'forbidden' | 'skip';
  }

  const q = <T extends HTMLElement>(sel: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(sel)!;
  const all = <T extends HTMLElement>(sel: string) =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<T>(sel));
  const text = (sel: string) => q(sel).textContent!.replace(/\s+/g, ' ').trim();

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function open(role: Role = 'OWNER', options: Options = {}): Promise<void> {
    const { state = 'on', view = ownerView(), patients = listOf() } = options;
    snack = vi.fn();
    leaveAnswer = true;
    dialogOpen = vi.fn(() => ({ afterClosed: (): Observable<boolean> => of(leaveAnswer) }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ role }) } },
        { provide: MatSnackBar, useValue: { open: snack, dismiss: vi.fn() } },
        { provide: MatDialog, useValue: { open: dialogOpen } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IpCardPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/platform/modules').flush({
      plan: 'PRO',
      ipd: { state, requested: false },
      hra: { on: false },
      ipdTrialAvailable: false,
      menuCounts: { simple: 7, advanced: 14 },
    });
    await settle();
    if (state === 'off') {
      return;
    }
    const call = http.expectOne(CARD);
    if (view === 'fail') {
      call.flush(problem('x', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
    } else if (view === 'forbidden') {
      call.flush(problem('forbidden', 403, 'Not allowed.'), {
        status: 403,
        statusText: 'Forbidden',
      });
    } else {
      call.flush(view);
    }
    await settle();
    if (role === 'OWNER' && view !== 'fail' && view !== 'forbidden' && patients !== 'skip') {
      answerPatients(patients);
      await settle();
    }
  }

  function patientsRequest(): TestRequest {
    return http.expectOne((r) => r.url === '/api/v1/ipd/admissions');
  }

  function answerPatients(patients: AdmissionList | 'fail' | 'forbidden' | 'skip'): void {
    const req = patientsRequest();
    expect(req.request.params.get('cardFacts')).toBe('all');
    if (patients === 'fail') {
      req.flush(problem('x', 500, 'Try later.'), { status: 500, statusText: 'Server Error' });
    } else if (patients === 'forbidden') {
      req.flush(problem('forbidden', 403, 'You do not have access to this.'), {
        status: 403,
        statusText: 'Forbidden',
      });
    } else if (patients !== 'skip') {
      req.flush(patients);
    }
  }

  async function click(sel: string): Promise<void> {
    q<HTMLElement>(sel).click();
    await settle();
  }

  async function input(sel: string, value: string, event = 'input'): Promise<void> {
    const el = q<HTMLInputElement>(sel);
    el.value = value;
    el.dispatchEvent(new Event(event, { bubbles: true }));
    await settle();
  }

  afterEach(() => http.verify());

  // ---------------------------------------------------------------- story 1

  describe('status bar, versions and the first look (AC1, AC2, AC9)', () => {
    it('shows the entry only with IPD: IPD off shows the gate, not the card setup', async () => {
      await open('OWNER', { state: 'off' });
      expect(fixture.nativeElement.textContent).toContain(
        'In-patient care (IPD) is not on for your clinic',
      );
      expect(q('[data-testid=pc-bar]')).toBeNull();
    });

    it('for a clinic that never published: teal pill, no unpublished changes, Reset disabled, Publish disabled', async () => {
      await open();
      const pill = q('[data-testid=pc-live]');
      expect(pill.textContent!.trim()).toBe('Sahaj standard card · v1 live');
      expect(pill.classList.contains('p-teal')).toBe(true);
      expect(text('[data-testid=pc-nochange]')).toBe('No unpublished changes');
      expect(q('[data-testid=pc-draft]')).toBeNull();
      const reset = q<HTMLButtonElement>('[data-act=reset]');
      expect(reset.disabled).toBe(true);
      expect(reset.getAttribute('title')).toBe('Already the default');
      expect(q('[data-act=save]')).toBeNull();
      expect(q('[data-act=discard]')).toBeNull();
      expect(q<HTMLButtonElement>('[data-act=publish]').disabled).toBe(true);
    });

    it('lists v1 as "Sahaj standard patient card (default) · Sahaj · When the clinic was created" with the pill Live', async () => {
      await open();
      const row = q('[data-testid=pc-versions] [data-version="1"]');
      expect(row.textContent).toContain('v1');
      expect(row.textContent).toContain('Sahaj standard patient card (default)');
      expect(row.textContent).toContain('Sahaj · When the clinic was created');
      expect(row.querySelector('.p-good')!.textContent!.trim()).toBe('Live');
      expect(q('[data-testid=pc-versions]').textContent).not.toMatch(/restore/i);
    });

    it('shows each later version newest first with person, date and IST time, and only the newest is Live', async () => {
      await open('OWNER', { view: published(standardSetup(), 2, ['layout', 'fields']) });
      const rows = all('[data-testid=pc-versions] .li');
      expect(rows.map((r) => r.getAttribute('data-version'))).toEqual(['2', '1']);
      expect(rows[0].textContent).toContain('Changed: layout, fields');
      expect(rows[0].textContent).toContain('Dr. Owner · 9 Nov 2026, 11:05 AM IST');
      expect(rows[0].querySelector('.p-good')).not.toBeNull();
      expect(rows[1].querySelector('.p-good')).toBeNull();
      expect(q('[data-testid=pc-live]').textContent!.trim()).toBe('Customized · v2 live');
      expect(q('[data-testid=pc-live]').classList.contains('p-blue')).toBe(true);
    });

    it('names the layout, who sets it and the version in the foot line', async () => {
      await open();
      expect(text('[data-testid=pc-foot]')).toBe('Standard card · Sahaj standard card · v1');
    });
  });

  describe('editing is a draft (AC3, AC8)', () => {
    it('counts the changed parts in an amber pill and enables Publish, Save draft and Discard', async () => {
      await open();
      await click('[data-layout=compact]');
      const pill = q('[data-testid=pc-draft]');
      expect(pill.textContent!.trim()).toBe('Draft · 1 change not published');
      expect(pill.classList.contains('p-warn')).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=publish]').disabled).toBe(false);
      expect(q('[data-act=save]')).not.toBeNull();
      expect(q('[data-act=discard]')).not.toBeNull();
      expect(q<HTMLButtonElement>('[data-act=reset]').disabled).toBe(false);
    });

    it('says "2 changes" for two parts', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-switch=billing]');
      expect(text('[data-testid=pc-draft]')).toBe('Draft · 2 changes not published');
    });

    it('drops the pill and disables Publish when a switch is changed and changed back', async () => {
      await open();
      await click('[data-switch=billing]');
      expect(q('[data-testid=pc-draft]')).not.toBeNull();
      await click('[data-switch=billing]');
      expect(q('[data-testid=pc-draft]')).toBeNull();
      expect(q<HTMLButtonElement>('[data-act=publish]').disabled).toBe(true);
      expect(q('[data-act=save]')).toBeNull();
    });

    it('sends nothing to the server while editing', async () => {
      await open();
      await click('[data-layout=expanded]');
      await click('[data-cols="4"]');
      http.expectNone(CARD + '/draft');
    });
  });

  describe('Save draft (AC3, AC4, AC13)', () => {
    it('stores the whole setup with both tokens, then shows "saved 11:05 AM" and the toast', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=save]');
      const put = http.expectOne(CARD + '/draft');
      expect(put.request.method).toBe('PUT');
      expect(put.request.body.baseVersion).toBe(1);
      expect(put.request.body.draftRevision).toBeNull();
      expect(put.request.body.setup.layout).toBe('compact');
      expect(q<HTMLButtonElement>('[data-act=save]').disabled).toBe(true);
      expect(q('[data-act=save]').textContent).toContain('Saving');
      const saved = { ...standardSetup(), layout: 'compact' as const };
      put.flush(ownerView({ draft: draftOf(saved) }));
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Draft saved. Staff still see the live card until you publish.',
        'Close',
        expect.anything(),
      );
      expect(text('[data-testid=pc-draft]')).toBe(
        'Draft · 1 change not published · saved 11:05 AM',
      );
      expect(text('[data-testid=pc-showing]')).toBe('Showing your draft');
    });

    it('sends the revision it received on the next save, and drops "saved" after a further edit', async () => {
      const saved = { ...standardSetup(), layout: 'compact' as const };
      await open('OWNER', { view: ownerView({ draft: draftOf(saved, 4) }) });
      expect(text('[data-testid=pc-draft]')).toContain('saved 11:05 AM');
      await click('[data-cols="4"]');
      expect(text('[data-testid=pc-draft]')).not.toContain('saved');
      await click('[data-act=save]');
      const put = http.expectOne(CARD + '/draft');
      expect(put.request.body.draftRevision).toBe(4);
      put.flush(ownerView({ draft: draftOf({ ...saved, cardsPerRow: 4 }, 5, ['layout']) }));
      await settle();
    });

    it('sends a large opaque server revision back unchanged on Save draft and on Discard', async () => {
      const big = 1790000000000001;
      const saved = { ...standardSetup(), layout: 'compact' as const };
      const answer = JSON.parse(JSON.stringify(ownerView({ draft: draftOf(saved, big) })));
      await open('OWNER', { view: answer });
      await click('[data-cols="4"]');
      await click('[data-act=save]');
      const put = http.expectOne(CARD + '/draft');
      expect(put.request.body.draftRevision).toBe(big);
      expect(JSON.stringify(put.request.body)).toContain('"draftRevision":1790000000000001');
      put.flush(
        JSON.parse(JSON.stringify(ownerView({ draft: draftOf({ ...saved, cardsPerRow: 4 }, big + 2) }))),
      );
      await settle();
      await click('[data-act=discard]');
      const del = http.expectOne(CARD + '/draft?draftRevision=1790000000000003');
      expect(del.request.method).toBe('DELETE');
      del.flush(ownerView());
      await settle();
    });

    it('keeps the edits on screen and does not claim a save when it fails (AC13)', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=save]');
      http
        .expectOne(CARD + '/draft')
        .flush(problem('x', 500, 'Boom.'), { status: 500, statusText: 'Server Error' });
      await settle();
      expect(text('[data-testid=pc-problem]')).toBe('We could not save the draft. Try again.');
      expect(q('[data-layout=compact]').getAttribute('aria-pressed')).toBe('true');
      expect(snack).not.toHaveBeenCalled();
      expect(text('[data-testid=pc-draft]')).not.toContain('saved');
      expect(q<HTMLButtonElement>('[data-act=save]').disabled).toBe(false);
    });

    it('shows the server message for a refused setup (400)', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=save]');
      http
        .expectOne(CARD + '/draft')
        .flush(
          { ...problem('validation', 400, 'Keep the label to 40 characters.') },
          { status: 400, statusText: 'Bad Request' },
        );
      await settle();
      expect(text('[data-testid=pc-problem]')).toBe('Keep the label to 40 characters.');
    });

    it('cannot be pressed twice while it runs', async () => {
      await open();
      await click('[data-layout=compact]');
      q<HTMLButtonElement>('[data-act=save]').click();
      q<HTMLButtonElement>('[data-act=save]').click();
      await settle();
      const calls = http.match(CARD + '/draft');
      expect(calls).toHaveLength(1);
      calls[0].flush(ownerView());
      await settle();
    });
  });

  describe('Discard draft (AC5)', () => {
    it('removes the saved draft on the server and shows the live setup again', async () => {
      const saved = { ...standardSetup(), layout: 'compact' as const };
      await open('OWNER', { view: ownerView({ draft: draftOf(saved, 2) }) });
      expect(q('[data-layout=compact]').getAttribute('aria-pressed')).toBe('true');
      await click('[data-act=discard]');
      const del = http.expectOne(CARD + '/draft?draftRevision=2');
      expect(del.request.method).toBe('DELETE');
      del.flush(ownerView());
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Draft discarded. The live card is unchanged.',
        'Close',
        expect.anything(),
      );
      expect(q('[data-layout=standard]').getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-testid=pc-draft]')).toBeNull();
      expect(text('[data-testid=pc-showing]')).toBe('Showing the live card');
    });

    describe('a saved draft stays reachable (code review 37 #3)', () => {
      const compact = { ...standardSetup(), layout: 'compact' as const };

      it('still offers Save draft and Discard draft when the editor was edited back to equal the live setup', async () => {
        await open('OWNER', { view: ownerView({ draft: draftOf(compact, 3) }) });
        await click('[data-layout=standard]');
        expect(q('[data-testid=pc-draft]')).toBeNull();
        expect(text('[data-testid=pc-nochange]')).toBe('No unpublished changes');
        expect(q('[data-act=discard]')).not.toBeNull();
        expect(q<HTMLButtonElement>('[data-act=save]').disabled).toBe(false);
        expect(q<HTMLButtonElement>('[data-act=publish]').disabled).toBe(true);
      });

      it('sends the live setup with the draft revision on Save draft, and the server then removes the draft', async () => {
        await open('OWNER', { view: ownerView({ draft: draftOf(compact, 3) }) });
        await click('[data-layout=standard]');
        await click('[data-act=save]');
        const put = http.expectOne(CARD + '/draft');
        expect(put.request.body.draftRevision).toBe(3);
        expect(put.request.body.setup.layout).toBe('standard');
        put.flush(ownerView());
        await settle();
        expect(q('[data-act=save]')).toBeNull();
        expect(q('[data-act=discard]')).toBeNull();
        expect(q('[data-testid=pc-draft]')).toBeNull();
      });

      it('removes the stored draft on Discard draft from that state', async () => {
        await open('OWNER', { view: ownerView({ draft: draftOf(compact, 3) }) });
        await click('[data-layout=standard]');
        await click('[data-act=discard]');
        const del = http.expectOne(CARD + '/draft?draftRevision=3');
        expect(del.request.method).toBe('DELETE');
        del.flush(ownerView());
        await settle();
        expect(q('[data-act=discard]')).toBeNull();
      });

      it('disables Save draft, and sends nothing, while the editor equals the saved draft', async () => {
        await open('OWNER', { view: ownerView({ draft: draftOf(compact, 3) }) });
        expect(q('[data-act=discard]')).not.toBeNull();
        expect(q<HTMLButtonElement>('[data-act=save]').disabled).toBe(true);
        q<HTMLButtonElement>('[data-act=save]').click();
        await settle();
        http.expectNone(CARD + '/draft');
      });
    });

    it('drops unsaved edits without a call when no draft was saved', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=discard]');
      http.expectNone(CARD + '/draft');
      expect(q('[data-layout=standard]').getAttribute('aria-pressed')).toBe('true');
      expect(snack).toHaveBeenCalledWith(
        'Draft discarded. The live card is unchanged.',
        'Close',
        expect.anything(),
      );
    });
  });

  describe('Reset to default (AC6)', () => {
    it('makes the draft the Sahaj standard setup and leaves the live setup alone', async () => {
      const custom = { ...standardSetup(), layout: 'expanded' as const, cardsPerRow: 4 as const };
      await open('OWNER', { view: published(custom, 2, ['layout']) });
      expect(q<HTMLButtonElement>('[data-act=reset]').disabled).toBe(false);
      await click('[data-act=reset]');
      http.expectNone(CARD + '/draft');
      expect(snack).toHaveBeenCalledWith(
        'Draft reset to the Sahaj standard card. Publish to apply it.',
        'Close',
        expect.anything(),
      );
      expect(q('[data-layout=standard]').getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-cols="3"]').getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-testid=pc-live]').textContent).toContain('v2 live');
      expect(q<HTMLButtonElement>('[data-act=reset]').disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-act=publish]').disabled).toBe(false);
    });
  });

  describe('Publish (AC7, AC13)', () => {
    it('sends the editor setup with the tokens, then shows the new version, the toast and the versions list', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-switch=billing]');
      await click('[data-act=publish]');
      const post = http.expectOne(CARD + '/publish');
      expect(post.request.method).toBe('POST');
      expect(post.request.body.baseVersion).toBe(1);
      expect(post.request.body.draftRevision).toBeNull();
      expect(post.request.body.setup.layout).toBe('compact');
      expect(q('[data-act=publish]').textContent).toContain('Publishing');
      const next = { ...standardSetup(), layout: 'compact' as const };
      next.sections.find((s) => s.key === 'billing')!.on = true;
      post.flush(published(next, 2, ['layout', 'sections']));
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Patient card v2 is live for every role. Layout, sections updated.',
        'Close',
        expect.anything(),
      );
      expect(q('[data-testid=pc-live]').textContent!.trim()).toBe('Customized · v2 live');
      expect(q('[data-testid=pc-draft]')).toBeNull();
      expect(
        all('[data-testid=pc-versions] .li').map((r) => r.getAttribute('data-version')),
      ).toEqual(['2', '1']);
      expect(q<HTMLButtonElement>('[data-act=publish]').disabled).toBe(true);
    });

    it('makes one version for a double tap', async () => {
      await open();
      await click('[data-layout=compact]');
      q<HTMLButtonElement>('[data-act=publish]').click();
      q<HTMLButtonElement>('[data-act=publish]').click();
      await settle();
      const calls = http.match(CARD + '/publish');
      expect(calls).toHaveLength(1);
      calls[0].flush(published({ ...standardSetup(), layout: 'compact' }, 2, ['layout']));
      await settle();
    });

    it('keeps the edits and says so when publishing fails', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=publish]');
      http
        .expectOne(CARD + '/publish')
        .flush(problem('x', 500, 'Boom.'), { status: 500, statusText: 'Server Error' });
      await settle();
      expect(text('[data-testid=pc-problem]')).toBe('We could not publish the card. Try again.');
      expect(q('[data-layout=compact]').getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-testid=pc-live]').textContent).toContain('v1 live');
    });
  });

  describe('two owners (AC12)', () => {
    it('shows the server message, keeps the edits and reloads on request', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=publish]');
      http
        .expectOne(CARD + '/publish')
        .flush(
          problem(
            'card-setup-changed',
            409,
            'The card setup was changed by someone else. Reload to see it.',
          ),
          { status: 409, statusText: 'Conflict' },
        );
      await settle();
      expect(text('[data-testid=pc-problem]')).toContain(
        'The card setup was changed by someone else. Reload to see it.',
      );
      expect(q('[data-layout=compact]').getAttribute('aria-pressed')).toBe('true');
      await click('[data-act=reload]');
      http.expectOne(CARD).flush(published({ ...standardSetup(), cardsPerRow: 2 }, 2, ['layout']));
      await settle();
      answerPatients(listOf());
      await settle();
      expect(q('[data-testid=pc-problem]')).toBeNull();
      expect(q('[data-layout=compact]').getAttribute('aria-pressed')).toBe('false');
      expect(q('[data-cols="2"]').getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-testid=pc-live]').textContent).toContain('v2 live');
    });

    it('also treats a stale draft revision as a conflict on Save draft and Discard', async () => {
      const saved = { ...standardSetup(), layout: 'compact' as const };
      await open('OWNER', { view: ownerView({ draft: draftOf(saved, 2) }) });
      await click('[data-act=discard]');
      http
        .expectOne(CARD + '/draft?draftRevision=2')
        .flush(
          problem(
            'card-setup-changed',
            409,
            'The card setup was changed by someone else. Reload to see it.',
          ),
          { status: 409, statusText: 'Conflict' },
        );
      await settle();
      expect(q('[data-act=reload]')).not.toBeNull();
      expect(snack).not.toHaveBeenCalled();
    });
  });

  describe('IPD Read-only (AC14)', () => {
    it('lets the owner read but offers no Save, Discard, Reset or Publish and disables every control', async () => {
      await open('OWNER', {
        state: 'readonly',
        view: ownerView({ canEdit: false, readOnlyReason: 'IPD_READONLY', state: 'readonly' }),
      });
      expect(text('[data-testid=pc-readonly]')).toBe('IPD is read-only. Nothing can be changed.');
      for (const act of ['reset', 'save', 'discard', 'publish']) {
        expect(q(`[data-act=${act}]`)).toBeNull();
      }
      expect(q<HTMLButtonElement>('[data-layout=compact]').disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-switch=billing]').disabled).toBe(true);
    });

    it('turns the page read-only when a write answers 409 ipd-readonly', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=publish]');
      http
        .expectOne(CARD + '/publish')
        .flush(problem('ipd-readonly', 409, 'IPD is read-only. Nothing can be changed.'), {
          status: 409,
          statusText: 'Conflict',
        });
      await settle();
      expect(text('[data-testid=pc-problem]')).toBe('IPD is read-only. Nothing can be changed.');
      expect(q('[data-act=publish]')).toBeNull();
      expect(q<HTMLButtonElement>('[data-layout=expanded]').disabled).toBe(true);
    });
  });

  describe('not the owner (AC10, AC25)', () => {
    it.each<Role>(['NURSE', 'DOCTOR', 'FRONT_DESK'])(
      '%s sees the live setup with every control disabled, no draft and no versions',
      async (role) => {
        await open(role, { view: staffView() });
        http.expectNone((r) => r.url === '/api/v1/ipd/admissions');
        expect(q('[data-testid=pc-draft]')).toBeNull();
        expect(q('[data-testid=pc-versions]')).toBeNull();
        expect(text('[data-testid=pc-readonly]')).toBe(
          'Only the owner can change the patient card. You can view it here.',
        );
        expect(text('[data-testid=pc-foot]')).toBe(
          'Standard card · Sahaj standard card · v1 · set by the clinic owner',
        );
        expect(q<HTMLButtonElement>('[data-act=publish]').disabled).toBe(true);
        expect(q<HTMLButtonElement>('[data-act=reset]').disabled).toBe(true);
        expect(q('[data-act=save]')).toBeNull();
        expect(all<HTMLButtonElement>('[data-template]').every((b) => b.disabled)).toBe(true);
        expect(all<HTMLButtonElement>('.pclay').every((b) => b.disabled)).toBe(true);
        expect(all<HTMLButtonElement>('[data-cols]').every((b) => b.disabled)).toBe(true);
        expect(all<HTMLButtonElement>('.sw').every((b) => b.disabled)).toBe(true);
        expect(all<HTMLButtonElement>('[data-move]').every((b) => b.disabled)).toBe(true);
      },
    );

    it('draws the preview with the example patient only and loads no patient list', async () => {
      await open('NURSE', { view: staffView() });
      expect(all('[data-testid=pc-patient] option').map((o) => o.textContent!.trim())).toEqual([
        'Example patient · 202',
      ]);
      expect(q('[data-testid=pc-frame] [data-testid=card-name]').textContent!.trim()).toBe(
        'Example patient',
      );
    });

    it('cannot edit even if a click reaches the store', async () => {
      await open('NURSE', { view: staffView() });
      q<HTMLButtonElement>('[data-layout=compact]').click();
      await settle();
      expect(q('[data-layout=standard]').getAttribute('aria-pressed')).toBe('true');
      http.expectNone(CARD + '/draft');
    });
  });

  describe('loading and refusals (AC13, SAH-40)', () => {
    it('shows the load error with Try again, and Try again reads it again', async () => {
      await open('OWNER', { view: 'fail' });
      expect(text('.notice.err')).toContain('We could not load the card setup. Try again.');
      await click('[data-act=try-again]');
      http.expectOne(CARD).flush(ownerView());
      await settle();
      answerPatients(listOf());
      await settle();
      expect(q('[data-testid=pc-bar]')).not.toBeNull();
    });

    it('clears everything on a 403 and shows the refusal', async () => {
      await open('OWNER', { view: 'forbidden' });
      expect(q('[data-testid=pc-bar]')).toBeNull();
      expect(text('.notice.err')).toContain('Not allowed.');
      expect(q('[data-testid=pc-preview]')).toBeNull();
    });

    it('clears the data and the patients when the preview list is refused with 403', async () => {
      await open('OWNER', { patients: 'forbidden' });
      expect(q('[data-testid=pc-bar]')).toBeNull();
      expect(fixture.nativeElement.textContent).not.toContain('Asha Patil');
      expect(text('.notice.err')).toContain('You do not have access to this.');
    });

    it('clears everything when a write answers 403 (a demoted owner)', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=publish]');
      http
        .expectOne(CARD + '/publish')
        .flush(problem('forbidden', 403, 'You do not have access to this.'), {
          status: 403,
          statusText: 'Forbidden',
        });
      await settle();
      expect(q('[data-testid=pc-bar]')).toBeNull();
      expect(text('.notice.err')).toContain('You do not have access to this.');
    });
  });

  // ---------------------------------------------------------------- story 2

  describe('Layout and sections (AC17 to AC25)', () => {
    it('shows the templates, the layouts with aria-pressed, Cards per row and the sections in order', async () => {
      await open();
      expect(all('[data-template] b').map((b) => b.textContent)).toEqual([
        'Small clinic',
        'Nursing home',
        'Hospital',
      ]);
      expect(all('[data-template] small').map((b) => b.textContent)).toEqual([
        'Compact card, no billing',
        'Standard card with deposit action',
        'Expanded card, billing section, all actions',
      ]);
      expect(all('.pclay').map((b) => b.getAttribute('aria-pressed'))).toEqual([
        'false',
        'true',
        'false',
      ]);
      expect(all('.pclay b').map((b) => b.textContent)).toEqual([
        'Compact',
        'Standard',
        'Expanded',
      ]);
      expect(text('[data-testid=pc-cols]')).toBe('2 3 4');
      expect(q('[data-cols="3"]').getAttribute('aria-pressed')).toBe('true');
      expect(all('[data-testid=pc-sections] .pcrm b').map((b) => b.textContent)).toEqual([
        'Card header',
        'Workflow status',
        'Admission and bed',
        'Care team',
        'Vitals',
        'Clinical',
        'Billing',
      ]);
      expect(text('[data-testid=pc-sections]')).toContain('4 of 6 fields on');
    });

    it('applies a template to the draft only, keeps Cards per row and tells the owner', async () => {
      await open();
      await click('[data-cols="4"]');
      await click('[data-template=smallClinic]');
      expect(snack).toHaveBeenCalledWith(
        'Small clinic template applied to your draft. Publish to use it.',
        'Close',
        expect.anything(),
      );
      expect(q('[data-layout=compact]').getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-cols="4"]').getAttribute('aria-pressed')).toBe('true');
      http.expectNone(CARD + '/draft');
      await click('[data-template=hospital]');
      expect(q('[data-layout=expanded]').getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-switch=billing]').getAttribute('aria-checked')).toBe('true');
    });

    it('shows Always on for the Card header and Safety · always on instead of a switch for three sections (AC22)', async () => {
      await open();
      const rows = all('[data-testid=pc-sections] .pcrow');
      expect(rows[0].textContent).toContain('Always on');
      expect(rows[0].querySelector('.sw')).toBeNull();
      expect(rows[0].querySelector('[data-move]')).toBeNull();
      for (const key of ['status', 'vitals', 'clinical']) {
        const row = q(`[data-row=${key}]`);
        expect(row.textContent).toContain('Safety · always on');
        expect(row.querySelector('.sw')).toBeNull();
        expect(row.querySelector('.p-good')!.getAttribute('title')).toBe(
          'Patient safety: cannot be hidden',
        );
      }
      expect(q('[data-row=stay] .sw').getAttribute('aria-label')).toBe('Show Admission and bed');
    });

    it('switches a section off and the row dims; the count stays (AC23)', async () => {
      await open();
      await click('[data-switch=stay]');
      expect(q('[data-row=stay]').classList.contains('off')).toBe(true);
      expect(q('[data-row=stay]').textContent).toContain('4 of 5 fields on');
      expect(q('[data-switch=stay]').getAttribute('aria-checked')).toBe('false');
    });

    it('moves a section with Move up / Move down, announces it politely and keeps the focus on the moved row', async () => {
      await open();
      const up = q<HTMLButtonElement>('[data-row=care] [data-move=up]');
      expect(up.getAttribute('aria-label')).toBe('Move up Care team');
      up.click();
      await settle();
      expect(all('[data-testid=pc-sections] .pcrm b').map((b) => b.textContent)).toEqual([
        'Card header',
        'Workflow status',
        'Care team',
        'Admission and bed',
        'Vitals',
        'Clinical',
        'Billing',
      ]);
      expect(text('[data-testid=pc-announce]')).toBe('Care team moved to position 3');
      expect((document.activeElement as HTMLElement | null)?.getAttribute('aria-label')).toBe(
        'Move up Care team',
      );
    });

    it('disables Move up at the top of the movable sections and Move down at the bottom', async () => {
      await open();
      expect(q<HTMLButtonElement>('[data-row=status] [data-move=up]').disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-row=billing] [data-move=down]').disabled).toBe(true);
    });

    it('moves a section by drag and drop (mouse) and never above the Card header', async () => {
      await open();
      const handle = q('[data-row=billing] .pcgrip');
      expect(handle.getAttribute('draggable')).toBe('true');
      const dragStart = new Event('dragstart', { bubbles: true });
      Object.defineProperty(dragStart, 'dataTransfer', {
        value: { setData: vi.fn(), setDragImage: vi.fn() },
      });
      handle.dispatchEvent(dragStart);
      const over = new Event('dragover', { bubbles: true, cancelable: true });
      q('[data-row=stay]').dispatchEvent(over);
      expect(over.defaultPrevented).toBe(true);
      q('[data-row=stay]').dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }));
      await settle();
      expect(all('[data-testid=pc-sections] .pcrm b').map((b) => b.textContent)).toEqual([
        'Card header',
        'Workflow status',
        'Billing',
        'Admission and bed',
        'Care team',
        'Vitals',
        'Clinical',
      ]);
      // Dropping on the Card header does nothing.
      handle.dispatchEvent(dragStart);
      const header = new Event('dragover', { bubbles: true, cancelable: true });
      q('[data-row=header]').dispatchEvent(header);
      expect(header.defaultPrevented).toBe(false);
    });
  });

  // ---------------------------------------------------------------- story 3

  describe('Fields (AC27 to AC36)', () => {
    async function openFields(role: Role = 'OWNER', options: Options = {}): Promise<void> {
      await open(role, options);
      await click('#pc-tab-fields');
    }

    it('lists each section as a group with its fields, labels, standard names and groups', async () => {
      await openFields();
      expect(text('[data-testid=pc-fields]')).toContain(
        'Drag a field to reorder it or move it to another section.',
      );
      const groups = all('.pcfsec').map((g) => g.getAttribute('data-section'));
      expect(groups).toEqual(['header', 'status', 'stay', 'care', 'vitals', 'clinical', 'billing']);
      expect(q('[data-section=billing]').textContent).toContain('Section off');
      expect(q('[data-section=stay]').textContent).not.toContain('Section off');
      const doctor = q('[data-row=doctor]');
      expect(doctor.querySelector('input')!.getAttribute('placeholder')).toBe('Doctor');
      expect(doctor.querySelector('input')!.getAttribute('aria-label')).toBe('Label for Doctor');
      expect(doctor.querySelector('small')!.textContent).toBe('Doctor · Patient and admission');
      expect(q('[data-row=bp] small').textContent).toBe('BP · Vitals and early warning');
      expect(q('[data-row=mobile] small').textContent).toBe('Mobile · Contact details');
      expect(q('[data-row=diagnosis] small').textContent).toBe('Diagnosis · Clinical details');
      expect(q('[data-row=payer] small').textContent).toBe('Payer · Billing');
    });

    it('lists exactly the 25 fields with data, and not Workflow stage or Assigned nurse', async () => {
      await openFields();
      expect(all('[data-section] .pcrow')).toHaveLength(25);
      expect(fixture.nativeElement.textContent).not.toMatch(
        /Workflow stage|Assigned nurse|Case paper|Update stage/,
      );
    });

    it('shows Required for the patient name and Safety · always on for the four safety fields, with no switch', async () => {
      await openFields();
      const name = q('[data-row=name]');
      expect(name.textContent).toContain('Required');
      expect(name.querySelector('.sw')).toBeNull();
      expect(name.querySelector('[data-move]')).toBeNull();
      expect(name.querySelector<HTMLInputElement>('input')!.disabled).toBe(true);
      for (const key of ['uhid', 'alerts', 'ews', 'allergies']) {
        const row = q(`[data-row=${key}]`);
        expect(row.textContent).toContain('Safety · always on');
        expect(row.querySelector('.sw')).toBeNull();
        const box = row.querySelector<HTMLInputElement>('input')!;
        expect(box.disabled).toBe(true);
      }
      expect(q<HTMLInputElement>('[data-row=allergies] input').value).toBe('Allergies');
    });

    it('switches a field and shows a label in the preview at once, as plain text (AC31)', async () => {
      await openFields();
      await input('[data-label=doctor]', '<b>Consultant</b>');
      expect(q<HTMLInputElement>('[data-label=doctor]').value).toBe('<b>Consultant</b>');
      const dts = all('[data-testid=pc-frame] dt').map((d) => d.textContent!.trim());
      expect(dts).toContain('<b>Consultant</b>');
      expect(q('[data-testid=pc-frame] dt b')).toBeNull();
      expect(text('[data-testid=pc-draft]')).toBe('Draft · 1 change not published');
      await click('[data-switch=admissionType]');
      expect(all('[data-testid=pc-frame] dt').map((d) => d.textContent!.trim())).toContain(
        'Admission type',
      );
    });

    it('stops the label box at 40 characters and trims it when the box is left', async () => {
      await openFields();
      expect(q('[data-label=doctor]').getAttribute('maxlength')).toBe('40');
      await input('[data-label=doctor]', '  Consultant  ', 'change');
      expect(q<HTMLInputElement>('[data-label=doctor]').value).toBe('Consultant');
      await click('[data-act=save]');
      expect(http.expectOne(CARD + '/draft').request.body.setup.sections[3].fields[0].label).toBe(
        'Consultant',
      );
    });

    it('keeps an empty label as the standard name', async () => {
      await openFields();
      await input('[data-label=doctor]', 'Consultant');
      await input('[data-label=doctor]', '');
      expect(q('[data-testid=pc-draft]')).toBeNull();
    });

    describe('messages under the label box (G-1, G-2, G-3; AC67 to AC69)', () => {
      const CONTROL = 'Label cannot contain control or text-direction characters';
      const LONG = 'Keep the label to 40 characters.';
      const USED = 'This name is already used by another field.';
      const BIDI = 'Doc\u{202E}tor';

      const leave = async (sel: string): Promise<void> => {
        q<HTMLInputElement>(sel).dispatchEvent(new Event('blur'));
        await settle();
      };
      const message = (key: string): string | null =>
        (fixture.nativeElement as HTMLElement).querySelector(`[data-error=${key}]`)?.textContent
          ?.trim() ?? null;
      const refused = (errors: { field: string; message: string }[]) => ({
        ...problem('validation', 400, errors[0].message),
        fieldErrors: errors,
      });
      const BAD = { status: 400, statusText: 'Bad Request' };
      const focused = () => (document.activeElement as HTMLElement | null)?.getAttribute('data-label');

      it('shows the message under the box after the owner leaves it, in the mockup error style', async () => {
        await openFields();
        await input('[data-label=doctor]', BIDI);
        expect(message('doctor')).toBeNull();
        await leave('[data-label=doctor]');
        expect(message('doctor')).toBe(CONTROL);
        const box = q<HTMLInputElement>('[data-label=doctor]');
        expect(box.getAttribute('aria-invalid')).toBe('true');
        expect(box.classList.contains('bad')).toBe(true);
        const note = q('[data-error=doctor]');
        expect(note.classList.contains('ferr')).toBe(true);
        expect(box.getAttribute('aria-describedby')).toBe(note.id);
        expect(q('[data-testid=pc-problem]')).toBeNull();
        expect(q('[data-label=ward]').getAttribute('aria-invalid')).toBeNull();
        expect(q('[data-label=ward]').getAttribute('aria-describedby')).toBeNull();
      });

      it('shows the duplicate message (V15) for another field’s standard name', async () => {
        await openFields();
        await input('[data-label=diagnosis]', ' ALLERGIES ');
        await leave('[data-label=diagnosis]');
        expect(message('diagnosis')).toBe(USED);
      });

      it('shows the duplicate message on the later field only, and moves it when the first is renamed', async () => {
        await openFields();
        await input('[data-label=ward]', 'Block');
        await leave('[data-label=ward]');
        await input('[data-label=room]', 'block');
        await leave('[data-label=room]');
        expect(message('ward')).toBeNull();
        expect(message('room')).toBe(USED);
        await input('[data-label=ward]', 'Wing');
        await leave('[data-label=ward]');
        expect(message('room')).toBeNull();
      });

      it('shows nothing before the box is left, and clears the message on the next keystroke', async () => {
        await openFields();
        await input('[data-label=doctor]', BIDI);
        await leave('[data-label=doctor]');
        expect(message('doctor')).toBe(CONTROL);
        await input('[data-label=doctor]', BIDI + 'x');
        expect(message('doctor')).toBeNull();
        expect(q('[data-label=doctor]').getAttribute('aria-invalid')).toBeNull();
        await leave('[data-label=doctor]');
        expect(message('doctor')).toBe(CONTROL);
      });

      it('shows no message for a good label', async () => {
        await openFields();
        await input('[data-label=doctor]', 'Consultant');
        await leave('[data-label=doctor]');
        expect(message('doctor')).toBeNull();
      });

      it('counts the field’s own standard name as no change (G-3)', async () => {
        await openFields();
        await input('[data-label=bed]', 'Bed');
        await leave('[data-label=bed]');
        expect(message('bed')).toBeNull();
        expect(q('[data-testid=pc-draft]')).toBeNull();
        expect(text('[data-testid=pc-nochange]')).toBe('No unpublished changes');
        expect(q<HTMLButtonElement>('[data-act=publish]').disabled).toBe(true);
        expect(q<HTMLButtonElement>('[data-act=reset]').disabled).toBe(true);
        expect(q('[data-act=save]')).toBeNull();
      });

      it('counts a case variant of the own name as a change, sent as typed', async () => {
        await openFields();
        await input('[data-label=bed]', 'bed');
        await leave('[data-label=bed]');
        expect(message('bed')).toBeNull();
        expect(text('[data-testid=pc-draft]')).toBe('Draft · 1 change not published');
        await click('[data-act=save]');
        const put = http.expectOne(CARD + '/draft');
        expect(put.request.body.setup.sections[0].fields[3]).toEqual({
          key: 'bed',
          on: true,
          label: 'bed',
        });
        put.flush(ownerView());
        await settle();
      });

      it('sends the own name as an empty label, so the browser and the server agree', async () => {
        await openFields();
        await input('[data-label=bed]', 'Bed');
        await input('[data-label=doctor]', 'Consultant');
        await click('[data-act=save]');
        const sent = http.expectOne(CARD + '/draft').request.body.setup;
        expect(sent.sections[0].fields[3].label).toBe('');
        expect(sent.sections[3].fields[0].label).toBe('Consultant');
      });

      it('keeps the leave guard quiet for the own name, and asks for a real edit', async () => {
        await openFields();
        await input('[data-label=bed]', 'Bed');
        expect(fixture.componentInstance.confirmLeave()).toBe(true);
        await input('[data-label=bed]', 'Cot');
        leaveAnswer = false;
        let answer: unknown;
        (fixture.componentInstance.confirmLeave() as Observable<boolean>).subscribe(
          (v) => (answer = v),
        );
        expect(answer).toBe(false);
        expect(dialogOpen).toHaveBeenCalledTimes(1);
      });

      it('sends nothing on Save draft with a label in error, shows every message and focuses the first box', async () => {
        await openFields();
        await input('[data-label=room]', 'Doctor');
        await input('[data-label=bed]', BIDI);
        await input('[data-label=diagnosis]', 'Allergies');
        await click('[data-act=save]');
        http.expectNone(CARD + '/draft');
        expect(message('bed')).toBe(CONTROL);
        expect(message('room')).toBe(USED);
        expect(message('diagnosis')).toBe(USED);
        expect(focused()).toBe('bed');
        expect(q('[data-testid=pc-problem]')).toBeNull();
        expect(q<HTMLButtonElement>('[data-act=save]').disabled).toBe(false);
      });

      it('sends nothing on Publish with a label in error, and opens the Fields tab first', async () => {
        await openFields();
        await input('[data-label=doctor]', 'Block');
        await input('[data-label=ward]', 'Block');
        await click('#pc-tab-layout');
        expect(q('[data-label=doctor]')).toBeNull();
        await click('[data-act=publish]');
        http.expectNone(CARD + '/publish');
        expect(q('[data-testid=pc-fields]')).not.toBeNull();
        expect(message('doctor')).toBe(USED);
        expect(focused()).toBe('doctor');
      });

      it('moves the focus to the first box in the order of the card, whatever the order of typing', async () => {
        await openFields();
        await input('[data-label=diagnosis]', 'Doctor');
        await input('[data-label=ward]', 'Doctor');
        await click('[data-act=publish]');
        http.expectNone(CARD + '/publish');
        expect(focused()).toBe('ward');
      });

      it('refuses a stored draft that already breaks the rule when the owner first submits', async () => {
        const saved = standardSetup();
        saved.sections[3].fields[0].label = 'Allergies';
        await open('OWNER', { view: ownerView({ draft: draftOf(saved, 3, ['fields']) }) });
        await click('#pc-tab-fields');
        expect(message('doctor')).toBeNull();
        await click('[data-act=publish]');
        http.expectNone(CARD + '/publish');
        expect(message('doctor')).toBe(USED);
        expect(focused()).toBe('doctor');
      });

      it('refuses a stored label over 40 characters with the length message', async () => {
        const saved = standardSetup();
        saved.sections[3].fields[0].label = 'a'.repeat(41);
        await open('OWNER', { view: ownerView({ draft: draftOf(saved, 3, ['fields']) }) });
        await click('#pc-tab-fields');
        await click('[data-act=publish]');
        http.expectNone(CARD + '/publish');
        expect(message('doctor')).toBe(LONG);
      });

      it('sends again once the labels are fixed', async () => {
        await openFields();
        await input('[data-label=doctor]', 'Allergies');
        await click('[data-act=save]');
        http.expectNone(CARD + '/draft');
        await input('[data-label=doctor]', 'Consultant');
        await click('[data-act=save]');
        http.expectOne(CARD + '/draft').flush(ownerView());
        await settle();
        expect(message('doctor')).toBeNull();
      });

      it('shows a server label error under its own box, with the focus, and clears it on a keystroke (G-1)', async () => {
        await openFields();
        // The browser would catch a duplicate; a stricter server rule is simulated with a clean label.
        await input('[data-label=doctor]', 'Consultant');
        await input('[data-label=ward]', 'Block');
        await click('[data-act=save]');
        http.expectOne(CARD + '/draft').flush(
          refused([
            { field: 'setup.sections[3].fields[0].label', message: 'Server says no to doctor.' },
            { field: 'setup.sections[2].fields[2].label', message: 'Server says no to ward.' },
          ]),
          BAD,
        );
        await settle();
        expect(message('doctor')).toBe('Server says no to doctor.');
        expect(message('ward')).toBe('Server says no to ward.');
        expect(q('[data-label=doctor]').getAttribute('aria-invalid')).toBe('true');
        expect(q('[data-testid=pc-problem]')).toBeNull();
        expect(focused()).toBe('ward');
        expect(q<HTMLButtonElement>('[data-act=save]').disabled).toBe(false);
        await input('[data-label=ward]', 'Blocks');
        expect(message('ward')).toBeNull();
        expect(message('doctor')).toBe('Server says no to doctor.');
      });

      it('lists other paths and unresolved label paths in the banner, all of them once', async () => {
        await openFields();
        await input('[data-label=doctor]', 'Consultant');
        await click('[data-act=save]');
        http.expectOne(CARD + '/draft').flush(
          refused([
            { field: 'setup.sections[3].fields[0].label', message: 'Label message.' },
            { field: 'setup.layout', message: 'Choose Compact, Standard or Expanded.' },
            { field: 'setup.sections[9].fields[0].label', message: 'Unresolved label message.' },
            { field: 'setup.cardsPerRow', message: 'Choose Compact, Standard or Expanded.' },
            { field: 'setup.sections[0].fields[0].label', message: 'Name message.' },
          ]),
          BAD,
        );
        await settle();
        expect(message('doctor')).toBe('Label message.');
        expect(message('name')).toBeNull();
        const banner = q('[data-testid=pc-problem]');
        expect(banner.getAttribute('role')).toBe('alert');
        expect(all('[data-testid=pc-problem] .pcprob > span').map((s) => s.textContent!.trim())).toEqual([
          'Choose Compact, Standard or Expanded.',
          'Unresolved label message.',
          'Name message.',
        ]);
      });

      it('keeps the single banner line when the 400 has no field errors', async () => {
        await openFields();
        await input('[data-label=doctor]', 'Consultant');
        await click('[data-act=save]');
        http
          .expectOne(CARD + '/draft')
          .flush(problem('validation', 400, 'The card setup is not valid.'), BAD);
        await settle();
        expect(text('[data-testid=pc-problem]')).toBe('The card setup is not valid.');
        expect(q('[data-error]')).toBeNull();
      });

      it('keeps the banner for a 409 and shows nothing under the boxes', async () => {
        await openFields();
        await input('[data-label=doctor]', 'Consultant');
        await click('[data-act=save]');
        http
          .expectOne(CARD + '/draft')
          .flush(problem('card-setup-changed', 409, 'Changed by someone else.'), {
            status: 409,
            statusText: 'Conflict',
          });
        await settle();
        expect(text('[data-testid=pc-problem]')).toContain('Changed by someone else.');
        expect(q('[data-error]')).toBeNull();
        expect(q('[data-act=reload]')).not.toBeNull();
      });

      it('shows a server error of a published change under the right box after Publish', async () => {
        await openFields();
        await input('[data-label=doctor]', 'Consultant');
        await click('[data-act=publish]');
        http.expectOne(CARD + '/publish').flush(
          refused([{ field: 'setup.sections[3].fields[0].label', message: 'Server says no.' }]),
          BAD,
        );
        await settle();
        expect(message('doctor')).toBe('Server says no.');
        expect(focused()).toBe('doctor');
        expect(q('[data-testid=pc-problem]')).toBeNull();
      });

      it('does not offer a message box for a fixed label (safety fields keep the standard name)', async () => {
        await openFields();
        await input('[data-label=doctor]', 'Consultant');
        await click('[data-act=save]');
        http.expectOne(CARD + '/draft').flush(
          refused([{ field: 'setup.sections[0].fields[1].label', message: 'UHID cannot change.' }]),
          BAD,
        );
        await settle();
        expect(q('[data-error]')).toBeNull();
        expect(text('[data-testid=pc-problem]')).toBe('UHID cannot change.');
      });
    });

    it('moves a field with the arrows across a section border and keeps the focus on it (AC27, AC36)', async () => {
      await openFields();
      const down = q<HTMLButtonElement>('[data-row=admissionType] [data-move=down]');
      expect(down.getAttribute('aria-label')).toBe('Move down Admission type');
      down.click();
      await settle();
      expect(all('[data-section=care] .pcrow').map((r) => r.getAttribute('data-row'))).toEqual([
        'admissionType',
        'doctor',
      ]);
      expect(text('[data-testid=pc-announce]')).toBe(
        'Admission type moved to position 1 in Care team',
      );
      expect((document.activeElement as HTMLElement | null)?.getAttribute('aria-label')).toBe(
        'Move down Admission type',
      );
    });

    it('refuses to move a safety field into a switched-off section with the toast of AC30', async () => {
      await openFields();
      // Allergies is last in Clinical; Billing is off.
      q<HTMLButtonElement>('[data-row=allergies] [data-move=down]').click();
      await settle();
      expect(snack).toHaveBeenCalledWith(
        'Allergies is a safety field. Move it to a section that is switched on.',
        'Close',
        expect.anything(),
      );
      expect(all('[data-section=clinical] .pcrow').map((r) => r.getAttribute('data-row'))).toEqual([
        'diagnosis',
        'allergies',
      ]);
    });

    it('then refuses to switch off a section that holds a safety field (AC23)', async () => {
      await openFields();
      await click('#pc-tab-layout');
      await click('[data-switch=billing]');
      await click('#pc-tab-fields');
      q<HTMLButtonElement>('[data-row=allergies] [data-move=down]').click();
      await settle();
      expect(all('[data-section=billing] .pcrow')[0].getAttribute('data-row')).toBe('allergies');
      await click('#pc-tab-layout');
      await click('[data-switch=billing]');
      expect(snack).toHaveBeenLastCalledWith(
        'This section holds a safety field (Allergies). Move it out first.',
        'Close',
        expect.anything(),
      );
      expect(q('[data-switch=billing]').getAttribute('aria-checked')).toBe('true');
    });

    it('moves a field by drag and drop onto another field or an empty section', async () => {
      await openFields();
      const start = new Event('dragstart', { bubbles: true });
      Object.defineProperty(start, 'dataTransfer', {
        value: { setData: vi.fn(), setDragImage: vi.fn() },
      });
      q('[data-row=room] .pcgrip').dispatchEvent(start);
      q('[data-row=admittedOn]').dispatchEvent(
        new Event('dragover', { bubbles: true, cancelable: true }),
      );
      q('[data-row=admittedOn]').dispatchEvent(
        new Event('drop', { bubbles: true, cancelable: true }),
      );
      await settle();
      expect(all('[data-section=stay] .pcrow').map((r) => r.getAttribute('data-row'))).toEqual([
        'room',
        'admittedOn',
        'lengthOfStay',
        'ward',
        'admissionType',
      ]);
      // Empty a section, then drop a field on it.
      q('[data-row=doctor] [data-move=up]').click();
      await settle();
      expect(q('[data-section=care]').textContent).toContain('Drop a field here');
      q('[data-row=ward] .pcgrip').dispatchEvent(start);
      q('[data-section=care]').dispatchEvent(
        new Event('dragover', { bubbles: true, cancelable: true }),
      );
      q('[data-section=care]').dispatchEvent(
        new Event('drop', { bubbles: true, cancelable: true }),
      );
      await settle();
      expect(all('[data-section=care] .pcrow').map((r) => r.getAttribute('data-row'))).toEqual([
        'ward',
      ]);
    });
  });

  // ---------------------------------------------------------------- story 4

  describe('Quick actions (AC37 to AC43)', () => {
    async function openActions(): Promise<void> {
      await open();
      await click('#pc-tab-actions');
    }

    it('lists the five actions with description, the fixed "who" line and a switch, and no Change who can use button', async () => {
      await openActions();
      expect(text('[data-testid=pc-actions]')).toContain(
        'Shown at the bottom of each card, in this order. Each role sees only the actions it is allowed to use.',
      );
      expect(all('[data-testid=pc-actions] .pcrm b').map((b) => b.textContent)).toEqual([
        'Nursing',
        'EMR',
        'Transfer',
        'Discharge',
        'Deposit',
      ]);
      expect(all('[data-testid=pc-who]').map((w) => w.textContent!.trim())).toEqual([
        'Owner, Nurse, Doctor',
        'Owner, Nurse, Doctor',
        'Owner, Nurse, Front desk',
        'Owner, Doctor',
        'Owner, Front desk',
      ]);
      expect(q('[data-row=nursing]').textContent).toContain('Record vitals and a nursing note');
      expect(
        all('[data-testid=pc-actions] .sw').map((s) => s.getAttribute('aria-checked')),
      ).toEqual(['true', 'true', 'true', 'true', 'false']);
      expect(fixture.nativeElement.textContent).not.toMatch(
        /Change who can use|Case paper|Update stage/,
      );
    });

    it('switches Deposit on and the preview shows it for the owner but not for a nurse', async () => {
      await openActions();
      await click('[data-switch=deposit]');
      expect(text('[data-testid=pc-draft]')).toBe('Draft · 1 change not published');
      // Nurse is the default role.
      expect(
        all('[data-testid=pc-frame] .pcacts button').map((b) => b.getAttribute('data-act')),
      ).toEqual(['nursing', 'emr', 'transfer']);
      expect(text('[data-testid=pc-hidden]')).toBe('Hidden for Nurse: 2 actions');
      const select = q<HTMLSelectElement>('[data-testid=pc-view-as]');
      select.value = 'OWNER';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
      expect(
        all('[data-testid=pc-frame] .pcacts button').map((b) => b.getAttribute('data-act')),
      ).toEqual(['nursing', 'emr', 'transfer', 'discharge', 'deposit']);
      expect(q('[data-testid=pc-hidden]')).toBeNull();
    });

    it('moves an action and the preview follows the order', async () => {
      await openActions();
      q<HTMLButtonElement>('[data-row=transfer] [data-move=up]').click();
      await settle();
      expect(all('[data-testid=pc-actions] .pcrm b').map((b) => b.textContent)).toEqual([
        'Nursing',
        'Transfer',
        'EMR',
        'Discharge',
        'Deposit',
      ]);
      expect(text('[data-testid=pc-announce]')).toBe('Transfer moved to position 2');
      expect(
        all('[data-testid=pc-frame] .pcacts button').map((b) => b.getAttribute('data-act')),
      ).toEqual(['nursing', 'transfer', 'emr']);
      expect(q<HTMLButtonElement>('[data-row=nursing] [data-move=up]').disabled).toBe(true);
      expect(q<HTMLButtonElement>('[data-row=deposit] [data-move=down]').disabled).toBe(true);
    });

    it('moves an action by drag and drop', async () => {
      await openActions();
      const start = new Event('dragstart', { bubbles: true });
      Object.defineProperty(start, 'dataTransfer', {
        value: { setData: vi.fn(), setDragImage: vi.fn() },
      });
      q('[data-row=discharge] .pcgrip').dispatchEvent(start);
      q('[data-row=emr]').dispatchEvent(new Event('dragover', { bubbles: true, cancelable: true }));
      q('[data-row=emr]').dispatchEvent(new Event('drop', { bubbles: true, cancelable: true }));
      await settle();
      expect(all('[data-testid=pc-actions] .pcrm b').map((b) => b.textContent)).toEqual([
        'Nursing',
        'Discharge',
        'EMR',
        'Transfer',
        'Deposit',
      ]);
    });
  });

  // ---------------------------------------------------------------- story 5

  describe('Live preview (AC44 to AC53)', () => {
    it('is inert, so its buttons leave the keyboard order and the accessibility tree (code review 37 #11)', async () => {
      await open();
      const frame = q('[data-testid=pc-frame]');
      expect(frame.hasAttribute('inert')).toBe(true);
      expect(frame.querySelectorAll('button').length).toBeGreaterThan(0);
    });

    it('is a labelled panel beside the editor, on every tab, with Desktop and Tablet', async () => {
      await open();
      expect(q('[data-testid=pc-preview]').getAttribute('aria-label')).toBe('Live preview');
      expect(text('[data-testid=pc-preview]')).toContain('Live preview');
      expect(text('[data-testid=pc-showing]')).toBe('Showing the live card');
      for (const tab of ['fields', 'actions', 'layout']) {
        await click(`#pc-tab-${tab}`);
        expect(q('[data-testid=pc-preview]')).not.toBeNull();
      }
      expect(
        all('[data-testid=pc-device] button').map((b) => b.getAttribute('aria-pressed')),
      ).toEqual(['true', 'false']);
    });

    it('offers View as Owner / Admin, Doctor, Nurse and Front desk with Nurse chosen, and the admitted patients', async () => {
      await open();
      const select = q<HTMLSelectElement>('[data-testid=pc-view-as]');
      expect(Array.from(select.options).map((o) => o.textContent!.trim())).toEqual([
        'Owner / Admin',
        'Doctor',
        'Nurse',
        'Front desk',
      ]);
      expect(select.value).toBe('NURSE');
      const patients = Array.from(q<HTMLSelectElement>('[data-testid=pc-patient]').options).map(
        (o) => o.textContent!.trim(),
      );
      expect(patients).toEqual(['Asha Patil · 101-A', 'Ravi Shah · 201-B', 'Sunita More · 102-A']);
      expect(q<HTMLSelectElement>('[data-testid=pc-patient]').value).toBe('a1');
    });

    it('asks for the patients once, with every card fact, and never polls', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('#pc-tab-fields');
      http.expectNone((r) => r.url === '/api/v1/ipd/admissions');
    });

    it('draws the draft for the chosen patient with the same card as the ward', async () => {
      await open();
      expect(text('[data-testid=pc-frame] [data-testid=card-name]')).toBe('Asha Patil');
      const select = q<HTMLSelectElement>('[data-testid=pc-patient]');
      select.value = 'a2';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
      expect(text('[data-testid=pc-frame] [data-testid=card-name]')).toBe('Ravi Shah');
      await click('[data-layout=compact]');
      expect(q('[data-testid=pc-frame] article').getAttribute('data-layout')).toBe('compact');
      expect(text('[data-testid=pc-showing]')).toBe('Showing your draft');
    });

    it('applies the fixed access rule from the catalogue: a field a role may not see is absent, and counted as hidden', async () => {
      await open();
      await click('[data-switch=billing]');
      // Nurse: three billing fields hidden.
      expect(q('[data-testid=pc-frame]').textContent).not.toMatch(/Running bill|Payer/);
      expect(text('[data-testid=pc-hidden]')).toBe('Hidden for Nurse: 3 fields, 1 action');
      const select = q<HTMLSelectElement>('[data-testid=pc-view-as]');
      select.value = 'FRONT_DESK';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
      expect(q('[data-testid=pc-frame]').textContent).toContain('Running bill');
      expect(q('[data-testid=pc-frame] [data-testid=card-flags]')).not.toBeNull();
      expect(q('[data-testid=pc-frame] [data-testid=card-vitals]')).toBeNull();
      expect(text('[data-testid=pc-hidden]')).toBe('Hidden for Front desk: 2 fields, 3 actions');
    });

    it('shows Mobile only for the owner and the front desk (AC33)', async () => {
      const rows = listOf();
      rows.items[0].mobile = '98000 11111';
      await open('OWNER', { patients: rows });
      await click('#pc-tab-fields');
      await click('[data-switch=mobile]');
      expect(q('[data-testid=pc-frame]').textContent).not.toContain('98000 11111');
      expect(text('[data-testid=pc-hidden]')).toBe('Hidden for Nurse: 1 field, 1 action');
      const select = q<HTMLSelectElement>('[data-testid=pc-view-as]');
      select.value = 'OWNER';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await settle();
      expect(q('[data-testid=pc-frame]').textContent).toContain('98000 11111');
    });

    it('is inert: the name is not a button and no action can be tabbed to or used', async () => {
      await open();
      const frame = q('[data-testid=pc-frame]');
      expect(frame.querySelector('[data-testid=card-name]')!.tagName).not.toBe('BUTTON');
      const buttons = Array.from(frame.querySelectorAll<HTMLButtonElement>('.pcacts button'));
      expect(buttons.length).toBeGreaterThan(0);
      expect(buttons.every((b) => b.getAttribute('tabindex') === '-1')).toBe(true);
      buttons.forEach((b) => b.click());
      await settle();
      expect(dialogOpen).not.toHaveBeenCalled();
      http.expectNone((r) => r.method !== 'GET');
    });

    it('writes the desktop note for each layout and the tablet note, and draws the tablet frame narrow', async () => {
      await open();
      expect(text('[data-testid=pc-note]')).toBe(
        'Desktop: cards fill the width, 3 to 4 to a row on a 1440 px screen.',
      );
      await click('[data-layout=compact]');
      expect(text('[data-testid=pc-note]')).toBe(
        'Desktop: cards fill the width, 4 to 5 to a row on a 1440 px screen.',
      );
      await click('[data-layout=expanded]');
      expect(text('[data-testid=pc-note]')).toBe(
        'Desktop: cards fill the width, 2 to 3 to a row on a 1440 px screen.',
      );
      await click('[data-device=tablet]');
      expect(text('[data-testid=pc-note]')).toBe(
        'Tablet: cards sit two to a row at 768 px width. Buttons stay at least 44 px tall.',
      );
      expect(q('[data-testid=pc-frame]').classList.contains('tablet')).toBe(true);
      expect(q('[data-device=tablet]').getAttribute('aria-pressed')).toBe('true');
    });

    it('offers one example patient when nobody is admitted (AC50)', async () => {
      await open('OWNER', { patients: listOf({ items: [] }) });
      expect(all('[data-testid=pc-patient] option').map((o) => o.textContent!.trim())).toEqual([
        'Example patient · 202',
      ]);
      expect(text('[data-testid=pc-frame] [data-testid=card-name]')).toBe('Example patient');
      expect(q('[data-testid=pc-patients-error]')).toBeNull();
    });

    it('shows the example patient and the notice when the patients cannot be loaded (AC53)', async () => {
      await open('OWNER', { patients: 'fail' });
      expect(text('[data-testid=pc-patients-error]')).toBe(
        'Could not load patients. Showing an example.',
      );
      expect(text('[data-testid=pc-frame] [data-testid=card-name]')).toBe('Example patient');
      expect(q('[data-testid=pc-bar]')).not.toBeNull();
    });

    it('draws the red outline and the flags for a patient with a High score, as on the ward (AC51)', async () => {
      const list = listOf();
      list.items[0].clinical = {
        escalation: { status: 'OPEN', waitingMinutes: 7, targetMinutes: 15, pastTarget: false },
        latestEws: { score: 8, risk: 'HIGH', recordedAt: '2026-10-24T07:00:00Z' },
        vitalsDue: false,
        allergy: { status: 'NOT_RECORDED' },
      };
      await open('OWNER', { patients: list });
      expect(q('[data-testid=pc-frame] article').classList.contains('pc-crit')).toBe(true);
      expect(text('[data-testid=pc-frame] [data-flag=escalation]')).toBe('Not seen · 7 min');
    });

    it('does not announce anything while the owner types (AC53)', async () => {
      await open();
      await click('#pc-tab-fields');
      await input('[data-label=doctor]', 'C');
      await input('[data-label=doctor]', 'Co');
      expect(text('[data-testid=pc-announce]')).toBe('');
    });
  });

  // ---------------------------------------------------------------- leaving

  describe('tabs', () => {
    it('has three tabs, Layout and sections first, and the arrow keys move between them', async () => {
      await open();
      expect(all('[role=tab]').map((t) => t.textContent!.trim())).toEqual([
        'Layout and sections',
        'Fields',
        'Quick actions',
      ]);
      expect(q('#pc-tab-layout').getAttribute('aria-selected')).toBe('true');
      const key = (k: string) => {
        q('#pc-tab-layout').dispatchEvent(
          new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }),
        );
        return settle();
      };
      await key('ArrowRight');
      expect(q('#pc-tab-fields').getAttribute('aria-selected')).toBe('true');
      expect(q('[data-testid=pc-fields]')).not.toBeNull();
      await key('End');
      expect(q('#pc-tab-actions').getAttribute('aria-selected')).toBe('true');
      await key('Home');
      expect(q('#pc-tab-layout').getAttribute('aria-selected')).toBe('true');
      await key('ArrowLeft');
      expect(q('#pc-tab-actions').getAttribute('aria-selected')).toBe('true');
      expect(q('#pc-panel').getAttribute('aria-labelledby')).toBe('pc-tab-actions');
    });
  });

  describe('leaving the page (AC4)', () => {
    it('lets the owner go when nothing is unsaved', async () => {
      await open();
      expect(fixture.componentInstance.confirmLeave()).toBe(true);
      await click('[data-layout=compact]');
      await click('[data-layout=standard]');
      expect(fixture.componentInstance.confirmLeave()).toBe(true);
      expect(dialogOpen).not.toHaveBeenCalled();
    });

    it('asks first when there are unsaved edits, and leaves only if the owner says so', async () => {
      await open();
      await click('[data-layout=compact]');
      let answer: boolean | undefined;
      leaveAnswer = false;
      (fixture.componentInstance.confirmLeave() as Observable<boolean>).subscribe(
        (v) => (answer = v),
      );
      expect(dialogOpen).toHaveBeenCalledTimes(1);
      expect(answer).toBe(false);
      leaveAnswer = true;
      (fixture.componentInstance.confirmLeave() as Observable<boolean>).subscribe(
        (v) => (answer = v),
      );
      expect(answer).toBe(true);
    });

    it('does not warn once the draft is saved (the saved draft is kept)', async () => {
      await open();
      await click('[data-layout=compact]');
      await click('[data-act=save]');
      http
        .expectOne(CARD + '/draft')
        .flush(ownerView({ draft: draftOf({ ...standardSetup(), layout: 'compact' }) }));
      await settle();
      expect(fixture.componentInstance.confirmLeave()).toBe(true);
    });

    it('asks the browser before the tab is closed with unsaved edits', async () => {
      await open();
      const clean = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(clean);
      expect(clean.defaultPrevented).toBe(false);
      await click('[data-layout=compact]');
      const dirty = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(dirty);
      expect(dirty.defaultPrevented).toBe(true);
    });
  });
});
