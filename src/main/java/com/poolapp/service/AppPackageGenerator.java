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
        if (projectRoot == null) {
            throw new IllegalStateException("The source project was not found. Run Comp Manager from the DATABASE project checkout to generate an app bundle.");
        }

        String buildDirectoryName = "gradle-build-download-" + FILE_STAMP.format(LocalDateTime.now());
        Path buildDirectory = projectRoot.resolve(buildDirectoryName);
        Path gradleWrapper = projectRoot.resolve("gradlew.bat");
        Path buildLog = Files.createTempFile("comp-manager-package-", ".log");
        String command = "\"" + gradleWrapper + "\" -PverificationBuildDir=" + buildDirectoryName
                + " packageAppImage --no-daemon --console=plain";

        Process process = new ProcessBuilder("cmd.exe", "/d", "/s", "/c", "\"" + command + "\"")
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(buildLog.toFile())
                .start();
        int exitCode = process.waitFor();
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
        zipAppImage(appImage, zipFile);
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

    private static void zipAppImage(Path appImage, Path zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            try (var paths = Files.walk(appImage)) {
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
