import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { Observable, Subject, of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../../core/shell/shell.store';
import { departmentView, sections, summary } from '../../emr/emr.fixtures';
import { EmrDepartmentList, EmrDepartmentView } from '../../emr/emr.model';
import { EmrConfigComponent } from './emr-config.component';

const EMR = '/api/v1/opd/emr';

const LIST = (canEdit: boolean): EmrDepartmentList => ({
  canEdit,
  departments: [
    summary(),
    summary({ departmentKey: 'dental', name: 'Dental', initials: 'DN', colour: '#0B6E69', status: 'LIVE', publishedVersion: 4 }),
  ],
});

describe('EmrConfigComponent', () => {
  let fixture: ComponentFixture<EmrConfigComponent>;
  let http: HttpTestingController;
  let snackOpen: ReturnType<typeof vi.fn>;
  let onAction: Subject<void>;
  let dialogChoice: boolean;
  let el: HTMLElement;

  async function boot(canEdit = true, role = 'OWNER'): Promise<void> {
    onAction = new Subject<void>();
    snackOpen = vi.fn(() => ({ onAction: () => onAction, dismiss: vi.fn() }));
    dialogChoice = false;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ role, clinic: { name: 'Sunrise Clinic' } }) } },
        { provide: MatSnackBar, useValue: { open: snackOpen } },
        { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(dialogChoice) }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmrConfigComponent);
    fixture.detectChanges();
    http.expectOne(`${EMR}/departments`).flush(LIST(canEdit));
    http.expectOne(`${EMR}/departments/gm`).flush(departmentView({ canEdit }));
    await settle();
    el = fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const chip = () => q('[data-testid=emr-chip]')!.textContent!.trim();
  const rowOf = (name: string) =>
    qa('.row').find((r) => r.querySelector('.nm')?.textContent?.trim() === name)!;
  const showSwitch = (name: string) => rowOf(name).querySelector<HTMLButtonElement>('[role=switch]')!;
  const act = (name: string) => q<HTMLButtonElement>(`[data-act=${name}]`)!;

  afterEach(() => http.verify());

  describe('Form builder links (SAH-59-1)', () => {
    it('shows "+ Form builder" in the header and the "Have your own chart or form?" band, for every role', async () => {
      await boot(false, 'DOCTOR');
      const link = el.querySelector<HTMLAnchorElement>('[data-act=form-builder]')!;
      expect(link.textContent!.trim()).toBe('+ Form builder');
      expect(link.getAttribute('href')).toBe('/app/config/forms');
      const band = el.querySelector('[data-testid=form-builder-band]')!;
      expect(band.textContent).toContain('Have your own chart or form?');
      expect(band.textContent).toContain('Build it in the Form builder: fields, scores, show-if rules and print layout. It appears here as a custom section.');
      expect(band.querySelector('a')!.textContent!.trim()).toBe('Open form builder');
      expect(band.querySelector('a')!.getAttribute('href')).toBe('/app/config/forms');
    });
  });

  describe('list (SAH-45-1)', () => {
    it('shows the title, the line, the clinic and a card for each department with status and version', async () => {
      await boot();
      expect(q('h2')!.textContent).toContain('EMR configuration by department');
      expect(el.textContent).toContain(
        'Choose a department, then pick which EMR sections the doctor sees, in what order, and which are required.',
      );
      expect(el.textContent).toContain('Sunrise Clinic');
      expect(q('[data-testid=emr-crumb]')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
        'Clinic setup / Specialty template / EMR configuration',
      );
      const clinic = q<HTMLSelectElement>('[data-testid=emr-clinic]')!;
      expect(clinic.disabled).toBe(true);
      expect(clinic.options[0].textContent).toContain('Sunrise Clinic');
      const cards = qa('.dcard');
      expect(cards).toHaveLength(2);
      expect(cards[0].textContent).toContain('GM');
      expect(cards[0].textContent).toContain('General Medicine');
      expect(cards[0].textContent).toContain('6 of 6 sections on');
      expect(cards[0].textContent).toContain('Template');
      expect(cards[0].textContent).not.toContain('v0');
      expect(cards[1].textContent).toContain('Live');
      expect(cards[1].textContent).not.toContain('v4');
    });

    it('selects the first department and its first Specialty section', async () => {
      await boot();
      expect(qa('.dcard')[0].getAttribute('aria-pressed')).toBe('true');
      expect(q('app-section-settings h2')!.textContent).toContain('Dental chart (odontogram)');
    });

    it('filters by search and shows an empty message when nothing matches', async () => {
      await boot();
      const input = q<HTMLInputElement>('input[type=search]')!;
      input.value = 'dent';
      input.dispatchEvent(new Event('input'));
      await settle();
      expect(qa('.dcard')).toHaveLength(1);
      input.value = 'zzz';
      input.dispatchEvent(new Event('input'));
      await settle();
      expect(qa('.dcard')).toHaveLength(0);
      expect(el.textContent).toContain('No department matches “zzz”');
    });

    it('loads another department when it is picked', async () => {
      await boot();
      qa('.dcard')[1].click();
      http.expectOne(`${EMR}/departments/dental`).flush(departmentView({ departmentKey: 'dental', name: 'Dental', initials: 'DN' }));
      await settle();
      expect(q('.dhead h3')!.textContent).toContain('Dental');
    });

    it('shows a retry when the list cannot be loaded', async () => {
      onAction = new Subject<void>();
      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          { provide: ShellStore, useValue: { me: () => ({ role: 'OWNER' }) } },
          { provide: MatSnackBar, useValue: { open: vi.fn() } },
          { provide: MatDialog, useValue: { open: vi.fn() } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(EmrConfigComponent);
      fixture.detectChanges();
      http.expectOne(`${EMR}/departments`).flush({}, { status: 500, statusText: 'x' });
      await settle();
      el = fixture.nativeElement as HTMLElement;
      expect(q('[role=alert]')!.textContent).toContain('We could not load the departments');
      expect(q('[data-act=save-draft]')).toBeNull();
      q<HTMLButtonElement>('[data-act=retry]')!.click();
      http.expectOne(`${EMR}/departments`).flush(LIST(true));
      http.expectOne(`${EMR}/departments/gm`).flush(departmentView());
    });
  });

  describe('sections and settings (SAH-45-2, SAH-45-3)', () => {
    it('groups the sections by part with the counts', async () => {
      await boot();
      const heads = qa('.grp-h h3').map((h) => h.textContent!.trim());
      expect(heads).toEqual(['Assessment · General Medicine', 'Treatment · General Medicine']);
      expect(qa('.grp-h .pill').map((p) => p.textContent!.trim())).toEqual(['3 of 3 on', '3 of 3 on']);
    });

    it('shows the tag, the field count and Required or Optional on a row', async () => {
      await boot();
      const row = rowOf('Chief complaints');
      expect(row.querySelector('.tag')!.textContent).toBe('Common');
      expect(row.textContent).toContain('3 fields');
      expect(row.querySelector('.req')!.textContent).toContain('Required');
      expect(rowOf('Vitals').querySelector('.req')!.textContent).toContain('Optional');
      expect(rowOf('Dental chart (odontogram)').querySelector('.tag')!.textContent).toBe('GM specialty');
    });

    it('turns Show off: the count drops, Required is cleared and cannot be turned on again', async () => {
      await boot();
      showSwitch('Chief complaints').click();
      await settle();
      expect(rowOf('Chief complaints').classList).toContain('off');
      expect(qa('.grp-h .pill')[0].textContent!.trim()).toBe('2 of 3 on');
      const req = rowOf('Chief complaints').querySelector<HTMLButtonElement>('.req')!;
      expect(req.textContent).toContain('Optional');
      expect(req.disabled).toBe(true);
      expect(chip()).toBe('2 unsaved changes');
    });

    it('counts a switch turned off and on again as no change', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      expect(chip()).toBe('1 unsaved change');
      showSwitch('Prescription (Rx)').click();
      await settle();
      expect(chip()).toBe('All changes published');
    });

    it('locks Show for Allergies with the reason', async () => {
      await boot();
      expect(showSwitch('Allergies').disabled).toBe(true);
      qa('.name').find((b) => b.textContent!.includes('Allergies'))!.click();
      await settle();
      expect(q('app-section-settings .lock')!.textContent).toContain('Allergies are always shown');
    });

    it('shows the five switches, the read-only fields and Who can fill in the panel', async () => {
      await boot();
      const labels = qa('app-section-settings .slabel').map((l) => l.textContent!.trim());
      expect(labels).toEqual([
        'Show in EMR',
        'Required to close visit',
        'Print on prescription',
        'Copy from last visit',
        'Collapsed by default',
      ]);
      expect(q('app-section-settings .fields h3')!.textContent).toContain('Fields (0)');
      expect(q('app-section-settings .empty')!.textContent).toContain('no fields');
      expect(qa('app-section-settings .chipb').map((c) => c.textContent!.trim())).toEqual([
        'Doctor',
        'Nurse / assistant',
        'Reception',
      ]);
      expect(el.textContent).toContain('Changes apply to General Medicine · New consult after you publish.');
    });

    it('toggles Who can fill from the chips', async () => {
      await boot();
      const nurse = qa('app-section-settings .chipb')[1];
      nurse.click();
      await settle();
      expect(qa('app-section-settings .chipb')[1].getAttribute('aria-pressed')).toBe('true');
      expect(chip()).toBe('1 unsaved change');
    });

    it('moves a section to Treatment, says so, and counts it', async () => {
      await boot();
      rowOf('Vitals').querySelector<HTMLButtonElement>('.mvpart')!.click();
      await settle();
      expect(snackOpen).toHaveBeenCalledWith('Vitals moved to Treatment', 'Close', expect.anything());
      expect(qa('.grp-h .pill').map((p) => p.textContent!.trim())).toEqual(['2 of 2 on', '4 of 4 on']);
      expect(chip()).toBe('1 unsaved change');
    });

    it('moves a section down with an accessible button and nothing happens at the edge', async () => {
      await boot();
      const up = qa('button').find((b) => b.getAttribute('aria-label') === 'Move Vitals up') as HTMLButtonElement;
      expect(up.disabled).toBe(true);
      const down = qa('button').find((b) => b.getAttribute('aria-label') === 'Move Vitals down') as HTMLButtonElement;
      down.click();
      await settle();
      const names = qa('.grp')[0].querySelectorAll('.nm');
      expect(names[0].textContent).toBe('Chief complaints');
      expect(chip()).toBe('1 unsaved change');
    });

    it('switches the preview words with the visit type without changing the data', async () => {
      await boot();
      const follow = qa('.visit .seg button').find((b) => b.textContent!.includes('Follow-up'))!;
      follow.click();
      await settle();
      expect(el.textContent).toContain('General Medicine · Follow-up after you publish');
      expect(chip()).toBe('All changes published');
    });
  });

  describe('preview (SAH-45-6)', () => {
    it('draws the doctor screen from the draft, with the red star and no Missing pill on required sections', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      qa('[role=tab]').find((t) => t.textContent!.includes('Doctor screen preview'))!.click();
      await settle();
      const preview = q('app-doctor-preview')!;
      expect(preview.textContent).toContain('Doctor screen preview · General Medicine · New consult');
      expect(preview.textContent).toContain('1 · Assessment');
      expect(preview.textContent).toContain('Doctor fills this first');
      expect(preview.textContent).toContain('2 · Treatment');
      expect(preview.textContent).toContain('Opens after assessment');
      expect(preview.querySelectorAll('.card-h .pill')).toHaveLength(0);
      expect(preview.querySelector('.card .star')).not.toBeNull();
      expect(preview.textContent).not.toContain('Prescription (Rx)');
      expect(preview.textContent).not.toContain('comes in a later step');
      const chart = preview.querySelector('[data-section=dental_chart]')!;
      expect(chart.querySelectorAll('.tooth')).toHaveLength(32);
      expect(chart.textContent).toContain('Caries');
      expect(chart.textContent).toContain('Missing');
      expect(chart.classList.contains('span2')).toBe(true);
    });
  });

  describe('Save draft and Publish (SAH-45-4)', () => {
    it('says nothing new to save without calling the server', async () => {
      await boot();
      act('save-draft').click();
      await settle();
      expect(snackOpen).toHaveBeenCalledWith('Nothing new to save', 'Close', expect.anything());
    });

    it('sends the whole layout with the revision and shows the draft as saved but not published', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('save-draft').click();
      const req = http.expectOne(`${EMR}/departments/gm/draft`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.revision).toBe(0);
      expect(req.request.body.origin).toBe('EDIT');
      expect(req.request.body.sections).toHaveLength(6);
      expect(req.request.body.sections.find((s: { key: string }) => s.key === 'rx').shown).toBe(false);
      expect(Object.keys(req.request.body.sections[0]).sort()).toEqual(
        ['collapsed', 'copy', 'key', 'part', 'print', 'required', 'shown', 'whoCanFill'],
      );
      const saved: EmrDepartmentView = departmentView({
        revision: 1,
        status: 'DRAFT',
        hasDraft: true,
        layoutSource: 'DRAFT',
        sections: sections().map((s) => (s.key === 'rx' ? { ...s, shown: false } : s)),
      });
      req.flush({ outcome: 'SAVED', department: saved });
      await settle();
      expect(snackOpen).toHaveBeenCalledWith(
        'Draft saved. Doctors still see the published layout.',
        'Close',
        expect.anything(),
      );
      expect(chip()).toBe('Draft saved, not published');
      expect(qa('.dcard')[0].textContent).toContain('Draft');
    });

    it('makes one request when Save draft is tapped twice quickly', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('save-draft').click();
      act('save-draft').click();
      const reqs = http.match(`${EMR}/departments/gm/draft`);
      expect(reqs).toHaveLength(1);
      reqs[0].flush({ outcome: 'SAVED', department: departmentView({ revision: 1 }) });
    });

    it('shows a busy state while the request runs', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('save-draft').click();
      await settle();
      expect(act('save-draft').textContent).toContain('Saving');
      expect(act('save-draft').disabled).toBe(false);
      http.expectOne(`${EMR}/departments/gm/draft`).flush({ outcome: 'NOTHING_NEW', department: departmentView() });
    });

    it('says nothing to publish without calling the server', async () => {
      await boot();
      act('publish').click();
      await settle();
      expect(snackOpen).toHaveBeenCalledWith('Nothing to publish', 'Close', expect.anything());
    });

    it('publishes the next version and shows Live with the new version', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('publish').click();
      const req = http.expectOne(`${EMR}/departments/gm/publish`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.revision).toBe(0);
      req.flush({
        outcome: 'PUBLISHED',
        publishedVersion: 1,
        department: departmentView({ revision: 1, status: 'LIVE', publishedVersion: 1, layoutSource: 'PUBLISHED' }),
      });
      await settle();
      expect(snackOpen).toHaveBeenCalledWith(
        'General Medicine published as v1. New visits use it from now.',
        'Close',
        expect.anything(),
      );
      expect(chip()).toBe('All changes published');
      expect(qa('.dcard')[0].textContent).toContain('Live');
      expect(qa('.dcard')[0].textContent).not.toContain('v1');
    });

    it('applies nothing and offers a reload when someone else changed the department (409)', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('save-draft').click();
      http.expectOne(`${EMR}/departments/gm/draft`).flush(
        { type: 'https://sahaj.in/problems/emr-setup-changed', title: 'Conflict', status: 409 },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      expect(q('[data-testid=emr-save-error]')!.textContent).toContain(
        'Someone else changed this department. Reload and try again.',
      );
      expect(chip()).toBe('1 unsaved change');
      expect(act('save-draft').disabled).toBe(false);
      act('save-draft').click();
      act('publish').click();
      http.expectNone(`${EMR}/departments/gm/draft`);
      q<HTMLButtonElement>('[data-act=reload]')!.click();
      http.expectOne(`${EMR}/departments/gm`).flush(departmentView({ revision: 5 }));
      await settle();
      expect(chip()).toBe('All changes published');
      expect(q('[data-testid=emr-save-error]')).toBeNull();
    });

    it('keeps the changes on screen as unsaved when the network fails', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('publish').click();
      http.expectOne(`${EMR}/departments/gm/publish`).error(new ProgressEvent('error'));
      await settle();
      expect(q('[data-testid=emr-save-error]')!.textContent).toContain('still here, unsaved');
      expect(chip()).toBe('1 unsaved change');
      expect(act('publish').disabled).toBe(false);
    });

    it('shows the rule message of the server for a refused layout (400)', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('save-draft').click();
      http.expectOne(`${EMR}/departments/gm/draft`).flush(
        { type: 'https://sahaj.in/problems/section-not-hideable', title: 'Bad', status: 400, detail: 'Allergies cannot be hidden.' },
        { status: 400, statusText: 'Bad' },
      );
      await settle();
      expect(q('[data-testid=emr-save-error]')!.textContent).toContain('Allergies cannot be hidden.');
    });
  });

  describe('Reset to template (SAH-45-5)', () => {
    const target = (shownRx: boolean) => ({
      departmentKey: 'gm',
      basis: 'STARTER',
      sections: sections().map((s) => ({
        key: s.key, part: s.part, shown: s.key === 'rx' ? shownRx : s.shown, required: s.required,
        print: s.print, copy: s.copy, collapsed: s.collapsed, whoCanFill: s.whoCanFill,
      })),
    });

    it('puts the starter values on the page, counts them as unsaved and offers Undo', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('reset').click();
      http.expectOne(`${EMR}/departments/gm/reset-target`).flush(target(true));
      await settle();
      expect(snackOpen).toHaveBeenLastCalledWith('General Medicine reset to template', 'Undo', expect.anything());
      expect(showSwitch('Prescription (Rx)').getAttribute('aria-checked')).toBe('true');
      // Back to the saved layout: nothing differs from what the server sent.
      expect(chip()).toBe('All changes published');
      onAction.next();
      await settle();
      expect(showSwitch('Prescription (Rx)').getAttribute('aria-checked')).toBe('false');
      expect(chip()).toBe('1 unsaved change');
    });

    it('says nothing changed when the department already equals the template', async () => {
      await boot();
      act('reset').click();
      http.expectOne(`${EMR}/departments/gm/reset-target`).flush(target(true));
      await settle();
      expect(snackOpen).toHaveBeenCalledWith('Nothing changed', 'Close', expect.anything());
      expect(chip()).toBe('All changes published');
    });

    it('sends origin RESET with the save that follows', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('reset').click();
      http.expectOne(`${EMR}/departments/gm/reset-target`).flush(target(true));
      await settle();
      showSwitch('Vitals').click();
      await settle();
      act('save-draft').click();
      const req = http.expectOne(`${EMR}/departments/gm/draft`);
      expect(req.request.body.origin).toBe('RESET');
      req.flush({ outcome: 'SAVED', department: departmentView({ revision: 1 }) });
    });

    it('no longer offers Undo after another change', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      act('reset').click();
      http.expectOne(`${EMR}/departments/gm/reset-target`).flush(target(true));
      await settle();
      showSwitch('Vitals').click();
      await settle();
      onAction.next();
      await settle();
      expect(showSwitch('Prescription (Rx)').getAttribute('aria-checked')).toBe('true');
      expect(showSwitch('Vitals').getAttribute('aria-checked')).toBe('false');
    });
  });

  describe('other roles read only (SAH-45-1, SAH-45-2)', () => {
    it('shows the line, no Save, Publish, Reset, move or Add department, and disabled switches', async () => {
      await boot(false, 'DOCTOR');
      expect(q('[data-testid=emr-readonly]')!.textContent).toContain('Only the owner can change this.');
      expect(q('[data-act=save-draft]')).toBeNull();
      expect(q('[data-act=publish]')).toBeNull();
      expect(q('[data-act=reset]')).toBeNull();
      expect(q('.mvpart')).toBeNull();
      expect(q('.mvb')).toBeNull();
      expect(q('.addlink')).toBeNull();
      expect(showSwitch('Vitals').disabled).toBe(true);
      expect(rowOf('Vitals').querySelector<HTMLButtonElement>('.req')!.disabled).toBe(true);
      expect(qa('app-section-settings .chipb').every((c) => (c as HTMLButtonElement).disabled)).toBe(true);
      expect(qa('app-section-settings [role=switch]').every((c) => (c as HTMLButtonElement).disabled)).toBe(true);
    });

    it('still lets them look at the preview', async () => {
      await boot(false, 'NURSE');
      qa('[role=tab]').find((t) => t.textContent!.includes('Doctor screen preview'))!.click();
      await settle();
      expect(q('app-doctor-preview')).not.toBeNull();
    });

    it('ignores a change that reaches the page anyway', async () => {
      await boot(false, 'DOCTOR');
      showSwitch('Vitals').click();
      await settle();
      expect(chip()).toBe('All changes published');
    });
  });

  describe('leaving the page (SAH-45-4)', () => {
    it('lets the owner leave when nothing is unsaved', async () => {
      await boot();
      expect(fixture.componentInstance.confirmLeave()).toBe(true);
    });

    it('asks first when changes are unsaved, and follows the answer', async () => {
      await boot();
      showSwitch('Prescription (Rx)').click();
      await settle();
      let answer: boolean | undefined;
      (fixture.componentInstance.confirmLeave() as Observable<boolean>).subscribe((v) => (answer = v));
      expect(answer).toBe(false);
      dialogChoice = true;
      (fixture.componentInstance.confirmLeave() as Observable<boolean>).subscribe((v) => (answer = v));
      expect(answer).toBe(true);
    });

    it('warns before the browser tab is closed with unsaved changes', async () => {
      await boot();
      const clean = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(clean);
      expect(clean.defaultPrevented).toBe(false);
      showSwitch('Prescription (Rx)').click();
      await settle();
      const dirty = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(dirty);
      expect(dirty.defaultPrevented).toBe(true);
    });
  });
});
