import { TestBed } from '@angular/core/testing';
import { casePaperContext } from '../../emr.fixtures';
import { specialtyRecord } from '../specialty.fixtures';
import { mountEditor, byText, click, typeInto, EMR_RECORDS } from '../specialty.testing';
import { ConsentChecklistComponent } from '../shared/consent-checklist.component';
import { emptyChart } from '../dental.logic';
import { DentalDoneComponent, DentalPlanComponent, LabWorkComponent } from './dental-plan.component';
import { ToothChartComponent } from './tooth-chart.component';
import { SpDentalPlan, SpToothChart } from '../specialty.model';

const anchors = [
  { key: 'dental_chart', component: 'TOOTH_CHART' as const, required: true, part: 'TREATMENT' as const },
  { key: 'tx_plan', component: 'DENTAL_PLAN' as const, part: 'TREATMENT' as const },
  { key: 'dental_done', component: 'DENTAL_DONE' as const, part: 'TREATMENT' as const },
  { key: 'dental_lab', component: 'LAB_WORK' as const, part: 'TREATMENT' as const },
  { key: 'consent', component: 'CONSENT_CHECKLIST' as const, part: 'TREATMENT' as const },
];
const view = (dental = {}) => specialtyRecord('dental', anchors, { dental: { estimateTotalPaise: 0, estimateState: 'NOT_SIGNED', doneTodayRowIds: [], childTabDefault: false, ...dental } });
const emptyPlan: SpDentalPlan = { component: 'DENTAL_PLAN', schemaVersion: 1, rows: [] };
const sceneOf = (h: { fixture: { componentInstance: unknown } }): string => (h.fixture.componentInstance as { sceneTransform(): string }).sceneTransform();

