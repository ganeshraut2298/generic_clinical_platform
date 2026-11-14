import { EmrLayoutSection } from '../emr.model';
import { EmrSpecialtyView, EmrStillNeededItem } from '../specialty/specialty.model';
import { ACCENT, PROFILE_CHROME, accentVars, chromeOf } from '../specialty/specialty-registry';
import { assessmentBar, badgeFull, badgeText, cardNumber, dentalAllergyFooter, gmRecap, oncoCounts, readyFooterText, stillNeededText, surgeryRecap } from './frame.logic';

const view = (over: Partial<EmrSpecialtyView>): EmrSpecialtyView => ({ profile: 'gm', mode: null, tagNote: 't', ...over });
const sec = (key: string): EmrLayoutSection => ({ key }) as EmrLayoutSection;
const item = (key: string): EmrStillNeededItem => ({ key, sectionKey: key, label: key, count: null });

describe('the profile chrome registry (SAH-75-1, SAH-75-2; ADR-026 D1)', () => {
  it('draws the section nav on General Medicine and on the generic screen only', () => {
    expect(chromeOf('gm').nav).toBe(true);
    expect(chromeOf(null).nav).toBe(true);
    for (const p of ['dental', 'og', 'paeds', 'physio', 'surgery', 'onco'] as const) {
      expect(chromeOf(p).nav).toBe(false);
    }
  });

  it('draws the part tabs on General Medicine, Dental, Surgery, Oncology and the generic screen; not on Gynae, Paediatrics, Physiotherapy', () => {
    expect(['gm', 'dental', 'surgery', 'onco'].every((p) => PROFILE_CHROME[p as 'gm'].partTabs)).toBe(true);
    expect(chromeOf(null).partTabs).toBe(true);
    expect(['og', 'paeds', 'physio'].some((p) => PROFILE_CHROME[p as 'og'].partTabs)).toBe(false);
  });

  it('has Quick tools on General Medicine, Paediatrics, Physiotherapy and Gynae, and not on Dental, Surgery, Oncology', () => {
    expect(['gm', 'paeds', 'physio', 'og'].every((p) => !!PROFILE_CHROME[p as 'gm'].quickTools)).toBe(true);
    expect(['dental', 'surgery', 'onco'].some((p) => !!PROFILE_CHROME[p as 'dental'].quickTools)).toBe(false);
    expect(PROFILE_CHROME.paeds.quickTools?.sectionKeys).toEqual(['wtdose', 'feeding']);
    expect(PROFILE_CHROME.physio.quickTools?.sectionKeys).toEqual(['hep', 'goals']);
  });

  it('gives the accent colours of BR75-10 and none to the generic screen', () => {
    expect(ACCENT).toEqual({ gm: '#1D5FA8', dental: '#0B6E69', og: '#A3366B', paeds: '#B4471F', physio: '#6D3FC0', surgery: '#1E40AF', onco: '#9D174D' });
    expect(chromeOf(null).accent).toBeUndefined();
    expect(accentVars(chromeOf(null).accent)).toBeNull();
    expect(accentVars('#0B6E69')).toMatchObject({ '--pri': '#0B6E69', '--paper-accent': '#0B6E69' });
  });

  it('keeps the part subtitles of the design and the card titles that differ from the library', () => {
    expect(chromeOf('gm').partSubtitles).toEqual({ ASSESSMENT: 'Complaints and examination', TREATMENT: 'Diagnosis, medicines, tests and services' });
    expect(chromeOf('dental').partSubtitles?.TREATMENT).toBe('Diagnosis, chart, plan, sittings and payments');
    expect(chromeOf('surgery').partSubtitles?.ASSESSMENT).toBe('Complaints, examination, body map, fitness');
    expect(chromeOf('onco').partSubtitles?.ASSESSMENT).toBe('Side effects · examination · labs · decision');
    expect(chromeOf('gm').cardTitles).toMatchObject({ vitals: 'General examination', exam: 'Systemic examination', local_exam: 'Local examination · body map' });
    expect(chromeOf('onco').cardTitles).toEqual({
      complaints: 'Other complaints (optional)',
      onc_tox: 'Side effects since last cycle',
      onc_bsa: 'Examination',
      onc_labs: 'Pre-chemo labs',
      onc_ecog: 'Performance status (ECOG)',
    });
    expect(chromeOf('onco').numberedCards).toEqual(['onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog']);
    expect(chromeOf('onco').badgeWord).toBe('done');
  });

  it('numbers the oncology cards in the order of the layout (ADR-026 D14)', () => {
    const numbered = chromeOf('onco').numberedCards;
    const layout = ['complaints', 'onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog'];
    expect(['complaints', 'onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog'].map((k) => cardNumber(numbered, layout, k))).toEqual([null, 1, 2, 3, 4]);
    const older = ['onc_tox', 'onc_bsa', 'onc_ecog', 'onc_labs'];
    expect(cardNumber(numbered, older, 'onc_ecog')).toBe(3);
    expect(cardNumber(numbered, older, 'onc_labs')).toBe(4);
    expect(cardNumber(numbered, ['onc_tox', 'onc_ecog'], 'onc_ecog')).toBe(2);
    expect(cardNumber(chromeOf('gm').numberedCards, layout, 'onc_tox')).toBeNull();
  });

  it('puts the course panel where the design draws it', () => {
    expect(chromeOf('og').coursePanel).toEqual({ kind: 'ANC', after: 'anc', mode: 'ANC' });
    expect(chromeOf('physio').coursePanel).toEqual({ kind: 'REHAB', before: 'advice' });
    expect(chromeOf('surgery').coursePanel).toEqual({ kind: 'SURGERY', before: 'advice' });
    expect(chromeOf('onco').coursePanel).toEqual({ kind: 'CHEMO', before: 'referral' });
    expect(chromeOf('dental').coursePanel).toBeUndefined();
  });

  it('words the print button of each drawn footer', () => {
    expect(chromeOf('dental').printLabel?.(null)).toBe('Print estimate');
    expect(chromeOf('og').printLabel?.('ANC')).toBe('Print ANC card');
    expect(chromeOf('og').printLabel?.('GYNAE')).toBe('Print Rx');
    expect(chromeOf('paeds').printLabel?.(null)).toBe('Print vaccination card');
    expect(chromeOf('physio').printLabel?.(null)).toBe('Progress report');
    expect(chromeOf('gm').printLabel).toBeUndefined();
  });
});

