-- SAH-79 WP-D, migration D2c. ADR-029 P3 (append-only ledger), P7 (second signer), P15 (monthly partitions), "Data needed" D2.
-- New table pharmacy.stock_ledger, range-partitioned by month on occurred_at. This file creates the parent only; the helper function
-- and the monthly partitions are in V2026_12_17_1340. Until the next migration runs, the table has no partition and refuses inserts,
-- and both run in the same Flyway start, so no row can be written in between.
-- Append-only: select and insert only; row-level deny trigger on the parent (PostgreSQL clones it to every partition); the
-- statement-level TRUNCATE deny trigger is created on the parent here and on each partition by pharmacy.create_ledger_partitions().
-- Corrections are new lines with reverses_ledger_id.

set local lock_timeout = '30s';

create table if not exists pharmacy.stock_ledger (
  id                            uuid not null default gen_random_uuid(),
  tenant_id                     uuid not null default platform.current_tenant()
                                  constraint fk_stock_ledger_tenant references platform.tenant (id),
  unit_id                       uuid not null,
  -- Server time at the insert, never from a client. clock_timestamp (not now) so that lines of one balance row are in lock order:
  -- a transaction that waited for the balance lock writes a later time than the one it waited for, even if it started earlier.
  occurred_at                   timestamptz not null default clock_timestamp(),
  -- Groups the lines of one movement: a move between stores or states is exactly two lines with one movement_id (service rule).
  movement_id                   uuid not null,
  txn_type                      text not null
                                  constraint ck_stock_ledger_txn_type check (txn_type in
                                    ('OPENING_STOCK', 'GRN', 'EXPIRY_QUARANTINE', 'STOCK_ADJUSTMENT', 'VENDOR_RETURN_HOLD',
                                     'VENDOR_RETURN_RELEASE', 'VENDOR_RETURN', 'INDENT_DISPATCH', 'INDENT_RECEIPT_ACCEPTED',
                                     'INDENT_RECEIPT_REJECTED', 'RETURN_ACCEPTED', 'SHORTAGE_FOUND', 'SHORTAGE_WRITE_OFF',
                                     'STORE_RETURN', 'DAMAGE_QUARANTINE', 'DAMAGE_RELEASE', 'DAMAGE_SEND_TO_CS', 'WRITE_OFF',
                                     'TRANSFER_DISPATCH', 'TRANSFER_RECEIPT_ACCEPTED', 'TRANSFER_RECEIPT_REJECTED',
                                     'WARD_CONSUMPTION', 'WARD_CONSUMPTION_PATIENT', 'STOCK_AUDIT_ADJUSTMENT')),
  -- The document behind the line. Both null for a system move without a document (the expiry quarantine).
  doc_type                      text
                                  constraint ck_stock_ledger_doc_type check (doc_type ~ '^[A-Z][A-Z_]{1,39}$'),
  doc_id                        uuid,
  -- The reference shown: the document number, "Ward general", or the admission number for patient-wise consumption. Never a name.
  doc_no                        text
                                  constraint ck_stock_ledger_doc_no check (char_length(doc_no) between 1 and 40),
  doc_line_id                   uuid,
  store_id                      uuid not null,
  state                         text not null
                                  constraint ck_stock_ledger_state check (state in ('AVAILABLE', 'IN_TRANSIT', 'PENDING_RETURN', 'QUARANTINE')),
  product_id                    uuid not null,
  batch_id                      uuid not null,
  qty_in                        integer not null default 0
                                  constraint ck_stock_ledger_qty_in check (qty_in >= 0),
  qty_out                       integer not null default 0
                                  constraint ck_stock_ledger_qty_out check (qty_out >= 0),
  -- Balance of this store, batch and state after this line.
  balance_after                 integer not null
                                  constraint ck_stock_ledger_balance_after check (balance_after >= 0),
  actor_kind                    text not null
                                  constraint ck_stock_ledger_actor_kind check (actor_kind in ('USER', 'SYSTEM')),
  actor_membership_id           uuid,
  second_signer_membership_id   uuid,
  approved_by_membership_id     uuid,
  reason_key                    text
                                  constraint ck_stock_ledger_reason check (reason_key ~ '^[a-z0-9][a-z0-9.-]{0,63}$'),
  -- The line this one corrects (79-5). No foreign key: a foreign key to a partitioned table would need the partition key; the service checks it.
  reverses_ledger_id            uuid,
  -- Patient-wise consumption only: an id, never a name.
  admission_id                  uuid,
  -- A partitioned table's primary key must contain the partition key.
  constraint pk_stock_ledger primary key (id, occurred_at),
  constraint fk_stock_ledger_unit foreign key (tenant_id, unit_id) references pharmacy.unit (tenant_id, id),
  constraint fk_stock_ledger_store foreign key (tenant_id, store_id) references pharmacy.store (tenant_id, id),
  constraint fk_stock_ledger_batch foreign key (tenant_id, batch_id, product_id) references pharmacy.batch (tenant_id, id, product_id),
  -- Exactly one of qty_in and qty_out is above 0.
  constraint ck_stock_ledger_one_direction check ((qty_in > 0) <> (qty_out > 0)),
  constraint ck_stock_ledger_actor check ((actor_kind = 'SYSTEM') = (actor_membership_id is null)),
  -- Schedule X: the second signer is never the person who acted, and only a person can act with a signer (P7).
  constraint ck_stock_ledger_signer_differs check (second_signer_membership_id is null or second_signer_membership_id <> actor_membership_id),
  constraint ck_stock_ledger_signer_needs_actor check (second_signer_membership_id is null or actor_membership_id is not null),
  constraint ck_stock_ledger_doc_pair check ((doc_type is null) = (doc_id is null)),
  constraint ck_stock_ledger_not_self_reverse check (reverses_ledger_id is null or reverses_ledger_id <> id),
  constraint ck_stock_ledger_admission check ((txn_type = 'WARD_CONSUMPTION_PATIENT') = (admission_id is not null))
) partition by range (occurred_at);

