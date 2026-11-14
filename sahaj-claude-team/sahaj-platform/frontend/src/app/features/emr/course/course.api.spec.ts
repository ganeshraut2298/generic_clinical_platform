import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CourseApi } from './course.api';

const OPD = '/api/v1/opd/emr/courses';
const BILLING = '/api/v1/billing/courses';

/** The course calls use the paths, bodies and headers of opd.yaml 0.4.0 and billing.yaml 0.2.0. */
describe('CourseApi (SAH-58)', () => {
  let api: CourseApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(CourseApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the courses of a patient and reads one course', () => {
    api.listForPatient('p1').subscribe();
    const list = http.expectOne((r) => r.url === OPD);
    expect(list.request.params.get('patientId')).toBe('p1');
    list.flush([]);
    api.get('c1').subscribe();
    expect(http.expectOne(`${OPD}/c1`).request.method).toBe('GET');
  });

  it('starts a course and adds a sitting, each with an Idempotency-Key', () => {
    api.create({ fromRecordId: 'r1', kind: 'DENTAL' }, 'key-aaaaaaaa').subscribe();
    const created = http.expectOne(OPD);
    expect(created.request.method).toBe('POST');
    expect(created.request.headers.get('Idempotency-Key')).toBe('key-aaaaaaaa');
    expect(created.request.body).toEqual({ fromRecordId: 'r1', kind: 'DENTAL' });
    created.flush({});
    api.addSitting('c1', { revision: 5, addOptionKey: 'opd_sitting' }, 'key-bbbbbbbb').subscribe();
    const added = http.expectOne(`${OPD}/c1/sittings`);
    expect(added.request.headers.get('Idempotency-Key')).toBe('key-bbbbbbbb');
    expect(added.request.body).toEqual({ revision: 5, addOptionKey: 'opd_sitting' });
    added.flush({});
  });

  it('changes a sitting with PUT and links a visit with POST', () => {
    const body = { revision: 5, encounterKind: 'OPD' as const, plannedDate: '2026-11-18', items: ['x'], chargePaise: 100000, note: null };
    api.changeSitting('c1', 's1', body).subscribe();
    const put = http.expectOne(`${OPD}/c1/sittings/s1`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual(body);
    put.flush({});
    api.linkVisit('c1', 's1', { revision: 5, visitId: 'v1' }).subscribe();
    const link = http.expectOne(`${OPD}/c1/sittings/s1/link-visit`);
    expect(link.request.method).toBe('POST');
    expect(link.request.body).toEqual({ revision: 5, visitId: 'v1' });
    link.flush({});
  });

  it('reads the account from billing and collects a payment with the key of the tap', () => {
    api.account('c1').subscribe();
    expect(http.expectOne(`${BILLING}/c1/account`).request.method).toBe('GET');
    const pay = { type: 'PART_PAYMENT' as const, amountPaise: 150000, mode: 'UPI' as const, useCredit: true, againstSittingId: null };
    api.collect('c1', pay, 'key-cccccccc').subscribe();
    const req = http.expectOne(`${BILLING}/c1/payments`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-cccccccc');
    expect(req.request.body).toEqual(pay);
    req.flush({});
  });

  it('reads a receipt by its number', () => {
    api.receipt('c1', 2201).subscribe();
    expect(http.expectOne(`${BILLING}/c1/receipts/2201`).request.method).toBe('GET');
  });
});
