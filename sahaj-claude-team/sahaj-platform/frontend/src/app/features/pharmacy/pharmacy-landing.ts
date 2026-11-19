import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { ShellStore } from '../../core/shell/shell.store';

/**
 * Where a Store staff member lands (SAH-78, ADR-028 D4 and D11). It guards the `today` route, which
 * `/app` still redirects to for everyone, so every existing role lands on Today exactly as before.
 *
 * It must be a guard and not part of the `''` redirect: Angular runs `redirectTo` functions during
 * route recognition, before `shellGuard` has loaded `/me`, so the menu would still be empty. A child
 * `canActivate` runs after the parent's `shellGuard`, when the menu is known.
 *
 * Two cases are redirected to `/app/pharmacy`:
 * - the menu has `pharmacy` and no `today` (a Store staff member with a mapped store, module on);
 * - the role is Store staff, whatever the menu. A Store staff member with no mapped store has an
 *   empty menu, and the Today APIs answer 403 for that role. The Pharmacy page then shows the
 *   server's sentence ("You do not have access to Pharmacy & Inventory. Ask your owner.") or the
 *   module-off one.
 * Every other role, including an empty menu for a role that is not Store staff, or `/me` not loaded
 * yet, is let through to Today as it was before this story.
 */
export const storeStaffLandingGuard: CanActivateFn = () => {
  const shell = inject(ShellStore);
  const menu = shell.menu();
  const isStoreStaff = shell.me()?.role === 'STORE_STAFF';
  if (isStoreStaff || (menu.includes('pharmacy') && !menu.includes('today'))) {
    return inject(Router).createUrlTree(['/app/pharmacy']);
  }
  return true;
};
