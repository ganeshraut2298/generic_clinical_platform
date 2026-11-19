import { Routes } from '@angular/router';
import { menuGuard } from '../../core/shell/menu.guard';
import { storeStaffLandingGuard } from '../pharmacy/pharmacy-landing';

/**
 * Everything behind the app shell (SAH-5). `today` has no menu guard: `/me.menu` always
 * includes it. Every other menu page is guarded by its key; the backend still enforces the
 * role on every request (acceptance criterion 11).
 */
export const SHELL_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'today' },
      {
        path: 'today',
        title: 'Today · Sahaj',
        // SAH-78: only a Store staff member (menu has `pharmacy`, no `today`) is sent on to Pharmacy & Inventory.
        canActivate: [storeStaffLandingGuard],
        loadComponent: () => import('./today/today.page').then((m) => m.TodayPage),
      },
      {
        path: 'queue',
        canActivate: [menuGuard('queue')],
        loadChildren: () => import('../queue/queue.routes').then((m) => m.QUEUE_ROUTES),
      },
      {
        path: 'appointments',
        canActivate: [menuGuard('appointments')],
        loadChildren: () =>
          import('../appointments/appointments.routes').then((m) => m.APPOINTMENTS_ROUTES),
      },
      {
        path: 'patients',
        canActivate: [menuGuard('patients')],
        loadChildren: () => import('../patients/patients.routes').then((m) => m.PATIENTS_ROUTES),
      },
      {
        path: 'followups',
        canActivate: [menuGuard('followups')],
        loadChildren: () => import('../followups/followups.routes').then((m) => m.FOLLOWUPS_ROUTES),
      },
      {
        path: 'billing',
        canActivate: [menuGuard('billing')],
        loadChildren: () => import('../billing/billing.routes').then((m) => m.BILLING_ROUTES),
      },
      {
        path: 'revenue',
        canActivate: [menuGuard('revenue')],
        loadChildren: () => import('../revenue/revenue.routes').then((m) => m.REVENUE_ROUTES),
      },
      {
        path: 'emr',
        loadChildren: () => import('../emr/emr.routes').then((m) => m.EMR_ROUTES),
      },
      {
        path: 'courses',
        loadChildren: () => import('../emr/course/course.routes').then((m) => m.COURSE_ROUTES),
      },
      {
        path: 'staff',
        loadChildren: () => import('../staff/staff.routes').then((m) => m.STAFF_ROUTES),
      },
      {
        path: 'ipd',
        loadChildren: () => import('../ipd/ipd.routes').then((m) => m.IPD_ROUTES),
      },
      {
        path: 'emergency',
        canActivate: [menuGuard('emergency')],
        loadChildren: () => import('../ipd/ipd.routes').then((m) => m.EMERGENCY_ROUTES),
      },
      {
        // SAH-78 (ADR-028 D11). No menu guard: the page asks the server (GET /context) and says
        // "You do not have access to Pharmacy & Inventory. Ask your owner." itself.
        path: 'pharmacy',
        title: 'Pharmacy & Inventory · Sahaj',
        loadChildren: () => import('../pharmacy/pharmacy.routes').then((m) => m.PHARMACY_ROUTES),
      },
      {
        path: 'reports',
        title: 'Reports · Sahaj',
        canActivate: [menuGuard('reports')],
        data: { title: 'Reports' },
        loadComponent: () =>
          import('./placeholder/menu-placeholder.page').then((m) => m.MenuPlaceholderPage),
      },
      {
        path: 'config',
        title: 'Clinic setup · Sahaj',
        canActivate: [menuGuard('config')],
        loadChildren: () => import('../config/config.routes').then((m) => m.CONFIG_ROUTES),
      },
      {
        path: 'not-allowed',
        title: 'Not allowed · Sahaj',
        loadComponent: () => import('./not-allowed/not-allowed.page').then((m) => m.NotAllowedPage),
      },
      { path: '**', redirectTo: 'today' },
    ],
  },
];
