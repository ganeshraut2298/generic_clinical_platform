import { account, sitting } from './course.fixtures';
import {
  KIND_ACCENT,
  STATUS_WORD,
  UNIT_WORD,
  actionLabel,
  dateProblem,
  doneToast,
  numberOf,
  amountHint,
  amountProblem,
  apiType,
  capsAtBalance,
  chargeText,
  collectLabel,
  encounterText,
  parseRupees,
  payNote,
  payOptions,
  receiptNumber,
  rupees,
  sittingLabel,
  stats,
  suggestedPaise,
  todayAmountPaise,
} from './course.logic';

describe('treatment course rules (SAH-58; B-1 tagged)', () => {
  it('uses the unit words of the five kinds', () => {
    expect(UNIT_WORD).toEqual({ SITTING: 'Sitting', VISIT: 'Visit', CYCLE: 'Cycle', STEP: 'Step', SESSION: 'Session' });
    expect(sittingLabel(sitting({ sittingNo: 3 }), 'SESSION')).toBe('S3');
  });

  it('writes amounts with Indian grouping', () => {
    expect(rupees(12500000)).toBe('₹1,25,000');
    expect(rupees(150000)).toBe('₹1,500');
  });

  describe('encounter and charge texts', () => {
    it('names the OPD visit, or says it is booked', () => {
      expect(encounterText(sitting({ encounterRef: 'OPD-10231' }))).toBe('OPD visit · OPD-10231');
      expect(encounterText(sitting())).toBe('OPD visit · booked');
    });

    it('names a day-care or IPD stay, or says it is a request', () => {
      expect(encounterText(sitting({ encounterKind: 'IPD', encounterLabel: 'Delivery admission', encounterRef: 'IP-2232' }))).toBe('IPD · Delivery admission · IP-2232');
      expect(encounterText(sitting({ encounterKind: 'DAY_CARE', encounterLabel: 'Day-care admission' }))).toBe('Day care · Day-care admission · request');
    });

    it('says "Billed in IPD" with the stay total once the sitting is linked to an admission', () => {
      expect(chargeText(sitting({ settledInIpd: true, ipdBillTotalPaise: 5500000 }))).toBe('Billed in IPD · ₹55,000');
      expect(chargeText(sitting({ settledInIpd: true }))).toBe('Billed in IPD');
      expect(chargeText(sitting({ chargePaise: 300000 }))).toBe('₹3,000');
    });
  });

  describe('the four figures', () => {
    it('shows estimate, paid, advance balance and due today', () => {
      const s = stats(account());
      expect(s.map((x) => x.label)).toEqual(['Course estimate', 'Paid so far', 'Advance balance', 'Due today']);
      expect(s[0]).toMatchObject({ value: '₹6,250', note: '3 sittings · 1 done' });
      expect(s[1]).toMatchObject({ value: '₹6,000', note: '2 receipt(s)' });
      expect(s[2]).toMatchObject({ value: '₹2,750', note: 'Used against next charges', tone: 'good' });
      expect(s[3]).toMatchObject({ value: '₹0', tone: 'good' });
    });

    it('shows "Due from earlier" in red when there is no credit', () => {
      const s = stats(account({ creditPaise: 0, dueFromEarlierPaise: 100000, dueTodayPaise: 250000 }));
      expect(s[2]).toMatchObject({ label: 'Due from earlier', value: '₹1,000', note: 'Not paid yet', tone: 'crit' });
      expect(s[3]).toMatchObject({ value: '₹2,500', tone: 'crit' });
    });

    it('says there is no sitting today', () => {
      expect(stats(account({ todayChargePaise: 0 }))[3].note).toBe('No sitting today');
    });
  });

  describe('payment types and amounts', () => {
    const a = account({ creditPaise: 50000, dueFromEarlierPaise: 100000, todayChargePaise: 150000 });

    it('offers the four types of the mockup', () => {
      const o = payOptions(a, true);
      expect(o.map((x) => x.label)).toEqual(['Today’s sitting', 'Full course balance', 'Part payment', 'Advance']);
      expect(o[1].sub).toBe('Clears the whole estimate');
      expect(o[2].sub).toBe('Any amount now, rest later');
    });

    it('reads "Advance / IPD deposit" when the next planned sitting is an admission', () => {
      const withAdmission = account({ nextPlannedAdmission: { sittingId: 's9', sittingNo: 9, chargePaise: 5500000, note: 'Admission deposit is due by 36 weeks.' }, suggestions: { partPaymentPaise: 50000, ipdDepositPaise: 2200000 } });
      const o = payOptions(withAdmission, true);
      expect(o[3].label).toBe('Advance / IPD deposit');
      expect(o[3].sub).toContain('₹22,000');
    });

    it('takes the advance off first when it is ticked, and asks the full amount when it is not', () => {
      expect(todayAmountPaise(a, true)).toBe(200000);
      expect(todayAmountPaise(a, false)).toBe(250000);
      expect(todayAmountPaise(account({ creditPaise: 900000 }), true)).toBe(0);
    });

    it('suggests an amount for each choice', () => {
      expect(suggestedPaise('today', a, true)).toBe(200000);
      expect(suggestedPaise('balance', a, true)).toBe(a.balancePaise);
      expect(suggestedPaise('partial', account(), true)).toBe(50000);
      expect(suggestedPaise('partial', account({ suggestions: { partPaymentPaise: null, ipdDepositPaise: null } }), true)).toBe(0);
      expect(suggestedPaise('advance', account(), true)).toBe(0);
      expect(suggestedPaise('advance', account({ nextPlannedAdmission: { sittingId: 's', sittingNo: 4, chargePaise: 100000, note: null }, suggestions: { partPaymentPaise: null, ipdDepositPaise: 40000 } }), true)).toBe(40000);
    });

    it('records the right type', () => {
      expect(apiType('today', a)).toBe('VISIT_PAYMENT');
      expect(apiType('balance', a)).toBe('FULL_BALANCE');
      expect(apiType('partial', a)).toBe('PART_PAYMENT');
      expect(apiType('advance', a)).toBe('ADVANCE');
      expect(apiType('advance', account({ nextPlannedAdmission: { sittingId: 's', sittingNo: 4, chargePaise: 1, note: null } }))).toBe('IPD_DEPOSIT');
    });
  });

  describe('checks before collecting', () => {
    it('needs a positive amount in whole rupees', () => {
      expect(amountProblem(0, 'VISIT_PAYMENT', account())).toBe('Nothing to collect for this choice.');
      expect(amountProblem(-100, 'ADVANCE', account())).not.toBeNull();
      expect(amountProblem(150, 'ADVANCE', account())).toBe('Enter whole rupees.');
      expect(amountProblem(50000, 'ADVANCE', account())).toBeNull();
    });

    it('does not let a visit payment, full balance or part payment pass the balance, but lets an advance', () => {
      const a = account({ balancePaise: 25000 });
      expect(amountProblem(30000, 'PART_PAYMENT', a)).toContain('₹250');
      expect(amountProblem(30000, 'VISIT_PAYMENT', a)).not.toBeNull();
      expect(amountProblem(30000, 'FULL_BALANCE', a)).not.toBeNull();
      expect(amountProblem(30000, 'ADVANCE', a)).toBeNull();
      expect(amountProblem(30000, 'IPD_DEPOSIT', a)).toBeNull();
      expect(capsAtBalance('VISIT_PAYMENT')).toBe(true);
      expect(capsAtBalance('ADVANCE')).toBe(false);
    });

    it('words the hint, the button and the note under the panel', () => {
      expect(amountHint('partial', account(), 100000)).toBe('Balance after this: ₹0. Use − / + to change.');
      expect(amountHint('advance', account(), 100000)).toBe('Kept as credit and adjusted automatically in later bills.');
      expect(amountHint('today', account(), 0)).toBe('Nothing to collect for this choice.');
      expect(collectLabel(150000, 'UPI')).toBe('Collect ₹1,500 by UPI');
      expect(collectLabel(0, 'CASH')).toBe('Nothing to collect');
      expect(payNote(account())).toBe(
        'Charges are added when a sitting is marked done. Advances and IPD deposits stay as credit and are used first. Balance on the course: ₹250.',
      );
    });
  });

  it('reads the receipt number and whole rupees', () => {
    expect(receiptNumber('RC-2201')).toBe(2201);
    expect(receiptNumber(null)).toBeNull();
    expect(parseRupees('1,500')).toBe(150000);
    expect(parseRupees('15.5')).toBeNull();
    expect(parseRupees('')).toBeNull();
    expect(parseRupees('-5')).toBeNull();
  });
});

