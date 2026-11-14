import { section } from '../emr.fixtures';
import {
  DoneContext,
  footerItems,
  footerSentence,
  formFRequired,
  hiddenSectionKeys,
  isSpecialtyDone,
  isSpecialtyEmpty,
  sanitizeSpecialty,
  specialtyOf,
  vitalsFlags,
} from './specialty.logic';
import {
  EmrSpecialtyValue,
  SpAncCheck,
  SpConsentChecklist,
  SpDentalPlan,
  SpImmunization,
  SpOncLabs,
  SpOncTox,
  SpOutcomeScales,
  SpSurgeryFitness,
  SpToothChart,
  SpVitalsLog,
} from './specialty.model';
import { emptyChart } from './dental.logic';

const ctx = (over: Partial<DoneContext> = {}): DoneContext => ({
  values: {},
  view: null,
  today: '2026-11-12',
  orderStatus: null,
  sectionDirty: () => false,
  ...over,
});

const tox = (grades: (number | null)[]): SpOncTox => ({
  component: 'ONC_TOX',
  schemaVersion: 1,
  symptoms: ['NAUSEA', 'VOMITING', 'MOUTH_SORES', 'LOOSE_MOTIONS', 'TINGLING_NUMBNESS', 'FATIGUE', 'FEVER_LOW_COUNTS', 'PAIN', 'LOSS_OF_APPETITE']
    .slice(0, grades.length)
    .map((key, i) => ({ key: key as never, patientGrade: null, confirmedGrade: grades[i] })),
  events: [],
});

