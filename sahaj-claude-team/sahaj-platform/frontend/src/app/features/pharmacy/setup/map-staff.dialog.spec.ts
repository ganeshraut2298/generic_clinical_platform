import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { istToday } from '../../../shared/format/ist-date';
import { CANDIDATES, STORE_STAFF_MAPPING, mapping } from '../pharmacy.fixtures';
import { MAP_STAFF_CONFIG, MapStaffData, MapStaffDialog } from './map-staff.dialog';

const BASE = '/api/v1/pharmacy';

interface Internals {
  form: MapStaffDialog['form'];
  roles(): string[];
  people(): { membershipId: string }[];
  save(): void;
}

describe('MapStaffDialog', () => {
  let fixture: ComponentFixture<MapStaffDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(data: MapStaffData): Promise<HTMLElement> {
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
    fixture = TestBed.createComponent(MapStaffDialog);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === `${BASE}/staff-candidates`);
    expect(req.request.params.get('storeId')).toBe(data.storeId);
    req.flush({ items: CANDIDATES });
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
  const NEW: MapStaffData = { storeId: 'store-opd', storeName: 'Test OPD Store' };

  afterEach(() => http.verify());

  it('lists only people who are not yet in the store', async () => {
    await open(NEW);
    expect(me().people().map((p) => p.membershipId)).toEqual(['m-doc2', 'm-ss2']);
  });

  it('offers a Store staff member only Store user and Central Store user', async () => {
    await open(NEW);
    me().form.controls.membershipId.setValue('m-ss2');
    expect(me().roles()).toEqual(['STORE_USER', 'CENTRAL_STORE_USER']);
    expect(me().roles()).not.toContain('APPROVER');
    expect(me().roles()).not.toContain('DELEGATE');
  });

  it('starts a new person as Store user, and offers a doctor Approver and Delegate too', async () => {
    await open(NEW);
    me().form.controls.membershipId.setValue('m-doc2');
    expect(me().form.getRawValue().storeRole).toBe('STORE_USER');
    expect(me().roles()).toEqual(['STORE_USER', 'APPROVER', 'DELEGATE']);
  });

  it('says what is missing and sends nothing', async () => {
    const el = await open(NEW);
    me().save();
    await settle();
    expect(el.textContent).toContain('Choose a team member');
    http.expectNone(`${BASE}/stores/store-opd/staff`);
  });

  it('maps a Store staff member as Store user, without any access columns in the request', async () => {
    await open(NEW);
    me().form.controls.membershipId.setValue('m-ss2');
    me().save();
    const post = http.expectOne(`${BASE}/stores/store-opd/staff`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      membershipId: 'm-ss2',
      storeRole: 'STORE_USER',
      delegateFrom: null,
      delegateTo: null,
    });
    post.flush(STORE_STAFF_MAPPING);
    expect(close).toHaveBeenCalledOnce();
  });

  it('needs the dates of a Delegate approver, and refuses an end date in the past', async () => {
    await open(NEW);
    const form = me().form;
    form.controls.membershipId.setValue('m-doc2');
    form.controls.storeRole.setValue('DELEGATE');
    me().save();
    expect(form.controls.delegateTo.errors).toEqual({ server: 'Enter the end date' });
    form.patchValue({ delegateTo: '2000-01-01' });
    me().save();
    expect(form.controls.delegateTo.errors).toEqual({ server: 'The end date cannot be in the past' });
    http.expectNone(`${BASE}/stores/store-opd/staff`);
  });

  it('sends the dates of a Delegate approver', async () => {
    await open(NEW);
    const today = istToday();
    me().form.controls.membershipId.setValue('m-doc2');
    me().form.controls.storeRole.setValue('DELEGATE');
    me().form.patchValue({ delegateFrom: today, delegateTo: today });
    me().save();
    const post = http.expectOne(`${BASE}/stores/store-opd/staff`);
    expect(post.request.body).toMatchObject({ storeRole: 'DELEGATE', delegateFrom: today, delegateTo: today });
    post.flush(mapping({ storeRole: 'DELEGATE' }));
  });

  it('shows delegate dates as "24 Oct 2026" with a date picker, keeps YYYY-MM-DD, and names the sheet', async () => {
    const el = await open(NEW);
    me().form.controls.membershipId.setValue('m-doc2');
    me().form.controls.storeRole.setValue('DELEGATE');
    me().form.patchValue({ delegateFrom: '2026-10-24', delegateTo: '2026-11-30' });
    await settle();
    expect(el.querySelector('input[type="date"]')).toBeNull();
    expect((el.querySelector('[data-testid="f-from"]') as HTMLInputElement).value).toBe('24 Oct 2026');
    expect((el.querySelector('[data-testid="f-to"]') as HTMLInputElement).value).toBe('30 Nov 2026');
    expect(el.querySelectorAll('mat-datepicker-toggle button').length).toBe(2);
    expect(MAP_STAFF_CONFIG.ariaLabelledBy).toBe('map-title');
    expect(el.querySelector('#map-title')).toBeTruthy();
  });

  it('shows the server refusal for a person already in the store and keeps the sheet open', async () => {
    const el = await open(NEW);
    me().form.controls.membershipId.setValue('m-doc2');
    me().save();
    http.expectOne(`${BASE}/stores/store-opd/staff`).flush(
      {
        type: 'https://sahaj.in/problems/already-mapped',
        title: 'Already mapped',
        status: 409,
        detail: 'That person is already in this store',
      },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(q(el, 'map-error')?.textContent).toContain('That person is already in this store');
    expect(close).not.toHaveBeenCalled();
  });

  it('says the standard line when the save cannot reach the server', async () => {
    const el = await open(NEW);
    me().form.controls.membershipId.setValue('m-doc2');
    me().save();
    http.expectOne(`${BASE}/stores/store-opd/staff`).error(new ProgressEvent('error'));
    await settle();
    expect(q(el, 'map-error')?.textContent).toContain('Could not save. Check your connection and try again.');
  });

  it('shows a 400 field message from the server as a message in the sheet', async () => {
    const el = await open(NEW);
    me().form.controls.membershipId.setValue('m-ss2');
    me().form.controls.storeRole.setValue('CENTRAL_STORE_USER');
    me().save();
    http.expectOne(`${BASE}/stores/store-opd/staff`).flush(
      {
        type: 'https://sahaj.in/problems/validation',
        title: 'Check the form',
        status: 400,
        fieldErrors: [{ field: 'storeRole', message: 'Central Store user is only for the Central Store' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(el.textContent).toContain('Central Store user is only for the Central Store');
  });

  describe('change', () => {
    it('fixes the person, sends the version and the new role', async () => {
      const el = await open({ ...NEW, mapping: STORE_STAFF_MAPPING });
      expect(q(el, 'f-person-fixed')?.textContent).toBe('Test Store Staff One');
      me().form.controls.storeRole.setValue('STORE_USER');
      me().save();
      const put = http.expectOne(`${BASE}/stores/store-opd/staff/map-3`);
      expect(put.request.method).toBe('PUT');
      expect(put.request.body).toEqual({
        version: 1,
        storeRole: 'STORE_USER',
        delegateFrom: null,
        delegateTo: null,
      });
      put.flush(STORE_STAFF_MAPPING);
      expect(close).toHaveBeenCalledOnce();
    });
  });

  it('is a column capped by the screen whose body scrolls, so Save and Cancel stay in reach (QA-78-5)', async () => {
    const el = await open(NEW);
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
