package com.poolapp.model;

import org.json.JSONObject;
import org.json.JSONArray;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record VisitRecord(long id, String customerId, Instant visitedAt, String ph, String chlo,
                          String stb, Integer alk, Integer cal, String notes, List<String> photos, Integer salt) {
    public static final int MAX_PHOTO_LENGTH = 600000;
    public static final int MAX_PHOTOS = 3;
    public static final List<String> PH_VALUES = List.of("7.0", "7.2", "7.4", "7.6", "7.8", "8.0");
    public static final List<String> CHLO_VALUES = List.of("1-2", "2-4", "3-6", "5-10", "7.5-15", "10-20");
    public static final List<String> STB_VALUES = List.of("100", "90", "80", "70", "60", "50", "40", "30");
    public static final List<Integer> SALT_VALUES = List.of(420, 510, 600, 700, 800, 920, 1040, 1180, 1320, 1470,
            1630, 1800, 1990, 2180, 2390, 2610, 2840, 3080, 3350, 3630, 3920, 4240, 4580, 4950);

    public VisitRecord {
        if (customerId == null || customerId.isBlank()) throw new IllegalArgumentException("Select a customer.");
        if (visitedAt == null) throw new IllegalArgumentException("Visit date and time are required.");
        if (!PH_VALUES.contains(ph) || !CHLO_VALUES.contains(chlo) || !STB_VALUES.contains(stb)) {
            throw new IllegalArgumentException("Choose one of the listed PH, CHLO, and STB values.");
        }
        if ((alk != null && alk <= 0) || (cal != null && cal <= 0)) {
            throw new IllegalArgumentException("ALK and CAL must be positive whole numbers when provided.");
        }
        if (notes == null || notes.length() > 16000) throw new IllegalArgumentException("Notes must be at most 16000 characters.");
        if (photos == null || photos.size() > MAX_PHOTOS) {
            throw new IllegalArgumentException("A visit can have up to 3 photos.");
        }
        photos = List.copyOf(photos);
        for (String photo : photos) {
            if (photo == null || photo.length() > MAX_PHOTO_LENGTH
                    || !photo.matches("data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+")) {
                throw new IllegalArgumentException("Each visit photo must be a PNG or JPEG, at most 600000 encoded characters.");
            }
        }
        if (salt != null && !SALT_VALUES.contains(salt)) throw new IllegalArgumentException("Choose one of the listed SALT values.");
    }

    public VisitRecord(long id, String customerId, Instant visitedAt, String ph, String chlo,
                       String stb, Integer alk, Integer cal, String notes) {
        this(id, customerId, visitedAt, ph, chlo, stb, alk, cal, notes, List.of(), null);
    }

    public VisitRecord(long id, String customerId, Instant visitedAt, String ph, String chlo,
                       String stb, Integer alk, Integer cal, String notes, String photoData) {
        this(id, customerId, visitedAt, ph, chlo, stb, alk, cal, notes,
                photoData == null || photoData.isEmpty() ? List.of() : List.of(photoData), null);
    }

    public VisitRecord(long id, String customerId, Instant visitedAt, String ph, String chlo,
                       String stb, Integer alk, Integer cal, String notes, String photoData, Integer salt) {
        this(id, customerId, visitedAt, ph, chlo, stb, alk, cal, notes,
                photoData == null || photoData.isEmpty() ? List.of() : List.of(photoData), salt);
    }

    public String photoData() {
        return photos.isEmpty() ? "" : photos.get(0);
    }

    public JSONObject measurementsJson() {
        JSONArray photoArray = new JSONArray();
        photos.forEach(photoArray::put);
        return new JSONObject().put("ph", ph).put("chlo", chlo).put("stb", stb)
                .put("alk", alk == null ? JSONObject.NULL : alk)
                .put("cal", cal == null ? JSONObject.NULL : cal).put("notes", notes)
                .put("photos", photoArray).put("photo_data", photoData())
                .put("salt", salt == null ? JSONObject.NULL : salt);
    }

    public static VisitRecord fromJson(JSONObject row) {
        List<String> photos = new ArrayList<>();
        JSONArray photoArray = row.optJSONArray("photos");
        if (photoArray != null) {
            for (int i = 0; i < photoArray.length(); i++) photos.add(photoArray.getString(i));
        } else {
            String photo = row.optString("photo_data", "");
            if (!photo.isEmpty()) photos.add(photo);
            for (String field : List.of("photo_data_2", "photo_data_3")) {
                String additionalPhoto = row.optString(field, "");
                if (!additionalPhoto.isEmpty()) photos.add(additionalPhoto);
            }
        }
        return new VisitRecord(row.getLong("visit_id"), row.getString("customer_id"),
                Instant.parse(row.getString("visited_at")), row.getString("ph"),
                row.getString("chlo"), row.getString("stb"), row.isNull("alk") ? null : row.getInt("alk"),
                row.isNull("cal") ? null : row.getInt("cal"), row.getString("notes"), photos,
                row.has("salt") && !row.isNull("salt") ? row.getInt("salt") : null);
    }

    public static Integer optionalWholeNumber(String text, String label) {
        return text == null || text.isBlank() ? null : positiveWholeNumber(text, label);
    }

    public static int positiveWholeNumber(String text, String label) {
        if (text == null || !text.matches("[0-9]+")) {
            throw new IllegalArgumentException(label + " must be a positive whole number.");
        }
        try {
            int number = Integer.parseInt(text);
            if (number > 0) return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be between 1 and 2147483647.", e);
        }
        throw new IllegalArgumentException(label + " must be between 1 and 2147483647.");
    }
}
