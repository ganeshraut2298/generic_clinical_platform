import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { isAdminApiUrl } from '../../../core/http/admin-url';
import { isBootstrapCall } from '../../../core/http/bootstrap-call';
import { AdminAuthService } from './admin-auth.service';

/** The four public admin auth calls: no token, no refresh-and-retry. */
const PUBLIC_PATHS = ['/auth/login', '/auth/totp/enrol', '/auth/totp/verify', '/auth/refresh'];

function isPublicAdminCall(req: HttpRequest<unknown>, apiBase: string): boolean {
  const path = new URL(req.url, globalThis.location?.origin ?? 'http://localhost').pathname;
  const adminBase = new URL(`${apiBase}/admin`, globalThis.location?.origin ?? 'http://localhost')
    .pathname;
  return PUBLIC_PATHS.includes(path.slice(adminBase.length));
}

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

/**
 * The admin realm's interceptor (ADR-003 D8). It attaches the in-memory admin token ONLY to
 * `/api/v1/admin/**`, and to any other URL it does nothing at all. On a 401 from a protected
 * admin call it tries one silent refresh through the cookie and retries once; if that fails,
 * the admin is sent back to `/admin/sign-in` with "Your session ended".
 */
export const adminAuthInterceptor: HttpInterceptorFn = (req, next) => {
  if (isBootstrapCall(req)) {
    return next(req);
  }
  const apiBase = inject(API_BASE_URL);
  if (!isAdminApiUrl(req.url, apiBase)) {
    return next(req);
  }
  if (isPublicAdminCall(req, apiBase)) {
    return next(req);
  }

  const auth = inject(AdminAuthService);
  const router = inject(Router);

  return next(withToken(req, auth.accessToken())).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        switchMap((refreshed) => {
          if (!refreshed) {
            auth.endSession();
            void router.navigate(['/admin/sign-in'], { queryParams: { reason: 'ended' } });
            return throwError(() => error);
          }
          return next(withToken(req, auth.accessToken()));
        }),
      );
    }),
  );
};
