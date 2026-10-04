package com.poolapp.service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class AppPackageGenerator {
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final Path projectRoot;

    public AppPackageGenerator() {
        this.projectRoot = locateProjectRoot();
    }

    public boolean canGenerate() {
        return projectRoot != null;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public Path generate() throws IOException, InterruptedException {
        return generate(message -> { });
    }

    public Path generate(Consumer<String> progress) throws IOException, InterruptedException {
        if (projectRoot == null) {
            throw new IllegalStateException("The source project was not found. Run Comp Manager from the DATABASE project checkout to generate an app bundle.");
        }

        String buildDirectoryName = "gradle-build-download-" + FILE_STAMP.format(LocalDateTime.now());
        Path buildDirectory = Paths.get(System.getProperty("java.io.tmpdir"), buildDirectoryName);
        Path gradleWrapper = projectRoot.resolve("gradlew.bat");
        Path buildLog = Files.createTempFile("comp-manager-package-", ".log");
        String command = "\"" + gradleWrapper + "\" -PverificationBuildDir=\"" + buildDirectory
                        + "\" packageAppImage --no-daemon --console=plain";

        progress.accept("Building app...");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/s", "/c", "\"" + command + "\"")
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(buildLog.toFile())
                .start();
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("App packaging timed out after 15 minutes. Gradle log: " + buildLog);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Gradle app packaging failed (exit " + exitCode + "). Log: " + buildLog + "\n" + readLastLines(buildLog, 35));
        }

        Path appImage = buildDirectory.resolve("dist").resolve("Comp Manager");
        Path executable = appImage.resolve("Comp Manager.exe");
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Gradle reported success but the app executable is missing: " + executable + "\n" + readLastLines(buildLog, 35));
        }

        Path downloads = Paths.get(System.getProperty("user.home"), "Downloads");
        Files.createDirectories(downloads);
        Path zipFile = downloads.resolve("Comp-Manager-cloud-test-" + FILE_STAMP.format(LocalDateTime.now()) + ".zip");
        progress.accept("Zipping app...");
        for (int attempt = 1; ; attempt++) {
            try {
                zipAppImage(appImage, zipFile, progress);
                break;
            } catch (IOException e) {
                // Antivirus/indexers can briefly lock freshly built files.
                if (attempt >= 5) {
                    throw new IOException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                }
                Thread.sleep(2000);
            }
        }
        try {
            deleteBuildDirectory(buildDirectory);
            Files.deleteIfExists(buildLog);
        } catch (IOException ignored) {
            // The ZIP is already created; leftover temp files are harmless.
        }
        progress.accept("Complete");
        return zipFile;
    }

    private static Path locateProjectRoot() {
        Path[] startingPoints;
        try {
            Path codePath = Paths.get(AppPackageGenerator.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            if (Files.isRegularFile(codePath)) codePath = codePath.getParent();
            startingPoints = new Path[]{Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath(), codePath};
        } catch (Exception e) {
            startingPoints = new Path[]{Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath()};
        }

        for (Path start : startingPoints) {
            for (Path current = start; current != null; current = current.getParent()) {
                if (Files.isRegularFile(current.resolve("build.gradle")) && Files.isRegularFile(current.resolve("gradlew.bat"))) {
                    return current;
                }
            }
        }
        return null;
    }

    private static void zipAppImage(Path appImage, Path zipFile, Consumer<String> progress) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            try (var paths = Files.walk(appImage)) {
                int[] fileCount = {0};
                paths.filter(Files::isRegularFile)
                        .sorted(Comparator.naturalOrder())
                        .forEach(path -> {
                            String relative = appImage.relativize(path).toString().replace('\\', '/');
                            ZipEntry entry = new ZipEntry("Comp Manager/" + relative);
                            try {
                                FileTime modified = Files.getLastModifiedTime(path);
                                entry.setLastModifiedTime(modified);
                                zip.putNextEntry(entry);
                                try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
                                    input.transferTo(zip);
                                }
                                zip.closeEntry();
                                fileCount[0]++;
                                if (fileCount[0] % 250 == 0) progress.accept("Compressing app files... " + fileCount[0] + " files added");
                            } catch (IOException e) {
                                throw new ZipCreationException(e);
                            }
                        });
            } catch (ZipCreationException e) {
                Files.deleteIfExists(zipFile);
                throw e.ioException;
            }
        }
    }

    private static void deleteBuildDirectory(Path buildDirectory) throws IOException {
        try (var paths = Files.walk(buildDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    private static String readLastLines(Path file, int count) throws IOException {
        var lines = Files.readAllLines(file);
        int start = Math.max(0, lines.size() - count);
        return String.join(System.lineSeparator(), lines.subList(start, lines.size()));
    }

    private static final class ZipCreationException extends RuntimeException {
        private final IOException ioException;

        private ZipCreationException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }
    }
}
