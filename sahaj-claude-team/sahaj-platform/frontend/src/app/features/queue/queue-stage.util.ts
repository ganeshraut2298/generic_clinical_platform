import { Role } from '../../core/platform/platform.model';
import { STAGE_ORDER, VisitStage } from './queue.model';

/**
 * Role that owns each stage (story acceptance criterion 4 and 8): NURSE owns Vitals,
 * DOCTOR/VISITING_DOCTOR own With doctor. Counsel and Billing have an owning role too
 * (Counsellor, Billing) even though no story has built their screens yet, so "My tasks"
 * behaves sensibly once SAH-10/SAH-11 land. OWNER and FRONT_DESK own none of these
 * specifically; they see "Everyone" by default and may move any card (acceptance
 * criterion 8), independent of this map.
 */
const STAGE_OWNERS: Record<VisitStage, readonly Role[]> = {
  WAITING: ['FRONT_DESK'],
  VITALS: ['NURSE'],
  WITH_DOCTOR: ['DOCTOR', 'VISITING_DOCTOR'],
  COUNSEL: ['COUNSELLOR'],
  BILLING: ['BILLING'],
  DONE: [],
};

/** Roles allowed to issue a walk-in token (acceptance criterion 8). */
export const TOKEN_CREATOR_ROLES: readonly Role[] = ['OWNER', 'FRONT_DESK', 'NURSE'];

/** Roles allowed to move any card, forward or backward (acceptance criterion 3 and 8). */
const ANY_CARD_ROLES: readonly Role[] = ['OWNER', 'FRONT_DESK'];

export function canCreateToken(role: Role | undefined): boolean {
  return !!role && TOKEN_CREATOR_ROLES.includes(role);
}

/** Whether the signed-in role owns the given stage (used by the Everyone/My tasks filter). */
export function ownsStage(role: Role | undefined, stage: VisitStage): boolean {
  return !!role && STAGE_OWNERS[stage].includes(role);
}

/** Whether the signed-in role may move this card forward (own stage, or OWNER/FRONT_DESK). */
export function canMoveForward(role: Role | undefined, stage: VisitStage): boolean {
  if (!role) {
    return false;
  }
  return ANY_CARD_ROLES.includes(role) || ownsStage(role, stage);
}

/** Only OWNER may move a card backward, to an earlier stage, to correct a mistake. */
export function canMoveBackward(role: Role | undefined): boolean {
  return role === 'OWNER';
}

/**
 * The next stage one step forward from `current`, given which optional steps are on in the
 * clinic's current published workflow (Vitals, Counselling). The server still decides what a
 * visit may do, using the version the visit started on.
 */
export function nextStage(current: VisitStage, vitalsEnabled: boolean, counselEnabled = false): VisitStage | null {
  switch (current) {
    case 'WAITING':
      return vitalsEnabled ? 'VITALS' : 'WITH_DOCTOR';
    case 'VITALS':
      return 'WITH_DOCTOR';
    case 'WITH_DOCTOR':
      return counselEnabled ? 'COUNSEL' : 'BILLING';
    case 'COUNSEL':
      return 'BILLING';
    case 'BILLING':
      return 'DONE';
    case 'DONE':
      return null;
  }
}

/** Earlier stages an owner could move a card back to, in forward order (acceptance criterion 3). */
export function earlierStages(current: VisitStage): VisitStage[] {
  const index = STAGE_ORDER.indexOf(current);
  return STAGE_ORDER.slice(0, index).filter((stage) => stage !== 'COUNSEL');
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
