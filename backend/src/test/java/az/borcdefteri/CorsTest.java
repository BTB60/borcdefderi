package az.borcdefteri;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CORS: exact origins and Vercel preview patterns are allowed, everything else is refused. */
class CorsTest extends IntegrationTestBase {

    private org.springframework.test.web.servlet.ResultActions preflight(String origin) throws Exception {
        return mvc.perform(options("/api/auth/login")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"));
    }

    @Test
    void exactProductionOriginIsAllowed() throws Exception {
        preflight("https://borcdefderi.vercel.app").andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", is("https://borcdefderi.vercel.app")));
    }

    @Test
    void vercelPreviewOriginMatchesPattern() throws Exception {
        String preview = "https://borcdefderi-oysb2aikg-btb60s-projects.vercel.app";
        preflight(preview).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", is(preview)));
    }

    @Test
    void otherOriginsAreRefused() throws Exception {
        preflight("https://evil.example.com").andExpect(status().isForbidden());
        preflight("https://borcdefderi-x-someone-else.vercel.app").andExpect(status().isForbidden());
    }
}
