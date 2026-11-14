import { TestBed } from '@angular/core/testing';
import { specialtyRecord } from '../specialty.fixtures';
import { mountEditor, byText, click, typeInto, EMR_RECORDS } from '../specialty.testing';
import { OncEcogComponent, OncExamComponent, OncLabsComponent, OncToxComponent } from './onco-assessment';
import { OncDecisionComponent } from './onc-decision.component';
import { OncStepsComponent, orderedSteps } from './onc-steps.component';
import { SpOncEcog, SpOncExam, SpOncLabs, SpOncTox, SpVitalsLog } from '../specialty.model';

const anchors = [
  { key: 'vitals', component: 'VITALS_LOG' as const },
  { key: 'onc_tox', component: 'ONC_TOX' as const },
  { key: 'onc_bsa', component: 'ONC_EXAM' as const },
  { key: 'onc_labs', component: 'ONC_LABS' as const },
  { key: 'onc_ecog', component: 'ONC_ECOG' as const },
  { key: 'onc_regimen', component: 'CHEMO_PLAN' as const, part: 'TREATMENT' as const },
];
const record = (onco: object = {}) => specialtyRecord('onco', anchors, { onco: { cycleNo: 4, cycle1WeightKg: 64, ...onco } as never });

const tox = (over: Partial<SpOncTox> = {}): SpOncTox => ({
  component: 'ONC_TOX',
  schemaVersion: 1,
  events: [],
  symptoms: [
    { key: 'NAUSEA', patientGrade: 1, confirmedGrade: null },
    { key: 'VOMITING', patientGrade: 0, confirmedGrade: null },
    { key: 'FEVER_LOW_COUNTS', patientGrade: null, confirmedGrade: null },
  ],
  ...over,
});
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
const labs = (values: SpOncLabs['values'], drawnAt: string | null = new Date().toISOString()): SpOncLabs => ({ component: 'ONC_LABS', schemaVersion: 1, drawnAt, values });

describe('Side effects (SAH-65-1)', () => {
  it('lists the nine symptoms with the CTCAE line and no confirmed grade until the doctor picks one', () => {
    const h = mountEditor(OncToxComponent, record(), 'onc_tox');
    expect(h.el.textContent).toContain('CTCAE 0 none · 1 mild · 2 moderate · 3 severe · 4 life-threatening.');
    expect(h.el.querySelectorAll('tbody tr')).toHaveLength(9);
    expect(h.el.textContent).toContain('Not confirmed yet');
    byText(h.el, 'tr', 'Nausea').querySelectorAll<HTMLElement>('button.gr')[2].click();
    expect(h.store.specialtyValue('onc_tox')).toMatchObject({ symptoms: expect.arrayContaining([{ key: 'NAUSEA', patientGrade: null, confirmedGrade: 2 }]) });
  });

  it('"Accept patient grades" copies only the patient’s grades into empty confirmations and Done needs all nine', () => {
    const h = mountEditor(OncToxComponent, record(), 'onc_tox', { onc_tox: tox() });
    const accept = byText(h.el, 'button', 'Accept patient grades (2)');
    accept.click();
    const symptoms = (h.store.specialtyValue('onc_tox') as SpOncTox).symptoms;
    expect(symptoms.find((s) => s.key === 'NAUSEA')?.confirmedGrade).toBe(1);
    expect(symptoms.find((s) => s.key === 'FEVER_LOW_COUNTS')?.confirmedGrade).toBeNull();
    expect(h.store.stateOf('onc_tox')?.done).toBe(false);
  });

  it('warns about grade 3 and about fever or an admission since the last cycle', () => {
    const h = mountEditor(OncToxComponent, record(), 'onc_tox', { onc_tox: tox({ events: ['ADMITTED'] }) });
    byText(h.el, 'tr', 'Nausea').querySelectorAll<HTMLElement>('button.gr')[3].click();
    h.fixture.detectChanges();
    const alerts = Array.from(h.el.querySelectorAll('[data-testid="tox-alert"]')).map((a) => a.textContent?.replace(/\s+/g, ' ').trim());
    expect(alerts).toEqual([
      'Grade 3 or more side effect.Hold today or reduce the dose; record the reason.',
      'Fever or admission since last cycle.Consider pegfilgrastim from this cycle and a 20% dose cut.',
    ]);
  });

  it('keeps the patient’s own WhatsApp check as Later', () => {
    const h = mountEditor(OncToxComponent, record(), 'onc_tox');
    expect(h.el.textContent).toContain('The patient’s own symptom check on WhatsApp is Later.');
  });
});

