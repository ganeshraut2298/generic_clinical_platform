import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { OpdApi } from './opd.api';

const BASE = '/api/v1/opd';

describe('OpdApi', () => {
  let api: OpdApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(OpdApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('adds a service and edits it with a partial body', () => {
    api.addService({ name: 'Dressing', category: 'Procedure', priceRupees: 150 }).subscribe();
    const add = http.expectOne(`${BASE}/services`);
    expect(add.request.method).toBe('POST');
    add.flush({ id: 's1', name: 'Dressing', category: 'Procedure', priceRupees: 150, active: true });

    api.updateService('s1', { active: false }).subscribe();
    const patch = http.expectOne(`${BASE}/services/s1`);
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ active: false });
    patch.flush({});
  });

  it('searches medicines with the query, page and favourites filter', () => {
    api.searchMedicines(' para ', 2, true).subscribe();
    const req = http.expectOne((r) => r.url === `${BASE}/medicines`);
    expect(req.request.params.get('q')).toBe('para');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('favouritesOnly')).toBe('true');
    req.flush({ items: [], total: 0 });
  });

  it('marks and unmarks a favourite', () => {
    api.markFavourite('m1').subscribe();
    const put = http.expectOne(`${BASE}/medicine-favourites/m1`);
    expect(put.request.method).toBe('PUT');
    put.flush(null, { status: 204, statusText: 'No Content' });

    api.unmarkFavourite('m1').subscribe();
    const del = http.expectOne(`${BASE}/medicine-favourites/m1`);
    expect(del.request.method).toBe('DELETE');
    del.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('saves fee settings and case paper settings with PUT', () => {
    api
      .saveFeeSettings({
        feeTiming: 'after',
        followUpDays: 6,
        followUpFee: 'free',
        discountLimits: { FRONT_DESK: 10 },
        upiOnBill: true,
      })
      .subscribe();
    expect(http.expectOne(`${BASE}/fee-settings`).request.method).toBe('PUT');

    api.saveCasePaperSettings({ size: 'A5', preprinted: false, caps: false, sections: { rx: true } }).subscribe();
    expect(http.expectOne(`${BASE}/case-paper-settings`).request.method).toBe('PUT');
  });
});
