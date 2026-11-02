import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { ShellStore } from './shell.store';

/**
 * Blocks a menu page the signed-in user's role does not have. This is a UI convenience only;
 * `/me` already names only the allowed keys, and every API still checks the role itself (SAH-5
 * acceptance criterion 1 and 11).
 */
export function menuGuard(menuKey: string): CanActivateFn {
  return () => {
    const store = inject(ShellStore);
    const router = inject(Router);
    return store.menu().includes(menuKey) ? true : router.createUrlTree(['/app/not-allowed']);
  };
}
