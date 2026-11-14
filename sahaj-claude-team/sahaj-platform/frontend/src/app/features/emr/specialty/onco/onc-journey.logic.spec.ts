import { CancerJourney } from '../specialty.model';
import {
  NOT_MEASURED,
  NOT_RECORDED,
  biologyCell,
  cycleText,
  diagnosisCell,
  doxorubicinChip,
  formOf,
  formProblems,
  inputOf,
  intentCell,
  ordinal,
  responseCell,
  shortDate,
} from './onc-journey.logic';

const journey = (over: Partial<CancerJourney> = {}): CancerJourney => ({
  version: 2,
  stageGroup: 'IIIA',
  tnmText: 'cT2 cN2 M0',
  markersText: 'ER+ PR+ HER2−',
  ki67Pct: 30,
  geneticsText: 'BRCA pending',
  intent: 'CURATIVE',
  setting: 'NEOADJUVANT',
  lineNo: 1,
  boardDate: '2026-07-26',
  recordedAt: '2026-07-26T10:00:00Z',
  recordedByName: 'Dr Test',
  ...over,
});
const addDays = (iso: string, n: number): string => new Date(Date.parse(iso) + n * 86_400_000).toISOString().slice(0, 10);

describe('the Cancer journey snapshot cells (SAH-75-7; tagged)', () => {
  it('writes Diagnosis, Biology and Intent · line as drawn', () => {
    expect(diagnosisCell('Ca left breast · IDC G2', journey())).toEqual({ label: 'Diagnosis', line1: 'Ca left breast · IDC G2', line2: 'Stage IIIA · cT2 cN2 M0' });
    expect(biologyCell(journey())).toEqual({ label: 'Biology', line1: 'ER+ PR+ HER2−', line2: 'Ki-67 30% · BRCA pending' });
    expect(intentCell(journey())).toEqual({ label: 'Intent · line', line1: 'Curative · neoadjuvant', line2: '1st line · board 26 Jul' });
  });

  it('reads "Not recorded" for a cell with no value, and leaves a part with no value out', () => {
    expect(diagnosisCell(null, null)).toEqual({ label: 'Diagnosis', line1: NOT_RECORDED, line2: null });
    expect(biologyCell(null).line1).toBe(NOT_RECORDED);
    expect(intentCell(null).line1).toBe(NOT_RECORDED);
    expect(biologyCell(journey({ ki67Pct: null, geneticsText: null })).line2).toBeNull();
    expect(intentCell(journey({ setting: null })).line1).toBe('Curative');
  });

  it('counts the ordinals and shortens the board date', () => {
    expect([1, 2, 3, 4, 10].map(ordinal)).toEqual(['1st', '2nd', '3rd', '4th', '10th']);
    expect(shortDate('2026-07-26')).toBe('26 Jul');
  });

  it('says which cycle it is, or that it is delayed', () => {
    const cycles = [{ state: 'GIVEN' as const }, { state: 'TODAY' as const }];
    expect(cycleText({ cycleNo: 4, cycleCount: 8, cycles })).toBe('Cycle 4 of 8 · day 1');
    expect(cycleText({ cycleNo: 4, cycleCount: 8, cycles: [{ state: 'DELAYED' }] })).toBe('Cycle 4 delayed');
  });

  it('follows the response of the lesion tracker, or says "Not measured"', () => {
    const sums = { baseline: 10, previous: 8, today: 6, nadir: 6, pctFromBaseline: -40.2, pctFromNadir: 0 };
    expect(responseCell({ response: 'PARTIAL_RESPONSE', sums })).toEqual({ line1: 'PR · -40%', line2: 'Partial response since baseline', tone: 'good' });
    expect(responseCell({ response: 'PROGRESSIVE_DISEASE', sums: { ...sums, pctFromBaseline: 25 } }).line1).toBe('PD · +25%');
    expect(responseCell({ response: 'STABLE_DISEASE', sums }).tone).toBe('warn');
    expect(responseCell({ response: null, sums }).line1).toBe(NOT_MEASURED);
    expect(responseCell(null).line1).toBe(NOT_MEASURED);
  });

  it('shows the doxorubicin chip only when there is an earlier dose', () => {
    expect(doxorubicinChip({ doxorubicinGivenMgM2: 180, doxorubicinLimitMgM2: 450 })).toBe('Doxorubicin 180 / 450 mg/m²');
    expect(doxorubicinChip({ doxorubicinGivenMgM2: null, doxorubicinLimitMgM2: 450 })).toBeNull();
    expect(doxorubicinChip(undefined)).toBeNull();
  });
});

describe('the Cancer journey edit form (ours; OQ-75-7)', () => {
  it('starts from the version read, or empty', () => {
    expect(formOf(null).stageGroup).toBe('');
    expect(formOf(journey()).ki67Pct).toBe('30');
  });

  it('refuses a Ki-67 above 100, an over-long text and a board date more than a year ahead', () => {
    const f = { ...formOf(null), ki67Pct: '101', tnmText: 'x'.repeat(41), boardDate: '2030-01-01' };
    const p = formProblems(f, '2026-11-14', addDays);
    expect(p.ki67Pct).toBe('Enter a whole number from 0 to 100.');
    expect(p.tnmText).toBe('Use at most 40 characters.');
    expect(p.boardDate).toBe('Choose a date not more than a year ahead.');
    expect(formProblems({ ...formOf(null), ki67Pct: '30', boardDate: '2026-12-01' }, '2026-11-14', addDays)).toEqual({});
  });

  it('sends every field with the version read, an emptied field as null', () => {
    const body = inputOf({ ...formOf(journey()), tnmText: '', lineNo: '2' }, 2);
    expect(body).toMatchObject({ expectedVersion: 2, stageGroup: 'IIIA', tnmText: null, ki67Pct: 30, lineNo: 2, intent: 'CURATIVE' });
    expect(inputOf(formOf(null), 0)).toEqual({
      expectedVersion: 0,
      stageGroup: null,
      tnmText: null,
      markersText: null,
      ki67Pct: null,
      geneticsText: null,
      intent: null,
      setting: null,
      lineNo: null,
      boardDate: null,
    });
  });
});