describe('Examination (SAH-65-2)', () => {
  it('works out the BSA and recalculates doses when the weight moves 10 % from cycle 1', () => {
    const h = mountEditor(OncExamComponent, record(), 'onc_bsa', { onc_bsa: exam({ heightCm: 160, weightKg: 70.4 }) });
    expect(h.el.querySelector('[data-testid="bsa"]')?.textContent).toBe('Body surface area 1.77 m²');
    expect(h.el.querySelector('[data-testid="recalc"]')?.textContent).toContain('Doses are recalculated');
    byText(h.el, 'button', 'Cap at 2.0 m²').click();
    expect(h.store.specialtyValue('onc_bsa')).toMatchObject({ capAt2: true });
  });

  it('measures lesions, colours the change and decides the response by the drawn rule', () => {
    const h = mountEditor(OncExamComponent, record(), 'onc_bsa', { onc_bsa: exam() });
    byText(h.el, 'button.seg-tab, .seg button', 'Tumour & response').click();
    h.fixture.detectChanges();
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Lesion name"]')!, 'Left breast mass', 'input');
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Baseline size in centimetres"]')!, '5', 'input');
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="add-lesion"]');
    h.fixture.detectChanges();
    for (let i = 0; i < 20; i++) {
      byText(h.el, 'button', '−', ).click();
    }
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="response"]')?.textContent).toContain('Partial response (PR)');
    expect(h.el.querySelector('.ch')?.getAttribute('data-colour')).toBe('GREEN');
    expect(h.el.textContent).toContain('RECIST-style rule: complete = all gone');
    byText(h.el, 'button', 'New lesion seen').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="response"]')?.textContent).toContain('Progressive disease (PD)');
  });

  it('marks all systems normal with the drawn texts, and an abnormal system needs what was found', () => {
    const h = mountEditor(OncExamComponent, record(), 'onc_bsa', { onc_bsa: exam({ heightCm: 160, weightKg: 62 }) });
    byText(h.el, '.seg button', 'Systems').click();
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="all-normal"]');
    const systems = (h.store.specialtyValue('onc_bsa') as SpOncExam).systems;
    expect(systems.cvs).toMatchObject({ state: 'NORMAL', text: 'S1 S2 heard, no murmur' });
    expect(systems.bone).toMatchObject({ state: 'NORMAL' });
    expect(Object.keys(systems)).toEqual(['cvs', 'rs', 'pa', 'cns', 'bone']);
    expect(h.store.stateOf('onc_bsa')?.done).toBe(true);
    h.fixture.detectChanges();
    byText(h.el, 'button', 'Abnormal').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('textarea[aria-label="Heart (CVS) findings"]')).not.toBeNull();
    byText(h.el, 'button.chip', 'Raised JVP').click();
    expect((h.store.specialtyValue('onc_bsa') as SpOncExam).systems.cvs).toMatchObject({ state: 'ABNORMAL', chips: ['Raised JVP'] });
    expect(h.store.stateOf('onc_bsa')?.done).toBe(true);
  });

  it('has a fifth system "Spine and bones" (hint Bone spread) with its three chips, and Done needs it marked', () => {
    const h = mountEditor(OncExamComponent, record(), 'onc_bsa', { onc_bsa: exam({ heightCm: 160, weightKg: 62, systems: { cvs: { state: 'NORMAL' }, rs: { state: 'NORMAL' }, pa: { state: 'NORMAL' }, cns: { state: 'NORMAL' } } }) });
    byText(h.el, '.seg button', 'Systems').click();
    h.fixture.detectChanges();
    expect(Array.from(h.el.querySelectorAll('.sysrow .sp-row b')).map((b) => b.textContent)).toEqual(['Heart (CVS)', 'Lungs (RS)', 'Abdomen', 'Nervous system', 'Spine and bones']);
    expect(h.el.textContent).toContain('Bone spread');
    expect(h.el.textContent).toContain('4 of 5 checked');
    expect(h.store.stateOf('onc_bsa')?.done).toBe(false);
    const row = byText(h.el, '.sysrow', 'Spine and bones');
    byText(row, 'button', 'Abnormal').click();
    h.fixture.detectChanges();
    const chips = Array.from(byText(h.el, '.sysrow', 'Spine and bones').querySelectorAll('button.chip:not(.sq)')).map((c) => c.textContent?.replace(/\s+/g, ' ').trim());
    expect(chips).toEqual(['+ Spine tenderness', '+ Rib tenderness', '+ Restricted movement']);
    byText(h.el, 'button.chip', 'Rib tenderness').click();
    expect((h.store.specialtyValue('onc_bsa') as SpOncExam).systems.bone).toMatchObject({ state: 'ABNORMAL', chips: ['Rib tenderness'] });
    expect(h.store.stateOf('onc_bsa')?.done).toBe(true);
  });

  it('says "Port: no blood return yet" until the three checks for a vesicant are ticked', () => {
    const h = mountEditor(OncExamComponent, record(), 'onc_bsa', { onc_bsa: exam() });
    byText(h.el, '.seg button', 'Port / line').click();
    h.fixture.detectChanges();
    byText(h.el, 'button.chip', 'Chemo port (right chest)').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="port-note"]')?.textContent).toBe('Port: no blood return yet');
    for (const label of ['Flushes freely', 'Blood return seen', 'No redness or swelling']) {
      byText(h.el, 'button.chip', label).click();
      h.fixture.detectChanges();
    }
    expect(h.el.querySelector('[data-testid="port-note"]')?.textContent).toBe('Line ready for a vesicant');
  });
});

