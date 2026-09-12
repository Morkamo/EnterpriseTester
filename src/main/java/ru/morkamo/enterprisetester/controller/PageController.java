package ru.morkamo.enterprisetester.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.morkamo.enterprisetester.service.TestService;

@Controller
public class PageController {
    private final TestService tests;

    public PageController(TestService tests) {
        this.tests = tests;
    }

    @GetMapping("/mainpage")
    public String mainpage(HttpSession session, Model model) {
        var fullName = session.getAttribute("fullName");
        if (fullName == null) {
            return "redirect:/";
        }
        model.addAttribute("fullName", fullName);
        model.addAttribute("tests", tests.listTests());
        return "mainpage";
    }
}
