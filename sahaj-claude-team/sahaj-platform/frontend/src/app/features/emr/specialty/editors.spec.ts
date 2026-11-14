import { TestBed } from '@angular/core/testing';
import { casePaperContext } from '../emr.fixtures';
import { specialtyRecord } from './specialty.fixtures';
import { mountEditor, byText, click, typeInto, flushReference, EMR_RECORDS } from './specialty.testing';
import { ExamGeneralComponent, GmTrendComponent, VitalsLogComponent } from './gm/gm-editors';
import { GmToolsComponent } from './gm/gm-tools.component';
import { AncCheckComponent, FormFComponent, GynaeExamComponent } from './obgyn/obgyn-editors';
import {
  GoalsComponent,
  HomeExercisesComponent,
  MmtTableComponent,
  ModalitiesComponent,
  OutcomeScalesComponent,
  PainScoreComponent,
  PostureGaitComponent,
  RomTableComponent,
  SessionNoteComponent,
  SpecialTestsComponent,
} from './physio/physio-editors';
import { SpecialtyStripComponent } from './specialty-strip.component';
import { SpAncCheck, SpExamGeneral } from './specialty.model';

const gm = [
  { key: 'vitals', component: 'VITALS_LOG' as const },
  { key: 'exam', component: 'EXAM_GENERAL' as const },
];
const og = [
  { key: 'anc', component: 'ANC_CHECK' as const, required: true },
  { key: 'pcpndt', component: 'FORM_F' as const, required: true },
  { key: 'gyn_exam', component: 'GYNAE_EXAM' as const },
];
const physio = [
  { key: 'pain', component: 'PAIN_SCORE' as const },
  { key: 'posture', component: 'POSTURE_GAIT' as const },
  { key: 'rom', component: 'ROM_TABLE' as const },
  { key: 'mmt', component: 'MMT_TABLE' as const },
  { key: 'special_tests', component: 'SPECIAL_TESTS' as const },
  { key: 'func_scales', component: 'OUTCOME_SCALES' as const },
  { key: 'goals', component: 'GOALS' as const, part: 'TREATMENT' as const },
  { key: 'modalities', component: 'MODALITIES' as const, part: 'TREATMENT' as const },
  { key: 'hep', component: 'HOME_EXERCISES' as const, part: 'TREATMENT' as const },
  { key: 'sessions', component: 'SESSION_NOTE' as const, part: 'TREATMENT' as const },
];

