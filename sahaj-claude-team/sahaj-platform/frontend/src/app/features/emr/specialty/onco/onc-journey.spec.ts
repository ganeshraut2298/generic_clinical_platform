import { Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { specialtyRecord } from '../specialty.fixtures';
import { mountEditor } from '../specialty.testing';
import { EmrOncoView, OncJourneyStrip } from '../specialty.model';
import { OncJourneyStripComponent } from './onc-journey-strip.component';

const anchors = [{ key: 'onc_tox', component: 'ONC_TOX' as const, part: 'ASSESSMENT' as const }];

const strip = (over: Partial<OncJourneyStrip> = {}): OncJourneyStrip => ({
  record: {
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
  },
  diagnosisText: 'Ca left breast · IDC G2',
  plan: {
    courseId: 'c9',
    title: 'AC ×4 → Paclitaxel ×4',
    cycleNo: 4,
    cycleCount: 8,
    cycles: [
      { sittingId: 'a', label: 'AC1', state: 'GIVEN' },
      { sittingId: 'b', label: 'AC2', state: 'GIVEN' },
      { sittingId: 'c', label: 'AC3', state: 'GIVEN' },
      { sittingId: 'd', label: 'AC4', state: 'TODAY' },
      { sittingId: 'e', label: 'P1', state: 'PLANNED' },
    ],
  },
  ...over,
});

const onco = (journey: OncJourneyStrip | undefined, over: Partial<EmrOncoView> = {}): Partial<EmrOncoView> => ({
  response: 'PARTIAL_RESPONSE',
  sums: { baseline: 10, previous: 8, today: 6, nadir: 6, pctFromBaseline: -40, pctFromNadir: 0 },
  journey,
  watch: { doxorubicinGivenMgM2: 180, doxorubicinLimitMgM2: 450 },
  ...over,
});

const mount = (journey: OncJourneyStrip | undefined, over: Partial<EmrOncoView> = {}, extra: Provider[] = []) =>
  mountEditor(OncJourneyStripComponent, specialtyRecord('onco', anchors, { onco: onco(journey, over) as EmrOncoView }), '', {}, extra);

describe('Cancer journey strip (SAH-75-7)', () => {
  it('shows the five cells with their labels and values as drawn', () => {
    const h = mount(strip());
    const cells = Array.from(h.el.querySelectorAll('.cell'));
    expect(cells).toHaveLength(5);
    expect(h.el.querySelector('[aria-label="Cancer journey snapshot"]')).not.toBeNull();
    expect(cells[0].textContent).toContain('Diagnosis');
    expect(cells[0].textContent).toContain('Ca left breast · IDC G2');
    expect(cells[0].textContent).toContain('Stage IIIA · cT2 cN2 M0');
    expect(cells[1].textContent).toContain('Ki-67 30% · BRCA pending');
    expect(cells[2].textContent).toContain('Curative · neoadjuvant');
    expect(cells[2].textContent).toContain('1st line · board 26 Jul');
    expect(cells[4].textContent).toContain('PR · -40%');
    expect(cells[4].textContent).toContain('Partial response since baseline');
  });

  it('shows the plan, the cycle line and one chip per cycle with the hover words', () => {
    const h = mount(strip());
    expect(h.el.querySelector('.plan')!.textContent).toContain('Plan · AC ×4 → Paclitaxel ×4');
    expect(h.el.querySelector('[data-testid="cycle-text"]')!.textContent).toBe('Cycle 4 of 8 · day 1');
    const chips = Array.from(h.el.querySelectorAll('.cy'));
    expect(chips.map((c) => `${c.textContent}:${c.getAttribute('data-state')}:${c.getAttribute('title')}`)).toEqual([
      'AC1:GIVEN:Given',
      'AC2:GIVEN:Given',
      'AC3:GIVEN:Given',
      'AC4:TODAY:Today',
      'P1:PLANNED:Planned',
    ]);
  });

  it('turns today\'s chip amber with "Delayed 1 week" and the line "Cycle n delayed" when the decision is Delay', () => {
    const base = strip();
    const h = mount({ ...base, plan: { ...base.plan!, cycles: base.plan!.cycles.map((c) => (c.state === 'TODAY' ? { ...c, state: 'DELAYED' as const } : c)) } });
    expect(h.el.querySelector('[data-testid="cycle-text"]')!.textContent).toBe('Cycle 4 delayed');
    expect(h.el.querySelector('.cy[data-state="DELAYED"]')!.getAttribute('title')).toBe('Delayed 1 week');
  });

  it('reads "Not recorded" and "No chemotherapy plan" for a patient with no journey and no course', () => {
    const h = mount({ record: null, diagnosisText: null, plan: null }, { response: null });
    const text = h.el.textContent!;
    expect(text).toContain('Not recorded');
    expect(h.el.querySelector('[data-testid="no-plan"]')!.textContent).toBe('No chemotherapy plan');
    expect(h.el.querySelector('[data-testid="response-cell"]')!.textContent).toBe('Not measured');
  });

  it('shows the doxorubicin watch chip, and hides it when there is no earlier dose; there is no scheme chip', () => {
    const h = mount(strip());
    expect(h.el.querySelector('[data-testid="dox-chip"]')!.textContent).toBe('Doxorubicin 180 / 450 mg/m²');
    expect(h.el.textContent).not.toContain('MJPJAY');
    TestBed.resetTestingModule();
    const none = mount(strip(), { watch: { doxorubicinGivenMgM2: null, doxorubicinLimitMgM2: 450 } });
    expect(none.el.querySelector('[data-testid="dox-chip"]')).toBeNull();
  });

  it('shows a grey "No known drug allergy" watch chip only for NONE_RECORDED, beside or without the doxorubicin chip', () => {
    const base = specialtyRecord('onco', anchors, { onco: onco(strip()) as EmrOncoView });
    const withStatus = (status: 'RECORDED' | 'NONE_RECORDED' | 'NOT_RECORDED'): typeof base => ({ ...base, header: { ...base.header, allergyStatus: status } });
    const noneRecorded = mountEditor(OncJourneyStripComponent, withStatus('NONE_RECORDED'), '', {});
    expect(noneRecorded.el.querySelector('[data-testid="no-allergy-chip"]')!.textContent).toBe('No known drug allergy');
    expect(noneRecorded.el.querySelector('[data-testid="dox-chip"]')).not.toBeNull();
    TestBed.resetTestingModule();
    const recorded = mountEditor(OncJourneyStripComponent, withStatus('RECORDED'), '', {});
    expect(recorded.el.querySelector('[data-testid="no-allergy-chip"]')).toBeNull();
    TestBed.resetTestingModule();
    const alone = mountEditor(
      OncJourneyStripComponent,
      { ...withStatus('NONE_RECORDED'), specialty: { ...base.specialty!, onco: onco(strip(), { watch: { doxorubicinGivenMgM2: null, doxorubicinLimitMgM2: 450 } }) as EmrOncoView } },
      '',
      {},
    );
    expect(alone.el.querySelector('[data-testid="no-allergy-chip"]')).not.toBeNull();
    expect(alone.el.querySelector('[data-testid="dox-chip"]')).toBeNull();
  });

  it('draws nothing while the server has sent no strip (never a half-filled one)', () => {
    const h = mount(undefined);
    expect(h.el.querySelector('[data-testid="journey-strip"]')).toBeNull();
  });

  it('opens the edit form with the version read, and reloads the record after a save', () => {
    const open = vi.fn(() => ({ afterClosed: () => of(true) }));
    const h = mount(strip(), {}, [{ provide: MatDialog, useValue: { open } }]);
    const refresh = vi.spyOn(h.store, 'refreshRecord');
    h.el.querySelector<HTMLButtonElement>('[data-act="edit-journey"]')!.click();
    expect((open.mock.calls as unknown[][])[0][1]).toMatchObject({ data: { recordId: 'r1', current: { version: 2 } } });
    expect(refresh).toHaveBeenCalledWith(true);
    TestBed.resetTestingModule();
  });
});
