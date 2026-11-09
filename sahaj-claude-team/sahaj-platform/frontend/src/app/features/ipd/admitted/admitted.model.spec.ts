import { AdmittedClinicalFlags } from '../ipd.model';
import {
  ABILITIES_FRONT_DESK,
  ABILITIES_NURSE,
  ABILITIES_OWNER,
  AS_OF,
  CLINICAL_QUIET,
  ROW,
  WARD_A,
  WARD_B,
  rowOf,
  withoutClinical,
} from './admitted.fixtures';
import {
  ALL_WARDS,
  admittedOnText,
  announceText,
  cardFlags,
  cardLabel,
  filterAnnounceText,
  filterRows,
  foldFlags,
  foldedText,
  identityLine,
  initials,
  isCritical,
  lengthOfStay,
  roomText,
  sexLabel,
  showingText,
  visibleActions,
} from './admitted.model';

function clinical(partial: Partial<AdmittedClinicalFlags>): AdmittedClinicalFlags {
  return { ...CLINICAL_QUIET, ...partial };
}

describe('admitted.model', () => {
  describe('filterAnnounceText', () => {
    it('says the count, all, or the empty line', () => {
      expect(filterAnnounceText(2, 61)).toBe('Showing 2 of 61 patients');
      expect(filterAnnounceText(61, 61)).toBe('Showing all 61 patients');
      expect(filterAnnounceText(0, 61)).toBe('No patients match these filters.');
    });
  });

  describe('filterRows', () => {
    const rows = [
      rowOf({ id: '1', patientName: 'Asha Patil', uhid: 'PT-1', bedCode: '101-A', ipNumber: 'IP/1', wardId: WARD_A }),
      rowOf({ id: '2', patientName: 'Ravi Shah', uhid: 'PT-22', bedCode: '201-B', ipNumber: 'IP/2', wardId: WARD_B }),
      rowOf({ id: '3', patientName: 'Sunita More', uhid: null, bedCode: '102-A', ipNumber: 'IP/3', wardId: WARD_A }),
    ];
    const ids = (r: { id: string }[]) => r.map((x) => x.id);

    it('returns everything for empty text and all wards, in the same order', () => {
      expect(ids(filterRows(rows, '', ALL_WARDS))).toEqual(['1', '2', '3']);
      expect(ids(filterRows(rows, '   ', ALL_WARDS))).toEqual(['1', '2', '3']);
    });

    it('matches part of name, UHID, bed code or IP number, ignoring case and edge spaces', () => {
      expect(ids(filterRows(rows, '  ASHA ', ALL_WARDS))).toEqual(['1']);
      expect(ids(filterRows(rows, 'pt-22', ALL_WARDS))).toEqual(['2']);
      expect(ids(filterRows(rows, '102-a', ALL_WARDS))).toEqual(['3']);
      expect(ids(filterRows(rows, 'ip/3', ALL_WARDS))).toEqual(['3']);
      expect(ids(filterRows(rows, 'a', ALL_WARDS))).toEqual(['1', '2', '3']);
      expect(ids(filterRows(rows, 'zzz', ALL_WARDS))).toEqual([]);
    });

    it('filters by ward id and needs both the ward and the text to match', () => {
      expect(ids(filterRows(rows, '', WARD_A))).toEqual(['1', '3']);
      expect(ids(filterRows(rows, 'ravi', WARD_A))).toEqual([]);
      expect(ids(filterRows(rows, 'a', WARD_A))).toEqual(['1', '3']);
      expect(ids(filterRows(rows, '', 'no-such-ward'))).toEqual([]);
    });

    it('does not match the word "null" for a missing UHID', () => {
      expect(ids(filterRows(rows, 'null', ALL_WARDS))).toEqual([]);
    });
  });

  describe('texts of the card', () => {
    it('makes initials from the first two words', () => {
      expect(initials('Asha Patil')).toBe('AP');
      expect(initials('  ravi  kumar shah ')).toBe('RK');
      expect(initials('Meena')).toBe('M');
      expect(initials('')).toBe('');
    });

    it('spells the sex in full and shows a dash when missing', () => {
      expect(sexLabel('M')).toBe('Male');
      expect(sexLabel('F')).toBe('Female');
      expect(sexLabel('O')).toBe('Other');
      expect(sexLabel('female')).toBe('Female');
      expect(sexLabel(null)).toBe('—');
      expect(sexLabel('')).toBe('—');
    });

    it('builds the UHID line and never prints null or undefined', () => {
      expect(identityLine(ROW)).toBe('UHID PT-000042 · 56 · Female');
      expect(identityLine({ uhid: null, age: null, sex: null })).toBe('UHID — · — · —');
      const odd = identityLine({ uhid: undefined as unknown as null, age: undefined as unknown as null, sex: undefined as unknown as null });
      expect(odd).not.toContain('undefined');
      expect(odd).not.toContain('null');
    });

    it('writes Admitted on with the year, the time and IST', () => {
      expect(admittedOnText('2026-10-22T03:50:00Z')).toBe('22 Oct 2026, 9:20 AM IST');
      expect(admittedOnText('not a date')).toBe('—');
    });

    it('writes Length of stay from the server day number and the server date', () => {
      // asOf is 24 Oct 2026, 1:00 PM IST
      expect(lengthOfStay('2026-10-24T03:50:00Z', 1, AS_OF)).toBe('Day 1 (today)');
      expect(lengthOfStay('2026-10-22T03:50:00Z', 3, AS_OF)).toBe('3 days');
      expect(lengthOfStay('2026-10-23T12:00:00Z', 1, AS_OF)).toBe('1 day');
      // 18:00Z is 11:30 PM IST on 23 Oct: yesterday in India.
      expect(lengthOfStay('2026-10-23T18:00:00Z', 2, AS_OF)).toBe('2 days');
      // Just after midnight IST on 24 Oct (18:35Z on 23 Oct) is today.
      expect(lengthOfStay('2026-10-23T18:35:00Z', 1, AS_OF)).toBe('Day 1 (today)');
    });

    it('shows a dash for a missing room', () => {
      expect(roomText({ roomName: null })).toBe('—');
      expect(roomText({ roomName: '  ' })).toBe('—');
      expect(roomText({ roomName: 'Room 12' })).toBe('Room 12');
    });

    it('names the card "<name>, bed <code>"', () => {
      expect(cardLabel(ROW)).toBe('Asha Patil, bed 101-A');
    });

    it('writes the paging lines', () => {
      expect(showingText(24, 61)).toBe('Showing 24 of 61 patients');
      expect(announceText(48, 61)).toBe('Showing 48 of 61 patients');
      expect(announceText(61, 61)).toBe('Showing all 61 patients');
    });
  });

  describe('visibleActions', () => {
    it('draws only what the server allows, in the default order', () => {
      expect(visibleActions(ABILITIES_OWNER).map((a) => a.label)).toEqual([
        'Nursing',
        'EMR',
        'Transfer',
        'Discharge',
        'Deposit',
      ]);
      expect(visibleActions(ABILITIES_NURSE).map((a) => a.label)).toEqual(['Nursing', 'EMR', 'Transfer']);
      expect(visibleActions(ABILITIES_FRONT_DESK).map((a) => a.label)).toEqual(['Transfer', 'Deposit']);
      expect(
        visibleActions({ ...ABILITIES_OWNER, nursing: false, emr: false, transfer: false, discharge: false, deposit: false }),
      ).toEqual([]);
    });

    it('follows the order of the published setup and never adds what the server does not allow (AC41, AC39)', () => {
      expect(visibleActions(ABILITIES_OWNER, ['discharge', 'nursing']).map((a) => a.label)).toEqual([
        'Discharge',
        'Nursing',
      ]);
      expect(visibleActions(ABILITIES_NURSE, ['discharge', 'deposit', 'emr']).map((a) => a.label)).toEqual(['EMR']);
    });
  });

  describe('cardFlags', () => {
    it('gives no flag for a quiet patient', () => {
      expect(cardFlags(ROW, ABILITIES_OWNER)).toEqual([]);
    });

    it('gives no clinical flag when `clinical` is absent (front desk) and keeps Going home and Deposit low', () => {
      const row = withoutClinical(rowOf({ goingHomeToday: true, depositLow: true }));
      const flags = cardFlags(row, ABILITIES_FRONT_DESK);
      expect(flags.map((f) => f.text)).toEqual(['Going home today', 'Deposit low']);
    });

    it('shows "Not seen · N min" with the tooltip for an open escalation, past or within the target', () => {
      const past = cardFlags(
        rowOf({ clinical: clinical({ escalation: { status: 'OPEN', waitingMinutes: 22, targetMinutes: 15, pastTarget: true } }) }),
        ABILITIES_NURSE,
      )[0];
      expect(past.text).toBe('Not seen · 22 min');
      expect(past.tone).toBe('crit');
      expect(past.icon).toBe('siren');
      expect(past.safety).toBe(true);
      expect(past.title).toBe('Early warning escalation raised 22 min ago, past the 15 min target, not seen by a doctor');
      const within = cardFlags(
        rowOf({ clinical: clinical({ escalation: { status: 'OPEN', waitingMinutes: 4, targetMinutes: 60, pastTarget: false } }) }),
        ABILITIES_NURSE,
      )[0];
      expect(within.title).toBe('Early warning escalation raised 4 min ago, within the 60 min target, not seen by a doctor');
    });

    it('shows the amber "Doctor reviewing" once a doctor has acknowledged', () => {
      const flag = cardFlags(
        rowOf({ clinical: clinical({ escalation: { status: 'ACK', waitingMinutes: 30, targetMinutes: 15, pastTarget: false } }) }),
        ABILITIES_NURSE,
      )[0];
      expect(flag.text).toBe('Doctor reviewing');
      expect(flag.tone).toBe('warn');
      expect(flag.safety).toBe(true);
    });

    it('shows the allergy flag only for a recorded allergy, with the full text', () => {
      const text = 'Penicillin (rash), sulfa drugs (swelling)';
      const flag = cardFlags(
        rowOf({ clinical: clinical({ allergy: { status: 'RECORDED', text } }) }),
        ABILITIES_NURSE,
      )[0];
      expect(flag.text).toBe(`Allergy: ${text}`);
      expect(flag.title).toBe(`Allergy: ${text}`);
      expect(flag.tone).toBe('crit');
      expect(flag.icon).toBe('alert');
      expect(flag.safety).toBe(true);
      for (const status of ['NONE_KNOWN', 'NOT_RECORDED'] as const) {
        expect(cardFlags(rowOf({ clinical: clinical({ allergy: { status } }) }), ABILITIES_NURSE)).toEqual([]);
      }
    });

    it('still warns when a recorded allergy comes without its text', () => {
      const flag = cardFlags(rowOf({ clinical: clinical({ allergy: { status: 'RECORDED' } }) }), ABILITIES_NURSE)[0];
      expect(flag.text).toBe('Allergy recorded');
      expect(flag.text).not.toContain('undefined');
    });

    it('shows EWS only for Medium and High, with the score', () => {
      const at = '2026-10-24T06:00:00Z';
      const medium = cardFlags(rowOf({ clinical: clinical({ latestEws: { score: 5, risk: 'MEDIUM', recordedAt: at } }) }), ABILITIES_NURSE);
      expect(medium.map((f) => [f.text, f.tone, f.icon])).toEqual([['EWS 5 · Medium', 'warn', 'heart']]);
      const high = cardFlags(rowOf({ clinical: clinical({ latestEws: { score: 8, risk: 'HIGH', recordedAt: at } }) }), ABILITIES_NURSE);
      expect(high.map((f) => [f.text, f.tone])).toEqual([['EWS 8 · High', 'crit']]);
      expect(cardFlags(rowOf({ clinical: clinical({ latestEws: { score: 1, risk: 'LOW', recordedAt: at } }) }), ABILITIES_NURSE)).toEqual([]);
    });

    it('shows "Vitals due" (amber, clock) from the server field', () => {
      const flag = cardFlags(rowOf({ clinical: clinical({ vitalsDue: true }) }), ABILITIES_NURSE)[0];
      expect(flag.text).toBe('Vitals due');
      expect(flag.tone).toBe('warn');
      expect(flag.icon).toBe('clock');
      expect(flag.safety).toBe(false);
    });

    it('shows "Going home today" (blue, door) for every role', () => {
      const flag = cardFlags(rowOf({ goingHomeToday: true }), ABILITIES_NURSE)[0];
      expect(flag.text).toBe('Going home today');
      expect(flag.tone).toBe('blue');
      expect(flag.icon).toBe('door');
    });

    it('shows "Deposit low" only when the row says so and the card ability allows it', () => {
      const row = rowOf({ depositLow: true });
      expect(cardFlags(row, ABILITIES_NURSE)).toEqual([]);
      const flag = cardFlags(row, ABILITIES_OWNER)[0];
      expect(flag.text).toBe('Deposit low');
      expect(flag.tone).toBe('crit');
      expect(flag.icon).toBe('deposit');
      expect(cardFlags(rowOf({ depositLow: false }), ABILITIES_OWNER)).toEqual([]);
    });

    it('keeps the order: escalation, allergy, EWS, vitals due, going home, deposit low', () => {
      const row = rowOf({
        goingHomeToday: true,
        depositLow: true,
        clinical: {
          escalation: { status: 'OPEN', waitingMinutes: 3, targetMinutes: 15, pastTarget: false },
          latestEws: { score: 7, risk: 'HIGH', recordedAt: '2026-10-24T06:00:00Z' },
          vitalsDue: true,
          allergy: { status: 'RECORDED', text: 'Penicillin' },
        },
      });
      expect(cardFlags(row, ABILITIES_OWNER).map((f) => f.kind)).toEqual([
        'escalation',
        'allergy',
        'ews',
        'vitals',
        'home',
        'deposit',
      ]);
    });

    it('builds no Dose overdue and no Emergency access flag', () => {
      const texts = cardFlags(
        rowOf({
          goingHomeToday: true,
          clinical: clinical({ vitalsDue: true, allergy: { status: 'RECORDED', text: 'Latex' } }),
        }),
        ABILITIES_OWNER,
      ).map((f) => f.text);
      expect(texts.join(' ')).not.toMatch(/Dose overdue|Emergency access/);
    });
  });

  describe('foldFlags', () => {
    const flagsOf = (row: Parameters<typeof cardFlags>[0]) => cardFlags(row, ABILITIES_OWNER);

    it('draws a single flag as it is', () => {
      const r = foldFlags(flagsOf(rowOf({ goingHomeToday: true })));
      expect(r.shown.map((f) => f.text)).toEqual(['Going home today']);
      expect(r.folded).toEqual([]);
    });

    it('folds the non-safety flags after the first into "+N"', () => {
      const r = foldFlags(
        flagsOf(rowOf({ goingHomeToday: true, depositLow: true, clinical: clinical({ vitalsDue: true }) })),
      );
      expect(r.shown.map((f) => f.text)).toEqual(['Vitals due']);
      expect(r.folded.map((f) => f.text)).toEqual(['Going home today', 'Deposit low']);
      expect(foldedText(r.folded)).toBe('2 more: Going home today, Deposit low');
    });

    it('never folds a safety flag, however many there are', () => {
      const row = rowOf({
        goingHomeToday: true,
        depositLow: true,
        clinical: {
          escalation: { status: 'OPEN', waitingMinutes: 3, targetMinutes: 15, pastTarget: false },
          latestEws: { score: 7, risk: 'HIGH', recordedAt: '2026-10-24T06:00:00Z' },
          vitalsDue: true,
          allergy: { status: 'RECORDED', text: 'Penicillin' },
        },
      });
      const r = foldFlags(flagsOf(row));
      expect(r.shown.map((f) => f.kind)).toEqual(['escalation', 'allergy', 'ews']);
      expect(r.folded.map((f) => f.text)).toEqual(['Vitals due', 'Going home today', 'Deposit low']);
      expect(r.folded.every((f) => !f.safety)).toBe(true);
    });

    it('folds the other flags behind a single safety flag', () => {
      const r = foldFlags(
        flagsOf(rowOf({ goingHomeToday: true, clinical: clinical({ allergy: { status: 'RECORDED', text: 'Latex' } }) })),
      );
      expect(r.shown.map((f) => f.kind)).toEqual(['allergy']);
      expect(r.folded.map((f) => f.kind)).toEqual(['home']);
    });
  });

  describe('isCritical', () => {
    it('is true only for a High score', () => {
      const at = '2026-10-24T06:00:00Z';
      expect(isCritical(rowOf({ clinical: clinical({ latestEws: { score: 8, risk: 'HIGH', recordedAt: at } }) }))).toBe(true);
      expect(isCritical(rowOf({ clinical: clinical({ latestEws: { score: 5, risk: 'MEDIUM', recordedAt: at } }) }))).toBe(false);
      expect(isCritical(withoutClinical(ROW))).toBe(false);
    });
  });
});
