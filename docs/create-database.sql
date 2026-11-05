-- Run as your MySQL administrator. Replace the password before executing.
CREATE DATABASE IF NOT EXISTS college_attendance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'attendance'@'localhost' IDENTIFIED BY 'REPLACE_WITH_YOUR_DATABASE_PASSWORD';
GRANT ALL PRIVILEGES ON college_attendance.* TO 'attendance'@'localhost';
-- Flyway creates and upgrades tables when the Java application starts.
