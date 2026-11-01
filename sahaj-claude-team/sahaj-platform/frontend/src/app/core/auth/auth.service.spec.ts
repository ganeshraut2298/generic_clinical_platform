import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { AuthApi } from './auth.api';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let api: {
    requestOtp: ReturnType<typeof vi.fn>;
    verifyOtp: ReturnType<typeof vi.fn>;
    selectClinic: ReturnType<typeof vi.fn>;
    refresh: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
    logoutAll: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      requestOtp: vi.fn(),
      verifyOtp: vi.fn(),
      selectClinic: vi.fn(),
      refresh: vi.fn(),
      logout: vi.fn(),
      logoutAll: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [{ provide: AuthApi, useValue: api }] });
    service = TestBed.inject(AuthService);
  });

  it('starts signed out, with no access token anywhere', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.accessToken()).toBeNull();
  });

  it('holds the access token in memory after a successful verify', () => {
    api.verifyOtp.mockReturnValue(of({ next: 'APP', accessToken: 'token-1', expiresInSeconds: 900 }));
    service.verifyOtp('9822000001', '123456', 'LOGIN').subscribe();
    expect(service.isAuthenticated()).toBe(true);
    expect(service.accessToken()).toBe('token-1');
  });

  it('keeps the selection token and clinics for CHOOSE_CLINIC, with no access token yet', () => {
    api.verifyOtp.mockReturnValue(
      of({
        next: 'CHOOSE_CLINIC',
        selectionToken: 'sel-1',
        clinics: [{ tenantId: 't1', name: 'Rao Clinic' }],
      }),
    );
    service.verifyOtp('9822000001', '123456', 'LOGIN').subscribe();
    expect(service.isAuthenticated()).toBe(false);
    expect(service.selectionToken()).toBe('sel-1');
    expect(service.clinics()?.length).toBe(1);
  });

  it('uses the stored selection token to select a clinic', () => {
    api.verifyOtp.mockReturnValue(of({ next: 'CHOOSE_CLINIC', selectionToken: 'sel-1' }));
    service.verifyOtp('9822000001', '123456', 'LOGIN').subscribe();

    api.selectClinic.mockReturnValue(of({ next: 'APP', accessToken: 'token-2' }));
    service.selectClinic('tenant-1').subscribe();

    expect(api.selectClinic).toHaveBeenCalledWith({ selectionToken: 'sel-1', tenantId: 'tenant-1' });
    expect(service.accessToken()).toBe('token-2');
  });

  it('refresh() sets the new access token on success', () => {
    api.refresh.mockReturnValue(of({ next: 'APP', accessToken: 'token-3' }));
    let ok: boolean | undefined;
    service.refresh().subscribe((value) => (ok = value));
    expect(ok).toBe(true);
    expect(service.accessToken()).toBe('token-3');
  });

  it('refresh() clears the session and resolves false on failure, without throwing', () => {
    api.refresh.mockReturnValue(throwError(() => new Error('401')));
    let ok: boolean | undefined;
    service.refresh().subscribe((value) => (ok = value));
    expect(ok).toBe(false);
    expect(service.isAuthenticated()).toBe(false);
  });

  it('shares one in-flight refresh across concurrent callers', () => {
    const subject = new Subject<{ next: 'APP'; accessToken: string }>();
    api.refresh.mockReturnValue(subject.asObservable());
    service.refresh().subscribe();
    service.refresh().subscribe();
    expect(api.refresh).toHaveBeenCalledTimes(1);
    subject.next({ next: 'APP', accessToken: 'token-4' });
    subject.complete();
    expect(service.accessToken()).toBe('token-4');
  });

  it('logout() clears the in-memory session', () => {
    api.verifyOtp.mockReturnValue(of({ next: 'APP', accessToken: 'token-5' }));
    service.verifyOtp('9822000001', '123456', 'LOGIN').subscribe();
    expect(service.isAuthenticated()).toBe(true);

    api.logout.mockReturnValue(of(undefined));
    service.logout().subscribe();
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logoutAll() clears the session even if the call fails', () => {
    api.verifyOtp.mockReturnValue(of({ next: 'APP', accessToken: 'token-6' }));
    service.verifyOtp('9822000001', '123456', 'LOGIN').subscribe();

    api.logoutAll.mockReturnValue(throwError(() => new Error('network')));
    service.logoutAll().subscribe();
    expect(service.isAuthenticated()).toBe(false);
  });
});
