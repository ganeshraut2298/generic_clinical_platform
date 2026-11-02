import { Routes } from '@angular/router';

/** `/app/appointments` (SAH-11), behind the shell's `appointments` menu guard. */
export const APPOINTMENTS_ROUTES: Routes = [
  {
    path: '',
    title: 'Appointments · Sahaj',
    loadComponent: () => import('./board/appointments-board.page').then((m) => m.AppointmentsBoardPage),
  },
];
