import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EMR_COMPONENT_CONTEXT, EmrComponentContext } from '../emr-context';
import { EmrBodyMapPin, EmrBodyMapValue } from '../emr-phase3.model';
import { BodyMapComponent } from './body-map.component';
import { buildBody } from './body-geometry';
import { newPin } from './body-map.logic';

describe('BodyMapComponent (SAH-57)', () => {
  let fixture: ComponentFixture<BodyMapComponent>;
  let el: HTMLElement;
  let emitted: EmrBodyMapValue[];

  function pin(no: number, over: Partial<EmrBodyMapPin> = {}): EmrBodyMapPin {
    return { ...newPin(no, 'FRONT', 120, 180, 'torso2', 'Abdomen'), ...over };
  }

  function render(pins: EmrBodyMapPin[] | null = null, opts: { disabled?: boolean; sex?: 'F' | 'M' | 'O' | null; confirmed?: boolean } = {}): void {
    const ctx = { patientSex: signal(opts.sex ?? null) } as unknown as EmrComponentContext;
    TestBed.configureTestingModule({ providers: [{ provide: EMR_COMPONENT_CONTEXT, useValue: ctx }] });
    fixture = TestBed.createComponent(BodyMapComponent);
    fixture.componentRef.setInput('inputId', 'bm-test');
    fixture.componentRef.setInput('disabled', opts.disabled ?? false);
    fixture.componentRef.setInput(
      'value',
      pins || opts.confirmed
        ? { component: 'BODY_MAP', schemaVersion: 1, pins: pins ?? [], nextPinNo: (pins?.length ?? 0) + 1, noFindingsConfirmed: opts.confirmed ?? false }
        : null,
    );
    emitted = [];
    fixture.componentInstance.valueChange.subscribe((v) => {
      emitted.push(v);
      fixture.componentRef.setInput('value', v);
      fixture.detectChanges();
    });
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));
  const last = () => emitted[emitted.length - 1];
  const chip = (group: string, label: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>(`[aria-labelledby="bm-test-${group}"] button`)).find((b) => b.textContent!.trim() === label)!;

  afterEach(() => vi.useRealTimers());

  describe('the drawing (SAH-57-1)', () => {
    it('shows the title, the instruction, and the body from the front', () => {
      render();
      expect(q('h3')!.textContent).toBe('Local examination · body map');
      expect(q('.ht .faint')!.textContent).toBe('Tap the body to drop a pin, drag a pin to move it, then record what you found and what happened.');
      expect(q('[data-act=front]')!.getAttribute('aria-pressed')).toBe('true');
      expect(q('svg')!.getAttribute('aria-label')).toBe('Body, front view, 0 pin(s)');
    });

    it('sets the sex from the patient record, and Other when it is not recorded', () => {
      render(null, { sex: 'F' });
      expect(q('[data-sex=female]')!.getAttribute('aria-selected')).toBe('true');
      TestBed.resetTestingModule();
      render(null, { sex: null });
      expect(q('[data-sex=other]')!.getAttribute('aria-selected')).toBe('true');
    });

    it('changes the drawing for Male, Female and Other and keeps the pins where they are', () => {
      render([pin(1)], { sex: 'M' });
      q<HTMLButtonElement>('[data-sex=female]')!.click();
      fixture.detectChanges();
      expect(q('[data-sex=female]')!.getAttribute('aria-selected')).toBe('true');
      expect(qa('.pin')).toHaveLength(1);
      expect(emitted).toHaveLength(0);
    });

    it('offers three skin tones as a display choice that is not saved', () => {
      render();
      const tones = qa('.tone');
      expect(tones.map((t) => t.getAttribute('aria-label'))).toEqual(['Light skin tone', 'Wheatish skin tone', 'Dark skin tone']);
      tones[2].click();
      fixture.detectChanges();
      expect(tones[2].getAttribute('aria-pressed')).toBe('true');
      expect(q('.stage')!.classList).toContain('tone-dark');
      expect(emitted).toHaveLength(0);
    });

    it('makes each of the 17 regions a separate tap area a keyboard can reach, with its name', () => {
      render();
      const hits = qa('.hit');
      expect(hits).toHaveLength(17);
      expect(hits[0].getAttribute('tabindex')).toBe('0');
      expect(hits.map((h) => h.getAttribute('aria-label'))).toContain('Abdomen. Press Enter to drop a pin.');
    });

    it('shows the other side with Back and Turn, and keeps each pin on the side it was dropped on', () => {
      render([pin(1), pin(2, { side: 'BACK', where: 'Lower back', region: 'torso2' })]);
      expect(qa('.pin')).toHaveLength(1);
      q<HTMLButtonElement>('[data-act=back]')!.click();
      fixture.detectChanges();
      expect(qa('.pin')).toHaveLength(1);
      expect(q('.tag')!.textContent).toBe('BACK');
      q<HTMLButtonElement>('[data-act=turn]')!.click();
      fixture.detectChanges();
      expect(q('.tag')!.textContent).toBe('FRONT');
    });

    it('zooms by one and a half times up to four, and Fit returns to the whole body', () => {
      render();
      const viewBox = () => q('svg')!.getAttribute('viewBox')!;
      expect(viewBox()).toBe('0.0 0.0 240.0 480.0');
      q<HTMLButtonElement>('[data-act=zoom-in]')!.click();
      fixture.detectChanges();
      expect(viewBox().split(' ')[2]).toBe('160.0');
      for (let i = 0; i < 6; i++) {
        q<HTMLButtonElement>('[data-act=zoom-in]')!.click();
      }
      fixture.detectChanges();
      expect(viewBox().split(' ')[2]).toBe('60.0');
      q<HTMLButtonElement>('[data-act=zoom-out]')!.click();
      fixture.detectChanges();
      expect(Number(viewBox().split(' ')[2])).toBeGreaterThan(60);
      q<HTMLButtonElement>('[data-act=fit]')!.click();
      fixture.detectChanges();
      expect(viewBox()).toBe('0.0 0.0 240.0 480.0');
    });

    it('centres the zoom on the selected pin', () => {
      render([pin(1, { x: 0.75, y: 0.5 })]);
      q<HTMLButtonElement>('[aria-label="Show pin 1"]')!.click();
      q<HTMLButtonElement>('[data-act=zoom-in]')!.click();
      fixture.detectChanges();
      const [x, y] = q('svg')!.getAttribute('viewBox')!.split(' ').map(Number);
      expect([x, y]).toEqual([80, 80]);
    });
  });

  describe('dropping a pin (SAH-57-2)', () => {
    it('shows the idle hint with the number of the next pin', () => {
      render([pin(1), pin(2)]);
      expect(q('[data-testid=idle-hint]')!.textContent!.trim()).toBe('Tap anywhere on the body to drop pin 3. Use Turn to see the back, + to zoom in on small areas.');
    });

    it('drops a numbered pin on the region with the defaults, selects it and says so', () => {
      render();
      const abdomen = buildBody('other', 'FRONT').find((r) => r.id === 'torso2')!;
      fixture.componentInstance.tapAt(abdomen.cx, abdomen.cy);
      fixture.detectChanges();
      expect(last().pins).toHaveLength(1);
      expect(last().pins[0]).toMatchObject({ no: 1, side: 'FRONT', region: 'torso2', where: 'Abdomen', finding: 'WOUND', cause: 'FALL', severity: 'MILD', sizeCm: 2, note: '', mlc: false });
      expect(q('[data-testid=bm-toast]')!.textContent).toBe('Pin 1 on abdomen. Pick the finding and what happened.');
      expect(q('[data-testid=pin-form]')).not.toBeNull();
    });

    it('selects nothing when a tap lands outside the body', () => {
      render([pin(1)]);
      q<HTMLButtonElement>('[aria-label="Show pin 1"]')!.click();
      fixture.detectChanges();
      expect(q('[data-testid=pin-form]')).not.toBeNull();
      fixture.componentInstance.tapAt(2, 2);
      fixture.detectChanges();
      expect(q('[data-testid=pin-form]')).toBeNull();
    });

    it('drops a pin with the keyboard: Enter on a region', () => {
      render();
      const hit = qa('.hit').find((h) => h.getAttribute('aria-label')!.startsWith('Chest'))!;
      hit.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      fixture.detectChanges();
      expect(last().pins[0].where).toBe('Chest');
    });

    it('drops a pin from a tap on the drawing', () => {
      render();
      const svg = q('svg')!;
      svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 240, height: 480, right: 240, bottom: 480, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
      const head = buildBody('other', 'FRONT').find((r) => r.id === 'head')!;
      const at = { clientX: head.cx, clientY: head.cy, bubbles: true };
      svg.dispatchEvent(new MouseEvent('pointerdown', at));
      svg.dispatchEvent(new MouseEvent('pointerup', at));
      fixture.detectChanges();
      expect(last().pins[0]).toMatchObject({ where: 'Head and face', region: 'head' });
    });

    it('moves a dragged pin and the region follows the new spot', () => {
      render([pin(1)]);
      const svg = q('svg')!;
      svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 240, height: 480, right: 240, bottom: 480, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
      const chest = buildBody('other', 'FRONT').find((r) => r.id === 'torso1')!;
      q('.pin')!.dispatchEvent(new MouseEvent('pointerdown', { clientX: 120, clientY: 180, bubbles: true }));
      svg.dispatchEvent(new MouseEvent('pointermove', { clientX: chest.cx, clientY: chest.cy, bubbles: true }));
      svg.dispatchEvent(new MouseEvent('pointerup', { clientX: chest.cx, clientY: chest.cy, bubbles: true }));
      fixture.detectChanges();
      expect(last().pins[0]).toMatchObject({ region: 'torso1', where: 'Chest' });
      expect(emitted.length).toBeGreaterThan(1);
    });

    it('counts a tiny drag as a tap and does not move the pin', () => {
      render([pin(1)]);
      const svg = q('svg')!;
      svg.getBoundingClientRect = () => ({ left: 0, top: 0, width: 240, height: 480, right: 240, bottom: 480, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
      q('.pin')!.dispatchEvent(new MouseEvent('pointerdown', { clientX: 120, clientY: 180, bubbles: true }));
      svg.dispatchEvent(new MouseEvent('pointermove', { clientX: 121, clientY: 181, bubbles: true }));
      svg.dispatchEvent(new MouseEvent('pointerup', { clientX: 121, clientY: 181, bubbles: true }));
      expect(emitted).toHaveLength(0);
    });

    it('moves the selected pin by small steps with the arrow keys', () => {
      render([pin(1)]);
      q('.pin')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
      expect(last().pins[0].x).toBeGreaterThan(0.5);
    });
  });

  describe('the pin form', () => {
    function selectFirst(): void {
      q<HTMLButtonElement>('[aria-label="Show pin 1"]')!.click();
      fixture.detectChanges();
    }

    it('offers the ten findings, the eight causes and the three severities', () => {
      render([pin(1)]);
      selectFirst();
      expect(qa('[aria-labelledby="bm-test-find"] button').map((b) => b.textContent!.trim())).toEqual(['Wound', 'Swelling', 'Tenderness', 'Bruise', 'Burn', 'Rash', 'Lump', 'Fracture?', 'Scar', 'Pain']);
      expect(qa('[aria-labelledby="bm-test-cause"] button')).toHaveLength(8);
      expect(qa('[aria-labelledby="bm-test-sev"] button').map((b) => b.textContent!.trim())).toEqual(['Mild', 'Moderate', 'Severe']);
      expect(chip('find', 'Wound').getAttribute('aria-pressed')).toBe('true');
    });

    it('changes the finding and the severity', () => {
      render([pin(1)]);
      selectFirst();
      chip('find', 'Bruise').click();
      chip('sev', 'Severe').click();
      expect(last().pins[0]).toMatchObject({ finding: 'BRUISE', severity: 'SEVERE' });
    });

    it('changes the size in half centimetres and shows "not measured" at 0', () => {
      render([pin(1, { sizeCm: 0.5 })]);
      selectFirst();
      q<HTMLButtonElement>('[aria-label="Larger"]')!.click();
      expect(last().pins[0].sizeCm).toBe(1);
      q<HTMLButtonElement>('[aria-label="Smaller"]')!.click();
      q<HTMLButtonElement>('[aria-label="Smaller"]')!.click();
      expect(last().pins[0].sizeCm).toBe(0);
      expect(q('[data-testid=pin-size]')!.textContent).toBe('not measured');
    });

    it('marks the pin medico-legal at once for a road accident, an assault or a burn, with the message and the note', () => {
      render([pin(1)]);
      selectFirst();
      chip('cause', 'Road accident').click();
      fixture.detectChanges();
      expect(last().pins[0]).toMatchObject({ cause: 'ROAD_ACCIDENT', mlc: true });
      expect(q('[data-testid=bm-toast]')!.textContent).toBe('Marked as medico-legal (MLC) for road accident');
      expect(q('[data-testid=mlc-note]')!.textContent).toBe('MLC: record time of examination, who brought the patient, and inform the police as per hospital policy.');
      expect(q<HTMLInputElement>('[data-act=mlc]')!.checked).toBe(true);
    });

    it('does not claim photos are kept, because the documents service does not exist', () => {
      render([pin(1, { mlc: true })]);
      selectFirst();
      expect(q('[data-testid=mlc-note]')!.textContent).not.toContain('Photos');
    });

    it('keeps the flag when the cause is changed away, until the checkbox is cleared', () => {
      render([pin(1)]);
      selectFirst();
      chip('cause', 'Assault').click();
      chip('cause', 'Fall').click();
      fixture.detectChanges();
      expect(last().pins[0].mlc).toBe(true);
      const box = q<HTMLInputElement>('[data-act=mlc]')!;
      box.checked = false;
      box.dispatchEvent(new Event('change'));
      expect(last().pins[0].mlc).toBe(false);
    });

    it('lets the doctor set the flag by hand for any pin', () => {
      render([pin(1)]);
      selectFirst();
      const box = q<HTMLInputElement>('[data-act=mlc]')!;
      box.checked = true;
      box.dispatchEvent(new Event('change'));
      expect(last().pins[0].mlc).toBe(true);
    });

    it('takes a note of up to 500 characters', () => {
      render([pin(1)]);
      selectFirst();
      const input = q<HTMLInputElement>('.note input')!;
      expect(input.getAttribute('maxlength')).toBe('500');
      input.value = 'abrasion with gravel';
      input.dispatchEvent(new Event('input'));
      expect(last().pins[0].note).toBe('abrasion with gravel');
    });

    it('deletes a pin with a message and does not reuse its number', () => {
      render([pin(1), pin(2, { x: 0.3 })]);
      q<HTMLButtonElement>('[aria-label="Show pin 2"]')!.click();
      fixture.detectChanges();
      q<HTMLButtonElement>('[data-act=delete-pin]')!.click();
      fixture.detectChanges();
      expect(last().pins.map((p) => p.no)).toEqual([1]);
      expect(q('[data-testid=bm-toast]')!.textContent).toBe('Pin 2 deleted');
      expect(q('[data-testid=idle-hint]')!.textContent).toContain('drop pin 3');
    });

    it('changes Where from the list: the pin stays on the drawing, and a part on the other side turns the view', () => {
      render([pin(1)]);
      selectFirst();
      const select = q<HTMLSelectElement>('[data-testid=pick-part]')!;
      select.value = 'FRONT:torso1';
      select.dispatchEvent(new Event('change'));
      expect(last().pins[0]).toMatchObject({ region: 'torso1', where: 'Chest', side: 'FRONT', x: 0.5 });
      select.value = 'BACK:torso2';
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      expect(last().pins[0]).toMatchObject({ side: 'BACK', where: 'Lower back' });
      expect(q('[data-act=back]')!.getAttribute('aria-pressed')).toBe('true');
    });

    it('drops a new pin from the list when no pin is selected', () => {
      render();
      const select = q<HTMLSelectElement>('[data-testid=pick-part]')!;
      select.value = 'FRONT:r_hand';
      select.dispatchEvent(new Event('change'));
      expect(last().pins[0]).toMatchObject({ no: 1, where: 'Right hand' });
    });
  });

  describe('findings register (SAH-57-3)', () => {
    it('says "No local findings yet." and tells the doctor to tap a number', () => {
      render();
      expect(q('[data-testid=register-empty]')!.textContent).toBe('No local findings yet.');
      expect(q('[data-testid=register-side]')!.textContent).toBe('Tap a number to find the pin');
      expect(q('.reghead strong')!.textContent).toBe('Findings register (0)');
    });

    it('lists the pins with finding, cause, size and a red MLC badge, and counts the medico-legal ones', () => {
      render([pin(1, { finding: 'TENDERNESS', severity: 'MODERATE', note: 'right lower' }), pin(2, { mlc: true, cause: 'ASSAULT', sizeCm: 0, side: 'BACK', where: 'Upper back' })]);
      expect(qa('th').map((h) => h.textContent!.trim())).toEqual(['#', 'Where', 'Finding', 'What happened', 'Size', 'Recorded', 'Delete']);
      const rows = qa('tbody tr');
      expect(rows[0].textContent).toContain('Tenderness · moderate');
      expect(rows[0].textContent).toContain('Fall — right lower');
      expect(rows[0].textContent).toContain('2 cm');
      expect(rows[1].textContent).toContain('Upper back (back)');
      expect(rows[1].textContent).toContain('MLC');
      expect(rows[1].textContent).toContain('—');
      expect(q('[data-testid=register-side]')!.textContent).toBe('1 medico-legal');
    });

    it('selects a pin from its number in the register and turns the view to its side', () => {
      render([pin(1), pin(2, { side: 'BACK' })]);
      q<HTMLButtonElement>('[aria-label="Show pin 2"]')!.click();
      fixture.detectChanges();
      expect(q('[data-act=back]')!.getAttribute('aria-pressed')).toBe('true');
      expect(q('[data-testid=pin-form]')).not.toBeNull();
    });
  });

  describe('Done (SAH-57-4)', () => {
    it('offers "No local findings" so a required map can be completed, and lets the doctor undo it', () => {
      render();
      q<HTMLButtonElement>('[data-act=no-findings]')!.click();
      expect(last().noFindingsConfirmed).toBe(true);
      expect(q('[data-act=no-findings]')!.textContent!.trim()).toBe('✓ No local findings confirmed');
      q<HTMLButtonElement>('[data-act=no-findings]')!.click();
      expect(last().noFindingsConfirmed).toBe(false);
    });

    it('does not offer it once there is a pin', () => {
      render([pin(1)]);
      expect(q('[data-act=no-findings]')).toBeNull();
    });
  });

  describe('read-only', () => {
    it('shows the pins and the register but nothing can be added, moved, changed or deleted', () => {
      render([pin(1)], { disabled: true });
      q<HTMLButtonElement>('[aria-label="Show pin 1"]')!.click();
      fixture.detectChanges();
      expect(q('[data-act=delete-pin]')).toBeNull();
      expect(q('[data-testid=pick-part]')).toBeNull();
      expect(q('[data-act=no-findings]')).toBeNull();
      expect(qa('[aria-labelledby="bm-test-find"] button').every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
      expect(q<HTMLInputElement>('.note input')!.disabled).toBe(true);
      expect(qa('.hit')[0].getAttribute('tabindex')).toBeNull();
      fixture.componentInstance.tapAt(120, 180);
      expect(emitted).toHaveLength(0);
    });
  });
});
