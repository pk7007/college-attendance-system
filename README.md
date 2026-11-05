# College Attendance Management System

College Attendance Management System (CAMS) is a Java, MySQL, and browser-based application for managing academic classes, recording class-session attendance, and showing students their own records. The repository also includes an offline-only preview at `demo/index.html`; that preview stores sample data in browser storage and does not connect to the database.

## Run the full application

Install Docker Desktop with Docker Compose, then from this repository folder run:

```powershell
Copy-Item attendance.env.example .env
docker compose up --build -d
```

Open <http://localhost:4173>. The API is at <http://localhost:8080/api/health>. On first startup, Flyway creates the MySQL schema and the development profile inserts sample accounts, a Computer Science class, and two sample attendance sessions.

### Development demo accounts

The example `.env` sets these local passwords:

| Role | Email | Password |
| --- | --- | --- |
| Admin | `admin@college.test` | `CampusAdmin-Change-123!` |
| Faculty | `faculty@college.test` | `FacultyDemo-2026!` |
| Student | `student@college.test` | `StudentDemo-2026!` |

These are development-only credentials. Change them before sharing a development environment. Do not use real student records until the deployment, privacy, and security review is complete.

To stop the services while keeping the MySQL volume:

```powershell
docker compose down
```

### Run backend without Compose

Requirements: Java 21, Maven 3.6.3+, and a MySQL 8 database. Set `CAM_DB_URL`, `CAM_DB_USER`, `CAM_DB_PASSWORD`, `CAM_BOOTSTRAP_ADMIN_EMAIL`, and `CAM_BOOTSTRAP_ADMIN_PASSWORD`; then run:

```powershell
cd backend
mvn spring-boot:run
```

Flyway applies the SQL files in `backend/src/main/resources/db/migration/` on startup. In this mode, serve the frontend through Nginx or another same-origin reverse proxy that forwards `/api/` to port 8080.

## What is included

- Admin: create user accounts, set up departments, years, semesters, courses, sections and subjects, enroll students, and assign faculty.
- Faculty: view assigned classes, open dated class sessions, mark the full enrolled roster present, absent, or late, and update saved sessions with version checks.
- Student: view overall and per-subject attendance and paginated history for their own account.
- Reports: admins can view all completed attendance and export CSV; faculty reports are limited to assigned classes.
- Security: BCrypt password hashes, server-side role checks, HttpOnly session cookies, CSRF protection for writes, and ownership checks on attendance routes.
- Database: MySQL 8 with forward-only Flyway migrations, foreign keys, indexes, unique class-session and student-mark constraints, and audit events.

Attendance percentage is `(PRESENT + LATE) / completed sessions × 100`. Cancelled and incomplete sessions are excluded. Class dates are stored as SQL `DATE`; audit timestamps are UTC instants. The institution display timezone is assumed to be `Asia/Kolkata`.

## API routes

- `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`, `GET /api/auth/csrf`
- `GET/POST /api/admin/users`, `GET /api/admin/classes`, `POST /api/admin/academic/setup`, `POST /api/admin/enrollments`, `POST /api/admin/assignments`
- `GET /api/faculty/classes`, `GET /api/faculty/classes/{assignmentId}/roster`, `GET/POST /api/faculty/classes/{assignmentId}/sessions`, `PUT /api/faculty/sessions/{sessionId}/attendance`
- `GET /api/student/attendance/summary`, `GET /api/student/attendance/history`
- `GET /api/reports/attendance`, `GET /api/reports/attendance.csv`, `GET /api/health`

## GitHub transfer

This project is in [pk7007/college-attendance-system](https://github.com/pk7007/college-attendance-system). Clone it on another laptop, copy `attendance.env.example` to `.env`, then run the Compose commands above. The database volume remains on the machine where it was created; GitHub transfers source code, not database contents.

## Production notes

Compose defaults and the development bootstrap are for local demo use only. For production, use the `prod` profile, high-entropy secrets from a secret manager, HTTPS, secure cookies, protected network ingress, reviewed backup/restore procedures, and institution-approved retention and privacy policies. The development bootstrap is disabled outside the `dev` profile. Never expose MySQL to the public internet.