describe('the badges (SAH-75-2)', () => {
  it('reads "n of m required" and "n of m done", with a tick when every counted section is done', () => {
    expect(badgeText('required', { done: 1, total: 2 })).toBe('1 of 2 required');
    expect(badgeText('done', { done: 2, total: 3 })).toBe('2 of 3 done');
    expect(badgeFull({ done: 2, total: 2 })).toBe(true);
    expect(badgeFull({ done: 0, total: 0 })).toBe(true);
    expect(badgeFull({ done: 1, total: 2 })).toBe(false);
  });

  it('counts the oncology steps from the footer list: three in step 1, two in step 2', () => {
    const layout = ['onc_tox', 'onc_bsa', 'onc_labs', 'diagnosis', 'onc_regimen'].map(sec);
    expect(oncoCounts('ASSESSMENT', layout, [])).toEqual({ done: 3, total: 3 });
    expect(oncoCounts('ASSESSMENT', layout, [item('onc_tox'), item('ONC_DECISION')])).toEqual({ done: 1, total: 3 });
    expect(oncoCounts('TREATMENT', layout, [item('onc_regimen')])).toEqual({ done: 1, total: 2 });
  });

  it('does not count a step the layout does not have', () => {
    expect(oncoCounts('TREATMENT', [sec('diagnosis')], [])).toEqual({ done: 1, total: 1 });
  });
});

describe('the Assessment bar (SAH-75-2)', () => {
  const base = { done: false, hasComplaints: true, view: null };
  it('General Medicine names what is still needed, then what comes next', () => {
    expect(assessmentBar({ ...base, profile: 'gm', missing: ['chief complaints', 'general examination'] })).toBe(
      'Still needed in assessment: chief complaints, general examination. You can still go ahead.',
    );
    expect(assessmentBar({ ...base, profile: 'gm', missing: [], done: true })).toBe('Assessment done. Next: diagnosis, medicines, tests and services.');
  });

  it('Dental has its own next words', () => {
    expect(assessmentBar({ ...base, profile: 'dental', missing: [], done: true })).toBe('Assessment done. Next: diagnosis, chart, plan and payments.');
    expect(assessmentBar({ ...base, profile: 'dental', missing: ['medical history review'] })).toBe('Still needed in assessment: medical history review. You can still go ahead.');
  });

  it('Surgery says "Add complaints." and the fitness sentence', () => {
    const v = view({ surgery: { fitness: { state: 'TESTS_PENDING', text: '2 test(s) pending: PT / INR, Chest X-ray' } } as EmrSpecialtyView['surgery'] });
    expect(assessmentBar({ ...base, profile: 'surgery', missing: [], hasComplaints: false, view: v })).toBe('Add complaints. 2 test(s) pending: PT / INR, Chest X-ray.');
    const fit = view({ surgery: { fitness: { state: 'FIT', text: 'Fit for surgery' } } as EmrSpecialtyView['surgery'] });
    expect(assessmentBar({ ...base, profile: 'surgery', missing: [], view: fit })).toBe('Fit for surgery.');
  });

  it('the generic screen keeps its short sentences', () => {
    expect(assessmentBar({ ...base, profile: null, missing: [] })).toBe('Still needed in assessment. You can still go ahead.');
    expect(assessmentBar({ ...base, profile: null, missing: [], done: true })).toBe('Assessment done.');
  });
});

