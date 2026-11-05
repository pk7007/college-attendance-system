package edu.college.attendance;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceRulesTest {
    @Test void acceptsCompleteRosterAndNormalizesStatuses() {
        var result=AttendanceRules.completeRoster(List.of(1L,2L),List.of(new FacultyController.Mark(1,"present"),new FacultyController.Mark(2," late ")));
        assertEquals("PRESENT",result.get(1L)); assertEquals("LATE",result.get(2L));
    }
    @Test void rejectsMissingStudent() {
        assertThrows(IllegalArgumentException.class,()->AttendanceRules.completeRoster(List.of(1L,2L),List.of(new FacultyController.Mark(1,"PRESENT"))));
    }
    @Test void rejectsDuplicateAndUnknownStudents() {
        assertThrows(IllegalArgumentException.class,()->AttendanceRules.completeRoster(List.of(1L),List.of(new FacultyController.Mark(1,"PRESENT"),new FacultyController.Mark(1,"ABSENT"))));
        assertThrows(IllegalArgumentException.class,()->AttendanceRules.completeRoster(List.of(1L),List.of(new FacultyController.Mark(2,"PRESENT"))));
    }
    @Test void rejectsUnknownStatus() {
        assertThrows(IllegalArgumentException.class,()->AttendanceRules.completeRoster(List.of(1L),List.of(new FacultyController.Mark(1,"EXCUSED"))));
    }
}
