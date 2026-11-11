import { ApiError } from '../../core/http/problem-detail';

/** Any 403 (`forbidden`, `module-off` ...): the server no longer lets this user see the data (DEC-025 G-2, SAH-40). */
export function isRefusal(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

/** 403 `module-off`: IPD is switched off for the clinic. */
export function isModuleOff(error: unknown): boolean {
  return isRefusal(error) && (error as ApiError).problem.type.endsWith('module-off');
}

/**
 * SAH-34 403 `outside-scope`: the patient is not in the caller's wards (after a transfer, or after an
 * emergency access ended). It is about one patient, not about the page: a sheet closes and the page is
 * not locked, so it is told apart from `forbidden` and `module-off`.
 */
export function isOutsideScope(error: unknown): boolean {
  return isRefusal(error) && (error as ApiError).problem.type.endsWith('outside-scope');
}

/**
 * Whether keyboard focus is about to be lost by a refusal (SAH-40): it is inside the screen, or it fell
 * to the page body because the button that had it (Try again) was replaced by the Loading text.
 */
export function focusIsLost(host: HTMLElement, doc: Document, afterTryAgain: boolean): boolean {
  const active = doc.activeElement;
  if (active !== null && host.contains(active)) {
    return true;
  }
  return afterTryAgain && (active === null || active === doc.body);
}
