import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../config-format';
import { WorkflowApi } from '../../workflow/workflow.api';
import {
  WORKFLOW_STEP_META,
  WORKFLOW_STEP_ORDER,
  WORKFLOW_STEP_OWNERS,
  WorkflowStep,
  WorkflowStepKey,
  WorkflowStepOwner,
  WorkflowVersion,
  workflowStepsEqual,
} from '../../workflow/workflow.model';

/**
 * OPD workflow (SAH-12), mockup `cfgWorkflow`: the clinic's 8 steps, on/off switches, an owner
 * for Vitals and Counselling, a flow preview and a version badge. Token, Consultation and
 * Billing are fixed/always-on in this build (scope decision, 1 Nov) — they show locked, with no
 * owner change. Case paper print and Follow-up set are plain on/off toggles, stored only; they
 * have no effect yet (no print feature, no Follow-ups story). Step reordering is out of scope.
 * Owner-only edit; everyone else sees it read-only, same pattern as SAH-10/SAH-11.
 */
@Component({
  selector: 'app-workflow',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatSelectModule, IconComponent],
  styleUrl: './workflow.page.scss',
  templateUrl: './workflow.page.html',
})
export class WorkflowPage implements OnInit {
  private readonly api = inject(WorkflowApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly stepOrder = WORKFLOW_STEP_ORDER;
  protected readonly stepMeta = WORKFLOW_STEP_META;
  protected readonly ownerChoices = WORKFLOW_STEP_OWNERS;

  protected readonly draftSteps = signal<WorkflowStep[]>([]);
  protected readonly current = signal<WorkflowVersion | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly publishing = signal(false);

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');

  protected readonly dirty = computed(() => {
    const current = this.current();
    return current != null && !workflowStepsEqual(this.draftSteps(), current.steps);
  });

  protected readonly onSteps = computed(() => this.draftSteps().filter((step) => step.on));

  protected readonly owners = computed(() => [...new Set(this.onSteps().map((step) => this.ownerLabel(step.owner)))]);

  ngOnInit(): void {
    forkJoin({ draft: this.api.getDraft(), current: this.api.getCurrent() }).subscribe({
      next: ({ draft, current }) => {
        this.draftSteps.set(draft.steps);
        this.current.set(current);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load the OPD workflow. Try again.'));
      },
    });
  }

  protected stepFor(key: WorkflowStepKey): WorkflowStep | undefined {
    return this.draftSteps().find((step) => step.key === key);
  }

  protected ownerLabel(owner: WorkflowStepOwner | undefined): string {
    return this.ownerChoices.find((choice) => choice.value === owner)?.label ?? '—';
  }

  protected toggleStep(key: WorkflowStepKey): void {
    if (!this.isOwner() || this.stepMeta[key].fixed || this.saving()) {
      return;
    }
    const next = this.draftSteps().map((step) => (step.key === key ? { ...step, on: !step.on } : step));
    this.saveDraft(next);
  }

  protected setOwner(key: WorkflowStepKey, owner: WorkflowStepOwner): void {
    if (!this.isOwner() || this.stepMeta[key].fixed || this.saving()) {
      return;
    }
    const next = this.draftSteps().map((step) => (step.key === key ? { ...step, owner } : step));
    this.saveDraft(next);
  }

  protected discard(): void {
    const current = this.current();
    if (!current || !this.isOwner() || !this.dirty()) {
      return;
    }
    this.saveDraft(current.steps.map((step) => ({ ...step })));
  }

  protected publish(): void {
    if (!this.isOwner() || !this.dirty() || this.publishing()) {
      return;
    }
    this.publishing.set(true);
    this.api.publish().subscribe({
      next: (version) => {
        this.publishing.set(false);
        this.current.set(version);
        this.draftSteps.set(version.steps);
        this.toast(`Published as version ${version.versionNo}.`);
      },
      error: (error: unknown) => {
        this.publishing.set(false);
        this.toast(problemMessage(error, 'We could not publish. Try again.'));
      },
    });
  }

  private saveDraft(steps: WorkflowStep[]): void {
    const before = this.draftSteps();
    this.draftSteps.set(steps);
    this.saving.set(true);
    this.api.saveDraft(steps).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.draftSteps.set(saved.steps);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.draftSteps.set(before);
        this.toast(problemMessage(error, 'We could not save that change. Try again.'));
      },
    });
  }

  private toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
