import {
  ANC_FIELDS,
  ancAlerts,
  ancNote,
  anaemiaGrade,
  chartPoint,
  doseFor,
  doseText,
  DOSE_RULES,
  highBp,
  nextResult,
  painBand,
  romPercent,
  stepOne,
  stepValue,
  weightNote,
  weightOnly,
  NO_CENTILE,
  HEP_DOSE_TEXT,
} from './obgyn-paeds-physio.logic';
import { SpAncCheck } from './specialty.model';

const anc = (over: Partial<SpAncCheck> = {}): SpAncCheck => ({ component: 'ANC_CHECK', schemaVersion: 1, today: {}, symptoms: [], orders: [], scans: [], ...over });

describe('obstetrics (C-4: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed)', () => {
  it('BP is high at 140 systolic or 90 diastolic', () => {
    expect(highBp({ bpSystolic: 139, bpDiastolic: 89 })).toBe(false);
    expect(highBp({ bpSystolic: 140, bpDiastolic: 80 })).toBe(true);
    expect(highBp({ bpSystolic: 120, bpDiastolic: 90 })).toBe(true);
    expect(ancNote('bpSystolic', { bpSystolic: 140, bpDiastolic: 80 }, 27, null)).toEqual({ field: 'bpSystolic', label: 'High: 140/90 or above', alert: true });
    expect(ancNote('bpSystolic', { bpSystolic: 124, bpDiastolic: 80 }, 27, null)?.label).toBe('Normal');
  });

  it('grades anaemia mild below 11, moderate below 10, severe below 7', () => {
    expect(anaemiaGrade(11)).toBeNull();
    expect(anaemiaGrade(10.9)).toBe('Mild');
    expect(anaemiaGrade(9.8)).toBe('Moderate');
    expect(anaemiaGrade(6.9)).toBe('Severe');
    expect(ancNote('hbGdl', { hbGdl: 9.8 }, 27, null)).toEqual({ field: 'hbGdl', label: 'Moderate anaemia', alert: true });
  });

  it('checks the fundal height against the weeks, within 2', () => {
    expect(ancNote('fundalHeightCm', { fundalHeightCm: 27 }, 27, null)?.label).toBe('Matches gestational age');
    expect(ancNote('fundalHeightCm', { fundalHeightCm: 29 }, 27, null)?.label).toBe('Matches gestational age');
    expect(ancNote('fundalHeightCm', { fundalHeightCm: 30 }, 27, null)).toEqual({ field: 'fundalHeightCm', label: 'Does not match 27 weeks', alert: true });
    expect(ancNote('fundalHeightCm', { fundalHeightCm: 27 }, null, null)?.label).toBe('Needs LMP or dating scan');
  });

  it('checks the fetal heart between 110 and 160 and the weight change', () => {
    expect(ancNote('fetalHeartBpm', { fetalHeartBpm: 109 }, 27, null)?.label).toBe('Outside 110–160');
    expect(ancNote('fetalHeartBpm', { fetalHeartBpm: 160 }, 27, null)?.label).toBe('Normal (110–160)');
    expect(ancNote('weightKg', { weightKg: 61.5 }, 27, 1.5)?.label).toBe('+1.5 kg since last visit');
    expect(ancNote('weightKg', { weightKg: 61.5 }, 27, null)).toBeNull();
  });

  it('raises the drawn alerts with their texts and buttons', () => {
    const high = ancAlerts(anc({ today: { bpSystolic: 146, bpDiastolic: 92 }, symptoms: ['HEADACHE'] }));
    expect(high[0].title).toBe('Possible pre-eclampsia.');
    expect(high[0].text).toContain('Headache / blurred vision reported.');
    expect(high[0].button).toBe('Order PIH workup');
    const done = ancAlerts(anc({ today: { bpSystolic: 146 }, orders: [{ key: 'PIH_WORKUP' }] }));
    expect(done[0].button).toBe('✓ Workup ordered');
    const moderate = ancAlerts(anc({ today: { hbGdl: 9.8 } }));
    expect(moderate[0]).toMatchObject({ title: 'Moderate anaemia (Hb 9.8).', text: 'Consider IV iron at this stage of pregnancy.', button: 'Add to plan' });
    expect(ancAlerts(anc({ today: { hbGdl: 6 } }))[0].text).toBe('Needs urgent evaluation.');
    expect(ancAlerts(anc({ today: { hbGdl: 10.5 } }))[0].text).toBe('Continue oral iron.');
    expect(ancAlerts(anc({ symptoms: ['REDUCED_MOVEMENTS'] }))[0]).toMatchObject({ title: 'Warning sign reported.', button: 'Order NST', order: 'NST' });
    expect(ancAlerts(anc({ today: { bpSystolic: 120, bpDiastolic: 80, hbGdl: 12 } }))).toEqual([]);
  });

  it('steps a value from its drawn starting point', () => {
    const wt = ANC_FIELDS[0];
    expect(stepValue(null, wt, 1)).toBe(60.5);
    expect(stepValue(61.5, wt, -1)).toBe(61);
    expect(stepValue(11, ANC_FIELDS[5], -1)).toBe(10.9);
  });
});

