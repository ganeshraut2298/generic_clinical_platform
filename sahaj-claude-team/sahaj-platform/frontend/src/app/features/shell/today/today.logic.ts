import { nowMinutesIst } from '../../../shared/format/ist-date';

/** "Good morning/afternoon/evening" by the India clock: before 12 morning, before 17 afternoon. */
export function greetingFor(now: Date = new Date()): string {
  const hour = Math.floor(nowMinutesIst(now) / 60);
  if (hour < 12) {
    return 'Good morning';
  }
  return hour < 17 ? 'Good afternoon' : 'Good evening';
}

/** The name the server saves for a new owner until they enter their own (NEW_OWNER_PLACEHOLDER_NAME). */
const PLACEHOLDER_OWNER_NAME = 'owner';

/**
 * First name for the greeting. A leading "Dr" is kept with the next word ("Dr. Rao"). Empty when
 * there is no name yet, or when the saved name is only the placeholder "Owner".
 */
export function greetingName(fullName: string | undefined): string {
  const words = (fullName ?? '').trim().split(/\s+/).filter(Boolean);
  if (!words.length || (words.length === 1 && words[0].toLowerCase() === PLACEHOLDER_OWNER_NAME)) {
    return '';
  }
  return /^dr\.?$/i.test(words[0]) && words.length > 1 ? `${words[0]} ${words[1]}` : words[0];
}

/** Up to two initials, ignoring a leading "Dr". */
export function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter((w) => w && !/^dr\.?$/i.test(w));
  return words
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('');
}

export function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}
