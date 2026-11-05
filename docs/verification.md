# Local verification — 2026-11-05

## Environment and result

The integrated application ran on Windows at http://localhost:8080 using installed Oracle JDK 22.0.1, Maven Wrapper 3.9.11, and an isolated real MySQL 8.1 instance on localhost:3307. Maven compiles for Java 21. A separate GitHub Actions build uses Temurin Java 21; see the repository Actions page for its result.

- Maven `verify`: passed; four AttendanceRules tests, no failures/errors.
- Real HTTP/MySQL integration script: passed login, invalid credentials, missing CSRF protection, role restrictions, admin user/class creation, enrollment, faculty assignment, attendance save/update, stale-version conflict, student record isolation, percentage calculation, filtered reports/CSV, disabled-account session rejection, and logout.
- Persistence: session 3 remained LATE at version 2 after stopping Java and restarting through `start-local.ps1`.
- Windows startup script: successfully built and launched the web application using Windows PowerShell.
- Browser: signed in as Admin, Faculty and Student; loaded the saved roster and student summary. Login rendered at a 390px mobile viewport.
- Screenshots: `docs/screenshots/login.png`, `admin.png`, `faculty.png`, and `student.png` are actual captures of this Java/MySQL application with sample data.

## Scope and limitations

The tests use sample records and are not a production security or load audit. This was not tested on a second physical laptop. Backup/restore commands are documented but a full backup/restore drill has not been performed. Most end-to-end writes were verified through HTTP APIs; browser checks covered login, navigation and displaying stored data. Local configuration, database files and generated credentials are excluded from Git.

To repeat workflow checks, start the dev application and run `python scripts/verify-local.py`. This optional test creates labelled verification records; use a development database. Python is not needed to run the application.
