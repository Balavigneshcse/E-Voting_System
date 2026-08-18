package Backend;

import Backend.security.MachineSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled for the housekeeping jobs that keep the security tables bounded:
 * pruning used request nonces and expired biometric tokens. Without it those tables would
 * grow for the lifetime of the deployment.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MachineSecurityProperties.class)
public class EvotingBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(EvotingBackendApplication.class, args);
    }
}
