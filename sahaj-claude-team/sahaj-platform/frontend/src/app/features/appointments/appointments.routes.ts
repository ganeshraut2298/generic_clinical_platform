import { Routes } from '@angular/router';

/** `/app/appointments` (SAH-11), behind the shell's `appointments` menu guard. */
export const APPOINTMENTS_ROUTES: Routes = [
  {
    // SAH-90: the front desk console. The old board stays at the empty path.
    path: 'desk',
    title: 'Front desk console · Sahaj',
    loadComponent: () => import('./desk/desk-console.page').then((m) => m.DeskConsolePage),
  },
  {
    path: '',
    title: 'Appointments · Sahaj',
    loadComponent: () => import('./board/appointments-board.page').then((m) => m.AppointmentsBoardPage),
  },
];
