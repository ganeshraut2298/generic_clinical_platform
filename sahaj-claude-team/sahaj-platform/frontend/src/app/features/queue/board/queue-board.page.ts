import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { ApiError } from '../../../core/http/problem-detail';
import { ShellStore } from '../../../core/shell/shell.store';
import { formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { NewTokenDialogComponent } from '../new-token/new-token-dialog.component';
import { QueueApi } from '../queue.api';
import { boardColumns, currentStages } from '../queue-columns.util';
import { WorkflowApi } from '../../workflow/workflow.api';
import { WorkflowStep } from '../../workflow/workflow.model';
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
  imports: [MatButtonModule, MatButtonToggleModule, MatMenuModule, IconComponent],
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

  protected readonly stageLabels = STAGE_LABELS;
  protected readonly stagePillClass = stagePillClass;
  protected readonly visitTypeLabel = visitTypeLabel;
  protected readonly formatInr = formatInr;
  protected readonly placeholderStages = PLACEHOLDER_STAGES;

  protected readonly visits = signal<Visit[]>([]);
  /** Steps of the currently published workflow version (SAH-12). */
  protected readonly steps = signal<WorkflowStep[]>([]);
  protected readonly liveVersion = signal(1);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly myOnly = signal(false);
  protected readonly now = signal(new Date());
  protected readonly movingId = signal<string | null>(null);

  private readonly role = computed(() => this.store.me()?.role);

  protected readonly canCreateToken = computed(() => roleCanCreateToken(this.role()));
  private readonly stages = computed(() => currentStages(this.steps()));
  protected readonly vitalsEnabled = computed(() => this.stages().includes('VITALS'));
  protected readonly counselEnabled = computed(() => this.stages().includes('COUNSEL'));

  protected readonly columns = computed<ColumnView[]>(() => {
    const role = this.role();
    const mine = this.myOnly();
    const visits = this.visits();
    return boardColumns(this.steps(), visits).map((column) => ({
      ...column,
      visits: visits.filter((visit) => visit.stage === column.stage && (!mine || ownsStage(role, column.stage))),
    }));
  });

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

  protected canMove(visit: Visit): boolean {
    return canMoveForward(this.role(), visit.stage);
  }

  protected canMoveBack(): boolean {
    return canMoveBackward(this.role());
  }

  protected earlierStagesOf(visit: Visit): VisitStage[] {
    return earlierStages(visit.stage);
  }

  protected nextStageLabel(visit: Visit): string | null {
    const next = nextStage(visit.stage, this.vitalsEnabled(), this.counselEnabled());
    return next ? STAGE_LABELS[next] : null;
  }

  protected moveToNext(visit: Visit): void {
    const next = nextStage(visit.stage, this.vitalsEnabled(), this.counselEnabled());
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
    forkJoin({ visits: this.api.today(), current: this.workflowApi.getCurrent() }).subscribe({
      next: ({ visits, current }) => {
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
