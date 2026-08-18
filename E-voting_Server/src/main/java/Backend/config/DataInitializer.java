package Backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Start-up sanity check, not a seeder.
 *
 * <p>This application does not generate its own data. The electoral geography and
 * sample voters come from the Flyway migrations ({@code V1}, {@code V4}); a real
 * deployment's full voter roll is expected to arrive as a database restore. All this
 * class does is confirm that data landed, so a genuinely empty database is reported at
 * startup instead of surfacing later as "no candidates" on the first terminal that
 * connects.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final JdbcTemplate jdbc;

    public DataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM states", Integer.class);
            if (count != null && count > 0) {
                log.info("Electoral geography present: {} state(s) loaded.", count);
            } else {
                log.warn("The states table is empty. Flyway should have populated it from "
                        + "V1 and V4 — check that migrations ran to completion.");
            }
        } catch (RuntimeException e) {
            log.error("Could not query the states table at startup: {}", e.getMessage());
        }
    }
}
