import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { PharmacyApi } from '../pharmacy.api';
import { dispatch, indent, indentRow, returnItem, shortage } from './indent.fixtures';

const BASE = '/api/v1/pharmacy';
const S = `${BASE}/stores/store-ward`;

describe('PharmacyApi, SAH-79 work package F', () => {
  let api: PharmacyApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting()],
    });
    api = TestBed.inject(PharmacyApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the raised indents with a status and the dispatch view with no status', () => {
    api.indents('store-ward', { view: 'RAISED', status: 'DRAFT', page: 1, size: 20 }).subscribe();
    const raised = http.expectOne((r) => r.url === `${S}/indents`);
    expect(raised.request.params.get('view')).toBe('RAISED');
    expect(raised.request.params.get('status')).toBe('DRAFT');
    expect(raised.request.params.get('page')).toBe('1');
    raised.flush({ items: [indentRow()], total: 1, page: 1, size: 20 });

    api.indents('store-central', { view: 'TO_DISPATCH', status: '' }).subscribe();
    const toDispatch = http.expectOne((r) => r.url === `${BASE}/stores/store-central/indents`);
    expect(toDispatch.request.params.get('view')).toBe('TO_DISPATCH');
    expect(toDispatch.request.params.has('status')).toBe(false);
    toDispatch.flush({ items: [], total: 0, page: 0, size: 20 });
  });

  it('creates, changes, submits, discards and cancels an indent with the contract bodies', () => {
    api.createIndent('store-ward', { clientKey: 'k1', priority: 'URGENT', lines: [{ productId: 'p1', requestedQty: 20 }] }).subscribe();
    const create = http.expectOne(`${S}/indents`);
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual({ clientKey: 'k1', priority: 'URGENT', lines: [{ productId: 'p1', requestedQty: 20 }] });
    create.flush(indent({ status: 'DRAFT', number: null }));

    api.updateIndent('store-ward', 'ind-1', { version: 1, priority: 'ROUTINE', lines: [{ productId: 'p1', requestedQty: 5 }] }).subscribe();
    const update = http.expectOne(`${S}/indents/ind-1`);
    expect(update.request.method).toBe('PUT');
    update.flush(indent());

    api.submitIndent('store-ward', 'ind-1', { clientKey: 'k2', version: 2 }).subscribe();
    const submit = http.expectOne(`${S}/indents/ind-1/submit`);
    expect(submit.request.body).toEqual({ clientKey: 'k2', version: 2 });
    submit.flush(indent());

    api.discardIndent('store-ward', 'ind-1', { version: 2 }).subscribe();
    http.expectOne(`${S}/indents/ind-1/discard`).flush(indent({ status: 'DISCARDED' }));

    api.cancelIndent('store-ward', 'ind-1', { version: 3, reasonKey: 'approval-reject.other', note: 'Not needed' }).subscribe();
    const cancel = http.expectOne(`${S}/indents/ind-1/cancel`);
    expect(cancel.request.body).toEqual({ version: 3, reasonKey: 'approval-reject.other', note: 'Not needed' });
    cancel.flush(indent({ status: 'CANCELLED' }));
  });

  it('reads the replacement options and the FEFO pick of the Central Store', () => {
    api.indentReplacementOptions('store-central', 'ind-1', 'line-1').subscribe((o) => expect(o.length).toBe(1));
    http
      .expectOne(`${BASE}/stores/store-central/indents/ind-1/lines/line-1/replacement-options`)
      .flush({ items: [{ product: indent().lines[1].product, csAvailable: 12 }] });

    api.fefoPick('store-central', 'prod-1', 18).subscribe((p) => expect(p.batches[0].batch.batchNo).toBe('TESTB001'));
    const pick = http.expectOne((r) => r.url === `${BASE}/stores/store-central/stock/fefo-pick`);
    expect(pick.request.params.get('productId')).toBe('prod-1');
    expect(pick.request.params.get('quantity')).toBe('18');
    pick.flush({
      productId: 'prod-1',
      requested: 18,
      availableQty: 110,
      shortBy: 0,
      batches: [{ batch: { batchId: 'b1', batchNo: 'TESTB001', expiryDate: '2028-03-31' }, quantity: 18, purchaseRate: '70.5000' }],
    });
  });

  it('dispatches an indent and reads the incoming dispatches and one dispatch', () => {
    api
      .dispatchIndent('store-central', 'ind-1', {
        clientKey: 'k3',
        indentVersion: 3,
        lines: [{ indentLineId: 'line-1', dispatchProductId: 'prod-1', quantity: 18, balanceAction: 'BACK_ORDER' }],
        secondSigner: null,
      })
      .subscribe();
    const post = http.expectOne(`${BASE}/stores/store-central/indents/ind-1/dispatches`);
    expect(post.request.body.indentVersion).toBe(3);
    post.flush(dispatch(), { status: 201, statusText: 'Created' });

    api.incomingDispatches('store-ward', { status: 'IN_TRANSIT', size: 20 }).subscribe();
    const list = http.expectOne((r) => r.url === `${S}/dispatches`);
    expect(list.request.params.get('status')).toBe('IN_TRANSIT');
    list.flush({ items: [], total: 0, page: 0, size: 20 });

    api.dispatch('store-ward', 'disp-1').subscribe();
    http.expectOne(`${S}/dispatches/disp-1`).flush(dispatch());
  });

  it('confirms a receipt with the accepted and rejected quantities per line and the signer', () => {
    api
      .receiveDispatch('store-ward', 'disp-1', {
        clientKey: 'k4',
        dispatchVersion: 1,
        lines: [{ dispatchLineId: 'dline-1', accepted: 16, rejected: 2, rejectReasonKey: 'receipt-reject.damaged-in-transit', skipped: false }],
        secondSigner: { membershipId: 'm1', pin: '1234' },
      })
      .subscribe();
    const req = http.expectOne(`${S}/dispatches/disp-1/receipt`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.lines[0].rejectReasonKey).toBe('receipt-reject.damaged-in-transit');
    req.flush(dispatch({ status: 'RECEIVED' }));
  });

  it('lists the waiting returns by side, accepts, disputes and replies', () => {
    api.waitingForAction('store-central', { side: 'ACCEPTING', page: 0, size: 20 }).subscribe();
    const list = http.expectOne((r) => r.url === `${BASE}/stores/store-central/waiting-for-action`);
    expect(list.request.params.get('side')).toBe('ACCEPTING');
    list.flush({ items: [returnItem()], total: 1, page: 0, size: 20 });

    api.returnItem('store-central', 'ret-1').subscribe();
    http.expectOne(`${BASE}/stores/store-central/return-items/ret-1`).flush(returnItem());

    api.acceptReturnItem('store-central', 'ret-1', { clientKey: 'k5', version: 1, putBackInto: 'QUARANTINE' }).subscribe();
    const accept = http.expectOne(`${BASE}/stores/store-central/return-items/ret-1/accept`);
    expect(accept.request.body.putBackInto).toBe('QUARANTINE');
    accept.flush(returnItem({ status: 'ACCEPTED_QUARANTINE' }));

    api.disputeReturnItem('store-central', 'ret-1', { version: 1, reasonKey: 'dispute.other', note: 'Not ours' }).subscribe();
    http.expectOne(`${BASE}/stores/store-central/return-items/ret-1/dispute`).flush(returnItem({ status: 'DISPUTED' }));

    api.replyReturnItem('store-ward', 'ret-1', { clientKey: 'k6', note: 'It was sealed' }).subscribe();
    const reply = http.expectOne(`${S}/return-items/ret-1/notes`);
    reply.flush(returnItem(), { status: 201, statusText: 'Created' });
  });

  it('lists the shortages and settles one', () => {
    api.shortages('store-central', { status: 'OPEN' }).subscribe();
    const list = http.expectOne((r) => r.url === `${BASE}/stores/store-central/shortages`);
    expect(list.request.params.get('status')).toBe('OPEN');
    list.flush({ items: [shortage()], total: 1, page: 0, size: 20 });

    api.settleShortage('store-central', 'short-1', { clientKey: 'k7', version: 1, outcome: 'TRANSIT_LOSS', note: null }).subscribe();
    const settle = http.expectOne(`${BASE}/stores/store-central/shortages/short-1/settle`);
    expect(settle.request.body.outcome).toBe('TRANSIT_LOSS');
    settle.flush(shortage({ status: 'WAITING_WRITE_OFF' }));
  });
});
