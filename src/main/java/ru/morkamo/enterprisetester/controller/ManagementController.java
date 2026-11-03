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
import java.math.BigDecimal;

@Controller
@RequestMapping("/management")
public class ManagementController {
    private final ManagementService management;
    private final SessionService sessions;
    private final StatisticsExportService statisticsExport;
    private final QuestionImageService images;
    private final QuestionImportService questionImport;

    public ManagementController(ManagementService management, SessionService sessions,
                                StatisticsExportService statisticsExport, QuestionImageService images,
                                QuestionImportService questionImport) {
        this.management = management;
        this.sessions = sessions;
        this.statisticsExport = statisticsExport;
        this.images = images;
        this.questionImport = questionImport;
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
        if (sessions.isAdmin(session)) {
            model.addAttribute("users", management.users());
            model.addAttribute("questionOwners", management.questionOwners());
        }
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
    public String saveQuestion(@RequestParam(required = false) Long id,
                               @RequestParam(required = false) String title, @RequestParam String text,
                               @RequestParam(defaultValue = "false") boolean multiple,
                               @RequestParam List<String> answerText, @RequestParam List<BigDecimal> points,
                               @RequestParam(required = false) List<org.springframework.web.multipart.MultipartFile> imagesToAdd,
                               @RequestParam(required = false) List<Integer> removeImages,
                               HttpSession session, RedirectAttributes redirect) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        try {
            management.saveQuestion(id, title, text, multiple, answerText, points, imagesToAdd, removeImages,
                    sessions.userId(session), sessions.isAdmin(session));
            return "redirect:/management?section=questions";
        } catch (org.springframework.web.server.ResponseStatusException error) {
            redirect.addFlashAttribute("error", error.getReason());
            return "redirect:/management/questions/" + (id == null ? "new" : id);
        }
    }

    @PostMapping("/questions/import/preview")
    public String previewQuestions(@RequestParam org.springframework.web.multipart.MultipartFile file,
                                   HttpSession session, Model model, jakarta.servlet.http.HttpServletResponse response) {
        if (!sessions.canManage(session)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            model.addAttribute("error", "Нет доступа к импорту вопросов");
        } else {
            try {
                var imported = questionImport.parse(file);
                model.addAttribute("questions", management.previewQuestions(imported, sessions.userId(session)));
            } catch (org.springframework.web.server.ResponseStatusException error) {
                response.setStatus(error.getStatusCode().value());
                model.addAttribute("error", error.getReason());
            }
        }
        return "questionImport";
    }

    @PostMapping("/questions/import")
    public String importQuestions(@RequestParam org.springframework.web.multipart.MultipartFile file,
                                  HttpSession session, RedirectAttributes redirect) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        try {
            var questions = questionImport.parse(file);
            management.importQuestions(questions, sessions.userId(session));
            redirect.addFlashAttribute("success", "Успешный импорт");
        } catch (org.springframework.web.server.ResponseStatusException error) {
            redirect.addFlashAttribute("error", error.getReason());
        }
        return "redirect:/management?section=questions";
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
        if (sessions.isAdmin(session)) model.addAttribute("questionOwners", management.questionOwners());
        return "testForm";
    }

    @PostMapping("/tests/import/preview")
    public String previewTest(@RequestParam org.springframework.web.multipart.MultipartFile file,
                              HttpSession session, Model model, jakarta.servlet.http.HttpServletResponse response) {
        if (!sessions.canManage(session)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            model.addAttribute("error", "Нет доступа к импорту тестов");
        } else {
            try {
                var test = questionImport.parseTest(file);
                management.previewTest(test, sessions.userId(session));
                model.addAttribute("test", test);
                model.addAttribute("questions", test.getQuestions());
                model.addAttribute("users", management.users());
            } catch (org.springframework.web.server.ResponseStatusException error) {
                response.setStatus(error.getStatusCode().value());
                model.addAttribute("error", error.getReason());
            }
        }
        return "testImport";
    }

    @PostMapping("/tests/import")
    public String importTest(@RequestParam org.springframework.web.multipart.MultipartFile file,
                             @RequestParam(required = false) List<Long> userIds,
                             HttpSession session, RedirectAttributes redirect) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        try {
            var test = questionImport.parseTest(file);
            management.importTest(test, userIds, sessions.userId(session), sessions.isAdmin(session));
            redirect.addFlashAttribute("success", "Успешный импорт");
        } catch (org.springframework.web.server.ResponseStatusException error) {
            redirect.addFlashAttribute("error", error.getReason());
        }
        return "redirect:/management?section=tests";
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

    @GetMapping("/tests/{id}/manage")
    public String manageTest(@PathVariable Long id, HttpSession session, Model model) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        var test = management.accessibleTest(id, sessions.userId(session), sessions.isAdmin(session));
        header(session, model);
        model.addAttribute("test", test);
        model.addAttribute("users", management.users());
        model.addAttribute("assignedIds", test.getTesters().stream().map(user -> user.getId()).toList());
        model.addAttribute("attempts", management.participantAttempts(id, sessions.userId(session), sessions.isAdmin(session)));
        model.addAttribute("serverNow", java.time.Instant.now().toEpochMilli());
        return "testManagement";
    }

    @PostMapping("/tests/{id}/participants")
    public String saveParticipants(@PathVariable Long id, @RequestParam(required = false) List<Long> userIds,
                                   HttpSession session, RedirectAttributes redirect) {
        if (!sessions.canManage(session)) return "redirect:/mainpage";
        management.accessibleTest(id, sessions.userId(session), sessions.isAdmin(session));
        try {
            management.saveParticipants(id, userIds, sessions.userId(session), sessions.isAdmin(session));
            redirect.addFlashAttribute("success", "Сохранено");
        } catch (org.springframework.web.server.ResponseStatusException error) {
            redirect.addFlashAttribute("error", error.getReason());
        }
        return "redirect:/management/tests/" + id + "/manage";
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
                           @RequestParam(required = false) String email, @RequestParam String password,
                           @RequestParam(required = false) String username,
                           @RequestParam Long roleId, HttpSession session,
                           RedirectAttributes redirect) {
        if (!sessions.isAdmin(session)) return "redirect:/mainpage";
        try {
            management.saveUser(id, firstName, lastName, email, username, password, roleId);
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
        model.addAttribute("currentUserId", sessions.userId(session));
    }
}
