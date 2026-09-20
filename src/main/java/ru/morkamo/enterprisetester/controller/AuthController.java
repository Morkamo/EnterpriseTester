package ru.morkamo.enterprisetester.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.morkamo.enterprisetester.dto.request.LoginRequest;
import ru.morkamo.enterprisetester.dto.response.LoginResponse;
import ru.morkamo.enterprisetester.repository.UserRepository;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticator;
    private final SecurityContextRepository contexts;
    private final UserRepository users;

    public AuthController(AuthenticationManager authenticator, SecurityContextRepository contexts,
                          UserRepository users) {
        this.authenticator = authenticator;
        this.contexts = contexts;
        this.users = users;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return Map.of("token", token.getToken());
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest login, HttpServletRequest request,
                               HttpServletResponse response) {
        if (login.email() == null || login.email().isBlank()
                || login.password() == null || login.password().isBlank()) {
            return failure(request);
        }
        try {
            var authentication = authenticator.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login.email(), login.password()));
            var account = users.findByEmail(authentication.getName()).orElseThrow();
            var session = request.getSession(true);
            request.changeSessionId();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            String name = account.getFirstName() + " " + account.getLastName();
            session.setAttribute("fullName", name);
            session.setAttribute("userId", account.getId());
            session.setAttribute("roleId", account.getUserRole());
            return new LoginResponse(true, "Успешная авторизация", name,
                    account.getId(), account.getUserRole());
        } catch (AuthenticationException | java.util.NoSuchElementException error) {
            return failure(request);
        }
    }

    private LoginResponse failure(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return new LoginResponse(false, "Неверный логин или пароль");
    }
}