describe('General Medicine (SAH-60)', () => {
  const record = () => specialtyRecord('gm', gm);

  it('records an entry per "Record general examination" and flags the readings with text', () => {
    const h = mountEditor(VitalsLogComponent, record(), 'vitals');
    expect(h.el.querySelector('[data-testid="vitals-sub"]')?.textContent).toBe('The doctor’s entry for this visit is still needed.');
    expect((h.el.querySelector('button[data-act="record-vitals"]') as HTMLButtonElement).disabled).toBe(true);
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="BP systolic in mmHg"]')!, '146');
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="BP diastolic in mmHg"]')!, '92');
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Temp in °F"]')!, '100.4');
    h.fixture.detectChanges();
    const notes = Array.from(h.el.querySelectorAll('[data-testid="stepper-note"]')).map((n) => n.textContent?.trim());
    expect(notes).toEqual(['High', 'High', 'Fever']);
    click(h.el, 'button[data-act="record-vitals"]');
    const entries = (h.store.specialtyValue('vitals') as { entries: { id: string; bpSystolic: number; temperature: { value: number; unit: string } }[] }).entries;
    expect(entries).toHaveLength(1);
    expect(entries[0]).toMatchObject({ bpSystolic: 146, temperature: { value: 100.4, unit: 'F' } });
    expect(h.store.stateOf('vitals')?.done).toBe(true);
  });

  it('shows the server’s flags of the latest entry while nothing is unsaved', () => {
    const view = specialtyRecord('gm', gm, { vitalsFlags: [{ field: 'bp', label: 'High', alert: true }] });
    const h = mountEditor(VitalsLogComponent, view, 'vitals');
    expect(h.el.querySelector('[data-testid="server-flags"]')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('bp: High');
  });

  it('keeps Handwriting and Voice as Later and loads the history when asked', () => {
    const h = mountEditor(VitalsLogComponent, record(), 'vitals');
    expect((byText(h.el, 'button', 'Handwriting') as HTMLButtonElement).disabled).toBe(true);
    expect(byText(h.el, 'button', 'Voice').textContent).toContain('Later');
    byText(h.el, '.seg button', 'History').click();
    h.fixture.detectChanges();
    h.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({
      profile: 'gm',
      someHidden: true,
      vitals: [{ recordedAt: '2026-11-11T05:12:00Z', recordedByName: 'Sr. Test', bp: '146/92', pulse: 82, spo2: 98, temperature: '98.4', respRate: 18, pain: 2, how: 'NURSE' }],
    });
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('146/92');
    expect(h.el.textContent).toContain('Typed at triage');
    expect(h.el.textContent).toContain('Some readings are hidden');
  });

  it('draws the trend from earlier visits only, and says so when there are none yet', () => {
    const h = mountEditor(GmTrendComponent, record(), '');
    h.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({ profile: 'gm', someHidden: false, trend: { bp: [], sugar: [] } });
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="trend-empty"]')).not.toBeNull();
    TestBed.resetTestingModule();
    const g = mountEditor(GmTrendComponent, record(), '');
    g.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({
      profile: 'gm',
      someHidden: false,
      trend: { bp: [162, 154, 150, 154, 146].map((s, i) => ({ date: `2026-10-0${i + 1}`, systolic: s, diastolic: 90 })), sugar: [] },
    });
    g.fixture.detectChanges();
    expect(g.el.querySelector('svg[role="img"]')?.getAttribute('aria-label')).toContain('Systolic BP');
    expect(g.el.querySelectorAll('circle')).toHaveLength(5);
    expect(g.el.querySelector('[data-testid="trend-note"]')?.textContent).toBe('Coming down; still above the 140 target.');
  });

  it('cycles a general sign absent → present → clear, and "Mark all normal" fills the drawn texts', () => {
    const h = mountEditor(ExamGeneralComponent, record(), 'exam');
    byText(h.el, 'button.chip', 'Pallor').click();
    expect((h.store.specialtyValue('exam') as SpExamGeneral).signs.pallor).toBe('ABSENT');
    h.fixture.detectChanges();
    byText(h.el, 'button.chip', 'No pallor').click();
    expect((h.store.specialtyValue('exam') as SpExamGeneral).signs.pallor).toBe('PRESENT');
    h.fixture.detectChanges();
    byText(h.el, 'button.chip', 'Pallor').click();
    expect((h.store.specialtyValue('exam') as SpExamGeneral).signs.pallor).toBeNull();
    click(h.el, 'button[data-act="all-normal"]');
    expect(Array.from(h.el.querySelectorAll('.field .sp-lbl')).map((n) => n.textContent)).toEqual(['General', 'CVS', 'RS', 'P/A', 'CNS']);
    expect((h.store.specialtyValue('exam') as SpExamGeneral).systems.general).toEqual({ state: 'NORMAL', text: 'No pallor, icterus, cyanosis, oedema' });
    expect((h.store.specialtyValue('exam') as SpExamGeneral).systems.cvs).toEqual({ state: 'NORMAL', text: 'S1 S2 heard, no murmur' });
    expect((h.store.specialtyValue('exam') as SpExamGeneral).systems.cns?.text).toBe('Conscious, oriented, no focal deficit');
  });

  it('is Done only when an Abnormal system has text', () => {
    const h = mountEditor(ExamGeneralComponent, record(), 'exam');
    h.fixture.detectChanges();
    byText(h.el, 'button.chip', 'Abnormal').click();
    expect(h.store.stateOf('exam')?.done).toBe(false);
    h.fixture.detectChanges();
    typeInto(h.el.querySelector<HTMLTextAreaElement>('textarea')!, 'Systolic murmur');
    expect(h.store.stateOf('exam')?.done).toBe(true);
  });

  it('applies a visit template by merging, skips the same text, undoes exactly what it added and saves a new one', () => {
    const view = specialtyRecord('gm', gm);
    const h = mountEditor(GmToolsComponent, view, '');
    h.http.expectOne(`${EMR_RECORDS.replace('/records', '')}/visit-templates`).flush([
      {
        id: 't1',
        name: 'Viral fever',
        subLine: 'Fever, body ache · CBC · paracetamol',
        items: {
          diagnoses: [{ itemKey: null, text: 'Viral fever', newToList: false }],
          investigations: [{ itemKey: null, text: 'CBC', newToList: false }],
          medicines: [{ medicineId: null, name: 'Paracetamol 650 mg', frequency: '1-1-1', days: 3, when: 'AFTER_FOOD' }],
        },
      },
    ]);
    h.store.addInvestigations(['CBC']);
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="apply-template"]');
    expect(h.store.listEntries('DIAGNOSIS').map((e) => e.text)).toEqual(['Viral fever']);
    expect(h.store.listEntries('INVESTIGATIONS').map((e) => e.text)).toEqual(['CBC']);
    expect(h.store.prescriptionRows().map((r) => `${r.name} ${r.frequency} ${r.days}`)).toEqual(['Paracetamol 650 mg 1-1-1 3']);
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Template applied: Viral fever. Review each section.');
    click(h.el, 'button[data-act="undo-template"]');
    expect(h.store.listEntries('DIAGNOSIS')).toEqual([]);
    expect(h.store.listEntries('INVESTIGATIONS').map((e) => e.text)).toEqual(['CBC']);
    expect(h.store.prescriptionRows()).toEqual([]);
  });

  it('saves the current visit as a template and reports a name that exists', () => {
    const h = mountEditor(GmToolsComponent, specialtyRecord('gm', gm), '');
    h.http.expectOne('/api/v1/opd/emr/visit-templates').flush([]);
    h.store.addListEntries('DIAGNOSIS', [{ itemKey: null, text: 'Acidity', newToList: true }]);
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="tpl-empty"]')).not.toBeNull();
    typeInto(h.el.querySelector<HTMLInputElement>('details input')!, 'Acidity', 'input');
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="save-template"]');
    const req = h.http.expectOne('/api/v1/opd/emr/visit-templates');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toMatchObject({ name: 'Acidity', subLine: null, items: { diagnoses: [{ text: 'Acidity' }], repeatLastRx: false } });
    req.flush({ type: 'https://sahaj.in/problems/validation', title: 'Invalid', status: 400, detail: 'A template with this name exists' }, { status: 400, statusText: 'Bad Request' });
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="tpl-error"]')?.textContent).toBe('A template with this name exists');
  });
});

