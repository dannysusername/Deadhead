package deadhead.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The doors: what a stranger can reach, and what signing up actually gets you. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthWebTest {

    @Autowired MockMvc mvc;

    private static String uniqueEmail(String tag) {
        return tag + "-" + System.nanoTime() + "@example.com";
    }

    private void signUp(String email, String password) throws Exception {
        mvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void the_app_and_its_api_are_closed_to_strangers() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/flights?from=2026-07-13&to=2026-07-19"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void the_sign_in_pages_are_open_and_styled() throws Exception {
        // /login and /register forward to the static files; MockMvc records the
        // forward rather than following it, so assert both halves.
        mvc.perform(get("/login")).andExpect(forwardedUrl("/login.html"));
        mvc.perform(get("/register")).andExpect(forwardedUrl("/register.html"));

        mvc.perform(get("/login.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/style.css")));
        mvc.perform(get("/register.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Create your account")));
        mvc.perform(get("/style.css")).andExpect(status().isOk());
    }

    @Test
    void signing_up_creates_an_account_you_can_sign_in_with() throws Exception {
        String email = uniqueEmail("web");
        signUp(email, "correct-horse-battery");

        mvc.perform(formLogin("/login").user(email).password("correct-horse-battery"))
            .andExpect(authenticated())
            .andExpect(redirectedUrlPattern("/**"));
    }

    @Test
    void a_wrong_password_does_not_sign_you_in() throws Exception {
        String email = uniqueEmail("bad");
        signUp(email, "correct-horse-battery");

        mvc.perform(formLogin("/login").user(email).password("not-the-password"))
            .andExpect(unauthenticated());
    }

    @Test
    void a_duplicate_signup_is_a_readable_400() throws Exception {
        String email = uniqueEmail("dupe");
        signUp(email, "correct-horse-battery");

        mvc.perform(post("/api/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"another-long-one\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("already an account")));
    }

    @Test
    void a_signed_in_pilot_sees_their_own_empty_schedule() throws Exception {
        String email = uniqueEmail("me");
        signUp(email, "correct-horse-battery");

        mvc.perform(get("/api/config").with(user(email)))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(email)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"flights\":0")));
    }

    @Test
    void writes_without_a_csrf_token_are_rejected() throws Exception {
        String email = uniqueEmail("csrf");
        signUp(email, "correct-horse-battery");

        mvc.perform(post("/api/settings").with(user(email))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"home\":\"KTMB\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/settings").with(user(email)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"home\":\"KTMB\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void signing_out_ends_the_session() throws Exception {
        String email = uniqueEmail("out");
        signUp(email, "correct-horse-battery");

        // Logout is a POST with a token — a stray <img src="/logout"> must not do it.
        mvc.perform(post("/logout").with(user(email)).with(csrf()))
            .andExpect(unauthenticated());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String email) {
        return org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.user(email).roles("USER");
    }
}