describe('course actions and numbers (SAH-75-10)', () => {
  it('words each drawn action button', () => {
    const s = sitting({ plannedDate: '2026-11-18' });
    expect(actionLabel('MARK_DONE', 'SITTING', s)).toBe('Mark sitting done');
    expect(actionLabel('MARK_DONE', 'CYCLE', s)).toBe('Mark cycle done');
    expect(actionLabel('MISSED', 'SITTING', s)).toBe('Patient did not come');
    expect(actionLabel('REBOOK', 'SITTING', s)).toBe('Rebook');
    expect(actionLabel('RESCHEDULE', 'SITTING', s)).toBe('Reschedule: 18 Nov 2026 ▾');
    expect(actionLabel('REMOVE', 'SITTING', s)).toBe('Remove');
    expect(actionLabel('REQUEST_ADMISSION', 'SITTING', s)).toBe('Move to day care / IPD');
    expect(actionLabel('REQUEST_ADMISSION', 'SITTING', sitting({ encounterKind: 'DAY_CARE' }))).toBe('Request admission');
    expect(actionLabel('ADMIT', 'SITTING', s)).toBe('Admit now');
  });

  it('writes the toast after "Mark done" from the server\'s charge, or says it is billed in IPD', () => {
    expect(doneToast('SITTING', sitting({ displayNo: 2, chargePaise: 150000 }))).toBe('Sitting 2 done · ₹1,500 added to the account');
    expect(doneToast('CYCLE', sitting({ displayNo: 4, settledInIpd: true }))).toBe('Cycle 4 done · billed in IPD');
  });

  it('shows the renumbered number, else the stable one', () => {
    expect(numberOf(sitting({ sittingNo: 3, displayNo: 2 }))).toBe(2);
    expect(sittingLabel(sitting({ sittingNo: 3, displayNo: 2 }), 'STEP')).toBe('S2');
  });

  it('never accepts a date in the past', () => {
    expect(dateProblem('2026-11-13', '2026-11-14')).toBe('Choose today or a later date.');
    expect(dateProblem('2026-11-14', '2026-11-14')).toBeNull();
    expect(dateProblem('', '2026-11-14')).toBe('Choose a date.');
  });

  it('has a status word for Missed and an accent for each kind', () => {
    expect(STATUS_WORD.MISSED).toBe('Missed');
    expect(KIND_ACCENT).toEqual({ DENTAL: '#0B6E69', ANC: '#A3366B', REHAB: '#6D3FC0', SURGERY: '#1E40AF', CHEMO: '#9D174D' });
  });
});
