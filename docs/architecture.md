# Architecture

Browser → Spring Boot on 127.0.0.1:8080 → local MySQL 8.

Spring Boot serves `backend/src/main/resources/static/index.html` and `/api/**` from the same origin. There is no frontend build or separate proxy. Maven Wrapper packages the HTML and Java API together in an executable jar.

Flyway migrations in `backend/src/main/resources/db/migration` create/upgrade tables. Do not change already-applied migrations; add subsequent versioned SQL files. Configuration is loaded by `start-local.ps1` from ignored `local-config.psd1` into process environment variables.

Controllers enforce roles and faculty assignments. Student routes derive identity from the authenticated session. CSRF protects writes; passwords use BCrypt. Disabled users lose authenticated access on subsequent requests. Attendance saves validate the complete roster and use an optimistic version check inside a transaction.

Development data is seeded only in the dev profile. Git stores source and documentation, not MySQL files, credentials or user records. The original machine's isolated test database is under ignored `.local/mysql-data` and uses port 3307; a normal MySQL installation uses its own managed data directory and port 3306.
