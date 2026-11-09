import { CanDeactivateFn } from '@angular/router';
import { Observable } from 'rxjs';

/** What the page tells the router when the owner tries to leave with edits that are not saved (SAH-37 AC4). */
export interface LeaveAware {
  confirmLeave(): Observable<boolean> | boolean;
}

/**
 * Warns before leaving Clinic setup → Patient card with unsaved edits. If the owner leaves, the unsaved
 * edits are lost; a saved draft is kept on the server.
 */
export const ipCardLeaveGuard: CanDeactivateFn<LeaveAware> = (page) => page.confirmLeave();
