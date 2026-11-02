import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslateService } from './translate.service';

describe('TranslateService', () => {
  let service: TranslateService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TranslateService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('falls back to the key when nothing is loaded yet', () => {
    expect(service.t('nav.today')).toBe('nav.today');
  });

  it('loads the dictionary for a language and translates known keys', () => {
    service.use('mr').subscribe();
    http.expectOne('/i18n/mr.json').flush({ 'nav.today': 'आज' });
    expect(service.t('nav.today')).toBe('आज');
    expect(service.language()).toBe('mr');
  });

  it('caches a language so switching back does not call the server again', () => {
    service.use('en').subscribe();
    http.expectOne('/i18n/en.json').flush({ 'nav.today': 'Today' });
    service.use('mr').subscribe();
    http.expectOne('/i18n/mr.json').flush({ 'nav.today': 'आज' });
    service.use('en').subscribe();
    http.expectNone('/i18n/en.json');
    expect(service.t('nav.today')).toBe('Today');
  });
});
