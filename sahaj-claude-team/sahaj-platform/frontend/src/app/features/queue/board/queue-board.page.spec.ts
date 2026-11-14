import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { EmrAccess } from '../../emr/emr-access.service';
import { DEFAULT_WORKFLOW_STEPS, WorkflowStep, WorkflowVersion } from '../../workflow/workflow.model';
import { Visit } from '../queue.model';
import { QueueBoardPage } from './queue-board.page';

const BASE = '/api/v1/opd';

const WAITING: Visit = {
  id: 'v1',
  tokenNo: 1,
  patientId: 'p1',
  patientName: 'Rohit Patil',
  age: 30,
  sex: 'M',
  doctorMembershipId: 'd1',
  doctorName: 'Dr. Rao',
  visitType: 'NEW',
  stage: 'WAITING',
  feePaise: 50000,
  workflowVersionNo: 2,
  createdAt: new Date().toISOString(),
};

const WITH_DOCTOR: Visit = {
  ...WAITING,
  id: 'v2',
  tokenNo: 2,
  patientName: 'Seema Kadam',
  stage: 'WITH_DOCTOR',
};

/** Default steps with the given on/off and owner overrides, like a published version. */
function version(
  versionNo: number,
  overrides: Partial<Record<string, Partial<WorkflowStep>>> = {},
): WorkflowVersion {
  return {
    versionNo,
    steps: DEFAULT_WORKFLOW_STEPS.map((step) => ({ ...step, ...overrides[step.key] })),
    publishedAt: '2026-11-01T05:00:00Z',
  };
}

/** v1 is the default; v2 turns Vitals off and Counselling on. */
const V1 = version(1);
const V2 = version(2, { vitals: { on: false }, counsel: { on: true } });

