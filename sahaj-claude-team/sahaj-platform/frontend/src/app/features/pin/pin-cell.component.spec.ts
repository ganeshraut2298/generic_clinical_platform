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
      (showTries)="tries = tries + 1"
    />
  `,
})
class HostComponent {
  readonly pin = signal<TeamMemberPin | null>(null);
  readonly owner = signal(false);
  readonly self = signal(false);
  resets = 0;
  owns = 0;
  tries = 0;
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

  describe('colleague tries line (SAH-38, ADR-014 D9)', () => {
    const triesButton = () => el().querySelector<HTMLButtonElement>('[data-act=pin-tries]');
    const withTries = (count: number | null | undefined): void => {
      host.pin.set({ ...pin('SET'), colleagueTries7d: count });
      host.owner.set(true);
      fixture.detectChanges();
    };

    it('shows "N wrong tries by colleagues in 7 days" to the owner as a button that names the member', () => {
      withTries(4);
      expect(squash(triesButton())).toBe('4 wrong tries by colleagues in 7 days');
      expect(triesButton()!.getAttribute('aria-label')).toBe('4 wrong tries by colleagues in 7 days for Rahul Deshmukh');
      expect(triesButton()!.getAttribute('aria-haspopup')).toBe('dialog');
      triesButton()!.click();
      expect(host.tries).toBe(1);
    });

    it('says "1 wrong try by a colleague in 7 days" for one', () => {
      withTries(1);
      expect(squash(triesButton())).toBe('1 wrong try by a colleague in 7 days');
    });

    it('shows nothing extra at 0, and no 0 when the server sent none', () => {
      withTries(0);
      expect(triesButton()).toBeNull();
      withTries(null);
      expect(triesButton()).toBeNull();
      withTries(undefined);
      expect(triesButton()).toBeNull();
    });

    it('is never shown to a role that is not the owner, even if a count were present', () => {
      withTries(4);
      host.owner.set(false);
      fixture.detectChanges();
      expect(triesButton()).toBeNull();
    });

    it('keeps the "PIN locked" pill unchanged next to it', () => {
      host.pin.set({ ...pin('LOCKED'), colleagueTries7d: 5 });
      host.owner.set(true);
      fixture.detectChanges();
      expect(squash(pill())).toBe('PIN locked');
      expect(triesButton()).not.toBeNull();
    });
  });
});
