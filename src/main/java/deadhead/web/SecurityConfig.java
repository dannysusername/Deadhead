package deadhead.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * One shared login for the whole app — it holds real client names and
 * schedules, so the public URL can't be open. Credentials come from
 * APP_USER / APP_PASSWORD env vars (see application.properties defaults).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())   // single-user tool; JS posts JSON without tokens
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .formLogin(withDefaults())
            .httpBasic(withDefaults());
        return http.build();
    }
}
