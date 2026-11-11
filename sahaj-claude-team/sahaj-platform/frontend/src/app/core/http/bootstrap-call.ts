import { HttpContextToken, HttpRequest } from '@angular/common/http';

/**
 * Marks the two dev-only bootstrap registration calls (SAH-42, docs/api/admin.yaml). Both realm
 * interceptors let such a call pass untouched: no clinic or admin token is attached (the server
 * ignores one anyway, BR14) and a 401 is never a reason to refresh a session. A wrong PIN is a 401
 * that belongs to the page, not to a session.
 *
 * Use `isBootstrapCall` to read the marker: it honours it only for the two bootstrap paths.
 */
export const BOOTSTRAP_CALL = new HttpContextToken<boolean>(() => false);

/** The only two paths the marker is honoured for (path only: the page sends no host). */
export const BOOTSTRAP_PATHS: readonly string[] = [
  '/api/v1/admin/bootstrap/unlock',
  '/api/v1/admin/bootstrap/register',
];

/** True only when the request carries the marker AND goes to one of the two bootstrap paths. */
export function isBootstrapCall(req: HttpRequest<unknown>): boolean {
  if (!req.context.get(BOOTSTRAP_CALL)) {
    return false;
  }
  const { pathname } = new URL(req.url, globalThis.location?.origin ?? 'http://localhost');
  return BOOTSTRAP_PATHS.includes(pathname);
}
