package ru.morkamo.enterprisetester.service;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.model.*;
import ru.morkamo.enterprisetester.repository.*;
import java.util.*;

@Service
@Transactional
public class ManagementService {
    private final TestRepository tests;
    private final QuestionRepository questions;
    private final UserRepository users;
    private final TestAttemptRepository attempts;
    private final EntityManager entityManager;
    private final TestService testService;
    private final ru.morkamo.enterprisetester.config.TestSettings settings;
    private final QuestionImageService images;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;

    public ManagementService(TestRepository tests, QuestionRepository questions, UserRepository users,
                             TestAttemptRepository attempts, EntityManager entityManager,
                             TestService testService,
                             ru.morkamo.enterprisetester.config.TestSettings settings,
                             QuestionImageService images,
                             org.springframework.security.crypto.password.PasswordEncoder passwords) {
        this.tests = tests;
        this.questions = questions;
        this.users = users;
        this.attempts = attempts;
        this.entityManager = entityManager;
        this.testService = testService;
        this.settings = settings;
        this.images = images;
        this.passwords = passwords;
    }

    public List<TestDefinition> tests(Long ownerId, boolean admin) {
        return admin ? tests.findAllByOrderByIdDesc() : tests.findByOwnerIdOrderByIdDesc(ownerId);
    }

    public List<Question> questions(Long ownerId, boolean admin) {
        return admin ? questions.findAllByOrderByIdDesc() : questions.findByOwnerIdOrderByIdDesc(ownerId);
    }

    public List<User> users() {
        return users.findByDeletedFalseOrderByLastNameAscFirstNameAsc();
    }

    public List<User> statisticsUsers() {
        return users.findAllByOrderByLastNameAscFirstNameAsc();
    }

    public int minimumQuestionsCount() {
        return settings.getDefaultQuestionsCount();
    }

    public boolean isTimeLimitEnabled() {
        return settings.isEnableTimeLimit();
    }

    public Question question(Long id, Long ownerId, boolean admin) {
        var question = questions.findById(id).orElseThrow(() -> notFound("Вопрос не найден"));
        if (!admin && !Objects.equals(question.getOwnerId(), ownerId)) throw forbidden();
        question.getAnswers().size();
        return question;
    }

    public Question saveQuestion(Long id, String text, boolean multiple,
                                 List<String> answerTexts, List<Integer> points,
                                 List<org.springframework.web.multipart.MultipartFile> imagesToAdd,
                                 List<Integer> removeImages,
                                 Long ownerId, boolean admin) {
        if (answerTexts == null || points == null || answerTexts.size() != points.size() || answerTexts.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Добавьте хотя бы два варианта");
        }
        if (text == null || text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Введите вопрос");
        var question = id == null ? new Question() : question(id, ownerId, admin);
        if (id == null) question.setOwnerId(ownerId);
        question.setText(text.trim());
        question.setMultipleAllowed(multiple);
        question.getAnswers().clear();
        for (int i = 0; i < answerTexts.size(); i++) {
            if (answerTexts.get(i).isBlank()) continue;
            var answer = new Answer();
            answer.setText(answerTexts.get(i).trim());
            answer.setPoints(Math.max(0, points.get(i)));
            answer.setCorrect(answer.getPoints() > 0);
            answer.setQuestion(question);
            question.getAnswers().add(answer);
        }
        if (question.getAnswers().size() < 2 || question.getAnswers().stream().noneMatch(Answer::isCorrect)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Нужно минимум два варианта и хотя бы один вариант с баллами");
        }
        var names = question.getImageNames();
        var removed = removeImages == null ? Set.<Integer>of() : new HashSet<>(removeImages);
        if (removed.stream().anyMatch(index -> index == null || index < 0 || index >= names.size())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неверный номер изображения");
        }
        int addedCount = imagesToAdd == null ? 0 : (int) imagesToAdd.stream()
                .filter(file -> file != null && !file.isEmpty()).count();
        if (names.size() - removed.size() + addedCount > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "К вопросу можно прикрепить не больше 10 изображений");
        }
        var newNames = images.storeAll(imagesToAdd);
        for (int index = names.size() - 1; index >= 0; index--) {
            if (removed.contains(index)) names.remove(index);
        }
        names.addAll(newNames);
        return questions.save(question);
    }

    public void deleteQuestion(Long id, Long ownerId, boolean admin) {
        var question = question(id, ownerId, admin);
        entityManager.createNativeQuery("delete from test_questions where question_id = :id")
                .setParameter("id", id).executeUpdate();
        questions.delete(question);
    }

