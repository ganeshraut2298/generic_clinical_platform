import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { signal } from '@angular/core';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
  let isAuthenticated: ReturnType<typeof signal<boolean>>;
  let router: Router;

  function run() {
    return TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));
  }

  beforeEach(() => {
    isAuthenticated = signal(false);
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: { isAuthenticated },
        },
      ],
    });
    router = TestBed.inject(Router);
  });

  it('allows a signed-in visitor through', () => {
    isAuthenticated.set(true);
    expect(run()).toBe(true);
  });

  it('sends a signed-out visitor to /login instead of showing the page', () => {
    isAuthenticated.set(false);
    const result = run();
    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(router.createUrlTree(['/login']).toString());
  });
});