describe('Tooth chart (SAH-49-1)', () => {
  it('shows the adult teeth in two jaws, 16 each, with the drawn hint', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT'), tx_plan: emptyPlan });
    expect(h.el.querySelectorAll('.arch')[0].querySelectorAll('button.tooth')).toHaveLength(16);
    expect(h.el.querySelectorAll('.arch')[1].querySelectorAll('button.tooth')).toHaveLength(16);
    expect(h.el.querySelector('[data-testid="chart-hint"]')?.textContent).toBe('Tap a tooth, pick its condition on the right, then add treatment to the plan.');
    expect(h.el.textContent).toContain('Upper right');
    expect(h.el.textContent).toContain('Lower left');
  });

  it('marks the selected tooth and stores only teeth that differ from healthy', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT') });
    click(h.el, 'button.tooth[data-tooth="46"]');
    byText(h.el, 'button.cond', 'Missing').click();
    expect((h.store.specialtyValue('dental_chart') as SpToothChart).teeth).toEqual([{ tooth: 46, condition: 'MISSING', surfaces: [], mobility: 0 }]);
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Tooth 46 marked Missing');
    byText(h.el, 'button', 'Undo').click();
    expect((h.store.specialtyValue('dental_chart') as SpToothChart).teeth).toEqual([]);
  });

  it('allows surfaces only for caries, filled and crown', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT') });
    click(h.el, 'button.tooth[data-tooth="47"]');
    const surface = h.el.querySelector<HTMLButtonElement>('button.sbtn[aria-label="Occlusal / incisal"]')!;
    expect(surface.disabled).toBe(true);
    byText(h.el, 'button.cond', 'Caries').click();
    h.fixture.detectChanges();
    h.el.querySelector<HTMLButtonElement>('button.sbtn[aria-label="Occlusal / incisal"]')!.click();
    h.el.querySelector<HTMLButtonElement>('button.sbtn[aria-label="Mesial"]')!.click();
    expect((h.store.specialtyValue('dental_chart') as SpToothChart).teeth[0]).toEqual({ tooth: 47, condition: 'CARIES', surfaces: ['M', 'O'], mobility: 0 });
  });

  it('adds a suggested treatment to the plan with its surfaces, phase and three sittings for a root canal, and refuses a duplicate', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT'), tx_plan: emptyPlan });
    click(h.el, 'button.tooth[data-tooth="36"]');
    byText(h.el, 'button.cond', 'Caries').click();
    h.fixture.detectChanges();
    h.el.querySelector<HTMLButtonElement>('button.sbtn[aria-label="Occlusal / incisal"]')!.click();
    h.fixture.detectChanges();
    byText(h.el, 'button[data-act="add-plan"]', 'Root canal treatment').click();
    const plan = h.store.specialtyValue('tx_plan') as SpDentalPlan;
    expect(plan.rows).toHaveLength(1);
    expect(plan.rows[0]).toMatchObject({ tooth: 36, procedureKey: 'ROOT_CANAL', procedureText: 'Root canal treatment (O)', phase: 1, status: 'PLANNED', sittingsPlanned: 3, currentSitting: 1 });
    byText(h.el, 'button[data-act="add-plan"]', 'Root canal treatment').click();
    expect((h.store.specialtyValue('tx_plan') as SpDentalPlan).rows).toHaveLength(1);
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('is already on the plan');
  });

  it('says "Healthy tooth: nothing to plan." and opens on the child tab for a patient under 13', () => {
    const h = mountEditor(ToothChartComponent, view({ childTabDefault: true }), 'dental_chart');
    expect(h.el.querySelectorAll('.arch')[0].querySelectorAll('button.tooth')).toHaveLength(10);
    expect(h.el.textContent).toContain('Healthy tooth: nothing to plan.');
  });

  it('draws the jaw in 3D with CSS transforms: 32 teeth of six faces, with the drawn viewpoints and sliders', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT') });
    click(h.el, 'button[data-view="3d"]');
    h.fixture.detectChanges();
    expect(h.el.querySelectorAll('button.t3')).toHaveLength(32);
    expect(h.el.querySelectorAll('button.t3')[0].querySelectorAll('.face')).toHaveLength(6);
    expect(sceneOf(h)).toContain('rotateY(-22deg)');
    expect(h.el.textContent).toContain('Upper jaw (biting surface)');
    expect(h.el.textContent).toContain('Turn left / right');
    byText(h.el, 'button', 'Front').click();
    h.fixture.detectChanges();
    expect(sceneOf(h)).toContain('rotateX(-8deg) rotateY(0deg)');
    byText(h.el, 'button', 'Upper jaw (biting surface)').click();
    h.fixture.detectChanges();
    const lower = h.el.querySelectorAll<HTMLElement>('button.t3')[16];
    expect(lower.style.display).toBe('none');
  });

  it('selects a tooth from the 3D view and starts turning with Auto-turn', () => {
    vi.useFakeTimers();
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT') });
    click(h.el, 'button[data-view="3d"]');
    h.fixture.detectChanges();
    h.el.querySelectorAll<HTMLElement>('button.t3')[0].click();
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Tooth 18');
    click(h.el, 'button[data-act="spin"]');
    vi.advanceTimersByTime(130);
    h.fixture.detectChanges();
    expect(sceneOf(h)).toContain('rotateY(-18deg)');
    click(h.el, 'button[data-act="spin"]');
    vi.useRealTimers();
  });

  it('marks surfaces and mobility in the detail view and steps to the next tooth', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT') });
    click(h.el, 'button[data-view="detail"]');
    h.fixture.detectChanges();
    h.fixture.detectChanges();
    h.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({ profile: 'dental', someHidden: false, dentalToothHistory: [] });
    h.fixture.detectChanges();
    expect(h.el.querySelectorAll('.detail > .dcol')).toHaveLength(3);
    expect(h.el.textContent).toContain('Surfaces of 36 · tap to mark');
    expect(h.el.textContent).toContain('Side view · crown and roots');
    expect(h.el.querySelector('[data-testid="tooth-history-empty"]')?.textContent).toBe('No earlier work on this tooth.');
    expect(h.el.querySelector('[data-testid="surf-text"]')?.textContent).toContain('No surface marked');
    byText(h.el, 'button', 'G2').click();
    expect((h.store.specialtyValue('dental_chart') as SpToothChart).teeth[0]).toMatchObject({ tooth: 36, mobility: 2 });
    click(h.el, 'button[data-act="next-tooth"]');
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Surfaces of 37');
  });

  it('lists the earlier work on the selected tooth from the signed visits', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT') });
    click(h.el, 'button[data-view="detail"]');
    h.fixture.detectChanges();
    h.http.expectOne(`${EMR_RECORDS}/r1/specialty-history`).flush({
      profile: 'dental',
      someHidden: false,
      dentalToothHistory: [
        { tooth: '36', date: '2026-09-02', text: 'RCT · sitting 1 of 3' },
        { tooth: '46', date: '2026-08-01', text: 'Scaling' },
      ],
    });
    h.fixture.detectChanges();
    const rows = Array.from(h.el.querySelectorAll('[data-testid="tooth-history"]')).map((r) => r.textContent?.replace(/\s+/g, ' ').trim());
    expect(rows).toHaveLength(1);
    expect(rows[0]).toContain('RCT · sitting 1 of 3');
    expect(rows[0]).toContain('Sep 2026');
    expect(h.el.querySelector('[data-testid="tooth-history-empty"]')).toBeNull();
  });

  it('gives the open-mouth slider the drawn range of 6 to 160', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT') });
    click(h.el, 'button[data-view="3d"]');
    h.fixture.detectChanges();
    const ranges = Array.from(h.el.querySelectorAll<HTMLInputElement>('input[type="range"]')).map((i) => [i.min, i.max]);
    expect(ranges).toContainEqual(['6', '160']);
  });

  it('keeps "All teeth healthy" as a confirmation', () => {
    const h = mountEditor(ToothChartComponent, view(), 'dental_chart', { dental_chart: emptyChart('ADULT') });
    click(h.el, 'button[data-act="all-healthy"]');
    expect(h.store.specialtyValue('dental_chart')).toMatchObject({ allHealthyConfirmed: true, teeth: [] });
    expect(h.store.stateOf('dental_chart')?.done).toBe(true);
  });
});

