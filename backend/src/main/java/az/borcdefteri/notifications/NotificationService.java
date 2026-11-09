package az.borcdefteri.notifications;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Stored notifications for a store (e.g. "Ödənişiniz təsdiqləndi"). They are shown through the existing
 * GET /api/notifications list (DashboardService), which only returns the caller's own store.
 */
@Service
public class NotificationService {

    public record StoredNotification(String kind, String title, String message, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Called inside the caller's transaction, so the notification exists only if the action committed. */
    public void create(UUID storeId, String kind, String title, String message) {
        jdbc.sql("""
                INSERT INTO store_notifications (id, store_id, kind, title, message)
                VALUES (:id, :storeId, :kind, :title, :message)
                """)
                .param("id", UUID.randomUUID()).param("storeId", storeId).param("kind", kind)
                .param("title", title).param("message", message)
                .update();
    }

    public List<StoredNotification> recent(UUID storeId, OffsetDateTime since) {
        return jdbc.sql("""
                SELECT kind, title, message, created_at FROM store_notifications
                WHERE store_id = :s AND created_at >= :since ORDER BY created_at DESC LIMIT 10
                """)
                .param("s", storeId).param("since", since)
                .query((rs, n) -> new StoredNotification(rs.getString("kind"), rs.getString("title"),
                        rs.getString("message"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
