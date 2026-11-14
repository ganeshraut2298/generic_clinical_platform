import { EmrListItem } from '../emr-phase3.model';
import {
  LIST_KINDS,
  activeItems,
  capitalise,
  choiceLabel,
  countText,
  emptyListValue,
  hasText,
  isLockedByBill,
  itemsOf,
  newListItem,
  showsCode,
  withItems,
} from './emr-list.logic';

function item(over: Partial<EmrListItem> = {}): EmrListItem {
  return { id: 'i1', itemKey: 'k1', text: 'Fever', newToList: false, attr1: 'D3', attr2: 'MODERATE', active: true, ...over };
}

describe('EMR list rules (SAH-56)', () => {
  describe('the five kinds', () => {
    it('has the mockup labels, placeholders and empty texts', () => {
      expect(LIST_KINDS.COMPLAINTS.nameLabel).toBe('Complaint');
      expect(LIST_KINDS.COMPLAINTS.placeholder).toBe('Type a complaint, e.g. fever');
      expect(LIST_KINDS.DIAGNOSIS.placeholder).toBe('Type a diagnosis or ICD-10 code');
      expect(LIST_KINDS.INVESTIGATIONS.placeholder).toBe('Type a test, e.g. CBC');
      expect(LIST_KINDS.ADVICE.placeholder).toBe('Type advice, e.g. drink plenty of water');
      expect(LIST_KINDS.SERVICES.placeholder).toBe('Type a procedure or service');
      expect(LIST_KINDS.COMPLAINTS.empty).toBe('No complaint added yet. Tap a most-used one below or + Add.');
      expect(LIST_KINDS.DIAGNOSIS.empty).toBe('No diagnosis yet. It is needed to complete the visit.');
      expect(LIST_KINDS.INVESTIGATIONS.empty).toBe('No tests ordered.');
      expect(LIST_KINDS.ADVICE.empty).toBe('No advice yet. Tap a most-used one below.');
      expect(LIST_KINDS.SERVICES.empty).toBe('Nothing added. Consultation fee is billed by the front desk.');
    });

    it('lists the eight durations in order, ending with "Over 1 month"', () => {
      expect(LIST_KINDS.COMPLAINTS.attr1.choices.map((c) => c.label)).toEqual([
        '1 day',
        '2 days',
        '3 days',
        '5 days',
        '1 week',
        '2 weeks',
        '1 month',
        'Over 1 month',
      ]);
    });

    it('has the attribute names and defaults of every kind', () => {
      const names = Object.values(LIST_KINDS).map((k) => [k.attr1.label, k.attr2.label, k.attr1.default, k.attr2.default]);
      expect(names).toEqual([
        ['Duration', 'Severity', 'D3', 'MODERATE'],
        ['Type', 'Status', 'PROVISIONAL', 'ACTIVE'],
        ['Priority', 'Where', 'ROUTINE', 'CLINIC_LAB'],
        ['Language', 'Send by', 'EN', 'PRINT'],
        ['Qty', 'Billing', '1', 'BILL_TODAY'],
      ]);
    });

    it('reads a label from a key and falls back to the key', () => {
      expect(choiceLabel(LIST_KINDS.COMPLAINTS.attr2, 'SEVERE')).toBe('Severe');
      expect(choiceLabel(LIST_KINDS.COMPLAINTS.attr2, 'ODD')).toBe('ODD');
    });
  });

  describe('count and active items', () => {
    it('counts active items, and disabled ones when some are switched off', () => {
      expect(countText([item(), item({ id: 'i2' })])).toBe('2');
      expect(countText([item(), item({ id: 'i2', active: false })])).toBe('1 active · 1 disabled');
      expect(countText([])).toBe('0');
    });

    it('leaves out disabled items', () => {
      expect(activeItems([item(), item({ id: 'i2', active: false })]).map((i) => i.id)).toEqual(['i1']);
    });

    it('matches a name ignoring case and spaces', () => {
      expect(hasText([item()], '  fever ')).toBe(true);
      expect(hasText([item()], 'cough')).toBe(false);
    });
  });

  describe('a new item', () => {
    it('carries the defaults of its kind and an id made by the client', () => {
      const a = newListItem('COMPLAINTS', { itemKey: 'k', text: 'Fever', newToList: false });
      expect(a).toMatchObject({ itemKey: 'k', text: 'Fever', newToList: false, attr1: 'D3', attr2: 'MODERATE', active: true });
      expect(a.id).toMatch(/^[0-9a-f-]{36}$/);
      expect(newListItem('COMPLAINTS', { itemKey: 'k', text: 'Fever', newToList: false }).id).not.toBe(a.id);
    });

    it('starts Advice in the visit language', () => {
      expect(newListItem('ADVICE', { itemKey: 'a', text: 'Low salt diet', newToList: false }, 'MR').attr1).toBe('MR');
      expect(newListItem('ADVICE', { itemKey: 'a', text: 'Low salt diet', newToList: false }).attr1).toBe('EN');
    });

    it('capitalises a typed item and tags it new to the list', () => {
      const typed = newListItem('COMPLAINTS', { itemKey: null, text: 'ear fullness', newToList: true });
      expect(typed.text).toBe('Ear fullness');
      expect(typed.newToList).toBe(true);
      expect(typed.itemKey).toBeNull();
      expect(capitalise('  x')).toBe('X');
    });

    it('keeps the ICD-10 code of a diagnosis from the master and none for a typed one', () => {
      expect(newListItem('DIAGNOSIS', { itemKey: 'I10', text: 'Hypertension', newToList: false, code: 'I10' }).code).toBe('I10');
      expect(newListItem('DIAGNOSIS', { itemKey: null, text: 'my dx', newToList: true }).code).toBeUndefined();
    });

    it('shows a code only for a diagnosis that has one', () => {
      expect(showsCode('DIAGNOSIS', item({ code: 'I10' }))).toBe(true);
      expect(showsCode('DIAGNOSIS', item())).toBe(false);
      expect(showsCode('COMPLAINTS', item({ code: 'I10' }))).toBe(false);
    });
  });

  describe('value helpers', () => {
    it('builds an empty value and reads items from any value', () => {
      expect(emptyListValue('ADVICE')).toEqual({ component: 'EMR_LIST', kind: 'ADVICE', schemaVersion: 1, items: [] });
      expect(itemsOf(null)).toEqual([]);
      expect(itemsOf({ items: [item()] })).toHaveLength(1);
    });

    it('replaces the items and keeps the component shape', () => {
      const v = withItems(null, 'SERVICES', [item()]);
      expect(v).toMatchObject({ component: 'EMR_LIST', kind: 'SERVICES', schemaVersion: 1 });
      expect(v.items).toHaveLength(1);
    });

    it('locks a service on a paid bill only', () => {
      expect(isLockedByBill('SERVICES', item({ billed: 'ON_PAID_BILL' }))).toBe(true);
      expect(isLockedByBill('SERVICES', item({ billed: 'ON_BILL' }))).toBe(false);
      expect(isLockedByBill('COMPLAINTS', item({ billed: 'ON_PAID_BILL' }))).toBe(false);
    });
  });
});