describe('Treatment plan and estimate (SAH-49-2, SAH-69-1)', () => {
  const plan = (over: Partial<SpDentalPlan> = {}): SpDentalPlan => ({
    ...emptyPlan,
    rows: [{ id: 'a', tooth: 36, procedureKey: 'ROOT_CANAL', procedureText: 'Root canal treatment', phase: 1, status: 'PLANNED', sittingsPlanned: 3, currentSitting: 1, estimatePaise: 450000, estimateSource: 'TARIFF' }],
    ...over,
  });

  it('shows the rows with the tariff estimate, "Not in tariff" with a typed amount, and the total', () => {
    const typed = plan();
    typed.rows.push({ id: 'b', tooth: 16, procedureKey: null, procedureText: 'Scaling', phase: 1, status: 'PLANNED', sittingsPlanned: 1, currentSitting: 1, typedEstimatePaise: 80000, estimateSource: 'TYPED', estimatePaise: 80000 });
    typed.rows.push({ id: 'c', tooth: 26, procedureKey: null, procedureText: 'Polish', phase: 1, status: 'PLANNED', sittingsPlanned: 1, currentSitting: 1 });
    const h = mountEditor(DentalPlanComponent, view(), 'tx_plan', { tx_plan: typed });
    const text = h.el.textContent ?? '';
    expect(text).toContain('Root canal treatment');
    expect(text).toContain('sitting 1 of 3');
    expect(text).toContain('Not in tariff');
    expect(h.el.querySelector('[data-testid="plan-summary"]')?.textContent).toBe('3 items · 0 done · 0 in progress · 3 planned');
    expect(h.el.querySelector('[data-testid="plan-total"]')?.textContent).toContain('₹');
  });

  it('moves a status forward on tap and counts it done on this visit', () => {
    const h = mountEditor(DentalPlanComponent, view(), 'tx_plan', { tx_plan: plan() });
    click(h.el, 'button[data-act="status"]');
    expect((h.store.specialtyValue('tx_plan') as SpDentalPlan).rows[0].status).toBe('IN_PROGRESS');
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="status"]');
    expect((h.store.specialtyValue('tx_plan') as SpDentalPlan).rows[0].status).toBe('DONE');
  });

  it('adds a typed procedure, refuses a duplicate and a bad tooth number', () => {
    const h = mountEditor(DentalPlanComponent, view(), 'tx_plan', { tx_plan: plan() });
    const [tooth, text] = Array.from(h.el.querySelectorAll<HTMLInputElement>('.add input'));
    typeInto(tooth, '99', 'input');
    typeInto(text, 'Scaling', 'input');
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="add-typed"]');
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('Enter a tooth number from 11 to 48');
    typeInto(tooth, '36', 'input');
    typeInto(text, 'root canal TREATMENT', 'input');
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="add-typed"]');
    h.fixture.detectChanges();
    expect(h.el.textContent).toContain('already on the plan for this tooth');
    typeInto(tooth, '16', 'input');
    typeInto(text, 'Scaling', 'input');
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="add-typed"]');
    expect((h.store.specialtyValue('tx_plan') as SpDentalPlan).rows.map((r) => r.procedureText)).toEqual(['Root canal treatment', 'Scaling']);
  });

  it('signs the estimate with a tick, and Print is disabled with "Add a treatment first" when there is no row', () => {
    const empty = mountEditor(DentalPlanComponent, view(), 'tx_plan', { tx_plan: emptyPlan });
    expect((empty.el.querySelector('button[data-act="print-estimate"]') as HTMLButtonElement).disabled).toBe(true);
    expect(empty.el.textContent).toContain('Add a treatment first');
    expect(empty.el.textContent).toContain('Not signed');
    TestBed.resetTestingModule();
    const h = mountEditor(DentalPlanComponent, view(), 'tx_plan', { tx_plan: plan() });
    click(h.el, 'input[data-act="sign-estimate"]');
    expect(h.store.specialtyValue('tx_plan')).toMatchObject({ signEstimate: true });
  });

  it('shows "Estimate changed. Sign again" when the server says the plan changed after signing', () => {
    const changed = view({ estimateState: 'CHANGED' });
    const h = mountEditor(DentalPlanComponent, changed, 'tx_plan');
    expect(h.el.querySelector('[data-testid="estimate-state"]')?.textContent?.trim()).toBe('Estimate changed. Sign again');
  });

  it('starts the treatment course from the plan with an Idempotency-Key and links to it', () => {
    const h = mountEditor(DentalPlanComponent, view(), 'tx_plan', { tx_plan: plan() });
    h.store.save().subscribe();
    h.http.expectOne('/api/v1/opd/emr/records/r1/draft').flush({ ...view(), revision: 4 });
    h.fixture.detectChanges();
    click(h.el, 'button[data-act="start-course"]');
    const req = h.http.expectOne('/api/v1/opd/emr/courses');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ fromRecordId: 'r1', kind: 'DENTAL', fromPlan: true });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
    req.flush({ id: 'c1' });
    h.fixture.detectChanges();
    // The course is shown by the embedded panel (SAH-75-3): it asks for this patient's dental courses.
    h.http.expectOne((r) => r.url === '/api/v1/opd/emr/courses' && r.params.get('kind') === 'DENTAL').flush([]);
    expect(h.el.querySelector('app-course-panel')).not.toBeNull();
  });

  it('prints the estimate with the clinic header, the rows, the total and the analyst line, then says so', () => {
    let printed = '';
    const spy = vi.spyOn(window, 'print').mockImplementation(() => {
      printed = document.querySelector('.print-root')?.textContent ?? '';
    });
    const h = mountEditor(DentalPlanComponent, view(), 'tx_plan', { tx_plan: plan() });
    h.store.paperContext.set(casePaperContext());
    click(h.el, 'button[data-act="print-estimate"]');
    expect(printed).toContain('Test Clinic');
    expect(printed).toContain('Treatment estimate');
    expect(printed).toContain('36 · Root canal treatment · Phase 1 · ₹4,500');
    expect(printed).toContain('Estimate only. Final charges are as billed.');
    h.fixture.detectChanges();
    expect(h.el.querySelector('[data-testid="print-message"]')?.textContent).toBe('Estimate sent to printer');
    spy.mockRestore();
  });

  it('keeps the WhatsApp copy as Later', () => {
    const h = mountEditor(DentalPlanComponent, view(), 'tx_plan', { tx_plan: plan() });
    const wa = byText(h.el, 'button', 'Send on WhatsApp') as HTMLButtonElement;
    expect(wa.disabled).toBe(true);
    expect(wa.textContent).toContain('Later');
  });
});

