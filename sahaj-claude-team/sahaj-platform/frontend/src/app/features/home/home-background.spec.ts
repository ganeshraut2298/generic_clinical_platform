import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideReducedMotion } from '../../testing/motion-testing';
import { KineticBackgroundComponent } from '../../shared/ui/kinetic-background/kinetic-background.component';
import { HomePage } from './home.page';

/** SAH-39: the ambient background is mounted once, in the home page only, and the blocks carry the contract attributes. */
describe('HomePage ambient background', () => {
  let fixture: ComponentFixture<HomePage>;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    await TestBed.configureTestingModule({
      imports: [HomePage],
      providers: [provideRouter([]), provideReducedMotion(true)],
    }).compileComponents();
    fixture = TestBed.createComponent(HomePage);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => vi.restoreAllMocks());

  it('mounts the background once, before and outside the content wrapper', () => {
    const layers = el().querySelectorAll('app-kinetic-background');
    expect(layers.length).toBe(1);
    const wrap = el().querySelector('.pubwrap')!;
    expect(wrap.contains(layers[0])).toBe(false);
    expect(layers[0].compareDocumentPosition(wrap) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('uses the component with its default inputs', () => {
    const debug = fixture.debugElement.query((d) => d.componentInstance instanceof KineticBackgroundComponent);
    const layer = debug.componentInstance as KineticBackgroundComponent;
    expect(layer.interactionRadius()).toBe(90);
    expect(layer.gridSpacing()).toBe(28);
    expect(layer.friction()).toBe(0.75);
    expect(layer.enabled()).toBe(true);
  });

  it('marks exactly the header and navigation as data-dot-exclude', () => {
    const excluded = el().querySelectorAll('[data-dot-exclude]');
    expect(excluded.length).toBe(1);
    expect(excluded[0].tagName).toBe('HEADER');
    expect(excluded[0].querySelector('nav')).not.toBeNull();
    expect(excluded[0].hasAttribute('data-surface')).toBe(true);
  });

  it('puts the frosted glass on the hero text block only', () => {
    const glass = el().querySelectorAll('.surface-glass');
    expect(glass.length).toBe(1);
    expect(glass[0].classList.contains('hero-t')).toBe(true);
    expect(glass[0].hasAttribute('data-surface')).toBe(true);
    expect(glass[0].querySelector('h1')).not.toBeNull();
  });

  it('puts solid white and data-surface on all nine section heads', () => {
    const heads = Array.from(el().querySelectorAll('app-section-head'));
    expect(heads.length).toBe(9);
    for (const head of heads) {
      expect(head.classList.contains('surface-solid')).toBe(true);
      expect(head.hasAttribute('data-surface')).toBe(true);
    }
  });

  it('marks cards, forms, tables, FAQ, pricing and compare as data-surface', () => {
    const surfaceCount = (selector: string) => el().querySelectorAll(`${selector}[data-surface]`).length;
    const total = (selector: string) => el().querySelectorAll(selector).length;
    for (const selector of [
      '.hpanel',
      '.ssteps',
      '.spanel',
      '.palette',
      '.canvas',
      '.visit',
      '.member',
      '.jdetail',
      '.spcards',
      '.spprev',
      '.calc-in',
      '.calc-out',
      '.grow',
      '.price',
      '.tpanel',
      '.faq',
      'section.final',
      'footer',
    ]) {
      expect(total(selector), selector).toBeGreaterThan(0);
      expect(surfaceCount(selector), selector).toBe(total(selector));
    }
  });

  it('wraps the scrolling journey rail and the billing toggle instead of adding the class to them', () => {
    const rail = el().querySelector('.jrail-wrap')!;
    expect(rail.classList.contains('surface-solid')).toBe(false);
    expect(rail.parentElement!.classList.contains('surface-solid')).toBe(true);
    const toggle = el().querySelector('mat-button-toggle-group[aria-label="Billing period"]')!;
    expect(toggle.parentElement!.classList.contains('surface-solid')).toBe(true);
    expect(toggle.parentElement!.classList.contains('period')).toBe(true);
  });

  it('leaves no canvas and a working page when the browser cannot draw', () => {
    expect(el().querySelectorAll('canvas').length).toBe(0);
    expect(el().querySelector('app-kinetic-background')!.getAttribute('data-state')).toBe('off');
    expect(el().querySelectorAll('main h1, main h2').length).toBe(11);
  });

  it('paints the role cards and the selected specialty card on white (the global .member rule is transparent)', () => {
    const rules = Array.from(document.head.querySelectorAll('style'))
      .flatMap((style) => (style.textContent ?? '').match(/[^{}]+\{[^{}]*\}/g) ?? [])
      .filter((rule) => rule.includes('var(--surface)'));
    expect(rules.some((rule) => /\.member\b[^{]*\{/.test(rule) && !/aria-pressed/.test(rule))).toBe(true);
    expect(rules.some((rule) => rule.includes('aria-pressed') && rule.includes('var(--pri-soft)'))).toBe(true);
  });
});
