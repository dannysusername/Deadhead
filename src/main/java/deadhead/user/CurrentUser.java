package deadhead.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Who is asking. Every user-scoped service takes a user id as an argument
 * rather than reaching for the security context itself — this is the single
 * place the web layer turns "the current session" into that id, which keeps
 * the background sync (which has no session at all) honest.
 */
@Service
public class CurrentUser {

    private final UserService users;

    public CurrentUser(UserService users) {
        this.users = users;
    }

    public UserAccount account() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new IllegalStateException("Not signed in.");
        }
        return users.byEmail(auth.getName());
    }

    public long id() {
        return account().id();
    }
}
