import { Routes } from '@angular/router';

/**
 * `/app/ipd` (SAH-23). Not behind `menuGuard`: when IPD is off the page itself shows the lock
 * notice (the API answers 403 `module-off`) instead of redirecting, as Staff does.
 */
export const IPD_ROUTES: Routes = [
  {
    path: '',
    title: 'Beds and admissions · Sahaj',
    loadComponent: () => import('./ipd.page').then((m) => m.IpdPage),
  },
];

/**
 * `/app/emergency` (SAH-28, menu key `emergency`): the same page opened on the Emergency tab, as
 * the mockup does (`VIEWS.emergency`). The menu guard is added where the route is mounted.
 */
export const EMERGENCY_ROUTES: Routes = [
  {
    path: '',
    title: 'Emergency · Sahaj',
    data: { tab: 'er' },
    loadComponent: () => import('./ipd.page').then((m) => m.IpdPage),
  },
];
