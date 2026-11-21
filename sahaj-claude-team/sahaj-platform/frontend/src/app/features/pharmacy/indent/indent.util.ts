import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { Chip } from '../pharmacy-display.util';
import {
  DispatchStatus,
  Indent,
  IndentChips,
  IndentLine,
  IndentListRow,
  IndentPriority,
  IndentStatus,
  ReturnItem,
  ReturnItemStatus,
  ReturnItemType,
  ShortageStatus,
} from '../pharmacy-indent.model';
import { expiryMonthYear, waitingText } from '../inventory/inventory.util';

/** A null quantity is a dash; "Counting in progress" is shown by the screens that know the reason (79-9, rule 24). */
export function qtyText(value: number | null): string {
  return value === null ? '—' : String(value);
}

export const COUNTING_IN_PROGRESS = 'Counting in progress';

export const INDENT_STATUS_FILTER: readonly { value: IndentStatus; label: string }[] = [
  { value: 'DRAFT', label: 'Draft' },
  { value: 'WAITING_APPROVAL', label: 'Waiting for approval' },
  { value: 'APPROVED', label: 'Approved · with Central Store' },
  { value: 'PARTLY_DISPATCHED', label: 'Partly dispatched' },
  { value: 'DISPATCHED', label: 'Dispatched · in transit' },
  { value: 'RECEIVED', label: 'Received' },
  { value: 'CLOSED', label: 'Closed' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

export function priorityText(priority: IndentPriority): string {
  return priority === 'URGENT' ? 'Urgent' : 'Routine';
}

export function priorityChip(priority: IndentPriority): Chip {
  return priority === 'URGENT' ? { text: 'Urgent', css: 'p-crit' } : { text: 'Routine', css: 'p-blue' };
}

/** The main status chip of an indent. `waitingFor` tells "Waiting for HOD approval" from "... owner approval". */
export function indentStatusChip(
  status: IndentStatus,
  options: { waitingFor?: 'HOD' | 'OWNER' | null; backOrderOpen?: boolean; allDispatchesReceived?: boolean; backOrderProduct?: string | null } = {},
): Chip {
  switch (status) {
    case 'DRAFT':
      return { text: 'Draft', css: 'p-blue' };
    case 'WAITING_APPROVAL':
      return {
        text: options.waitingFor === 'OWNER' ? 'Waiting for owner approval' : 'Waiting for HOD approval',
        css: 'p-wait',
      };
    case 'APPROVED':
      return { text: 'Approved · with Central Store', css: 'p-teal' };
    case 'PARTLY_DISPATCHED':
      return partlyDispatchedChip(options);
    case 'DISPATCHED':
      return { text: 'Dispatched · in transit', css: 'p-wait' };
    case 'RECEIVED':
      return { text: 'Received', css: 'p-good' };
    case 'CLOSED':
      return { text: 'Closed', css: 'p-good' };
    case 'REJECTED':
      return { text: 'Rejected', css: 'p-crit' };
    case 'CANCELLED':
      return { text: 'Cancelled', css: 'p-blue' };
    case 'DISCARDED':
      return { text: 'Discarded', css: 'p-blue' };
  }
}

/**
 * An indent with a back-order open stays "Partly dispatched" (contract 0.3.3). The mockup's label for it is
 * "Received · <product> back-order open" once every dispatch so far is received, and "Approved · back-order open"
 * (or "Dispatched · in transit") before that (story 79-12).
 */
function partlyDispatchedChip(options: {
  backOrderOpen?: boolean;
  allDispatchesReceived?: boolean;
  backOrderProduct?: string | null;
}): Chip {
  if (options.backOrderOpen === false) {
    return { text: 'Partly dispatched', css: 'p-wait' };
  }
  if (options.allDispatchesReceived) {
    return {
      text: options.backOrderProduct ? `Received · ${options.backOrderProduct} back-order open` : 'Received · back-order open',
      css: 'p-wait',
    };
  }
  return { text: 'Approved · back-order open', css: 'p-wait' };
}

/**
 * The status chip row of one indent: the main chip, then the chips "· n rejected, waiting for CS" and
 * "· n short, CS investigating". The rejected and short chips win over the back-order label when both apply, and
 * the Schedule X chip is added while Schedule X dispatch lines wait for a pharmacist (contract 0.3.4).
 */
export function indentChips(indent: Indent): Chip[] {
  const chips: IndentChips = indent.chips;
  const rejectedOrShort = chips.rejectedWaitingCs > 0 || chips.shortInvestigating > 0;
  const allReceived = indent.dispatches.length > 0 && indent.dispatches.every((d) => d.status === 'RECEIVED');
  const open = indent.lines.find((l) => l.balanceAction === 'BACK_ORDER' && l.remainingQty > 0);
  const main = indentStatusChip(indent.status, {
    waitingFor: indent.approval?.waitingFor ?? null,
    backOrderOpen: chips.backOrderOpen,
    allDispatchesReceived: allReceived && !rejectedOrShort,
    backOrderProduct: open ? open.product.name : null,
  });
  // The rejected and short chips win over the back-order label (mockup 10): "Received" plus the chip.
  const winsOver = rejectedOrShort && allReceived && indent.status === 'PARTLY_DISPATCHED';
  const list: Chip[] = [winsOver ? { text: 'Received', css: 'p-good' } : main];
  if (chips.rejectedWaitingCs > 0) {
    list.push({ text: `· ${chips.rejectedWaitingCs} rejected, waiting for CS`, css: 'p-warn' });
  }
  if (chips.shortInvestigating > 0) {
    list.push({ text: `· ${chips.shortInvestigating} short, CS investigating`, css: 'p-warn' });
  }
  if (chips.scheduleXWaiting > 0) {
    list.push({ text: '· Schedule X lines waiting for a pharmacist', css: 'p-warn' });
  }
  if (indent.approval?.escalated && indent.status === 'WAITING_APPROVAL') {
    list.push({ text: 'Escalated', css: 'p-crit' });
  }
  if (indent.approval?.needsAnotherApprover && indent.status === 'WAITING_APPROVAL') {
    list.push({ text: 'Needs another approver', css: 'p-warn' });
  }
  return list;
}

/** The list row has no chips of its own, only the status. */
export function indentRowChip(row: IndentListRow): Chip {
  return indentStatusChip(row.status);
}

export function dispatchStatusChip(status: DispatchStatus): Chip {
  switch (status) {
    case 'IN_TRANSIT':
      return { text: 'In transit', css: 'p-wait' };
    case 'PARTLY_RECEIVED':
      return { text: 'Partly received', css: 'p-warn' };
    case 'RECEIVED':
      return { text: 'Received', css: 'p-good' };
  }
}

export function ageText(minutes: number | null): string {
  return minutes === null ? '—' : waitingText(minutes);
}

export function whenText(iso: string | null): string {
  return iso === null ? '—' : formatDisplayDateTimeOfInstant(iso);
}

/** The header line of the raise card: "<IND-…> · <from> → <to> · <priority> · raised <date and time>". */
export function indentHeaderLine(indent: Indent): string {
  const parts = [
    indent.number ?? 'New indent',
    `${indent.fromStore.name} → ${indent.toStore.name}`,
    priorityText(indent.priority),
  ];
  if (indent.raisedAt) {
    parts.push(`raised ${formatDisplayDateTimeOfInstant(indent.raisedAt)}`);
  }
  return parts.join(' · ');
}

/** "+4 by HOD" or "−5 by HOD"; a dash when the HOD changed nothing. */
export function hodChangeText(change: number | null): string {
  if (change === null || change === 0) {
    return '—';
  }
  return change > 0 ? `+${change} by HOD` : `−${Math.abs(change)} by HOD`;
}

export function lineDecisionText(approvedQty: number | null): string {
  return approvedQty === 0 ? 'Reject' : 'Approve';
}

/** The pick shown on the dispatch card: batches are shown as "Mar 2028" (OQ-79-7). */
export function batchWithExpiry(batchNo: string, expiryDate: string): string {
  return `${batchNo} · ${expiryMonthYear(expiryDate)}`;
}

/** The dispatch quantity must be a whole number from 0 to the smaller of the remaining and the available quantity. */
export function dispatchQtyProblem(value: string, remaining: number, available: number | null): string | null {
  const text = value.trim();
  if (!/^\d+$/.test(text)) {
    return 'Enter a whole number, 0 or more';
  }
  const qty = Number(text);
  if (qty > remaining) {
    return `Enter ${remaining} or less (the remaining approved quantity)`;
  }
  if (available !== null && qty > available) {
    return `Only ${available} in stock at the Central Store`;
  }
  return null;
}

export function lineBalance(line: Pick<IndentLine, 'remainingQty'>, dispatchQty: number): number {
  return Math.max(line.remainingQty - dispatchQty, 0);
}

const RETURN_TYPE_LABELS: Record<ReturnItemType, string> = {
  INDENT_REJECTION: 'Indent rejection',
  TRANSFER_REJECTION: 'Transfer rejection',
  RETURN_TO_CS: 'Return to CS',
  DAMAGED: 'Damaged',
};

export function returnTypeText(type: ReturnItemType): string {
  return RETURN_TYPE_LABELS[type];
}

export function returnStatusChip(status: ReturnItemStatus): Chip {
  switch (status) {
    case 'WAITING':
      return { text: 'Waiting for Central Store', css: 'p-wait' };
    case 'DISPUTED':
      return { text: 'Disputed', css: 'p-warn' };
    case 'ACCEPTED_SALEABLE':
      return { text: 'Accepted by CS · saleable', css: 'p-good' };
    case 'ACCEPTED_QUARANTINE':
      return { text: 'Accepted by CS · quarantine', css: 'p-teal' };
    case 'WAITING_HOD':
      return { text: 'Waiting for HOD', css: 'p-wait' };
  }
}

export function shortageStatusChip(status: ShortageStatus): Chip {
  switch (status) {
    case 'OPEN':
      return { text: 'Short · under investigation', css: 'p-warn' };
    case 'CLAIM_OPEN':
      return { text: 'Claim open', css: 'p-wait' };
    case 'WAITING_WRITE_OFF':
      return { text: 'Waiting for write-off approval', css: 'p-wait' };
    case 'FOUND':
      return { text: 'Found in Central Store', css: 'p-good' };
    case 'WRITTEN_OFF':
      return { text: 'Written off · transit loss', css: 'p-blue' };
  }
}

export function isOpenShortage(status: ShortageStatus): boolean {
  return status === 'OPEN' || status === 'CLAIM_OPEN';
}

/** "Accept <n> units back" and the result panel (story 79-13). */
export function acceptedResultText(item: Pick<ReturnItem, 'qty' | 'status'>): string {
  return item.status === 'ACCEPTED_SALEABLE'
    ? `Return accepted. ${item.qty} units added to Central Store: saleable stock.`
    : `Return accepted. ${item.qty} units added to Central Store: quarantine.`;
}

export function shortageBanner(qty: number, storeName: string): string {
  return `Shortage, not a return. ${qty} units never reached ${storeName}, so nothing comes back to the shelf. Central Store investigates: found in store, transit loss, or claim from carrier.`;
}

/** The note shown under the receipt table for rejected and short units (story 79-12). */
export function receiptNotes(rejected: number, short: number, storeName: string): string[] {
  const notes: string[] = [];
  if (short > 0) {
    notes.push(
      `These ${short} units are not added to ${storeName} stock and cannot be returned. Central Store will investigate.`,
    );
  }
  if (rejected > 0) {
    notes.push(`The ${rejected} rejected units do not enter ${storeName} stock. They wait for Central Store to accept them back.`);
  }
  return notes;
}

/** The PIN of the second signer is four digits (contract 0.3.4, L17). */
export function pinProblem(pin: string): string | null {
  return /^[0-9]{4}$/.test(pin) ? null : 'Enter a 4-digit PIN.';
}
