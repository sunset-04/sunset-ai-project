package com.sunset.ai.controller;

import com.sunset.ai.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {
    private final AuthService users;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;

    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/me") public Me me(Authentication authentication) { return new Me(users.current(authentication)); }

    @PostMapping("/register") public Me register(@RequestBody AuthService.Credentials input,
            HttpServletRequest request, HttpServletResponse response) {
        return signedIn(users.register(input), request, response);
    }
    @PostMapping("/login") public Me login(@RequestBody AuthService.Credentials input,
            HttpServletRequest request, HttpServletResponse response) {
        return signedIn(users.login(input), request, response);
    }
    private Me signedIn(AuthService.UserView user, HttpServletRequest request, HttpServletResponse response) {
        request.getSession();
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.id(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        tokens.saveToken(null, request, response);
        return new Me(user);
    }
    public record Me(AuthService.UserView user) { }
}