describe('the sticky footer sentences when nothing is still needed (SAH-75-2)', () => {
  const input = { mode: null, languageName: null, view: null, nextAncWeeks: null, vaccinesToday: 0, medicines: 0 };
  it('General Medicine says the print language, the generic ready line otherwise', () => {
    expect(readyFooterText({ ...input, profile: 'gm', languageName: 'Marathi' })).toBe('Ready to complete. Prescription prints in Marathi with English drug names.');
    expect(readyFooterText({ ...input, profile: null })).toBe('Ready to complete.');
  });

  it('Dental counts the procedures done today', () => {
    expect(readyFooterText({ ...input, profile: 'dental', view: view({ dental: { doneTodayRowIds: ['a', 'b'] } as EmrSpecialtyView['dental'] }) })).toBe('2 procedure(s) done today will be billed.');
    expect(dentalAllergyFooter('Amoxicillin')).toBe('Remove Amoxicillin before completing (penicillin allergy).');
  });

  it('Gynae: warnings, then the next ANC visit from the gestational age, and the Gynae sentence', () => {
    const og = (alerts: string[], weeks: number | null): EmrSpecialtyView =>
      view({ og: { alerts, gestationalAge: weeks === null ? null : { weeks, days: 1 } } as unknown as EmrSpecialtyView['og'] });
    expect(readyFooterText({ ...input, profile: 'og', mode: 'ANC', view: og(['WARNING_SIGN', 'ANAEMIA_MILD'], 27) })).toBe('2 warning(s) on this visit, reviewed before completing.');
    expect(readyFooterText({ ...input, profile: 'og', mode: 'ANC', view: og([], 27), nextAncWeeks: 30 })).toBe('Next ANC visit suggested at 30 weeks (in 3 weeks).');
    expect(readyFooterText({ ...input, profile: 'og', mode: 'GYNAE', view: og([], null) })).toBe('Gynae visit: menstrual history required.');
  });

  it('Paediatrics, Physiotherapy, Surgery and Oncology use their own sentences', () => {
    expect(readyFooterText({ ...input, profile: 'paeds', vaccinesToday: 3, medicines: 2 })).toBe('3 vaccine(s) given today · 2 medicine(s) prescribed');
    expect(readyFooterText({ ...input, profile: 'physio', view: view({ physio: { sessionNo: 4 } as EmrSpecialtyView['physio'] }) })).toBe('Completing marks session 4 as used and books session 5.');
    expect(readyFooterText({ ...input, profile: 'surgery' })).toBe('Ready to complete. Admission letter and pre-op instructions will print.');
    expect(readyFooterText({ ...input, profile: 'onco' })).toBe('Ready to complete. Cycle record, take-home medicines and calendar will print.');
  });

  it('writes "Still needed" with the names it is given', () => {
    expect(stillNeededText(['complaints', 'diagnosis', 'OT booking'])).toBe('Still needed: complaints, diagnosis, OT booking.');
  });
});

describe('the General Medicine recap (SAH-75-11 G10)', () => {
  it('names complaints, BP and PR, and the local findings with MLC', () => {
    expect(gmRecap({ complaints: ['Fever (3 days)'], bp: '142/90', pulse: 82, pins: [{ mlc: false }, { mlc: true }] })).toBe('Fever (3 days) · BP 142/90, PR 82 · 2 local finding(s) incl. MLC');
  });
  it('writes the surgery recap with Murphy, ASA and the fitness sentence (G-S5)', () => {
    expect(surgeryRecap({ complaints: ['Right upper abdominal pain'], murphy: 'POSITIVE', asa: 'II', fitness: '2 test(s) pending: CBC, LFT' })).toBe(
      'Right upper abdominal pain · Murphy positive · ASA II · 2 test(s) pending: CBC, LFT',
    );
    expect(surgeryRecap({ complaints: [], murphy: null, asa: null, fitness: 'PAC not done' })).toBe('no complaints · PAC not done');
  });
  it('says what is not recorded', () => {
    expect(gmRecap({ complaints: [], bp: null, pulse: null, pins: [] })).toBe('no complaints · general exam not recorded');
  });
});
