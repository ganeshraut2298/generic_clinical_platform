import { CLINICAL_QUIET, ROW, listOf, manyRows, rowOf } from './admitted.fixtures';

/**
 * The shared fixtures must not leak a change from one spec into another. A spec in another file used to
 * write `listOf().items[0].clinical = ...`, which rewrote ROW for every later spec of the same worker
 * (admitted.model.spec failed in some full runs). These specs keep that door shut.
 */
describe('admitted.fixtures', () => {
  it('freezes ROW and CLINICAL_QUIET, nested objects too, so a change throws at once', () => {
    expect(Object.isFrozen(ROW)).toBe(true);
    expect(Object.isFrozen(CLINICAL_QUIET)).toBe(true);
    expect(Object.isFrozen(CLINICAL_QUIET.allergy)).toBe(true);
    expect(() => {
      (ROW as { goingHomeToday: boolean }).goingHomeToday = true;
    }).toThrow(TypeError);
  });

  it('hands out a fresh first row from listOf, not ROW itself', () => {
    const list = listOf();
    expect(list.items[0]).not.toBe(ROW);
    expect(list.items[0]).toEqual(ROW);
    list.items[0].clinical = {
      escalation: { status: 'OPEN', waitingMinutes: 7, targetMinutes: 15, pastTarget: false },
      latestEws: { score: 8, risk: 'HIGH', recordedAt: '2026-10-24T07:00:00Z' },
      vitalsDue: false,
      allergy: { status: 'NOT_RECORDED' },
    };
    list.items[0].goingHomeToday = true;
    expect(ROW.clinical).toBe(CLINICAL_QUIET);
    expect(ROW.clinical?.escalation).toBeNull();
    expect(ROW.clinical?.latestEws).toBeNull();
    expect(ROW.goingHomeToday).toBe(false);
    expect(listOf().items[0].goingHomeToday).toBe(false);
    expect(listOf().items[0].clinical?.escalation).toBeNull();
  });

  it('copies the nested objects in rowOf and manyRows', () => {
    const a = rowOf();
    const b = rowOf();
    expect(a.clinical).not.toBe(b.clinical);
    expect(a.clinical).toEqual(CLINICAL_QUIET);
    expect(manyRows(2)[0].clinical).not.toBe(manyRows(2)[1].clinical);
  });
});
