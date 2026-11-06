import { Routes } from '@angular/router';

/** `/app/followups` (SAH-14), behind the shell's `followups` menu guard. */
export const FOLLOWUPS_ROUTES: Routes = [
  {
    path: '',
    title: 'Follow-ups · Sahaj',
    loadComponent: () => import('./list/followups.page').then((m) => m.FollowUpsPage),
  },
];
