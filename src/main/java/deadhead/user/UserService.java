package deadhead.user;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Signing up and signing in. Passwords are BCrypt-hashed here and nowhere
 * else; nothing outside this class ever sees a plaintext password.
 */
@Service
public class UserService implements UserDetailsService {

    /** Deliberately permissive — "looks like an address" is all we can honestly check. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    static final int MIN_PASSWORD = 10;
    private static final int MAX_PASSWORD = 200;   // BCrypt silently truncates past 72 bytes

    private final UserRepo repo;
    private final PasswordEncoder encoder;

    public UserService(UserRepo repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    public UserAccount register(String email, String password) {
        String key = UserRepo.normalize(email);
        if (key.isEmpty()) throw new IllegalArgumentException("Enter your email address.");
        if (key.length() > 254 || !EMAIL.matcher(key).matches()) {
            throw new IllegalArgumentException("That doesn't look like an email address.");
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException(
                "Use a password of at least " + MIN_PASSWORD + " characters.");
        }
        if (password.length() > MAX_PASSWORD) {
            throw new IllegalArgumentException("That password is too long.");
        }
        return repo.create(key, encoder.encode(password));
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserAccount a = repo.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));
        return new User(a.email(), a.passwordHash(), AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    public UserAccount byEmail(String email) {
        return repo.findByEmail(email)
            .orElseThrow(() -> new IllegalStateException("Signed in as an account that no longer exists."));
    }
}
