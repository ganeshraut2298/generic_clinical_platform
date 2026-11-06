import { TestBed } from '@angular/core/testing';
import { Route, Router, provideRouter } from '@angular/router';
import { ShellStore } from '../../core/shell/shell.store';
import { SHELL_ROUTES } from './shell.routes';

/**
 * Mounts the real shell children (without the shell component, which needs the whole app) under
 * `/app` so the real paths, guards and lazy loading are exercised. SAH-28 QA finding F1: the
 * `emergency` route was exported by the IPD feature but never mounted, so it fell through to Today.
 */
describe('SHELL_ROUTES emergency', () => {
  const children: Route[] = SHELL_ROUTES[0].children ?? [];

  async function navigate(menu: string[], url: string): Promise<string> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'app', children },
          { path: '**', children: [] },
        ]),
        { provide: ShellStore, useValue: { menu: () => menu } },
      ],
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    return router.url;
  }

  it('mounts an emergency route guarded by the menu', () => {
    const route = children.find((r) => r.path === 'emergency');
    expect(route).toBeDefined();
    expect(route?.canActivate?.length).toBe(1);
    expect(route?.loadChildren).toBeDefined();
  });

  it('opens /app/emergency for a role whose menu has emergency', async () => {
    expect(await navigate(['today', 'emergency'], '/app/emergency')).toBe('/app/emergency');
  });

  it('sends a role without the emergency menu item to the Not allowed page', async () => {
    expect(await navigate(['today', 'queue'], '/app/emergency')).toBe('/app/not-allowed');
  });

  it('does not fall through to Today for /app/emergency', async () => {
    expect(await navigate(['today', 'emergency'], '/app/emergency')).not.toBe('/app/today');
  });
});
