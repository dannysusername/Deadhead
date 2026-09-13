package deadhead.web;

import deadhead.user.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Accounts, not one shared password.
 *
 * Each pilot signs up with an email and their own password; the schedule they
 * import holds real client names, so every route except the sign-in pages
 * requires a session, and every query behind those routes is scoped to the
 * signed-in account's id.
 */
@Configuration
public class SecurityConfig {

    /** BCrypt, default strength 10. Slow on purpose — that is the point of it. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UserService users) throws Exception {
        // The page is JS talking to a JSON API, so the token has to be readable
        // by JS: cookie repository + the plain request handler, and the browser
        // echoes it back in X-XSRF-TOKEN.
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);   // resolve the token eagerly, so it is always sent

        http
            .userDetailsService(users)
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers("/api/register"))   // no session to protect yet
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/login.html", "/register", "/register.html",
                                 "/api/register", "/style.css", "/favicon.ico", "/favicon.svg",
                                 "/favicon-32.png", "/apple-touch-icon.png", "/error")
                    .permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error")
                .permitAll())
            .logout(out -> out
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?bye")
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                .permitAll())
            .exceptionHandling(ex -> ex
                // A fetch() that outlives its session should get a 401 to act on,
                // not a login page rendered inside a JSON parse.
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**"))
                // ...but a person typing a URL still gets sent to the sign-in page.
                // Spring promotes a lone mapping to the global default, so this
                // second one is what keeps the browser case a redirect.
                .defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE))
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                  + "script-src 'self' 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")));

        return http.build();
    }
}
