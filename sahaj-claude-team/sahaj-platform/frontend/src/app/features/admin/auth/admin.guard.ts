import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AdminAuthService } from './admin-auth.service';

/**
 * Keeps a signed-out visitor out of the admin shell. On a page reload the in-memory token is
 * gone, so the guard first tries the refresh cookie once. The backend still checks the admin
 * role and the session on every request.
 */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AdminAuthService);
  const router = inject(Router);
  return auth.restore().pipe(map((ok) => (ok ? true : router.createUrlTree(['/admin/sign-in']))));
};

/** An admin who is already signed in has no reason to see the sign-in card. */
export const adminGuestGuard: CanActivateFn = () => {
  const auth = inject(AdminAuthService);
  const router = inject(Router);
  return auth.isAuthenticated() ? router.createUrlTree(['/admin/clinics']) : true;
};
