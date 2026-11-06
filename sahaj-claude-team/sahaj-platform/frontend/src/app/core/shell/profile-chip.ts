/** What the top-bar profile chip shows: the person on the first line, their role under it. */
export interface ProfileChip {
  readonly primary: string;
  /** Empty when the name is missing or is only the role word, so the role is never shown twice. */
  readonly secondary: string;
}

/**
 * New owners are saved with the placeholder name "Owner" until they enter their own, so the name
 * can be empty or the same word as the role. In both cases show the role once, never
 * "Owner / Owner". The mockup shows "Dr. Meera · Owner" when there is a real name.
 */
export function profileChip(name: string | undefined, roleLabel: string): ProfileChip {
  const person = (name ?? '').trim();
  const role = roleLabel.trim();
  if (!person || person.toLowerCase() === role.toLowerCase()) {
    return { primary: role || person, secondary: '' };
  }
  return { primary: person, secondary: role };
}
