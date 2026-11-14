import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { NgTemplateOutlet } from '@angular/common';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, forkJoin, of } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PageHeadComponent } from '../../../shared/ui/page-head.component';
import { EmrAccess } from '../../emr/emr-access.service';
import { EmrReprintService, roleCanReprint } from '../../emr/reprint/emr-reprint.service';
import { OpenEmrButtonComponent } from '../../emr/open-emr-button.component';
import { NewTokenDialogComponent } from '../new-token/new-token-dialog.component';
import { QueueApi } from '../queue.api';
import { QueueViewState } from '../queue-view.state';
import { boardColumns } from '../queue-columns.util';
import { WorkflowApi } from '../../workflow/workflow.api';
import { WorkflowStep, WorkflowVersion } from '../../workflow/workflow.model';
import { PLACEHOLDER_STAGES, STAGE_LABELS, Visit, VisitStage, visitTypeLabel } from '../queue.model';
import {
  canCreateToken as roleCanCreateToken,
  canMoveBackward,
  canMoveForward,
  earlierStages,
  minutesWaited,
  nextStage,
  ownsStage,
  stagePillClass,
  stepsForVersion,
} from '../queue-stage.util';

interface ColumnView {
  readonly stage: VisitStage;
  readonly label: string;
  readonly legacy: boolean;
  readonly visits: readonly Visit[];
}

const NEW_TOKEN_DIALOG_CONFIG = { panelClass: 'sheet-panel', width: '560px', maxWidth: '100vw' } as const;

/**
 * Queue (SAH-9), mockup `vQueue`: a board of stage columns, Everyone/My tasks filter, and a
 * "New token" dialog. Drag-and-drop from the mockup is out of scope (story §6); each card has
 * a "Move to <next stage>" button instead.
 */
@Component({
  selector: 'app-queue-board',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatButtonToggleModule, MatMenuModule, NgTemplateOutlet, IconComponent, PageHeadComponent, EmptyStateComponent, OpenEmrButtonComponent],
  styleUrl: './queue-board.page.scss',
  templateUrl: './queue-board.page.html',
})
export class QueueBoardPage implements OnInit {
  private readonly api = inject(QueueApi);
  private readonly workflowApi = inject(WorkflowApi);
  private readonly store = inject(ShellStore);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly emrAccess = inject(EmrAccess);
  protected readonly reprint = inject(EmrReprintService);

  protected readonly stageLabels = STAGE_LABELS;
  protected readonly stagePillClass = stagePillClass;
  protected readonly visitTypeLabel = visitTypeLabel;
  protected readonly formatInr = formatInr;
  protected readonly placeholderStages = PLACEHOLDER_STAGES;

  protected readonly visits = signal<Visit[]>([]);
  /** Steps of the currently published workflow version (SAH-12). */
  protected readonly steps = signal<WorkflowStep[]>([]);
  /** Every published version, loaded once per page load: a card moves through the steps of the
   * version its visit was issued under, not the current one. */
  private readonly versions = signal<WorkflowVersion[]>([]);
  protected readonly liveVersion = signal(1);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  private readonly view = inject(QueueViewState);
  protected readonly myOnly = this.view.myOnly;
  protected readonly now = signal(new Date());
  protected readonly movingId = signal<string | null>(null);

  private readonly role = computed(() => this.store.me()?.role);

  protected readonly canCreateToken = computed(() => roleCanCreateToken(this.role()));

  /** An Emergency visit that is still in the clinic joins the group at the top (SAH-67-4). */
  private static inEmergencyGroup(visit: Visit): boolean {
    return visit.visitType === 'EMERGENCY' && visit.stage !== 'DONE';
  }

  protected readonly columns = computed<ColumnView[]>(() => {
    const role = this.role();
    const mine = this.myOnly();
    const visits = this.visits();
    return boardColumns(this.steps(), visits).map((column) => ({
      ...column,
      visits: visits.filter(
        (visit) =>
          visit.stage === column.stage &&
          !QueueBoardPage.inEmergencyGroup(visit) &&
          (!mine || ownsStage(role, visit.stage, this.stepsOf(visit))),
      ),
    }));
  });