describe('specialty Done rules (ADR-023 D3)', () => {
  it('VITALS_LOG is Done with one entry in this record', () => {
    const empty: SpVitalsLog = { component: 'VITALS_LOG', schemaVersion: 1, entries: [] };
    expect(isSpecialtyDone('VITALS_LOG', empty, ctx(), 'vitals')).toBe(false);
    expect(isSpecialtyDone('VITALS_LOG', { ...empty, entries: [{ id: 'a', pulse: 80 }] }, ctx(), 'vitals')).toBe(true);
  });

  it('EXAM_GENERAL needs a mark and text for every Abnormal system', () => {
    const base = { component: 'EXAM_GENERAL', schemaVersion: 1, signs: {}, systems: {} } as const;
    expect(isSpecialtyDone('EXAM_GENERAL', { ...base }, ctx(), 'exam')).toBe(false);
    expect(isSpecialtyDone('EXAM_GENERAL', { ...base, signs: { pallor: 'ABSENT' } }, ctx(), 'exam')).toBe(true);
    expect(isSpecialtyDone('EXAM_GENERAL', { ...base, systems: { cvs: { state: 'ABNORMAL', text: '' } } }, ctx(), 'exam')).toBe(false);
    expect(isSpecialtyDone('EXAM_GENERAL', { ...base, systems: { cvs: { state: 'ABNORMAL', text: 'Murmur' } } }, ctx(), 'exam')).toBe(true);
  });

  it('TOOTH_CHART is Done with one tooth not healthy or "All teeth healthy" confirmed', () => {
    const chart = emptyChart('ADULT');
    expect(isSpecialtyDone('TOOTH_CHART', chart, ctx(), 'dental_chart')).toBe(false);
    expect(isSpecialtyDone('TOOTH_CHART', { ...chart, allHealthyConfirmed: true }, ctx(), 'dental_chart')).toBe(true);
    const marked: SpToothChart = { ...chart, teeth: [{ tooth: 36, condition: 'RCT', surfaces: [], mobility: 0 }] };
    expect(isSpecialtyDone('TOOTH_CHART', marked, ctx(), 'dental_chart')).toBe(true);
  });

  it('CONSENT_CHECKLIST needs at least one item and every item ticked', () => {
    const none: SpConsentChecklist = { component: 'CONSENT_CHECKLIST', schemaVersion: 1, items: [] };
    expect(isSpecialtyDone('CONSENT_CHECKLIST', none, ctx(), 'consent')).toBe(false);
    const partly = { ...none, items: [{ key: 'a', confirmed: true }, { key: 'b', confirmed: false }] };
    expect(isSpecialtyDone('CONSENT_CHECKLIST', partly, ctx(), 'consent')).toBe(false);
    expect(isSpecialtyDone('CONSENT_CHECKLIST', { ...none, items: [{ key: 'a', confirmed: true }] }, ctx(), 'consent')).toBe(true);
  });

  it('DENTAL_DONE is derived from plan rows done on this record date', () => {
    const plan: SpDentalPlan = {
      component: 'DENTAL_PLAN',
      schemaVersion: 1,
      rows: [
        { id: 'r1', tooth: 36, procedureKey: 'ROOT_CANAL', procedureText: 'Root canal treatment', phase: 1, status: 'DONE', sittingsPlanned: 3, currentSitting: 2 },
        { id: 'r2', tooth: 16, procedureKey: null, procedureText: 'Polish', phase: 1, status: 'DONE', sittingsPlanned: 1, currentSitting: 1, doneOn: '2026-10-01' },
      ],
    };
    const c = ctx({ values: { tx_plan: { specialty: plan } } });
    expect(isSpecialtyDone('DENTAL_DONE', null, c, 'dental_done')).toBe(true);
    expect(isSpecialtyDone('DENTAL_DONE', null, ctx(), 'dental_done')).toBe(false);
  });

  it('ANC_CHECK needs one of today’s values; FORM_F follows the growth scan order', () => {
    const anc: SpAncCheck = { component: 'ANC_CHECK', schemaVersion: 1, today: {}, symptoms: [], orders: [], scans: [] };
    expect(isSpecialtyDone('ANC_CHECK', anc, ctx(), 'anc')).toBe(false);
    expect(isSpecialtyDone('ANC_CHECK', { ...anc, today: { bpSystolic: 120 } }, ctx(), 'anc')).toBe(true);
    const ordered = ctx({ values: { anc: { specialty: { ...anc, orders: [{ key: 'GROWTH_SCAN_FORM_F' }] } } } });
    expect(formFRequired(ordered)).toBe(true);
    expect(isSpecialtyDone('FORM_F', null, ordered, 'pcpndt')).toBe(false);
    expect(isSpecialtyDone('FORM_F', { component: 'FORM_F', schemaVersion: 1, filledAndSigned: true }, ordered, 'pcpndt')).toBe(true);
    expect(isSpecialtyDone('FORM_F', null, ctx(), 'pcpndt')).toBe(true);
  });

  it('GYNAE_EXAM needs a finding and the chaperone named or declined', () => {
    const base = { component: 'GYNAE_EXAM', schemaVersion: 1, perAbdomen: 'Soft' } as const;
    expect(isSpecialtyDone('GYNAE_EXAM', { ...base }, ctx(), 'gyn_exam')).toBe(false);
    expect(isSpecialtyDone('GYNAE_EXAM', { ...base, chaperoneName: 'Sister' }, ctx(), 'gyn_exam')).toBe(true);
    expect(isSpecialtyDone('GYNAE_EXAM', { ...base, chaperoneDeclined: true }, ctx(), 'gyn_exam')).toBe(true);
  });

  it('IMMUNIZATION is Done when the schedule is reviewed or a vaccine is given today', () => {
    const v: SpImmunization = { component: 'IMMUNIZATION', schemaVersion: 1, givenToday: [], scheduleReviewed: false };
    expect(isSpecialtyDone('IMMUNIZATION', v, ctx(), 'immunization')).toBe(false);
    expect(isSpecialtyDone('IMMUNIZATION', { ...v, scheduleReviewed: true }, ctx(), 'immunization')).toBe(true);
    expect(isSpecialtyDone('IMMUNIZATION', { ...v, givenToday: [{ vaccineKey: 'bcg' }] }, ctx(), 'immunization')).toBe(true);
  });

  it('SURGERY_FITNESS is Done only for "Fit for surgery"; SURGERY_PLAN only when booked and not edited since', () => {
    const allNormal = ['CBC', 'LFT', 'KFT', 'PT_INR', 'BLOOD_SUGAR', 'ECG', 'CHEST_XRAY', 'HIV', 'HBSAG', 'HCV', 'BLOOD_GROUP'].map((key) => ({ key: key as never, state: 'NORMAL' as const }));
    const fit: SpSurgeryFitness = { component: 'SURGERY_FITNESS', schemaVersion: 1, otherIllness: [], pac: 'FIT_WITH_CAUTION', tests: allNormal };
    expect(isSpecialtyDone('SURGERY_FITNESS', fit, ctx(), 'sx_fitness')).toBe(true);
    expect(isSpecialtyDone('SURGERY_FITNESS', { ...fit, tests: [] }, ctx(), 'sx_fitness')).toBe(false);
    const booked = { profile: 'surgery', mode: null, tagNote: '', surgery: { bookState: 'BOOKED' } } as never;
    expect(isSpecialtyDone('SURGERY_PLAN', null, ctx({ view: booked }), 'sx_plan')).toBe(true);
    expect(isSpecialtyDone('SURGERY_PLAN', null, ctx({ view: booked, sectionDirty: () => true }), 'sx_plan')).toBe(false);
  });

  it('ONC_TOX needs all nine grades confirmed; ONC_LABS all seven values and the drawn time', () => {
    expect(isSpecialtyDone('ONC_TOX', tox([0, 1, 2, 0, 1, 0, 0, 1, 0]), ctx(), 'onc_tox')).toBe(true);
    expect(isSpecialtyDone('ONC_TOX', tox([0, 1, 2, 0, 1, 0, 0, 1, null]), ctx(), 'onc_tox')).toBe(false);
    const labs: SpOncLabs = { component: 'ONC_LABS', schemaVersion: 1, drawnAt: '2026-11-12T03:00:00Z', values: { hb: 11, anc: 3000, plt: 200, cr: 0.8, bili: 0.6, alt: 30, lvef: 60 } };
    expect(isSpecialtyDone('ONC_LABS', labs, ctx(), 'onc_labs')).toBe(true);
    expect(isSpecialtyDone('ONC_LABS', { ...labs, drawnAt: null }, ctx(), 'onc_labs')).toBe(false);
    expect(isSpecialtyDone('ONC_LABS', { ...labs, values: { ...labs.values, lvef: null } }, ctx(), 'onc_labs')).toBe(false);
  });

  it('ONC_EXAM needs weight, height and all five systems marked (Spine and bones too); CHEMO_PLAN follows the order status', () => {
    const exam = {
      component: 'ONC_EXAM',
      schemaVersion: 1,
      heightCm: 160,
      weightKg: 62,
      capAt2: false,
      signs: {},
      lesions: [],
      newLesionSeen: false,
      breastSkin: [],
      systems: { cvs: { state: 'NORMAL' }, rs: { state: 'NORMAL' }, pa: { state: 'NORMAL' } },
      line: { device: null, checks: [] },
    } as const;
    expect(isSpecialtyDone('ONC_EXAM', exam as never, ctx(), 'onc_bsa')).toBe(false);
    const four = { ...exam, systems: { ...exam.systems, cns: { state: 'NORMAL' } } };
    expect(isSpecialtyDone('ONC_EXAM', four as never, ctx(), 'onc_bsa')).toBe(false);
    expect(isSpecialtyDone('ONC_EXAM', { ...four, systems: { ...four.systems, bone: { state: 'NORMAL' } } } as never, ctx(), 'onc_bsa')).toBe(true);
    expect(isSpecialtyDone('CHEMO_PLAN', null, ctx({ orderStatus: 'DRAFT' }), 'onc_regimen')).toBe(false);
    expect(isSpecialtyDone('CHEMO_PLAN', null, ctx({ orderStatus: 'SENT' }), 'onc_regimen')).toBe(true);
    expect(isSpecialtyDone('CHEMO_PLAN', null, ctx({ orderStatus: 'HELD' }), 'onc_regimen')).toBe(true);
  });
});

