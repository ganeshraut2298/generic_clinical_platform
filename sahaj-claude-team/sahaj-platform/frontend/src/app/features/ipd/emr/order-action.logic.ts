import { ApiError } from '../../../core/http/problem-detail';
import { problemMessage } from '../../config/config-format';
import { isOutsideScope } from '../ipd-refusal';
import { KEY_RELOAD, OrderActionResult, OrderErrors, orderFieldErrors } from './emr.model';

/**
 * What a sheet does with a refused Change, Hold, Stop or Reply (docs/api/ipd.yaml, SAH-76-1 and 76-2):
 * - `scope`: 403 outside-scope; the EMR sheet leaves the patient (SAH-34).
 * - `fields`: 400 with field messages; the sheet stays open and shows them, a bad clientKey makes a new key.
 * - `conflict`: 404 or 409 (order-changed, order-state, not-admitted, ipd-readonly ...); the sheet closes, the
 *   EMR sheet reads again and shows the server's text. A 409 `client-key-reused` is not this: the typing is
 *   kept, the key is replaced and the next try is a new request.
 * - `stay`: anything else (403, 5xx, network): the sheet stays open with the text and what was typed.
 */
export type ActionFailure =
  | { kind: 'scope' }
  | { kind: 'fields'; errors: OrderErrors; other: string | null; newKey: boolean }
  | { kind: 'conflict'; result: OrderActionResult }
  | { kind: 'stay'; message: string; newKey: boolean };

export function classifyFailure(error: unknown, fallback: string): ActionFailure {
  if (isOutsideScope(error)) {
    return { kind: 'scope' };
  }
  if (error instanceof ApiError) {
    if (error.status === 400) {
      const { shown, other, newKey } = orderFieldErrors(error.fieldErrors);
      if (Object.keys(shown).length > 0 || other !== null) {
        return { kind: 'fields', errors: shown, other, newKey };
      }
      return { kind: 'stay', message: problemMessage(error, fallback), newKey: false };
    }
    if (error.status === 409 && error.problem.type.endsWith('client-key-reused')) {
      return { kind: 'stay', message: problemMessage(error, KEY_RELOAD), newKey: true };
    }
    if (error.status === 409 || error.status === 404) {
      return { kind: 'conflict', result: { kind: 'conflict', message: problemMessage(error, fallback) } };
    }
  }
  return { kind: 'stay', message: problemMessage(error, fallback), newKey: false };
}
