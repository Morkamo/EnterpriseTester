package ru.morkamo.enterprisetester.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final ru.morkamo.enterprisetester.service.SessionService sessions;
    private final ru.morkamo.enterprisetester.service.QuestionImageService images;

    public TestController(TestService tests, ru.morkamo.enterprisetester.service.SessionService sessions,
                          ru.morkamo.enterprisetester.service.QuestionImageService images) {
        this.tests = tests;
        this.sessions = sessions;
        this.images = images;
    }

    @GetMapping("/tests/{id}")
    public String cover(@PathVariable Long id, HttpSession session, Model model) {
        if (!sessions.isActive(session)) return "redirect:/";
        var test = tests.getTest(id);
        if (test.getTesters().stream().noneMatch(user -> user.getId().equals(sessions.userId(session)))) {
            return "redirect:/mainpage?error=notAssigned";
        }
        model.addAttribute("test", test);
        model.addAttribute("time", tests.timeLimit(test));
        model.addAttribute("activeAttempt", tests.activeAttempt(id, sessions.userId(session)));
        return "testcover";
    }

    @PostMapping("/tests/{id}/start")
    public String start(@PathVariable Long id, HttpSession session) {
        if (!sessions.isActive(session)) return "redirect:/";
        try {
            return "redirect:/attempts/" + tests.start(id, userId(session)).getId();
        } catch (ResponseStatusException error) {
            return "redirect:/mainpage?error=testUnavailable";
        }
    }

    @GetMapping("/attempts/{id}")
    public String question(@PathVariable Long id, HttpSession session, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!sessions.isActive(session)) return "redirect:/";
        ru.morkamo.enterprisetester.model.TestAttempt attempt;
        try {
            attempt = tests.getAttempt(id, userId(session));
        } catch (ResponseStatusException error) {
            return "redirect:/mainpage?error=testClosed";
        }
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

    @GetMapping("/attempts/{id}/questions/{index}/images/{imageIndex}")
    public ResponseEntity<byte[]> questionImage(@PathVariable Long id, @PathVariable int index,
                                                @PathVariable int imageIndex,
                                                HttpSession session) {
        var attempt = tests.getAttempt(id, userId(session));
        if (index < 0 || index >= attempt.getQuestions().size()) {
            return ResponseEntity.notFound().build();
        }
        var names = attempt.getQuestions().get(index).getImageNames();
        if (imageIndex < 0 || imageIndex >= names.size()) return ResponseEntity.notFound().build();
        var name = names.get(imageIndex);
        return ResponseEntity.ok()
                .contentType(name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .body(images.read(name));
    }

    @PostMapping("/attempts/{id}/navigate")
    public String navigate(@PathVariable Long id, @RequestParam int questionIndex,
                           @RequestParam(defaultValue = "") Set<Long> selected,
                           @RequestParam(required = false) Integer target,
                           @RequestParam(defaultValue = "false") boolean complete, HttpSession session) {
        try {
            var attempt = tests.save(id, userId(session), questionIndex, selected, target, complete);
            return "redirect:/attempts/" + id + (attempt.isFinished() ? "/result" : "");
        } catch (ResponseStatusException error) {
            return "redirect:/mainpage?error=testClosed";
        }
    }

    @GetMapping("/attempts/{id}/result")
    public String result(@PathVariable Long id, HttpSession session, Model model) {
        if (!sessions.isActive(session)) return "redirect:/";
        var attempt = tests.getAttempt(id, userId(session));
        if (!attempt.isFinished()) return "redirect:/attempts/" + id;
        model.addAttribute("attempt", attempt);
        return "testResult";
    }

    private Long userId(HttpSession session) {
        return sessions.userId(session);
    }
}
