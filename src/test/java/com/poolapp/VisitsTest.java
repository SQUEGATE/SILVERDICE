package com.poolapp;

import com.poolapp.model.EmployeeProfile;
import com.poolapp.model.VisitRecord;
import org.json.JSONObject;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

public class VisitsTest {
    @Test
    public void validatesReadingsAndPermissionDefaults() {
        check(VisitRecord.PH_VALUES.equals(List.of("7.0", "7.2", "7.4", "7.6", "7.8", "8.0")), "PH choices");
        check(VisitRecord.CHLO_VALUES.equals(List.of("1-2", "2-4", "3-6", "5-10", "7.5-15", "10-20")), "CHLO choices");
        check(VisitRecord.STB_VALUES.equals(List.of("100", "90", "80", "70", "60", "50", "40", "30")), "STB choices");
        for (String invalid : List.of("", "0", "-1", "1.5", "abc", "+5", "1e2", "2147483648", " 12", "-0")) {
            rejects(() -> VisitRecord.positiveWholeNumber(invalid, "ALK"));
        }
        check(VisitRecord.positiveWholeNumber("2147483647", "CAL") == Integer.MAX_VALUE, "Max whole number");
        VisitRecord visit = new VisitRecord(3, "c1", Instant.now(), "7.4", "3-6", "50", 70, 250, "Test notes");
        check(!visit.measurementsJson().has("visited_at"), "Client must not replace timestamp");
        check(VisitRecord.optionalWholeNumber("", "ALK") == null, "Blank ALK is optional");
        check(VisitRecord.optionalWholeNumber(" ", "CAL") == null, "Blank CAL is optional");
        VisitRecord blank = new VisitRecord(3, "c1", Instant.now(), "7.4", "3-6", "50", null, null, "");
        check(blank.measurementsJson().isNull("alk") && blank.measurementsJson().isNull("cal"), "Blank fields sent as null");
        check(VisitRecord.fromJson(blank.measurementsJson().put("visit_id", 3).put("customer_id", "c1")
                .put("visited_at", blank.visitedAt().toString())).alk() == null, "Nullable readings deserialize");
        rejects(() -> new VisitRecord(0, "c1", Instant.now(), "7.4", "3-6", "50", null, null, "", "https://example.invalid/photo"));
        rejects(() -> new VisitRecord(0, "c1", Instant.now(), "7.4", "3-6", "50", null, null, "",
                "data:image/jpeg;base64," + "a".repeat(600000)));
        rejects(() -> new VisitRecord(0, "c1", Instant.now(), "7.3", "3-6", "50", 70, 250, ""));
        rejects(() -> new VisitRecord(0, "c1", Instant.now(), "7.4", "3-6", "50", 0, 250, ""));
        rejects(() -> new VisitRecord(0, "c1", Instant.now(), "7.4", "3-6", "50", 70, 250, "x".repeat(16001)));
        check(VisitRecord.SALT_VALUES.equals(List.of(420, 510, 600, 700, 800, 920, 1040, 1180, 1320, 1470, 1630, 1800,
                1990, 2180, 2390, 2610, 2840, 3080, 3350, 3630, 3920, 4240, 4580, 4950)), "SALT choices");
        check(blank.salt() == null && blank.measurementsJson().isNull("salt"), "SALT optional and sent as null");
        VisitRecord salted = new VisitRecord(3, "c1", Instant.now(), "7.4", "3-6", "50", null, null, "", "", 4950);
        check(salted.measurementsJson().getInt("salt") == 4950, "SALT sent to API");
        check(VisitRecord.fromJson(salted.measurementsJson().put("visit_id", 3).put("customer_id", "c1")
                .put("visited_at", salted.visitedAt().toString())).salt() == 4950, "SALT deserializes");
        check(VisitRecord.fromJson(new VisitRecord(3, "c1", Instant.now(), "7.4", "3-6", "50", null, null, "")
                .measurementsJson().put("visit_id", 3).put("customer_id", "c1").put("visited_at", Instant.EPOCH.toString())
                .put("salt", JSONObject.NULL)).salt() == null, "Null SALT deserializes");
        for (int invalid : List.of(0, 421, 5000, -420)) {
            rejects(() -> new VisitRecord(0, "c1", Instant.now(), "7.4", "3-6", "50", null, null, "", "", invalid));
        }
        EmployeeProfile employee = new EmployeeProfile();
        check(!employee.isCanAccessVisits(), "Employee visits must be opt-in");
        employee.setCanAccessVisits(true);
        check(employee.isCanAccessVisits(), "Grant visits access");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void rejects(Runnable task) {
        try {
            task.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid input to be rejected");
    }
}
