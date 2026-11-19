import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { openingImport } from '../pharmacy.fixtures';
import { REJECT_IMPORT_CONFIG, RejectImportData, RejectImportDialog } from './reject-import.dialog';

const BASE = '/api/v1/pharmacy';
const DATA: RejectImportData = { storeId: 'store-central', importId: 'imp-1', storeName: 'Test Central Store' };
const URL = `${BASE}/stores/store-central/opening-stock-imports/imp-1/reject`;

interface Internals {
  form: RejectImportDialog['form'];
  save(): void;
}

describe('RejectImportDialog', () => {
  let fixture: ComponentFixture<RejectImportDialog>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  async function open(): Promise<HTMLElement> {
    close = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: DATA },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RejectImportDialog);
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

  afterEach(() => http.verify());

  it('names the sheet and the store', async () => {
    const el = await open();
    expect(REJECT_IMPORT_CONFIG.ariaLabelledBy).toBe('reject-title');
    expect(el.querySelector('#reject-title')?.textContent).toBe('Reject this file');
    expect(el.textContent).toContain('Test Central Store');
  });

  it('needs a reason from the list and sends nothing without one', async () => {
    const el = await open();
    me().save();
    await settle();
    expect(el.textContent).toContain('Choose a reason');
    http.expectNone(URL);
  });

  it('needs a note of at least 4 characters for Other, and shows the note field only then', async () => {
    const el = await open();
    expect(q(el, 'f-note')).toBeNull();
    me().form.patchValue({ reasonKey: 'OTHER', note: 'abc' });
    await settle();
    expect(q(el, 'f-note')).toBeTruthy();
    expect(q(el, 'note-hint')?.textContent?.trim()).toBe('Do not add patient details.');
    me().save();
    await settle();
    expect(el.textContent).toContain('Write at least 4 characters');
    http.expectNone(URL);
  });

  it('rejects with the reason and no note, and closes with the rejected file', async () => {
    await open();
    me().form.patchValue({ reasonKey: 'WRONG_QUANTITIES', note: 'ignored' });
    me().save();
    const post = http.expectOne(URL);
    expect(post.request.body).toEqual({ reasonKey: 'WRONG_QUANTITIES', note: null });
    const done = openingImport({ status: 'REJECTED', rejectReasonKey: 'WRONG_QUANTITIES' });
    post.flush(done);
    expect(close).toHaveBeenCalledWith(done);
  });

  it('sends the trimmed note for Other', async () => {
    await open();
    me().form.patchValue({ reasonKey: 'OTHER', note: '  Counts are old  ' });
    me().save();
    const post = http.expectOne(URL);
    expect(post.request.body).toEqual({ reasonKey: 'OTHER', note: 'Counts are old' });
    post.flush(openingImport({ status: 'REJECTED', rejectReasonKey: 'OTHER' }));
  });

  it('shows the server sentence and keeps the sheet open when the file is no longer waiting', async () => {
    const el = await open();
    me().form.patchValue({ reasonKey: 'WRONG_FILE' });
    me().save();
    http.expectOne(URL).flush(
      { type: 'https://sahaj.in/problems/import-state', title: 'State', status: 409, detail: 'This import can no longer be changed.' },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(q(el, 'reject-error')?.textContent).toContain('This import can no longer be changed.');
    expect(close).not.toHaveBeenCalled();
  });

  it('disables the button while the request runs', async () => {
    const el = await open();
    me().form.patchValue({ reasonKey: 'WRONG_FILE' });
    me().save();
    me().save();
    await settle();
    expect((q(el, 'confirm-reject') as HTMLButtonElement).disabled).toBe(true);
    http.expectOne(URL).flush(openingImport({ status: 'REJECTED' }));
  });

  it('is a column capped by the screen whose body scrolls, so Save and Cancel stay in reach (QA-78-5)', async () => {
    const el = await open();
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
