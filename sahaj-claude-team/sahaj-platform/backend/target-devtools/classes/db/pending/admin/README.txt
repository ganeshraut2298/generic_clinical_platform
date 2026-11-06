Parked until the human creates role sahaj_admin_definer (DEC-012). To activate: move the two V2026_11_15_* files back into db/migration (and keep their versions) once the role exists, then run node scripts/validate.mjs db.

Revision 5 Nov (ADM-1, java-developer requests). Edited in place because the files have never run anywhere. Changes in V2026_11_15_1010 only:
- admin_preauth_get also returns email and throttle_key (two new last columns).
- admin_session_rotate also returns auth_method (new last column).
- admin_sign_in has a new last parameter p_summary text default null; the only accepted value is 'dev bypass' (TOTP method only).
These files were NOT executed after this edit (no database access for the db-engineer in this task). They are checked by reading only. The first real run is the activation step above; run node scripts/validate.mjs db then and fix forward if anything fails.
