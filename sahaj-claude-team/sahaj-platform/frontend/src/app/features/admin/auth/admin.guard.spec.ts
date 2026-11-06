import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { Observable, of } from 'rxjs';
import { AdminAuthService } from './admin-auth.service';
import { adminGuard, adminGuestGuard } from './admin.guard';

describe('admin guards', () => {
  let isAuthenticated: ReturnType<typeof signal<boolean>>;
  let restore: ReturnType<typeof vi.fn>;
  let router: Router;

  beforeEach(() => {
    isAuthenticated = signal(false);
    restore = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: AdminAuthService, useValue: { isAuthenticated, restore } }],
    });
    router = TestBed.inject(Router);
  });

  function runAdmin(): Observable<boolean | UrlTree> {
    return TestBed.runInInjectionContext(
      () => adminGuard({} as never, {} as never) as Observable<boolean | UrlTree>,
    );
  }

  function runGuest() {
    return TestBed.runInInjectionContext(() => adminGuestGuard({} as never, {} as never));
  }

  it('lets a signed-in admin (or a restored session) into the shell', () => {
    restore.mockReturnValue(of(true));
    let result: boolean | UrlTree | undefined;
    runAdmin().subscribe((value) => (result = value));
    expect(result).toBe(true);
  });

  it('sends a visitor with no admin session to /admin/sign-in, not to the clinic login', () => {
    restore.mockReturnValue(of(false));
    let result: boolean | UrlTree | undefined;
    runAdmin().subscribe((value) => (result = value));
    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(router.createUrlTree(['/admin/sign-in']).toString());
  });

  it('keeps a signed-in admin away from the sign-in card', () => {
    isAuthenticated.set(true);
    const result = runGuest();
    expect((result as UrlTree).toString()).toBe(router.createUrlTree(['/admin/clinics']).toString());
  });

  it('shows the sign-in card to a signed-out visitor', () => {
    expect(runGuest()).toBe(true);
  });
});