describe('Obstetrics and gynaecology (SAH-61)', () => {
  const view = (og_: object = {}) => specialtyRecord('og', og, { og: { gestationalAge: { weeks: 27, days: 1 }, edd: '2027-02-09', eddSource: 'LMP', daysToEdd: 90, obstetricScore: 'G2 P1 L1 A0', weightChangeKg: 1.5, notes: [], alerts: [], riskChips: [{ field: 'lscs', label: 'High risk: previous LSCS', alert: true }], scanStates: [{ key: 'GROWTH', state: 'DUE' }, { key: 'DATING', state: 'DONE' }], dueOrders: [], formFRequired: false, formFCount: { complete: 2, total: 3 }, ...og_ } as never });
  const anc = (today: SpAncCheck['today'] = {}, over: Partial<SpAncCheck> = {}): SpAncCheck => ({ component: 'ANC_CHECK', schemaVersion: 1, today, symptoms: [], orders: [], scans: [], ...over });

  it('shows the antenatal summary from the server, risk chips and the scans with their states', () => {
    const h = mountEditor(AncCheckComponent, view(), 'anc');
    expect(h.el.querySelector('[data-testid="ga"]')?.textContent).toBe('27w 1d');
    expect(h.el.querySelector('[data-testid="edd"]')?.textContent).toBe('9 Feb 2027');
    expect(h.el.textContent).toContain('From LMP');
    expect(h.el.textContent).toContain('G2 P1 L1 A0');
    expect(h.el.textContent).toContain('90 days');
    expect(h.el.textContent).toContain('High risk: previous LSCS');
    expect(h.el.querySelector('.scan.due')?.textContent).toContain('Growth scan');
    expect(h.el.querySelector('[data-testid="formf-text"]')?.textContent).toBe('2 of 3 forms complete for this pregnancy');
  });

  it('shows "Not recorded" for the gestational age without LMP or a dating scan', () => {
    const h = mountEditor(AncCheckComponent, view({ gestationalAge: null, edd: null, eddSource: 'NONE', daysToEdd: null, obstetricScore: null }), 'anc');
    expect(h.el.querySelector('[data-testid="ga"]')?.textContent).toBe('Not recorded');
    expect(h.el.textContent).toContain('Needs LMP or dating scan');
  });

  it('shows the live notes beside each value, with text, and the alerts with their buttons', () => {
    const h = mountEditor(AncCheckComponent, view(), 'anc', { anc: anc({ bpSystolic: 146, bpDiastolic: 92, fundalHeightCm: 31, fetalHeartBpm: 150, hbGdl: 9.8, weightKg: 61.5 }, { symptoms: ['HEADACHE'] }) });
    const notes = Array.from(h.el.querySelectorAll('[data-testid="stepper-note"]')).map((n) => n.textContent?.trim());
    expect(notes).toEqual(['+1.5 kg since last visit', 'High: 140/90 or above', 'High: 140/90 or above', 'Does not match 27 weeks', 'Normal (110–160)', 'Moderate anaemia']);
    const alerts = Array.from(h.el.querySelectorAll('[data-testid="anc-alert"]')).map((a) => a.textContent?.replace(/\s+/g, ' ').trim());
    expect(alerts[0]).toContain('Possible pre-eclampsia.');
    expect(alerts[0]).toContain('Repeat BP after 15 minutes rest and check urine protein. Headache / blurred vision reported.');
    expect(alerts[1]).toContain('Moderate anaemia (Hb 9.8).');
    expect(alerts[1]).toContain('Consider IV iron at this stage of pregnancy.');
    expect(Array.from(h.el.querySelectorAll('.pill')).map((p) => p.textContent?.trim())).toContain('BP high today');
  });

  it('records the PIH workup and adds it to Investigations; no order is sent anywhere', () => {
    const h = mountEditor(AncCheckComponent, view(), 'anc', { anc: anc({ bpSystolic: 146, bpDiastolic: 92 }) });
    byText(h.el, 'button', 'Order PIH workup').click();
    expect((h.store.specialtyValue('anc') as SpAncCheck).orders.map((o) => o.key)).toEqual(['PIH_WORKUP']);
    expect(h.store.listEntries('INVESTIGATIONS').map((e) => e.text)).toEqual(['PIH workup: urine protein, LFT, platelets, uric acid']);
    h.fixture.detectChanges();
    expect(byText(h.el, 'button', '✓ Workup ordered')).toBeTruthy();
  });

  it('orders all the due tests at once and counts what is left', () => {
    const h = mountEditor(AncCheckComponent, view(), 'anc', { anc: anc() });
    expect(h.el.textContent).toContain('Due now · 3 left');
    click(h.el, 'button[data-act="order-all"]');
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Due now · 0 left');
    expect(h.store.listEntries('INVESTIGATIONS').map((e) => e.text)).toEqual(['OGTT (75 g)', 'Td 2nd dose', 'Growth scan + Form F']);
    expect(h.store.footerItems().map((i) => i.key)).toContain('pcpndt');
  });

  it('adds a ticked supplement to the prescription and takes it out when stopped', () => {
    const h = mountEditor(AncCheckComponent, view(), 'anc', { anc: anc() });
    byText(h.el, 'button[data-act="supplement"]', 'Calcium 500 mg + Vit D3').click();
    h.http.expectOne((r) => r.url === '/api/v1/opd/emr/medicines').flush({ items: [], total: 0, page: 0, pageSize: 20 });
    expect(h.store.prescriptionRows()[0]).toMatchObject({ name: 'Calcium 500 mg + Vit D3', frequency: '0-1-0', source: 'SPECIALTY_LIST', note: '2 h apart from iron' });
    h.fixture.detectChanges();
    byText(h.el, 'button[data-act="supplement"]', 'Calcium 500 mg + Vit D3').click();
    expect(h.store.prescriptionRows()).toEqual([]);
  });

  it('marks a scan done with its finding', () => {
    const h = mountEditor(AncCheckComponent, view(), 'anc', { anc: anc() });
    byText(h.el, 'button.scan', 'Anomaly scan').click();
    h.fixture.detectChanges();
    click(h.el, '[data-testid="scan-detail"] input[type="checkbox"]');
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Finding"]')!, 'No anomaly seen');
    expect((h.store.specialtyValue('anc') as SpAncCheck).scans).toEqual([{ key: 'ANOMALY', done: true, finding: 'No anomaly seen' }]);
  });

  it('Form F is needed while a growth scan is ordered and is ticked as filled and signed', () => {
    const h = mountEditor(FormFComponent, view(), 'pcpndt', { anc: anc({}, { orders: [{ key: 'GROWTH_SCAN_FORM_F' }] }) });
    expect(h.el.querySelector('[data-testid="formf-state"]')?.textContent).toContain('Form F is needed');
    expect(h.store.stateOf('pcpndt')?.done).toBe(false);
    click(h.el, 'input[type="checkbox"]');
    expect(h.store.specialtyValue('pcpndt')).toMatchObject({ filledAndSigned: true });
    expect(h.store.stateOf('pcpndt')?.done).toBe(true);
    expect(h.el.textContent).toContain('The referral slip file and the signature image are Later.');
  });

  it('the gynae examination needs the chaperone named or declined', () => {
    const h = mountEditor(GynaeExamComponent, view(), 'gyn_exam');
    h.store.setScreenMode('GYNAE');
    typeInto(h.el.querySelectorAll<HTMLTextAreaElement>('textarea')[0], 'Soft, no mass');
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Record the chaperone’s name, or tap Declined by patient.');
    expect(h.store.stateOf('gyn_exam')?.done).toBe(false);
    click(h.el, 'button[data-act="declined"]');
    expect(h.store.specialtyValue('gyn_exam')).toMatchObject({ chaperoneDeclined: true, chaperoneName: null });
    expect(h.store.stateOf('gyn_exam')?.done).toBe(true);
  });
});

