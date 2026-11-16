package az.borcdefteri;

import az.borcdefteri.auth.PasswordResetMailer;
import az.borcdefteri.common.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** İki addımlı giriş (e-mail code) and "sign out on every device". */
class TwoFactorTest extends IntegrationTestBase {

    static class CodeMailer extends PasswordResetMailer {
        final Map<String, String> codes = new ConcurrentHashMap<>();

        CodeMailer(ObjectProvider<JavaMailSender> sender, AppProperties props) {
            super(sender, "smtp.test", "noreply@test.az", props);
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean sendLoginCode(String to, String fullName, String code, int minutes) {
            codes.put(to, code);
            return true;
        }
    }

    @TestConfiguration
    static class MailConfig {
        @Bean
        @Primary
        CodeMailer codeMailer(ObjectProvider<JavaMailSender> sender, AppProperties props) {
            return new CodeMailer(sender, props);
        }
    }

    @Autowired
    private CodeMailer mailer;

    @Test
    void withTwoStepLoginTheEmailedCodeIsNeeded() throws Exception {
        String email = "iki-" + UUID.randomUUID() + "@test.az";
        String owner = body(postRaw("/api/auth/register", Map.of("email", email, "password", OWNER_PASSWORD, "fullName", "İki Addım",
                "storeName", "İki Addım Mağaza"))).get("token").asText();
        put(owner, "/api/profile/two-factor", Map.of("enabled", true, "currentPassword", "wrong-password")).andExpect(status().is4xxClientError());
        put(owner, "/api/profile/two-factor", Map.of("enabled", true, "currentPassword", OWNER_PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorEnabled", is(true)));

        JsonNode first = body(postRaw("/api/auth/login", Map.of("email", email, "password", OWNER_PASSWORD))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error", is("TWO_FACTOR_REQUIRED"))));
        String challenge = first.get("fields").get("challengeId").asText();
        String code = mailer.codes.get(email);

        postRaw("/api/auth/login/verify", Map.of("challengeId", challenge, "code", "000000".equals(code) ? "111111" : "000000"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error", is("CODE_INVALID")));
        String token = body(postRaw("/api/auth/login/verify", Map.of("challengeId", challenge, "code", code))
                .andExpect(status().isOk())).get("token").asText();
        postRaw("/api/auth/login/verify", Map.of("challengeId", challenge, "code", code)).andExpect(status().isUnauthorized());
        get(token, "/api/auth/me").andExpect(status().isOk());

        // sign out everywhere: the old token stops, the new one works
        Thread.sleep(1100); // JWT "iat" has whole-second precision
        String fresh = body(post(token, "/api/profile/logout-all", Map.of()).andExpect(status().isOk())).get("token").asText();
        get(owner, "/api/auth/me").andExpect(status().isUnauthorized());
        get(fresh, "/api/auth/me").andExpect(status().isOk());
    }
}
