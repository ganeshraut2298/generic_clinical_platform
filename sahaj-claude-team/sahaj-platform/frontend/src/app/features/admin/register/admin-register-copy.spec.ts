import { ApiError, ProblemDetail } from '../../../core/http/problem-detail';
import {
  EMAIL_ERROR,
  NAME_ERROR,
  NO_CONNECTION,
  PASSWORD_ERROR,
  PIN_BLANK,
  SOMETHING_WENT_WRONG,
  describeBootstrapFailure,
  lockedMessage,
  lockedMinutes,
  wrongPinMessage,
} from './admin-register-copy';

function problem(status: number, slug: string, extra: Partial<ProblemDetail> = {}): ApiError {
  return new ApiError({
    type: `https://sahaj.in/problems/${slug}`,
    title: 'Words from the server that are never shown',
    status,
    ...extra,
  });
}

describe('admin register copy', () => {
  it('words the wrong PIN with the tries left, "1 try left" for one', () => {
    expect(wrongPinMessage(4)).toBe('That PIN is not right. 4 tries left.');
    expect(wrongPinMessage(3)).toBe('That PIN is not right. 3 tries left.');
    expect(wrongPinMessage(2)).toBe('That PIN is not right. 2 tries left.');
    expect(wrongPinMessage(1)).toBe('That PIN is not right. 1 try left.');
    expect(wrongPinMessage(undefined)).toBe('That PIN is not right.');
  });

  it('rounds the lock up to whole minutes, never below 1, and falls back to 60', () => {
    expect(lockedMinutes(3600)).toBe(60);
    expect(lockedMinutes(1800)).toBe(30);
    expect(lockedMinutes(61)).toBe(2);
    expect(lockedMinutes(1)).toBe(1);
    expect(lockedMinutes(undefined)).toBe(60);
    expect(lockedMinutes(0)).toBe(60);
    expect(lockedMessage(30)).toBe('Registration is locked. Try again in 30 min.');
  });

  it('maps every answer to the story text', () => {
    expect(describeBootstrapFailure(problem(0, 'x'))).toEqual({ kind: 'message', message: NO_CONNECTION });
    expect(describeBootstrapFailure(problem(404, 'not-found'))).toEqual({ kind: 'closed' });
    expect(describeBootstrapFailure(problem(423, 'bootstrap-locked', { retryAfterSeconds: 3600 }))).toEqual({
      kind: 'locked',
      minutes: 60,
    });
    expect(describeBootstrapFailure(problem(401, 'bootstrap-pin-invalid', { triesLeft: 1 }))).toEqual({
      kind: 'wrong-pin',
      message: 'That PIN is not right. 1 try left.',
    });
    expect(describeBootstrapFailure(problem(401, 'bootstrap-ticket-invalid'))).toEqual({ kind: 'ticket-expired' });
    expect(describeBootstrapFailure(problem(400, 'bad-request'))).toEqual({
      kind: 'message',
      message: SOMETHING_WENT_WRONG,
    });
    for (const status of [403, 413, 429, 500, 503]) {
      expect(describeBootstrapFailure(problem(status, 'x')), String(status)).toEqual({
        kind: 'message',
        message: SOMETHING_WENT_WRONG,
      });
    }
    expect(describeBootstrapFailure(new Error('boom'))).toEqual({ kind: 'message', message: SOMETHING_WENT_WRONG });
  });

  it('uses its own texts for field errors and never the server words', () => {
    const result = describeBootstrapFailure(
      problem(400, 'validation', {
        fieldErrors: [
          { field: 'fullName', message: 'server words 1' },
          { field: 'email', message: 'server words 2' },
          { field: 'password', message: 'server words 3' },
          { field: 'pin', message: 'server words 4' },
          { field: 'toString', message: 'not a field' },
        ],
      }),
    );
    expect(result).toEqual({
      kind: 'fields',
      fields: { fullName: NAME_ERROR, email: EMAIL_ERROR, password: PASSWORD_ERROR, pin: PIN_BLANK },
    });
  });

  it('shows the safety-net text for a validation answer that names no known field', () => {
    expect(describeBootstrapFailure(problem(400, 'validation', { fieldErrors: [{ field: 'x', message: 'y' }] }))).toEqual({
      kind: 'message',
      message: SOMETHING_WENT_WRONG,
    });
  });
});