describe('footer items (ADR-023 D3)', () => {
  const layout = (keys: { key: string; required?: boolean }[]) => keys.map((k) => section({ key: k.key, name: k.key.toUpperCase(), required: k.required ?? false }));

  it('uses the profile words: OT booking and systemic exam', () => {
    const items = footerItems({
      profile: 'surgery',
      mode: null,
      layout: layout([{ key: 'sx_plan', required: true }, { key: 'onc_bsa', required: true }]),
      doneByKey: {},
      values: {},
      decisionRecorded: false,
      holdMode: false,
    });
    expect(items.map((i) => i.label)).toEqual(['OT booking', 'systemic exam']);
    expect(footerSentence(items)).toBe('Still needed: OT booking, systemic exam.');
  });

  it('oncology lists grades, systemic exam, order and decision even when the sections are not required (H2)', () => {
    const items = footerItems({
      profile: 'onco',
      mode: null,
      layout: layout([{ key: 'onc_tox' }, { key: 'onc_bsa' }, { key: 'onc_labs' }, { key: 'onc_regimen' }]),
      doneByKey: {},
      values: { onc_tox: { specialty: tox([0, 1, 2]) } },
      decisionRecorded: false,
      holdMode: false,
    });
    expect(items.map((i) => i.label)).toEqual(['6 side effect grade(s)', 'systemic exam', 'send chemo order', "today's decision"]);
  });

  it('says "record the hold" for a Delay or Stop decision and drops the decision item once recorded', () => {
    const items = footerItems({
      profile: 'onco',
      mode: null,
      layout: layout([{ key: 'onc_tox' }, { key: 'onc_regimen' }]),
      doneByKey: { onc_tox: true },
      values: {},
      decisionRecorded: true,
      holdMode: true,
    });
    expect(items.map((i) => i.label)).toEqual(['record the hold']);
  });

  it('physio SESSION mode asks for one treatment given today, in its own sentence', () => {
    const items = footerItems({ profile: 'physio', mode: 'SESSION', layout: layout([{ key: 'modalities' }]), doneByKey: {}, values: {}, decisionRecorded: false, holdMode: false });
    expect(footerSentence(items)).toBe('Select at least one treatment given today.');
    const given = footerItems({
      profile: 'physio',
      mode: 'SESSION',
      layout: layout([{ key: 'modalities' }]),
      doneByKey: {},
      values: { modalities: { specialty: { component: 'MODALITIES', schemaVersion: 1, given: [{ key: 'IFT', text: 'IFT', params: null }] } } },
      decisionRecorded: false,
      holdMode: false,
    });
    expect(given).toEqual([]);
    expect(footerSentence(given)).toBe('Ready to complete.');
    expect(footerItems({ profile: 'physio', mode: 'FIRST_VISIT', layout: [], doneByKey: {}, values: {}, decisionRecorded: false, holdMode: false })).toEqual([]);
  });
});

