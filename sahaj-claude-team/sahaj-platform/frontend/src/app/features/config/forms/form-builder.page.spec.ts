import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmrFormView } from '../../emr/emr-phase3.model';
import { footDefinition, formView, settings } from '../../emr/forms/form.fixtures';
import { FormBuilderPage } from './form-builder.page';

const FORMS = '/api/v1/opd/emr/forms/f_abcdefghij';

describe('FormBuilderPage (SAH-59)', () => {
  let fixture: ComponentFixture<FormBuilderPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let dialogResult: unknown;
  let page: FormBuilderPage;

  function boot(view: EmrFormView = formView()): void {
    dialogResult = 'stay';
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'f_abcdefghij' } } } },
        { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(dialogResult) }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FormBuilderPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
    http.expectOne(FORMS).flush(view);
    http.expectOne('/api/v1/opd/emr/departments').flush({
      canEdit: true,
      departments: [
        { departmentKey: 'gm', name: 'General Medicine', status: 'TEMPLATE', publishedVersion: 0 },
        { departmentKey: 'dental', name: 'Dental', status: 'TEMPLATE', publishedVersion: 0 },
        { departmentKey: 'd_hidden0001', name: 'Unpublished', status: 'DRAFT', publishedVersion: 0 },
      ],
    });
    http.expectOne('/api/v1/platform/clinic/profile').flush({ name: 'Test Clinic', address: '12 Test Road', phone: '020 5555 0100' });
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const card = (key: string) => q(`[data-field=${key}]`)!;
  const toast = () => q('[data-testid=builder-toast]')?.textContent?.replace(/\s+/g, ' ').trim();
  const click = (sel: string) => {
    q<HTMLButtonElement>(sel)!.click();
    fixture.detectChanges();
  };
  const tab = (name: string) => click(`[data-tab=${name}]`);
  const mode = (name: string) => click(`[data-mode=${name}]`);

  function selectField(key: string): void {
    card(key).querySelector<HTMLButtonElement>('.pick')!.click();
    fixture.detectChanges();
  }

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    http.verify();
  });

  describe('the page (SAH-59-1)', () => {
    it('shows the form name, the state chip, the field count line and the three modes', () => {
      boot();
      expect(q<HTMLInputElement>('[data-testid=form-name]')!.value).toBe('Diabetic foot examination');
      expect(q('[data-testid=state-chip]')!.textContent).toBe('v1 · draft');
      expect(q('[data-testid=count-line]')!.textContent).toBe('9 fields · 1 with logic · 2 scored');
      expect(qa('[role=tab][data-mode]').map((t) => t.textContent!.trim())).toEqual(['Build', 'Preview', 'Print']);
      expect(q('.crumb')!.textContent!.replace(/\s+/g, ' ')).toContain('Settings / EMR configuration / Form builder');
    });

    it('lists the 23 field types of the palette in three groups and the hint', () => {
      boot();
      expect(qa('.pitem')).toHaveLength(23);
      expect(qa('.pg .gl').map((g) => g.textContent)).toEqual(['Basic fields', 'Clinical', 'Layout and sign-off']);
      expect(q('.left .faint')!.textContent).toBe('Drag a field onto the form, or tap to add it below the selected field.');
    });

    it('draws each field on the canvas with its badges (shows-if, scored, code)', () => {
      boot();
      expect(qa('[data-testid=canvas] .card')).toHaveLength(10);
      expect(card('site').textContent).toContain('Shows if Ulcer present now = Yes');
      expect(card('ulcer').textContent).toContain('Scored');
      expect(card('mono').textContent).toContain('LOINC');
    });

    it('shows the Later note on photo, signature, file and handwriting fields', () => {
      boot();
      expect(card('sig').textContent).toContain('Later');
    });
  });

  describe('building (SAH-59-1)', () => {
    it('adds a field below the selected one, selects it and says so', () => {
      boot();
      selectField('mono');
      click('[data-type=DATE]');
      const keys = qa('[data-testid=canvas] .card').map((c) => c.getAttribute('data-field'));
      expect(keys.slice(2, 5)).toEqual(['mono', 'date', 'vib']);
      expect(toast()).toBe('Date added. Set it up on the right.');
      expect(q('[data-testid=field-settings]')).not.toBeNull();
      expect(q<HTMLInputElement>('[data-testid=field-settings] input')!.value).toBe('Date');
      expect(q('[data-testid=count-line]')!.textContent).toBe('10 fields · 1 with logic · 2 scored');
    });

    it('adds at the end when nothing is selected', () => {
      boot();
      page['store'].select(null);
      fixture.detectChanges();
      click('[data-type=SHORT_TEXT]');
      const keys = qa('[data-testid=canvas] .card').map((c) => c.getAttribute('data-field'));
      expect(keys[keys.length - 1]).toBe('short_text');
    });

    it('adds a field where it is dropped', () => {
      boot();
      const pitem = q<HTMLElement>('[data-type=NUMBER]')!;
      pitem.dispatchEvent(new Event('dragstart'));
      const target = card('vib');
      target.dispatchEvent(new Event('dragover', { cancelable: true }));
      target.dispatchEvent(new Event('drop', { cancelable: true }));
      fixture.detectChanges();
      const keys = qa('[data-testid=canvas] .card').map((c) => c.getAttribute('data-field'));
      expect(keys[keys.indexOf('vib') - 1]).toBe('number');
    });

    it('moves a field up and down with buttons', () => {
      boot();
      selectField('mono');
      click('[data-act=up]');
      expect(qa('[data-testid=canvas] .card').map((c) => c.getAttribute('data-field')).slice(1, 4)).toEqual(['mono', 'ulcer', 'vib']);
      click('[data-act=down]');
      expect(qa('[data-testid=canvas] .card').map((c) => c.getAttribute('data-field')).slice(1, 4)).toEqual(['ulcer', 'mono', 'vib']);
    });

    it('refuses a move that would leave a rule pointing to a later field', () => {
      boot();
      selectField('site');
      click('[data-act=up]');
      expect(toast()).toContain('must stay below the field its rule uses');
    });

    it('duplicates a field below with "(copy)"', () => {
      boot();
      selectField('mono');
      click('[data-act=copy]');
      expect(card('mono_2').textContent).toContain('Monofilament: sites felt (copy)');
    });

    it('deletes a field with an Undo message', () => {
      boot();
      selectField('sig');
      click('[data-act=delete]');
      expect(q('[data-field=sig]')).toBeNull();
      expect(toast()).toContain('“Examined by” deleted');
      click('[data-act=undo]');
      expect(q('[data-field=sig]')).not.toBeNull();
    });

    it('does not delete a field that other fields use, and says which', () => {
      boot();
      selectField('now');
      click('[data-act=delete]');
      expect(q('[data-field=now]')).not.toBeNull();
      expect(toast()).toContain('“Ulcer site, size and depth”');
      expect(toast()).toContain('“Foot risk score”');
    });

    it('changes the label of the selected field on the canvas as it is typed', () => {
      boot();
      selectField('mono');
      const label = q<HTMLInputElement>('[data-testid=field-settings] label.lab input')!;
      label.value = 'Sites felt';
      label.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      expect(card('mono').textContent).toContain('Sites felt');
    });

    it('turns the Required switch and the width', () => {
      boot();
      selectField('vib');
      const sw = q<HTMLButtonElement>('[data-testid=field-settings] [aria-label="Required"]')!;
      expect(sw.getAttribute('aria-checked')).toBe('false');
      sw.click();
      fixture.detectChanges();
      expect(card('vib').textContent).toContain('*');
      const half = Array.from(qa('[data-testid=field-settings] .seg button')).find((b) => b.textContent === 'Half')!;
      half.click();
      fixture.detectChanges();
      expect(card('vib').style.gridColumn).toContain('span 3');
    });

    it('edits options and their scores, and keeps at least one option', () => {
      boot();
      selectField('vib');
      const scores = () => qa('[data-testid=option-score]').map((s) => s.textContent);
      expect(scores()).toEqual(['0', '1', '1']);
      q<HTMLButtonElement>('[data-testid=field-settings] [aria-label="Higher score"]')!.click();
      fixture.detectChanges();
      expect(scores()).toEqual(['1', '1', '1']);
      click('[data-act=add-option]');
      expect(scores()).toHaveLength(4);
    });

    it('sets the show-if rule from the earlier choice fields and its value', () => {
      boot();
      selectField('mono');
      const select = q<HTMLSelectElement>('[data-testid=logic-field]')!;
      expect(Array.from(select.options).map((o) => o.textContent!.trim())).toEqual(['Always', 'Only when “Previous foot ulcer” …']);
      select.value = 'ulcer';
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      expect(card('mono').textContent).toContain('Shows if Previous foot ulcer = Yes');
      Array.from(qa('.lv .chip')).find((c) => c.textContent === 'No')!.click();
      fixture.detectChanges();
      expect(card('mono').textContent).toContain('Shows if Previous foot ulcer = No');
    });

    it('shows the number settings with the mockup words', () => {
      boot();
      selectField('mono');
      const labels = qa('[data-testid=field-settings] .box .lab > span').map((s) => s.textContent);
      expect(labels).toEqual(['Unit', 'Normal from', 'Lowest allowed', 'Normal to', 'Highest allowed']);
    });

    it('lets the owner pick the fields a Score adds up and add a points rule', () => {
      boot();
      selectField('risk');
      expect(qa('[data-testid=field-settings] .chk input').map((c) => (c as HTMLInputElement).checked)).toEqual([true, true, false, true]);
      click('[data-act=add-rule-number]');
      expect(q('[data-testid=field-settings]')!.textContent).toContain('is below');
    });

    it('lets the owner edit the bands of a Score band', () => {
      boot();
      selectField('cat');
      expect(qa('.brow')).toHaveLength(3);
      click('[data-act=add-band]');
      expect(qa('.brow')).toHaveLength(4);
    });
  });

  describe('Form settings and versions (SAH-59-4)', () => {
    it('lists the visible departments and the places, and picks where the form is used', () => {
      boot();
      tab('form');
      const chips = qa('[data-testid=form-settings] .chip').map((c) => c.textContent!.trim().replace('✓ ', ''));
      expect(chips).toContain('General Medicine');
      expect(chips).toContain('Dental');
      expect(chips).not.toContain('Unpublished');
      expect(chips).toContain('Emergency');
      expect(chips).toContain('IPD wards');
      Array.from(qa('[data-testid=form-settings] .chip')).find((c) => c.textContent!.includes('Dental'))!.click();
      fixture.detectChanges();
      expect(q('.namerow')).not.toBeNull();
      expect(page['store'].settings()!.useIn.departmentKeys).toEqual(['gm', 'dental']);
    });

    it('turns "Approval before publishing" off, and the button reads Publish', () => {
      boot();
      expect(q('[data-act=publish]')!.textContent!.trim()).toBe('Send for approval');
      tab('form');
      click('[data-act=approval-off]');
      expect(q('[data-act=publish]')!.textContent!.trim()).toBe('Publish');
      expect(q('[data-testid=form-settings]')!.textContent).toContain('Publishes straight away.');
    });

    it('lists the versions and the sentence about filled records', () => {
      boot(formView({ versions: [{ versionNo: 3, state: 'DRAFT', at: '2026-11-12T05:00:00Z', byName: 'Dr Owner' }, { versionNo: 2, state: 'LIVE', at: '2026-11-01T05:00:00Z', byName: 'Dr Owner' }] }));
      tab('form');
      expect(qa('.ver').map((v) => v.textContent!.replace(/\s+/g, ' ').trim())[1]).toContain('v2');
      expect(q('[data-testid=form-settings]')!.textContent).toContain('Records already filled keep the version they were filled in.');
    });
  });

  describe('Save draft and the approval steps', () => {
    it('reads the meta line with the roles that fill the form', () => {
      boot(formView({ definition: footDefinition({ settings: settings({ whoCanFill: ['DOCTOR', 'NURSE'] }) }) }));
      expect(page['store'].metaLine()).toBe('General Medicine · Assessment · filled by doctor, nurse');
    });

    it('answers "Nothing new to save" when nothing changed, without calling the server', () => {
      boot();
      const button = q<HTMLButtonElement>('[data-act=save-draft]')!;
      expect(button.disabled).toBe(false);
      button.click();
      fixture.detectChanges();
      expect(toast()).toBe('Nothing new to save');
    });

    it('saves the draft with the revision, then says "Draft saved"', () => {
      boot();
      const name = q<HTMLInputElement>('[data-testid=form-name]')!;
      name.value = 'Foot check';
      name.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      click('[data-act=save-draft]');
      const req = http.expectOne(`${FORMS}/draft`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.revision).toBe(4);
      expect(req.request.body.name).toBe('Foot check');
      expect(req.request.body.fields).toHaveLength(10);
      req.flush(formView({ revision: 5, definition: footDefinition({ name: 'Foot check' }) }));
      fixture.detectChanges();
      expect(toast()).toBe('Draft saved');
    });

    it('keeps the changes on screen and offers a reload when someone else saved first (409)', () => {
      boot();
      click('[data-type=DATE]');
      click('[data-act=save-draft]');
      http.expectOne(`${FORMS}/draft`).flush({ type: 'https://sahaj.in/problems/form-changed', title: 'x', status: 409 }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(q('[data-testid=save-error]')!.textContent).toContain('Someone else changed this form. Reload and try again.');
      expect(qa('[data-testid=canvas] .card')).toHaveLength(11);
      click('[data-act=reload]');
      http.expectOne(FORMS).flush(formView());
      http.expectOne('/api/v1/opd/emr/departments').flush({ canEdit: true, departments: [] });
      fixture.detectChanges();
      expect(qa('[data-testid=canvas] .card')).toHaveLength(10);
    });

    it('does nothing and says "Pick at least one department in Form settings first." when none is chosen', () => {
      boot(formView({ definition: footDefinition({ settings: settings({ useIn: { departmentKeys: [], places: [] } }) }) }));
      click('[data-act=publish]');
      expect(toast()).toBe('Pick at least one department in Form settings first.');
      expect(q('[data-testid=form-settings]')).not.toBeNull();
    });

    it('does nothing and says "Add at least one field" for an empty form', () => {
      boot(formView({ definition: footDefinition({ fields: [] }) }));
      click('[data-act=publish]');
      expect(toast()).toBe('Add at least one field');
    });

    it('sends for approval and names the departments where it will appear', () => {
      boot();
      click('[data-act=publish]');
      const submit = http.expectOne(`${FORMS}/submit`);
      expect(submit.request.body).toEqual({ revision: 4 });
      submit.flush(formView({ state: 'WAITING_APPROVAL', chipVersionNo: 1, revision: 5 }));
      fixture.detectChanges();
      expect(toast()).toBe('v1 sent for approval. After approval it appears in General Medicine (Assessment).');
      expect(q('[data-testid=state-chip]')!.textContent).toBe('v1 · waiting approval');
    });

    it('saves unsaved changes first, then sends for approval', () => {
      boot();
      click('[data-type=DATE]');
      click('[data-act=publish]');
      http.expectOne(`${FORMS}/draft`).flush(formView({ revision: 5 }));
      const submit = http.expectOne(`${FORMS}/submit`);
      expect(submit.request.body).toEqual({ revision: 5 });
      submit.flush(formView({ state: 'WAITING_APPROVAL', revision: 6 }));
    });

    it('publishes at once when approval is off', () => {
      boot(formView({ definition: footDefinition({ settings: settings({ approvalRequired: false }) }) }));
      click('[data-act=publish]');
      http.expectOne(`${FORMS}/publish`).flush(formView({ state: 'LIVE', chipVersionNo: 1, liveVersionNo: 1, revision: 5 }));
      fixture.detectChanges();
      expect(toast()).toBe('v1 is live in General Medicine under Assessment.');
    });

    it('lets the owner approve a waiting version', () => {
      boot(formView({ state: 'WAITING_APPROVAL' }));
      tab('form');
      click('[data-act=approve]');
      http.expectOne(`${FORMS}/approve`).flush(formView({ state: 'LIVE', liveVersionNo: 1, chipVersionNo: 1, revision: 6 }));
      fixture.detectChanges();
      expect(q('[data-testid=state-chip]')!.textContent).toBe('v1 · live');
      expect(toast()).toBe('v1 is live. New visits get it at once.');
    });

    it('sends back with a reason, and only with a reason', () => {
      boot(formView({ state: 'WAITING_APPROVAL' }));
      tab('form');
      click('[data-act=send-back]');
      const send = () => q<HTMLButtonElement>('[data-act=send-back-now]')!;
      expect(send().disabled).toBe(true);
      const reason = q<HTMLInputElement>('[data-testid=form-settings] .lab input')!;
      reason.value = 'Add a Marathi label';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      send().click();
      const req = http.expectOne(`${FORMS}/send-back`);
      expect(req.request.body).toEqual({ revision: 4, reason: 'Add a Marathi label' });
      req.flush(formView({ state: 'SENT_BACK', sentBackReason: 'Add a Marathi label', revision: 5 }));
      fixture.detectChanges();
      expect(q('[data-testid=sent-back]')!.textContent).toBe('Sent back: Add a Marathi label');
    });

    it('retires a form', () => {
      boot(formView({ state: 'LIVE', liveVersionNo: 1 }));
      tab('form');
      click('[data-act=retire]');
      http.expectOne(`${FORMS}/retire`).flush(formView({ state: 'RETIRED', revision: 5 }));
      fixture.detectChanges();
      expect(toast()).toBe('Form retired. Records already filled keep it.');
    });
  });

  describe('Preview (SAH-59-5)', () => {
    it('shows the form as a doctor fills it, with the status line, Clear and a disabled save with its note', () => {
      boot();
      mode('preview');
      expect(q('app-form-fill')).not.toBeNull();
      expect(q('[data-testid=preview-status]')!.textContent).toBe('0 of 2 required fields filled');
      const save = q<HTMLButtonElement>('[data-act=preview-save]')!;
      expect(save.disabled).toBe(true);
      expect(q('#pv-note')!.textContent).toBe("Fill the form from a patient's EMR to save it");
      expect(q('[data-testid=state-chip]')).not.toBeNull();
    });

    it('runs show-if rules and live scores on example data, and Clear empties them', () => {
      boot();
      mode('preview');
      const yes = Array.from(qa('[data-field=ulcer] button')).find((b) => b.textContent!.trim() === 'Yes')!;
      yes.click();
      fixture.detectChanges();
      expect(q('[data-testid=score-total]')!.textContent).toBe('2');
      expect(q('[data-testid=preview-status]')!.textContent).toBe('1 of 2 required fields filled');
      click('[data-act=clear]');
      expect(q('[data-testid=score-total]')!.textContent).toBe('—');
    });

    it('hides the builder panels and keeps the owner in read-only mode', () => {
      boot();
      mode('preview');
      expect(q('.left')).toBeNull();
      expect(q('.right')).toBeNull();
    });
  });

  describe('Print (SAH-59-5)', () => {
    it('shows the clinic header, the standard words and the fields that print, and prints in the browser', () => {
      const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
      boot();
      mode('print');
      const sheet = q('.sheet')!.textContent!.replace(/\s+/g, ' ');
      expect(sheet).toContain('Test Clinic');
      expect(sheet).toContain('12 Test Road · 020 5555 0100');
      expect(sheet).toContain('Patient: ____________');
      expect(sheet).toContain('Age / sex');
      expect(sheet).toContain('UHID');
      expect(sheet).toContain('Monofilament: sites felt');
      expect(sheet).toContain('Signature · Dr. ____________ · Reg. no. ____________');
      click('[data-act=print]');
      expect(print).toHaveBeenCalledTimes(1);
    });

    it('chooses A4 or A5', () => {
      boot();
      mode('print');
      const buttons = qa('.ptools .seg button');
      buttons[1].click();
      fixture.detectChanges();
      expect(q('.sheet')!.classList).toContain('a5');
    });
  });

  describe('everyone but the owner', () => {
    it('can read and preview, and sees no palette, no save and no publish', () => {
      boot(formView({ canEdit: false, canApprove: false }));
      expect(q('[data-testid=readonly-note]')!.textContent).toContain('Only the owner can change forms.');
      expect(q('.left')).toBeNull();
      expect(q('[data-act=save-draft]')).toBeNull();
      expect(q('[data-act=publish]')).toBeNull();
      expect(q<HTMLInputElement>('[data-testid=form-name]')!.disabled).toBe(true);
      selectField('mono');
      expect(q<HTMLInputElement>('[data-testid=field-settings] label.lab input')!.disabled).toBe(true);
      expect(q('[data-act=delete]')).toBeNull();
      mode('preview');
      expect(q('app-form-fill')).not.toBeNull();
    });

    it('does not offer approval to a user who cannot approve', () => {
      boot(formView({ state: 'WAITING_APPROVAL', canEdit: false, canApprove: false }));
      tab('form');
      expect(q('[data-testid=approve-box]')).toBeNull();
    });
  });

  describe('leaving with unsaved changes', () => {
    it('asks first, and lets the router go when nothing is unsaved', () => {
      boot();
      expect(page.confirmLeave()).toBe(true);
      click('[data-type=DATE]');
      const answer = page.confirmLeave();
      expect(typeof answer).not.toBe('boolean');
    });
  });

  describe('states', () => {
    it('shows a message and a retry when the form cannot be opened', () => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          provideRouter([]),
          { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'f_abcdefghij' } } } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(FormBuilderPage);
      fixture.detectChanges();
      http.expectOne(FORMS).flush({ type: 'https://sahaj.in/problems/form-not-found', title: 'Not found', status: 404, detail: 'This form does not exist.' }, { status: 404, statusText: 'Not Found' });
      http.expectOne('/api/v1/opd/emr/departments').flush({ canEdit: true, departments: [] });
      http.expectOne('/api/v1/platform/clinic/profile').flush({ name: 'x' });
      fixture.detectChanges();
      el = fixture.nativeElement as HTMLElement;
      expect(q('[role=alert]')!.textContent).toContain('This form does not exist.');
      expect(q('[data-act=retry]')).not.toBeNull();
    });
  });
});
