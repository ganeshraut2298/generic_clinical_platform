import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdmissionsApi } from './admissions.api';
import { AdmissionInput } from './ipd.model';

const IPD = '/api/v1/ipd';

describe('AdmissionsApi', () => {
  let api: AdmissionsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(AdmissionsApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('reads, saves and confirms the admission rules', () => {
    api.rules().subscribe();
    expect(http.expectOne(`${IPD}/admission-rules`).request.method).toBe('GET');

    const body = {
      depositPaise: { planned: 1, emergency: 2, daycare: 3, maternity: 4 },
      categoryAddPaise: { gen: 0, semi: 0, pvt: 0, icu: 0 },
      emergencyWithoutDeposit: true,
      alertPercent: 80,
    };
    api.saveRules(body).subscribe();
    const put = http.expectOne(`${IPD}/admission-rules`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual(body);

    api.confirmRules().subscribe();
    const post = http.expectOne(`${IPD}/admission-rules/confirm`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toBeNull();
  });

  it('reads the options and the admitted list', () => {
    api.options().subscribe();
    expect(http.expectOne(`${IPD}/admission-options`).request.method).toBe('GET');
    api.list().subscribe();
    const list = http.expectOne((r) => r.url === `${IPD}/admissions`);
    expect(list.request.params.get('status')).toBe('ADMITTED');
  });

  it('admits, reads one admission and collects a deposit', () => {
    const input: AdmissionInput = {
      patientId: 'p1',
      doctorMembershipId: 'm1',
      admissionType: 'planned',
      bedId: 'b1',
      payer: 'Self',
      depositPaise: 1000000,
      depositMode: 'UPI',
      consentSigned: true,
    };
    api.admit(input).subscribe();
    const post = http.expectOne(`${IPD}/admissions`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual(input);

    api.get('a1').subscribe();
    expect(http.expectOne(`${IPD}/admissions/a1`).request.method).toBe('GET');

    api.collectDeposit('a1', 500000, 'Cash').subscribe();
    const dep = http.expectOne(`${IPD}/admissions/a1/deposits`);
    expect(dep.request.method).toBe('POST');
    expect(dep.request.body).toEqual({ amountPaise: 500000, mode: 'Cash' });
  });

  it('reads the bed board and changes bed status', () => {
    api.board().subscribe();
    expect(http.expectOne(`${IPD}/board`).request.method).toBe('GET');

    api.ready('b1').subscribe();
    const ready = http.expectOne(`${IPD}/beds/b1/ready`);
    expect(ready.request.method).toBe('POST');
    expect(ready.request.body).toBeNull();

    api.block('b2').subscribe();
    const block = http.expectOne(`${IPD}/beds/b2/block`);
    expect(block.request.method).toBe('POST');
  });

  it('reads the charge services and adds one charge by its key', () => {
    api.chargeServices().subscribe();
    expect(http.expectOne(`${IPD}/charge-services`).request.method).toBe('GET');
    api.addCharge('a1', 'cbc').subscribe();
    const post = http.expectOne(`${IPD}/admissions/a1/charges`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ serviceKey: 'cbc' });
  });

  it('reads and saves the transfer rules, and moves a patient to a bed', () => {
    api.transferRules().subscribe();
    expect(http.expectOne(`${IPD}/transfer-rules`).request.method).toBe('GET');
    api.saveTransferRules({ upgradeNeedsOwner: false, newRateFrom: 'next' }).subscribe();
    const put = http.expectOne(`${IPD}/transfer-rules`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ upgradeNeedsOwner: false, newRateFrom: 'next' });
    api.transfer('a1', 'b9').subscribe();
    const post = http.expectOne(`${IPD}/admissions/a1/transfer`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ toBedId: 'b9' });
  });

  it('reads and saves the discharge settings with the whole setting', () => {
    api.dischargeSettings().subscribe();
    expect(http.expectOne(`${IPD}/discharge-settings`).request.method).toBe('GET');
    api.saveDischargeSettings({ templateKey: null, approvalChain: false, followUpDays: 10 }).subscribe();
    const put = http.expectOne(`${IPD}/discharge-settings`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ templateKey: null, approvalChain: false, followUpDays: 10 });
  });

  it('reads the discharge sheet, signs it and discharges', () => {
    api.dischargeSheet('a1').subscribe();
    const get = http.expectOne(`${IPD}/admissions/a1/discharge`);
    expect(get.request.method).toBe('GET');
    api.signDischarge('a1', { type: 'lama', templateKey: 'surgical' }).subscribe();
    const sign = http.expectOne(`${IPD}/admissions/a1/discharge/signoff`);
    expect(sign.request.method).toBe('PUT');
    expect(sign.request.body).toEqual({ type: 'lama', templateKey: 'surgical' });
    api
      .discharge('a1', {
        type: 'normal',
        templateKey: 'general',
        expectedTotalPaise: 500000,
        expectedDepositsPaise: 350000,
        mode: 'UPI',
      })
      .subscribe();
    const post = http.expectOne(`${IPD}/admissions/a1/discharge`);
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({
      type: 'normal',
      templateKey: 'general',
      expectedTotalPaise: 500000,
      expectedDepositsPaise: 350000,
      mode: 'UPI',
    });
  });
});
