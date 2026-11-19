import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyContextStore } from '../pharmacy-context.store';
import {
  CENTRAL,
  OPD_STORE,
  OWNER_CONTEXT,
  PHARMACIST_CONTEXT,
  openingImport,
  openingLine,
} from '../pharmacy.fixtures';
import { OpeningStockImport, PharmacyContext } from '../pharmacy.model';
import { OpeningStockSection } from './opening-stock.section';

const BASE = '/api/v1/pharmacy';
const HEADER = 'store code,product code,batch,expiry date,MRP,purchase rate,quantity';
const ALL = `${BASE}/opening-stock-imports`;
const STORE_LIST = `${BASE}/stores/store-central/opening-stock-imports`;

const PHARMACIST_UPLOADER: PharmacyContext = {
  ...PHARMACIST_CONTEXT,
  stores: [
    {
      ...PHARMACIST_CONTEXT.stores[0],
      storeId: 'store-central',
      name: 'Test Central Store',
      access: { ...PHARMACIST_CONTEXT.stores[0].access, uploadOpeningStock: true },
    },
  ],
  workingStoreId: 'store-central',
  mustPickWorkingStore: false,
};

function csv(rows: number): string {
  const lines = Array.from({ length: rows }, (_, i) => `CEN,G-000101,B${i},31-12-2027,45.50,30.25,10`);
  return `${HEADER}\n${lines.join('\n')}\n`;
}

interface Internals {
  accept(name: string, text: string): void;
  upload(): void;
  chooseStore(id: string): void;
}

