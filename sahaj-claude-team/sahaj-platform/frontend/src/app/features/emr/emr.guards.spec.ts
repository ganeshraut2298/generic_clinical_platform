import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { ShellStore } from '../../core/shell/shell.store';
import { emrLeaveGuard, emrRoleGuard } from './emr.guards';

describe('EMR guards', () => {
  function run(role: string | undefined): boolean | UrlTree {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: ShellStore, useValue: { me: () => (role ? { role } : null) } }],
    });
    return TestBed.runInInjectionContext(() =>
      emrRoleGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  it('lets a doctor, a visiting doctor and the owner in', () => {
    for (const role of ['DOCTOR', 'VISITING_DOCTOR', 'OWNER']) {
      TestBed.resetTestingModule();
      expect(run(role)).toBe(true);
    }
  });

  it('sends every other role, and an unknown user, to the Not allowed page', () => {
    for (const role of ['NURSE', 'FRONT_DESK', 'BILLING', 'PHARMACIST', 'COUNSELLOR', undefined]) {
      TestBed.resetTestingModule();
      const result = run(role);
      expect(result).toBeInstanceOf(UrlTree);
      expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/app/not-allowed');
    }
  });

  it('asks the page before leaving', () => {
    const page = { confirmLeave: vi.fn(() => false) };
    const result = TestBed.runInInjectionContext(() =>
      emrLeaveGuard(page, {} as ActivatedRouteSnapshot, {} as RouterStateSnapshot, {} as RouterStateSnapshot),
    );
    expect(result).toBe(false);
    expect(page.confirmLeave).toHaveBeenCalled();
  });
});
