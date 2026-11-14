import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { EmrAccess } from '../../emr/emr-access.service';
import { EmrReprintService } from '../../emr/reprint/emr-reprint.service';
import { DEFAULT_WORKFLOW_STEPS, WorkflowVersion } from '../../workflow/workflow.model';
import { Visit } from '../queue.model';
import { QueueViewState } from '../queue-view.state';
import { QueueBoardPage } from './queue-board.page';

const BASE = '/api/v1/opd';
const V1: WorkflowVersion = { versionNo: 1, steps: [...DEFAULT_WORKFLOW_STEPS], publishedAt: '2026-11-01T05:00:00Z' };

const visit = (over: Partial<Visit>): Visit => ({
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
  workflowVersionNo: 1,
  createdAt: '2026-11-13T04:00:00Z',
  ...over,
});

describe('QueueBoardPage, phase 5 (SAH-67-4 Emergency group, SAH-51-2 Consult done, SAH-51-6 Reprint)', () => {
  let http: HttpTestingController;
  let reprint: { open: ReturnType<typeof vi.fn>; opening: () => string | null };

  async function open(role: Role, visits: Visit[], access: { canOpenVisit: (d: string) => boolean } = { canOpenVisit: () => false }): Promise<HTMLElement> {
    reprint = { open: vi.fn(), opening: () => null };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
        { provide: EmrAccess, useValue: { ensureLoaded: () => undefined, canOpen: () => true, canOpenVisit: access.canOpenVisit } },
        { provide: EmrReprintService, useValue: reprint },
        { provide: MatDialog, useValue: { open: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(QueueBoardPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/visits/today`).flush(visits);
    http.expectOne(`${BASE}/workflow/current`).flush(V1);
    http.expectOne(`${BASE}/workflow/versions`).flush([V1]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  describe('Emergency group', () => {
    const emergency = (id: string, token: number, level: number, name: string, createdAt: string) =>
      visit({ id, tokenNo: token, visitType: 'EMERGENCY', triageLevel: level, triageLevelName: name, triageTone: level === 1 ? 'crit' : 'warn', createdAt, patientName: `Patient ${token}` });

    it('is at the top, most urgent first and then longest waiting, with the level as text', async () => {
      const el = await open('OWNER', [
        visit({ id: 'n1', tokenNo: 1 }),
        emergency('e2', 2, 2, 'Orange · Very urgent', '2026-11-13T04:00:00Z'),
        emergency('e3', 3, 1, 'Red · Immediate', '2026-11-13T04:30:00Z'),
        emergency('e4', 4, 2, 'Orange · Very urgent', '2026-11-13T03:00:00Z'),
      ]);
      const group = el.querySelector('[data-testid=emergency-group]')!;
      expect(group).not.toBeNull();
      expect(group.compareDocumentPosition(el.querySelector('.board-wrap')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(Array.from(group.querySelectorAll('.tk')).map((t) => t.textContent!.trim())).toEqual(['T-3', 'T-4', 'T-2']);
      expect(Array.from(group.querySelectorAll('[data-testid=triage-level]')).map((t) => t.textContent!.trim())).toEqual([
        'Red · Immediate',
        'Orange · Very urgent',
        'Orange · Very urgent',
      ]);
      expect(group.querySelector('.n')!.textContent).toBe('3');
    });

    it('takes the Emergency visits out of the stage columns', async () => {
      const el = await open('OWNER', [visit({ id: 'n1', tokenNo: 1 }), emergency('e2', 2, 1, 'Red · Immediate', '2026-11-13T04:00:00Z')]);
      const waiting = el.querySelector('.board .col')!;
      expect(Array.from(waiting.querySelectorAll('.tk')).map((t) => t.textContent!.trim())).toEqual(['T-1']);
    });

    it('shows no group when nobody is an Emergency visit, and a finished Emergency visit stays in Done', async () => {
      const none = await open('OWNER', [visit({ id: 'n1' })]);
      expect(none.querySelector('[data-testid=emergency-group]')).toBeNull();
      TestBed.resetTestingModule();
      const done = await open('OWNER', [{ ...emergency('e2', 2, 1, 'Red · Immediate', '2026-11-13T04:00:00Z'), stage: 'DONE' }]);
      expect(done.querySelector('[data-testid=emergency-group]')).toBeNull();
      expect(done.textContent).toContain('T-2');
    });

    it('labels the card "Emergency"', async () => {
      const el = await open('OWNER', [emergency('e2', 2, 1, 'Red · Immediate', '2026-11-13T04:00:00Z')]);
      expect(el.querySelector('[data-testid=emergency-group] .qtop .pill')!.textContent!.trim()).toBe('Emergency');
    });

    it('is also shown to front desk (who cannot set the type from the EMR)', async () => {
      const el = await open('FRONT_DESK', [emergency('e2', 2, 1, 'Red · Immediate', '2026-11-13T04:00:00Z')]);
      expect(el.querySelector('[data-testid=emergency-group]')).not.toBeNull();
    });
  });

  describe('Consult done becomes Open EMR for doctors (ADR-025 H3)', () => {
    const withDoctor = visit({ id: 'v2', tokenNo: 2, stage: 'WITH_DOCTOR', patientName: 'Seema Kadam' });
    const moveButtons = (el: HTMLElement) => Array.from(el.querySelectorAll('button')).map((b) => b.textContent!.trim()).filter((t) => t.startsWith('Move to'));

    it('hides the move button on the With doctor card when the user can open the EMR for that visit', async () => {
      const el = await open('DOCTOR', [withDoctor], { canOpenVisit: () => true });
      expect(moveButtons(el)).toEqual([]);
    });

    it('keeps the move button for a visit the EMR cannot open (another doctor\'s patient)', async () => {
      const el = await open('DOCTOR', [withDoctor], { canOpenVisit: () => false });
      expect(moveButtons(el)).toEqual(['Move to Billing']);
    });

    it('front desk keeps moving cards', async () => {
      const el = await open('FRONT_DESK', [withDoctor], { canOpenVisit: () => false });
      expect(moveButtons(el)).toEqual(['Move to Billing']);
    });

    it('does not touch the other stages', async () => {
      const el = await open('DOCTOR', [visit({ id: 'w', stage: 'WAITING' })], { canOpenVisit: () => true });
      expect(moveButtons(el)).toEqual([]);
    });
  });

  describe('Reprint (SAH-51-6)', () => {
    const signed = visit({ id: 'v9', tokenNo: 9, stage: 'DONE', emrStatus: 'SIGNED', patientName: 'Asha Mehta' });
    const reprintButton = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('[data-act=reprint]');

    it('is offered on a signed visit to owner, front desk and pharmacist', async () => {
      for (const role of ['OWNER', 'FRONT_DESK', 'PHARMACIST'] as const) {
        const el = await open(role, [signed]);
        expect(reprintButton(el), role).not.toBeNull();
        TestBed.resetTestingModule();
      }
    });

    it('is offered to a doctor only for their own visit', async () => {
      const own = await open('DOCTOR', [signed], { canOpenVisit: () => true });
      expect(reprintButton(own)).not.toBeNull();
      TestBed.resetTestingModule();
      const other = await open('DOCTOR', [signed], { canOpenVisit: () => false });
      expect(reprintButton(other)).toBeNull();
    });

    it('is not offered to billing or nurse, or on a visit that is not signed', async () => {
      const billing = await open('BILLING', [signed]);
      expect(reprintButton(billing)).toBeNull();
      TestBed.resetTestingModule();
      const nurse = await open('NURSE', [signed]);
      expect(reprintButton(nurse)).toBeNull();
      TestBed.resetTestingModule();
      const draft = await open('OWNER', [{ ...signed, emrStatus: 'DRAFT' }]);
      expect(reprintButton(draft)).toBeNull();
    });

    it('opens the signed prescription of that visit', async () => {
      const el = await open('OWNER', [signed]);
      reprintButton(el)!.click();
      expect(reprint.open).toHaveBeenCalledWith('v9');
    });
  });

  it('keeps the Everyone or My tasks choice for the session (the doctor comes back to the same filter)', async () => {
    const el = await open('OWNER', [visit({ id: 'n1' })]);
    const view = TestBed.inject(QueueViewState);
    expect(view.myOnly()).toBe(false);
    Array.from(el.querySelectorAll<HTMLElement>('mat-button-toggle')).find((t) => t.textContent!.includes('My tasks'))!.querySelector('button')!.click();
    expect(view.myOnly()).toBe(true);
  });
});
