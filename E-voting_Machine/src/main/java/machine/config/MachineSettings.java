package machine.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Terminal configuration, loaded from {@code config.properties}.
 *
 * <p>Looks for the file next to the jar first and falls back to the packaged copy, so an
 * operator can change the server address or the provisioning secret on a deployed Pi
 * without rebuilding.
 */
public final class MachineSettings {

    private static final String CONFIG_FILE_NAME = "config.properties";

    private final Properties properties = new Properties();
    private final Path       dataDirectory;

    private MachineSettings(Properties loaded, Path dataDirectory) {
        this.properties.putAll(loaded);
        this.dataDirectory = dataDirectory;
    }

    public static MachineSettings load() {
        Properties loaded = new Properties();

        Path external = Paths.get(CONFIG_FILE_NAME).toAbsolutePath();
        if (Files.isReadable(external)) {
            try (InputStream in = Files.newInputStream(external)) {
                loaded.load(in);
                System.out.println("Configuration loaded from " + external);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + external, e);
            }
        } else {
            try (InputStream in = MachineSettings.class.getClassLoader()
                    .getResourceAsStream(CONFIG_FILE_NAME)) {
                if (in == null) {
                    throw new IllegalStateException(
                            CONFIG_FILE_NAME + " not found beside the jar or on the classpath.");
                }
                loaded.load(in);
                System.out.println("Configuration loaded from the packaged " + CONFIG_FILE_NAME);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read the packaged " + CONFIG_FILE_NAME, e);
            }
        }

        Path dataDirectory = Paths.get(loaded.getProperty("DATA_DIR", "evoting-data")).toAbsolutePath();
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the data directory " + dataDirectory, e);
        }
        return new MachineSettings(loaded, dataDirectory);
    }

    // ── Server connection ───────────────────────────────────────────────────

    public String serverUrl() {
        String url = required("SERVER_URL").replaceAll("/+$", "");
        if (!url.startsWith("https://") && !allowInsecureTransport()) {
            throw new IllegalStateException("""
                    SERVER_URL must use https. Ballots must not travel over plain HTTP.
                    Set SERVER_URL=https://<server>:8443, or set ALLOW_INSECURE_TRANSPORT=true \
                    if you are knowingly testing without TLS.""");
        }
        return url;
    }

    /**
     * Escape hatch for a bench test without certificates.
     *
     * <p>Off by default, and the terminal prints a warning when it is on, because with it
     * enabled a ballot travels in the clear.
     */
    public boolean allowInsecureTransport() {
        return flag("ALLOW_INSECURE_TRANSPORT", false);
    }

    /** PKCS12 or JKS truststore holding the server certificate. Preferred over trust-all. */
    public String truststorePath()     { return optional("TRUSTSTORE_PATH"); }
    public String truststorePassword() { return optional("TRUSTSTORE_PASSWORD"); }

    /**
     * Accepts any server certificate.
     *
     * <p>This removes the guarantee that the terminal is talking to the real server rather
     * than to something impersonating it, so it is only appropriate on an isolated bench.
     */
    public boolean trustAnyCertificate() {
        return flag("TRUST_ANY_CERTIFICATE", false);
    }

    // ── Identity ────────────────────────────────────────────────────────────

    public String machineId() {
        return required("MACHINE_ID");
    }

    /** One-time secret issued by the admin dashboard when the terminal was provisioned. */
    public String provisioningSecret() {
        return required("PROVISIONING_SECRET");
    }

    /** Second factor for election-officer actions at the terminal. May be absent. */
    public String adminKey() {
        return optional("ADMIN_KEY");
    }

    // ── Behaviour ───────────────────────────────────────────────────────────

    public int sessionTimeoutSeconds() {
        return number("SESSION_TIMEOUT_SECONDS", 120);
    }

    public int queueRetrySeconds() {
        return number("QUEUE_RETRY_SECONDS", 15);
    }

    public boolean kioskFullScreen() {
        return flag("KIOSK_FULLSCREEN", false);
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path pendingVotesFile() {
        return dataDirectory.resolve("pending-votes.dat");
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    private String required(String key) {
        String value = optional(key);
        if (value == null) {
            throw new IllegalStateException(key + " is not set in " + CONFIG_FILE_NAME);
        }
        return value;
    }

    private String optional(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private boolean flag(String key, boolean fallback) {
        String value = optional(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private int number(String key, int fallback) {
        String value = optional(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println(key + " is not a number, using " + fallback);
            return fallback;
        }
    }
}
