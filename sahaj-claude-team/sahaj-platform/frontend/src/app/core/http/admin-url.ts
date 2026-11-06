/**
 * Tells the clinic realm and the admin realm apart by URL (ADR-003 D8). Both HTTP interceptors
 * use this one rule, so a clinic token can never ride on an admin call and an admin token can
 * never ride on any other call.
 */

function origin(): string {
  return globalThis.location?.origin ?? 'http://localhost';
}

function resolve(url: string): URL | null {
  try {
    return new URL(url, origin());
  } catch {
    return null;
  }
}

function isUnder(path: string, prefix: string): boolean {
  return path === prefix || path.startsWith(`${prefix}/`);
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/**
 * Strict: the URL points at `<API base>/admin/**` on the same origin as the API base, after the
 * browser's own normalisation (so `/api/v1/admin/../platform/me` is not admin). Only these
 * requests may carry the admin token.
 */
export function isAdminApiUrl(url: string, apiBase: string): boolean {
  const base = resolve(`${apiBase}/admin`);
  const target = resolve(url);
  if (!base || !target || base.origin !== target.origin) {
    return false;
  }
  return isUnder(target.pathname, base.pathname);
}

/**
 * Lenient: also true for encoded or differently cased spellings and for an unparsable URL under
 * an admin-looking path. The clinic interceptor uses this one, so it errs on the side of NOT
 * attaching the clinic token.
 */
export function mayBeAdminApiUrl(url: string, apiBase: string): boolean {
  if (isAdminApiUrl(url, apiBase)) {
    return true;
  }
  const base = resolve(`${apiBase}/admin`);
  const target = resolve(url);
  if (!base || !target || base.origin !== target.origin) {
    return false;
  }
  return isUnder(safeDecode(target.pathname).toLowerCase(), safeDecode(base.pathname).toLowerCase());
}
