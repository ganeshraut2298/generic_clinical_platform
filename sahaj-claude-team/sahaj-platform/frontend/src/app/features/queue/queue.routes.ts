import { Routes } from '@angular/router';

/** `/app/queue` (SAH-9), behind the shell's `queue` menu guard. */
export const QUEUE_ROUTES: Routes = [
  {
    path: '',
    title: 'Queue · Sahaj',
    loadComponent: () => import('./board/queue-board.page').then((m) => m.QueueBoardPage),
  },
];
