import {
  SAHAJ_COST_3Y,
  applyPreset,
  calcNums,
  cycleFeature,
  headline,
  initialCalcState,
  levelOf,
  parseAmount,
  setNumber,
} from './calculator';
import { COMPARE_FEATURES } from './compare.data';

describe('calculator (same formulas as calcNums in the mockup)', () => {
  it('starts on typical cloud software with 2 doctors and 40 patients a day', () => {
    const c = initialCalcState();
    expect(c.preset).toBe('cloud');
    expect(c.doctors).toBe(2);
    expect(c.ppd).toBe(40);
    expect(c.spend).toBe(3000);
    expect(c.msg).toBe(500);
    expect(c.edited).toBe(false);
  });

  it('works out the default estimates', () => {
    const n = calcNums(initialCalcState());
    expect(n.cur3).toBe((3000 + 500) * 36);
    expect(n.sah3).toBe(SAHAJ_COST_3Y);
    expect(n.sah3).toBe(3998);
    expect(n.custom).toBe(false);
    // 40 patients x 26 days x 12 months = 12,480 visits; 30% need follow-up, 30% miss, 25% recovered
    expect(n.back).toBe(281);
    expect(n.val).toBe(281 * 300);
    // (3 - 1.5) minutes x 40 patients x 26 days / 60
    expect(n.hrs).toBe(26);
    expect(n.yours).toBe(8.5);
    expect(n.gaps.length).toBe(11);
  });

  it('uses (software + messages) x 36 for the cost over 3 years', () => {
    let c = initialCalcState();
    c = setNumber(c, 'spend', 1000);
    c = setNumber(c, 'msg', 200);
    expect(calcNums(c).cur3).toBe(43200);
  });

  it('never gives negative doctor time', () => {
    let c = initialCalcState();
    c = setNumber(c, 'minsNow', 1);
    c = setNumber(c, 'minsApp', 4);
    expect(calcNums(c).hrs).toBe(0);
  });

  it('switches to a custom plan above 2 doctors and gives no Sahaj price', () => {
    const c = setNumber(initialCalcState(), 'doctors', 3);
    const n = calcNums(c);
    expect(n.custom).toBe(true);
    expect(n.sah3).toBeNull();
    expect(headline(c, n)).toEqual({ kind: 'custom', doctors: 3 });
  });

  it('stays on the standard plan at exactly 2 doctors', () => {
    expect(calcNums(setNumber(initialCalcState(), 'doctors', 2)).custom).toBe(false);
  });

  it('says how much you save when today costs more than Sahaj', () => {
    const c = initialCalcState();
    expect(headline(c)).toEqual({ kind: 'save', save: 126000 - 3998, gaps: 11 });
  });

  it('says the first year is free when there is nothing to save', () => {
    const paper = applyPreset(initialCalcState(), 'paper');
    expect(headline(paper)).toEqual({ kind: 'free', gaps: 16, versus: 'paper' });
    let low = setNumber(initialCalcState(), 'spend', 10);
    low = setNumber(low, 'msg', 0);
    expect(headline(low)).toMatchObject({ kind: 'free', versus: 'today' });
  });

  describe('choosing what you use today', () => {
    it('resets the features and the costs to that option', () => {
      let c = initialCalcState();
      c = cycleFeature(c, 'token');
      c = setNumber(c, 'spend', 9999);
      const basic = applyPreset(c, 'basic');
      expect(basic.preset).toBe('basic');
      expect(basic.edited).toBe(false);
      expect(basic.features).toEqual({ token: 1, upi: 1, disc: 1 });
      expect(basic.spend).toBe(400 * 2);
      expect(basic.spendEdited).toBe(false);
      expect(basic.msg).toBe(0);
    });

    it('keeps the doctors and patients a day the visitor chose', () => {
      let c = setNumber(initialCalcState(), 'doctors', 4);
      c = setNumber(c, 'ppd', 90);
      const next = applyPreset(c, 'govt');
      expect(next.doctors).toBe(4);
      expect(next.ppd).toBe(90);
      expect(next.spend).toBe(250 * 4);
    });

    it('treats paper as no software and no features', () => {
      const paper = applyPreset(initialCalcState(), 'paper');
      expect(paper.spend).toBe(0);
      expect(calcNums(paper).yours).toBe(0);
    });
  });

  describe('doctors and software cost', () => {
    it('moves the software cost with the doctors until the visitor types a cost', () => {
      const c = setNumber(initialCalcState(), 'doctors', 5);
      expect(c.spend).toBe(1500 * 5);
    });

    it('stops following the doctors once the visitor typed a cost', () => {
      let c = setNumber(initialCalcState(), 'spend', 2200);
      expect(c.spendEdited).toBe(true);
      c = setNumber(c, 'doctors', 5);
      expect(c.spend).toBe(2200);
    });
  });

  describe('feature rows', () => {
    it('cycles No, Partly, Yes and back to No', () => {
      let c = applyPreset(initialCalcState(), 'paper');
      expect(levelOf(c, 'stylus')).toBe(0);
      c = cycleFeature(c, 'stylus');
      expect(levelOf(c, 'stylus')).toBe(1);
      c = cycleFeature(c, 'stylus');
      expect(levelOf(c, 'stylus')).toBe(2);
      c = cycleFeature(c, 'stylus');
      expect(levelOf(c, 'stylus')).toBe(0);
    });

    it('marks the table as adjusted by the visitor', () => {
      const c = initialCalcState();
      expect(c.edited).toBe(false);
      expect(cycleFeature(c, 'token').edited).toBe(true);
    });

    it('changes the feature count by half a point per step', () => {
      const c = applyPreset(initialCalcState(), 'paper');
      expect(calcNums(cycleFeature(c, 'token')).yours).toBe(0.5);
    });

    it('lists no gaps when every feature is Yes', () => {
      let c = applyPreset(initialCalcState(), 'paper');
      for (const f of COMPARE_FEATURES) {
        c = cycleFeature(cycleFeature(c, f.key), f.key);
      }
      const n = calcNums(c);
      expect(n.gaps).toEqual([]);
      expect(n.yours).toBe(COMPARE_FEATURES.length);
    });
  });

  it('reads typed amounts like the mockup', () => {
    expect(parseAmount('₹ 1,500')).toBe(1500);
    expect(parseAmount('')).toBe(0);
    expect(parseAmount('abc')).toBe(0);
    expect(parseAmount('2.5')).toBe(2.5);
  });
});
