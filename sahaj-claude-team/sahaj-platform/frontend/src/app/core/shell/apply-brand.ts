import { Brand } from '../platform/platform.model';
import { Theme } from './shell.model';

/**
 * Applies the clinic's brand colour and the light/dark theme to `<html>`, matching
 * `docs/design/tokens.md`: `data-brand` picks the palette, `data-theme="dark"` switches theme.
 * `custom` with a hex sets `--pri` directly so a clinic can use its own colour.
 */
export function applyBrand(brand: Brand, brandHex?: string): void {
  const root = document.documentElement;
  if (brand === 'custom' && brandHex) {
    root.style.setProperty('--pri', brandHex);
    root.dataset['brand'] = 'custom';
    return;
  }
  root.style.removeProperty('--pri');
  if (brand && brand !== 'blue' && brand !== 'custom') {
    root.dataset['brand'] = brand;
  } else {
    delete root.dataset['brand'];
  }
}

export function applyTheme(theme: Theme): void {
  const root = document.documentElement;
  if (theme === 'dark') {
    root.dataset['theme'] = 'dark';
  } else {
    delete root.dataset['theme'];
  }
}
