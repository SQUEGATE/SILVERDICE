package com.poolapp.ui;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import java.awt.Image;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

final class AppWindowStyle {
    private static final String LOGO_RESOURCE_NAME = "/logo.jpg";
    private static Image cachedLogo;
    private static boolean logoLookupDone;

    private AppWindowStyle() {
    }

    static void apply(JFrame frame) {
        frame.setTitle("Comp Manager");
        Image logo = loadLogo();
        if (logo != null) {
            frame.setIconImage(logo);
        }
    }

    private static synchronized Image loadLogo() {
        if (logoLookupDone) {
            return cachedLogo;
        }
        logoLookupDone = true;

        URL logoResource = AppWindowStyle.class.getResource(LOGO_RESOURCE_NAME);
        if (logoResource != null) {
            cachedLogo = new ImageIcon(logoResource).getImage();
            return cachedLogo;
        }

        for (File candidate : candidateLogoFiles()) {
            if (candidate.isFile()) {
                cachedLogo = new ImageIcon(candidate.getAbsolutePath()).getImage();
                return cachedLogo;
            }
        }

        System.err.println("Comp Manager: logo.jpg was not found on the classpath or in any known project location.");
        return null;
    }

    private static File[] candidateLogoFiles() {
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        return new File[] {
                new File("logo.jpg"),
                cwd.resolve("src/main/resources/logo.jpg").toFile(),
                cwd.resolve("DATABASE/src/main/resources/logo.jpg").toFile(),
                cwd.resolve("../DATABASE/src/main/resources/logo.jpg").normalize().toFile(),
                cwd.resolve("SILVERDICE LOGO.jpg").toFile(),
                cwd.resolve("../SILVERDICE LOGO.jpg").normalize().toFile()
        };
    }
}