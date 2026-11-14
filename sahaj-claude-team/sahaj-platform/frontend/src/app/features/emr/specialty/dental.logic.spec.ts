import {
  ADULT_LOWER,
  ADULT_UPPER,
  CHILD_LOWER,
  CHILD_UPPER,
  courseSummary,
  defaultSittings,
  emptyChart,
  estimateTotalPaise,
  isDuplicateRow,
  isValidTooth,
  legendCounts,
  nextStatus,
  planSummary,
  procedureText,
  splitShares,
  suggestionsFor,
  toggleSurface,
  toothName,
  toothOf,
  withTooth,
} from './dental.logic';
import { arch3d, boxFaces, jaw3d, PRESETS, toothColour, toothSize, wrapYaw } from './dental/dental3d.logic';
import { SpPlanRow } from './specialty.model';

const row = (over: Partial<SpPlanRow> = {}): SpPlanRow => ({
  id: 'r',
  tooth: 36,
  procedureKey: 'ROOT_CANAL',
  procedureText: 'Root canal treatment',
  phase: 1,
  status: 'PLANNED',
  sittingsPlanned: 3,
  currentSitting: 1,
  ...over,
});

describe('dental rules (C-3, B-2: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed)', () => {
  it('has 32 adult and 20 child teeth by FDI number', () => {
    expect(ADULT_UPPER.length + ADULT_LOWER.length).toBe(32);
    expect(CHILD_UPPER.length + CHILD_LOWER.length).toBe(20);
    expect(ADULT_UPPER.every(isValidTooth)).toBe(true);
    expect(CHILD_LOWER.every(isValidTooth)).toBe(true);
    expect(isValidTooth(19)).toBe(false);
    expect(isValidTooth(56)).toBe(false);
    expect(isValidTooth(90)).toBe(false);
  });

  it('keeps only teeth that differ from healthy, no surfaces and G0', () => {
    let chart = emptyChart('ADULT');
    chart = withTooth(chart, { tooth: 36, condition: 'CARIES', surfaces: ['O'], mobility: 0 });
    expect(chart.teeth).toEqual([{ tooth: 36, condition: 'CARIES', surfaces: ['O'], mobility: 0 }]);
    chart = withTooth(chart, { tooth: 36, condition: 'HEALTHY', surfaces: [], mobility: 0 });
    expect(chart.teeth).toEqual([]);
    chart = withTooth(chart, { tooth: 11, condition: 'HEALTHY', surfaces: [], mobility: 2 });
    expect(toothOf(chart, 11).mobility).toBe(2);
  });

  it('allows surfaces only for caries, filled and crown, in M O D B L order', () => {
    const rct = withTooth(emptyChart('ADULT'), { tooth: 36, condition: 'RCT', surfaces: ['O'], mobility: 0 });
    expect(toothOf(rct, 36).surfaces).toEqual([]);
    const t = toggleSurface(toggleSurface({ tooth: 47, condition: 'CARIES', surfaces: [], mobility: 0 }, 'O'), 'M');
    expect(t.surfaces).toEqual(['M', 'O']);
    expect(toggleSurface(t, 'M').surfaces).toEqual(['O']);
  });

  it('a change of the chart clears "All teeth healthy"', () => {
    const chart = { ...emptyChart('ADULT'), allHealthyConfirmed: true };
    expect(withTooth(chart, { tooth: 18, condition: 'TO_EXTRACT', surfaces: [], mobility: 0 }).allHealthyConfirmed).toBe(false);
  });

  it('suggests the drawn treatments with their phases', () => {
    expect(suggestionsFor('CARIES').map((s) => `${s.label}/${s.phase}`)).toEqual(['Composite filling/1', 'Root canal treatment/1']);
    expect(suggestionsFor('MISSING').map((s) => `${s.label}/${s.phase}`)).toEqual(['Implant with crown/3', 'Bridge/2']);
    expect(suggestionsFor('HEALTHY')).toEqual([]);
    expect(procedureText('Composite filling', ['O', 'M'])).toBe('Composite filling (MO)');
    expect(defaultSittings('ROOT_CANAL')).toBe(3);
    expect(defaultSittings('EXTRACTION')).toBe(1);
  });

  it('refuses the same tooth and procedure twice, ignoring case', () => {
    expect(isDuplicateRow([row()], 36, ' root canal TREATMENT ')).toBe(true);
    expect(isDuplicateRow([row()], 46, 'Root canal treatment')).toBe(false);
  });

  it('moves a status Planned → In progress → Done → Planned and summarises the plan', () => {
    expect(nextStatus('PLANNED')).toBe('IN_PROGRESS');
    expect(nextStatus('IN_PROGRESS')).toBe('DONE');
    expect(nextStatus('DONE')).toBe('PLANNED');
    expect(planSummary([row({ status: 'DONE' }), row({ id: 'b', status: 'IN_PROGRESS' }), row({ id: 'c' })])).toBe('3 items · 1 done · 1 in progress · 1 planned');
  });

  it('splits an estimate into equal parts rounded to ₹ 50, the last taking the rest', () => {
    expect(splitShares(450000, 3)).toEqual([150000, 150000, 150000]);
    expect(splitShares(100000, 3)).toEqual([35000, 35000, 30000]);
    expect(splitShares(5000, 1)).toEqual([5000]);
    expect(splitShares(100000, 3).reduce((a, b) => a + b, 0)).toBe(100000);
  });

  it('adds up the tariff and typed amounts and shows "Not in tariff" rows as nothing', () => {
    expect(estimateTotalPaise([row({ estimatePaise: 150000 }), row({ id: 'b', typedEstimatePaise: 50000 }), row({ id: 'c' })])).toBe(200000);
  });

  it('names a tooth and counts the legend', () => {
    expect(toothName(36)).toBe('First molar, lower left');
    expect(toothName(75)).toBe('Second molar (milk tooth), lower left');
    const chart = withTooth(withTooth(emptyChart('ADULT'), { tooth: 36, condition: 'RCT', surfaces: [], mobility: 0 }), { tooth: 46, condition: 'RCT', surfaces: [], mobility: 0 });
    expect(legendCounts(chart, [...ADULT_UPPER, ...ADULT_LOWER]).find((l) => l.condition === 'RCT')?.count).toBe(2);
  });
});

