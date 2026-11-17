import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from '../../core/config/api-config';
import { IPD_DEVICE_HEADER, IPD_DEVICE_STORAGE_KEY } from './ipd-device';
import { ipdDeviceInterceptor } from './ipd-device.interceptor';

describe('ipdDeviceInterceptor', () => {
  let http: HttpClient;
  let mock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(IPD_DEVICE_STORAGE_KEY, 'device-1');
    TestBed.configureTestingModule({
      providers: [
        { provide: API_BASE_URL, useValue: '/api/v1' },
        provideHttpClient(withInterceptors([ipdDeviceInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    mock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => mock.verify());

  it('adds the device header to IPD calls', () => {
    http.get('/api/v1/ipd/admissions').subscribe();
    const req = mock.expectOne('/api/v1/ipd/admissions');
    expect(req.request.headers.get(IPD_DEVICE_HEADER)).toBe('device-1');
    req.flush({});
  });

  it.each(['/api/v1/opd/visits', '/api/v1/platform/me', '/api/v1/ipdx/a', '/api/v1/admin/ipd/a'])(
    'leaves %s alone',
    (url) => {
      http.get(url).subscribe();
      const req = mock.expectOne(url);
      expect(req.request.headers.has(IPD_DEVICE_HEADER)).toBe(false);
      req.flush({});
    },
  );
});
