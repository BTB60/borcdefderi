package az.borcdefteri.config;

import az.borcdefteri.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Creates the first SUPER_ADMIN from environment variables (ADMIN_EMAIL / ADMIN_PASSWORD) when none exists.
 * No credentials are embedded in the code or repository.
 */
@Component
public class SuperAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final AppProperties props;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public SuperAdminBootstrap(AppProperties props, JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.props = props;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = props.bootstrap().superAdminEmail();
        String password = props.bootstrap().superAdminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (password.length() < 12) {
            throw new IllegalStateException("ADMIN_PASSWORD ən azı 12 simvol olmalıdır");
        }
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM users WHERE platform_role = 'SUPER_ADMIN'")
                .query(Long.class).single() > 0;
        if (exists) {
            return;
        }
        jdbc.sql("""
                INSERT INTO users (id, email, password_hash, full_name, platform_role)
                VALUES (:id, :email, :hash, :name, 'SUPER_ADMIN')
                """)
                .param("id", UUID.randomUUID())
                .param("email", email.trim().toLowerCase(Locale.ROOT))
                .param("hash", passwordEncoder.encode(password))
                .param("name", props.bootstrap().superAdminName())
                .update();
        log.info("Platform SUPER_ADMIN account created from environment configuration");
    }
}
