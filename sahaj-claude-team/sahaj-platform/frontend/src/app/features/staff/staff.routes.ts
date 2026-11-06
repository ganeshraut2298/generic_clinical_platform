import { Routes } from '@angular/router';

/**
 * `/app/staff` (SAH-20). Not behind `menuGuard`: when the HRA add-on is off the page itself
 * shows the lock page (acceptance criterion 1) instead of redirecting.
 */
export const STAFF_ROUTES: Routes = [
  {
    path: '',
    title: 'Staff and leave · Sahaj',
    loadComponent: () => import('./staff.page').then((m) => m.StaffPage),
  },
];
