import { applyBrand, applyTheme } from './apply-brand';

describe('applyBrand / applyTheme', () => {
  afterEach(() => {
    delete document.documentElement.dataset['brand'];
    delete document.documentElement.dataset['theme'];
    document.documentElement.style.removeProperty('--pri');
  });

  it('sets data-brand for a named brand', () => {
    applyBrand('teal');
    expect(document.documentElement.dataset['brand']).toBe('teal');
  });

  it('removes data-brand for the default blue', () => {
    applyBrand('teal');
    applyBrand('blue');
    expect(document.documentElement.dataset['brand']).toBeUndefined();
  });

  it('sets --pri directly for a custom hex', () => {
    applyBrand('custom', '#123456');
    expect(document.documentElement.style.getPropertyValue('--pri')).toBe('#123456');
    expect(document.documentElement.dataset['brand']).toBe('custom');
  });

  it('sets and clears data-theme', () => {
    applyTheme('dark');
    expect(document.documentElement.dataset['theme']).toBe('dark');
    applyTheme('light');
    expect(document.documentElement.dataset['theme']).toBeUndefined();
  });
});