    public TestDefinition createTest(String name, String description, boolean timed, String minutesText,
                                     List<Long> questionIds, List<Long> userIds, Long ownerId, boolean admin) {
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Введите название");
        int minimum = settings.getDefaultQuestionsCount();
        if (minimum < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Минимальное количество вопросов должно быть больше нуля");
        }
        var uniqueQuestionIds = questionIds == null ? Set.<Long>of() : new LinkedHashSet<>(questionIds);
        if (uniqueQuestionIds.size() < minimum) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "В тесте должно быть не меньше " + minimum + " вопросов");
        }
        if (uniqueQuestionIds.size() != questionIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Один вопрос нельзя добавлять несколько раз");
        }
        if (userIds == null || userIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Назначьте пользователей");
        }
        if (timed && !settings.isEnableTimeLimit()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Создание временных тестов отключено");
        }
        Integer minutes = parseMinutes(timed, minutesText);
        var available = questions(ownerId, admin);
        var allowed = available.stream().map(Question::getId).collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(uniqueQuestionIds)) throw forbidden();
        var test = new TestDefinition();
        test.setOwnerId(ownerId);
        test.setName(name.trim());
        test.setDescription(description == null ? "" : description.trim());
        test.setTemporaryTest(timed);
        test.setTimeMinutes(timed ? minutes : null);
        test.setQuestions(new ArrayList<>(questions.findAllById(uniqueQuestionIds)));
        var assignedUsers = users.findAllById(userIds).stream().filter(user -> !user.isDeleted()).toList();
        if (assignedUsers.size() != new HashSet<>(userIds).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите действующих пользователей");
        }
        test.setTesters(new ArrayList<>(assignedUsers));
        return tests.save(test);
    }

    public void closeTest(Long id, Long ownerId, boolean admin) {
        var test = accessibleTest(id, ownerId, admin);
        testService.finishOpenAttempts(id, java.time.Instant.now());
        test.setTestClosed(true);
    }

    public TestDefinition testContent(Long id, Long ownerId, boolean admin) {
        var test = accessibleTest(id, ownerId, admin);
        test.getQuestions().forEach(question -> question.getAnswers().size());
        return test;
    }

    public TestDefinition accessibleTest(Long id, Long ownerId, boolean admin) {
        var test = tests.findById(id).orElseThrow(() -> notFound("Тест не найден"));
        if (!admin && !Objects.equals(test.getOwnerId(), ownerId)) throw forbidden();
        return test;
    }

    public List<TestAttempt> statistics(Long testId, Long ownerId, boolean admin) {
        var test = accessibleTest(testId, ownerId, admin);
        if (!test.isTestClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала завершите тест");
        }
        testService.finishOpenAttempts(testId, java.time.Instant.now());
        return attempts.findByTestIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(testId);
    }

    public TestAttempt statisticAttempt(Long testId, Long attemptId, Long ownerId, boolean admin) {
        accessibleTest(testId, ownerId, admin);
        var attempt = attempts.findById(attemptId).orElseThrow(() -> notFound("Результат не найден"));
        if (!Objects.equals(attempt.getTestId(), testId)) throw forbidden();
        attempt.getQuestions().forEach(question -> question.getOptions().size());
        return attempt;
    }

    public User saveUser(Long id, String firstName, String lastName, String email,
                         String password, Long roleId) {
        if (firstName.isBlank() || lastName.isBlank() || email.isBlank()
                || (id == null && (password == null || password.isBlank()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заполните все поля");
        }
        if (!Set.of(SessionService.USER, SessionService.ADMIN, SessionService.MANAGER).contains(roleId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неверная роль");
        }
        var duplicate = users.findByEmail(email);
        if (duplicate.isPresent() && !Objects.equals(duplicate.get().getId(), id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email уже используется");
        }
        var user = id == null ? new User() : users.findById(id)
                .filter(existing -> !existing.isDeleted())
                .orElseThrow(() -> notFound("Пользователь не найден"));
        user.setFirstName(firstName.trim());
        user.setLastName(lastName.trim());
        user.setEmail(email.trim());
        if (password != null && !password.isBlank()) user.setPassword(passwords.encode(password));
        user.setUserRole(roleId);
        return users.save(user);
    }

    public void deleteUser(Long id, Long currentUserId) {
        if (Objects.equals(id, currentUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нельзя удалить собственную учётную запись");
        }
        entityManager.createNativeQuery("update questions set owner_id = :adminId where owner_id = :id")
                .setParameter("adminId", currentUserId).setParameter("id", id).executeUpdate();
        entityManager.createNativeQuery("update tests set owner_id = :adminId where owner_id = :id")
                .setParameter("adminId", currentUserId).setParameter("id", id).executeUpdate();
        var user = users.findById(id).orElseThrow(() -> notFound("Пользователь не найден"));
        testService.finishAttemptsForUser(id, java.time.Instant.now());
        user.setDeleted(true);
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private Integer parseMinutes(boolean timed, String value) {
        if (!timed) return null;
        if (value == null || !value.matches("[0-9]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Время должно быть целым числом");
        }
        try {
            int minutes = Integer.parseInt(value);
            if (minutes < 1 || minutes > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Время должно быть от 1 до 1000000 минут");
            }
            return minutes;
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Время должно быть от 1 до 1000000 минут");
        }
    }
}