describe('Assessment steps bar (SAH-75-11 O-4)', () => {
  it('lists the five steps in the drawn order with a number, and a tick on a step whose card is done', () => {
    mountEditor(OncEcogComponent, record(), 'onc_ecog', { onc_ecog: { component: 'ONC_ECOG', schemaVersion: 1, grade: 1 } });
    const steps = TestBed.createComponent(OncStepsComponent);
    steps.detectChanges();
    const el = steps.nativeElement as HTMLElement;
    const names = Array.from(el.querySelectorAll('[data-testid="onc-steps"] .step')).map((b) => b.textContent?.replace(/\s+/g, ' ').trim());
    expect(names).toEqual(['1 Side effects', '2 Examination', '3 Labs', '✓ ECOG (done)', '5 Decision']);
    expect(el.querySelector('[data-testid="onc-steps"]')?.getAttribute('aria-label')).toBe('Assessment steps');
  });
});

describe('Assessment steps order (ADR-026 D14)', () => {
  it('follows the layout: Side effects, Examination, Labs, ECOG, then Decision', () => {
    expect(orderedSteps(['vitals', 'onc_tox', 'onc_bsa', 'onc_labs', 'onc_ecog', 'onc_regimen']).map((s) => s.label)).toEqual(['Side effects', 'Examination', 'Labs', 'ECOG', 'Decision']);
  });

  it('follows an older layout that had ECOG before Labs, and leaves out a card the layout does not have', () => {
    expect(orderedSteps(['onc_tox', 'onc_bsa', 'onc_ecog', 'onc_labs']).map((s) => s.label)).toEqual(['Side effects', 'Examination', 'ECOG', 'Labs', 'Decision']);
    expect(orderedSteps(['onc_tox', 'onc_ecog']).map((s) => s.label)).toEqual(['Side effects', 'ECOG', 'Decision']);
  });
});

describe('Examination card details (SAH-75-11 O-3, O-6)', () => {
  it('shows the vitals of this visit as tiles in the first tab, read from the Vitals card', () => {
    const vitals: SpVitalsLog = { component: 'VITALS_LOG', schemaVersion: 1, entries: [{ id: 'e1', bpSystolic: 128, bpDiastolic: 84, pulse: 104, temperature: { value: 37.2, unit: 'C' }, spo2: 97 }] };
    const h = mountEditor(OncExamComponent, record(), 'onc_bsa', { onc_bsa: exam(), vitals });
    const tiles = Array.from(h.el.querySelectorAll('[data-testid="exam-vitals"] .vtile')).map((t) => t.textContent?.replace(/\s+/g, ' ').trim());
    expect(tiles).toEqual(['BP128/84Normal', 'PR104Fast · check for fever, anaemia, heart', 'Temp37.2 °CNormal', 'SpO₂97 %Normal']);
    expect(h.el.querySelectorAll('[data-testid="exam-vitals"] .vtile.bad')).toHaveLength(1);
  });

  it('says a vital is not recorded yet when the Vitals card is empty', () => {
    const h = mountEditor(OncExamComponent, record(), 'onc_bsa', { onc_bsa: exam() });
    expect(h.el.querySelector('[data-testid="exam-vitals"]')?.textContent).toContain('Not recorded yet');
  });

  it('adds a lesion from a quick-add chip and draws its trend with the earlier cycles', () => {
    const h = mountEditor(OncExamComponent, record(), 'onc_bsa', { onc_bsa: exam() });
    byText(h.el, '.seg button', 'Tumour & response').click();
    h.fixture.detectChanges();
    h.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({
      profile: 'onco',
      someHidden: false,
      oncologyCycles: [
        { cycleNo: 2, date: '2026-09-01', protocolKey: 'AC', levelPct: 100, weightKg: 60, bsaUsed: 1.6, labs: {}, grades: null, lesions: [{ name: 'Skin nodule', cm: 1.4 }] },
        { cycleNo: 3, date: '2026-09-22', protocolKey: 'AC', levelPct: 100, weightKg: 60, bsaUsed: 1.6, labs: {}, grades: null, lesions: [{ name: 'Skin nodule', cm: 1.1 }] },
      ],
    });
    click(h.el, 'button[data-act="quick-lesion"]', 1);
    h.fixture.detectChanges();
    expect(Array.from(h.el.querySelectorAll('thead th')).map((t) => t.textContent?.trim())).toEqual(['Lesion', 'Baseline', 'Last (C3)', 'Today', 'Trend', 'Change', 'Remove']);
    expect((h.store.specialtyValue('onc_bsa') as SpOncExam).lesions).toMatchObject([{ name: 'Skin nodule', baselineCm: 1, todayCm: 1 }]);
    expect(h.el.querySelector('.spk svg')?.getAttribute('aria-label')).toBe('Size of Skin nodule over the cycles: 1, 1.4, 1.1, 1');
  });
});

