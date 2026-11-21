import { ApiError } from '../../core/http/problem-detail';

/** The slug of a problem type, for example `emr-setup-changed` from `https://sahaj.in/problems/emr-setup-changed`. */
export function problemSlug(error: unknown): string | null {
  if (!(error instanceof ApiError)) {
    return null;
  }
  const type = error.problem.type;
  const slash = type.lastIndexOf('/');
  return slash >= 0 ? type.slice(slash + 1) : type;
}

export function statusOf(error: unknown): number | null {
  return error instanceof ApiError ? error.status : null;
}

/** The network is down or the server did not answer (status 0). */
export function isOffline(error: unknown): boolean {
  return statusOf(error) === 0;
}

export const SETUP_CHANGED_TEXT = 'Someone else changed this department. Reload and try again.';
export const RECORD_CHANGED_TEXT = 'This EMR was changed elsewhere. Reload to see the latest.';

/** The field the server names when the chosen physiotherapy slot is not a real free slot (opd.yaml 0.6.2). */
export const PHYSIO_SLOT_FIELD = 'choices.physio.nextSlot';
/** The wording the IPD screens already use for 409 ipd-read-only. */
export const IPD_READ_ONLY_TEXT = 'IPD is read-only. Nothing can be changed.';

/** The server's own words when it gave any; otherwise the fallback. */
export function detailOr(error: unknown, fallback: string): string {
  return error instanceof ApiError ? (error.problem.detail ?? fallback) : fallback;
}

/**
 * The client gives up on a draft save, a finish check or a sign that has not answered after 30 seconds:
 * the server's own statement_timeout (DEC-054), so a request the server is still working on is not cut short.
 */
export const EMR_REQUEST_TIMEOUT_MS = 30_000;
/** What the doctor is told when the server did not answer in time. */
export const NO_ANSWER_TEXT = 'We could not reach the server. Try again.';
const CLIENT_TIMEOUT = 'client-timeout';

/** The error a request that did not answer in time ends with (status 0, like a lost connection). */
export function noAnswerError(): ApiError {
  return new ApiError({ type: CLIENT_TIMEOUT, title: 'The server did not answer', status: 0, detail: NO_ANSWER_TEXT });
}

export function isNoAnswer(error: unknown): boolean {
  return problemSlug(error) === CLIENT_TIMEOUT;
}
