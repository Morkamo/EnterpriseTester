package ru.morkamo.enterprisetester.service;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.model.*;
import ru.morkamo.enterprisetester.repository.*;
import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;

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

    public Map<Long, String> questionOwners() {
        var labels = new HashMap<Long, String>();
        for (var user : users.findAll()) {
            String label = "[null]";
            if (user.getUsername() != null && !user.getUsername().isBlank()) {
                label = "[От: " + user.getUsername() + "]";
            } else if (user.getEmail() != null && !user.getEmail().isBlank()) {
                label = "[От: " + user.getEmail() + "]";
            }
            labels.put(user.getId(), label);
        }
        return labels;
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

    public Question saveQuestion(Long id, String title, String text, boolean multiple,
                                 List<String> answerTexts, List<BigDecimal> points,
                                 List<org.springframework.web.multipart.MultipartFile> imagesToAdd,
                                 List<Integer> removeImages,
                                 Long ownerId, boolean admin) {
        if (answerTexts == null || points == null || answerTexts.size() != points.size() || answerTexts.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Добавьте хотя бы два варианта");
        }
        if (text == null || text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Введите вопрос");
        if (title != null && title.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заголовок должен быть не длиннее 255 символов");
        }
        var question = id == null ? new Question() : question(id, ownerId, admin);
        if (id == null) question.setOwnerId(ownerId);
        if (title != null) {
            question.setTitle(title.trim());
        }
        question.setText(text.trim());
        question.setMultipleAllowed(multiple);
        question.getAnswers().clear();
        for (int i = 0; i < answerTexts.size(); i++) {
            if (answerTexts.get(i).isBlank()) continue;
            var answer = new Answer();
            answer.setText(answerTexts.get(i).trim());
            var value = points.get(i);
            if (value == null || value.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимое количество баллов");
            }
            answer.setPoints(value.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            answer.setCorrect(answer.getPoints().signum() > 0);
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

    public List<Question> previewQuestions(List<Question> imported, Long ownerId) {
        var existing = new HashMap<QuestionKey, Question>();
        for (var question : questions.findByOwnerIdOrderByIdDesc(ownerId)) {
            existing.putIfAbsent(questionKey(question), question);
        }
        var result = new LinkedHashMap<QuestionKey, Question>();
        for (var question : imported) {
            var key = questionKey(question);
            result.putIfAbsent(key, existing.getOrDefault(key, question));
        }
        return new ArrayList<>(result.values());
    }

    public List<Question> importQuestions(List<Question> imported, Long ownerId) {
        users.findByIdForUpdate(ownerId).filter(user -> !user.isDeleted()).orElseThrow(this::forbidden);
        var resolved = previewQuestions(imported, ownerId);
        for (var question : resolved) {
            if (question.getId() == null) {
                question.setOwnerId(ownerId);
                questions.save(question);
            }
        }
        return resolved;
    }

    public void previewTest(TestDefinition test, Long ownerId) {
        test.setQuestions(previewQuestions(test.getQuestions(), ownerId));
        if (test.getQuestions().size() < minimumQuestionsCount() || minimumQuestionsCount() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "В тесте должно быть не меньше " + minimumQuestionsCount() + " различных вопросов");
        }
        if (test.isTemporaryTest() && !settings.isEnableTimeLimit()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Создание временных тестов отключено");
        }
    }

    public TestDefinition importTest(TestDefinition test, List<Long> userIds, Long ownerId, boolean admin) {
        var resolved = importQuestions(test.getQuestions(), ownerId);
        var ids = resolved.stream().map(Question::getId).toList();
        String minutes = "";
        if (test.getTimeMinutes() != null) minutes = test.getTimeMinutes().toString();
        return createTest(test.getName(), test.getDescription(), test.isTemporaryTest(), minutes,
                ids, userIds, ownerId, admin);
    }

    private QuestionKey questionKey(Question question) {
        var answers = question.getAnswers().stream()
                .map(answer -> new AnswerKey(normalizeText(answer.getText()),
                        answer.getPoints().stripTrailingZeros().toPlainString(), answer.isCorrect()))
                .sorted(Comparator.comparing(AnswerKey::text).thenComparing(AnswerKey::points)
                        .thenComparing(AnswerKey::correct))
                .toList();
        return new QuestionKey(normalizeText(question.getText()), question.isMultipleAllowed(),
                answers, List.copyOf(question.getImageNames()));
    }

    private String normalizeText(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private record AnswerKey(String text, String points, boolean correct) {
    }

    private record QuestionKey(String text, boolean multiple, List<AnswerKey> answers, List<String> images) {
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
        test.setTesters(assignedUsers(userIds));
        return tests.save(test);
    }

    public Map<Long, TestAttempt> participantAttempts(Long testId, Long ownerId, boolean admin) {
        accessibleTest(testId, ownerId, admin);
        var result = new HashMap<Long, TestAttempt>();
        for (var attempt : attempts.findByTestIdOrderByStartedAtDesc(testId)) {
            result.putIfAbsent(attempt.getUserId(), attempt);
        }
        return result;
    }

    public void saveParticipants(Long testId, List<Long> userIds, Long ownerId, boolean admin) {
        var test = accessibleTest(testId, ownerId, admin);
        if (test.isTestClosed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Тест завершён");
        }
        var assigned = assignedUsers(userIds);
        test.getTesters().clear();
        test.getTesters().addAll(assigned);
    }

    private List<User> assignedUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return new ArrayList<>();
        var assigned = users.findAllById(userIds).stream().filter(user -> !user.isDeleted()).toList();
        if (assigned.size() != new HashSet<>(userIds).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите действующих пользователей");
        }
        return new ArrayList<>(assigned);
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
        accessibleTest(testId, ownerId, admin);
        var results = attempts.findByTestIdOrderByStartedAtDesc(testId);
        results.forEach(this::prepareStatistics);
        return results;
    }

    public TestAttempt statisticAttempt(Long testId, Long attemptId, Long ownerId, boolean admin) {
        accessibleTest(testId, ownerId, admin);
        var attempt = attempts.findById(attemptId).orElseThrow(() -> notFound("Результат не найден"));
        if (!Objects.equals(attempt.getTestId(), testId)) throw forbidden();
        prepareStatistics(attempt);
        return attempt;
    }

    private void prepareStatistics(TestAttempt attempt) {
        attempt.getQuestions().forEach(question -> {
            question.getOptions().size();
            question.getImageNames().size();
        });
        if (!attempt.isFinished()) {
            entityManager.detach(attempt);
            testService.calculateResult(attempt);
        }
    }

    public User saveUser(Long id, String firstName, String lastName, String email, String username,
                         String password, Long roleId) {
        String emailAddress = email == null || email.isBlank() ? null : email.trim();
        String loginName = username == null || username.isBlank() ? null : username.trim().toLowerCase(Locale.ROOT);
        if (firstName.isBlank() || lastName.isBlank()
                || (id == null && (password == null || password.isBlank()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заполните все поля");
        }
        if (emailAddress == null && loginName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите email или имя пользователя");
        }
        if (!Set.of(SessionService.USER, SessionService.ADMIN, SessionService.MANAGER).contains(roleId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неверная роль");
        }
        if (emailAddress != null) {
            var duplicate = users.findByEmail(emailAddress);
            if (duplicate.isPresent() && !Objects.equals(duplicate.get().getId(), id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email уже используется");
            }
        }
        if (loginName != null) {
            if (!loginName.matches("[a-z0-9_.-]{3,32}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Имя пользователя: 3–32 символа, латинские буквы, цифры, точка, дефис или подчёркивание");
            }
            var existing = users.findByUsernameIgnoreCase(loginName);
            if (existing.isPresent() && !Objects.equals(existing.get().getId(), id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Имя пользователя уже используется");
            }
        }
        var user = id == null ? new User() : users.findById(id)
                .filter(existing -> !existing.isDeleted())
                .orElseThrow(() -> notFound("Пользователь не найден"));
        user.setFirstName(firstName.trim());
        user.setLastName(lastName.trim());
        user.setEmail(emailAddress);
        user.setUsername(loginName);
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
