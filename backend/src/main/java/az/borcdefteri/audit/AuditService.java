package az.borcdefteri.audit;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/** Append-only audit trail (table audit_logs is protected by a DB trigger). */
@Service
public class AuditService {

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void log(UUID storeId, UUID actorId, String action, String entityType, String entityId, String details) {
        String ip = currentIp();
        jdbc.sql("""
                INSERT INTO audit_logs (store_id, actor_id, action, entity_type, entity_id, details, ip_address)
                VALUES (:storeId, :actorId, :action, :entityType, :entityId, :details, :ip)
                """)
                .param("storeId", storeId)
                .param("actorId", actorId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("details", truncate(details, 2000))
                .param("ip", ip)
                .update();
    }

    private static String currentIp() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servlet) {
                return truncate(servlet.getRequest().getRemoteAddr(), 64);
            }
        } catch (DataAccessException | IllegalStateException ignored) {
            // no request context (scheduled jobs)
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
