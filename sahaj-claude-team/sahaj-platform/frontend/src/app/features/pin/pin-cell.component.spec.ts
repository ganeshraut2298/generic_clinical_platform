import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PinCellComponent } from './pin-cell.component';
import { TeamMemberPin } from './pin.model';

@Component({
  imports: [PinCellComponent],
  template: `
    <app-pin-cell
      [pin]="pin()"
      memberId="m1"
      memberName="Rahul Deshmukh"
      [owner]="owner()"
      [self]="self()"
      (resetPin)="resets = resets + 1"
      (ownPin)="owns = owns + 1"
    />
  `,
})
class HostComponent {
  readonly pin = signal<TeamMemberPin | null>(null);
  readonly owner = signal(false);
  readonly self = signal(false);
  resets = 0;
  owns = 0;
}

const pin = (state: TeamMemberPin['state']): TeamMemberPin => ({
  state,
  setAt: state === 'NOT_SET' ? null : '2026-11-06T05:35:00Z',
  lockedUntil: state === 'LOCKED' ? '2026-11-06T05:50:00Z' : null,
});

describe('PinCellComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const pill = () => el().querySelector('[data-testid=pin-pill]');
  const resetButton = () => el().querySelector<HTMLButtonElement>('[data-act=pin-reset]');
  const ownButton = () => el().querySelector<HTMLButtonElement>('[data-act=pin-own]');

  function show(state: TeamMemberPin['state'] | null, owner: boolean, self: boolean): void {
    host.pin.set(state ? pin(state) : null);
    host.owner.set(owner);
    host.self.set(self);
    fixture.detectChanges();
  }

  it('shows the three pills with their words', () => {
    show('SET', true, false);
    expect(squash(pill())).toBe('PIN set');
    expect(pill()!.classList).toContain('p-good');
    show('LOCKED', true, false);
    expect(squash(pill())).toBe('PIN locked');
    expect(pill()!.classList).toContain('p-warn');
    show('NOT_SET', true, false);
    expect(squash(pill())).toBe('No PIN yet');
    expect(pill()!.classList).toContain('p-wait');
  });

  it('shows nothing for a colleague the server sent no PIN for', () => {
    show(null, false, false);
    expect(pill()).toBeNull();
    expect(resetButton()).toBeNull();
    expect(ownButton()).toBeNull();
  });

  it('gives the owner Reset PIN on a set or locked PIN only', () => {
    show('SET', true, false);
    expect(squash(resetButton())).toBe('Reset PIN');
    expect(resetButton()!.getAttribute('aria-label')).toBe('Reset PIN for Rahul Deshmukh');
    show('LOCKED', true, false);
    expect(resetButton()).not.toBeNull();
    show('NOT_SET', true, false);
    expect(resetButton()).toBeNull();
    show('SET', false, false);
    expect(resetButton()).toBeNull();
  });

  it('gives the own row "Set my PIN" or "Change my PIN"', () => {
    show('NOT_SET', false, true);
    expect(squash(ownButton())).toBe('Set my PIN');
    show('SET', false, true);
    expect(squash(ownButton())).toBe('Change my PIN');
    show('LOCKED', false, true);
    expect(squash(ownButton())).toBe('Change my PIN');
    show(null, false, true);
    expect(squash(ownButton())).toBe('Set my PIN');
    show('SET', false, false);
    expect(ownButton()).toBeNull();
  });

  it('emits reset and own, and the pill can take focus for the parent', () => {
    show('SET', true, true);
    resetButton()!.click();
    ownButton()!.click();
    expect(host.resets).toBe(1);
    expect(host.owns).toBe(1);
    expect(pill()!.getAttribute('id')).toBe('pin-pill-m1');
    expect(pill()!.getAttribute('tabindex')).toBe('-1');
  });
});
