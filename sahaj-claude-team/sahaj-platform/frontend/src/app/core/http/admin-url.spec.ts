import { isAdminApiUrl, mayBeAdminApiUrl } from './admin-url';

describe('admin URL rule', () => {
  const base = '/api/v1';

  it('recognises /api/v1/admin/** as admin', () => {
    for (const url of [
      '/api/v1/admin/me',
      '/api/v1/admin/clinics?page=0&size=25',
      '/api/v1/admin/auth/sign-out',
      '/api/v1/admin',
      '/api/v1/admin/../admin/me',
    ]) {
      expect(isAdminApiUrl(url, base), url).toBe(true);
    }
  });

  it('does not treat other URLs as admin', () => {
    for (const url of [
      '/api/v1/platform/me',
      '/api/v1/platform/admin/x',
      '/api/v1/administrator',
      '/api/v1/admin/../platform/me',
      '/api/v2/admin/me',
      'https://evil.example/api/v1/admin/me',
      '/assets/admin/logo.svg',
    ]) {
      expect(isAdminApiUrl(url, base), url).toBe(false);
    }
  });

  it('follows an absolute API base such as the dev server', () => {
    const devBase = 'http://localhost:8080/api/v1';
    expect(isAdminApiUrl('http://localhost:8080/api/v1/admin/me', devBase)).toBe(true);
    expect(isAdminApiUrl('http://localhost:8080/api/v1/platform/me', devBase)).toBe(false);
    expect(isAdminApiUrl('http://localhost:9999/api/v1/admin/me', devBase)).toBe(false);
  });

  it('the lenient check also catches encoded and differently cased spellings', () => {
    expect(isAdminApiUrl('/api/v1/%61dmin/me', base)).toBe(false);
    expect(mayBeAdminApiUrl('/api/v1/%61dmin/me', base)).toBe(true);
    expect(mayBeAdminApiUrl('/api/v1/Admin/me', base)).toBe(true);
    expect(mayBeAdminApiUrl('/api/v1/platform/me', base)).toBe(false);
  });
});
