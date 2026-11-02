package com.example.server.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthAccounts implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public AuthAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String name) {
        var users =
                jdbc.query(
                        "SELECT id,username,password FROM users WHERE username=?",
                        (r, n) -> new Account(r.getLong(1), r.getString(2), r.getString(3)),
                        name);
        if (users.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
        Account u = users.getFirst();
        return new org.springframework.security.core.userdetails.User(
                Long.toString(u.id()), u.password(), List.of());
    }

    private record Account(long id, String username, String password) {}

    public record Profile(long id, String username, String nickname, String avatar) {}

    public Profile profile(long id) {
        var rows =
                jdbc.query(
                        "SELECT id,username,nickname,avatar FROM users WHERE id=?",
                        (r, n) ->
                                new Profile(
                                        r.getLong(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4)),
                        id);
        if (rows.isEmpty()) throw new AuthException(401, "SESSION_EXPIRED", "账号已失效");
        return rows.getFirst();
    }
}