describe('Pre-chemo labs and ECOG (SAH-65-3)', () => {
  it('draws a trend with the earlier cycles and names the cycles in the column head (O-7)', () => {
    const h = mountEditor(OncLabsComponent, record(), 'onc_labs', { onc_labs: labs({ hb: 11 }) });
    h.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({
      profile: 'onco',
      someHidden: false,
      oncologyCycles: [1, 2, 3].map((n) => ({ cycleNo: n, date: '2026-09-01', protocolKey: 'AC', levelPct: 100, weightKg: 60, bsaUsed: 1.6, labs: { hb: 9 + n }, grades: null })),
    });
    h.fixture.detectChanges();
    expect(Array.from(h.el.querySelectorAll('thead th')).map((t) => t.textContent?.trim())).toEqual(['Test', 'C1 · C2 · C3', 'Trend', 'Today', 'Needed', 'Check']);
    expect(h.el.querySelector('.spk svg')?.getAttribute('aria-label')).toBe('Haemoglobin over the cycles: 10, 11, 12, 11');
  });

  it('flags values against what is needed, with text, and a badge of how many are out of range', () => {
    const h = mountEditor(OncLabsComponent, record(), 'onc_labs', { onc_labs: labs({ hb: 11, anc: 1200, plt: 200, cr: 1.8, bili: 0.6, alt: 30, lvef: 60 }) });
    expect(h.el.querySelector('[data-testid="lab-anc"]')?.textContent).toBe('✗ Out of range');
    expect(h.el.querySelector('[data-testid="lab-hb"]')?.textContent).toBe('✓ OK');
    expect(h.el.querySelector('[data-testid="labs-badge"]')?.textContent?.trim()).toBe('2 out of range');
    expect(h.el.textContent).toContain('Typed by the doctor (labs are not connected yet)');
  });

  it('says "Older than 72 hours" and refuses a drawn time in the future', () => {
    const old = new Date(Date.now() - 80 * 3_600_000).toISOString();
    const h = mountEditor(OncLabsComponent, record(), 'onc_labs', { onc_labs: labs({ hb: 11 }, old) });
    expect(h.el.querySelector('[data-testid="labs-old"]')?.textContent).toBe('Older than 72 hours');
    TestBed.resetTestingModule();
    const future = new Date(Date.now() + 5 * 3_600_000).toISOString();
    const g = mountEditor(OncLabsComponent, record(), 'onc_labs', { onc_labs: labs({ hb: 11 }, future) });
    expect(g.el.textContent).toContain('The drawn time cannot be in the future.');
  });

  it('is Done only with all seven values and the drawn time', () => {
    const h = mountEditor(OncLabsComponent, record(), 'onc_labs', { onc_labs: labs({ hb: 11, anc: 3000, plt: 200, cr: 0.8, bili: 0.6, alt: 30 }) });
    expect(h.store.stateOf('onc_labs')?.done).toBe(false);
    h.store.setSpecialty('onc_labs', labs({ hb: 11, anc: 3000, plt: 200, cr: 0.8, bili: 0.6, alt: 30, lvef: 60 }));
    expect(h.store.stateOf('onc_labs')?.done).toBe(true);
  });

  it('picks the ECOG grade and says whether it is fit for chemo', () => {
    const h = mountEditor(OncEcogComponent, record(), 'onc_ecog');
    expect(h.el.textContent).toContain('Fully active');
    expect(h.el.textContent).toContain('Fully bedbound');
    byText(h.el, 'button.tile', 'Light work possible').click();
    h.fixture.detectChanges();
    expect((h.store.specialtyValue('onc_ecog') as SpOncEcog).grade).toBe(1);
    expect(h.el.querySelector('[data-testid="ecog-note"]')?.textContent).toBe('ECOG 1 · fit for chemo');
    byText(h.el, 'button.tile', 'In bed or chair over 50% of day').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="ecog-note"]')?.textContent).toBe('ECOG 3 · usually not fit for chemo');
  });
});

