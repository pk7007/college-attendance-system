CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  role ENUM('ADMIN','FACULTY','STUDENT') NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE departments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(30) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE academic_years (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  label VARCHAR(30) NOT NULL UNIQUE,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CHECK (end_date >= start_date)
);
CREATE TABLE semesters (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  academic_year_id BIGINT NOT NULL,
  label VARCHAR(50) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_semester_year FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
  CONSTRAINT uq_semester UNIQUE (academic_year_id,label),
  CHECK (end_date >= start_date)
);
CREATE TABLE courses (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  department_id BIGINT NOT NULL,
  code VARCHAR(30) NOT NULL,
  name VARCHAR(120) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_course_department FOREIGN KEY (department_id) REFERENCES departments(id),
  CONSTRAINT uq_course UNIQUE (department_id,code)
);
CREATE TABLE sections (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  course_id BIGINT NOT NULL,
  semester_id BIGINT NOT NULL,
  code VARCHAR(30) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_section_course FOREIGN KEY (course_id) REFERENCES courses(id),
  CONSTRAINT fk_section_semester FOREIGN KEY (semester_id) REFERENCES semesters(id),
  CONSTRAINT uq_section UNIQUE (course_id,semester_id,code)
);
CREATE TABLE subjects (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  course_id BIGINT NOT NULL,
  code VARCHAR(30) NOT NULL,
  name VARCHAR(120) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_subject_course FOREIGN KEY (course_id) REFERENCES courses(id),
  CONSTRAINT uq_subject UNIQUE (course_id,code)
);
CREATE TABLE student_enrollments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_user_id BIGINT NOT NULL,
  section_id BIGINT NOT NULL,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_enrollment_user FOREIGN KEY (student_user_id) REFERENCES users(id),
  CONSTRAINT fk_enrollment_section FOREIGN KEY (section_id) REFERENCES sections(id),
  CONSTRAINT uq_enrollment UNIQUE (student_user_id,section_id),
  INDEX ix_enrollment_section (section_id,status)
);
CREATE TABLE faculty_assignments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  faculty_user_id BIGINT NOT NULL,
  section_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_assignment_user FOREIGN KEY (faculty_user_id) REFERENCES users(id),
  CONSTRAINT fk_assignment_section FOREIGN KEY (section_id) REFERENCES sections(id),
  CONSTRAINT fk_assignment_subject FOREIGN KEY (subject_id) REFERENCES subjects(id),
  CONSTRAINT uq_faculty_assignment UNIQUE (faculty_user_id,section_id,subject_id)
);
CREATE TABLE class_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  section_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  session_date DATE NOT NULL,
  session_label VARCHAR(80) NOT NULL,
  state ENUM('OPEN','COMPLETED','CANCELLED') NOT NULL DEFAULT 'OPEN',
  version BIGINT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_session_section FOREIGN KEY (section_id) REFERENCES sections(id),
  CONSTRAINT fk_session_subject FOREIGN KEY (subject_id) REFERENCES subjects(id),
  CONSTRAINT fk_session_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT uq_class_session UNIQUE (section_id,subject_id,session_date,session_label),
  INDEX ix_session_scope (section_id,subject_id,session_date)
);
CREATE TABLE attendance_records (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_session_id BIGINT NOT NULL,
  student_user_id BIGINT NOT NULL,
  status ENUM('PRESENT','ABSENT','LATE') NOT NULL,
  marked_by BIGINT NOT NULL,
  marked_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_attendance_session FOREIGN KEY (class_session_id) REFERENCES class_sessions(id),
  CONSTRAINT fk_attendance_student FOREIGN KEY (student_user_id) REFERENCES users(id),
  CONSTRAINT fk_attendance_marker FOREIGN KEY (marked_by) REFERENCES users(id),
  CONSTRAINT uq_attendance_mark UNIQUE (class_session_id,student_user_id),
  INDEX ix_attendance_student (student_user_id,class_session_id)
);
CREATE TABLE audit_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_user_id BIGINT NULL,
  action VARCHAR(60) NOT NULL,
  entity_type VARCHAR(60) NOT NULL,
  entity_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users(id),
  INDEX ix_audit_time (created_at)
);
