import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PATIENT } from './emr.fixtures';
import { EmrPatient } from './emr.model';
import { IdentityBarComponent } from './identity-bar.component';

describe('IdentityBarComponent', () => {
  let fixture: ComponentFixture<IdentityBarComponent>;

  function show(patient: EmrPatient): HTMLElement {
    fixture = TestBed.createComponent(IdentityBarComponent);
    fixture.componentRef.setInput('patient', patient);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }
  const text = (el: HTMLElement, sel: string) => (el.querySelector(sel)?.textContent ?? '').replace(/\s+/g, ' ').trim();

  beforeEach(() => TestBed.configureTestingModule({ imports: [IdentityBarComponent] }));

  it('shows the name in bold and the small line with the bed', () => {
    const el = show(PATIENT);
    expect(el.querySelector('b')!.textContent).toBe('Asha Patil');
    expect(text(el, '[data-testid=identity-line]')).toBe('UH-0001 · 42 · Female · bed 101-A');
  });

  it('shows a red chip with an icon and the allergy text for a recorded allergy', () => {
    const el = show(PATIENT);
    const chip = el.querySelector('[data-testid=allergy-chip]')!;
    expect(chip.classList).toContain('bad');
    expect(chip.querySelector('app-icon')).not.toBeNull();
    expect(text(el, '[data-testid=allergy-chip]')).toBe('Allergy: Penicillin (rash)');
  });

  it('shows the green chip only for NONE_KNOWN', () => {
    const el = show({ ...PATIENT, allergyText: 'None known', allergyStatus: 'NONE_KNOWN' });
    expect(el.querySelector('[data-testid=allergy-chip]')!.classList).toContain('ok');
    expect(text(el, '[data-testid=allergy-chip]')).toBe('No known allergies');
  });

  it('shows "Allergies not recorded" in the warn style', () => {
    const el = show({ ...PATIENT, allergyText: null, allergyStatus: 'NOT_RECORDED' });
    expect(el.querySelector('[data-testid=allergy-chip]')!.classList).toContain('nr');
    expect(text(el, '[data-testid=allergy-chip]')).toBe('Allergies not recorded');
  });

  it('follows the patient when it changes', () => {
    const el = show(PATIENT);
    fixture.componentRef.setInput('patient', { ...PATIENT, name: 'Ravi Shah', bedCode: '202-B' });
    fixture.detectChanges();
    expect(el.querySelector('b')!.textContent).toBe('Ravi Shah');
    expect(text(el, '[data-testid=identity-line]')).toContain('bed 202-B');
  });
});
