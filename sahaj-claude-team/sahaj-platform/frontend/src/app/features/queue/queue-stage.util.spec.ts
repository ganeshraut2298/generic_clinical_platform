import { DEFAULT_WORKFLOW_STEPS, WorkflowStep, WorkflowVersion } from '../workflow/workflow.model';
import {
  canCreateToken,
  canMoveBackward,
  canMoveForward,
  earlierStages,
  enabledStages,
  minutesWaited,
  nextStage,
  ownsStage,
  stepsForVersion,
} from './queue-stage.util';

/** The default steps with the given on/off and owner overrides, like a published version. */
function steps(overrides: Partial<Record<string, Partial<WorkflowStep>>> = {}): WorkflowStep[] {
  return DEFAULT_WORKFLOW_STEPS.map((step) => ({ ...step, ...overrides[step.key] }));
}

/** v1: Vitals on, Counselling off (the default). */
const V1 = steps();
/** v2: Vitals turned off and Counselling turned on. */
const V2 = steps({ vitals: { on: false }, counsel: { on: true } });

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

describe('enabledStages', () => {
  it('lists Waiting, Vitals, With doctor, Billing, Done by default', () => {
    expect(enabledStages(V1)).toEqual(['WAITING', 'VITALS', 'WITH_DOCTOR', 'BILLING', 'DONE']);
  });

  it('drops Vitals when off and adds Counselling when on', () => {
    expect(enabledStages(V2)).toEqual(['WAITING', 'WITH_DOCTOR', 'COUNSEL', 'BILLING', 'DONE']);
  });
});

describe('ownsStage', () => {
  it('lets owner and front desk own every stage', () => {
    expect(ownsStage('OWNER', 'VITALS', V1)).toBe(true);
    expect(ownsStage('FRONT_DESK', 'BILLING', V1)).toBe(true);
  });

  it('matches the nurse to Vitals and both doctor roles to With doctor by default', () => {
    expect(ownsStage('NURSE', 'VITALS', V1)).toBe(true);
    expect(ownsStage('DOCTOR', 'WITH_DOCTOR', V1)).toBe(true);
    expect(ownsStage('VISITING_DOCTOR', 'WITH_DOCTOR', V1)).toBe(true);
    expect(ownsStage('NURSE', 'WITH_DOCTOR', V1)).toBe(false);
    expect(ownsStage('NURSE', 'BILLING', V1)).toBe(false);
    expect(ownsStage(undefined, 'VITALS', V1)).toBe(false);
  });

  it('follows a reassigned owner: Vitals by a doctor, Counselling by a nurse', () => {
    const reassigned = steps({ vitals: { owner: 'DOCTOR' }, counsel: { on: true, owner: 'NURSE' } });
    expect(ownsStage('NURSE', 'VITALS', reassigned)).toBe(false);
    expect(ownsStage('DOCTOR', 'VITALS', reassigned)).toBe(true);
    expect(ownsStage('VISITING_DOCTOR', 'VITALS', reassigned)).toBe(true);
    expect(ownsStage('NURSE', 'COUNSEL', reassigned)).toBe(true);
    expect(ownsStage('DOCTOR', 'COUNSEL', reassigned)).toBe(false);
    expect(ownsStage('DOCTOR', 'WITH_DOCTOR', reassigned)).toBe(true);
  });

  it('gives no one but owner and front desk a stage whose step has no owner', () => {
    const noOwner = steps({ vitals: { owner: undefined } });
    expect(ownsStage('NURSE', 'VITALS', noOwner)).toBe(false);
  });
});

describe('canMoveForward', () => {
  it('lets owner and front desk move any stage', () => {
    expect(canMoveForward('OWNER', 'VITALS', V1)).toBe(true);
    expect(canMoveForward('FRONT_DESK', 'BILLING', V1)).toBe(true);
  });

  it('lets a role move only the stage it owns', () => {
    expect(canMoveForward('NURSE', 'VITALS', V1)).toBe(true);
    expect(canMoveForward('NURSE', 'WITH_DOCTOR', V1)).toBe(false);
  });

  it('moves Vitals forward for a doctor once Vitals is reassigned to the doctor', () => {
    const reassigned = steps({ vitals: { owner: 'DOCTOR' } });
    expect(canMoveForward('DOCTOR', 'VITALS', reassigned)).toBe(true);
    expect(canMoveForward('NURSE', 'VITALS', reassigned)).toBe(false);
  });
});

describe('canMoveBackward', () => {
  it('is owner-only', () => {
    expect(canMoveBackward('OWNER')).toBe(true);
    expect(canMoveBackward('FRONT_DESK')).toBe(false);
  });
});

describe('nextStage', () => {
  it('goes straight to With doctor when Vitals is off', () => {
    expect(nextStage('WAITING', V2)).toBe('WITH_DOCTOR');
  });

  it('goes to Vitals first when Vitals is on', () => {
    expect(nextStage('WAITING', V1)).toBe('VITALS');
    expect(nextStage('VITALS', V1)).toBe('WITH_DOCTOR');
  });

  it('skips Counselling when it is off', () => {
    expect(nextStage('WITH_DOCTOR', V1)).toBe('BILLING');
    expect(nextStage('BILLING', V1)).toBe('DONE');
  });

  it('goes With doctor to Counselling to Billing when Counselling is on', () => {
    expect(nextStage('WITH_DOCTOR', V2)).toBe('COUNSEL');
    expect(nextStage('COUNSEL', V2)).toBe('BILLING');
  });

  it('has no next stage after Done, or from a stage the steps do not have', () => {
    expect(nextStage('DONE', V1)).toBeNull();
    expect(nextStage('COUNSEL', V1)).toBeNull();
  });
});

describe('earlierStages', () => {
  it('lists the stages before the current one in that version, no Counselling by default', () => {
    expect(earlierStages('BILLING', V1)).toEqual(['WAITING', 'VITALS', 'WITH_DOCTOR']);
  });

  it('offers Counselling when that version has it, and not Vitals when it does not', () => {
    expect(earlierStages('BILLING', V2)).toEqual(['WAITING', 'WITH_DOCTOR', 'COUNSEL']);
  });

  it('is empty for Waiting', () => {
    expect(earlierStages('WAITING', V1)).toEqual([]);
  });
});

describe('stepsForVersion', () => {
  const versions: WorkflowVersion[] = [
    { versionNo: 1, steps: V1, publishedAt: '2026-10-01T05:00:00Z' },
    { versionNo: 2, steps: V2, publishedAt: '2026-11-01T05:00:00Z' },
  ];

  it('returns the steps of the visit own version', () => {
    expect(stepsForVersion(1, versions, V2)).toBe(V1);
    expect(stepsForVersion(2, versions, V1)).toBe(V2);
  });

  it('falls back to the given steps when the version is missing', () => {
    expect(stepsForVersion(7, versions, V2)).toBe(V2);
    expect(stepsForVersion(undefined, versions, V2)).toBe(V2);
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
