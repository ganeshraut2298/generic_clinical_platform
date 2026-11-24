-- DEC-095: the contract event name H1_OVERRIDE has a digit; the old pattern ^[A-Z][A-Z_]{1,39}$ refused it (500).
-- Widening only: no data change, no grant change, RLS policies and FORCE RLS untouched.
-- doc_type checks (document_event, stock_ledger) are not widened: no contract doc type has a digit.
alter table pharmacy.document_event drop constraint if exists ck_document_event_event;
alter table pharmacy.document_event add constraint ck_document_event_event check (event ~ '^[A-Z][A-Z0-9_]{1,39}$');