  /** Most urgent first (level 1), then the longest waiting (the rule of the Emergency queue, SAH-28). */
  protected readonly emergencyVisits = computed<Visit[]>(() => {
    const role = this.role();
    const mine = this.myOnly();
    return this.visits()
      .filter((v) => QueueBoardPage.inEmergencyGroup(v) && (!mine || ownsStage(role, v.stage, this.stepsOf(v))))
      .sort((a, b) => (a.triageLevel ?? 99) - (b.triageLevel ?? 99) || a.createdAt.localeCompare(b.createdAt) || a.tokenNo - b.tokenNo);
  });

  protected triagePill(tone: string | null | undefined): string {
    return tone === 'crit' ? 'p-crit' : tone === 'warn' ? 'p-warn' : 'p-good';
  }

  /** "Reprint" on a signed visit for the roles that may (owner, front desk, pharmacist, the visit's doctor). */
  protected canReprint(visit: Visit): boolean {
    return visit.emrStatus === 'SIGNED' && roleCanReprint(this.role(), this.emrAccess.canOpenVisit(visit.doctorMembershipId));
  }

  protected reprintRx(visit: Visit): void {
    this.reprint.open(visit.id);
  }

  ngOnInit(): void {
    this.load();
    const timer = setInterval(() => this.now.set(new Date()), 30_000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected minutesOf(visit: Visit): number {
    return minutesWaited(visit.createdAt, this.now());
  }

  protected setMyOnly(mine: boolean): void {
    this.myOnly.set(mine);
  }

  /**
   * The doctor completes a visit from the EMR, so "Open EMR" takes the place of the move button on the
   * With doctor card of a doctor who can open it (SAH-51-2, ADR-025 H3). The stage endpoint is unchanged
   * and front desk keeps moving cards.
   */
  protected canMove(visit: Visit): boolean {
    if (visit.stage === 'WITH_DOCTOR' && this.emrAccess.canOpenVisit(visit.doctorMembershipId)) {
      return false;
    }
    return canMoveForward(this.role(), visit.stage, this.stepsOf(visit));
  }

  protected canMoveBack(): boolean {
    return canMoveBackward(this.role());
  }

  protected earlierStagesOf(visit: Visit): VisitStage[] {
    return earlierStages(visit.stage, this.stepsOf(visit));
  }

  protected nextStageLabel(visit: Visit): string | null {
    const next = nextStage(visit.stage, this.stepsOf(visit));
    return next ? STAGE_LABELS[next] : null;
  }

  protected moveToNext(visit: Visit): void {
    const next = nextStage(visit.stage, this.stepsOf(visit));
    if (next) {
      this.move(visit, next);
    }
  }

  protected moveBack(visit: Visit, stage: VisitStage): void {
    this.move(visit, stage);
  }

  protected openNewToken(): void {
    if (!this.canCreateToken()) {
      return;
    }
    this.dialog
      .open(NewTokenDialogComponent, NEW_TOKEN_DIALOG_CONFIG)
      .afterClosed()
      .subscribe((visit: Visit | null) => {
        if (visit) {
          this.load();
        }
      });
  }

  /** Steps of the visit's own workflow version; the current published steps if that version is not known. */
  private stepsOf(visit: Visit): readonly WorkflowStep[] {
    return stepsForVersion(visit.workflowVersionNo, this.versions(), this.steps());
  }

  private move(visit: Visit, stage: VisitStage): void {
    if (this.movingId()) {
      return;
    }
    this.movingId.set(visit.id);
    this.api.moveStage(visit.id, stage).subscribe({
      next: () => {
        this.movingId.set(null);
        this.load();
      },
      error: (error: unknown) => {
        this.movingId.set(null);
        this.toast(
          error instanceof ApiError
            ? error.problem.detail ?? 'We could not move this token. Try again.'
            : 'We could not move this token. Try again.',
        );
      },
    });
  }

  private load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    forkJoin({
      visits: this.api.today(),
      current: this.workflowApi.getCurrent(),
      versions: this.workflowApi.listVersions().pipe(catchError(() => of<WorkflowVersion[]>([]))),
    }).subscribe({
      next: ({ visits, current, versions }) => {
        this.versions.set(versions);
        this.visits.set(visits);
        this.steps.set(current.steps);
        this.liveVersion.set(current.versionNo);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(
          error instanceof ApiError
            ? error.problem.detail ?? 'We could not load the queue. Try again.'
            : 'We could not load the queue. Try again.',
        );
      },
    });
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
