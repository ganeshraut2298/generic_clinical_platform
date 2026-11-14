import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AdviceTextService } from './advice-text.service';

const EMR = '/api/v1/opd/emr';

describe('AdviceTextService (SAH-56-2, SAH-55-2)', () => {
  let service: AdviceTextService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(AdviceTextService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('remembers the Marathi and Hindi text the master returned', () => {
    service.remember('a1', 'कमी मीठ', null);
    expect(service.textFor({ itemKey: 'a1' }, 'MR')).toBe('कमी मीठ');
    expect(service.textFor({ itemKey: 'a1' }, 'HI')).toBeNull();
    expect(service.textFor({ itemKey: 'a1' }, 'EN')).toBeNull();
  });

  it('knows the master has no text in a language only once it has looked', () => {
    expect(service.lacksText({ itemKey: 'a1', newToList: false }, 'MR')).toBe(false);
    service.remember('a1', null, 'कम नमक');
    expect(service.lacksText({ itemKey: 'a1', newToList: false }, 'MR')).toBe(true);
    expect(service.lacksText({ itemKey: 'a1', newToList: false }, 'HI')).toBe(false);
    expect(service.lacksText({ itemKey: 'a1', newToList: false }, 'EN')).toBe(false);
  });

  it('never says "no text" for a typed item', () => {
    service.remember('a1', null, null);
    expect(service.lacksText({ itemKey: null, newToList: true }, 'MR')).toBe(false);
  });

  it('looks an item up in the advice master once and keeps the text', () => {
    service.ensure({ itemKey: 'a1', text: 'Low salt diet', newToList: false }, 'gm');
    service.ensure({ itemKey: 'a1', text: 'Low salt diet', newToList: false }, 'gm');
    const req = http.expectOne((r) => r.url === `${EMR}/masters/advice`);
    expect(req.request.params.get('q')).toBe('Low salt diet');
    req.flush({ items: [{ id: 'a1', name: 'Low salt diet', specialtyHint: 'gm', nameMr: 'कमी मीठ', nameHi: null }], total: 1, page: 0, pageSize: 20 });
    expect(service.textFor({ itemKey: 'a1' }, 'MR')).toBe('कमी मीठ');
    service.ensure({ itemKey: 'a1', text: 'Low salt diet', newToList: false }, 'gm');
    http.expectNone((r) => r.url === `${EMR}/masters/advice`);
  });

  it('does not look up a typed item or an item without a key', () => {
    service.ensure({ itemKey: null, text: 'Walk', newToList: true }, 'gm');
    service.ensure({ itemKey: 'a2', text: 'Walk', newToList: true }, 'gm');
    http.expectNone((r) => r.url === `${EMR}/masters/advice`);
  });

  it('ignores a failed lookup, so English is used', () => {
    service.ensure({ itemKey: 'a3', text: 'Rest', newToList: false }, null);
    http.expectOne((r) => r.url === `${EMR}/masters/advice`).flush({}, { status: 500, statusText: 'Server Error' });
    expect(service.textFor({ itemKey: 'a3' }, 'MR')).toBeNull();
    expect(service.lacksText({ itemKey: 'a3', newToList: false }, 'MR')).toBe(false);
  });
});
