import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { OWNER_CONTEXT, PHARMACIST_CONTEXT } from '../pharmacy.fixtures';
import { ACTIVE_VENDOR, BLOCKED_VENDOR, CONSUMABLES_VENDOR, vendor } from '../pharmacy-c.fixtures';
import { PharmacyContext, Vendor } from '../pharmacy.model';
import { VendorsSection } from './vendors.section';

const BASE = '/api/v1/pharmacy';
const url = (r: { url: string }) => r.url === `${BASE}/vendors`;

function page(items: Vendor[], total = items.length, pageNo = 0) {
  return { items, total, page: pageNo, size: 25 };
}

describe('VendorsSection', () => {
  let fixture: ComponentFixture<VendorsSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, first = page([ACTIVE_VENDOR, BLOCKED_VENDOR, CONSUMABLES_VENDOR])) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(VendorsSection);
    fixture.detectChanges();
    const request = http.expectOne(url);
    expect(request.request.params.has('status')).toBe(false);
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('25');
    request.flush(first);
    await settle();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  const reader = (): PharmacyContext => ({ ...PHARMACIST_CONTEXT, capabilities: ['SETUP_VIEW'] });

  function dialogReturns(value: unknown) {
    return vi.spyOn(TestBed.inject(MatDialog), 'open').mockReturnValue({ afterClosed: () => of(value) } as never);
  }

  afterEach(() => http.verify());

  it('draws the card "Vendors / suppliers" with the mockup columns and the note', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(el.querySelector('#vm-h')?.textContent).toBe('Vendors / suppliers');
    const heads = Array.from(el.querySelectorAll('th')).map((h) => h.textContent?.trim());
    expect(heads.slice(0, 7)).toEqual([
      'Vendor',
      'GSTIN',
      'Drug licence',
      'Credit',
      'Products',
      'Supplies to',
      'Status',
    ]);
    expect(text(el, 'vendors-note')).toBe(
      'A vendor with an expired drug licence is blocked from new purchase orders automatically.',
    );
  });

  it('shows the licence, the Blocked chip from the server and Not required for consumables', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(text(el, 'vendor-licence-vendor-1')).toBe('TEST-DL-0002 · till 31 Mar 2028');
    expect(text(el, 'vendor-licence-vendor-2')).toBe('TEST-DL-0003 · expired 31 Oct 2026');
    expect(q(el, 'vendor-licence-vendor-2')?.classList.contains('warn-text')).toBe(true);
    expect(text(el, 'vendor-status-vendor-1')).toBe('Active');
    expect(text(el, 'vendor-status-vendor-2')).toBe('Blocked · licence expired');
    expect(text(el, 'vendor-licence-vendor-3')).toBe('Not required (consumables)');
    expect(q(el, 'vendor-vendor-3')?.textContent).toContain('15 days');
    expect(q(el, 'vendor-vendor-3')?.textContent).toContain('Not set');
  });

  it('gives the owner + Vendor, Edit and Switch off', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(q(el, 'add-vendor')).not.toBeNull();
    expect(q(el, 'edit-vendor-vendor-2')).not.toBeNull();
    expect(q(el, 'switch-off-vendor-vendor-2')).not.toBeNull();
  });

  it('shows a reader of setup the table and no button', async () => {
    const el = await open({ ...PHARMACIST_CONTEXT, capabilities: ['SETUP_VIEW'] });
    expect(q(el, 'vendor-table')).not.toBeNull();
    expect(q(el, 'add-vendor')).toBeNull();
    expect(el.querySelector('[data-testid^="edit-vendor"]')).toBeNull();
    expect(el.querySelector('[data-testid^="switch-off-vendor"]')).toBeNull();
  });

  it('says "No vendors yet. Add the suppliers you buy from." to the owner', async () => {
    const el = await open(OWNER_CONTEXT, page([]));
    expect(text(el, 'vendors-empty')).toBe('No vendors yet. Add the suppliers you buy from.');
    expect(q(el, 'add-vendor')).not.toBeNull();
  });

  it('says only "No vendors yet." to a reader', async () => {
    const el = await open({ ...PHARMACIST_CONTEXT, capabilities: ['SETUP_VIEW'] }, page([]));
    expect(text(el, 'vendors-empty')).toBe('No vendors yet.');
    expect(q(el, 'add-vendor')).toBeNull();
  });

  it('shows "Could not load. Try again." with a Try again button when the read fails', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(OWNER_CONTEXT);
    fixture = TestBed.createComponent(VendorsSection);
    fixture.detectChanges();
    http.expectOne(url).error(new ProgressEvent('error'));
    await settle();
    const el = fixture.nativeElement as HTMLElement;
    expect(text(el, 'vendors-error')).toBe('Could not load. Try again.');
    expect(q(el, 'vendor-table')).toBeNull();
  });

  it('reads the list again with the filter the owner chose', async () => {
    const el = await open(OWNER_CONTEXT);
    (fixture.componentInstance as unknown as { setFilter(v: string): void }).setFilter('INACTIVE');
    const request = http.expectOne(url);
    expect(request.request.params.get('status')).toBe('INACTIVE');
    request.flush(page([]));
    await settle();
    expect(text(el, 'vendors-none')).toBe('No vendors in this view.');
  });

  it('adds a vendor through the sheet, then reads the list again and tells the tab', async () => {
    await open(OWNER_CONTEXT);
    const changed = vi.fn();
    fixture.componentInstance.changed.subscribe(changed);
    const spy = dialogReturns(ACTIVE_VENDOR);
    (q(fixture.nativeElement, 'add-vendor') as HTMLButtonElement).click();
    expect(spy).toHaveBeenCalledOnce();
    expect(spy.mock.calls[0][1]).toMatchObject({ ariaLabelledBy: 'vendor-form-title' });
    http.expectOne(url).flush(page([ACTIVE_VENDOR]));
    await settle();
    expect(changed).toHaveBeenCalledOnce();
  });

  it('switches a vendor off only after the owner confirms', async () => {
    const el = await open(OWNER_CONTEXT);
    const spy = dialogReturns(false);
    (q(el, 'switch-off-vendor-vendor-1') as HTMLButtonElement).click();
    http.expectNone(`${BASE}/vendors/vendor-1/switch-off`);
    spy.mockReturnValue({ afterClosed: () => of(true) } as never);
    (q(el, 'switch-off-vendor-vendor-1') as HTMLButtonElement).click();
    const call = http.expectOne(`${BASE}/vendors/vendor-1/switch-off`);
    expect(call.request.method).toBe('POST');
    call.flush({ ...ACTIVE_VENDOR, status: 'INACTIVE' });
    http.expectOne(url).flush(page([BLOCKED_VENDOR]));
    await settle();
  });

  it('shows the server sentence when the switch off fails', async () => {
    const el = await open(OWNER_CONTEXT);
    dialogReturns(true);
    (q(el, 'switch-off-vendor-vendor-1') as HTMLButtonElement).click();
    http.expectOne(`${BASE}/vendors/vendor-1/switch-off`).error(new ProgressEvent('error'));
    await settle();
    expect(text(el, 'vendor-action-error')).toBe('Could not save. Check your connection and try again.');
  });

  describe('Switch on (contract 0.2.1, DEC-078 item 7)', () => {
    const OFF = vendor({ id: 'vendor-9', name: 'Test Switched Off (fake)', status: 'INACTIVE' });

    it('gives the owner Switch on for a switched-off vendor only, and no Switch off for it', async () => {
      const el = await open(OWNER_CONTEXT, page([ACTIVE_VENDOR, OFF]));
      expect(q(el, 'switch-on-vendor-vendor-9')?.textContent?.trim()).toBe('Switch on');
      expect(q(el, 'switch-on-vendor-vendor-9')?.getAttribute('aria-label')).toBe('Switch on Test Switched Off (fake)');
      expect(q(el, 'switch-off-vendor-vendor-9')).toBeNull();
      expect(q(el, 'switch-on-vendor-vendor-1')).toBeNull();
      expect(q(el, 'switch-off-vendor-vendor-1')).not.toBeNull();
      expect(text(el, 'vendor-status-vendor-9')).toBe('Inactive');
    });

    it('shows a reader no Switch on', async () => {
      const el = await open(reader(), page([OFF]));
      expect(el.querySelector('[data-testid^="switch-on-vendor"]')).toBeNull();
    });

    it('switches the vendor on with one POST and no body, then reads the list again and tells the tab', async () => {
      const el = await open(OWNER_CONTEXT, page([OFF]));
      const changed = vi.fn();
      fixture.componentInstance.changed.subscribe(changed);
      (q(el, 'switch-on-vendor-vendor-9') as HTMLButtonElement).click();
      const call = http.expectOne(`${BASE}/vendors/vendor-9/switch-on`);
      expect(call.request.method).toBe('POST');
      expect(call.request.body).toBeNull();
      call.flush({ ...OFF, status: 'ACTIVE' });
      http.expectOne(url).flush(page([{ ...OFF, status: 'ACTIVE' }]));
      await settle();
      expect(changed).toHaveBeenCalledOnce();
      expect(q(el, 'switch-on-vendor-vendor-9')).toBeNull();
      expect(text(el, 'vendor-status-vendor-9')).toBe('Active');
    });

    it('shows the status the server gives: Blocked when the licence has expired meanwhile', async () => {
      const el = await open(OWNER_CONTEXT, page([OFF]));
      (q(el, 'switch-on-vendor-vendor-9') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/vendors/vendor-9/switch-on`).flush({ ...OFF, status: 'BLOCKED', blocked: true });
      http.expectOne(url).flush(page([{ ...OFF, status: 'BLOCKED', blocked: true, licenceState: 'EXPIRED' }]));
      await settle();
      expect(text(el, 'vendor-status-vendor-9')).toBe('Blocked · licence expired');
    });

    it('shows the server sentence and keeps the row when the switch on fails (404 or 403)', async () => {
      const el = await open(OWNER_CONTEXT, page([OFF]));
      (q(el, 'switch-on-vendor-vendor-9') as HTMLButtonElement).click();
      http.expectOne(`${BASE}/vendors/vendor-9/switch-on`).flush(
        { type: 'https://sahaj.in/problems/not-found', title: 'Not found', status: 404, detail: 'This record was not found.' },
        { status: 404, statusText: 'Not Found', headers: { 'Content-Type': 'application/problem+json' } },
      );
      await settle();
      expect(text(el, 'vendor-action-error')).toBe('This record was not found.');
      expect(q(el, 'switch-on-vendor-vendor-9')).not.toBeNull();
    });
  });

  it('pages through the vendors', async () => {
    const el = await open(OWNER_CONTEXT, page([ACTIVE_VENDOR], 30));
    expect(text(el, 'vendor-range')).toBe('Showing 1–25 of 30');
    (q(el, 'vendors-next') as HTMLButtonElement).click();
    const next = http.expectOne(url);
    expect(next.request.params.get('page')).toBe('1');
    next.flush(page([BLOCKED_VENDOR], 30, 1));
    await settle();
    expect(text(el, 'vendor-range')).toBe('Showing 26–30 of 30');
  });
});