describe('visit mode hides sections (BR45-6)', () => {
  it('Obstetrics shows only the sections of the picked visit type', () => {
    expect([...hiddenSectionKeys('og', 'ANC')]).toEqual(['gyn_exam', 'infertility', 'contraception']);
    expect(hiddenSectionKeys('og', 'GYNAE').has('anc')).toBe(true);
    expect(hiddenSectionKeys('og', 'GYNAE').has('pcpndt')).toBe(true);
  });

  it('Gynae keeps the PCPNDT section while Form F is required (H21), and hides it otherwise', () => {
    expect(hiddenSectionKeys('og', 'GYNAE', true).has('pcpndt')).toBe(false);
    expect(hiddenSectionKeys('og', 'GYNAE', true).has('anc')).toBe(true);
    expect(hiddenSectionKeys('og', 'GYNAE', false).has('pcpndt')).toBe(true);
    expect(hiddenSectionKeys('og', 'ANC', true).has('pcpndt')).toBe(false);
  });

  it('Physiotherapy shows tests and gait in the first visit and treatment in a session', () => {
    expect(hiddenSectionKeys('physio', 'FIRST_VISIT').has('modalities')).toBe(true);
    expect(hiddenSectionKeys('physio', 'SESSION').has('special_tests')).toBe(true);
    expect(hiddenSectionKeys('gm', null).size).toBe(0);
  });
});

describe('OUTCOME_SCALES (contract 0.5.1)', () => {
  const none: SpOutcomeScales = { component: 'OUTCOME_SCALES', schemaVersion: 1, rows: [{ scale: 'WOMAC', score: null }] };
  it('is empty without a score and Done with one score', () => {
    expect(isSpecialtyEmpty(none)).toBe(true);
    expect(isSpecialtyDone('OUTCOME_SCALES', none, ctx(), 'func_scales')).toBe(false);
    const scored: SpOutcomeScales = { ...none, rows: [{ scale: 'WOMAC', score: 41 }] };
    expect(isSpecialtyEmpty(scored)).toBe(false);
    expect(isSpecialtyDone('OUTCOME_SCALES', scored, ctx(), 'func_scales')).toBe(true);
  });
});