describe('Dental done today, lab work and consent (SAH-69)', () => {
  it('lists the rows done on this visit and the count to be billed', () => {
    const done: SpDentalPlan = {
      ...emptyPlan,
      rows: [{ id: 'a', tooth: 36, procedureKey: 'ROOT_CANAL', procedureText: 'Root canal treatment', phase: 1, status: 'DONE', sittingsPlanned: 3, currentSitting: 2 }],
    };
    const h = mountEditor(DentalDoneComponent, view(), 'dental_done', { tx_plan: done });
    expect(h.el.querySelectorAll('[data-testid="done-row"]')).toHaveLength(1);
    expect(h.el.querySelector('[data-testid="done-count"]')?.textContent).toBe('1 procedure(s) done today will be billed.');
  });

  it('adds lab work typed by the doctor and refuses a due date before today', () => {
    const h = mountEditor(LabWorkComponent, view(), 'dental_lab', { dental_lab: { component: 'LAB_WORK', schemaVersion: 1, items: [] } });
    click(h.el, 'button[data-kind="CROWN"]');
    h.fixture.detectChanges();
    typeInto(h.el.querySelector<HTMLInputElement>('input[aria-label="Lab name for Crown"]')!, 'City dental lab');
    typeInto(h.el.querySelector<HTMLInputElement>('input[type="date"]')!, '2020-01-01');
    h.fixture.detectChanges();
    expect(h.store.specialtyValue('dental_lab')).toMatchObject({ items: [{ item: 'CROWN', labName: 'City dental lab', dueDate: '2020-01-01' }] });
    expect(h.el.textContent).toContain('Choose a date from today.');
    expect(h.el.textContent).toContain('Nothing is ordered from here.');
  });

  it('adds a consent item per plan row and is Done only when every item is ticked', () => {
    const plan: SpDentalPlan = { ...emptyPlan, rows: [{ id: 'row-1', tooth: 36, procedureKey: null, procedureText: 'Root canal treatment', phase: 1, status: 'PLANNED', sittingsPlanned: 1, currentSitting: 1 }] };
    const h = mountEditor(ConsentChecklistComponent, view(), 'consent', { tx_plan: plan });
    click(h.el, 'button[data-act="add-consent"]');
    h.fixture.detectChanges();
    expect(h.store.specialtyValue('consent')).toMatchObject({ items: [{ key: 'row-1', confirmed: false }] });
    expect(h.store.stateOf('consent')?.done).toBe(false);
    click(h.el, 'input[type="checkbox"]');
    expect(h.store.stateOf('consent')?.done).toBe(true);
  });
});