describe('Physiotherapy (SAH-63)', () => {
  const record = () => specialtyRecord('physio', physio, { mode: 'SESSION', physio: { sessionNo: 4, sessionsPlanned: 10, packageName: 'Knee package', romPercents: [] } });

  it('picks the pain score, bands the pad and keeps the first-visit sentence when only one visit exists', () => {
    const h = mountEditor(PainScoreComponent, record(), 'pain');
    h.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({ profile: 'physio', someHidden: false, physioProgress: { sessionsDone: 3, sessionsPlanned: 10, painFirst: 7, painLast: 5, romFirst: null, romLast: null, goalsAchieved: [], onlyFirstVisit: false } });
    click(h.el, 'button.pn', 4);
    h.fixture.detectChanges();
    expect(h.store.specialtyValue('pain')).toMatchObject({ score: 4 });
    expect(h.el.querySelectorAll('button.pn')).toHaveLength(11);
    expect(h.el.querySelector('[data-testid="pain-text"]')?.textContent).toBe('First visit 7. Last session: 5. Today: 4 — improving.');
    expect(h.el.querySelector('button.pn[data-band="crit"]')).not.toBeNull();
  });

  it('shows the knee range of motion with the normal range and right against normal, and a typed joint without one', () => {
    const h = mountEditor(RomTableComponent, record(), 'rom');
    expect(h.el.textContent).toContain('Normal range shown for comparison');
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Right Flexion in degrees"]')!, '105');
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Right Extension in degrees"]')!, '-5');
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('0–135°');
    const bars = Array.from(h.el.querySelectorAll('.bar-track')).map((b) => b.getAttribute('aria-label'));
    expect(bars).toEqual(['78 percent of normal', '90 percent of normal']);
    expect((h.store.specialtyValue('rom') as { rows: object[] }).rows).toEqual([
      { movement: 'flexion', rightDeg: 105, leftDeg: null },
      { movement: 'extension', rightDeg: -5, leftDeg: null },
    ]);
    typeInto(h.el.querySelector<HTMLInputElement>('input[type="text"]')!, 'shoulder');
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('No normal range is set up for this joint yet.');
  });

  it('records strength 0 to 5 and keeps a value in range', () => {
    const h = mountEditor(MmtTableComponent, record(), 'mmt');
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Right Quadriceps"]')!, '9');
    expect((h.store.specialtyValue('mmt') as { rows: { right: number }[] }).rows[0].right).toBe(5);
  });

  it('cycles a special test Not done → Positive → Negative → Not done and stores only the tests done', () => {
    const h = mountEditor(SpecialTestsComponent, record(), 'special_tests');
    byText(h.el, 'button.tile', 'Lachman').click();
    expect((h.store.specialtyValue('special_tests') as { results: object[] }).results).toEqual([{ test: 'lachman', result: 'POSITIVE' }]);
    byText(h.el, 'button.tile', 'Lachman').click();
    byText(h.el, 'button.tile', 'Lachman').click();
    expect((h.store.specialtyValue('special_tests') as { results: object[] }).results).toEqual([]);
  });

  it('stores the reference item keys: strength group and special test (jointMmt, jointSpecialTests)', () => {
    const mmt = mountEditor(MmtTableComponent, record(), 'mmt');
    typeInto(mmt.el.querySelector<HTMLInputElement>('input[aria-label="Right Hamstrings"]')!, '4');
    expect(mmt.store.specialtyValue('mmt')).toMatchObject({ rows: [{ group: 'hamstrings', right: 4, left: null }] });
    TestBed.resetTestingModule();

    const tests = mountEditor(SpecialTestsComponent, record(), 'special_tests');
    flushReference(tests.http, 'physio', {
      profile: 'physio',
      tagNote: '',
      lists: { jointSpecialTests: [{ key: 'mcmurray_medial', label: 'McMurray (medial)', group: 'knee' }, { key: 'lachman', label: 'Lachman', group: 'knee' }] },
    });
    tests.fixture.detectChanges();
    expect(Array.from(tests.el.querySelectorAll('button.tile b')).map((b) => b.textContent)).toEqual(['McMurray (medial)', 'Lachman']);
    byText(tests.el, 'button.tile', 'McMurray').click();
    expect(tests.store.specialtyValue('special_tests')).toMatchObject({ results: [{ test: 'mcmurray_medial', result: 'POSITIVE' }] });
  });

  it('records the gait, goals, treatments given today, home exercises and the session note', () => {
    const gait = mountEditor(PostureGaitComponent, record(), 'posture');
    byText(gait.el, 'button.chip', 'Antalgic').click();
    expect(gait.store.specialtyValue('posture')).toMatchObject({ gait: 'ANTALGIC' });
    TestBed.resetTestingModule();

    const goals = mountEditor(GoalsComponent, record(), 'goals');
    typeInto(goals.el.querySelector<HTMLInputElement>('input[aria-label="New goal"]')!, 'Pain below 4 by session 5', 'input');
    goals.fixture.detectChanges();
    byText(goals.el, 'button', '+ Add goal').click();
    goals.fixture.detectChanges();
    click(goals.el, 'button.chip.good');
    expect(goals.store.specialtyValue('goals')).toMatchObject({ goals: [{ text: 'Pain below 4 by session 5', achieved: true }] });
    TestBed.resetTestingModule();

    const mods = mountEditor(ModalitiesComponent, record(), 'modalities');
    byText(mods.el, 'button.tile', 'IFT').click();
    mods.fixture.detectChanges();
    expect(mods.el.querySelector('[data-testid="mod-count"]')?.textContent).toBe('1 selected');
    expect(mods.store.specialtyValue('modalities')).toMatchObject({ given: [{ key: 'IFT', text: 'IFT', params: '15 min · knee, 4-pole' }] });
    TestBed.resetTestingModule();

    const hep = mountEditor(HomeExercisesComponent, record(), 'hep');
    typeInto(hep.el.querySelector<HTMLInputElement>('input[aria-label="Exercise name"]')!, 'Quadriceps sets', 'input');
    hep.fixture.detectChanges();
    byText(hep.el, 'button', '+ Add exercise').click();
    hep.fixture.detectChanges();
    typeInto(hep.el.querySelector<HTMLInputElement>('input[placeholder="Sets"]')!, '3');
    typeInto(hep.el.querySelector<HTMLInputElement>('input[placeholder="Reps"]')!, '10');
    hep.fixture.detectChanges();
    expect(hep.el.textContent).toContain('3 × 10');
    const wa = byText(hep.el, 'button', 'Send to patient on WhatsApp') as HTMLButtonElement;
    expect(wa.disabled).toBe(true);
    TestBed.resetTestingModule();

    const note = mountEditor(SessionNoteComponent, record(), 'sessions');
    typeInto(note.el.querySelector<HTMLTextAreaElement>('textarea')!, 'Tolerated well');
    expect(note.store.specialtyValue('sessions')).toMatchObject({ note: 'Tolerated well' });
  });

  it('prints the home exercise programme and the progress report for the referring doctor, nothing is kept', () => {
    let printed = '';
    const spy = vi.spyOn(window, 'print').mockImplementation(() => {
      printed = document.querySelector('.print-root')?.textContent ?? '';
    });
    const hep = mountEditor(HomeExercisesComponent, record(), 'hep', {
      hep: { component: 'HOME_EXERCISES', schemaVersion: 1, items: [{ id: 'e1', name: 'Quadriceps sets', sets: 3, reps: 10 }] },
    });
    hep.store.paperContext.set(casePaperContext());
    click(hep.el, 'button[data-act="print-programme"]');
    expect(printed).toContain('Test Clinic');
    expect(printed).toContain('Home exercise programme');
    expect(printed).toContain('1. Quadriceps sets · 3 × 10');
    expect(document.querySelector('.print-root')).toBeNull();
    TestBed.resetTestingModule();

    const note = mountEditor(SessionNoteComponent, record(), 'sessions');
    note.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({
      profile: 'physio',
      someHidden: false,
      physioProgress: { sessionsDone: 3, sessionsPlanned: 10, painFirst: 7, painLast: 4, romFirst: { flexion: 95 }, romLast: { flexion: 110 }, goalsAchieved: ['Pain below 4 by session 5'], onlyFirstVisit: false },
    });
    note.store.paperContext.set(casePaperContext());
    note.store.addListEntries('DIAGNOSIS', [{ itemKey: null, text: 'Right knee osteoarthritis', newToList: true }]);
    click(note.el, 'button[data-act="progress-report"]');
    note.fixture.detectChanges();
    expect(printed).toContain('Right knee osteoarthritis');
    expect(printed).toContain('3 done of 10 planned');
    expect(printed).toContain('First visit 7 · latest 4');
    expect(printed).toContain('Latest: flexion: 110');
    expect(printed).toContain('Pain below 4 by session 5');
    expect(note.el.querySelector('[data-testid="report-message"]')?.textContent).toBe('Progress report (sessions 1–3) ready to share with the referring doctor');
    spy.mockRestore();
  });

  it('the report says only the first visit is recorded when nothing earlier exists', () => {
    let printed = '';
    const spy = vi.spyOn(window, 'print').mockImplementation(() => {
      printed = document.querySelector('.print-root')?.textContent ?? '';
    });
    const note = mountEditor(SessionNoteComponent, record(), 'sessions');
    note.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({ profile: 'physio', someHidden: false, physioProgress: { sessionsDone: 1, sessionsPlanned: 10, painFirst: 7, painLast: 7, romFirst: null, romLast: null, goalsAchieved: [], onlyFirstVisit: true } });
    click(note.el, 'button[data-act="progress-report"]');
    expect(printed).toContain('Only the first visit is recorded.');
    spy.mockRestore();
  });

  it('the strip shows the session and the package, and the mode switch changes the sections shown', () => {
    TestBed.resetTestingModule();
    const h = mountEditor(SpecialtyStripComponent, record(), '');
    expect(h.el.textContent).toContain('Session 4 of 10');
    expect(h.el.textContent).toContain('Knee package');
    expect(h.store.sectionsOf('ASSESSMENT').map((s) => s.key)).not.toContain('special_tests');
    byText(h.el, 'button', 'First visit: assessment').click();
    expect(h.store.visitMode()).toBe('FIRST_VISIT');
    expect(h.store.sectionsOf('ASSESSMENT').map((s) => s.key)).toContain('special_tests');
    expect(h.store.sectionsOf('TREATMENT').map((s) => s.key)).not.toContain('modalities');
  });
});