describe('vitals flags (C-1, C-8)', () => {
  it('flags General Medicine readings with text', () => {
    const flags = vitalsFlags({ id: 'e', bpSystolic: 146, bpDiastolic: 92, pulse: 104, spo2: 93, temperature: { value: 100.1, unit: 'F' }, respRate: 22 }, 'gm');
    expect(flags.map((f) => `${f.field}:${f.label}`)).toEqual(['bp:High', 'pulse:High', 'spo2:Low', 'temperature:Fever', 'respRate:High']);
    expect(vitalsFlags({ id: 'e', bpSystolic: 85 }, 'gm')).toEqual([{ field: 'bp', label: 'Low', alert: false }]);
    expect(vitalsFlags({ id: 'e', bpSystolic: 120, bpDiastolic: 80, pulse: 72 }, 'gm')).toEqual([]);
  });

  it('uses the oncology limits in °C and its own words', () => {
    const flags = vitalsFlags({ id: 'e', bpSystolic: 165, pulse: 110, spo2: 92, temperature: { value: 38.2, unit: 'C' } }, 'onco');
    expect(flags.map((f) => f.label)).toEqual(['Check again', 'Fast · check for fever, anaemia, heart', 'Fever · rule out infection before chemo', 'Low · examine lungs']);
    expect(vitalsFlags({ id: 'e', bpDiastolic: 100 }, 'onco')[0].label).toBe('High');
    expect(vitalsFlags(null, 'gm')).toEqual([]);
  });
});

describe('what is stored and sent', () => {
  it('leaves server-stamped properties out of the body (the bodies are closed)', () => {
    const plan: SpDentalPlan = {
      component: 'DENTAL_PLAN',
      schemaVersion: 1,
      courseId: 'c1',
      estimate: { state: 'SIGNED', totalPaise: 100 },
      rows: [{ id: 'r', tooth: 36, procedureKey: null, procedureText: 'x', phase: 1, status: 'PLANNED', sittingsPlanned: 1, currentSitting: 1, serviceId: 's', estimatePaise: 100, estimateSource: 'TARIFF', sittingSharesPaise: [100], doneOn: '2026-11-12', typedEstimatePaise: 5000 }],
    };
    const out = sanitizeSpecialty(plan) as SpDentalPlan;
    expect(out.estimate).toBeUndefined();
    expect(out.courseId).toBeUndefined();
    expect(Object.keys(out.rows[0]).sort()).toEqual(['currentSitting', 'id', 'phase', 'procedureKey', 'procedureText', 'sittingsPlanned', 'status', 'tooth', 'typedEstimatePaise']);
    expect(sanitizeSpecialty({ component: 'WEIGHT_DOSE', schemaVersion: 1 } as EmrSpecialtyValue)).toEqual({ component: 'WEIGHT_DOSE', schemaVersion: 1 });
  });

  it('keeps writable choices and drops stamps on goals, vaccines and lesions', () => {
    const goals = sanitizeSpecialty({ component: 'GOALS', schemaVersion: 1, goals: [{ id: 'g', text: 't', achieved: true, achievedOn: '2026-11-12' }] }) as never as { goals: object[] };
    expect(goals.goals[0]).toEqual({ id: 'g', text: 't', achieved: true });
    const vax = sanitizeSpecialty({ component: 'IMMUNIZATION', schemaVersion: 1, scheduleReviewed: false, givenToday: [{ vaccineKey: 'bcg', given: { at: 'x', byMembershipId: 'm' } }] }) as never as { givenToday: object[] };
    expect(vax.givenToday[0]).toEqual({ vaccineKey: 'bcg' });
  });

  it('knows an empty value from a stored one', () => {
    expect(isSpecialtyEmpty(emptyChart('ADULT'))).toBe(true);
    expect(isSpecialtyEmpty({ ...emptyChart('ADULT'), shade: 'A3' })).toBe(false);
    expect(isSpecialtyEmpty({ component: 'SESSION_NOTE', schemaVersion: 1, note: '  ' })).toBe(true);
    expect(isSpecialtyEmpty({ component: 'PAIN_SCORE', schemaVersion: 1, score: 0 })).toBe(false);
    expect(specialtyOf({ specialty: { component: 'GOALS', schemaVersion: 1, goals: [] } })?.component).toBe('GOALS');
    expect(specialtyOf({ other: 1 })).toBeNull();
  });
});
