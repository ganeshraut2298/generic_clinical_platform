import { ABILITIES_FRONT_DESK, ABILITIES_NURSE, ABILITIES_OWNER, CLINICAL_QUIET, rowOf, withoutClinical } from './admitted.fixtures';
import { cardFlags, foldFlags } from './admitted.model';

describe('"Round done" flag (SAH-67-1, ADR-025 D8)', () => {
  const done = rowOf({ clinical: { ...CLINICAL_QUIET, roundDoneToday: true } });

  it('is a good flag with a tick and the text, for the roles that get clinical flags', () => {
    const flags = cardFlags(done, ABILITIES_NURSE);
    expect(flags).toEqual([{ kind: 'round', text: 'Round done', tone: 'good', icon: 'check', title: 'Round done today', safety: false }]);
    expect(cardFlags(done, ABILITIES_OWNER).map((f) => f.kind)).toEqual(['round']);
  });

  it('is not built when the server sends no clinical flags (front desk) or the round is not done', () => {
    expect(cardFlags(withoutClinical(done), ABILITIES_FRONT_DESK)).toEqual([]);
    expect(cardFlags(rowOf({ clinical: { ...CLINICAL_QUIET, roundDoneToday: false } }), ABILITIES_NURSE)).toEqual([]);
    expect(cardFlags(rowOf({ clinical: CLINICAL_QUIET }), ABILITIES_NURSE)).toEqual([]);
  });

  it('comes after "Deposit low" and folds into "+N" behind a safety flag', () => {
    const row = rowOf({ depositLow: true, clinical: { ...CLINICAL_QUIET, roundDoneToday: true, vitalsDue: true } });
    const flags = cardFlags(row, { ...ABILITIES_OWNER, depositLowFlag: true });
    expect(flags.map((f) => f.kind)).toEqual(['vitals', 'deposit', 'round']);
    const folded = foldFlags(flags);
    expect(folded.shown.map((f) => f.kind)).toEqual(['vitals']);
    expect(folded.folded.map((f) => f.kind)).toEqual(['deposit', 'round']);
  });
});
