package edu.college.attendance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AttendanceRules {
    private AttendanceRules() {}
    public static Map<Long,String> completeRoster(List<Long> roster,List<FacultyController.Mark> records) {
        if(roster==null||records==null) throw new IllegalArgumentException("Attendance roster and marks are required.");
        Map<Long,String> marks=new HashMap<>();
        for(FacultyController.Mark mark:records) {
            String status=mark.status()==null?"":mark.status().trim().toUpperCase();
            if(!Set.of("PRESENT","ABSENT","LATE").contains(status)) throw new IllegalArgumentException("Status must be PRESENT, ABSENT, or LATE.");
            if(marks.put(mark.studentId(),status)!=null) throw new IllegalArgumentException("Duplicate student mark.");
        }
        if(marks.size()!=roster.size()||!marks.keySet().equals(new HashSet<>(roster))) throw new IllegalArgumentException("Submit exactly one attendance mark for every active student in the class.");
        return Map.copyOf(marks);
    }
}
