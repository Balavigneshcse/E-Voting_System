package Backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Data initialization check...");

        try {
            // Check if states table already has data
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM states", Integer.class);
            if (count != null && count > 0) {
                System.out.println("✓ Data already exists in database (" + count + " states). Skipping initialization.");
                return;
            }
        } catch (Exception e) {
            System.out.println("⚠ Could not query states table: " + e.getMessage());
            System.out.println("Skipping data initialization. Database should be pre-populated via backup restore.");
            return;
        }
    }
}