import { WorkflowStep, WorkflowStepKey } from '../workflow/workflow.model';
import { STAGE_LABELS, Visit, VisitStage } from './queue.model';

/** Mockup `STAGE_OF`: the workflow steps that are board stages, and the stage each one is. */
const STAGE_OF_STEP: Partial<Record<WorkflowStepKey, VisitStage>> = {
  queue: 'WAITING',
  vitals: 'VITALS',
  consult: 'WITH_DOCTOR',
  counsel: 'COUNSEL',
  billing: 'BILLING',
};

export interface BoardColumn {
  readonly stage: VisitStage;
  readonly label: string;
  /** True for a stage no longer in the published steps that an in-progress visit still sits in
   * (SAH-12 acceptance criterion 4, mockup `columns()`). */
  readonly legacy: boolean;
}

/** The stages of the currently published steps, in flow order, without Done. */
export function currentStages(steps: readonly WorkflowStep[]): VisitStage[] {
  const stages: VisitStage[] = [];
  for (const step of steps) {
    const stage = STAGE_OF_STEP[step.key];
    if (step.on && stage) {
      stages.push(stage);
    }
  }
  return stages;
}

/**
 * Board columns (mockup `columns()`): one per enabled stage step of the current version, then one
 * "legacy" column for each stage still holding a non-Done visit that is not in the current
 * version, then Done.
 */
export function boardColumns(steps: readonly WorkflowStep[], visits: readonly Visit[]): BoardColumn[] {
  const columns: BoardColumn[] = currentStages(steps).map((stage) => ({
    stage,
    label: STAGE_LABELS[stage],
    legacy: false,
  }));
  for (const visit of visits) {
    if (visit.stage !== 'DONE' && !columns.some((column) => column.stage === visit.stage)) {
      columns.push({ stage: visit.stage, label: STAGE_LABELS[visit.stage], legacy: true });
    }
  }
  columns.push({ stage: 'DONE', label: STAGE_LABELS.DONE, legacy: false });
  return columns;
}
