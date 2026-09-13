package deadhead.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The two pages you can reach signed out. They are plain static files; this
 * just gives them the clean URLs Spring Security is configured to use.
 */
@Controller
public class PageController {

    @GetMapping("/login")
    String login() {
        return "forward:/login.html";
    }

    @GetMapping("/register")
    String register() {
        return "forward:/register.html";
    }
}
