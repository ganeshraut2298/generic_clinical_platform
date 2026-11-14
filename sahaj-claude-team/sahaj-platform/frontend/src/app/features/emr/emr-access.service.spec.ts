import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { ShellStore } from '../../core/shell/shell.store';
import { EmrAccess } from './emr-access.service';

const ME = '/api/v1/opd/emr/me';

describe('EmrAccess (SAH-46-1)', () => {
  let http: HttpTestingController;

  function setup(role: string | null): EmrAccess {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => (role ? { role } : null) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(EmrAccess);
  }

  afterEach(() => http.verify());

  it('does not ask until the user is known', () => {
    const access = setup(null);
    access.ensureLoaded();
    expect(access.canOpen()).toBe(false);
  });

  it('does not ask for a role that never opens the EMR', () => {
    for (const role of ['NURSE', 'FRONT_DESK', 'BILLING', 'PHARMACIST', 'COUNSELLOR']) {
      TestBed.resetTestingModule();
      const access = setup(role);
      access.ensureLoaded();
      http.expectNone(ME);
      expect(access.canOpen()).toBe(false);
    }
  });

  it('opens for a doctor when the server answers 200, asking only once', () => {
    const access = setup('DOCTOR');
    access.ensureLoaded();
    access.ensureLoaded();
    http.expectOne(ME).flush({ membershipId: 'm1', name: 'Dr X' });
    expect(access.canOpen()).toBe(true);
  });

  it('is closed for an owner without doctor details (403)', () => {
    const access = setup('OWNER');
    access.ensureLoaded();
    http
      .expectOne(ME)
      .flush({ type: 'https://sahaj.in/problems/doctor-details-missing', title: 'x', status: 403 }, { status: 403, statusText: 'Forbidden' });
    expect(access.canOpen()).toBe(false);
  });

  it('lets a doctor open only their own visits and the owner any', () => {
    const doctor = setup('DOCTOR');
    doctor.ensureLoaded();
    http.expectOne(ME).flush({ membershipId: 'm1' });
    expect(doctor.canOpenVisit('m1')).toBe(true);
    expect(doctor.canOpenVisit('m2')).toBe(false);

    TestBed.resetTestingModule();
    const owner = setup('OWNER');
    owner.ensureLoaded();
    http.expectOne(ME).flush({ membershipId: 'm9' });
    expect(owner.canOpenVisit('m2')).toBe(true);
  });
});
