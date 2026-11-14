import { Routes } from '@angular/router';
import { chemoCheckerGuard, emrLeaveGuard, emrRoleGuard } from './emr.guards';

/** `/app/emr/:recordId` (SAH-46, SAH-47): the EMR of one record, opened from a card. */
export const EMR_ROUTES: Routes = [
  {
    path: 'second-checks',
    title: 'Second checks · Sahaj',
    canActivate: [chemoCheckerGuard],
    loadComponent: () => import('./specialty/checks/chemo-checks.page').then((m) => m.ChemoChecksPage),
  },
  {
    path: ':recordId/past/:pastRecordId',
    title: 'Earlier visit · Sahaj',
    canActivate: [emrRoleGuard],
    loadComponent: () => import('./record/emr-record.page').then((m) => m.EmrRecordPage),
  },
  {
    path: ':recordId',
    title: 'EMR · Sahaj',
    canActivate: [emrRoleGuard],
    canDeactivate: [emrLeaveGuard],
    loadComponent: () => import('./record/emr-record.page').then((m) => m.EmrRecordPage),
  },
];
