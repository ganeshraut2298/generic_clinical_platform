# DEC-010 · Who switches IPD on for a clinic (the Sahaj admin console)

Status: DECIDED 5 Nov by the human: option 2 (owner starts the trial). The admin console stays a later story; lock the trial switch (`sahaj.modules.owner-ipd-trial=false`) before real clinics use the product.

## Context
The mockup says IPD is switched on by the Sahaj team from the admin console (Clinics → clinic → Modules); the owner can only "Request IPD". No admin console and no platform-admin login exist. Without one, the IPD state of every clinic stays Off, so the whole IPD block (wards and beds, admission, bed board, nursing, discharge) cannot be reached by any user, including in a demo.
A platform-admin login is an architecture change (a new kind of user outside any tenant, able to see and change any clinic), so it needs a human decision.

## Options
1. **Build a minimal Sahaj admin portal** (separate admin sign-in, tenants list, set a clinic's modules and plan). Pros: matches the mockup and the screen map ("tenants, plans"); the real product path. Cons: new auth realm and cross-tenant access path; needs its own security review; about 2 stories.
2. **Owner can start the 30-day IPD trial themselves** (the mockup has this button "to try IPD in this mockup"). Pros: quick, lets IPD be built and demoed now. Cons: not the product behaviour; must be removed or locked before real use.
3. **A local-only dev switch** (a profile-guarded endpoint like the OTP dev bypass). Pros: no product change; Cons: another backdoor to remember; demo to a client still needs the dev profile.

## Recommendation
Option 2 for now (owner-started trial, clearly labelled, one flag to turn off), then Option 1 as a proper story before any real client. This keeps IPD buildable overnight without creating a cross-tenant login.

## Blocked until answered
Switching IPD on (IPD stories can still be built against the entitlement; they just cannot be reached). Plan and modules (SAH-19) is built without it.
