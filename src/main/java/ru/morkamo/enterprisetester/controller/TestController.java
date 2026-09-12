package ru.morkamo.enterprisetester.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.service.TestService;
import java.time.Instant;
import java.util.Set;

@Controller
public class TestController {
    private final TestService tests;

    public TestController(TestService tests) {
        this.tests = tests;
    }

    @GetMapping("/tests/{id}")
    public String cover(@PathVariable Long id, HttpSession session, Model model) {
        if (session.getAttribute("userId") == null) return "redirect:/";
        var test = tests.getTest(id);
        model.addAttribute("test", test);
        model.addAttribute("time", tests.timeLimit(test));
        return "testcover";
    }

    @PostMapping("/tests/{id}/start")
    public String start(@PathVariable Long id, HttpSession session) {
        if (session.getAttribute("userId") == null) return "redirect:/";
        return "redirect:/attempts/" + tests.start(id, userId(session)).getId();
    }

    @GetMapping("/attempts/{id}")
    public String question(@PathVariable Long id, HttpSession session, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (session.getAttribute("userId") == null) return "redirect:/";
        var attempt = tests.getAttempt(id, userId(session));
        if (attempt.isFinished()) return "redirect:/attempts/" + id + "/result";
        model.addAttribute("attempt", attempt);
        model.addAttribute("question", attempt.getQuestions().get(attempt.getCurrentQuestion()));
        model.addAttribute("serverNow", Instant.now().toEpochMilli());
        model.addAttribute("deadline", attempt.getDeadline() == null ? null : attempt.getDeadline().toEpochMilli());
        return "testpage";
    }

    @PostMapping("/attempts/{id}/answer")
    @ResponseBody
    public boolean answer(@PathVariable Long id, @RequestParam int questionIndex,
                          @RequestParam(defaultValue = "") Set<Long> selected, HttpSession session) {
        return tests.save(id, userId(session), questionIndex, selected, null, false).isFinished();
    }

    @PostMapping("/attempts/{id}/navigate")
    public String navigate(@PathVariable Long id, @RequestParam int questionIndex,
                           @RequestParam(defaultValue = "") Set<Long> selected,
                           @RequestParam(required = false) Integer target,
                           @RequestParam(defaultValue = "false") boolean complete, HttpSession session) {
        var attempt = tests.save(id, userId(session), questionIndex, selected, target, complete);
        return "redirect:/attempts/" + id + (attempt.isFinished() ? "/result" : "");
    }

    @GetMapping("/attempts/{id}/result")
    public String result(@PathVariable Long id, HttpSession session, Model model) {
        if (session.getAttribute("userId") == null) return "redirect:/";
        var attempt = tests.getAttempt(id, userId(session));
        if (!attempt.isFinished()) return "redirect:/attempts/" + id;
        model.addAttribute("attempt", attempt);
        return "testResult";
    }

    private Long userId(HttpSession session) {
        var id = (Long) session.getAttribute("userId");
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Войдите в систему");
        return id;
    }
}
