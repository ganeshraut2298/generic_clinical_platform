import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { ShellStore } from './shell.store';

/**
 * Loads `/me` before any `/app` child route activates, so the shell and every menu guard have
 * the signed-in user's role, clinic and menu ready. A 401 here means the access token was
 * rejected; send the visitor back to `/login` (the `authGuard` already covers the common case).
 */
export const shellGuard: CanActivateFn = () => {
  const store = inject(ShellStore);
  const router = inject(Router);
  return store.load().pipe(
    map(() => true),
    catchError(() => of(router.createUrlTree(['/login']))),
  );
};
