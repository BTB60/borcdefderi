package az.borcdefteri;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** "Parametrlər": personal data, store data, password change (with session revocation) and profile picture. */
class ProfileTest extends IntegrationTestBase {

    @Test
    void ownerEditsPersonalAndStoreDataEvenWithoutSubscription() throws Exception {
        String owner = register("Profil Mağazası " + UUID.randomUUID());
        get(owner, "/api/profile").andExpect(status().isOk()).andExpect(jsonPath("$.role", is("STORE_OWNER")));

        put(owner, "/api/profile", Map.of("fullName", "Nigar Məmmədova", "phone", "+994 50 111 22 33"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName", is("Nigar Məmmədova")))
                .andExpect(jsonPath("$.phone", is("+994 50 111 22 33")));
        put(owner, "/api/profile/store", Map.of("name", "Nigar Market", "phone", "012 444 55 66", "address", "Bakı, Nərimanov"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName", is("Nigar Market")))
                .andExpect(jsonPath("$.storeAddress", is("Bakı, Nərimanov")));
        get(owner, "/api/auth/me").andExpect(jsonPath("$.storeName", is("Nigar Market")))
                .andExpect(jsonPath("$.phone", is("+994 50 111 22 33")));
        put(owner, "/api/profile", Map.of("fullName", "", "phone", "<x>")).andExpect(status().isBadRequest());
    }

    @Test
    void changingEmailNeedsCurrentPasswordAndAFreeAddress() throws Exception {
        String owner = register("E-poçt Mağazası " + UUID.randomUUID());
        String taken = body(get(register("Başqa " + UUID.randomUUID()), "/api/auth/me")).get("email").asText();
        String fresh = uniqueEmail("yeni");

        put(owner, "/api/profile", Map.of("fullName", "Sahib", "email", fresh))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("WRONG_PASSWORD")));
        put(owner, "/api/profile", Map.of("fullName", "Sahib", "email", taken, "currentPassword", OWNER_PASSWORD))
                .andExpect(status().isConflict());
        put(owner, "/api/profile", Map.of("fullName", "Sahib", "email", fresh.toUpperCase(java.util.Locale.ROOT), "currentPassword", OWNER_PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email", is(fresh)));
        login(fresh, OWNER_PASSWORD);
    }

    @Test
    void passwordChangeSignsOutOtherSessionsAndKeepsThisOne() throws Exception {
        String owner = register("Şifrə Mağazası " + UUID.randomUUID());
        String email = body(get(owner, "/api/auth/me")).get("email").asText();
        String otherDevice = login(email, OWNER_PASSWORD);
        Thread.sleep(1100); // JWT "iat" has one-second precision

        put(owner, "/api/profile/password", Map.of("currentPassword", "yanlış-şifrə", "newPassword", "YeniŞifrə#2026"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("WRONG_PASSWORD")));
        put(owner, "/api/profile/password", Map.of("currentPassword", OWNER_PASSWORD, "newPassword", "qısa"))
                .andExpect(status().isBadRequest());
        put(owner, "/api/profile/password", Map.of("currentPassword", OWNER_PASSWORD, "newPassword", OWNER_PASSWORD))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("PASSWORD_UNCHANGED")));

        JsonNode res = body(put(owner, "/api/profile/password",
                Map.of("currentPassword", OWNER_PASSWORD, "newPassword", "YeniŞifrə#2026")).andExpect(status().isOk()));
        String newToken = res.get("token").asText();

        get(otherDevice, "/api/profile").andExpect(status().isUnauthorized());
        get(owner, "/api/profile").andExpect(status().isUnauthorized());
        get(newToken, "/api/profile").andExpect(status().isOk());
        postRaw("/api/auth/login", Map.of("email", email, "password", OWNER_PASSWORD)).andExpect(status().isUnauthorized());
        login(email, "YeniŞifrə#2026");
    }

    @Test
    void employeesAndAdminUseTheSameSettingsWithTheirOwnRules() throws Exception {
        String owner = register("İşçi Profil " + UUID.randomUUID());
        activateSubscription(owner, "MONTHLY");
        String email = uniqueEmail("profil-isci");
        post(owner, "/api/employees", Map.of("email", email, "password", EMPLOYEE_PASSWORD, "fullName", "İşçi",
                "permissions", perms("CUSTOMER_DEBTS"))).andExpect(status().isCreated());
        String worker = login(email, EMPLOYEE_PASSWORD);
        put(worker, "/api/profile", Map.of("fullName", "Yeni Ad")).andExpect(status().isOk());
        put(worker, "/api/profile/store", Map.of("name", "Oğurlanmış ad")).andExpect(status().isForbidden());

        // Platform admin: settings work without a store; admin passwords need at least 12 characters.
        String admin = adminToken();
        get(admin, "/api/profile").andExpect(jsonPath("$.platformRole", is("SUPER_ADMIN")))
                .andExpect(jsonPath("$.storeId").doesNotExist());
        put(admin, "/api/profile/password", Map.of("currentPassword", ADMIN_PASSWORD, "newPassword", "Qisa#2026"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("PASSWORD_TOO_SHORT")));
        Map<String, Object> adminUpdate = new HashMap<>(Map.of("fullName", "Platforma Rəhbəri"));
        put(admin, "/api/profile", adminUpdate).andExpect(status().isOk());
        put(admin, "/api/profile/store", Map.of("name", "x")).andExpect(status().isForbidden());
    }

    @Test
    void profilePictureIsResizedCleanedAndPrivate() throws Exception {
        String owner = register("Şəkil Mağazası " + UUID.randomUUID());
        String other = register("Şəkilsiz Mağaza " + UUID.randomUUID());
        get(owner, "/api/profile/avatar").andExpect(status().isNotFound());

        mvc.perform(multipart("/api/profile/avatar").file(new MockMultipartFile("file", "foto.jpg", "image/jpeg", jpeg(800, 600)))
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar", is(true)))
                .andExpect(jsonPath("$.avatarVersion", notNullValue()));
        byte[] stored = get(owner, "/api/profile/avatar").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(stored));
        assertEquals(256, img.getWidth());
        assertEquals(256, img.getHeight());
        get(owner, "/api/auth/me").andExpect(jsonPath("$.hasAvatar", is(true)));
        get(other, "/api/profile/avatar").andExpect(status().isNotFound());

        mvc.perform(multipart("/api/profile/avatar").file(new MockMultipartFile("file", "x.png", "image/png", "not an image".getBytes()))
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest());
        byte[] fakePng = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0};
        mvc.perform(multipart("/api/profile/avatar").file(new MockMultipartFile("file", "x.png", "image/png", fakePng))
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", is("INVALID_IMAGE")));

        mvc.perform(delete("/api/profile/avatar").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasAvatar", is(false)));
        get(owner, "/api/profile/avatar").andExpect(status().isNotFound());
        get(null, "/api/profile").andExpect(status().isUnauthorized());
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(0x10, 0x1C, 0x35));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0xD6, 0xAD, 0x60));
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
