package az.borcdefteri.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limit for credential endpoints (POST /api/auth/**), per client IP.
 * Keyed on the IP the framework resolved (forward headers are honoured by Spring when behind a proxy).
 * Single-instance only; use a shared store (e.g. Redis) when scaling horizontally.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;

    private final int limit;
    private final ObjectMapper objectMapper;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int limitPerMinute, ObjectMapper objectMapper) {
        this.limit = limitPerMinute;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().startsWith("/api/auth/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String key = request.getRemoteAddr();
        Window window = windows.compute(key, (k, w) ->
                (w == null || now - w.start >= WINDOW_MS) ? new Window(now) : w);
        int count = window.count.incrementAndGet();
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().start >= WINDOW_MS);
        }
        if (count > limit) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", "60");
            objectMapper.writeValue(response.getOutputStream(),
                    Map.of("error", "RATE_LIMITED", "message", "Çox sayda sorğu. Bir dəqiqə sonra yenidən cəhd edin"));
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class Window {
        final long start;
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        Window(long start) {
            this.start = start;
        }
    }
}
