package com.poolapp;

import com.poolapp.db.DatabaseManager;
import com.poolapp.model.Customer;
import com.poolapp.model.VisitRecord;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.sql.DriverManager;
import com.poolapp.service.VisitPhoto;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

public class VisitsDatabaseTest {
    @Test
    public void persistsAndEditsVisitsWithoutCrossingCustomers() throws Exception {
        Path directory = Files.createTempDirectory("comp-manager-visits-test-");
        Path path = directory.resolve("visits.db");
        try {
            DatabaseManager database = new DatabaseManager(path);
            database.addCustomer(customer("c1", "Monday"));
            database.addCustomer(customer("c2", "Tuesday"));
            VisitRecord first = database.saveVisit(draft("c1", 70, "Original"));
            VisitRecord second = database.saveVisit(draft("c1", 80, "Second"));
            VisitsTest.check(first.id() > 0 && second.id() > first.id(), "Unique persisted visit IDs");
            VisitRecord edited = database.saveVisit(new VisitRecord(first.id(), "c1", Instant.EPOCH,
                    "7.8", "10-20", "90", 100, 500, "Updated\nnotes"));
            VisitsTest.check(edited.visitedAt().equals(first.visitedAt()), "Editing must preserve timestamp");
            VisitsTest.check(edited.id() == first.id(), "Editing must not add a new visit");
            DatabaseManager reopened = new DatabaseManager(path);
            var visits = reopened.getVisits("c1");
            VisitsTest.check(visits.size() == 2, "Persist across reopen");
            VisitsTest.check(visits.get(0).id() == second.id(), "Newest visit first");
            VisitsTest.check(visits.get(1).notes().equals("Updated\nnotes"), "Notes persisted");
            VisitsTest.check(reopened.getVisits("c2").isEmpty(), "Customer visit isolation");
            VisitsTest.rejects(() -> database.saveVisit(new VisitRecord(first.id(), "c2", Instant.now(),
                    "7.4", "3-6", "50", 70, 250, "")));
            VisitsTest.rejects(() -> database.saveVisit(draft("missing", 70, "")));
            VisitsTest.rejects(() -> database.deleteVisit("c2", first.id()));
            VisitsTest.check(database.getVisits("c1").size() == 2, "Wrong customer must not delete");
            database.deleteVisit("c1", first.id());
            VisitsTest.check(database.getVisits("c1").size() == 1, "Selected visit deleted");
            VisitsTest.check(database.getVisits("c1").get(0).id() == second.id(), "Other visit preserved");
            VisitsTest.rejects(() -> database.deleteVisit("c1", first.id()));
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory.resolve("visits.db.bak"));
            Files.delete(directory);
        }
    }

    @Test
    public void migratesRequiredReadingsAndSavesOptionalReadingsAndPhoto() throws Exception {
        Path directory = Files.createTempDirectory("comp-manager-visits-migration-test-");
        Path path = directory.resolve("visits.db");
        Path photo = directory.resolve("photo.png");
        try {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE customer_visits(visit_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "customer_id TEXT NOT NULL, visited_at TEXT NOT NULL, ph TEXT NOT NULL, chlo TEXT NOT NULL,"
                        + "stb TEXT NOT NULL, alk INTEGER NOT NULL CHECK(alk>0), cal INTEGER NOT NULL CHECK(cal>0), notes TEXT NOT NULL DEFAULT '')");
                statement.execute("INSERT INTO customer_visits VALUES(7,'c1','2026-10-25T12:00:00Z','7.4','3-6','50',70,250,'old visit')");
            }
            DatabaseManager database = new DatabaseManager(path);
            database.addCustomer(customer("c1", "Monday"));
            VisitRecord old = database.getVisits("c1").get(0);
            VisitsTest.check(old.id() == 7 && old.alk() == 70 && old.photoData().isEmpty(), "Migration preserves visit");
            ImageIO.write(new BufferedImage(1500, 900, BufferedImage.TYPE_INT_RGB), "png", photo.toFile());
            String data = VisitPhoto.fromFile(photo);
            VisitsTest.check(data.length() <= VisitRecord.MAX_PHOTO_LENGTH, "Compressed photo fits server limit");
            VisitsTest.check(VisitPhoto.preview(data).getWidth() <= 640, "Photo can be previewed");
            VisitRecord added = database.saveVisit(new VisitRecord(0, "c1", Instant.now(), "7.4", "3-6", "50",
                    null, null, "photo", data));
            VisitsTest.check(added.alk() == null && added.cal() == null, "Blank readings saved as NULL");
            VisitsTest.check(added.photoData().equals(data), "Photo persisted");
            DatabaseManager reopened = new DatabaseManager(path);
            VisitsTest.check(reopened.getVisits("c1").get(0).photoData().equals(data), "Photo survives reopen");
            VisitRecord removed = reopened.saveVisit(new VisitRecord(added.id(), "c1", Instant.now(),
                    "7.4", "3-6", "50", 70, null, "removed", ""));
            VisitsTest.check(removed.photoData().isEmpty() && removed.alk() == 70 && removed.cal() == null, "Remove photo and fill only ALK");
            VisitsTest.check(removed.visitedAt().equals(added.visitedAt()), "Photo edit preserves timestamp");
            reopened.deleteVisit("c1", added.id());
            VisitsTest.check(new DatabaseManager(path).getVisits("c1").size() == 1, "Deletion persists after reopen");
        } finally {
            Files.deleteIfExists(photo);
            Files.deleteIfExists(path);
            Files.deleteIfExists(directory.resolve("visits.db.bak"));
            Files.delete(directory);
        }
    }

    static Customer customer(String id, String day) {
        return new Customer(id, "Test", id, "123 Test St", "", "", day, BigDecimal.ZERO, "");
    }

    static VisitRecord draft(String customerId, int alk, String notes) {
        return new VisitRecord(0, customerId, Instant.now(), "7.4", "3-6", "50", alk, 250, notes);
    }
}
