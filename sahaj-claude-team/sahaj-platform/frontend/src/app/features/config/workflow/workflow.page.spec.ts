import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { DEFAULT_WORKFLOW_STEPS, WorkflowStep, WorkflowVersion } from '../../workflow/workflow.model';
import { WorkflowPage } from './workflow.page';

const BASE = '/api/v1/opd/workflow';

const LIVE: WorkflowVersion = {
  versionNo: 3,
  steps: [...DEFAULT_WORKFLOW_STEPS],
  publishedAt: '2026-10-01T05:00:00.000Z',
};

describe('WorkflowPage', () => {
  let fixture: ComponentFixture<WorkflowPage>;
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(role: Role, draft: readonly WorkflowStep[] = DEFAULT_WORKFLOW_STEPS) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(WorkflowPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/draft`).flush({ steps: draft });
    http.expectOne(`${BASE}/current`).flush(LIVE);
    fixture.detectChanges();
    await fixture.whenStable();
    el = fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  const row = (label: string) =>
    Array.from(el.querySelectorAll('.srow')).find((r) => r.querySelector('b')!.textContent!.trim() === label)!;
  const sw = (label: string) => row(label).querySelector<HTMLButtonElement>('button.sw')!;
  const button = (text: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.includes(text))!;
  const flow = () => Array.from(el.querySelectorAll('.flow .fs')).map((s) => s.firstChild!.textContent!.trim());

  it('shows the 8 steps in order with the live version badge', async () => {
    await open('OWNER');
    const labels = Array.from(el.querySelectorAll('.srow b')).map((b) => b.textContent!.trim());
    expect(labels).toEqual([
      'Token',
      'Queue',
      'Vitals',
      'Consultation',
      'Counselling',
      'Case paper print',
      'Billing',
      'Follow-up set',
    ]);
    expect(el.querySelector('.panel-h .pill')!.textContent).toContain('Live · v3');
    expect(flow()).toEqual(['Token', 'Queue', 'Vitals', 'Consultation', 'Case paper print', 'Billing', 'Follow-up set']);
  });

  it('locks Token, Queue, Consultation and Billing with no way to switch or reassign them', async () => {
    await open('OWNER');
    for (const label of ['Token', 'Queue', 'Consultation', 'Billing']) {
      expect(sw(label).disabled).toBe(true);
    }
    for (const label of ['Token', 'Consultation', 'Billing']) {
      expect(row(label).querySelector('mat-select')!.classList).toContain('mat-mdc-select-disabled');
    }
    expect(sw('Vitals').disabled).toBe(false);
    expect(sw('Counselling').disabled).toBe(false);
    sw('Billing').click();
    http.expectNone(`${BASE}/draft`);
  });

  it('saves a draft when Vitals is switched off, shows the draft badge and drops it from the flow', async () => {
    await open('OWNER');
    sw('Vitals').click();
    const req = http.expectOne(`${BASE}/draft`);
    expect(req.request.method).toBe('PUT');
    const sent = req.request.body.steps as WorkflowStep[];
    expect(sent.find((s) => s.key === 'vitals')!.on).toBe(false);
    expect(sent).toHaveLength(8);
    req.flush({ steps: sent });
    fixture.detectChanges();
    expect(el.querySelector('.panel-h .pill')!.textContent).toContain('Draft · not published');
    expect(flow()).not.toContain('Vitals');
  });

  it('offers only the four real roles in the owner dropdown', async () => {
    await open('OWNER');
    row('Vitals').querySelector<HTMLElement>('.mat-mdc-select-trigger')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const options = Array.from(document.querySelectorAll('mat-option')).map((o) => o.textContent!.trim());
    expect(options).toEqual(['Front desk', 'Nurse', 'Doctor', 'Owner']);
    expect(options).not.toContain('Counsellor');
  });

  it('saves the new owner when Counselling is reassigned', async () => {
    await open('OWNER');
    const page = fixture.componentInstance as unknown as { setOwner(k: string, o: string): void };
    page.setOwner('counsel', 'NURSE');
    const req = http.expectOne(`${BASE}/draft`);
    expect((req.request.body.steps as WorkflowStep[]).find((s) => s.key === 'counsel')!.owner).toBe('NURSE');
    req.flush({ steps: req.request.body.steps });
  });

  it('publishes the draft as the next version and clears the draft badge', async () => {
    await open('OWNER');
    sw('Counselling').click();
    const save = http.expectOne(`${BASE}/draft`);
    save.flush({ steps: save.request.body.steps });
    fixture.detectChanges();
    expect(button('Publish v4').disabled).toBe(false);

    button('Publish v4').click();
    const pub = http.expectOne(`${BASE}/publish`);
    expect(pub.request.method).toBe('POST');
    pub.flush({ ...LIVE, versionNo: 4, steps: save.request.body.steps });
    fixture.detectChanges();
    expect(el.querySelector('.panel-h .pill')!.textContent).toContain('Live · v4');
    expect(flow()).toContain('Counselling');
  });

  it('discards the draft by saving the live steps back', async () => {
    const draft = DEFAULT_WORKFLOW_STEPS.map((s) => (s.key === 'vitals' ? { ...s, on: false } : s));
    await open('OWNER', draft);
    expect(el.querySelector('.panel-h .pill')!.textContent).toContain('Draft · not published');
    button('Discard').click();
    const req = http.expectOne(`${BASE}/draft`);
    expect(req.request.body.steps).toEqual(LIVE.steps);
    req.flush({ steps: LIVE.steps });
    fixture.detectChanges();
    expect(el.querySelector('.panel-h .pill')!.textContent).toContain('Live · v3');
  });

  it('disables Publish and Discard when there is no draft change', async () => {
    await open('OWNER');
    expect(button('Publish v4').disabled).toBe(true);
    expect(button('Discard').disabled).toBe(true);
  });

  it('is read-only with a banner for a non-owner', async () => {
    await open('FRONT_DESK');
    expect(el.textContent).toContain('Only the owner can change the OPD workflow');
    expect(sw('Vitals').disabled).toBe(true);
    expect(button('Publish v4').disabled).toBe(true);
    sw('Vitals').click();
    http.expectNone(`${BASE}/draft`);
  });
});
