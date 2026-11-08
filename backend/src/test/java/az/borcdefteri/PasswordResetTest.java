package az.borcdefteri;

import az.borcdefteri.auth.PasswordResetMailer;
import az.borcdefteri.common.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** "Şifrəni unutdum": e-mail link, admin temporary password, owner resets employee, forced password change. */
class PasswordResetTest extends IntegrationTestBase {

    /** Captures reset links instead of talking to an SMTP server. */
    static class CapturingMailer extends PasswordResetMailer {
        final Map<String, String> links = new ConcurrentHashMap<>();

        CapturingMailer(ObjectProvider<JavaMailSender> sender, AppProperties props) {
            super(sender, "smtp.test", "noreply@test.az", props);
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean send(String to, String fullName, String link, int minutes) {
            links.put(to, link);
            return true;
        }
    }

    @TestConfiguration
    static class MailConfig {
        @Bean
        @Primary
        CapturingMailer capturingMailer(ObjectProvider<JavaMailSender> sender, AppProperties props) {
            return new CapturingMailer(sender, props);
        }
    }

    @Autowired
    private CapturingMailer mailer;

    @BeforeEach
    void clearMail() {
        mailer.links.clear();
    }

    @Test
    void forgotPasswordAnswersTheSameForUnknownEmails() throws Exception {
        postRaw("/api/auth/forgot-password", Map.of("email", "hec-kim-" + UUID.randomUUID() + "@test.az"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emailEnabled", is(true)));
        Thread.sleep(300);
        assertTrue(mailer.links.isEmpty());
    }

    @Test
    void emailLinkResetsThePasswordOnceAndSignsOutOldSessions() throws Exception {
        String owner = register("Unutqan Mağaza " + UUID.randomUUID());
        String email = body(get(owner, "/api/auth/me")).get("email").asText();
        Thread.sleep(1100);

        postRaw("/api/auth/forgot-password", Map.of("email", email.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk());
        String link = waitForLink(email);
        assertTrue(link.startsWith("http://localhost:3000/sifre-berpa?token="), link);
        String token = link.substring(link.indexOf("token=") + 6);

        // The request is also visible to the platform admin.
        get(adminToken(), "/api/admin/password-requests").andExpect(jsonPath("$[*].email", hasItem(email)));

        postRaw("/api/auth/reset-password", Map.of("token", token, "newPassword", "qısa")).andExpect(status().isBadRequest());
        postRaw("/api/auth/reset-password", Map.of("token", token, "newPassword", "Bərpa#Şifrə2026")).andExpect(status().isOk());
        postRaw("/api/auth/reset-password", Map.of("token", token, "newPassword", "Başqa#Şifrə2026"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_RESET_LINK")));
        postRaw("/api/auth/reset-password", Map.of("token", "saxta-" + token, "newPassword", "Başqa#Şifrə2026"))
                .andExpect(status().isBadRequest());

        get(owner, "/api/auth/me").andExpect(status().isUnauthorized());
        postRaw("/api/auth/login", Map.of("email", email, "password", OWNER_PASSWORD)).andExpect(status().isUnauthorized());
        login(email, "Bərpa#Şifrə2026");
        get(adminToken(), "/api/admin/password-requests").andExpect(jsonPath("$[*].email", not(hasItem(email))));
    }

    @Test
    void expiredLinkIsRejected() throws Exception {
        String owner = register("Gecikən Mağaza " + UUID.randomUUID());
        String email = body(get(owner, "/api/auth/me")).get("email").asText();
        postRaw("/api/auth/forgot-password", Map.of("email", email)).andExpect(status().isOk());
        String token = waitForLink(email).split("token=")[1];
        clock.setDate(java.time.LocalDate.now(clock).plusDays(1));
        postRaw("/api/auth/reset-password", Map.of("token", token, "newPassword", "Bərpa#Şifrə2026"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_RESET_LINK")));
    }

    @Test
    void adminTemporaryPasswordForcesANewPasswordBeforeWork() throws Exception {
        String owner = register("Müvəqqəti Şifrə " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        JsonNode me = body(get(owner, "/api/auth/me"));
        String email = me.get("email").asText();
        String userId = me.get("userId").asText();
        Thread.sleep(1100);

        JsonNode temp = body(post(adminToken(), "/api/admin/users/" + userId + "/reset-password", Map.of())
                .andExpect(status().isOk()));
        String tempPassword = temp.get("temporaryPassword").asText();
        assertEquals(11, tempPassword.length());

        get(owner, "/api/auth/me").andExpect(status().isUnauthorized()); // old sessions signed out
        String session = login(email, tempPassword);
        get(session, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword", is(true)));
        get(session, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("PASSWORD_CHANGE_REQUIRED")));

        String fresh = body(put(session, "/api/profile/password",
                Map.of("currentPassword", tempPassword, "newPassword", "ÖzŞifrəm#2026")).andExpect(status().isOk()))
                .get("token").asText();
        get(fresh, "/api/auth/me").andExpect(jsonPath("$.mustChangePassword", is(false)));
        get(fresh, "/api/debts?type=CUSTOMER_DEBT").andExpect(status().isOk());

        // Only the platform admin may do this, and never for another platform admin.
        post(owner, "/api/admin/users/" + userId + "/reset-password", Map.of()).andExpect(status().isUnauthorized());
        post(fresh, "/api/admin/users/" + userId + "/reset-password", Map.of()).andExpect(status().isForbidden());
        String adminId = body(get(adminToken(), "/api/auth/me")).get("userId").asText();
        post(adminToken(), "/api/admin/users/" + adminId + "/reset-password", Map.of()).andExpect(status().isForbidden());
    }

    @Test
    void ownerResetsOnlyOwnEmployees() throws Exception {
        String owner = register("İşçi Bərpa " + UUID.randomUUID());
        String stranger = register("Yad Mağaza " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        String email = uniqueEmail("bərpa-isci");
        String memberId = body(post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD,
                "fullName", "İşçi", "permissions", perms("CUSTOMER_DEBTS")))).get("memberId").asText();
        String worker = login(email, EMPLOYEE_PASSWORD);

        post(stranger, "/api/employees/" + memberId + "/reset-password", Map.of()).andExpect(status().isNotFound());
        post(worker, "/api/employees/" + memberId + "/reset-password", Map.of()).andExpect(status().isForbidden());
        String temp = body(post(owner, "/api/employees/" + memberId + "/reset-password", Map.of())
                .andExpect(status().isOk())).get("temporaryPassword").asText();
        get(login(email, temp), "/api/auth/me").andExpect(jsonPath("$.mustChangePassword", is(true)));
    }

    private String waitForLink(String email) throws InterruptedException {
        for (int i = 0; i < 50 && !mailer.links.containsKey(email); i++) {
            Thread.sleep(100);
        }
        String link = mailer.links.get(email);
        assertNotNull(link, "reset e-mail was not sent to " + email);
        return link;
    }
}
