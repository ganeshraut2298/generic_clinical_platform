import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { EmrApi } from './emr.api';

const EMR = '/api/v1/opd/emr';

/** The phase 5 calls use exactly the paths, methods, headers and bodies of docs/api/opd.yaml 0.6.0. */
describe('EmrApi, phase 5 (SAH-51, SAH-67, SAH-68)', () => {
  let api: EmrApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(EmrApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function expectCall(method: string, url: string, body?: unknown) {
    const req = http.expectOne(url);
    expect(req.request.method).toBe(method);
    if (body !== undefined) {
      expect(req.request.body).toEqual(body);
    }
    req.flush({});
    return req;
  }

  it('finish-check posts the choices and sends no key', () => {
    api.finishCheck('r1', { confirmAllergyRemoval: true }).subscribe();
    const req = expectCall('POST', `${EMR}/records/r1/finish-check`, { confirmAllergyRemoval: true });
    expect(req.request.headers.has('Idempotency-Key')).toBe(false);
  });

  it('sign posts the revision and the choices with the Idempotency-Key', () => {
    api.sign('r1', { revision: 12, choices: {} }, 'key-12345678').subscribe();
    const req = expectCall('POST', `${EMR}/records/r1/sign`, { revision: 12, choices: {} });
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-12345678');
  });

  it('takes, takes over and releases the edit hold', () => {
    api.takeEditHold('r1', false).subscribe();
    expectCall('PUT', `${EMR}/records/r1/edit-hold`, {});
    api.takeEditHold('r1', true).subscribe();
    expectCall('PUT', `${EMR}/records/r1/edit-hold`, { takeOver: true });
    api.releaseEditHold('r1').subscribe();
    expectCall('DELETE', `${EMR}/records/r1/edit-hold`);
  });

  it('adds an addendum with the Idempotency-Key', () => {
    api.addAddendum('r1', { reason: 'Wrong dose', text: 'Was 5 mg' }, 'key-abcdefgh').subscribe();
    const req = expectCall('POST', `${EMR}/records/r1/addenda`, { reason: 'Wrong dose', text: 'Was 5 mg' });
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-abcdefgh');
  });

  it('requests an admission with the Idempotency-Key and reads the prefill', () => {
    api.getAdmissionPrefill('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/admission-prefill`);
    api.requestAdmission('r1', { admissionType: 'planned', plannedStayDays: 3 }, 'key-ijklmnop').subscribe();
    const req = expectCall('POST', `${EMR}/records/r1/admission-request`, { admissionType: 'planned', plannedStayDays: 3 });
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-ijklmnop');
  });

  it('reads the vitals, the IPD order options, the triage levels and the free slots', () => {
    api.getEncounterVitals('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/encounter-vitals`);
    api.getIpdOrderOptions('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/ipd-order-options`);
    api.listTriageLevels().subscribe();
    expectCall('GET', `${EMR}/triage-levels`);
    api.listFreeSlots('r1').subscribe();
    expectCall('GET', `${EMR}/records/r1/free-slots?limit=4`);
    api.listFreeSlots('r1', '2026-11-20', 6).subscribe();
    expectCall('GET', `${EMR}/records/r1/free-slots?limit=6&from=2026-11-20`);
  });

  it('the visit type carries the triage level only for Emergency', () => {
    api.changeVisitType('v1', 'EMERGENCY', 2).subscribe();
    expectCall('PATCH', '/api/v1/opd/visits/v1/visit-type', { visitType: 'EMERGENCY', triageLevel: 2 });
    api.changeVisitType('v1', 'NEW').subscribe();
    expectCall('PATCH', '/api/v1/opd/visits/v1/visit-type', { visitType: 'NEW' });
  });

  it('reprints the signed prescription of a visit', () => {
    api.reprintPrescription('v1').subscribe();
    expectCall('GET', '/api/v1/opd/visits/v1/prescription-print');
  });
});