describe('paediatrics (C-5)', () => {
  const rule = (key: string) => DOSE_RULES.find((r) => r.key === key)!;

  it('works out the dose by weight: mg = min(max, round(weight × mg/kg)), ml = mg ÷ mg per ml to 1 decimal', () => {
    expect(doseFor(10.2, rule('PARACETAMOL_120_5'))).toEqual({ mg: 153, ml: 6.4, capped: false });
    expect(doseFor(10.2, rule('PARACETAMOL_250_5'))).toEqual({ mg: 153, ml: 3.1, capped: false });
    expect(doseFor(10.2, rule('IBUPROFEN_100_5'))).toEqual({ mg: 102, ml: 5.1, capped: false });
    expect(doseText({ mg: 153, ml: 6.4 })).toBe('6.4 ml (153 mg)');
  });

  it('limits the dose to the maximum and gives no dose without a weight', () => {
    expect(doseFor(40, rule('PARACETAMOL_120_5'))).toEqual({ mg: 500, ml: 20.8, capped: true });
    expect(doseFor(60, rule('IBUPROFEN_100_5'))).toEqual({ mg: 400, ml: 20, capped: true });
    expect(doseFor(null, rule('PARACETAMOL_120_5'))).toBeNull();
    expect(doseFor(0, rule('PARACETAMOL_120_5'))).toBeNull();
  });

  it('puts the weight into the drawn 18-month bands (no estimate for other ages)', () => {
    expect(weightNote(undefined, 18, 8.0)).toEqual({ field: 'weight', label: 'Below 3rd centile', alert: true });
    expect(weightNote(undefined, 18, 9.0)?.label).toBe('~15th centile');
    expect(weightNote(undefined, 18, 10.2)?.label).toBe('~25–50th centile');
    expect(weightNote(undefined, 18, 12)?.label).toBe('~50–97th centile');
    expect(weightNote(undefined, 18, 14)).toEqual({ field: 'weight', label: 'Above 97th centile', alert: true });
    expect(weightNote(undefined, 9, 8.0)).toEqual({ field: 'weight', label: NO_CENTILE, alert: false });
    expect(weightNote(undefined, null, 8.0)?.label).toBe(NO_CENTILE);
    expect(weightNote(undefined, 18, null)).toBeNull();
  });

  it('uses the reference bands when the server sends them', () => {
    const bands = [{ measure: 'WEIGHT', sex: null, ageMonthsFrom: 12, ageMonthsTo: 12, upperExclusive: null, label: 'From the table', alert: false }];
    expect(weightNote(bands, 12, 9)?.label).toBe('From the table');
  });

  it('shows only the weight note: length and head circumference notes are hidden (DEC-044, H8)', () => {
    const flags = [
      { field: 'length', label: '~40th centile', alert: false },
      { field: 'weight', label: 'Below 3rd centile', alert: true },
      { field: 'headCirc', label: '~50th centile', alert: false },
    ];
    expect(weightOnly(flags)).toEqual({ field: 'weight', label: 'Below 3rd centile', alert: true });
    expect(weightOnly([flags[0], flags[2]])).toBeNull();
  });

  it('steps by 0.1 kg and 0.5 cm and places the weight on the chart as drawn', () => {
    expect(stepOne(10.2, 10, 0.1, 1)).toBe(10.3);
    expect(stepOne(null, 80, 0.5, -1)).toBe(79.5);
    expect(chartPoint(18, 10.2)).toEqual({ x: 280, y: 90 });
    expect(chartPoint(18, 3)).toEqual({ x: 280, y: 165 });
    expect(chartPoint(0, 20).y).toBe(30);
  });
});

describe('physiotherapy (C-6)', () => {
  it('works out right against normal; extension is 100 % at 0° and 90 % otherwise', () => {
    expect(romPercent(105, 135)).toBe(78);
    expect(romPercent(135, 135)).toBe(100);
    expect(romPercent(0, 0)).toBe(100);
    expect(romPercent(-5, 0)).toBe(90);
    expect(romPercent(null, 135)).toBeNull();
  });

  it('cycles a special test Not done → Positive → Negative → Not done', () => {
    expect(nextResult('NOT_DONE')).toBe('POSITIVE');
    expect(nextResult('POSITIVE')).toBe('NEGATIVE');
    expect(nextResult('NEGATIVE')).toBe('NOT_DONE');
  });

  it('bands the pain pad 0–3, 4–6, 7–10 and writes the home exercise dose', () => {
    expect([0, 3, 4, 6, 7, 10].map(painBand)).toEqual(['good', 'good', 'warn', 'warn', 'crit', 'crit']);
    expect(HEP_DOSE_TEXT({ sets: 3, reps: 10 })).toBe('3 × 10');
    expect(HEP_DOSE_TEXT({ sets: 3, reps: 10, holdSeconds: 30 })).toBe('3 × 10 · hold 30 s');
    expect(HEP_DOSE_TEXT({})).toBe('');
  });
});
