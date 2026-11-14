import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { isComponentEmpty, isComponentFilled, sanitizeComponentValue } from './component-values.logic';
import { EmrFollowUpValue } from '../emr-finish.model';
import { FOLLOW_UP_CHOICES, FollowUpComponent, followUpLabel, followUpOf } from './follow-up.component';

@Component({
  imports: [FollowUpComponent],
  template: `<app-follow-up inputId="f-followup-next_visit" [value]="value()" [disabled]="disabled()" (valueChange)="changed = $event" />`,
})
class HostComponent {
  readonly value = signal<unknown>(null);
  readonly disabled = signal(false);
  changed: EmrFollowUpValue | null = null;
}

describe('FollowUpComponent (SAH-51-6)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  const chips = () => Array.from(el.querySelectorAll<HTMLButtonElement>('.chip'));

  it('shows the five choices of the mockup, none chosen at start', () => {
    expect(chips().map((c) => c.textContent!.trim())).toEqual(['3 days', '7 days', '15 days', '1 month', '3 months']);
    expect(chips().every((c) => c.getAttribute('aria-pressed') === 'false')).toBe(true);
  });

  it('says "Books an appointment on that day." and shows the SMS reminder switched off', () => {
    expect(el.querySelector('[data-testid=follow-note]')!.textContent).toBe('Books an appointment on that day.');
    expect(el.textContent).toContain('SMS reminders: later');
    expect((el.querySelector('[data-testid=sms-off]') as HTMLInputElement).disabled).toBe(true);
  });

  it('emits the interval of the chip tapped, and takes it back when tapped again', () => {
    chips()[1].click();
    expect(host.changed).toEqual({ component: 'FOLLOW_UP', schemaVersion: 1, interval: 'D7' });
    host.value.set(host.changed);
    fixture.detectChanges();
    expect(chips()[1].getAttribute('aria-pressed')).toBe('true');
    chips()[1].click();
    expect(host.changed).toEqual({ component: 'FOLLOW_UP', schemaVersion: 1, interval: null });
  });

  it('shows the due date the server worked out', () => {
    host.value.set({ component: 'FOLLOW_UP', schemaVersion: 1, interval: 'M1', dueDate: '2026-12-13' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=follow-due]')!.textContent).toBe('Due 13 Dec 2026');
  });

  it('does nothing when disabled', () => {
    host.disabled.set(true);
    fixture.detectChanges();
    chips()[0].click();
    expect(host.changed).toBeNull();
  });

  describe('rules', () => {
    it('is Done only when an interval is chosen', () => {
      expect(isComponentFilled({ component: 'FOLLOW_UP', schemaVersion: 1, interval: null })).toBe(false);
      expect(isComponentEmpty({ component: 'FOLLOW_UP', schemaVersion: 1, interval: null })).toBe(true);
      expect(isComponentFilled({ component: 'FOLLOW_UP', schemaVersion: 1, interval: 'D3' })).toBe(true);
    });

    it('sends the interval only: the due date is the server\'s', () => {
      expect(sanitizeComponentValue({ component: 'FOLLOW_UP', schemaVersion: 1, interval: 'D15', dueDate: '2026-11-28' })).toEqual({
        component: 'FOLLOW_UP',
        schemaVersion: 1,
        interval: 'D15',
      });
    });

    it('maps the keys to words and reads a missing value as none', () => {
      expect(FOLLOW_UP_CHOICES.map((c) => c.key)).toEqual(['D3', 'D7', 'D15', 'M1', 'M3']);
      expect(followUpLabel('M3')).toBe('3 months');
      expect(followUpLabel(null)).toBeNull();
      expect(followUpOf(undefined).interval).toBeNull();
    });
  });
});
