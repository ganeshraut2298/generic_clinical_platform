# Prompt for Claude Code

Paste everything below the line into Claude Code, from the root of the application repository.
Change the stack line and the folder path if yours are different.

---

Read `docs/design/emr-config/SCREENS.md` first. It indexes the 17 screens of our approved design for "EMR configuration by department": a screenshot, a source file and a build order for each.

**Goal:** build this feature in our application so the screens look and behave like the design.

**Stack:** Angular 21, Java 17, PostgreSQL. Follow the conventions already in this repository: folder layout, component library, state handling, API style and tests. If the repository and the design disagree on code structure, follow the repository. If they disagree on appearance or behaviour, follow the design.

## Step 1: plan only, no code

For each screen, look at the screenshot in `screens/`, then read its file in `source/`. Then write `docs/design/emr-config/PLAN.md` with:

1. The components you will build, each mapped to its design file, and which existing components in this repository you will reuse.
2. The design tokens (colours, type sizes, spacing, radius) to add to our theme.
3. For each screen, the behaviours you found in its script block: states, validations, what each button does.
4. The data model and API endpoints needed for department, section and field configuration, including visit types, required/optional, draft and publish, and versions.
5. Open questions and anything in the design you could not interpret.

Stop after writing the plan and wait for my approval.

## Step 2: build in this order, one pull request each

1. Theme tokens.
2. Shared components: EmrList, BodyMap, VisitHistory, CasePaper, TreatmentCourse.
3. Configurator: Main and AddDepartment.
4. Form builder.
5. General Medicine doctor screen (Assessment and Treatment), rendered from the saved configuration.
6. The other departments, one at a time: Dental, Obstetrics and Gynaecology, Paediatrics, Physiotherapy, General Surgery, Medical Oncology.

## Rules

- The `.dc.html` files are a specification. Do not copy their runtime, custom tags or inline styles into the application. Convert styles into theme tokens and component styles.
- Doctor screens must be rendered from configuration (which sections, in what order, which are required, for which visit type). Do not hard-code one screen per department. The department files show what the configuration should produce.
- Patients, doctors, counts and dates in the design are example data. Use them only in fixtures and tests.
- Keep labels and messages exactly as written in the design unless I say otherwise.
- Do not change existing database tables or public APIs without asking me first.
- Never overwrite signed clinical documentation silently. Changes after signing go in as an addendum.

## How to check your work

After each screen, run the application, take a screenshot at the designed width given in `SCREENS.md`, and compare it with the matching file in `screens/`. List the differences, fix them, and include the before and after screenshots in the pull request.