describe('Physiotherapy outcome scales (contract 0.5.1)', () => {
  const view = (outcomeScales: object[] = []) =>
    specialtyRecord('physio', physio, { mode: 'FIRST_VISIT', physio: { sessionNo: 1, sessionsPlanned: 10, packageName: 'Knee package', romPercents: [], outcomeScales } as never });

  it('adds a scale, stores a whole score within its range, and refuses a score out of range', () => {
    const h = mountEditor(OutcomeScalesComponent, view(), 'func_scales');
    byText(h.el, 'button', '+ Add scale').click();
    h.fixture.detectChanges();
    expect(h.store.specialtyValue('func_scales')).toMatchObject({ component: 'OUTCOME_SCALES', rows: [{ scale: 'ODI', score: null }] });
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="ODI score, 0 to 100"]')!, '101');
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="scale-error"]')?.textContent).toContain('ODI score must be a whole number from 0 to 100.');
    expect((h.store.specialtyValue('func_scales') as { rows: { score: number | null }[] }).rows[0].score).toBeNull();
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="ODI score, 0 to 100"]')!, '34');
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="scale-error"]')).toBeNull();
    expect((h.store.specialtyValue('func_scales') as { rows: { score: number }[] }).rows[0].score).toBe(34);
    expect(h.store.stateOf('func_scales')?.done).toBe(true);
  });

  it('uses the limits ODI 100, NDI 50, WOMAC 96 and LEFS 80, and refuses the same scale twice', () => {
    const h = mountEditor(OutcomeScalesComponent, view(), 'func_scales');
    const select = h.el.querySelector<HTMLSelectElement>('select[aria-label="Scale to add"]')!;
    for (const scale of ['NDI', 'WOMAC', 'LEFS']) {
      select.value = scale;
      select.dispatchEvent(new Event('change', { bubbles: true }));
      byText(h.el, 'button', '+ Add scale').click();
      h.fixture.detectChanges();
    }
    expect(h.el.querySelector('input[aria-label="NDI score, 0 to 50"]')).not.toBeNull();
    expect(h.el.querySelector('input[aria-label="WOMAC score, 0 to 96"]')).not.toBeNull();
    expect(h.el.querySelector('input[aria-label="LEFS score, 0 to 80"]')).not.toBeNull();
    byText(h.el, 'button', '+ Add scale').click();
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="scale-error"]')?.textContent).toContain('LEFS is already on the list.');
    expect((h.store.specialtyValue('func_scales') as { rows: unknown[] }).rows).toHaveLength(3);
  });

  it('shows the earlier score and the chip "WOMAC 48 → 41" in both cards, and "WOMAC 41" with no earlier score', () => {
    const earlier = [{ scale: 'WOMAC', previousScore: 48, previousDate: '2026-10-28', todayScore: 41 }];
    const scales = mountEditor(OutcomeScalesComponent, view(earlier), 'func_scales', { func_scales: { component: 'OUTCOME_SCALES', schemaVersion: 1, rows: [{ scale: 'WOMAC', score: 41 }] } });
    expect(scales.el.querySelector('[data-testid="scale-chips"]')?.textContent?.trim()).toBe('WOMAC 48 → 41');
    expect(scales.el.textContent).toContain('48 on 28 Oct 2026');
    TestBed.resetTestingModule();

    const rom = mountEditor(RomTableComponent, view(earlier), 'rom');
    expect(rom.el.querySelector('[data-testid="scale-chips"]')?.textContent?.trim()).toBe('WOMAC 48 → 41');
    TestBed.resetTestingModule();

    const first = mountEditor(RomTableComponent, view([{ scale: 'WOMAC', previousScore: null, previousDate: null, todayScore: 41 }]), 'rom');
    expect(first.el.querySelector('[data-testid="scale-chips"]')?.textContent?.trim()).toBe('WOMAC 41');
  });

  it('is plugged in through the registry (OUTCOME_SCALES)', async () => {
    const { editorLoader } = await import('./specialty-registry');
    const type = await editorLoader('OUTCOME_SCALES')!();
    expect(type).toBe(OutcomeScalesComponent);
  });
});
