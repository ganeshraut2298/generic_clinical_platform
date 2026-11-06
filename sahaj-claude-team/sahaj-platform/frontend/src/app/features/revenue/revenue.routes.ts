import { Routes } from '@angular/router';

/** `/app/revenue` (SAH-16), behind the shell's `revenue` menu guard. */
export const REVENUE_ROUTES: Routes = [
  {
    path: '',
    title: 'Revenue · Sahaj',
    loadComponent: () => import('./report/revenue.page').then((m) => m.RevenuePage),
  },
];
