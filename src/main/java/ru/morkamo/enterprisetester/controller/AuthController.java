package ru.morkamo.enterprisetester.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.morkamo.enterprisetester.dto.request.LoginRequest;
import ru.morkamo.enterprisetester.dto.response.LoginResponse;
import ru.morkamo.enterprisetester.service.UserService;

@RestController()
@RequestMapping("api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request, HttpSession session) {
        var response = userService.login(request);
        if (response.success()) {
            session.setAttribute("fullName", response.fullName());
            session.setAttribute("userId", response.userId());
        }
        else {
            session.removeAttribute("fullName");
            session.removeAttribute("userId");
        }
        return response;
    }
}
