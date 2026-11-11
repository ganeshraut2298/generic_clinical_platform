import { DestroyRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  EMERGENCY_ENDED_MESSAGE,
  OUTSIDE_SCOPE_MESSAGE,
  ScopeRefusal,
  ScopeRefusals,
  outsideScopeText,
} from './ipd-scope-refusal';

describe('outsideScopeText (SAH-34 AC 38, 39)', () => {
  it('says the patient is not in the nurse wards', () => {
    expect(outsideScopeText(false, false)).toBe('This patient is not in your wards.');
    expect(OUTSIDE_SCOPE_MESSAGE).toBe('This patient is not in your wards.');
  });

  it('says the emergency access has ended for a patient the user had through a grant', () => {
    expect(outsideScopeText(true, false)).toBe('Your emergency access to this patient has ended.');
    expect(EMERGENCY_ENDED_MESSAGE).toBe('Your emergency access to this patient has ended.');
  });

  it('adds "Nothing was saved." after a save', () => {
    expect(outsideScopeText(false, true)).toBe('This patient is not in your wards. Nothing was saved.');
    expect(outsideScopeText(true, true)).toBe(
      'Your emergency access to this patient has ended. Nothing was saved.',
    );
  });
});

describe('ScopeRefusals', () => {
  let bus: ScopeRefusals;
  let destroyRef: DestroyRef;
  let destroy: () => void;

  beforeEach(() => {
    bus = TestBed.inject(ScopeRefusals);
    let callback: () => void = () => undefined;
    destroyRef = { onDestroy: (fn: () => void) => ((callback = fn), () => undefined) } as DestroyRef;
    destroy = () => callback();
  });

  it('tells every listener about a refusal, with ids only', () => {
    const seen: ScopeRefusal[] = [];
    bus.refusals.subscribe((r) => seen.push(r));
    bus.report('a1', true);
    expect(seen).toEqual([{ admissionId: 'a1', write: true }]);
  });

  it('wakes only the sheets of that admission, and stops when the sheet is destroyed', () => {
    const mine: ScopeRefusal[] = [];
    const other: ScopeRefusal[] = [];
    bus.watch('a1', destroyRef, (r) => mine.push(r));
    bus.watch('a2', TestBed.inject(DestroyRef), (r) => other.push(r));
    bus.report('a1', false);
    expect(mine).toEqual([{ admissionId: 'a1', write: false }]);
    expect(other).toEqual([]);
    destroy();
    bus.report('a1', false);
    expect(mine.length).toBe(1);
  });

  it('keeps nothing: a listener that comes later sees no earlier refusal (no scope outlives the event)', () => {
    bus.report('a1', true);
    const late: ScopeRefusal[] = [];
    bus.refusals.subscribe((r) => late.push(r));
    expect(late).toEqual([]);
  });
});
