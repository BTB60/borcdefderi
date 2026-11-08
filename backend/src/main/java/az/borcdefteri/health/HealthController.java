package az.borcdefteri.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Public health check for Render / uptime monitors. Reports database connectivity. */
@RestController
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        String db;
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            db = "UP";
        } catch (RuntimeException e) {
            db = "DOWN";
        }
        boolean up = "UP".equals(db);
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", up ? "UP" : "DEGRADED", "database", db, "time", Instant.now().toString()));
    }
}
