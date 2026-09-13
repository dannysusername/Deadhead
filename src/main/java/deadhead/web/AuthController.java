package deadhead.web;

import deadhead.user.CurrentUser;
import deadhead.user.UserAccount;
import deadhead.user.UserRepo;
import deadhead.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Sign up, and "who am I". Signing in and out is Spring Security's own filter. */
@RestController
public class AuthController {

    private final UserService users;
    private final CurrentUser current;

    public AuthController(UserService users, CurrentUser current) {
        this.users = users;
        this.current = current;
    }

    public record Registration(String email, String password) {}

    /**
     * Create the account and sign the browser straight in — a new pilot should
     * land on their empty schedule, not back on a login form.
     */
    @PostMapping(path = "/api/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> register(@RequestBody Registration body, HttpServletRequest req) {
        UserAccount account;
        try {
            account = users.register(body.email(), body.password());
        } catch (UserRepo.DuplicateEmailException taken) {
            throw new IllegalArgumentException(taken.getMessage());
        }
        try {
            // Same servlet-container login the form uses, so the session and
            // its CSRF token are established exactly as they would be normally.
            req.login(account.email(), body.password());
        } catch (Exception signInFailed) {
            // The account exists; they can sign in by hand.
            return Map.of("email", account.email(), "signedIn", "false");
        }
        return Map.of("email", account.email(), "signedIn", "true");
    }

    @GetMapping("/api/me")
    public Map<String, Object> me() {
        UserAccount a = current.account();
        return Map.of("email", a.email(), "since", a.createdAt());
    }
}
