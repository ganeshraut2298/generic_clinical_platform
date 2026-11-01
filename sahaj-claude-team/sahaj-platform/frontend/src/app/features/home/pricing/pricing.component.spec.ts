import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideReducedMotion } from '../../../testing/motion-testing';
import { EnquirySheet } from '../enquiry/enquiry-sheet.service';
import { PricingSectionComponent } from './pricing.component';
import { planPrice } from './pricing.data';

describe('planPrice', () => {
  it('gives Professional ₹1,999 a year or ₹299 a month', () => {
    expect(planPrice('pro', true)).toEqual({ amount: '₹1,999', per: 'per year', quoted: false });
    expect(planPrice('pro', false)).toEqual({ amount: '₹299', per: 'per month', quoted: false });
  });

  it('keeps Free at ₹0 for 12 months whatever the period', () => {
    expect(planPrice('free', true)).toEqual({ amount: '₹0', per: 'for 12 months', quoted: false });
    expect(planPrice('free', false)).toEqual(planPrice('free', true));
  });

  it('shows Multi-Doctor as a quote and Custom as "Let\'s talk"', () => {
    expect(planPrice('multi', true)).toMatchObject({ amount: 'Per doctor', per: 'quote on request' });
    expect(planPrice('custom', false)).toMatchObject({ amount: "Let's talk", per: '' });
  });
});

describe('PricingSectionComponent', () => {
  let fixture: ComponentFixture<PricingSectionComponent>;
  const open = vi.fn();

  beforeEach(async () => {
    open.mockClear();
    await TestBed.configureTestingModule({
      imports: [PricingSectionComponent],
      providers: [
        provideRouter([]),
        provideReducedMotion(true),
        { provide: EnquirySheet, useValue: { open } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PricingSectionComponent);
    fixture.detectChanges();
    await fixture.whenStable();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const cards = () => Array.from(el().querySelectorAll<HTMLElement>('article.price'));
  const card = (name: string) => cards().find((c) => c.querySelector('h3')!.textContent === name)!;
  const amount = (name: string) => card(name).querySelector('.amt')!.textContent!.replace(/\s+/g, ' ').trim();

  async function chooseToggle(group: string, label: string) {
    const toggle = Array.from(
      el().querySelectorAll<HTMLElement>(`mat-button-toggle-group[aria-label="${group}"] mat-button-toggle`),
    ).find((t) => t.textContent!.trim() === label)!;
    toggle.querySelector('button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('shows four plans in order', () => {
    expect(cards().map((c) => c.querySelector('h3')!.textContent)).toEqual([
      'Free',
      'Professional',
      'Multi-Doctor',
      'Custom',
    ]);
  });

  it('starts on the yearly price', () => {
    expect(amount('Professional')).toBe('₹1,999 per year');
    expect(el().querySelector('[aria-label="Billing period"]')!.textContent).toContain(
      'Yearly · save 44%',
    );
  });

  it('shows ₹299 per month after choosing Monthly, and ₹1,999 per year after choosing Yearly', async () => {
    await chooseToggle('Billing period', 'Monthly');
    expect(amount('Professional')).toBe('₹299 per month');
    await chooseToggle('Billing period', 'Yearly · save 44%');
    expect(amount('Professional')).toBe('₹1,999 per year');
  });

  it('does not change the other plans when the period changes', async () => {
    const before = ['Free', 'Multi-Doctor', 'Custom'].map(amount);
    await chooseToggle('Billing period', 'Monthly');
    expect(['Free', 'Multi-Doctor', 'Custom'].map(amount)).toEqual(before);
    expect(amount('Free')).toBe('₹0 for 12 months');
    expect(amount('Multi-Doctor')).toBe('Per doctor quote on request');
    expect(amount('Custom')).toBe("Let's talk");
  });

  it('says prices exclude 18% GST', () => {
    expect(el().textContent).toContain('Prices exclude 18% GST.');
  });

  it('opens the enquiry sheet from Contact sales on Multi-Doctor and Custom', () => {
    for (const name of ['Multi-Doctor', 'Custom']) {
      const button = card(name).querySelector<HTMLButtonElement>('button.cta')!;
      expect(button.textContent!.trim()).toBe('Contact sales');
      button.click();
    }
    expect(open).toHaveBeenCalledTimes(2);
    expect(open).toHaveBeenCalledWith('CONTACT_SALES');
  });

  it('links Start free on Free and Professional to the sign-up screen in New clinic mode', () => {
    for (const name of ['Free', 'Professional']) {
      const link = card(name).querySelector<HTMLAnchorElement>('a.cta')!;
      expect(link.textContent!.trim()).toBe('Start free');
      expect(link.getAttribute('href')).toBe('/login?mode=signup');
    }
  });

  it('marks the plans that fit the chosen clinic size', async () => {
    expect(card('Free').classList.contains('match')).toBe(true);
    expect(card('Multi-Doctor').classList.contains('match')).toBe(false);
    await chooseToggle('Clinic size', 'Branches');
    expect(card('Custom').classList.contains('match')).toBe(true);
    expect(card('Free').classList.contains('match')).toBe(false);
    expect(el().textContent).toContain('Branch 2 · Wakad');
  });
});
