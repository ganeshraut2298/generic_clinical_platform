/**
 * The height an element takes in the layout, fractions kept, ignoring any transform on it or on an ancestor.
 *
 * `getBoundingClientRect().height` is the transformed (painted) size: while a dialog opens with a scale animation it
 * is smaller than the layout size, and a ResizeObserver does not fire again when the animation ends because the layout
 * size never changed. The computed `height` is the layout value, so the border box is rebuilt from it. Where the
 * browser gives no number (`auto` on an element that is not displayed), `offsetHeight` is the fallback.
 */
export function layoutHeight(el: HTMLElement): number {
  const style = getComputedStyle(el);
  const height = parseFloat(style.height);
  if (!Number.isFinite(height)) {
    return el.offsetHeight;
  }
  if (style.boxSizing === 'border-box') {
    return height;
  }
  const edges = [style.paddingTop, style.paddingBottom, style.borderTopWidth, style.borderBottomWidth];
  return height + edges.reduce((sum, edge) => sum + (parseFloat(edge) || 0), 0);
}
