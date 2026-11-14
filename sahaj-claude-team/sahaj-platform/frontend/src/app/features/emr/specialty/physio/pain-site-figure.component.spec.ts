import { TestBed } from '@angular/core/testing';
import { PainSiteMarker } from '../specialty.model';
import { PainSiteFigureComponent, figureSex } from './pain-site-figure.component';

describe('PainSiteFigureComponent (SAH-75-5, OQ-75-3)', () => {
  function mount(marker: PainSiteMarker | null = null, disabled = false) {
    const fixture = TestBed.createComponent(PainSiteFigureComponent);
    fixture.componentRef.setInput('marker', marker);
    fixture.componentRef.setInput('disabled', disabled);
    const out: (PainSiteMarker | null)[] = [];
    fixture.componentInstance.markerChange.subscribe((m) => out.push(m));
    fixture.detectChanges();
    return { fixture, out, el: fixture.nativeElement as HTMLElement };
  }

  it('draws the front view with "R" and "L", and no marker until one is set', () => {
    const { el } = mount();
    expect(Array.from(el.querySelectorAll('.lr')).map((e) => e.textContent)).toEqual(['R', 'L']);
    expect(el.querySelector('[data-testid="pain-marker"]')).toBeNull();
    expect(el.querySelector('svg')!.getAttribute('aria-label')).toBe('Body, front view. Tap where it hurts.');
  });

  it('marks the region on Enter with one front-view marker', () => {
    const { el, out } = mount();
    const hit = el.querySelector<SVGPathElement>('path.hit')!;
    hit.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ side: 'FRONT' });
    expect(out[0]!.x).toBeGreaterThanOrEqual(0);
    expect(out[0]!.x).toBeLessThanOrEqual(1);
    expect(out[0]!.y).toBeLessThanOrEqual(1);
    expect(out[0]!.region).toMatch(/^[a-z][a-z0-9_]{1,39}$/);
  });

  it('shows the marker it is given and takes it off on the same region', () => {
    const first = mount();
    first.el.querySelector<SVGPathElement>('path.hit')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    const marker = first.out[0]!;
    TestBed.resetTestingModule();
    const { el, out } = mount(marker);
    expect(el.querySelector('[data-testid="pain-marker"]')).not.toBeNull();
    el.querySelector<SVGPathElement>('path.hit.on')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(out).toEqual([null]);
  });

  it('does nothing when the visit is read only', () => {
    const { el, out } = mount(null, true);
    el.querySelector<SVGPathElement>('path.hit')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(out).toEqual([]);
  });

  it('draws the figure of the recorded sex, "other" when it is not recorded', () => {
    expect(figureSex('F')).toBe('female');
    expect(figureSex('M')).toBe('male');
    expect(figureSex('O')).toBe('other');
    expect(figureSex(null)).toBe('other');
  });
});
