import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { ROLE_LABELS } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmptyStateComponent } from '../../../shared/ui/empty-state.component';
import { PageHeadComponent } from '../../../shared/ui/page-head.component';
import { problemMessage } from '../../config/config-format';
import { fixTarget, sortChecks, summarise } from '../../config/overview/overview.logic';
import { ClinicApi } from '../../config/platform/clinic.api';
import { SetupOverview } from '../../config/platform/clinic.model';
import { NewTokenDialogComponent } from '../../queue/new-token/new-token-dialog.component';
import { canCreateToken, canMoveForward, nextStage } from '../../queue/queue-stage.util';
import { QueueApi } from '../../queue/queue.api';
import { STAGE_LABELS, VisitType, visitTypeLabel } from '../../queue/queue.model';
import { WorkflowApi } from '../../workflow/workflow.api';
import { DEFAULT_WORKFLOW_STEPS, WorkflowStep } from '../../workflow/workflow.model';
import { TodayApi } from './today.api';
import { greetingFor, greetingName, initialsOf, plural } from './today.logic';
import { BillingTodaySummary, TodayTask, TodaySummary } from './today.model';

const NEW_TOKEN_DIALOG_CONFIG = {
  panelClass: 'sheet-panel',
  width: '560px',
  maxWidth: '100vw',
} as const;

interface AlertView {
  readonly text: string;
  readonly actionLabel: string;
  readonly link: string;
}

interface TaskAction {
  readonly kind: 'move' | 'link';
  readonly label: string;
}

/**
 * Today (SAH-15), mockup `vToday`: greeting, four KPI tiles, Live OPD, My tasks and Alerts, plus
 * the Getting started checklist for an owner whose setup is not complete. The two summaries are
 * loaded in parallel; if the billing one fails only the Collected tile shows a dash.
 */
@Component({
  selector: 'app-today',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, IconComponent, PageHeadComponent, EmptyStateComponent],
  styleUrl: './today.page.scss',
  templateUrl: './today.page.html',
})
export class TodayPage implements OnInit {
  private readonly api = inject(TodayApi);
  private readonly queueApi = inject(QueueApi);
  private readonly workflowApi = inject(WorkflowApi);
  private readonly clinicApi = inject(ClinicApi);
  private readonly store = inject(ShellStore);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly formatInr = formatInr;
  protected readonly initialsOf = initialsOf;
  protected readonly stageLabels = STAGE_LABELS;
  protected readonly fixTarget = fixTarget;

  protected readonly summary = signal<TodaySummary | null>(null);
  /** Null while unknown or when the billing call failed. */
  protected readonly billing = signal<BillingTodaySummary | null>(null);
  protected readonly steps = signal<readonly WorkflowStep[]>(DEFAULT_WORKFLOW_STEPS);
  protected readonly overview = signal<SetupOverview | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly movingId = signal<string | null>(null);

  private readonly me = this.store.me;
  protected readonly role = computed(() => this.me()?.role);
  protected readonly roleLabel = computed(() => {
    const role = this.role();
    return role ? ROLE_LABELS[role] : '';
  });
  protected readonly greeting = computed(() => {
    const name = greetingName(this.me()?.name);
    return name ? `${greetingFor()}, ${name}` : greetingFor();
  });
  protected readonly setupPercent = computed(() => this.me()?.clinic.setupPercent ?? 0);
  protected readonly showSetup = computed(
    () => this.role() === 'OWNER' && !(this.me()?.clinic.setupComplete ?? true),
  );

  protected readonly canBook = computed(
    () => this.role() === 'OWNER' || this.role() === 'FRONT_DESK',
  );
  protected readonly canIssueToken = computed(() => canCreateToken(this.role()));
  private readonly canSeeBilling = computed(() => this.me()?.menu.includes('billing') ?? false);

  protected readonly checks = computed(() => sortChecks(this.overview()?.checks ?? []));
  protected readonly setupCounts = computed(() => summarise(this.overview()?.checks ?? []));

  protected readonly alerts = computed<AlertView[]>(() => {
    if (!this.canBook()) {
      return [];
    }
    const out: AlertView[] = [];
    const pending = this.billing()?.billsPending ?? 0;
    if (pending > 0) {
      out.push({
        text: `${plural(pending, 'visit', 'visits')} waiting for payment`,
        actionLabel: 'Open billing',
        link: '/app/billing',
      });
    }
    const missed = this.summary()?.followUpsMissed ?? 0;
    if (missed > 0) {
      out.push({
        text: `${plural(missed, 'follow-up', 'follow-ups')} missed`,
        actionLabel: 'Review',
        link: '/app/followups',
      });
    }
    return out;
  });

  ngOnInit(): void {
    this.load(true);
    if (this.showSetup()) {
      this.clinicApi
        .getOverview()
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (overview) => this.overview.set(overview),
          error: () => this.overview.set(null),
        });
    }
  }

  protected taskAction(task: TodayTask): TaskAction | null {
    if (task.stage === 'BILLING') {
      return this.canSeeBilling() ? { kind: 'link', label: 'Collect payment' } : null;
    }
    const next = this.nextFor(task);
    if (!next || !canMoveForward(this.role(), task.stage, this.steps())) {
      return null;
    }
    return { kind: 'move', label: `Move to ${STAGE_LABELS[next]}` };
  }

  protected typeOf(type: VisitType): string {
    return visitTypeLabel(type);
  }

  protected moveTask(task: TodayTask): void {
    const next = this.nextFor(task);
    if (!next || this.movingId()) {
      return;
    }
    this.movingId.set(task.visitId);
    this.queueApi
      .moveStage(task.visitId, next)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.movingId.set(null);
          this.load(false);
        },
        error: (error: unknown) => {
          this.movingId.set(null);
          this.snackBar.open(
            problemMessage(error, 'We could not move this token. Try again.'),
            'Close',
            {
              duration: 5000,
            },
          );
        },
      });
  }

  protected openNewToken(): void {
    if (!this.canIssueToken()) {
      return;
    }
    this.dialog
      .open(NewTokenDialogComponent, NEW_TOKEN_DIALOG_CONFIG)
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((visit: unknown) => {
        if (visit) {
          this.load(false);
        }
      });
  }

  protected reload(): void {
    this.load(true);
  }

  private nextFor(task: TodayTask) {
    return nextStage(task.stage, this.steps());
  }

  private load(first: boolean): void {
    if (first) {
      this.loading.set(true);
    }
    this.loadError.set(null);
    forkJoin({
      opd: this.api.opd(),
      billing: this.api.billing().pipe(catchError(() => of(null))),
      workflow: this.workflowApi.getCurrent().pipe(catchError(() => of(null))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ opd, billing, workflow }) => {
          this.summary.set(opd);
          this.billing.set(billing);
          if (workflow) {
            this.steps.set(workflow.steps);
          }
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(problemMessage(error, 'We could not load Today. Try again.'));
        },
      });
  }
}
