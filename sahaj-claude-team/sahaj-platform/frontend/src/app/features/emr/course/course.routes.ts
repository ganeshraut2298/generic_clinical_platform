import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { ShellStore } from '../../../core/shell/shell.store';

const COURSE_ROLES: ReadonlySet<string> = new Set(['OWNER', 'DOCTOR', 'VISITING_DOCTOR', 'FRONT_DESK', 'BILLING']);

/**
 * A treatment course is read by the owner, doctors, front desk and billing (SAH-58-1). Nurse,
 * pharmacist and counsellor get the "not allowed" page. A UI convenience only: every call checks
 * the role again on the server.
 */
export const courseRoleGuard: CanActivateFn = () => {
  const role = inject(ShellStore).me()?.role;
  return role && COURSE_ROLES.has(role) ? true : inject(Router).createUrlTree(['/app/not-allowed']);
};

/** `/app/courses/:courseId` (SAH-58): one treatment course with its sittings and its running account. */
export const COURSE_ROUTES: Routes = [
  {
    path: ':courseId',
    title: 'Treatment course · Sahaj',
    canActivate: [courseRoleGuard],
    loadComponent: () => import('./course.page').then((m) => m.CoursePage),
  },
];
