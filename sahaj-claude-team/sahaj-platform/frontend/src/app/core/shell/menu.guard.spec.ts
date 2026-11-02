import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { signal } from '@angular/core';
import { menuGuard } from './menu.guard';
import { ShellStore } from './shell.store';

describe('menuGuard', () => {
  let menu: ReturnType<typeof signal<string[]>>;
  let router: Router;

  function run(key: string) {
    return TestBed.runInInjectionContext(() => menuGuard(key)({} as never, {} as never));
  }

  beforeEach(() => {
    menu = signal<string[]>(['today', 'queue']);
    TestBed.configureTestingModule({ providers: [{ provide: ShellStore, useValue: { menu } }] });
    router = TestBed.inject(Router);
  });

  it('allows a menu key the role has', () => {
    expect(run('queue')).toBe(true);
  });

  it('sends a nurse typing a hidden page URL to /app/not-allowed', () => {
    const result = run('billing');
    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(
      router.createUrlTree(['/app/not-allowed']).toString(),
    );
  });
});
