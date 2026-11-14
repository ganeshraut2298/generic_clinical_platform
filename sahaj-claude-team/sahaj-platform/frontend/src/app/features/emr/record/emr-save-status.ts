import { formatTimeOfInstant } from '../../../shared/format/ist-date';

/**
 * The small text beside "Save draft" (SAH-51-3): "Saving…", "Saved 11:05 AM", "Offline. Your changes
 * are not saved yet." and "Not saved: <reason>". It is read aloud by a screen reader (aria-live
 * polite). The values themselves are held in the open page only; nothing goes to browser storage.
 */
export type SaveState =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved'; at: string }
  | { kind: 'offline' }
  | { kind: 'failed'; reason: string };

export const OFFLINE_TEXT = 'Offline. Your changes are not saved yet.';

export function saveStatusText(state: SaveState): string {
  switch (state.kind) {
    case 'saving':
      return 'Saving…';
    case 'saved':
      return `Saved ${formatTimeOfInstant(state.at)}`;
    case 'offline':
      return OFFLINE_TEXT;
    case 'failed':
      return `Not saved: ${state.reason}`;
    default:
      return '';
  }
}

/** "Dr. Mehta": a staff name shown with its title once. */
export function drName(name: string): string {
  return /^dr\.?\s/i.test(name.trim()) ? name.trim() : `Dr. ${name.trim()}`;
}