describe('QueueBoardPage', () => {
  let fixture: ComponentFixture<QueueBoardPage>;
  let http: HttpTestingController;
  /** Stands in for `GET /emr/me`: the card button follows `canOpenVisit` (SAH-46-1). */
  let emrAccess: { ensureLoaded: () => void; canOpen: () => boolean; canOpenVisit: (doctor: string) => boolean };

  beforeEach(() => {
    emrAccess = { ensureLoaded: () => undefined, canOpen: () => false, canOpenVisit: () => false };
  });

  function flushLoad(visits: Visit[], versions: WorkflowVersion[]): void {
    http.expectOne(`${BASE}/visits/today`).flush(visits);
    http.expectOne(`${BASE}/workflow/current`).flush(versions[versions.length - 1]);
    http.expectOne(`${BASE}/workflow/versions`).flush(versions);
  }

  async function open(
    role: Role,
    visits: Visit[] = [WAITING, WITH_DOCTOR],
    steps: readonly WorkflowStep[] = DEFAULT_WORKFLOW_STEPS,
    versions: WorkflowVersion[] = [V1, { ...V1, versionNo: 2, steps: [...steps] }],
  ) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
        { provide: EmrAccess, useValue: emrAccess },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(QueueBoardPage);
    fixture.detectChanges();
    flushLoad(visits, versions);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  const labelsOf = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('.col-h .pill')).map((p) => p.textContent!.trim());

  it('builds columns from the published steps, Vitals included by default', async () => {
    const el = await open('OWNER');
    expect(labelsOf(el)).toEqual(['Waiting', 'Vitals', 'With doctor', 'Billing', 'Done']);
  });

  it('leaves out Vitals when it is off and adds Counselling when it is on', async () => {
    const steps = DEFAULT_WORKFLOW_STEPS.map((s) => ({
      ...s,
      on: s.key === 'vitals' ? false : s.key === 'counsel' ? true : s.on,
    }));
    const el = await open('OWNER', [WAITING, WITH_DOCTOR], steps);
    expect(labelsOf(el)).toEqual(['Waiting', 'With doctor', 'Counselling', 'Billing', 'Done']);
  });

  it('adds a legacy column for a visit stranded on a step that is now off', async () => {
    const steps = DEFAULT_WORKFLOW_STEPS.map((s) => ({ ...s, on: s.key === 'vitals' ? false : s.on }));
    const el = await open('OWNER', [WAITING, { ...WAITING, id: 'v3', tokenNo: 3, stage: 'VITALS' }], steps);
    expect(labelsOf(el)).toEqual(['Waiting', 'With doctor', 'Billing', 'Vitals', 'Done']);
    expect(el.querySelector('.col.legacy')?.textContent).toContain('Removed in v2');
  });

  it('shows "New token" for front desk and hides it for a doctor', async () => {
    const owner = await open('FRONT_DESK');
    expect(Array.from(owner.querySelectorAll('button')).some((b) => b.textContent!.includes('New token'))).toBe(
      true,
    );
    TestBed.resetTestingModule();
    const doc = await open('DOCTOR');
    expect(Array.from(doc.querySelectorAll('button')).some((b) => b.textContent!.includes('New token'))).toBe(
      false,
    );
  });

  it('shows a "Move to Vitals" button a front desk can use on a waiting card', async () => {
    const el = await open('FRONT_DESK');
    const button = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent!.includes('Move to Vitals'),
    );
    expect(button).toBeTruthy();
  });

  it('hides the move button on a card the signed-in role does not own', async () => {
    const el = await open('NURSE');
    const waitingCard = Array.from(el.querySelectorAll('.qcard')).find((c) => c.textContent!.includes('T-1'))!;
    expect(waitingCard.querySelector('button')).toBeNull();
  });

  it('moves a card forward and reloads the board', async () => {
    const el = await open('FRONT_DESK');
    const button = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent!.includes('Move to Vitals'),
    )!;
    button.click();
    const req = http.expectOne(`${BASE}/visits/v1/stage`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ stage: 'VITALS' });
    req.flush({ ...WAITING, stage: 'VITALS' });
    flushLoad([{ ...WAITING, stage: 'VITALS' }], [V1, { ...V1, versionNo: 2 }]);
  });

  const card = (el: HTMLElement, token: number) =>
    Array.from(el.querySelectorAll('.qcard')).find((c) => c.textContent!.includes(`T-${token}`))!;
  const moveLabels = (el: Element) =>
    Array.from(el.querySelectorAll('button'))
      .map((b) => b.textContent!.trim())
      .filter((t) => t.startsWith('Move to'));
  const backMenuLabels = async (button: HTMLButtonElement) => {
    button.click();
    fixture.detectChanges();
    await fixture.whenStable();
    return Array.from(document.querySelectorAll('button[mat-menu-item]')).map((b) => b.textContent!.trim());
  };

  describe('visit own workflow version', () => {
    const v1Waiting: Visit = { ...WAITING, workflowVersionNo: 1 };
    const v1WithDoctor: Visit = { ...WITH_DOCTOR, workflowVersionNo: 1 };
    const v2WithDoctor: Visit = { ...WITH_DOCTOR, id: 'v4', tokenNo: 4, workflowVersionNo: 2 };

    it('still offers Vitals to a v1 visit in Waiting after v2 turns Vitals off', async () => {
      const el = await open('FRONT_DESK', [v1Waiting], V2.steps, [V1, V2]);
      expect(moveLabels(card(el, 1))).toEqual(['Move to Vitals']);
    });

    it('sends a v1 visit in With doctor to Billing, and a v2 visit to Counselling', async () => {
      const el = await open('FRONT_DESK', [v1WithDoctor, v2WithDoctor], V2.steps, [V1, V2]);
      expect(moveLabels(card(el, 2))).toEqual(['Move to Billing']);
      expect(moveLabels(card(el, 4))).toEqual(['Move to Counselling']);
    });

    it('sends the PATCH for the version-specific next stage', async () => {
      const el = await open('FRONT_DESK', [v2WithDoctor], V2.steps, [V1, V2]);
      Array.from(el.querySelectorAll('button'))
        .find((b) => b.textContent!.includes('Move to Counselling'))!
        .click();
      const req = http.expectOne(`${BASE}/visits/v4/stage`);
      expect(req.request.body).toEqual({ stage: 'COUNSEL' });
      req.flush({ ...v2WithDoctor, stage: 'COUNSEL' });
      flushLoad([{ ...v2WithDoctor, stage: 'COUNSEL' }], [V1, V2]);
    });

    it('lets the owner move a v2 visit in Billing back to Counselling', async () => {
      const v2Billing: Visit = { ...WITH_DOCTOR, id: 'v5', tokenNo: 5, stage: 'BILLING', workflowVersionNo: 2 };
      const el = await open('OWNER', [v2Billing], V2.steps, [V1, V2]);
      const labels = await backMenuLabels(el.querySelector<HTMLButtonElement>('button[aria-label="Move back"]')!);
      expect(labels).toEqual(['Move back to Waiting', 'Move back to With doctor', 'Move back to Counselling']);
    });

    it('offers Vitals but not Counselling to move a v1 visit in Billing back', async () => {
      const v1Billing: Visit = { ...WITH_DOCTOR, id: 'v6', tokenNo: 6, stage: 'BILLING', workflowVersionNo: 1 };
      const el = await open('OWNER', [v1Billing], V2.steps, [V1, V2]);
      const labels = await backMenuLabels(el.querySelector<HTMLButtonElement>('button[aria-label="Move back"]')!);
      expect(labels).toEqual(['Move back to Waiting', 'Move back to Vitals', 'Move back to With doctor']);
    });

    it('falls back to the current published steps when the visit version is missing', async () => {
      const unknown: Visit = { ...WAITING, workflowVersionNo: 9 };
      const el = await open('FRONT_DESK', [unknown], V2.steps, [V1, V2]);
      expect(moveLabels(card(el, 1))).toEqual(['Move to With doctor']);
    });

    it('falls back to the current published steps when the versions call fails', async () => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([problemDetailInterceptor])),
          provideHttpClientTesting(),
          { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role: 'FRONT_DESK' as Role }) } },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      fixture = TestBed.createComponent(QueueBoardPage);
      fixture.detectChanges();
      http.expectOne(`${BASE}/visits/today`).flush([v1Waiting]);
      http.expectOne(`${BASE}/workflow/current`).flush(V2);
      http.expectOne(`${BASE}/workflow/versions`).flush(null, { status: 500, statusText: 'Server error' });
      fixture.detectChanges();
      await fixture.whenStable();
      expect(moveLabels(card(fixture.nativeElement, 1))).toEqual(['Move to With doctor']);
    });
  });

  describe('stage owners from the version steps', () => {
    const vitalsCard: Visit = { ...WAITING, id: 'v7', tokenNo: 7, stage: 'VITALS', workflowVersionNo: 2 };
    const counselCard: Visit = { ...WAITING, id: 'v8', tokenNo: 8, stage: 'COUNSEL', workflowVersionNo: 2 };
    const reassigned = version(2, { vitals: { owner: 'DOCTOR' }, counsel: { on: true, owner: 'NURSE' } });

    it('lets a doctor move Vitals and a nurse move Counselling after the owners are reassigned', async () => {
      const doctor = await open('DOCTOR', [vitalsCard, counselCard], reassigned.steps, [V1, reassigned]);
      expect(moveLabels(card(doctor, 7))).toEqual(['Move to With doctor']);
      expect(moveLabels(card(doctor, 8))).toEqual([]);
      TestBed.resetTestingModule();
      const nurse = await open('NURSE', [vitalsCard, counselCard], reassigned.steps, [V1, reassigned]);
      expect(moveLabels(card(nurse, 7))).toEqual([]);
      expect(moveLabels(card(nurse, 8))).toEqual(['Move to Billing']);
    });

    it('filters My tasks by the reassigned owner', async () => {
      const el = await open('NURSE', [vitalsCard, counselCard, WITH_DOCTOR], reassigned.steps, [V1, reassigned]);
      Array.from(el.querySelectorAll('mat-button-toggle'))
        .find((t) => t.textContent!.includes('My tasks'))!
        .querySelector('button')!
        .click();
      fixture.detectChanges();
      const tokens = Array.from(el.querySelectorAll('.qcard .tk')).map((t) => t.textContent!.trim());
      expect(tokens).toEqual(['T-8']);
    });
  });

  it('opens the new-token dialog when the button is clicked', async () => {
    const el = await open('OWNER');
    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => ({ subscribe: () => undefined }) } as never);
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes('New token'))!.click();
    expect(openSpy).toHaveBeenCalled();
  });

  describe('Open EMR button (SAH-46-1)', () => {
    const buttonsOf = (el: HTMLElement) => el.querySelectorAll('[data-act=\'open-emr\']');

    it('is not shown when the user cannot open the EMR', async () => {
      const el = await open('NURSE');
      expect(buttonsOf(el).length).toBe(0);
    });

    it('is shown on each card the user may open, with the patient in its name', async () => {
      emrAccess.canOpenVisit = () => true;
      emrAccess.canOpen = () => true;
      const el = await open('DOCTOR');
      const buttons = buttonsOf(el);
      expect(buttons.length).toBe(2);
      expect(buttons[0].textContent).toContain('Open EMR');
      expect(buttons[0].getAttribute('aria-label')).toBe('Open EMR for Rohit Patil');
    });

    it('is offered only on the doctors own patients', async () => {
      emrAccess.canOpen = () => true;
      emrAccess.canOpenVisit = (doctor) => doctor === 'd1';
      const other: Visit = { ...WITH_DOCTOR, doctorMembershipId: 'd2' };
      const el = await open('DOCTOR', [WAITING, other]);
      expect(buttonsOf(el).length).toBe(1);
    });

    it('opens the EMR of the visit and calls the server once', async () => {
      emrAccess.canOpenVisit = () => true;
      emrAccess.canOpen = () => true;
      const el = await open('DOCTOR', [WAITING]);
      (buttonsOf(el)[0] as HTMLButtonElement).click();
      const req = http.expectOne(BASE + '/emr/records/open');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ encounterType: 'OPD_VISIT', encounterId: 'v1' });
      req.flush({}, { status: 500, statusText: 'x' });
    });
  });
});
