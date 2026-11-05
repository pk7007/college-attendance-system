# Architecture and data model

The browser client is a static single page served by Nginx. Nginx forwards same-origin `/api/` requests to the Spring Boot API. The API uses Spring Security sessions and JDBC service queries; Flyway manages schema changes; MySQL stores academic structure, accounts, class sessions, attendance marks, and audit events.

```text
Browser → Nginx web client → Spring Boot API → MySQL
                                  └── Flyway migrations
```

## Main relationships

- A department owns courses; courses own sections and subjects.
- An academic year owns semesters; sections connect a course and semester.
- Student enrollments connect a student account to a section.
- Faculty assignments connect a faculty account to a section and subject.
- A class session is unique by section, subject, date, and session label.
- An attendance mark is unique by session and student; it records status, marker, and timestamp.
- Audit events record the actor, action, entity type, entity ID, and timestamp.

See `backend/src/main/resources/db/migration/V1__schema.sql` for the authoritative schema. Schema changes are forward-only Flyway migrations; Hibernate does not create production tables.

## Security boundaries

The server derives the signed-in user identity from the session. Admin operations require `ADMIN`; faculty APIs require `FACULTY` and check each assignment; student summary/history use the authenticated student ID and do not accept a student ID from the browser. Writes require the CSRF token cookie/header pair. Passwords are BCrypt hashes and are not returned by APIs.
