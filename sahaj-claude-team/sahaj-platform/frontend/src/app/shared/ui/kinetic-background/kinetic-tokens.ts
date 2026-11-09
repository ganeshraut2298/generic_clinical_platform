import { KineticColours, TOKEN_FALLBACKS, TOKEN_NAMES } from './kinetic-background.config';

/**
 * Reads the colours from the theme. A token that is missing, empty or rejected by `isColour`
 * gives the spec value instead, so the page always works.
 */
export function readTokens(
  style: Pick<CSSStyleDeclaration, 'getPropertyValue'>,
  isColour: (value: string) => boolean = () => true,
): KineticColours {
  const result: KineticColours = { ...TOKEN_FALLBACKS };
  for (const key of Object.keys(TOKEN_NAMES) as (keyof KineticColours)[]) {
    let value: string;
    try {
      value = (style.getPropertyValue(TOKEN_NAMES[key]) ?? '').trim();
    } catch {
      value = '';
    }
    if (value && isColour(value)) {
      result[key] = value;
    }
  }
  return result;
}
