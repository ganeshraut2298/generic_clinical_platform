import { ApiError } from '../../../core/http/problem-detail';
import { describeSignInFailure, lockedMessage } from './admin-sign-in-copy';

function problem(status: number, slug = 'x', retryAfterSeconds?: number): ApiError {
  return new ApiError({
    type: `https://sahaj.in/problems/${slug}`,
    title: 'server text that must not be shown',
    status,
    retryAfterSeconds,
  });
}

describe('describeSignInFailure', () => {
  it('uses the single combined text for bad credentials and bad codes', () => {
    for (const slug of ['admin-credentials-invalid', 'totp-invalid', 'unauthorized']) {
      expect(describeSignInFailure(problem(401, slug))).toEqual({
        message: 'The email, password or code is not right.',
        reset: false,
      });
    }
  });

  it('resets the card when the pre-auth token is gone', () => {
    expect(describeSignInFailure(problem(401, 'preauth-expired'))).toEqual({
      message: 'Your sign-in took too long. Start again.',
      reset: true,
    });
  });

  it('rounds the lock time up to whole minutes', () => {
    expect(lockedMessage(900)).toBe('Too many attempts. Try again in 15 min.');
    expect(lockedMessage(61)).toBe('Too many attempts. Try again in 2 min.');
    expect(lockedMessage(5)).toBe('Too many attempts. Try again in 1 min.');
    expect(lockedMessage(undefined)).toBe('Too many attempts. Try again in a few minutes.');
    expect(describeSignInFailure(problem(423, 'admin-sign-in-locked', 900)).message).toBe(
      'Too many attempts. Try again in 15 min.',
    );
  });

  it('maps rate limit, network, unavailable and offline', () => {
    expect(describeSignInFailure(problem(429, 'rate-limit', 30)).message).toBe(
      'Too many attempts. Try again in a few minutes.',
    );
    expect(describeSignInFailure(problem(403, 'admin-network-denied')).message).toBe(
      'Sign-in works only from approved networks.',
    );
    expect(describeSignInFailure(problem(503, 'admin-unavailable')).message).toContain('not available');
    expect(describeSignInFailure(problem(0, 'about:blank')).message).toContain('Cannot reach the server');
  });

  it('never shows text the server sent', () => {
    for (const status of [400, 401, 403, 409, 423, 429, 500, 503]) {
      expect(describeSignInFailure(problem(status)).message).not.toContain('server text');
    }
  });
});
