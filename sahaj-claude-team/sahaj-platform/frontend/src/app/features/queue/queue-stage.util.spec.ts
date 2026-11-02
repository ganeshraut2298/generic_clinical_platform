import {
  canCreateToken,
  canMoveBackward,
  canMoveForward,
  earlierStages,
  minutesWaited,
  nextStage,
  ownsStage,
} from './queue-stage.util';

describe('canCreateToken', () => {
  it('allows owner, front desk and nurse', () => {
    expect(canCreateToken('OWNER')).toBe(true);
    expect(canCreateToken('FRONT_DESK')).toBe(true);
    expect(canCreateToken('NURSE')).toBe(true);
  });

  it('blocks other roles', () => {
    expect(canCreateToken('DOCTOR')).toBe(false);
    expect(canCreateToken(undefined)).toBe(false);
  });
});

describe('ownsStage', () => {
  it('matches nurse to vitals and doctor to with-doctor', () => {
    expect(ownsStage('NURSE', 'VITALS')).toBe(true);
    expect(ownsStage('DOCTOR', 'WITH_DOCTOR')).toBe(true);
    expect(ownsStage('VISITING_DOCTOR', 'WITH_DOCTOR')).toBe(true);
    expect(ownsStage('NURSE', 'WITH_DOCTOR')).toBe(false);
  });
});

describe('canMoveForward', () => {
  it('lets owner and front desk move any stage', () => {
    expect(canMoveForward('OWNER', 'VITALS')).toBe(true);
    expect(canMoveForward('FRONT_DESK', 'BILLING')).toBe(true);
  });

  it('lets a role move only the stage it owns', () => {
    expect(canMoveForward('NURSE', 'VITALS')).toBe(true);
    expect(canMoveForward('NURSE', 'WITH_DOCTOR')).toBe(false);
  });
});

describe('canMoveBackward', () => {
  it('is owner-only', () => {
    expect(canMoveBackward('OWNER')).toBe(true);
    expect(canMoveBackward('FRONT_DESK')).toBe(false);
  });
});

describe('nextStage', () => {
  it('goes straight to with-doctor when vitals is off', () => {
    expect(nextStage('WAITING', false)).toBe('WITH_DOCTOR');
  });

  it('goes to vitals first when vitals is on', () => {
    expect(nextStage('WAITING', true)).toBe('VITALS');
    expect(nextStage('VITALS', true)).toBe('WITH_DOCTOR');
  });

  it('skips counsel and goes with-doctor to billing to done', () => {
    expect(nextStage('WITH_DOCTOR', false)).toBe('BILLING');
    expect(nextStage('COUNSEL', false)).toBe('BILLING');
    expect(nextStage('BILLING', false)).toBe('DONE');
  });

  it('has no next stage after done', () => {
    expect(nextStage('DONE', false)).toBeNull();
  });

  it('goes With doctor to Counselling when Counselling is on, and Counselling to Billing', () => {
    expect(nextStage('WITH_DOCTOR', false, true)).toBe('COUNSEL');
    expect(nextStage('COUNSEL', false, true)).toBe('BILLING');
  });
});

describe('earlierStages', () => {
  it('lists stages before the current one, excluding counsel', () => {
    expect(earlierStages('BILLING')).toEqual(['WAITING', 'VITALS', 'WITH_DOCTOR']);
  });

  it('is empty for waiting', () => {
    expect(earlierStages('WAITING')).toEqual([]);
  });
});

describe('minutesWaited', () => {
  it('computes whole minutes since created, never negative', () => {
    const createdAt = new Date('2026-10-24T05:00:00.000Z').toISOString();
    const now = new Date('2026-10-24T05:12:00.000Z');
    expect(minutesWaited(createdAt, now)).toBe(12);
  });

  it('clamps a future createdAt to zero', () => {
    const createdAt = new Date('2026-10-24T05:20:00.000Z').toISOString();
    const now = new Date('2026-10-24T05:00:00.000Z');
    expect(minutesWaited(createdAt, now)).toBe(0);
  });
});
