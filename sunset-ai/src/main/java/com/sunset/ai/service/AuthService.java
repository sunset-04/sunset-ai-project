package com.sunset.ai.service;

import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
@DependsOn("jdbcChatStore")
public class AuthService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private final String dummyHash;
    public AuthService(JdbcTemplate jdbc, BCryptPasswordEncoder encoder) {
        this.jdbc = jdbc; this.encoder = encoder;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public UserView register(Credentials input) {
        String username = username(input.username());
        validatePassword(input.password());
        String display = input.displayName() == null || input.displayName().isBlank() ? username : input.displayName().strip();
        if (display.length() > 30) throw new IllegalArgumentException("昵称不能超过 30 个字符");
        UserView user = new UserView(UUID.randomUUID().toString(), username, display);
        try {
            jdbc.update("INSERT INTO ai_user (id, username, display_name, password_hash) VALUES (?, ?, ?, ?)",
                    user.id(), username, display, encoder.encode(input.password()));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这个用户名已被使用，请换一个试试");
        }
        return user;
    }

    public UserView login(Credentials input) {
        String username = username(input.username());
        validatePassword(input.password());
        var users = jdbc.query("SELECT id, username, display_name, password_hash FROM ai_user WHERE username = ?",
                (rs, row) -> new Account(new UserView(rs.getString("id"), rs.getString("username"), rs.getString("display_name")), rs.getString("password_hash")), username);
        boolean valid = encoder.matches(input.password(), users.isEmpty() ? dummyHash : users.get(0).hash());
        if (!valid || users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码不正确");
        return users.get(0).user();
    }

    public UserView current(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return null;
        return jdbc.query("SELECT id, username, display_name FROM ai_user WHERE id = ?",
                (rs, row) -> new UserView(rs.getString("id"), rs.getString("username"), rs.getString("display_name")), auth.getName())
                .stream().findFirst().orElse(null);
    }

    @Override public UserDetails loadUserByUsername(String username) {
        return jdbc.query("SELECT id, password_hash FROM ai_user WHERE username = ?",
                (rs, row) -> User.withUsername(rs.getString("id")).password(rs.getString("password_hash")).roles("USER").build(), username(username))
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("用户名或密码不正确"));
    }

    private String username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{3,32}")) throw new IllegalArgumentException("用户名需为 3-32 位字母、数字或下划线");
        return value.toLowerCase(Locale.ROOT);
    }
    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码需为 8-64 个字符，UTF-8 编码不超过 72 字节");
        }
    }
    public record Credentials(String username, String password, String displayName) { }
    public record UserView(String id, String username, String displayName) { }
    private record Account(UserView user, String hash) { }
}
