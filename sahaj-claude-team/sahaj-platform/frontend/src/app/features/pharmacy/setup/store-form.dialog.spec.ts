import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { istToday } from '../../../shared/format/ist-date';
import { CANDIDATES, CENTRAL, OPD_STORE, store } from '../pharmacy.fixtures';
import { STORE_FORM_CONFIG, StoreFormData, StoreFormDialog } from './store-form.dialog';

const BASE = '/api/v1/pharmacy';

interface Internals {
  hodOptions(): { membershipId: string }[];
  form: StoreFormDialog['form'];
  typeOptions: string[];
  save(): void;
}

describe('StoreFormDialog', () => {
  let fixture: ComponentFixture<StoreFormDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(data: StoreFormData): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StoreFormDialog);
    fixture.detectChanges();
    // F-11: the candidates are always read, with the store's id only when the store already exists.
    const storeId = data.store?.id ?? null;
    for (const role of ['APPROVER', 'DELEGATE'] as const) {
      const req = http.expectOne(
        (r) => r.url === `${BASE}/staff-candidates` && r.params.get('storeRole') === role,
      );
      expect(req.request.params.get('storeId')).toBe(storeId);
      req.flush({ items: CANDIDATES.filter((c) => c.allowedStoreRoles.includes(role)) });
    }
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const me = () => fixture.componentInstance as unknown as Internals;
  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

  const FIRST: StoreFormData = {
    mode: 'create',
    hasCentral: false,
    centralStoreName: null,
  };
  const NEXT: StoreFormData = {
    mode: 'create',
    hasCentral: true,
   
    centralStoreName: 'Test Central Store',
  };

  afterEach(() => http.verify());

  it('has the sheet title that the dialog config names as the accessible name', async () => {
    const el = await open(NEXT);
    expect(STORE_FORM_CONFIG.ariaLabelledBy).toBe('store-form-title');
    expect(el.querySelector('#store-form-title')?.textContent).toContain('Add a store');
  });

  it('offers Central Store only while the unit has none, and defaults to it', async () => {
    await open(FIRST);
    expect(me().typeOptions).toEqual(['CENTRAL', 'PHARMACY', 'SUB_STORE', 'WARD']);
    expect(me().form.getRawValue().type).toBe('CENTRAL');
  });

  it('leaves Central Store out once the unit has one, and says Issues from the Central Store', async () => {
    const el = await open(NEXT);
    expect(me().typeOptions).toEqual(['PHARMACY', 'SUB_STORE', 'WARD']);
    expect(q(el, 'f-issues')?.textContent).toBe('Test Central Store');
  });

  it('offers Approver and Delegate for the very first Central Store, read without a store id (F-11)', async () => {
    const el = await open(FIRST);
    expect(q(el, 'no-approver-yet')).toBeNull();
    expect(q(el, 'f-hod')).toBeTruthy();
    expect(q(el, 'f-delegate')).toBeTruthy();
    expect(me().hodOptions().map((c) => c.membershipId)).toEqual(['m-doc', 'm-doc2']);
    expect(q(el, 'f-issues')?.textContent).toBe('Buys from vendors');
  });

  it('follows the type for Sale and Consumption until the owner changes them (NEW defaults)', async () => {
    await open(NEXT);
    const form = me().form;
    expect(form.getRawValue().saleAllowed).toBe(true);
    expect(form.getRawValue().consumptionAllowed).toBe(false);
    form.controls.type.setValue('WARD');
    expect(form.getRawValue().saleAllowed).toBe(false);
    expect(form.getRawValue().consumptionAllowed).toBe(true);
    form.controls.type.setValue('SUB_STORE');
    expect(form.getRawValue().consumptionAllowed).toBe(false);
    // Once the owner has touched Sale, the type no longer changes it.
    form.controls.saleAllowed.setValue(true);
    form.controls.saleAllowed.markAsDirty();
    form.controls.type.setValue('WARD');
    expect(form.getRawValue().saleAllowed).toBe(true);
  });

  it('needs a name and a code, with the story texts, and sends nothing', async () => {
    const el = await open(NEXT);
    me().save();
    await settle();
    expect(el.textContent).toContain('Enter the store name');
    expect(el.textContent).toContain('Enter the store code');
    http.expectNone(`${BASE}/stores`);
    expect(close).not.toHaveBeenCalled();
  });

  it('limits name to 60 characters and code to letters, digits and hyphen', async () => {
    const el = await open(NEXT);
    const form = me().form;
    form.patchValue({ name: 'x'.repeat(61), code: 'AB CD' });
    me().save();
    await settle();
    expect(form.controls.name.hasError('maxlength')).toBe(true);
    expect(form.controls.code.hasError('pattern')).toBe(true);
    expect(el.textContent).toContain('Use up to 10 letters, digits or hyphens');
    form.patchValue({ code: 'ABCDEFGHIJK' });
    expect(form.controls.code.hasError('pattern')).toBe(true);
    form.patchValue({ code: 'W3-B' });
    expect(form.controls.code.valid).toBe(true);
  });

  it('creates a store with the typed values and closes with the saved store', async () => {
    await open(NEXT);
    me().form.patchValue({ name: '  Test Ward 3B Store ', code: 'w3b', type: 'WARD' });
    me().save();
    const post = http.expectOne(`${BASE}/stores`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      name: 'Test Ward 3B Store',
      code: 'w3b',
      type: 'WARD',
      saleAllowed: false,
      consumptionAllowed: true,
      hodMembershipId: null,
      delegate: null,
    });
    post.flush(store({ id: 'store-ward', name: 'Test Ward 3B Store', type: 'WARD' }));
    expect(close).toHaveBeenCalledOnce();
  });

  it('always sends the approver and the delegate on create, null when none is chosen (F-11)', async () => {
    await open(FIRST);
    me().form.patchValue({ name: 'Test Central Store', code: 'CEN' });
    me().save();
    const post = http.expectOne(`${BASE}/stores`);
    expect(post.request.body).toMatchObject({ type: 'CENTRAL', hodMembershipId: null, delegate: null });
    expect(Object.keys(post.request.body as object)).toContain('hodMembershipId');
    expect(Object.keys(post.request.body as object)).toContain('delegate');
    post.flush(CENTRAL);
  });

  it('sends the chosen approver and delegate of the very first Central Store', async () => {
    await open(FIRST);
    const today = istToday();
    me().form.patchValue({
      name: 'Test Central Store',
      code: 'CEN',
      hodMembershipId: 'm-doc',
      delegateMembershipId: 'm-doc2',
      delegateFrom: today,
      delegateTo: today,
    });
    me().save();
    const post = http.expectOne(`${BASE}/stores`);
    expect(post.request.body).toMatchObject({
      type: 'CENTRAL',
      hodMembershipId: 'm-doc',
      delegate: { membershipId: 'm-doc2', from: today, to: today },
    });
    post.flush(CENTRAL);
  });

  it('refuses a delegate who is also the approver, and sends nothing', async () => {
    const el = await open(NEXT);
    me().form.patchValue({
      name: 'Test OT Store',
      code: 'OT',
      hodMembershipId: 'm-doc2',
      delegateMembershipId: 'm-doc2',
      delegateTo: '2026-12-01',
    });
    me().save();
    await settle();
    expect(el.textContent).toContain('The delegate must be a different person from the approver');
    http.expectNone(`${BASE}/stores`);
  });

  it('needs the end date of a delegate, and refuses one over 90 days or in the past', async () => {
    await open(NEXT);
    const form = me().form;
    form.patchValue({ name: 'Test OT Store', code: 'OT', delegateMembershipId: 'm-doc2', delegateFrom: istToday() });
    me().save();
    expect(form.controls.delegateTo.errors).toEqual({ server: 'Enter the end date' });
    form.patchValue({ delegateTo: '2000-01-01' });
    me().save();
    expect(form.controls.delegateTo.errors).toEqual({ server: 'The end date cannot be in the past' });
    form.patchValue({ delegateTo: '2999-01-01' });
    me().save();
    expect(form.controls.delegateTo.errors).toEqual({ server: 'A delegation can be at most 90 days' });
    http.expectNone(`${BASE}/stores`);
  });

  it('sends the delegate with its dates', async () => {
    await open(NEXT);
    const today = istToday();
    me().form.patchValue({
      name: 'Test OT Store',
      code: 'OT',
      hodMembershipId: 'm-doc',
      delegateMembershipId: 'm-doc2',
      delegateFrom: today,
      delegateTo: today,
    });
    me().save();
    const post = http.expectOne(`${BASE}/stores`);
    expect((post.request.body as { delegate: unknown }).delegate).toEqual({
      membershipId: 'm-doc2',
      from: today,
      to: today,
    });
    post.flush(store());
  });

  it('puts a server field message on the field and keeps what was typed (409 name in use)', async () => {
    const el = await open(NEXT);
    me().form.patchValue({ name: 'Test OPD Store', code: 'OPD2' });
    me().save();
    http.expectOne(`${BASE}/stores`).flush(
      {
        type: 'https://sahaj.in/problems/store-name-in-use',
        title: 'Name already used',
        status: 409,
        detail: 'That store name is already used',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(q(el, 'store-form-error')?.textContent).toContain('That store name is already used');
    expect(me().form.getRawValue().name).toBe('Test OPD Store');
    expect(close).not.toHaveBeenCalled();
  });

  it('shows the open-import sentence when the code of a store with an open file is changed (409)', async () => {
    const el = await open(NEXT);
    me().form.patchValue({ name: 'Test OPD Store', code: 'OPD2' });
    me().save();
    http.expectOne(`${BASE}/stores`).flush(
      {
        type: 'https://sahaj.in/problems/open-import',
        title: 'Opening stock file is open',
        status: 409,
        detail: 'Finish or reject the opening stock file of this store first.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(q(el, 'store-form-error')?.textContent).toContain('Finish or reject the opening stock file of this store first.');
    expect(me().form.getRawValue().code).toBe('OPD2');
    expect(close).not.toHaveBeenCalled();
  });

  it('maps a 400 field error to its control', async () => {
    const el = await open(NEXT);
    me().form.patchValue({ name: 'Test OT Store', code: 'OT' });
    me().save();
    http.expectOne(`${BASE}/stores`).flush(
      {
        type: 'https://sahaj.in/problems/validation',
        title: 'Check the form',
        status: 400,
        fieldErrors: [{ field: 'code', message: 'Enter the store code' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(me().form.controls.code.errors).toEqual({ server: 'Enter the store code' });
    expect(el.textContent).toContain('Enter the store code');
  });

  it('says the standard line when the save cannot reach the server', async () => {
    const el = await open(NEXT);
    me().form.patchValue({ name: 'Test OT Store', code: 'OT' });
    me().save();
    http.expectOne(`${BASE}/stores`).error(new ProgressEvent('error'));
    await settle();
    expect(q(el, 'store-form-error')?.textContent).toContain(
      'Could not save. Check your connection and try again.',
    );
  });

  describe('edit', () => {
    it('shows the saved values, sends the version, and a changed delegate keeps its dates', async () => {
      await open({ mode: 'edit', store: OPD_STORE, hasCentral: true, centralStoreName: 'Test Central Store' });
      const form = me().form;
      expect(form.getRawValue()).toMatchObject({
        name: 'Test OPD Store',
        code: 'OPD',
        type: 'SUB_STORE',
        hodMembershipId: 'm-doc',
        delegateMembershipId: 'm-doc2',
        delegateTo: '2026-11-30',
      });
      expect(me().typeOptions).toEqual(['PHARMACY', 'SUB_STORE', 'WARD']);
      form.patchValue({ delegateTo: istToday() });
      form.patchValue({ delegateFrom: istToday() });
      me().save();
      const put = http.expectOne(`${BASE}/stores/store-opd`);
      expect(put.request.method).toBe('PUT');
      expect((put.request.body as { version: number }).version).toBe(4);
      put.flush(OPD_STORE);
      expect(close).toHaveBeenCalledOnce();
    });

    it('sends every contract-required field of a store update, null included', async () => {
      await open({ mode: 'edit', store: { ...OPD_STORE, hod: null, delegate: null }, hasCentral: true, centralStoreName: 'Test Central Store' });
      me().save();
      const put = http.expectOne(`${BASE}/stores/store-opd`);
      expect(Object.keys(put.request.body as object).sort()).toEqual(
        ['code', 'consumptionAllowed', 'delegate', 'hodMembershipId', 'name', 'saleAllowed', 'type', 'version'],
      );
      expect(put.request.body).toMatchObject({ hodMembershipId: null, delegate: null });
      put.flush(OPD_STORE);
    });

    it('shows the delegate dates as "30 Nov 2026" and not in the browser locale format', async () => {
      const el = await open({ mode: 'edit', store: OPD_STORE, hasCentral: true, centralStoreName: 'Test Central Store' });
      expect(el.querySelector('input[type="date"]')).toBeNull();
      expect((q(el, 'f-delegate-to') as HTMLInputElement).value).toBe('30 Nov 2026');
      expect(me().form.getRawValue().delegateTo).toBe('2026-11-30');
    });

    it('locks type and code when the store has stock', async () => {
      const locked = store({ ...OPD_STORE, hasStock: true, lockedFields: ['TYPE', 'CODE', 'ISSUES_FROM'] });
      const el = await open({ mode: 'edit', store: locked, hasCentral: true, centralStoreName: null });
      expect(me().form.controls.type.disabled).toBe(true);
      expect(me().form.controls.code.disabled).toBe(true);
      expect(q(el, 'form-locked-note')?.textContent).toContain(
        'Type, Issues from and code are locked because this store has stock.',
      );
    });

    it('does not let the Central Store change its type', async () => {
      await open({ mode: 'edit', store: CENTRAL, hasCentral: true, centralStoreName: 'Test Central Store' });
      expect(me().typeOptions).toEqual(['CENTRAL']);
      expect(me().form.controls.type.disabled).toBe(true);
    });

    it('drops an ended delegate from the form, so saving ends it', async () => {
      const ended = store({ ...OPD_STORE, delegate: { ...OPD_STORE.delegate!, state: 'ENDED' } });
      await open({ mode: 'edit', store: ended, hasCentral: true, centralStoreName: null });
      expect(me().form.getRawValue().delegateMembershipId).toBe('');
    });
  });

  it('is a column capped by the screen whose body scrolls, so Save and Cancel stay in reach (QA-78-5)', async () => {
    const el = await open(NEXT);
    const host = getComputedStyle(fixture.nativeElement as HTMLElement);
    expect(host.display).toBe('flex');
    expect(host.flexDirection).toBe('column');
    expect(host.maxHeight).toMatch(/^92d?vh$/);
    const body = getComputedStyle(el.querySelector('.sheet-b') as HTMLElement);
    expect(body.overflowY).toBe('auto');
    expect(body.minHeight).toBe('0px');
    expect(getComputedStyle(el.querySelector('.sheet-h') as HTMLElement).flexShrink).toBe('0');
    expect(getComputedStyle(el.querySelector('.sheet-f') as HTMLElement).flexShrink).toBe('0');
  });
});
