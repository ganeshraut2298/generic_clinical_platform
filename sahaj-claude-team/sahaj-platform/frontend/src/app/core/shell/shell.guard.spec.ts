import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { firstValueFrom, isObservable, of, throwError } from 'rxjs';
import { shellGuard } from './shell.guard';
import { ShellStore } from './shell.store';

describe('shellGuard', () => {
  let store: { load: ReturnType<typeof vi.fn> };
  let router: Router;

  function run() {
    const result = TestBed.runInInjectionContext(() => shellGuard({} as never, {} as never));
    return isObservable(result) ? firstValueFrom(result) : Promise.resolve(result);
  }

  beforeEach(() => {
    store = { load: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: ShellStore, useValue: store }] });
    router = TestBed.inject(Router);
  });

  it('lets the route activate once /me loads', async () => {
    store.load.mockReturnValue(of({ userId: 'u1' }));
    const result = await run();
    expect(result).toBe(true);
  });

  it('sends the visitor to /login when /me fails', async () => {
    store.load.mockReturnValue(throwError(() => new Error('401')));
    const result = await run();
    expect(result).not.toBe(true);
    expect((result as UrlTree).toString()).toBe(router.createUrlTree(['/login']).toString());
  });
});
