package ru.morkamo.enterprisetester.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.morkamo.enterprisetester.service.*;
import java.util.List;

@Controller
@RequestMapping("/management")
public class ManagementController {
    private final ManagementService management;
    private final SessionService sessions;
    private final StatisticsExportService statisticsExport;
    private final QuestionImageService images;

    public ManagementController(ManagementService management, SessionService sessions,
                                StatisticsExportService statisticsExport, QuestionImageService images) {
        this.management = management;
        this.sessions = sessions;
        this.statisticsExport = statisticsExport;
        this.images = images;
    }

    @GetMapping
    public String panel(@RequestParam(defaultValue = "tests") String section,
                        HttpSession session, Model model) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        if ("users".equals(section) && !sessions.isAdmin(session)) {
            return "redirect:/management?section=tests";
        }
        header(session, model);
        model.addAttribute("section", section);
        model.addAttribute("tests", management.tests(sessions.userId(session), sessions.isAdmin(session)));
        model.addAttribute("questions", management.questions(sessions.userId(session), sessions.isAdmin(session)));
        if (sessions.isAdmin(session)) model.addAttribute("users", management.users());
        return "management";
    }

    @GetMapping("/questions/new")
    public String newQuestion(HttpSession session, Model model) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        header(session, model);
        model.addAttribute("question", null);
        return "questionForm";
    }

    @GetMapping("/questions/{id}")
    public String editQuestion(@PathVariable Long id, HttpSession session, Model model) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        header(session, model);
        model.addAttribute("question",
                management.question(id, sessions.userId(session), sessions.isAdmin(session)));
        return "questionForm";
    }

    @PostMapping("/questions/save")
    public String saveQuestion(@RequestParam(required = false) Long id, @RequestParam String text,
                               @RequestParam(defaultValue = "false") boolean multiple,
                               @RequestParam List<String> answerText, @RequestParam List<Integer> points,
                               @RequestParam(required = false) List<org.springframework.web.multipart.MultipartFile> imagesToAdd,
                               @RequestParam(required = false) List<Integer> removeImages,
                               HttpSession session, RedirectAttributes redirect) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        try {
            management.saveQuestion(id, text, multiple, answerText, points, imagesToAdd, removeImages,
                    sessions.userId(session), sessions.isAdmin(session));
            return "redirect:/management?section=questions";
        } catch (org.springframework.web.server.ResponseStatusException error) {
            redirect.addFlashAttribute("error", error.getReason());
            return "redirect:/management/questions/" + (id == null ? "new" : id);
        }
    }

    @GetMapping("/questions/{id}/images/{imageIndex}")
    public ResponseEntity<byte[]> questionImage(@PathVariable Long id, @PathVariable int imageIndex,
                                                HttpSession session) {
        if (!sessions.canManage(session)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var question = management.question(id, sessions.userId(session), sessions.isAdmin(session));
        return imageResponse(question.getImageNames(), imageIndex);
    }

    @PostMapping("/questions/{id}/delete")
    public String deleteQuestion(@PathVariable Long id, HttpSession session) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        management.deleteQuestion(id, sessions.userId(session), sessions.isAdmin(session));
        return "redirect:/management?section=questions";
    }

    @GetMapping("/tests/new")
    public String newTest(HttpSession session, Model model) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        header(session, model);
        model.addAttribute("questions",
                management.questions(sessions.userId(session), sessions.isAdmin(session)));
        model.addAttribute("users", management.users());
        model.addAttribute("defaultQuestionsCount", management.minimumQuestionsCount());
        model.addAttribute("timeLimitEnabled", management.isTimeLimitEnabled());
        return "testForm";
    }

    @PostMapping("/tests/create")
    public String createTest(@RequestParam String name, @RequestParam String description,
                             @RequestParam(defaultValue = "false") boolean timed,
                             @RequestParam(required = false) String minutes,
                             @RequestParam(required = false) List<Long> questionIds,
                             @RequestParam(required = false) List<Long> userIds,
                             HttpSession session, RedirectAttributes redirect) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        try {
            management.createTest(name, description, timed, minutes, questionIds, userIds,
                    sessions.userId(session), sessions.isAdmin(session));
            return "redirect:/management";
        } catch (org.springframework.web.server.ResponseStatusException error) {
            redirect.addFlashAttribute("error", error.getReason());
            return "redirect:/management/tests/new";
        }
    }

    @PostMapping("/tests/{id}/close")
    public String closeTest(@PathVariable Long id, HttpSession session) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        management.closeTest(id, sessions.userId(session), sessions.isAdmin(session));
        return "redirect:/management";
    }

    @GetMapping("/tests/{id}/statistics")
    public String statistics(@PathVariable Long id, HttpSession session, Model model) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        header(session, model);
        model.addAttribute("test", management.accessibleTest(id, sessions.userId(session), sessions.isAdmin(session)));
        model.addAttribute("attempts", management.statistics(id, sessions.userId(session), sessions.isAdmin(session)));
        model.addAttribute("userNames", management.statisticsUsers().stream().collect(
                java.util.stream.Collectors.toMap(ru.morkamo.enterprisetester.model.User::getId,
                        user -> user.getLastName() + " " + user.getFirstName())));
        return "testStatistics";
    }

    @GetMapping("/tests/{id}/statistics/export")
    public ResponseEntity<byte[]> exportStatistics(@PathVariable Long id, @RequestParam String format,
                                                   HttpSession session) {
        if (!sessions.canManage(session)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "/mainpage").build();
        }
        var test = management.accessibleTest(id, sessions.userId(session), sessions.isAdmin(session));
        var attempts = management.statistics(id, sessions.userId(session), sessions.isAdmin(session));
        var users = management.statisticsUsers().stream().collect(java.util.stream.Collectors.toMap(
                ru.morkamo.enterprisetester.model.User::getId, user -> user));

        String normalizedFormat = format.toLowerCase(java.util.Locale.ROOT);
        byte[] file;
        String contentType;
        if ("csv".equals(normalizedFormat)) {
            file = statisticsExport.csv(test, attempts, users);
            contentType = "text/csv;charset=UTF-8";
        } else if ("xlsx".equals(normalizedFormat)) {
            file = statisticsExport.xlsx(test, attempts, users);
            contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Доступны только форматы CSV и XLSX");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=statistics-test-" + id + "." + normalizedFormat)
                .contentType(MediaType.parseMediaType(contentType))
                .body(file);
    }

    @GetMapping("/tests/{id}/content")
    public String testContent(@PathVariable Long id, HttpSession session, Model model) {
        if (!sessions.isAdmin(session)) return "redirect:/mainpage";
        header(session, model);
        model.addAttribute("test", management.testContent(id, sessions.userId(session), true));
        return "testContent";
    }

    @GetMapping("/tests/{testId}/statistics/{attemptId}")
    public String statisticDetails(@PathVariable Long testId, @PathVariable Long attemptId,
                                   HttpSession session, Model model) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        header(session, model);
        model.addAttribute("attempt",
                management.statisticAttempt(testId, attemptId, sessions.userId(session), sessions.isAdmin(session)));
        model.addAttribute("userNames", management.statisticsUsers().stream().collect(
                java.util.stream.Collectors.toMap(ru.morkamo.enterprisetester.model.User::getId,
                        user -> user.getLastName() + " " + user.getFirstName())));
        return "attemptStatistics";
    }

    @GetMapping("/tests/{testId}/statistics/{attemptId}/questions/{index}/images/{imageIndex}")
    public ResponseEntity<byte[]> statisticImage(@PathVariable Long testId, @PathVariable Long attemptId,
                                                 @PathVariable int index, @PathVariable int imageIndex,
                                                 HttpSession session) {
        if (!sessions.canManage(session)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        var attempt = management.statisticAttempt(testId, attemptId, sessions.userId(session), sessions.isAdmin(session));
        if (index < 0 || index >= attempt.getQuestions().size()) {
            return ResponseEntity.notFound().build();
        }
        return imageResponse(attempt.getQuestions().get(index).getImageNames(), imageIndex);
    }

    private ResponseEntity<byte[]> imageResponse(List<String> names, int imageIndex) {
        if (imageIndex < 0 || imageIndex >= names.size()) return ResponseEntity.notFound().build();
        var name = names.get(imageIndex);
        var type = name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(images.read(name));
    }

    @PostMapping("/users/save")
    public String saveUser(@RequestParam(required = false) Long id,
                           @RequestParam String firstName, @RequestParam String lastName,
                           @RequestParam String email, @RequestParam String password,
                           @RequestParam Long roleId, HttpSession session,
                           RedirectAttributes redirect) {
        if (!sessions.isAdmin(session)) return "redirect:/mainpage";
        try {
            management.saveUser(id, firstName, lastName, email, password, roleId);
        } catch (org.springframework.web.server.ResponseStatusException error) {
            redirect.addFlashAttribute("error", error.getReason());
        }
        return "redirect:/management?section=users";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable Long id, HttpSession session,
                             RedirectAttributes redirect) {
        if (!sessions.isAdmin(session)) return "redirect:/mainpage";
        try {
            management.deleteUser(id, sessions.userId(session));
        } catch (org.springframework.web.server.ResponseStatusException error) {
            redirect.addFlashAttribute("error", error.getReason());
        }
        return "redirect:/management?section=users";
    }

    private void header(HttpSession session, Model model) {
        sessions.addHeader(session, model);
    }
}
