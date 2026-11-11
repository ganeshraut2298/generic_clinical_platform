import { HttpContext, HttpRequest } from '@angular/common/http';
import { BOOTSTRAP_CALL, BOOTSTRAP_PATHS, isBootstrapCall } from './bootstrap-call';

function request(url: string, marked: boolean): HttpRequest<unknown> {
  return new HttpRequest('POST', url, {}, { context: new HttpContext().set(BOOTSTRAP_CALL, marked) });
}

describe('isBootstrapCall', () => {
  it('honours the marker for the two bootstrap paths, with or without a host', () => {
    expect(BOOTSTRAP_PATHS).toHaveLength(2);
    for (const path of BOOTSTRAP_PATHS) {
      expect(isBootstrapCall(request(path, true)), path).toBe(true);
      expect(isBootstrapCall(request(`http://localhost:4200${path}`, true)), path).toBe(true);
      expect(isBootstrapCall(request(`${path}?x=1`, true)), path).toBe(true);
    }
  });

  it('is false for a marked call to any other path', () => {
    for (const url of [
      '/api/v1/admin/me',
      '/api/v1/platform/me',
      '/api/v1/admin/bootstrap',
      '/api/v1/admin/bootstrap/unlock/extra',
      '/api/v1/admin/bootstrap/../me',
      '/other/api/v1/admin/bootstrap/unlock',
    ]) {
      expect(isBootstrapCall(request(url, true)), url).toBe(false);
    }
  });

  it('is false when percent-encoded dot segments (%2e%2e, %2E%2E) lead away from the bootstrap paths', () => {
    for (const url of [
      '/api/v1/admin/bootstrap/%2e%2e/me',
      '/api/v1/admin/bootstrap/%2E%2E/me',
      '/api/v1/admin/bootstrap/unlock/%2e%2e/%2e%2e/me',
      '/api/v1/admin/bootstrap/unlock/%2E%2E/%2E%2E/me',
    ]) {
      expect(isBootstrapCall(request(url, true)), url).toBe(false);
    }
  });

  it('judges the path the browser will send: dot segments that resolve onto a bootstrap path count as that path', () => {
    for (const url of [
      '/api/v1/admin/me/%2e%2e/bootstrap/unlock',
      '/api/v1/admin/me/%2E%2E/bootstrap/register',
    ]) {
      expect(isBootstrapCall(request(url, true)), url).toBe(true);
    }
  });

  it('is false for upper-case or mixed-case variants of the bootstrap paths (paths are case-sensitive)', () => {
    for (const url of [
      '/API/V1/ADMIN/BOOTSTRAP/UNLOCK',
      '/api/v1/admin/bootstrap/Unlock',
      '/api/v1/Admin/bootstrap/register',
      '/api/v1/admin/bootstrap/REGISTER',
    ]) {
      expect(isBootstrapCall(request(url, true)), url).toBe(false);
    }
  });

  it('accepts a different host with the same bootstrap path, by design: only the path is checked, a pass only removes token and refresh behaviour and the server enforces same-origin (K4, K5)', () => {
    for (const path of BOOTSTRAP_PATHS) {
      expect(isBootstrapCall(request(`https://other.example.test${path}`, true)), path).toBe(true);
    }
  });

  it('is false for an unmarked call, even to a bootstrap path', () => {
    for (const path of BOOTSTRAP_PATHS) {
      expect(isBootstrapCall(request(path, false)), path).toBe(false);
    }
  });
});
