package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.boot.test.context.TestConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Runs against a REAL PostgreSQL instance (zonky embedded binaries, no Docker needed).
 * Flyway migrations are applied on startup, so the schema and triggers are tested as deployed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestBase.TestClockConfig.class)
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-key-with-more-than-thirty-two-chars-0123456789",
        "app.upload.dir=target/test-uploads",
        "app.bootstrap.super-admin-email=platform.admin@test.az",
        "app.bootstrap.super-admin-password=PlatformAdmin#2026",
        "app.rate-limit.auth-requests-per-minute=10000"
})
public abstract class IntegrationTestBase {

    static final String OWNER_PASSWORD = "Owner#Passw0rd";
    static final String EMPLOYEE_PASSWORD = "Worker#Passw0rd";
    static final String ADMIN_EMAIL = "platform.admin@test.az";
    static final String ADMIN_PASSWORD = "PlatformAdmin#2026";

    static final EmbeddedPostgres PG;

    static {
        try {
            // Windows locale names may contain non-ASCII characters; initdb needs a plain C locale.
            PG = EmbeddedPostgres.builder().setLocaleConfig("locale", "C").start();
        } catch (IOException e) {
            throw new IllegalStateException("Embedded PostgreSQL could not start", e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("app.cors.allowed-origins",
                () -> "https://borcdefderi.vercel.app, https://borcdefderi-*-btb60s-projects.vercel.app");
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(ZoneId.of("Asia/Baku"));
        }
    }

    /** Clock whose "today" can be moved forward to simulate subscription expiry. */
    public static final class MutableClock extends Clock {
        private final ZoneId zone;
        private volatile Instant instant;

        MutableClock(ZoneId zone) {
            this.zone = zone;
            this.instant = Instant.now();
        }

        public void setDate(LocalDate date) {
            this.instant = date.atTime(12, 0).atZone(zone).toInstant();
        }

        public void reset() {
            this.instant = Instant.now();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected MutableClock clock;

    private static String adminToken;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    // ---------- HTTP helpers ----------

    protected ResultActions get(String token, String url) throws Exception {
        return mvc.perform(auth(MockMvcRequestBuilders.get(url), token));
    }

    protected ResultActions post(String token, String url, Object body) throws Exception {
        return mvc.perform(auth(MockMvcRequestBuilders.post(url), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions put(String token, String url, Object body) throws Exception {
        return mvc.perform(auth(MockMvcRequestBuilders.put(url), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions postRaw(String url, Object body) throws Exception {
        return post(null, url, body);
    }

    protected ResultActions uploadReceipt(String token, String planCode, String fileName, String contentType, byte[] bytes)
            throws Exception {
        return mvc.perform(auth(multipart("/api/subscriptions")
                        .file(new MockMultipartFile("receipt", fileName, contentType, bytes))
                        .param("planCode", planCode)
                        .param("payerReference", "TRX-" + UUID.randomUUID().toString().substring(0, 8)), token));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    protected JsonNode body(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }

    // ---------- domain helpers ----------

    protected String register(String storeName) throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@test.az";
        JsonNode res = body(postRaw("/api/auth/register", Map.of(
                "email", email,
                "password", OWNER_PASSWORD,
                "fullName", "Sahib " + storeName,
                "storeName", storeName,
                "storePhone", "+994501112233")));
        return res.get("token").asText();
    }

    protected String login(String email, String password) throws Exception {
        return body(postRaw("/api/auth/login", Map.of("email", email, "password", password))).get("token").asText();
    }

    protected String adminToken() throws Exception {
        if (adminToken == null) {
            adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        }
        return adminToken;
    }

    /** Owner submits a plan (receipt uploaded) and the platform admin approves it. Returns subscription id. */
    protected UUID activateSubscription(String ownerToken, String planCode) throws Exception {
        JsonNode sub = body(uploadReceipt(ownerToken, planCode, "receipt.png", "image/png", pngBytes()));
        UUID id = UUID.fromString(sub.get("id").asText());
        post(adminToken(), "/api/admin/subscriptions/" + id + "/approve", Map.of()).andReturn();
        return id;
    }

    protected UUID createCustomer(String token, String name, String phone) throws Exception {
        JsonNode res = body(post(token, "/api/customers", Map.of("name", name, "phone", phone)));
        return UUID.fromString(res.get("id").asText());
    }

    protected UUID createCompany(String token, String name) throws Exception {
        JsonNode res = body(post(token, "/api/companies", Map.of("name", name, "phone", "+994551234567")));
        return UUID.fromString(res.get("id").asText());
    }

    protected ResultActions createDebt(String token, String type, UUID partyId, String amount) throws Exception {
        return post(token, "/api/debts", Map.of(
                "debtType", type,
                "partyId", partyId.toString(),
                "amount", amount,
                "debtDate", LocalDate.now(clock).toString(),
                "description", "Test borcu"));
    }

    protected UUID debtIdOf(String token, String type, UUID partyId, String amount) throws Exception {
        return UUID.fromString(body(createDebt(token, type, partyId, amount)).get("id").asText());
    }

    protected ResultActions pay(String token, UUID debtId, String amount, String method) throws Exception {
        return post(token, "/api/debts/" + debtId + "/payments", Map.of(
                "amount", amount, "paymentMethod", method, "description", "Ödəniş"));
    }

    protected static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
    }

    protected static List<String> perms(String... p) {
        return List.of(p);
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.az";
    }
}
