package com.poolapp.update;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

/**
 * Checks GitHub Releases for a newer version and prompts the user before opening the download page.
 * Never installs anything automatically.
 */
public class UpdateChecker {
    private static final String DEFAULT_REPO = "SQUEGATE/SILVERDICE";

    private UpdateChecker() {
    }

    /** Runs the check off the EDT; only touches Swing when showing the prompt. */
    public static void checkInBackground(Component parent) {
        Thread checkThread = new Thread(() -> {
            try {
                runCheck(parent);
            } catch (Exception ignored) {
                // Networking/parsing failures should never block the app from starting.
            }
        }, "update-checker");
        checkThread.setDaemon(true);
        checkThread.start();
    }

    private static void runCheck(Component parent) throws IOException, InterruptedException {
        String currentVersion = UpdateChecker.class.getPackage().getImplementationVersion();
        if (currentVersion == null || currentVersion.isBlank()) {
            return; // Not running from a packaged jar (e.g. dev/debug run); skip silently.
        }

        String repo = loadRepo();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return; // No releases published yet, or repo not reachable.
        }

        JSONObject release = new JSONObject(response.body());
        String tagName = release.optString("tag_name", "").trim();
        String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
        String releaseUrl = release.optString("html_url", "https://github.com/" + repo + "/releases/latest");
        String releaseName = release.optString("name", latestVersion);

        if (latestVersion.isBlank() || !isNewer(latestVersion, currentVersion)) {
            return;
        }

        String downloadUrl = findFirstAssetUrl(release).orElse(releaseUrl);

        SwingUtilities.invokeLater(() -> promptUser(parent, releaseName, downloadUrl));
    }

    private static java.util.Optional<String> findFirstAssetUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null || assets.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(assets.getJSONObject(0).optString("browser_download_url", null));
    }

    private static void promptUser(Component parent, String releaseName, String downloadUrl) {
        int choice = JOptionPane.showConfirmDialog(
                parent,
                "A new version is available: " + releaseName + "\nOpen the download page now?",
                "Update Available",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE
        );
        if (choice == JOptionPane.YES_OPTION) {
            try {
                Desktop.getDesktop().browse(URI.create(downloadUrl));
            } catch (Exception e) {
                JOptionPane.showMessageDialog(parent, "Unable to open the download page: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Compares dot-separated numeric versions, e.g. 1.2.10 > 1.2.9. Falls back to string inequality. */
    private static boolean isNewer(String latest, String current) {
        String[] latestParts = latest.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int latestPart = parsePart(latestParts, i);
            int currentPart = parsePart(currentParts, i);
            if (latestPart != currentPart) {
                return latestPart > currentPart;
            }
        }
        return false;
    }

    private static int parsePart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String loadRepo() {
        Properties properties = new Properties();
        try (InputStream input = UpdateChecker.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
        }
        File external = new File("config.properties");
        if (external.isFile()) {
            try (InputStream input = new FileInputStream(external)) {
                properties.load(input);
            } catch (IOException ignored) {
            }
        }
        return properties.getProperty("update.repo", DEFAULT_REPO).trim();
    }
}
