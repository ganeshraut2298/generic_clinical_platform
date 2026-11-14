import { newClientKey } from '../../../shared/client-key';
import { EmrListKind, EmrMaster } from '../emr.model';
import { EmrListItem, EmrListValue, PrintLanguage } from '../emr-phase3.model';

/**
 * Pure rules of the EMR list (SAH-56; mockup #EmrList). The five kinds, their two attributes with
 * the fixed value sets and defaults, the empty texts and the placeholders are the mockup's
 * (DEC-039: value from the EMR mockup, accepted by the human, not clinically reviewed).
 */

export interface AttrChoice {
  readonly key: string;
  readonly label: string;
}

export interface AttrSpec {
  readonly label: string;
  readonly choices: readonly AttrChoice[];
  readonly default: string;
}

export interface ListKindSpec {
  readonly kind: EmrListKind;
  readonly noun: string;
  readonly nameLabel: string;
  readonly attr1: AttrSpec;
  readonly attr2: AttrSpec;
  readonly placeholder: string;
  readonly empty: string;
  readonly master: EmrMaster | null;
}

const c = (key: string, label: string): AttrChoice => ({ key, label });

export const LIST_KINDS: Record<EmrListKind, ListKindSpec> = {
  COMPLAINTS: {
    kind: 'COMPLAINTS',
    noun: 'complaint',
    nameLabel: 'Complaint',
    attr1: {
      label: 'Duration',
      choices: [
        c('D1', '1 day'),
        c('D2', '2 days'),
        c('D3', '3 days'),
        c('D5', '5 days'),
        c('W1', '1 week'),
        c('W2', '2 weeks'),
        c('M1', '1 month'),
        c('OVER_M1', 'Over 1 month'),
      ],
      default: 'D3',
    },
    attr2: { label: 'Severity', choices: [c('MILD', 'Mild'), c('MODERATE', 'Moderate'), c('SEVERE', 'Severe')], default: 'MODERATE' },
    placeholder: 'Type a complaint, e.g. fever',
    empty: 'No complaint added yet. Tap a most-used one below or + Add.',
    master: 'complaints',
  },
  DIAGNOSIS: {
    kind: 'DIAGNOSIS',
    noun: 'diagnosis',
    nameLabel: 'Diagnosis',
    attr1: {
      label: 'Type',
      choices: [c('PROVISIONAL', 'Provisional'), c('FINAL', 'Final'), c('DIFFERENTIAL', 'Differential'), c('COMORBIDITY', 'Comorbidity')],
      default: 'PROVISIONAL',
    },
    attr2: { label: 'Status', choices: [c('ACTIVE', 'Active'), c('CHRONIC', 'Chronic'), c('RESOLVED', 'Resolved')], default: 'ACTIVE' },
    placeholder: 'Type a diagnosis or ICD-10 code',
    empty: 'No diagnosis yet. It is needed to complete the visit.',
    master: 'diagnoses',
  },
  INVESTIGATIONS: {
    kind: 'INVESTIGATIONS',
    noun: 'test',
    nameLabel: 'Test',
    attr1: {
      label: 'Priority',
      choices: [c('ROUTINE', 'Routine'), c('URGENT', 'Urgent'), c('BEFORE_NEXT_VISIT', 'Before next visit')],
      default: 'ROUTINE',
    },
    attr2: { label: 'Where', choices: [c('CLINIC_LAB', 'Clinic lab'), c('OUTSIDE_LAB', 'Outside lab')], default: 'CLINIC_LAB' },
    placeholder: 'Type a test, e.g. CBC',
    empty: 'No tests ordered.',
    master: 'investigations',
  },
  ADVICE: {
    kind: 'ADVICE',
    noun: 'advice',
    nameLabel: 'Advice',
    attr1: { label: 'Language', choices: [c('EN', 'English'), c('MR', 'Marathi'), c('HI', 'Hindi')], default: 'EN' },
    attr2: {
      label: 'Send by',
      choices: [c('PRINT', 'Print'), c('WHATSAPP', 'WhatsApp'), c('PRINT_WHATSAPP', 'Print + WhatsApp')],
      default: 'PRINT',
    },
    placeholder: 'Type advice, e.g. drink plenty of water',
    empty: 'No advice yet. Tap a most-used one below.',
    master: 'advice',
  },
  SERVICES: {
    kind: 'SERVICES',
    noun: 'service',
    nameLabel: 'Procedure / service',
    attr1: { label: 'Qty', choices: [c('1', '1'), c('2', '2'), c('3', '3'), c('4', '4')], default: '1' },
    attr2: {
      label: 'Billing',
      choices: [c('BILL_TODAY', 'Bill today'), c('NO_CHARGE', 'No charge'), c('IN_PACKAGE', 'In package')],
      default: 'BILL_TODAY',
    },
    placeholder: 'Type a procedure or service',
    empty: 'Nothing added. Consultation fee is billed by the front desk.',
    master: null,
  },
};

