package com.poolapp.db;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppApiConfiguration {
    private static final Properties PROPERTIES = load();

    private AppApiConfiguration() {
    }

    public static final String DEFAULT_WEB_URL = "https://silverdice.josueguerra5555.workers.dev";

    public static String getWebAppUrl() {
        String url = getBaseUrl().replaceAll("/+$", "");
        return url.matches("(?i)https?://.+") ? url : DEFAULT_WEB_URL;
    }

    public static String getBaseUrl() {
        String environment = System.getenv("COMP_MANAGER_API_URL");
        if (environment != null && !environment.isBlank()) return environment.trim();
        String systemProperty = System.getProperty("comp.manager.api.url");
        if (systemProperty != null && !systemProperty.isBlank()) return systemProperty.trim();
        return PROPERTIES.getProperty("api.base.url", "").trim();
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream input = AppApiConfiguration.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load packaged API configuration", e);
        }
        File external = new File("config.properties");
        if (external.isFile()) {
            try (InputStream input = new FileInputStream(external)) {
                properties.load(input);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to load external API configuration", e);
            }
        }
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            File userConfig = new File(userHome, ".compmanager/config.properties");
            if (userConfig.isFile()) {
                try (InputStream input = new FileInputStream(userConfig)) {
                    properties.load(input);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to load per-user API configuration", e);
                }
            }
        }
        return properties;
    }
}
