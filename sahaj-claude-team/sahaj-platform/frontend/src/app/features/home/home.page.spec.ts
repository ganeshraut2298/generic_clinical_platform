import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideReducedMotion } from '../../testing/motion-testing';
import { HomePage } from './home.page';

describe('HomePage', () => {
  let fixture: ComponentFixture<HomePage>;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HomePage],
      providers: [provideRouter([]), provideReducedMotion(true)],
    }).compileComponents();
    fixture = TestBed.createComponent(HomePage);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  it('shows the sections in mockup order', () => {
    const order = Array.from(el().querySelectorAll('main h1, main h2')).map((h) => h.id);
    expect(order).toEqual([
      'h-hero',
      'h-setup',
      'h-workflow',
      'h-roles',
      'h-journey',
      'h-specialties',
      'h-compare',
      'h-pricing',
      'h-trust',
      'h-faq',
      'h-final',
    ]);
    const footer = el().querySelector('footer')!;
    const last = el().querySelector('app-final-cta')!;
    expect(last.compareDocumentPosition(footer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('has the sticky header with brand, section links, Admin portal, Log in and Start Free', () => {
    const header = el().querySelector('app-home-header')!;
    const links = Array.from(header.querySelectorAll('nav button')).map((b) => b.textContent!.trim());
    expect(links).toEqual(['How it works', 'Workflow', 'Specialties', 'Compare', 'Pricing']);
    expect(header.querySelector('.brand')!.textContent).toContain('Sahaj');
    const hrefs = Array.from(header.querySelectorAll('.actions a')).map((a) => [
      a.textContent!.trim(),
      a.getAttribute('href'),
    ]);
    expect(hrefs).toEqual([
      ['Admin portal', '/admin'],
      ['Log in', '/login?mode=login'],
      ['Start Free', '/login?mode=signup'],
    ]);
  });

  it('scrolls to the section when a section link is used', () => {
    const target = el().querySelector('#pricing') as HTMLElement;
    target.scrollIntoView = vi.fn();
    const link = Array.from(el().querySelectorAll<HTMLButtonElement>('app-home-header nav button')).find(
      (b) => b.textContent!.trim() === 'Pricing',
    )!;
    document.body.appendChild(el());
    link.click();
    expect(target.scrollIntoView).toHaveBeenCalled();
    el().remove();
  });

  it('sends the hero Start Free to the sign-up screen in New clinic mode', () => {
    const start = Array.from(el().querySelectorAll<HTMLAnchorElement>('app-hero a')).find((a) =>
      a.textContent!.includes('Start Free'),
    )!;
    expect(start.getAttribute('href')).toBe('/login?mode=signup');
  });

  it('opens no demo clinic: the link "Or open the demo clinic now" is not shown', () => {
    expect(el().textContent).not.toContain('demo clinic');
  });

  it('leaves out the mockup note about the 15-minute promise', () => {
    expect(el().textContent).not.toContain('Mockup note');
    expect(el().textContent).not.toContain('pilot usability tests');
  });

  it('says patient data is hosted in India and prices exclude GST in the footer', () => {
    const footer = el().querySelector('footer')!.textContent!;
    expect(footer).toContain('Patient data hosted in India');
    expect(footer).toContain('Prices exclude GST');
  });

  it('has Free for your first year in the hero and the plan limits in pricing', () => {
    expect(el().querySelector('app-hero')!.textContent).toContain('Free for your first year');
    expect(el().querySelector('app-pricing-section')!.textContent).toContain(
      'Up to 2 doctors and 5 users',
    );
  });

  it('keeps the page headings in order: one h1 only', () => {
    expect(el().querySelectorAll('h1').length).toBe(1);
  });
});
