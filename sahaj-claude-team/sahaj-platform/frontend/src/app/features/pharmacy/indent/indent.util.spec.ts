import { dispatch, indent, indentLine, returnItem, shortage } from './indent.fixtures';
import {
  acceptedResultText,
  ageText,
  dispatchQtyProblem,
  dispatchStatusChip,
  hodChangeText,
  indentChips,
  indentHeaderLine,
  indentStatusChip,
  lineDecisionText,
  pinProblem,
  qtyText,
  receiptNotes,
  returnStatusChip,
  shortageBanner,
  shortageStatusChip,
} from './indent.util';

describe('indent.util', () => {
  it('shows a null quantity as a dash', () => {
    expect(qtyText(null)).toBe('—');
    expect(qtyText(0)).toBe('0');
    expect(ageText(null)).toBe('—');
  });

  it('names the status chips as the stories say', () => {
    expect(indentStatusChip('DRAFT').text).toBe('Draft');
    expect(indentStatusChip('WAITING_APPROVAL').text).toBe('Waiting for HOD approval');
    expect(indentStatusChip('WAITING_APPROVAL', { waitingFor: 'OWNER' }).text).toBe('Waiting for owner approval');
    expect(indentStatusChip('APPROVED').text).toBe('Approved · with Central Store');
    expect(indentStatusChip('DISPATCHED').text).toBe('Dispatched · in transit');
    expect(indentStatusChip('REJECTED').text).toBe('Rejected');
    expect(indentStatusChip('CANCELLED').text).toBe('Cancelled');
  });

  it('labels a partly dispatched indent by what has been received (79-12)', () => {
    expect(indentStatusChip('PARTLY_DISPATCHED', { backOrderOpen: true }).text).toBe('Approved · back-order open');
    expect(
      indentStatusChip('PARTLY_DISPATCHED', { backOrderOpen: true, allDispatchesReceived: true, backOrderProduct: 'IV NS' }).text,
    ).toBe('Received · IV NS back-order open');
  });

  it('shows the Received label for a back-order only when every dispatch is received and nothing is rejected or short', () => {
    const base = indent({
      status: 'PARTLY_DISPATCHED',
      chips: { rejectedWaitingCs: 0, shortInvestigating: 0, backOrderOpen: true, scheduleXWaiting: 0 },
      dispatches: [{ dispatchId: 'd1', reference: 'IND-0001 / 1', status: 'RECEIVED', dispatchedAt: '2026-11-19T06:00:00Z' }],
      lines: [indentLine({ balanceAction: 'BACK_ORDER', remainingQty: 4 })],
    });
    expect(indentChips(base).map((c) => c.text)).toEqual(['Received · Sample Ceftriaxone 1 g injection back-order open']);
    const rejected = indent({ ...base, chips: { ...base.chips, rejectedWaitingCs: 2 } });
    expect(indentChips(rejected).map((c) => c.text)).toEqual([
      'Approved · back-order open',
      '· 2 rejected, waiting for CS',
    ]);
  });

  it('adds the rejected, short and Schedule X chips, and Escalated or Needs another approver while waiting', () => {
    const chips = indentChips(
      indent({
        status: 'PARTLY_DISPATCHED',
        chips: { rejectedWaitingCs: 1, shortInvestigating: 3, backOrderOpen: true, scheduleXWaiting: 2 },
      }),
    );
    expect(chips.map((c) => c.text)).toEqual([
      'Approved · back-order open',
      '· 1 rejected, waiting for CS',
      '· 3 short, CS investigating',
      '· Schedule X lines waiting for a pharmacist',
    ]);
  });

  it('writes the header line of the mockup', () => {
    expect(indentHeaderLine(indent({ priority: 'URGENT' }))).toBe(
      'IND-0001 · Test Ward 3B store → Test Central Store · Urgent · raised 19 Nov 2026, 10:00 AM',
    );
    expect(indentHeaderLine(indent({ number: null, raisedAt: null }))).toBe(
      'New indent · Test Ward 3B store → Test Central Store · Routine',
    );
  });

  it('shows what the HOD changed and the line decision', () => {
    expect(hodChangeText(4)).toBe('+4 by HOD');
    expect(hodChangeText(-5)).toBe('−5 by HOD');
    expect(hodChangeText(0)).toBe('—');
    expect(hodChangeText(null)).toBe('—');
    expect(lineDecisionText(0)).toBe('Reject');
    expect(lineDecisionText(3)).toBe('Approve');
  });

  it('checks the dispatch quantity against the remaining and the stock', () => {
    expect(dispatchQtyProblem('x', 10, 20)).toBe('Enter a whole number, 0 or more');
    expect(dispatchQtyProblem('11', 10, 20)).toContain('10 or less');
    expect(dispatchQtyProblem('8', 10, 5)).toBe('Only 5 in stock at the Central Store');
    expect(dispatchQtyProblem('8', 10, null)).toBeNull();
    expect(dispatchQtyProblem('0', 10, 0)).toBeNull();
  });

  it('words the dispatch, return and shortage statuses', () => {
    expect(dispatchStatusChip('PARTLY_RECEIVED').text).toBe('Partly received');
    expect(returnStatusChip('WAITING').text).toBe('Waiting for Central Store');
    expect(returnStatusChip('ACCEPTED_QUARANTINE').text).toBe('Accepted by CS · quarantine');
    expect(shortageStatusChip('OPEN').text).toBe('Short · under investigation');
    expect(shortageStatusChip('CLAIM_OPEN').text).toBe('Claim open');
  });

  it('writes the result and banner sentences', () => {
    expect(acceptedResultText(returnItem({ qty: 2, status: 'ACCEPTED_QUARANTINE' }))).toBe(
      'Return accepted. 2 units added to Central Store: quarantine.',
    );
    expect(acceptedResultText(returnItem({ qty: 2, status: 'ACCEPTED_SALEABLE' }))).toBe(
      'Return accepted. 2 units added to Central Store: saleable stock.',
    );
    expect(shortageBanner(shortage().qty, 'Test Ward 3B store')).toContain('3 units never reached Test Ward 3B store');
    expect(receiptNotes(2, 3, 'Test Ward 3B store')).toEqual([
      'These 3 units are not added to Test Ward 3B store stock and cannot be returned. Central Store will investigate.',
      'The 2 rejected units do not enter Test Ward 3B store stock. They wait for Central Store to accept them back.',
    ]);
    expect(receiptNotes(0, 0, 'x')).toEqual([]);
  });

  it('wants a 4-digit PIN', () => {
    expect(pinProblem('1234')).toBeNull();
    expect(pinProblem('12')).toBe('Enter a 4-digit PIN.');
    expect(pinProblem('12a4')).toBe('Enter a 4-digit PIN.');
    expect(dispatch().lines.length).toBe(2);
  });
});
