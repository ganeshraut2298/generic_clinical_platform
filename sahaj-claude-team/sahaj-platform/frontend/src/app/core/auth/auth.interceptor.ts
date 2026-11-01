import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** `/auth/*` calls are public or use their own cookie/token; never attach the access token. */
function isAuthEndpoint(req: HttpRequest<unknown>): boolean {
  return req.url.includes('/platform/auth/');
}

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  if (!token || isAuthEndpoint(req)) {
    return req;
  }
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

/**
 * Attaches the in-memory access token. On a 401 from a protected call, tries one silent refresh
 * and retries the request once; if that also fails, the visitor is sent back to `/login`.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(withToken(req, auth.accessToken())).pipe(
    catchError((error: unknown) => {
      const isUnauthorized = error instanceof HttpErrorResponse && error.status === 401;
      if (!isUnauthorized || isAuthEndpoint(req)) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        switchMap((refreshed) => {
          if (!refreshed) {
            void router.navigate(['/login']);
            return throwError(() => error);
          }
          return next(withToken(req, auth.accessToken()));
        }),
      );
    }),
  );
};
