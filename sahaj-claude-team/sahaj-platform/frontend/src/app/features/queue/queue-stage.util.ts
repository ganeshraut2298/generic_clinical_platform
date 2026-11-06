import { Role } from '../../core/platform/platform.model';
import { WorkflowStep, WorkflowStepKey, WorkflowVersion } from '../workflow/workflow.model';
import { VisitStage } from './queue.model';

/** Roles allowed to issue a walk-in token (acceptance criterion 8). */
export const TOKEN_CREATOR_ROLES: readonly Role[] = ['OWNER', 'FRONT_DESK', 'NURSE'];

/** Roles allowed to move any card, forward or backward (acceptance criterion 3 and 8). Mirrors
 * `VisitService.MOVE_ANY_ROLES`; the same roles see every card under "My tasks". */
const ANY_CARD_ROLES: readonly Role[] = ['OWNER', 'FRONT_DESK'];

/** Doctor roles always own With doctor, whatever the Consultation step says. */
const DOCTOR_ROLES: readonly Role[] = ['DOCTOR', 'VISITING_DOCTOR'];

export function canCreateToken(role: Role | undefined): boolean {
  return !!role && TOKEN_CREATOR_ROLES.includes(role);
}

function isOn(steps: readonly WorkflowStep[], key: WorkflowStepKey): boolean {
  return steps.some((step) => step.key === key && step.on);
}

/**
 * The stages a visit passes through under the given workflow steps, in order, Done included.
 * Mirrors `VisitService.enabledStages`: Waiting, Vitals only when on, With doctor, Counselling
 * only when on, Billing, Done.
 */
export function enabledStages(steps: readonly WorkflowStep[]): VisitStage[] {
  const stages: VisitStage[] = ['WAITING'];
  if (isOn(steps, 'vitals')) {
    stages.push('VITALS');
  }
  stages.push('WITH_DOCTOR');
  if (isOn(steps, 'counsel')) {
    stages.push('COUNSEL');
  }
  stages.push('BILLING', 'DONE');
  return stages;
}

/** Whether the step owner (a role code) covers this role. A DOCTOR owner covers both doctor roles. */
function stepOwnedBy(steps: readonly WorkflowStep[], key: WorkflowStepKey, role: Role): boolean {
  return steps.some(
    (step) =>
      step.key === key &&
      step.owner != null &&
      (step.owner === role || (step.owner === 'DOCTOR' && DOCTOR_ROLES.includes(role))),
  );
}

/**
 * Whether the signed-in role owns the stage under these steps (used by the Everyone/My tasks
 * filter and by "Move to next"). Mirrors `VisitService.ownedStages`: Owner and Front desk own every
 * stage; doctors always own With doctor; Vitals and Counselling belong to whoever the workflow
 * step names as owner.
 */
export function ownsStage(role: Role | undefined, stage: VisitStage, steps: readonly WorkflowStep[]): boolean {
  if (!role) {
    return false;
  }
  if (ANY_CARD_ROLES.includes(role)) {
    return true;
  }
  switch (stage) {
    case 'WITH_DOCTOR':
      return DOCTOR_ROLES.includes(role);
    case 'VITALS':
      return stepOwnedBy(steps, 'vitals', role);
    case 'COUNSEL':
      return stepOwnedBy(steps, 'counsel', role);
    default:
      return false;
  }
}

/** Whether this role may move a card forward out of `stage` (its own stage, or Owner/Front desk). */
export function canMoveForward(role: Role | undefined, stage: VisitStage, steps: readonly WorkflowStep[]): boolean {
  return ownsStage(role, stage, steps);
}

/** Only OWNER may move a card backward, to an earlier stage, to correct a mistake. */
export function canMoveBackward(role: Role | undefined): boolean {
  return role === 'OWNER';
}

/**
 * The next stage one step forward from `current`, using the steps of the VISIT's own workflow
 * version (not the clinic's current one). Null at Done, or if `current` is not a stage of those
 * steps. The server still decides what a visit may do.
 */
export function nextStage(current: VisitStage, steps: readonly WorkflowStep[]): VisitStage | null {
  const stages = enabledStages(steps);
  const index = stages.indexOf(current);
  return index < 0 ? null : (stages[index + 1] ?? null);
}

/** Earlier stages an owner could move a card back to, in forward order (acceptance criterion 3),
 * limited to the stages the visit's own workflow version has. */
export function earlierStages(current: VisitStage, steps: readonly WorkflowStep[]): VisitStage[] {
  const stages = enabledStages(steps);
  const index = stages.indexOf(current);
  return index < 0 ? [] : stages.slice(0, index);
}

/** Maps a stage onto one of the existing `.pill` colour classes (`styles/_primitives.scss`),
 * rather than inventing new colour classes per stage. */
const STAGE_PILL_CLASS: Record<VisitStage, string> = {
  WAITING: 'p-wait',
  VITALS: 'p-vitals',
  WITH_DOCTOR: 'p-consult',
  COUNSEL: 'p-sec',
  BILLING: 'p-billing',
  DONE: 'p-good',
};

export function stagePillClass(stage: VisitStage): string {
  return STAGE_PILL_CLASS[stage];
}

/** Minutes since `createdAt`, computed client-side (acceptance criterion 5), never negative. */
export function minutesWaited(createdAt: string, now: Date = new Date()): number {
  const created = new Date(createdAt).getTime();
  if (Number.isNaN(created)) {
    return 0;
  }
  return Math.max(0, Math.round((now.getTime() - created) / 60_000));
}

/**
 * The steps a visit runs on: those of the published version it was issued under, or the
 * fallback (the clinic's current published steps) when that version is not in the list.
 */
export function stepsForVersion(
  versionNo: number | undefined,
  versions: readonly WorkflowVersion[],
  fallback: readonly WorkflowStep[],
): readonly WorkflowStep[] {
  return versions.find((version) => version.versionNo === versionNo)?.steps ?? fallback;
}
