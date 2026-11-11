import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AdmissionList, AdmissionScope } from '../ipd.model';
import { AdmittedPanelComponent } from './admitted-panel.component';
import { AdmittedViewState } from './admitted-view.state';
import { listOf, rowOf } from './admitted.fixtures';

const WARDS: AdmissionScope = { kind: 'WARDS', visible: 2, admitted: 12, hasWard: true, canOpenEmergencyAccess: true };

/** SAH-34 stories 34C and 34E on the Admitted tab: the chip, "My patients", the empty texts, the button. */
describe('AdmittedPanelComponent: ward scope (SAH-34)', () => {
  let fixture: ComponentFixture<AdmittedPanelComponent>;
  let state: AdmittedViewState;
  let emergency: number;
  const mounted: HTMLElement[] = [];

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [AdmittedViewState] });
    state = TestBed.inject(AdmittedViewState);
  });
  afterEach(() => mounted.splice(0).forEach((el) => el.remove()));

  function mount(list: AdmissionList, role: string | undefined): HTMLElement {
    fixture = TestBed.createComponent(AdmittedPanelComponent);
    fixture.componentRef.setInput('list', list);
    fixture.componentRef.setInput('role', role);
    emergency = 0;
    fixture.componentInstance.openEmergencyAccess.subscribe(() => (emergency += 1));
    document.body.appendChild(fixture.nativeElement as HTMLElement);
    mounted.push(fixture.nativeElement as HTMLElement);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const q = <T extends HTMLElement>(view: HTMLElement, sel: string) => view.querySelector<T>(sel);
  const names = (view: HTMLElement) =>
    Array.from(view.querySelectorAll('[data-testid=card-name]')).map((n) => n.textContent!.trim());
  const scoped = (partial: Partial<AdmissionList> = {}): AdmissionList =>
    listOf({
      scope: WARDS,
      items: [rowOf({ id: 'a1', mine: true }), rowOf({ id: 'a2', patientName: 'Ravi Shah', mine: true })],
      ...partial,
    });

  describe('the scope chip (AC 26 to 28)', () => {
    it('shows "Patients in their wards · N of M" with the shield icon, and the sentence as tooltip and accessible name', () => {
      const view = mount(scoped(), 'NURSE');
      const chip = q(view, '[data-testid=scope-chip]')!;
      expect(chip.textContent!.replace(/\s+/g, ' ').trim()).toBe('Patients in their wards · 2 of 12');
      const sentence = 'As Nurse you see patients in their wards (2 of 12). The owner sets your wards in Clinic setup.';
      expect(chip.getAttribute('title')).toBe(sentence);
      expect(chip.getAttribute('aria-label')).toBe(sentence);
      expect(chip.querySelector('app-icon')).not.toBeNull();
      expect(view.textContent).not.toContain('Roles and access');
    });

    it('is not drawn for a user whose scope is all', () => {
      for (const role of ['OWNER', 'DOCTOR', 'FRONT_DESK', 'NURSE']) {
        const view = mount(listOf(), role);
        expect(q(view, '[data-testid=scope-chip]')).toBeNull();
        expect(q(view, '[data-testid=emergency-access-button]')).toBeNull();
        fixture.destroy();
      }
    });

    it('is not drawn when nobody is admitted, and the list says "No one is admitted right now."', () => {
      const view = mount(
        listOf({ items: [], scope: { ...WARDS, visible: 0, admitted: 0 } }),
        'NURSE',
      );
      expect(q(view, '[data-testid=scope-chip]')).toBeNull();
      expect(view.textContent).toContain('No one is admitted right now.');
    });

    it('draws N and M exactly as the server sent them (no counting in the browser)', () => {
      const view = mount(scoped({ scope: { ...WARDS, visible: 7, admitted: 40 } }), 'NURSE');
      expect(q(view, '[data-testid=scope-chip]')!.textContent).toContain('7 of 40');
    });
  });

  describe('the button Emergency access (AC 44, 63)', () => {
    it('sits with the chip, has the key icon and asks the page to open the sheet', () => {
      const view = mount(scoped(), 'NURSE');
      const button = q<HTMLButtonElement>(view, '[data-testid=emergency-access-button]')!;
      expect(button.textContent!.trim()).toBe('Emergency access');
      expect(button.querySelector('app-icon')).not.toBeNull();
      expect(button.closest('[data-testid=scope]')).toBe(q(view, '[data-testid=scope]'));
      button.click();
      expect(emergency).toBe(1);
    });

    it('is not drawn when the server says the caller cannot open one', () => {
      const view = mount(scoped({ scope: { ...WARDS, canOpenEmergencyAccess: false } }), 'NURSE');
      expect(q(view, '[data-testid=scope-chip]')).not.toBeNull();
      expect(q(view, '[data-testid=emergency-access-button]')).toBeNull();
    });
  });

  describe('"My patients" (AC 32, 33, 42)', () => {
    it('is a toggle button with the user icon and aria-pressed, in the toolbar', () => {
      const view = mount(scoped(), 'NURSE');
      const toggle = q<HTMLButtonElement>(view, '[data-testid=my-patients]')!;
      expect(toggle.textContent!.trim()).toBe('My patients');
      expect(toggle.getAttribute('aria-pressed')).toBe('false');
      expect(toggle.querySelector('app-icon')).not.toBeNull();
      expect(toggle.closest('[data-testid=admitted-toolbar]')).not.toBeNull();
    });

    it('narrows the cards to the rows marked mine, and announces the pressed state', async () => {
      const list = scoped({
        items: [rowOf({ id: 'a1', mine: true }), rowOf({ id: 'a2', patientName: 'Ravi Shah', mine: false })],
      });
      const view = mount(list, 'DOCTOR');
      expect(names(view).length).toBe(2);
      q<HTMLButtonElement>(view, '[data-testid=my-patients]')!.click();
      await fixture.whenStable();
      fixture.detectChanges();
      expect(q(view, '[data-testid=my-patients]')!.getAttribute('aria-pressed')).toBe('true');
      expect(names(view)).toEqual(['Asha Patil']);
      q<HTMLButtonElement>(view, '[data-testid=my-patients]')!.click();
      fixture.detectChanges();
      expect(names(view).length).toBe(2);
    });

    it('shows "No patients match these filters." when nobody is mine, and keeps the toolbar', () => {
      const view = mount(scoped({ items: [rowOf({ id: 'a1', mine: false })] }), 'OWNER');
      q<HTMLButtonElement>(view, '[data-testid=my-patients]')!.click();
      fixture.detectChanges();
      expect(q(view, '[data-testid=admitted-empty]')!.textContent).toBe('No patients match these filters.');
      expect(q(view, '[data-testid=my-patients]')!.getAttribute('aria-pressed')).toBe('true');
    });

    it('works on the Table view as well', () => {
      state.setView('table');
      const list = scoped({
        items: [rowOf({ id: 'a1', mine: true }), rowOf({ id: 'a2', patientName: 'Ravi Shah', mine: false })],
      });
      const view = mount(list, 'DOCTOR');
      q<HTMLButtonElement>(view, '[data-testid=my-patients]')!.click();
      fixture.detectChanges();
      expect(view.querySelectorAll('.iptr:not(.ipth)').length).toBe(1);
    });

    it('is kept in memory only: nothing goes to the browser storage or the URL', () => {
      const setItem = vi.spyOn(Storage.prototype, 'setItem');
      const view = mount(scoped(), 'NURSE');
      q<HTMLButtonElement>(view, '[data-testid=my-patients]')!.click();
      expect(state.mine()).toBe(true);
      expect(setItem).not.toHaveBeenCalled();
      expect(location.search).toBe('');
      setItem.mockRestore();
    });

    it('is hidden for FRONT_DESK, and for a nurse with no live ward; a pressed filter is then not applied', () => {
      expect(q(mount(listOf(), 'FRONT_DESK'), '[data-testid=my-patients]')).toBeNull();
      fixture.destroy();
      state.setMine(true);
      const noWard = scoped({ scope: { ...WARDS, hasWard: false } });
      const view = mount(noWard, 'NURSE');
      expect(q(view, '[data-testid=my-patients]')).toBeNull();
      expect(names(view).length).toBe(2);
    });

    it('is shown to OWNER and DOCTOR with scope all', () => {
      expect(q(mount(listOf(), 'OWNER'), '[data-testid=my-patients]')).not.toBeNull();
      fixture.destroy();
      expect(q(mount(listOf(), 'DOCTOR'), '[data-testid=my-patients]')).not.toBeNull();
    });
  });

  describe('the empty texts (AC 29, 30, 34)', () => {
    it('says "No wards are assigned to you, ..." as a polite status, keeps the chip "0 of M" and the Emergency access button', () => {
      const view = mount(
        scoped({ items: [], scope: { ...WARDS, visible: 0, hasWard: false } }),
        'NURSE',
      );
      const text = q(view, '[data-testid=scope-empty]')!;
      expect(text.textContent).toBe(
        'No wards are assigned to you, so no patients are shown. Ask the owner to assign your ward. If a patient needs you now, use Emergency access.',
      );
      expect(text.getAttribute('role')).toBe('status');
      expect(q(view, '[data-testid=scope-chip]')!.textContent).toContain('0 of 12');
      expect(q(view, '[data-testid=emergency-access-button]')).not.toBeNull();
      expect(q(view, '[data-testid=admitted-empty]')).toBeNull();
      expect(q(view, '[data-testid=my-patients]')).toBeNull();
    });

    it('says "No patients in your wards right now." for a nurse with wards and nobody in them', () => {
      const view = mount(scoped({ items: [], scope: { ...WARDS, visible: 0 } }), 'NURSE');
      expect(q(view, '[data-testid=scope-empty]')!.textContent).toBe('No patients in your wards right now.');
      expect(q(view, '[data-testid=scope-chip]')!.textContent).toContain('0 of 12');
    });

    it('keeps "No patients match these filters." when a search matches nobody', async () => {
      const view = mount(scoped(), 'NURSE');
      const input = q<HTMLInputElement>(view, '[data-testid=admitted-search]')!;
      input.value = 'zzz';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      expect(q(view, '[data-testid=admitted-empty]')!.textContent).toBe('No patients match these filters.');
      expect(q(view, '[data-testid=scope-empty]')).toBeNull();
    });

    it('lists every ward in the Ward select, also a ward with none of her patients', () => {
      const view = mount(scoped(), 'NURSE');
      const options = Array.from(q<HTMLSelectElement>(view, '[data-testid=admitted-ward]')!.options).map((o) =>
        o.textContent!.trim(),
      );
      expect(options).toEqual(['All wards', 'General ward', 'Surgical ward', 'Maternity ward']);
    });
  });

  describe('the card flag (AC 51)', () => {
    it('draws the grey flag "Emergency access" on a row seen through the nurse own grant', () => {
      const list = scoped({
        items: [
          rowOf({ id: 'a1', mine: true }),
          rowOf({ id: 'a9', patientName: 'Ravi Shah', emergencyAccess: { expiresAt: '2026-10-24T06:05:00Z' } }),
        ],
      });
      const view = mount(list, 'NURSE');
      const flags = view.querySelectorAll('[data-flag=emergency-access]');
      expect(flags.length).toBe(1);
      expect(flags[0].textContent).toContain('Emergency access');
      expect(flags[0].getAttribute('title')).toBe('Emergency access until 11:35 AM IST');
      expect(flags[0].closest('[data-id=a9]')).not.toBeNull();
    });
  });
});