/** "Send by" choices that need the WhatsApp service: shown disabled until DEC-040 (SAH-56-2). */
export const WHATSAPP_SEND_BY: ReadonlySet<string> = new Set(['WHATSAPP', 'PRINT_WHATSAPP']);
export const WHATSAPP_NOTE = 'Needs the WhatsApp service';
export const ALREADY_BILLED_TEXT = 'Already billed. Ask billing to adjust.';
export const LIST_MAX = 50;
export const ITEM_TEXT_MAX = 200;

export function choiceLabel(spec: AttrSpec, key: string): string {
  return spec.choices.find((x) => x.key === key)?.label ?? key;
}

export function emptyListValue(kind: EmrListKind): EmrListValue {
  return { component: 'EMR_LIST', kind, schemaVersion: 1, items: [] };
}

export function itemsOf(value: unknown): EmrListItem[] {
  const v = value as Partial<EmrListValue> | null;
  return v && Array.isArray(v.items) ? v.items : [];
}

export function activeItems(items: readonly EmrListItem[]): EmrListItem[] {
  return items.filter((i) => i.active);
}

/** "3 active", or "3 active · 1 disabled" when some are switched off. */
export function countText(items: readonly EmrListItem[]): string {
  const active = activeItems(items).length;
  const off = items.length - active;
  return off > 0 ? `${active} active · ${off} disabled` : `${active} active`;
}

export function sameText(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase();
}

export function hasText(items: readonly EmrListItem[], text: string): boolean {
  return items.some((i) => sameText(i.text, text));
}

export function capitalise(text: string): string {
  const t = text.trim();
  return t.charAt(0).toUpperCase() + t.slice(1);
}

export interface NewItemInput {
  itemKey: string | null;
  text: string;
  newToList: boolean;
  /** Diagnosis from the master: shown beside the name until the server stamps it. */
  code?: string | null;
  unitPricePaise?: number | null;
}

/** A new item with the defaults of its kind; the Advice language defaults to the visit's print language. */
export function newListItem(kind: EmrListKind, input: NewItemInput, defaultLanguage: PrintLanguage = 'EN'): EmrListItem {
  const spec = LIST_KINDS[kind];
  const item: EmrListItem = {
    id: newClientKey(),
    itemKey: input.itemKey,
    text: input.newToList ? capitalise(input.text) : input.text,
    newToList: input.newToList,
    attr1: kind === 'ADVICE' ? defaultLanguage : spec.attr1.default,
    attr2: spec.attr2.default,
    active: true,
  };
  if (input.code) {
    item.code = input.code;
  }
  if (input.unitPricePaise !== undefined && input.unitPricePaise !== null) {
    item.unitPricePaise = input.unitPricePaise;
  }
  return item;
}

export function withItems(value: EmrListValue | null, kind: EmrListKind, items: EmrListItem[]): EmrListValue {
  return { ...(value ?? emptyListValue(kind)), component: 'EMR_LIST', kind, schemaVersion: 1, items };
}

/** The diagnosis code is shown only for a diagnosis from the master (a typed one has none). */
export function showsCode(kind: EmrListKind, item: EmrListItem): boolean {
  return kind === 'DIAGNOSIS' && !!item.code;
}

/** Services on an already paid visit bill cannot be removed or changed here (SAH-48-4). */
export function isLockedByBill(kind: EmrListKind, item: EmrListItem): boolean {
  return kind === 'SERVICES' && item.billed === 'ON_PAID_BILL';
}
