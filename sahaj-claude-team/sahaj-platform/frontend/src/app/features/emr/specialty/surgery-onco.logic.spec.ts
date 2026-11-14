import {
  applyProcedure,
  bookMissing,
  cycleTest,
  depositPaise,
  fitnessSummary,
  fitnessWarnings,
  payerChip,
  PROCEDURES,
  rowState,
  TEST_ROWS,
} from './surgery.logic';
import {
  bsa,
  composeCycleNote,
  effectiveWorst,
  labFails,
  labsFresh,
  lesionColour,
  lesionPct,
  lesionSums,
  needsReason,
  previewOnco,
  responseOf,
  suggestionOf,
  weightChangePct,
} from './onco.logic';
import { SpOncExam, SpOncLabs, SpOncTox, SpSurgeryFitness, SpSurgeryPlan } from './specialty.model';

const fitness = (over: Partial<SpSurgeryFitness> = {}): SpSurgeryFitness => ({ component: 'SURGERY_FITNESS', schemaVersion: 1, otherIllness: [], pac: 'FIT', tests: [], ...over });
const allNormal = (): SpSurgeryFitness['tests'] => ['CBC', 'LFT', 'KFT', 'PT_INR', 'BLOOD_SUGAR', 'ECG', 'CHEST_XRAY', 'HIV', 'HBSAG', 'HCV', 'BLOOD_GROUP'].map((key) => ({ key: key as never, state: 'NORMAL' as const }));

describe('surgery (C-7, L-3, B-1, B-3: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed)', () => {
  it('summarises fitness in the drawn order and words', () => {
    expect(fitnessSummary(fitness({ pac: 'UNFIT', tests: allNormal() }))).toEqual({ state: 'NOT_FIT', text: 'Not fit yet: PAC unfit' });
    const abnormal = allNormal().map((t) => (t.key === 'ECG' ? { ...t, state: 'ABNORMAL' as const } : t));
    expect(fitnessSummary(fitness({ tests: abnormal })).text).toBe('Not fit yet: ECG abnormal');
    expect(fitnessSummary(fitness({ tests: allNormal().filter((t) => t.key !== 'CBC' && t.key !== 'PT_INR') })).text).toBe('2 test(s) pending: CBC, PT / INR');
    expect(fitnessSummary(fitness({ pac: 'NOT_DONE', tests: allNormal() }))).toEqual({ state: 'PAC_NOT_DONE', text: 'PAC not done' });
    expect(fitnessSummary(fitness({ pac: 'FIT_WITH_CAUTION', tests: allNormal() }))).toEqual({ state: 'FIT', text: 'Fit for surgery' });
  });

  it('moves a test pending → normal → abnormal and the HIV, HBsAg, HCV row moves as one', () => {
    let v = fitness();
    v = cycleTest(v, ['CBC']);
    expect(rowState(v, ['CBC'])).toBe('NORMAL');
    v = cycleTest(v, ['CBC']);
    expect(rowState(v, ['CBC'])).toBe('ABNORMAL');
    v = cycleTest(v, ['CBC']);
    expect(v.tests).toEqual([]);
    const row = TEST_ROWS.find((r) => r.label === 'HIV, HBsAg, HCV')!;
    const moved = cycleTest(fitness(), row.keys);
    expect(moved.tests.map((t) => `${t.key}:${t.state}`)).toEqual(['HIV:NORMAL', 'HBSAG:NORMAL', 'HCV:NORMAL']);
  });

  it('warns, but does not block, for a blood thinner, another illness and PAC unfit', () => {
    expect(fitnessWarnings(fitness({ bloodThinner: 'ASPIRIN', otherIllness: ['ASTHMA_COPD'], pac: 'UNFIT' }))).toEqual({ blood: true, illness: true, pac: true });
    expect(fitnessWarnings(fitness({ bloodThinner: 'NONE', otherIllness: ['NONE'] }))).toEqual({ blood: false, illness: false, pac: false });
  });

  it('takes the default anaesthesia and stay from the chosen procedure', () => {
    const plan: SpSurgeryPlan = { component: 'SURGERY_PLAN', schemaVersion: 1, procedureKey: null, procedureText: null, anaesthesia: null, admission: null, payer: 'SELF' };
    expect(applyProcedure(plan, 'EXCISION_LIPOMA')).toMatchObject({ procedureText: 'Excision of lipoma', anaesthesia: 'LOCAL', admission: 'DAY_CARE' });
    expect(applyProcedure(plan, 'OPEN_MESH_HERNIOPLASTY')).toMatchObject({ anaesthesia: 'SPINAL', admission: 'IPD_2_DAYS' });
    expect(PROCEDURES).toHaveLength(8);
  });

  it('works out the deposit: Self half the package to ₹ 1,000, any other payer ₹ 10,000', () => {
    expect(depositPaise('SELF', 6_500_000)).toBe(3_300_000);
    expect(depositPaise('SELF', 4_500_000)).toBe(2_300_000);
    expect(depositPaise('SELF', null)).toBeNull();
    expect(depositPaise('INSURANCE_TPA', 6_500_000)).toBe(1_000_000);
    expect(depositPaise('COMPANY', null)).toBe(1_000_000);
  });

  it('lists what is missing before a booking, in the drawn words', () => {
    expect(bookMissing({ hasDiagnosis: false, consentTicked: 1, consentTotal: 5, payer: 'INSURANCE_TPA', preauthSent: false })).toEqual(['diagnosis', '4 consent item(s)', 'pre-authorisation']);
    expect(bookMissing({ hasDiagnosis: true, consentTicked: 5, consentTotal: 5, payer: 'SELF', preauthSent: false })).toEqual([]);
    expect(bookMissing({ hasDiagnosis: true, consentTicked: 0, consentTotal: 0, payer: 'SELF', preauthSent: false })).toEqual(['5 consent item(s)']);
    expect(payerChip('SELF', false)).toBe('Self-pay');
    expect(payerChip('MJPJAY_PMJAY', true)).toBe('MJPJAY / PM-JAY · pre-auth sent');
    expect(payerChip('COMPANY', false)).toBe('Company · pre-auth pending');
  });
});

