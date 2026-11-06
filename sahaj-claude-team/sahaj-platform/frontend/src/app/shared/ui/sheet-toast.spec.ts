import { BOTTOM_SHEET_MAX_WIDTH, sheetToast } from './sheet-toast';

describe('sheetToast', () => {
  const original = window.matchMedia;

  afterEach(() => {
    window.matchMedia = original;
  });

  function screen(matches: boolean): ReturnType<typeof vi.fn> {
    const query = vi.fn((q: string) => ({ matches, media: q }) as MediaQueryList);
    window.matchMedia = query as unknown as typeof window.matchMedia;
    return query;
  }

  it('goes to the top on a phone, where the sheet is a bottom sheet', () => {
    const query = screen(true);
    expect(sheetToast(8000)).toEqual({ duration: 8000, verticalPosition: 'top' });
    expect(query).toHaveBeenCalledWith(`(max-width: ${BOTTOM_SHEET_MAX_WIDTH}px)`);
  });

  it('keeps the default place (bottom) on wider screens', () => {
    screen(false);
    expect(sheetToast(6000)).toEqual({ duration: 6000, verticalPosition: 'bottom' });
  });

  it('is announced by default and not when announce is false (the toast stays, the live region is off)', () => {
    screen(false);
    expect(sheetToast(8000).politeness).toBeUndefined();
    expect(sheetToast(8000, { announce: true }).politeness).toBeUndefined();
    expect(sheetToast(8000, { announce: false })).toEqual({ duration: 8000, verticalPosition: 'bottom', politeness: 'off' });
  });

  it('stays at the bottom where the browser cannot answer the question', () => {
    (window as { matchMedia?: unknown }).matchMedia = undefined;
    expect(sheetToast(8000).verticalPosition).toBe('bottom');
  });
});
