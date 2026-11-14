import { inject } from '@angular/core';
import { CanActivateFn, CanDeactivateFn, Router } from '@angular/router';
import { Observable } from 'rxjs';
import { ShellStore } from '../../core/shell/shell.store';

const DOCTOR_ROLES: ReadonlySet<string> = new Set(['OWNER', 'DOCTOR', 'VISITING_DOCTOR']);

/**
 * The EMR screen is for doctors, visiting doctors and the owner (SAH-46-1). A UI convenience only:
 * every EMR call checks the role and the doctor details again on the server.
 */
export const emrRoleGuard: CanActivateFn = () => {
  const role = inject(ShellStore).me()?.role;
  return role && DOCTOR_ROLES.has(role) ? true : inject(Router).createUrlTree(['/app/not-allowed']);
};

const CHECKER_ROLES: ReadonlySet<string> = new Set(['OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'NURSE', 'PHARMACIST']);

/**
 * "Second checks" (SAH-66-3): the people who can be named as the independent second checker for a
 * chemotherapy order. A UI convenience only: the list and the confirmation check the role and the
 * named membership again on the server, and no EMR record is opened from there.
 */
export const chemoCheckerGuard: CanActivateFn = () => {
  const role = inject(ShellStore).me()?.role;
  return role && CHECKER_ROLES.has(role) ? true : inject(Router).createUrlTree(['/app/not-allowed']);
};

/** What the EMR screen tells the router when the doctor leaves with unsaved entries (SAH-47-5). */
export interface EmrLeaveAware {
  confirmLeave(): Observable<boolean> | boolean;
}

export const emrLeaveGuard: CanDeactivateFn<EmrLeaveAware> = (page) => page.confirmLeave();