describe('OpeningStockSection', () => {
  let fixture: ComponentFixture<OpeningStockSection>;
  let http: HttpTestingController;

  async function open(context: PharmacyContext, imports: OpeningStockImport[] = []): Promise<HTMLElement> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PharmacyContextStore).start();
    http.expectOne(`${BASE}/context`).flush(context);
    fixture = TestBed.createComponent(OpeningStockSection);
    fixture.detectChanges();
    if (context.caller.setupAccess === 'EDIT') {
      http.expectOne(`${BASE}/stores`).flush({ items: [CENTRAL, OPD_STORE], centralStoreId: 'store-central' });
      http.expectOne((r) => r.url === ALL).flush({ items: imports, total: imports.length, page: 0, size: 20 });
    } else {
      // A person with one store has it chosen at once and reads its files; one with none reads nothing.
      for (const r of http.match((x) => x.url === STORE_LIST)) {
        r.flush({ items: imports, total: imports.length, page: 0, size: 20 });
      }
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
  const text = (el: HTMLElement, id: string) => q(el, id)?.textContent?.replace(/\s+/g, ' ').trim();

  /** Answers the review panel that opens after an upload. */
  function answerReview(item: OpeningStockImport, lines = [openingLine()]): void {
    http.expectOne(`${STORE_LIST}/${item.id}`).flush(item);
    http
      .expectOne((r) => r.url === `${STORE_LIST}/${item.id}/lines`)
      .flush({ items: lines, total: lines.length, page: 0, size: 50 });
  }

  function problem(status: number, slug: string, detail: string, extra: object = {}) {
    return [
      { type: `https://sahaj.in/problems/${slug}`, title: slug, status, detail, ...extra },
      { status, statusText: slug },
    ] as const;
  }

  afterEach(() => http.verify());

  it('gives the owner every store, the template button and the files of every store', async () => {
    const el = await open(OWNER_CONTEXT, [openingImport()]);
    expect(el.querySelector('#os-h')?.textContent).toBe('Opening stock');
    expect(q(el, 'download-template')?.textContent?.trim()).toBe('Download template');
    expect(q(el, 'os-store')).toBeTruthy();
    const rows = el.querySelectorAll('[data-testid="imports-table"] tbody tr');
    expect(rows.length).toBe(1);
    const cells = Array.from(rows[0].querySelectorAll('td')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());
    expect(cells.slice(0, 6)).toEqual([
      'Test Central Store',
      'Waiting for approval',
      'Test Pharmacist One',
      '17 Nov 2026',
      '412',
      '₹ 12,50,000.00',
    ]);
    expect(q(el, 'review-imp-1')?.textContent?.trim()).toBe('Review');
  });

  it('says what to do next when there are no files', async () => {
    const el = await open(OWNER_CONTEXT);
    expect(text(el, 'imports-empty')).toBe('No files yet. Download the template, fill it and upload it.');
  });

  it('lets the owner show only the files that wait for approval', async () => {
    await open(OWNER_CONTEXT, [openingImport()]);
    const section = fixture.componentInstance as unknown as { setFilter(v: string): void };
    section.setFilter('WAITING_APPROVAL');
    const req = http.expectOne((r) => r.url === ALL);
    expect(req.request.params.get('status')).toBe('WAITING_APPROVAL');
    req.flush({ items: [], total: 0, page: 0, size: 20 });
  });

  it('downloads the template as a file and says so when it cannot', async () => {
    const el = await open(OWNER_CONTEXT);
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test');
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    (q(el, 'download-template') as HTMLButtonElement).click();
    const req = http.expectOne(`${BASE}/opening-stock/template`);
    expect(req.request.responseType).toBe('text');
    req.flush(`${HEADER}\n#EXAMPLE,G-000101,TESTB001,31-12-2027,45.50,30.25,100\n`);
    expect(click).toHaveBeenCalledOnce();
    expect(create).toHaveBeenCalledOnce();
    expect(revoke).toHaveBeenCalledWith('blob:test');
    (q(el, 'download-template') as HTMLButtonElement).click();
    http.expectOne(`${BASE}/opening-stock/template`).error(new ProgressEvent('error'));
    await settle();
    expect(text(el, 'template-error')).toBe('Could not download the template. Try again.');
    vi.restoreAllMocks();
  });

  it('refuses a file that is not the template, with the one sentence, and keeps Upload off', async () => {
    const el = await open(OWNER_CONTEXT);
    me().accept('stock.xlsx', csv(2));
    await settle();
    expect(text(el, 'file-error')).toBe('Use the Sahaj template. Save the file as CSV.');
    expect((q(el, 'upload') as HTMLButtonElement).disabled).toBe(true);
    me().accept('stock.csv', 'a,b\n1,2');
    await settle();
    expect(text(el, 'file-error')).toBe('Use the Sahaj template. Save the file as CSV.');
  });

  it('shows the file name and the number of rows, and turns Upload on once a store is chosen', async () => {
    const el = await open(OWNER_CONTEXT);
    me().accept('stock.csv', csv(3));
    await settle();
    expect(text(el, 'file-name')).toBe('stock.csv · 3 rows');
    expect((q(el, 'upload') as HTMLButtonElement).disabled).toBe(true);
    me().chooseStore('store-central');
    await settle();
    expect((q(el, 'upload') as HTMLButtonElement).disabled).toBe(false);
  });

  it('sends the file in batches of 200 and submits it, then opens the review of the waiting file', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(250));
    me().upload();
    const start = http.expectOne(STORE_LIST);
    expect(start.request.method).toBe('POST');
    const startBody = start.request.body as { clientKey: string; columns: string[]; rowCount: number };
    expect(startBody.rowCount).toBe(250);
    expect(startBody.columns).toEqual(['store code', 'product code', 'batch', 'expiry date', 'MRP', 'purchase rate', 'quantity']);
    expect(startBody.clientKey).toMatch(/^[0-9a-f-]{36}$/);
    start.flush(openingImport({ id: 'imp-9', status: 'DRAFT', rowCount: 250, receivedRows: 0 }), { status: 201, statusText: 'Created' });

    const first = http.expectOne(`${STORE_LIST}/imp-9/lines`);
    const firstRows = (first.request.body as { rows: { rowNo: number }[] }).rows;
    expect(firstRows.length).toBe(200);
    expect(firstRows[0]).toEqual({
      rowNo: 1,
      storeCode: 'CEN',
      productCode: 'G-000101',
      batch: 'B0',
      expiryDate: '31-12-2027',
      mrp: '45.50',
      purchaseRate: '30.25',
      quantity: '10',
    });
    first.flush({ receivedRows: 200, rowCount: 250, issues: [] });
    const second = http.expectOne(`${STORE_LIST}/imp-9/lines`);
    expect((second.request.body as { rows: { rowNo: number }[] }).rows.map((r) => r.rowNo)[0]).toBe(201);
    expect((second.request.body as { rows: unknown[] }).rows.length).toBe(50);
    second.flush({ receivedRows: 250, rowCount: 250, issues: [] });

    const submit = http.expectOne(`${STORE_LIST}/imp-9/submit`);
    expect(submit.request.method).toBe('POST');
    expect(submit.request.body).toBeNull();
    const waiting = openingImport({ id: 'imp-9', rowCount: 250, lineCount: 250 });
    submit.flush(waiting);
    http.expectOne((r) => r.url === ALL).flush({ items: [waiting], total: 1, page: 0, size: 20 });
    fixture.detectChanges();
    answerReview(waiting);
    await settle();
    expect(q(el, 'import-review')).toBeTruthy();
    expect(text(el, 'review-status')).toBe('Waiting for approval');
    expect(q(el, 'file-name')).toBeNull();
  });

  it('opens the file without sending rows when the start answers with a file that is no longer a draft', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(250));
    me().upload();
    const waiting = openingImport({ id: 'imp-9', status: 'WAITING_APPROVAL', rowCount: 250, lineCount: 250 });
    http.expectOne(STORE_LIST).flush(waiting);
    // A retry after a lost submit response: no lines and no submit are sent again.
    http.expectNone(`${STORE_LIST}/imp-9/lines`);
    http.expectNone(`${STORE_LIST}/imp-9/submit`);
    http.expectOne((r) => r.url === ALL).flush({ items: [waiting], total: 1, page: 0, size: 20 });
    fixture.detectChanges();
    answerReview(waiting);
    await settle();
    expect(q(el, 'import-review')).toBeTruthy();
    expect(q(el, 'upload-error')).toBeNull();
    expect(q(el, 'file-name')).toBeNull();
  });

  it('stops the upload when the section is left, and sends nothing more', async () => {
    await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(250));
    me().upload();
    http.expectOne(STORE_LIST).flush(openingImport({ id: 'imp-9', status: 'DRAFT' }));
    const first = http.expectOne(`${STORE_LIST}/imp-9/lines`);
    fixture.destroy();
    expect(first.cancelled).toBe(true);
    http.expectNone(`${STORE_LIST}/imp-9/lines`);
    http.expectNone(`${STORE_LIST}/imp-9/submit`);
  });

  it('refuses a file over 2 MB with the one sentence, without reading it', async () => {
    const el = await open(OWNER_CONTEXT);
    const big = new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'stock.csv', { type: 'text/csv' });
    const read = vi.spyOn(big, 'text');
    (me() as unknown as { onFile(e: unknown): void }).onFile({ target: { files: [big], value: 'x' } });
    await settle();
    expect(text(el, 'file-error')).toBe('Use the Sahaj template. Save the file as CSV.');
    expect(read).not.toHaveBeenCalled();
    expect(q(el, 'file-name')).toBeNull();
  });

  it('refuses a file with a value beyond the 7 columns and names only the row', async () => {
    const el = await open(OWNER_CONTEXT);
    me().accept('stock.csv', `${HEADER}
CEN,G-1,B1,31-12-2027,10,8,1,Some Patient
`);
    await settle();
    expect(text(el, 'file-error')).toBe('Row 1: there are more than 7 columns. Fix the file and upload it again.');
    expect(el.textContent).not.toContain('Some Patient');
  });

  it('sends the header cells found in the file as the columns', async () => {
    await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', `  Store Code ,Product code,Batch,Expiry date,Mrp,Purchase rate,Quantity
CEN,G-1,B1,31-12-2027,10,8,1
`);
    me().upload();
    const start = http.expectOne(STORE_LIST);
    expect((start.request.body as { columns: string[] }).columns).toEqual([
      'Store Code', 'Product code', 'Batch', 'Expiry date', 'Mrp', 'Purchase rate', 'Quantity',
    ]);
    start.error(new ProgressEvent('error'));
  });

  it('shows the progress while sending, and disables the buttons', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(250));
    me().upload();
    http.expectOne(STORE_LIST).flush(openingImport({ id: 'imp-9', status: 'DRAFT' }));
    http.expectOne(`${STORE_LIST}/imp-9/lines`).flush({ receivedRows: 200, rowCount: 250, issues: [] });
    await settle();
    expect(text(el, 'upload-progress')).toContain('Sending rows: 200 of 250');
    expect((q(el, 'upload') as HTMLButtonElement).disabled).toBe(true);
    expect((q(el, 'choose-file') as HTMLButtonElement).disabled).toBe(true);
    http.expectOne(`${STORE_LIST}/imp-9/lines`).flush({ receivedRows: 250, rowCount: 250, issues: [] });
    http.expectOne(`${STORE_LIST}/imp-9/submit`).error(new ProgressEvent('error'));
  });

  it('says "Could not upload. Try again." when the connection fails, keeps the file and retries with the same key', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(2));
    me().upload();
    const first = http.expectOne(STORE_LIST);
    const key = (first.request.body as { clientKey: string }).clientKey;
    first.error(new ProgressEvent('error'));
    await settle();
    expect(text(el, 'upload-error')).toBe('Could not upload. Try again.');
    expect(text(el, 'file-name')).toBe('stock.csv · 2 rows');
    expect((q(el, 'upload') as HTMLButtonElement).disabled).toBe(false);
    me().upload();
    const second = http.expectOne(STORE_LIST);
    expect((second.request.body as { clientKey: string }).clientKey).toBe(key);
    second.error(new ProgressEvent('error'));
  });

  it('shows the server sentence when the store already has approved opening stock', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(2));
    me().upload();
    http
      .expectOne(STORE_LIST)
      .flush(...problem(409, 'opening-stock-already-approved', 'Opening stock was already approved for this store.'));
    await settle();
    expect(text(el, 'upload-error')).toBe('Opening stock was already approved for this store.');
  });

  it('shows "Too many files today. Try again tomorrow." when the 11th start of the day is refused (DEC-077)', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(2));
    me().upload();
    http
      .expectOne(STORE_LIST)
      .flush(...problem(409, 'import-limit', 'Too many files today. Try again tomorrow.'));
    await settle();
    expect(text(el, 'upload-error')).toBe('Too many files today. Try again tomorrow.');
    expect(text(el, 'file-name')).toBe('stock.csv · 2 rows');
    expect(q(el, 'import-review')).toBeNull();
    expect((q(el, 'upload') as HTMLButtonElement).disabled).toBe(false);
  });

  it('shows the server sentence when the store was switched off (404) on the start, and keeps the file', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(2));
    me().upload();
    http.expectOne(STORE_LIST).flush(...problem(404, 'not-found', 'This record was not found.'));
    await settle();
    expect(text(el, 'upload-error')).toBe('This record was not found.');
    expect(text(el, 'file-name')).toBe('stock.csv · 2 rows');
    expect(q(el, 'import-review')).toBeNull();
  });

  it('shows the same sentence when the store is switched off while the rows are being sent', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(2));
    me().upload();
    http.expectOne(STORE_LIST).flush(openingImport({ id: 'imp-9', status: 'DRAFT' }));
    http.expectOne(`${STORE_LIST}/imp-9/lines`).flush(...problem(404, 'not-found', 'This record was not found.'));
    await settle();
    expect(text(el, 'upload-error')).toBe('This record was not found.');
    expect(q(el, 'import-review')).toBeNull();
  });

  it('resends an unchanged file after an error with the same key and the same batches, so the server writes nothing twice', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(250));
    me().upload();
    const first = http.expectOne(STORE_LIST);
    const key = (first.request.body as { clientKey: string }).clientKey;
    first.flush(openingImport({ id: 'imp-9', status: 'DRAFT' }));
    const firstBatch = http.expectOne(`${STORE_LIST}/imp-9/lines`);
    const firstRows = (firstBatch.request.body as { rows: { rowNo: number; batch: string }[] }).rows;
    expect(firstRows).toHaveLength(200);
    firstBatch.flush({ receivedRows: 200, rowCount: 250, issues: [] });
    http.expectOne(`${STORE_LIST}/imp-9/lines`).error(new ProgressEvent('error'));
    await settle();
    expect(text(el, 'upload-error')).toBe('Could not upload. Try again.');
    me().upload();
    const second = http.expectOne(STORE_LIST);
    expect((second.request.body as { clientKey: string }).clientKey).toBe(key);
    second.flush(openingImport({ id: 'imp-9', status: 'DRAFT' }), { status: 200, statusText: 'OK' });
    const again = http.expectOne(`${STORE_LIST}/imp-9/lines`);
    expect((again.request.body as { rows: unknown[] }).rows).toEqual(firstRows);
    again.error(new ProgressEvent('error'));
  });

  it('opens the review of a file that was not accepted, with its problems, and keeps nothing', async () => {
    const el = await open(OWNER_CONTEXT);
    me().chooseStore('store-central');
    me().accept('stock.csv', csv(2));
    me().upload();
    http.expectOne(STORE_LIST).flush(openingImport({ id: 'imp-9', status: 'DRAFT' }));
    http.expectOne(`${STORE_LIST}/imp-9/lines`).flush({ receivedRows: 2, rowCount: 2, issues: [] });
    http
      .expectOne(`${STORE_LIST}/imp-9/submit`)
      .flush(...problem(422, 'opening-stock-invalid', '1 rows have problems. Fix them and upload the file again.', { errorCount: 1 }));
    http.expectOne((r) => r.url === ALL).flush({ items: [], total: 0, page: 0, size: 20 });
    fixture.detectChanges();
    const rejected = openingImport({ id: 'imp-9', status: 'NOT_ACCEPTED', errorCount: 1, canApprove: false });
    http.expectOne(`${STORE_LIST}/imp-9`).flush(rejected);
    const lines = http.expectOne((r) => r.url === `${STORE_LIST}/imp-9/lines`);
    expect(lines.request.params.get('withIssues')).toBe('true');
    lines.flush({
      items: [
        openingLine({
          rowNo: 2,
          issues: [{ rowNo: 2, field: 'batch', code: 'batch-invalid', severity: 'ERROR', message: 'Enter a batch of up to 20 characters' }],
        }),
      ],
      total: 1,
      page: 0,
      size: 50,
    });
    await settle();
    expect(text(el, 'review-status')).toBe('Not accepted');
    expect(text(el, 'line-issue')).toContain('Batch: Enter a batch of up to 20 characters');
    expect(q(el, 'file-name')).toBeNull();
    expect(q(el, 'upload-error')).toBeNull();
  });

  it('keeps the file contents out of local storage and the address', async () => {
    await open(OWNER_CONTEXT);
    me().accept('stock.csv', csv(3));
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(location.href).not.toContain('G-000101');
  });

  describe('a pharmacist mapped to the store', () => {
    it('uploads for the one store they are mapped to, and lists only that store\'s files', async () => {
      const el = await open(PHARMACIST_UPLOADER, [openingImport({ canApprove: false, approveLockReason: 'NOT_OWNER' })]);
      expect(q(el, 'os-filter')).toBeNull();
      expect(el.textContent).toContain('Files of this store');
      expect(q(el, 'review-imp-1')?.textContent?.trim()).toBe('Open');
      me().accept('stock.csv', csv(1));
      await settle();
      expect((q(el, 'upload') as HTMLButtonElement).disabled).toBe(false);
    });

    it('cannot pick a store they have no upload right for', async () => {
      const el = await open({
        ...PHARMACIST_UPLOADER,
        stores: [{ ...PHARMACIST_UPLOADER.stores[0], access: { ...PHARMACIST_UPLOADER.stores[0].access, uploadOpeningStock: false } }],
      });
      expect(q(el, 'os-no-stores')?.textContent).toContain('You are not mapped to a store you can upload for. Ask your owner.');
      expect(q(el, 'upload')).toBeNull();
    });
  });
});
