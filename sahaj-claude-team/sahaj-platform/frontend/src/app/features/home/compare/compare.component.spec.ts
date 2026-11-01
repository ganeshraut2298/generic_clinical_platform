import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CompareSectionComponent } from './compare.component';

describe('CompareSectionComponent', () => {
  let fixture: ComponentFixture<CompareSectionComponent>;
  let component: CompareSectionComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CompareSectionComponent],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(CompareSectionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const result = () => el().querySelector('aside')!.textContent!.replace(/\s+/g, ' ');

  async function update() {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function choosePreset(label: string) {
    const option = Array.from(el().querySelectorAll<HTMLButtonElement>('.cmpopt')).find((b) =>
      b.textContent!.includes(label),
    )!;
    option.click();
    await update();
  }

  const rowButtons = () => Array.from(el().querySelectorAll<HTMLButtonElement>('button.tri'));
  const stateOf = (i: number) => rowButtons()[i].textContent!.replace('~', '').trim();

  it('lists the four options and the 16 features', () => {
    const options = Array.from(el().querySelectorAll('.cmpopt b')).map((b) => b.textContent);
    expect(options).toEqual([
      'Paper register and files',
      'Basic billing software',
      'Typical cloud clinic software',
      'Low-cost HMIS',
    ]);
    expect(rowButtons().length).toBe(16);
  });

  it('starts with typical cloud software chosen and the typical-state hint', () => {
    expect(el().querySelector('.cmpopt[aria-pressed="true"] b')!.textContent).toBe(
      'Typical cloud clinic software',
    );
    expect(el().textContent).toContain('Click a row to match yours.');
    expect(el().textContent).not.toContain('Adjusted by you');
  });

  it('labels the numbers as estimates', () => {
    expect(result()).toContain('Estimates from the numbers you enter.');
  });

  it('shows the saving over 3 years in Indian format', () => {
    // (3,000 + 500) x 36 = 1,26,000 minus Sahaj 3,998
    expect(result()).toContain('You save ₹1,22,002 over 3 years');
    expect(result()).toContain('₹1,26,000');
    expect(result()).toContain('₹3,998');
  });

  it('updates the estimates when a slider moves', async () => {
    component['setSlider']('ppd', 100);
    await update();
    // 100 x 26 x 12 = 31,200 visits; x 30% x 30% x 25% = 702
    expect(result()).toContain('702');
    expect(el().textContent).toContain('100');
  });

  describe('more than 2 doctors', () => {
    it('shows the custom plan message and a quote instead of a Sahaj price', async () => {
      component['setSlider']('doctors', 3);
      await update();
      expect(result()).toContain('Clinics with 3 doctors are on a custom plan.');
      expect(result()).toContain('Ask us for a quote.');
      expect(result()).toContain('Quote');
      expect(result()).not.toContain('You save');
    });

    it('goes back to the saving message at 2 doctors', async () => {
      component['setSlider']('doctors', 4);
      await update();
      component['setSlider']('doctors', 2);
      await update();
      expect(result()).toContain('You save');
    });
  });

  describe('choosing what you use today', () => {
    it('resets the feature table to that option', async () => {
      const cloud = rowButtons().map((b) => b.textContent!.trim());
      await choosePreset('Paper register');
      const paper = rowButtons().map((b) => b.textContent!.trim());
      expect(paper.every((s) => s === 'No')).toBe(true);
      expect(paper).not.toEqual(cloud);
      expect(el().querySelector('.cmpopt[aria-pressed="true"] b')!.textContent).toBe(
        'Paper register and files',
      );
    });

    it('resets the costs to that option', async () => {
      await choosePreset('Basic billing software');
      expect(component.state().spend).toBe(800);
      expect(component.state().msg).toBe(0);
      const money = el().querySelectorAll<HTMLInputElement>('input.num');
      expect(money[0].value).toBe('800');
      expect(money[1].value).toBe('0');
    });

    it('clears "Adjusted by you" and the edits the visitor made', async () => {
      rowButtons()[0].click();
      await update();
      expect(el().textContent).toContain('Adjusted by you');
      await choosePreset('Low-cost HMIS');
      expect(el().textContent).not.toContain('Adjusted by you');
      expect(el().textContent).toContain('Click a row to match yours.');
    });

    it('says "than paper" for paper and "than today" for the others', async () => {
      await choosePreset('Paper register');
      component['setSlider']('doctors', 1);
      component['setMoney']('spend', '0');
      component['setMoney']('msg', '0');
      await update();
      expect(result()).toContain('₹0 in your first year');
      expect(result()).toContain('than paper');
    });
  });

  describe('clicking a feature row', () => {
    beforeEach(async () => {
      await choosePreset('Paper register');
    });

    it('cycles No, Partly, Yes and back to No', async () => {
      expect(stateOf(0)).toBe('No');
      rowButtons()[0].click();
      await update();
      expect(stateOf(0)).toBe('Partly');
      rowButtons()[0].click();
      await update();
      expect(stateOf(0)).toBe('Yes');
      rowButtons()[0].click();
      await update();
      expect(stateOf(0)).toBe('No');
    });

    it('shows "Adjusted by you" after the first click', async () => {
      expect(el().textContent).not.toContain('Adjusted by you');
      rowButtons()[2].click();
      await update();
      expect(el().textContent).toContain('Adjusted by you');
    });

    it('names the state for screen readers', async () => {
      rowButtons()[0].click();
      await update();
      expect(rowButtons()[0].getAttribute('aria-label')).toBe(
        'Walk-in token by mobile number in your software: Partly. Click to change.',
      );
    });

    it('moves the feature bar and the list of what you would add', async () => {
      expect(result()).toContain('Features (of 16)');
      expect(result()).toMatch(/Today\s*0\s*Sahaj/);
      rowButtons()[0].click();
      rowButtons()[0].click();
      await update();
      expect(result()).toMatch(/Today\s*1\s*Sahaj/);
    });
  });

  it('has a Start free for 1 year link to the sign-up screen', () => {
    const link = Array.from(el().querySelectorAll<HTMLAnchorElement>('aside a')).find((a) =>
      a.textContent!.includes('Start free for 1 year'),
    )!;
    expect(link.getAttribute('href')).toBe('/login?mode=signup');
  });
});