const labs = (values: SpOncLabs['values'], drawnAt: string | null = '2026-11-12T03:00:00Z'): SpOncLabs => ({ component: 'ONC_LABS', schemaVersion: 1, drawnAt, values });
const exam = (over: Partial<SpOncExam> = {}): SpOncExam => ({
  component: 'ONC_EXAM',
  schemaVersion: 1,
  capAt2: false,
  signs: {},
  lesions: [],
  newLesionSeen: false,
  breastSkin: [],
  systems: {},
  line: { device: null, checks: [] },
  ...over,
});
const lesion = (id: string, baselineCm: number, todayCm: number | null, lastCm: number | null = null) => ({ id, name: id, baselineCm, lastCm, todayCm });

describe('oncology (C-8: value from the EMR mockup, accepted by the human in DEC-039, not clinically reviewed)', () => {
  it('works out the BSA = √(height × weight ÷ 3600) to 2 decimals, capped at 2.0 when the cap is on', () => {
    expect(bsa(160, 62, false)).toEqual({ raw: 1.66, used: 1.66 });
    expect(bsa(180, 110, false)).toEqual({ raw: 2.35, used: 2.35 });
    expect(bsa(180, 110, true)).toEqual({ raw: 2.35, used: 2 });
    expect(bsa(null, 60, false)).toEqual({ raw: null, used: null });
  });

  it('works out the weight change against cycle 1', () => {
    expect(weightChangePct(70.4, 64)).toBe(10);
    expect(weightChangePct(61.2, 64)).toBe(-4.4);
    expect(weightChangePct(null, 64)).toBeNull();
  });

  it('colours a lesion green at −30 % and red at +20 %', () => {
    expect(lesionPct(4, 2.4)).toBeCloseTo(-40);
    expect(lesionColour(-30)).toBe('GREEN');
    expect(lesionColour(-29)).toBe('NONE');
    expect(lesionColour(20)).toBe('RED');
    expect(lesionColour(null)).toBe('NONE');
  });

  it('decides the response: PD (new lesion, or 20 % and 0.5 cm above the nadir), CR, PR at −30 %, else SD', () => {
    expect(responseOf(exam({ lesions: [lesion('a', 5, 5)], newLesionSeen: true }))).toBe('PROGRESSIVE_DISEASE');
    expect(responseOf(exam({ lesions: [lesion('a', 5, 3.7, 3)] }))).toBe('PROGRESSIVE_DISEASE');
    expect(responseOf(exam({ lesions: [lesion('a', 5, 4.2, 4)] }))).toBe('STABLE_DISEASE');
    expect(responseOf(exam({ lesions: [lesion('a', 5, 0)] }))).toBe('COMPLETE_RESPONSE');
    expect(responseOf(exam({ lesions: [lesion('a', 10, 6.5, 8)] }))).toBe('PARTIAL_RESPONSE');
    expect(responseOf(exam({ lesions: [lesion('a', 10, 7.5, 8)] }))).toBe('STABLE_DISEASE');
    expect(responseOf(exam())).toBeNull();
    const sums = lesionSums(exam({ lesions: [lesion('a', 5, 4, 4.5), lesion('b', 3, 2, 2.5)] }))!;
    expect(sums).toMatchObject({ baseline: 8, previous: 7, today: 6, nadir: 7 });
  });

  it('flags the labs against what is needed (hemoglobin 8, ANC 1,500, platelets 100, creatinine 1.5, bilirubin 1.5, ALT 100, LVEF 50)', () => {
    expect(labFails(labs({ hb: 7.9, anc: 1499, plt: 99, cr: 1.6, bili: 1.6, alt: 101, lvef: 49 })).map((f) => f.key)).toEqual(['hb', 'anc', 'plt', 'cr', 'bili', 'alt', 'lvef']);
    expect(labFails(labs({ hb: 8, anc: 1500, plt: 100, cr: 1.5, bili: 1.5, alt: 100, lvef: 50 }))).toEqual([]);
    expect(labFails(labs({ cr: 1.6 }))[0]).toEqual({ key: 'cr', direction: 'ABOVE_NEEDED' });
    expect(labFails(null)).toEqual([]);
  });

  it('calls labs fresh within 72 hours before now', () => {
    const now = new Date('2026-11-12T12:00:00Z');
    expect(labsFresh('2026-11-12T03:00:00Z', now)).toBe(true);
    expect(labsFresh('2026-11-09T12:00:00Z', now)).toBe(true);
    expect(labsFresh('2026-11-09T11:59:00Z', now)).toBe(false);
    expect(labsFresh(null, now)).toBeNull();
  });

  it('suggests in order: Stop, Delay, Change regimen, Give reduced, Give as planned; nothing before ECOG', () => {
    const base = { ecog: 1, countOrganFail: false, fever: false, response: null, lvefLow: false, worstGrade: 0, feverOrAdmission: false };
    expect(suggestionOf({ ...base, ecog: null })).toBeNull();
    expect(suggestionOf({ ...base, ecog: 3, countOrganFail: true })).toBe('STOP');
    expect(suggestionOf({ ...base, countOrganFail: true, response: 'PROGRESSIVE_DISEASE' })).toBe('DELAY_1_WEEK');
    expect(suggestionOf({ ...base, fever: true })).toBe('DELAY_1_WEEK');
    expect(suggestionOf({ ...base, response: 'PROGRESSIVE_DISEASE', worstGrade: 3 })).toBe('CHANGE_REGIMEN');
    expect(suggestionOf({ ...base, lvefLow: true })).toBe('CHANGE_REGIMEN');
    expect(suggestionOf({ ...base, worstGrade: 3 })).toBe('GIVE_REDUCED');
    expect(suggestionOf({ ...base, feverOrAdmission: true })).toBe('GIVE_REDUCED');
    expect(suggestionOf(base)).toBe('GIVE_AS_PLANNED');
    expect(suggestionOf({ ...base, ecog: 2 })).toBe('GIVE_AS_PLANNED');
  });

  it('asks for a reason only when the doctor chooses something other than the suggestion', () => {
    expect(needsReason('GIVE_REDUCED', 'GIVE_AS_PLANNED')).toBe(true);
    expect(needsReason('GIVE_AS_PLANNED', 'GIVE_AS_PLANNED')).toBe(false);
    expect(needsReason(null, 'STOP')).toBe(false);
  });

  const tox = (grade: number, events: SpOncTox['events'] = []): SpOncTox => ({
    component: 'ONC_TOX',
    schemaVersion: 1,
    events,
    symptoms: [{ key: 'FEVER_LOW_COUNTS', patientGrade: null, confirmedGrade: grade }],
  });

  it('previews the whole answer from what is typed and keeps the server’s cycle and cumulative facts', () => {
    const view = previewOnco({
      tox: tox(0),
      exam: exam({ heightCm: 160, weightKg: 70.4, lesions: [lesion('a', 5, 3, 4)], line: { device: 'PORT', checks: ['FLUSHES_FREELY', 'NO_REDNESS_SWELLING'] } }),
      labs: labs({ hb: 11, anc: 1200, plt: 200, cr: 0.8, bili: 0.6, alt: 30, lvef: 60 }),
      ecog: { component: 'ONC_ECOG', schemaVersion: 1, grade: 1 },
      vitals: { id: 'v', temperature: { value: 36.8, unit: 'C' } },
      decision: null,
      server: { cycle1WeightKg: 64, cumulativeDoxorubicinMgM2: 410, cycleNo: 4 } as never,
      now: new Date('2026-11-12T12:00:00Z'),
    });
    expect(view.bsa).toBe(1.77);
    expect(view.weightChangePct).toBe(10);
    expect(view.recalculateDoses).toBe(true);
    expect(view.labFails).toEqual([{ key: 'anc', direction: 'BELOW_NEEDED' }]);
    expect(view.labsBadge).toBe('1 out of range');
    expect(view.labsFresh).toBe(true);
    expect(view.suggestion).toBe('DELAY_1_WEEK');
    expect(view.lineReady).toBe(false);
    expect(view.alerts).toEqual(['LAB_OUT_OF_RANGE', 'WEIGHT_CHANGE_10', 'CUMULATIVE_OVER_400', 'PORT_NO_BLOOD_RETURN']);
    expect(view.cycleNo).toBe(4);
    expect(view.ecogText).toBe('ECOG 1 · fit for chemo');
    expect(view.reasons.find((r) => r.key === 'COUNTS_FEVER')?.ok).toBe(false);
  });

  it('counts a fever event from the grade, the home fever or an admission, and the worst confirmed grade', () => {
    const base = { exam: null, labs: null, ecog: { component: 'ONC_ECOG' as const, schemaVersion: 1 as const, grade: 1 }, vitals: null, decision: null, server: null, now: new Date() };
    expect(previewOnco({ ...base, tox: tox(1) }).feverOrAdmissionEvent).toBe(true);
    expect(previewOnco({ ...base, tox: tox(0, ['ADMITTED']) }).feverOrAdmissionEvent).toBe(true);
    expect(previewOnco({ ...base, tox: tox(0, ['ER_VISIT']) }).feverOrAdmissionEvent).toBe(false);
    expect(effectiveWorst(tox(3))).toBe(3);
    expect(previewOnco({ ...base, tox: tox(3) }).suggestion).toBe('GIVE_REDUCED');
  });

  it('writes the cycle note from recorded facts only', () => {
    const note = composeCycleNote({
      cycleNo: 4,
      ecog: 1,
      worstGrade: 2,
      weightKg: 62,
      bsaUsed: 1.66,
      labs: labs({ hb: 11, lvef: 60 }),
      labsDrawnText: '12 Nov 2026, 8:40 AM',
      response: 'PARTIAL_RESPONSE',
      decision: 'GIVE_AS_PLANNED',
      reasonLabel: null,
      protocolLabel: 'AC',
      levelPct: 100,
      doses: [{ name: 'Doxorubicin', finalMg: 100 }],
    });
    expect(note).toBe(
      'Cycle 4, day 1. ECOG 1, worst side effect grade 2. Weight 62 kg, BSA 1.66 m². Labs (drawn 12 Nov 2026, 8:40 AM): Hb 11, LVEF 60%. Response: Partial response. Decision: Give as planned. Order: AC at 100%: Doxorubicin 100 mg.',
    );
    expect(
      composeCycleNote({ cycleNo: null, ecog: null, worstGrade: null, weightKg: null, bsaUsed: null, labs: null, labsDrawnText: null, response: null, decision: null, reasonLabel: null, protocolLabel: null, levelPct: null, doses: [] }),
    ).toBe('Cycle —, day 1.');
  });
});