-- One product over a date range (stock ledger report, partition pruning by the range).
create index if not exists ix_stock_ledger_product_time on pharmacy.stock_ledger (tenant_id, product_id, occurred_at);
-- One store, batch and state in time order: "stock moved since the count" (stock audit) and the ledger of a batch.
create index if not exists ix_stock_ledger_balance on pharmacy.stock_ledger (tenant_id, store_id, batch_id, state, occurred_at);
-- The lines of one document (History, reconciliation of a posting).
create index if not exists ix_stock_ledger_doc on pharmacy.stock_ledger (tenant_id, doc_type, doc_id) where doc_id is not null;

alter table pharmacy.stock_ledger enable row level security;
alter table pharmacy.stock_ledger force row level security;
create policy p_stock_ledger_select on pharmacy.stock_ledger for select to sahaj_app using (tenant_id = platform.current_tenant());
create policy p_stock_ledger_insert on pharmacy.stock_ledger for insert to sahaj_app with check (tenant_id = platform.current_tenant());
-- No update, delete or truncate right for the app; a policy for them does not exist either.
revoke all on pharmacy.stock_ledger from sahaj_app;
revoke all on pharmacy.stock_ledger from public;
grant select, insert on pharmacy.stock_ledger to sahaj_app;

-- Row-level: cloned by PostgreSQL to every existing and future partition. Statement-level: not inherited, so each partition gets its own.
create trigger trg_stock_ledger_no_update_delete before update or delete on pharmacy.stock_ledger
  for each row execute function pharmacy.deny_append_only_mutation();
create trigger trg_stock_ledger_no_truncate before truncate on pharmacy.stock_ledger
  for each statement execute function pharmacy.deny_append_only_mutation();

comment on table pharmacy.stock_ledger is
  'Stock ledger (SAH-79, ADR-029 P3): every stock movement, one line per change of one store, batch and state. Append-only: select and insert only; update, delete and truncate are refused by trigger on the parent and on every monthly partition; never deleted (a partition is dropped only when the human sets a legal period, OQ-PHI-19). Partitioned by month on occurred_at (IST month boundaries); no default partition: a missing month fails loudly. Corrections are new lines with reverses_ledger_id. Patient data: admission_id only, never a name.';
comment on column pharmacy.stock_ledger.occurred_at is
  'Database clock at the insert (clock_timestamp), never from a client. Partition key.';
comment on column pharmacy.stock_ledger.actor_membership_id is
  'Membership id of the person who acted (opaque uuid, no foreign key). Null exactly for actor_kind SYSTEM (shown "System", for example the expiry quarantine).';
comment on column pharmacy.stock_ledger.reverses_ledger_id is
  'The ledger line this line corrects. No foreign key (the target table is partitioned); the posting service checks that the line exists in the same store and batch.';
comment on column pharmacy.stock_ledger.doc_no is
  'Reference shown: document number, "Ward general", or the admission number. Never a patient name, mobile or ABHA number.';