describe('3D jaw (CSS 3D transforms only)', () => {
  it('places 16 teeth on each jaw, the upper above the opening and the lower below', () => {
    const upper = arch3d(ADULT_UPPER, true, 34, 'ADULT');
    const lower = arch3d(ADULT_LOWER, false, 34, 'ADULT');
    expect(upper).toHaveLength(16);
    expect(lower).toHaveLength(16);
    expect(upper[0].transform).toMatch(/^translate3d\(-?\d/);
    const y = (t: string): number => Number(/translate3d\([^,]+, (-?[\d.]+)px/.exec(t)?.[1]);
    expect(y(upper[0].transform)).toBeLessThan(0);
    expect(y(lower[0].transform)).toBeGreaterThan(0);
  });

  it('opening the mouth moves the jaws apart', () => {
    const y = (t: string): number => Number(/translate3d\([^,]+, (-?[\d.]+)px/.exec(t)?.[1]);
    expect(y(arch3d([11], true, 120, 'ADULT')[0].transform)).toBeLessThan(y(arch3d([11], true, 34, 'ADULT')[0].transform));
  });

  it('draws every tooth as six faces and carries its condition', () => {
    const chart = withTooth(emptyChart('ADULT'), { tooth: 36, condition: 'MISSING', surfaces: [], mobility: 0 });
    const teeth = jaw3d(chart, ADULT_UPPER, ADULT_LOWER, 34);
    expect(teeth).toHaveLength(32);
    expect(teeth.every((t) => t.faces.length === 6)).toBe(true);
    expect(teeth.find((t) => t.n === 36)?.condition).toBe('MISSING');
    expect(boxFaces(10, 20, 8)[0]).toEqual({ transform: 'translateZ(4px)', width: 10, height: 20, shade: 1 });
  });

  it('has the six viewpoints, wraps the turn angle and tints a condition', () => {
    expect(PRESETS.map((p) => p.key)).toEqual(['front', 'left', 'right', 'upper', 'lower', 'wide']);
    expect(wrapYaw(190)).toBe(-170);
    expect(wrapYaw(-190)).toBe(170);
    expect(toothColour('#F0E3C6', 'HEALTHY')).toEqual([0xf0, 0xe3, 0xc6]);
    expect(toothColour('#F0E3C6', 'CARIES')).not.toEqual([0xf0, 0xe3, 0xc6]);
    expect(toothSize(36).w).toBeGreaterThan(toothSize(11).w);
    expect(toothSize(75).w).toBeLessThan(toothSize(36).w);
  });

  it('writes the running treatment for the header from a multi-sitting row in progress', () => {
    const row = { tooth: 36, procedureText: 'RCT', status: 'IN_PROGRESS' as const, sittingsPlanned: 3, currentSitting: 2 };
    expect(courseSummary([row])).toBe('RCT on 36 in progress, sitting 2 of 3');
    expect(courseSummary([{ ...row, sittingsPlanned: 1 }])).toBeNull();
    expect(courseSummary([{ ...row, status: 'PLANNED' as const }])).toBeNull();
    expect(courseSummary([])).toBeNull();
  });
});