describe('Today’s decision (SAH-65-4)', () => {
  const ready: Record<string, SpOncEcog | SpOncTox | SpOncLabs | SpOncExam> = {
    onc_ecog: { component: 'ONC_ECOG', schemaVersion: 1, grade: 1 },
    onc_labs: labs({ hb: 11, anc: 3000, plt: 200, cr: 0.8, bili: 0.6, alt: 30, lvef: 60 }),
    onc_bsa: exam({ heightCm: 160, weightKg: 62 }),
  };

  it('suggests from the facts with the reasons as tick or cross, and says so before ECOG is chosen', () => {
    const h = mountEditor(OncDecisionComponent, record(), '');
    expect(h.el.querySelector('[data-testid="suggested"]')?.textContent).toBe('Suggested: choose the ECOG grade first');
    h.store.setSpecialty('onc_ecog', ready['onc_ecog'] as SpOncEcog);
    h.store.setSpecialty('onc_labs', ready['onc_labs'] as SpOncLabs);
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="suggested"]')?.textContent).toBe('Suggested: Give as planned');
    expect(h.el.textContent).toContain('Worked out from side effects, examination, labs, ECOG and response. You make the final call.');
    expect(h.el.querySelectorAll('.reasons li')).toHaveLength(6);
  });

  it('asks for a reason when the doctor picks something other than the suggestion, and saves it with the decision', () => {
    const h = mountEditor(OncDecisionComponent, record(), '', { onc_ecog: ready['onc_ecog'] as SpOncEcog, onc_labs: ready['onc_labs'] as SpOncLabs });
    byText(h.el, 'button.tile', 'Give, reduce dose').click();
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('You chose differently from the suggestion. Pick a reason (saved with the note):');
    const confirm = h.el.querySelector('button[data-act="confirm-decision"]') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    byText(h.el, 'button.chip', 'Patient preference').click();
    h.fixture.detectChanges();
    expect(confirm.disabled).toBe(false);
    expect(confirm.textContent).toContain('Confirm “Give, reduce dose” →');
    confirm.click();
    const req = h.http.expectOne(`${EMR_RECORDS}/r1/draft`);
    expect((req.request.body as { screen: unknown }).screen).toEqual({ oncDecision: { choice: 'GIVE_REDUCED', reasonKey: 'PATIENT_PREFERENCE' } });
    req.flush(
      specialtyRecord('onco', anchors, {
        onco: { suggestion: 'GIVE_AS_PLANNED', decision: { choice: 'GIVE_REDUCED', reasonKey: 'PATIENT_PREFERENCE', suggestionAtDecision: 'GIVE_AS_PLANNED', stamp: { at: '2026-11-12T05:00:00Z', byMembershipId: 'm1' } }, reasons: [], alerts: [], cycleNo: 4 } as never,
      }, { revision: 4 }),
    );
    expect(h.store.part()).toBe('TREATMENT');
  });

  it('says "Facts changed. Suggested now: …" when the server sees the facts move after the decision', () => {
    const h = mountEditor(
      OncDecisionComponent,
      record({ suggestion: 'DELAY_1_WEEK', factsChanged: true, reasons: [], alerts: [], decision: { choice: 'GIVE_AS_PLANNED', reasonKey: null, suggestionAtDecision: 'GIVE_AS_PLANNED', stamp: { at: '2026-11-12T05:00:00Z', byMembershipId: 'm1' } } }),
      '',
    );
    expect(h.el.querySelector('[data-testid="facts-changed"]')?.textContent).toBe('Facts changed. Suggested now: Delay 1 week');
    expect(h.el.querySelector('[data-testid="decision-badge"]')?.textContent?.trim()).toBe('✓ Confirmed: Give as planned');
  });
});
