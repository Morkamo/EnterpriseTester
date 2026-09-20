package ru.morkamo.enterprisetester.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.morkamo.enterprisetester.service.TestService;

@Controller
public class PageController {
    private final TestService tests;
    private final ru.morkamo.enterprisetester.repository.TestAttemptRepository attempts;
    private final ru.morkamo.enterprisetester.service.SessionService sessions;

    public PageController(TestService tests, ru.morkamo.enterprisetester.repository.TestAttemptRepository attempts,
                          ru.morkamo.enterprisetester.service.SessionService sessions) {
        this.tests = tests;
        this.attempts = attempts;
        this.sessions = sessions;
    }

    @GetMapping("/mainpage")
    public String mainpage(HttpSession session, Model model) {
        var fullName = session.getAttribute("fullName");
        if (fullName == null || !sessions.isActive(session)) {
            return "redirect:/";
        }
        sessions.addHeader(session, model);
        var userId = sessions.userId(session);
        model.addAttribute("tests", tests.listTests(userId));
        model.addAttribute("error", null);
        return "mainpage";
    }

    @GetMapping("/profile")
    public String profile(HttpSession session, Model model) {
        if (!sessions.isActive(session)) return "redirect:/";
        sessions.addHeader(session, model);
        model.addAttribute("history",
                attempts.findByUserIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(sessions.userId(session)));
        return "profile";
    }

    @org.springframework.web.bind.annotation.PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }
}
