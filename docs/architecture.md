# Architecture

Browser → Spring Boot (port 8080) → MySQL 8.

Spring Boot serves `backend/src/main/resources/static/index.html` and `/api/**` from the same origin. There is no frontend build step or separate proxy. Maven Wrapper packages the page and the Java API into one executable jar. With Docker, `docker-compose.yml` runs that jar next to a MySQL 8.4 container and keeps the database in the `db-data` volume.

## Data and migrations

Flyway migrations in `backend/src/main/resources/db/migration` create and upgrade tables on startup. Never edit a migration that has already run; add a new versioned file (`V2__...sql`).

Configuration comes from environment variables. `start-local.ps1` and `start-local.sh` load them from the git-ignored `local-config.psd1` / `local-config.env`, and Docker Compose loads them from `.env`.

## One rule for "is this class live?"

`AppSupport.LIVE_CLASS_FILTER` defines when a class can be used: its section, course, department, semester and subject must all be active. Every class list uses this one condition: admin classes, faculty classes and roster access, student classes, and enrollment and assignment checks. Faculty additionally need an active assignment. As a result, deactivating anything is reflected the same way for every role.

Attendance history is never filtered by it. Saved records stay in student summaries and reports even after a class is deactivated.

## Consistency between users

- Every view reads the same tables: student summary, student history, faculty reports, admin reports and CSV. Percentages use one formula, (PRESENT + LATE) / COMPLETED sessions, and cancelled or unsaved sessions are excluded everywhere.
- Saving attendance locks the session row (`SELECT … FOR UPDATE`), checks the version the faculty member loaded, validates the complete active roster, writes the marks and bumps the version, all in one transaction. A second faculty member with an older copy gets HTTP 409 instead of overwriting.
- Deactivating an administrator locks the admin rows first, so concurrent requests cannot remove the last admin.
- Re-enrolling, re-assigning or re-running class setup reactivates existing rows instead of failing on unique keys.

## Security

- Passwords are BCrypt hashes. Sessions use HttpOnly cookies, and every write needs a CSRF token.
- Controllers check the role with `@PreAuthorize`. Faculty routes also check the assignment, and student routes take the student's identity only from the session.
- A filter re-reads the account on every request. A deactivated user is signed out immediately, and a user with a temporary password can only change it.
- After repeated wrong passwords, login for that email is throttled for 15 minutes (`LoginThrottle`).
- Every error is returned as `{"error": "..."}`. Unexpected failures are logged and never expose stack traces.

## Startup data

`DevelopmentBootstrap` creates the first administrator from `CAM_BOOTSTRAP_ADMIN_*` in any profile, unless that account already exists. The `dev` profile also seeds a demo faculty member, a demo student and a class in the semester containing today. It uses the same June–May calendar the admin form suggests, so a fresh install always works.
