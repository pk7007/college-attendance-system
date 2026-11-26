# Verification

## Automated (runs on every push in GitHub Actions)

- **Unit tests**: roster validation rules (`AttendanceRulesTest`).
- **Multi-user integration tests** (`DataReflectionIntegrationTest`, 9 scenarios). These run against real MySQL 8.4. Each test signs in an admin, one or two faculty members and two students at the same time, each with their own session, and checks that every role sees the same data:
  1. Faculty save and correct marks; the student summary and history, admin reports, faculty reports, the per-student summary and the CSV all agree.
  2. Deactivating the subject, section, course, department or semester hides the class from admin, faculty and students, and blocks new sessions. History is kept, and reactivating restores the class.
  3. Removing and re-adding enrollments and faculty assignments; the roster and reports follow.
  4. Cancelled sessions leave every total, and reopening restores them.
  5. Two faculty members on one class see each other's saves, and a stale save is rejected with 409.
  6. Students and faculty cannot see other classes' data.
  7. Renames, deactivation (immediate sign-out), password reset with a forced change, and the last-admin protection.
  8. Bad input returns readable 400/404/409 JSON errors, never 500.
  9. Login throttling.
- **Docker image** builds from a clean checkout, and `docker-compose.yml` validates.

## Manual checks done for this version (2026-11-26)

These ran in a Linux container with OpenJDK 21 and MySQL 8.0 and 8.4.

- `./mvnw verify` with the integration tests enabled: 13 tests passed. The server ran with `TZ=Asia/Kolkata` to catch date-shift bugs.
- `scripts/verify-local.py` passed against a fresh database, a second run on the same database, the Docker Compose stack, and `start-local.sh`.
- Docker Compose (MySQL 8.4 plus the app image): the first start created the admin and the demo class. After `docker compose restart`, all users and attendance records were still there.
- Browser (Chromium via Playwright), with an admin, a faculty member in two tabs and a student signed in at once, passed 34 checks with no JavaScript errors:
  - The admin created a faculty member and a student, created a class with the form's default dates, assigned and enrolled them.
  - The new faculty member and student were both forced to replace their temporary passwords.
  - The faculty member's default session date was the browser's local date.
  - An Absent mark showed 0% for the student. Correcting it to Late showed 100% for the student and in the admin report.
  - A stale save from the second faculty tab was rejected.
  - Deactivating the subject hid the class from faculty and student but kept the history, and reactivating restored it.
  - A deactivated student was signed out on their next click.
  - At 390px phone width there was no horizontal scrolling.

## Not covered

- Load and performance testing, and a professional security audit.
- Running the Windows `start-local.ps1` path in this environment. It was verified on Windows for the previous version, and its behaviour is unchanged apart from the shared configuration.
- A full backup and restore drill. The commands are documented in the README.
