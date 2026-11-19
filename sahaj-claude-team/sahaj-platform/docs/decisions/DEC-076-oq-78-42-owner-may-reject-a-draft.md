# DEC-076 · OQ-78-42: what happens to a draft nobody finishes

Date: 18 Nov 2026 · Story: SAH-78-7 and the open-file rule of DEC-071 · Authority: DEC-073
Status: DECIDED (auto).

## Context
Since DEC-071, a store, and its products on the lines, cannot be switched off or changed while an opening-stock import is a DRAFT or WAITING_APPROVAL. A draft that the starter never finishes would hold the store and its products for good: only the starter can replace it, and the contract allows rejecting only a file that is waiting for approval.

## Options
- **A (chosen):** the owner may reject a DRAFT as well as a waiting file, with a reason from the list.
  - Pros: no orphan, nobody is stuck, and the rule is the same for both states.
  - Cons: a contract change and a small change in the backend and the frontend.
- **B:** block only a waiting file, and let a draft hold nothing.
  - Pros: no new operation.
  - Cons: a store switched off under a draft leaves a draft that can never be completed, because the store guard answers 404 for an inactive store.
- **C:** leave it as a known limit. Cons: the lock can be permanent.

## Decision
- Option A. The owner (SETUP_EDIT, an approver of the store) may reject an import in DRAFT or WAITING_APPROVAL. The same reasons apply, and the audit holds ids only.
- The uploader keeps the right to replace their own draft.
- A rejected import frees the store and its products at once (it already does, DEC-071).

## Work
- Architect: pharmacy.yaml wording (reject accepted from DRAFT; `import-state` only for other states). Locks free at about 23:50.
- Java: allow the transition and its checks. The DB trigger already treats REJECTED as final, so check whether the transition from DRAFT is allowed by the transition trigger; if not, the db-engineer writes a forward fix (1270 to 1299).
- Angular: show Reject for a draft to an owner.
- QA: change `afterRejectTheStoreAndTheProductsMayChangeAgain` and add the draft cases.
- BA: add the criterion to 78-7.

## Blocked
Nothing.
