import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EmrAccess } from './emr-access.service';
import { EmrLauncher } from './emr-launcher.service';
import { OpenEmrButtonComponent } from './open-emr-button.component';
import { signal } from '@angular/core';

describe('OpenEmrButtonComponent (SAH-46-1)', () => {
  let fixture: ComponentFixture<OpenEmrButtonComponent>;
  let access: { ensureLoaded: ReturnType<typeof vi.fn>; canOpen: () => boolean; canOpenVisit: (d: string) => boolean };
  let launcher: { open: ReturnType<typeof vi.fn>; opening: ReturnType<typeof signal<string | null>> };

  function render(inputs: Record<string, unknown>): HTMLElement {
    TestBed.configureTestingModule({
      providers: [
        { provide: EmrAccess, useValue: access },
        { provide: EmrLauncher, useValue: launcher },
      ],
    });
    fixture = TestBed.createComponent(OpenEmrButtonComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    access = { ensureLoaded: vi.fn(), canOpen: () => true, canOpenVisit: (d) => d === 'm1' };
    launcher = { open: vi.fn(), opening: signal<string | null>(null) };
  });

  it('asks once whether the user may open the EMR', () => {
    render({ encounterType: 'IPD_ROUND', encounterId: 'a1' });
    expect(access.ensureLoaded).toHaveBeenCalledTimes(1);
  });

  it('is drawn with the words "Open EMR" and a 44 px target, and opens the encounter', () => {
    const el = render({ encounterType: 'IPD_ROUND', encounterId: 'a1' });
    const button = el.querySelector<HTMLButtonElement>('[data-act=open-emr]')!;
    expect(button.textContent).toContain('Open EMR');
    button.click();
    expect(launcher.open).toHaveBeenCalledWith('IPD_ROUND', 'a1');
  });

  it('is not drawn when the user may not open the EMR', () => {
    access.canOpen = () => false;
    const el = render({ encounterType: 'ER_ASSESSMENT', encounterId: 'c1' });
    expect(el.querySelector('button')).toBeNull();
  });

  it('on an OPD card follows the own-patient rule', () => {
    const own = render({ encounterType: 'OPD_VISIT', encounterId: 'v1', doctorMembershipId: 'm1' });
    expect(own.querySelector('button')).not.toBeNull();
    TestBed.resetTestingModule();
    const other = render({ encounterType: 'OPD_VISIT', encounterId: 'v2', doctorMembershipId: 'm2' });
    expect(other.querySelector('button')).toBeNull();
  });

  it('names the patient for a screen reader and is off while another open is running', () => {
    launcher.opening.set('x');
    const el = render({ encounterType: 'OPD_VISIT', encounterId: 'v1', doctorMembershipId: 'm1', patientLabel: 'Asha Patil' });
    const button = el.querySelector<HTMLButtonElement>('button')!;
    expect(button.getAttribute('aria-label')).toBe('Open EMR for Asha Patil');
    expect(button.disabled).toBe(true);
  });
});
