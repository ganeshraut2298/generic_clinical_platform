import { ApiError } from '../../core/http/problem-detail';
import { focusIsLost, isModuleOff, isOutsideScope, isRefusal } from './ipd-refusal';

const error = (status: number, slug: string) =>
  new ApiError({ type: `https://sahaj.in/problems/${slug}`, title: slug, status, detail: 'x' });

describe('ipd-refusal', () => {
  it('treats every 403 as a refusal', () => {
    expect(isRefusal(error(403, 'forbidden'))).toBe(true);
    expect(isRefusal(error(403, 'module-off'))).toBe(true);
  });

  it('does not treat other statuses or other errors as a refusal', () => {
    expect(isRefusal(error(401, 'unauthorized'))).toBe(false);
    expect(isRefusal(error(409, 'case-closed'))).toBe(false);
    expect(isRefusal(error(503, 'unavailable'))).toBe(false);
    expect(isRefusal(new Error('network'))).toBe(false);
    expect(isRefusal(null)).toBe(false);
  });

  it('knows module-off only when the status is 403', () => {
    expect(isModuleOff(error(403, 'module-off'))).toBe(true);
    expect(isModuleOff(error(403, 'forbidden'))).toBe(false);
    expect(isModuleOff(error(409, 'module-off'))).toBe(false);
    expect(isModuleOff(undefined)).toBe(false);
  });

  it('tells outside-scope (one patient) from forbidden and module-off (SAH-34)', () => {
    expect(isOutsideScope(error(403, 'outside-scope'))).toBe(true);
    expect(isOutsideScope(error(403, 'forbidden'))).toBe(false);
    expect(isOutsideScope(error(403, 'module-off'))).toBe(false);
    expect(isOutsideScope(error(409, 'outside-scope'))).toBe(false);
    expect(isOutsideScope(new Error('network'))).toBe(false);
    expect(isRefusal(error(403, 'outside-scope'))).toBe(true);
    expect(isModuleOff(error(403, 'outside-scope'))).toBe(false);
  });

  describe('focusIsLost', () => {
    let host: HTMLElement;
    let inside: HTMLButtonElement;
    let outside: HTMLButtonElement;

    beforeEach(() => {
      host = document.createElement('div');
      inside = document.createElement('button');
      outside = document.createElement('button');
      host.appendChild(inside);
      document.body.append(host, outside);
    });

    afterEach(() => {
      host.remove();
      outside.remove();
    });

    it('is lost when focus is inside the screen', () => {
      inside.focus();
      expect(focusIsLost(host, document, false)).toBe(true);
    });

    it('is lost on the body only after Try again', () => {
      (document.activeElement as HTMLElement | null)?.blur();
      expect(document.activeElement).toBe(document.body);
      expect(focusIsLost(host, document, true)).toBe(true);
      expect(focusIsLost(host, document, false)).toBe(false);
    });

    it('is not lost when focus is on another control', () => {
      outside.focus();
      expect(focusIsLost(host, document, true)).toBe(false);
    });
  });
});
