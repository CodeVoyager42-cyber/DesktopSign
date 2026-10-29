package com.example.signer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * An optional per-machine settings file, for an IT department that installs
 * the MSI centrally and needs it to talk to a different coordination server
 * than the installer was built for.
 *
 * <p>{@code %APPDATA%\SignFlow\settings.properties} on Windows,
 * {@code ~/.signflow/settings.properties} elsewhere. It is read before the
 * context starts and takes precedence over the address baked into the
 * launcher by {@code scripts/package.sh}, which is the whole point: the
 * installer carries a sensible default and this overrides it without
 * rebuilding or repackaging anything.
 *
 * <pre>
 * # %APPDATA%\SignFlow\settings.properties
 * signature.remote.base-url=https://signflow.internal.example.com
 * </pre>
 *
 * <p>Two deliberate choices:
 *
 * <ul>
 *   <li><b>A bad value fails start-up, it is not ignored.</b> Silently falling
 *       back to the built-in address would leave an administrator believing
 *       the override took effect while every desktop still talked to the old
 *       server - the one failure mode worth being loud about. The address is
 *       checked by {@link ServerUrlRules}, the same rules the build applies,
 *       and plain HTTP is refused here regardless of what
 *       {@link ProductionSafetyValidator} would go on to say about it.</li>
 *   <li><b>Only known keys are accepted.</b> This file is not a general
 *       property back door into the application: an unrecognised key is a
 *       mistake worth reporting, not a feature. In particular it cannot
 *       switch profiles, so it is no route back to the {@code dev} profile in
 *       a packaged build.</li>
 * </ul>
 */
public class DesktopSettingsFile implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DesktopSettingsFile.class);

    static final String FILE_NAME = "settings.properties";

    /** The property source name, so the override is identifiable in /actuator/env-style dumps. */
    static final String SOURCE_NAME = "signflowDesktopSettings";

    static final String SERVER_URL = "signature.remote.base-url";

    /**
     * Everything this file may set. Profiles are absent on purpose - see the
     * class comment.
     */
    private static final Set<String> ALLOWED = Set.of(SERVER_URL);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        Map<String, Object> overrides = read(defaultSettingsFile());
        if (overrides.isEmpty()) {
            return;
        }
        // addFirst: above the system properties the packaged launcher sets, so
        // the file wins over the baked-in address.
        environment.getPropertySources()
                .addFirst(new MapPropertySource(SOURCE_NAME, overrides));
        log.info("Coordination server address overridden by {}: {}",
                defaultSettingsFile(), overrides.get(SERVER_URL));
    }

    /** {@code %APPDATA%\SignFlow\settings.properties}, or {@code ~/.signflow/settings.properties}. */
    static Path defaultSettingsFile() {
        String appData = System.getenv("APPDATA");
        if (isWindows() && appData != null && !appData.isBlank()) {
            return Path.of(appData, "SignFlow", FILE_NAME);
        }
        return Path.of(System.getProperty("user.home"), ".signflow", FILE_NAME);
    }

    /**
     * The validated overrides in {@code file}, or an empty map when there is
     * no such file.
     *
     * @throws IllegalStateException if the file exists but cannot be read, or
     *                               holds an unknown key or an unusable address
     */
    static Map<String, Object> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("SignFlow could not read its settings file "
                    + file + ": " + e.getMessage(), e);
        }

        Map<String, Object> overrides = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String name = key.trim();
            if (!ALLOWED.contains(name)) {
                throw new IllegalStateException("SignFlow does not understand '" + name
                        + "' in " + file + ". This file may set only: "
                        + String.join(", ", ALLOWED) + ".");
            }
            overrides.put(name, properties.getProperty(key));
        }

        String url = (String) overrides.get(SERVER_URL);
        if (url != null) {
            // Plain HTTP is refused here even though a loopback address would
            // satisfy the production validator: a file edited by hand on a
            // managed desktop is not the place to opt out of TLS.
            String problem = ServerUrlRules.problem(url, false);
            if (problem != null) {
                throw new IllegalStateException("SignFlow cannot use the "
                        + SERVER_URL + " in " + file + ": " + problem + ".");
            }
        }
        return overrides;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
