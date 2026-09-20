package ru.morkamo.enterprisetester.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.config.TestSettings;
import ru.morkamo.enterprisetester.config.MultiAnswerMode;
import ru.morkamo.enterprisetester.model.*;
import ru.morkamo.enterprisetester.repository.*;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class TestService {
    private final TestRepository tests;
    private final TestAttemptRepository attempts;
    private final TestSettings settings;
    private final UserRepository users;

    public TestService(TestRepository tests, TestAttemptRepository attempts, TestSettings settings,
                       UserRepository users) {
        this.tests = tests;
        this.attempts = attempts;
        this.settings = settings;
        this.users = users;
    }

    public List<TestDefinition> listTests(Long userId) {
        finishExpiredAttempts();
        return tests.findAvailableForUser(userId);
    }

    public TestAttempt activeAttempt(Long testId, Long userId) {
        var active = attempts.findFirstByTestIdAndUserIdAndFinishedAtIsNullOrderByIdDesc(testId, userId);
        if (active.isEmpty()) return null;
        var attempt = getAttempt(active.get().getId(), userId);
        return attempt.isFinished() ? null : attempt;
    }

    public TestDefinition getTest(Long id) {
        var test = tests.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Тест не найден"));
        if (test.isTestClosed()) {
            throw error(HttpStatus.NOT_FOUND, "Тест закрыт");
        }
        return test;
    }

    public Integer timeLimit(TestDefinition test) {
        return settings.isEnableTimeLimit() && test.isTemporaryTest() ? test.getTimeMinutes() : null;
    }

    public TestAttempt start(Long testId, Long userId) {
        var lockedUser = users.findByIdForUpdate(userId)
                .orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
        if (lockedUser.isDeleted()) throw error(HttpStatus.UNAUTHORIZED, "Пользователь удалён");
        var test = getTest(testId);
        if (test.getTesters().stream().noneMatch(user -> user.getId().equals(userId))) {
            throw error(HttpStatus.FORBIDDEN, "Тест вам не назначен");
        }
        if (attempts.findByTestIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(testId).stream()
                .anyMatch(attempt -> attempt.getUserId().equals(userId))) {
            throw error(HttpStatus.CONFLICT, "Этот тест уже пройден");
        }
        var active = attempts.findFirstByTestIdAndUserIdAndFinishedAtIsNullOrderByIdDesc(testId, userId);
        if (active.isPresent()) {
            var previous = getAttempt(active.get().getId(), userId);
            if (!previous.isFinished()) return previous;
            throw error(HttpStatus.CONFLICT, "Этот тест уже пройден");
        }
        var questions = new ArrayList<>(test.getQuestions());
        if (questions.isEmpty()) {
            throw error(HttpStatus.CONFLICT, "В тесте пока нет вопросов");
        }
        if (settings.getDefaultQuestionsCount() < 1) {
            throw error(HttpStatus.CONFLICT, "Количество вопросов должно быть больше нуля");
        }
        Collections.shuffle(questions);
        var attempt = new TestAttempt();
        attempt.setTestId(testId);
        attempt.setUserId(userId);
        attempt.setTestName(test.getName());
        attempt.setStartedAt(Instant.now());
        attempt.setAnswerMode(settings.getMultiAnswerMode().name());
        attempt.setShowCountdown(settings.isEnableTimeLimit() && settings.isShowCountdown());
        var minutes = timeLimit(test);
        if (settings.isEnableTimeLimit() && test.isTemporaryTest()) {
            if (minutes == null || minutes < 1) {
                throw error(HttpStatus.CONFLICT, "Для временного теста укажите время в минутах");
            }
            attempt.setDeadline(attempt.getStartedAt().plusSeconds(minutes * 60L));
        }

        for (var question : questions.subList(0, Math.min(questions.size(), settings.getDefaultQuestionsCount()))) {
            if (question.getAnswers().stream().noneMatch(Answer::isCorrect)) {
                throw error(HttpStatus.CONFLICT, "У вопроса нет правильных вариантов ответа");
            }
            var copy = new AttemptQuestion();
            copy.setText(question.getText());
            copy.getImageNames().addAll(question.getImageNames());
            copy.setMultiple(question.isMultipleAllowed());
            var answers = new ArrayList<>(question.getAnswers());
            Collections.shuffle(answers);
            for (var answer : answers) {
                var option = new AttemptOption();
                option.setText(answer.getText());
                option.setCorrect(answer.isCorrect());
                option.setPoints(answer.getPoints());
                copy.getOptions().add(option);
            }
            attempt.getQuestions().add(copy);
        }
        return attempts.save(attempt);
    }

    public TestAttempt getAttempt(Long id, Long userId) {
        var attempt = attempts.findForUpdate(id, userId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Прохождение не найдено"));
        attempt.getQuestions().forEach(q -> q.getOptions().size());
        var test = tests.findById(attempt.getTestId()).orElse(null);
        if (!attempt.isFinished() && (test == null || test.isTestClosed())) {
            throw error(HttpStatus.CONFLICT, "Тест завершён");
        }
        if (!attempt.isFinished() && attempt.getDeadline() != null
                && !Instant.now().isBefore(attempt.getDeadline())) {
            finish(attempt, attempt.getDeadline());
        }
        return attempt;
    }

    public TestAttempt save(Long id, Long userId, int questionIndex, Set<Long> selected,
                            Integer target, boolean complete) {
        var attempt = getAttempt(id, userId);
        if (attempt.isFinished()) {
            return attempt;
        }
        checkIndex(attempt, questionIndex);
        if (target != null) {
            checkIndex(attempt, target);
        }
        var question = attempt.getQuestions().get(questionIndex);
        var allowed = new HashSet<Long>();
        question.getOptions().forEach(option -> allowed.add(option.getId()));
        if (!allowed.containsAll(selected) || (!question.isMultiple() && selected.size() > 1)) {
            throw error(HttpStatus.BAD_REQUEST, "Варианты ответа не принадлежат этому вопросу");
        }
        question.getOptions().forEach(option -> option.setSelected(selected.contains(option.getId())));
        if (target != null) {
            attempt.setCurrentQuestion(target);
        }
        if (complete) {
            finish(attempt, Instant.now());
        }
        return attempt;
    }

    private void checkIndex(TestAttempt attempt, int index) {
        if (index < 0 || index >= attempt.getQuestions().size()) {
            throw error(HttpStatus.BAD_REQUEST, "Неверный номер вопроса");
        }
    }

    private void finish(TestAttempt attempt, Instant finishedAt) {
        int correctCount = 0;
        double score = 0;
        double maxScore = 0;
        for (var question : attempt.getQuestions()) {
            boolean exact = question.isMultiple()
                    ? question.getOptions().stream().allMatch(o -> o.isSelected() == o.isCorrect())
                    : question.getOptions().stream().anyMatch(o -> o.isSelected() && o.isCorrect());
            double questionMax = question.isMultiple()
                    ? question.getOptions().stream().filter(o -> o.getPoints() > 0).mapToInt(AttemptOption::getPoints).sum()
                    : question.getOptions().stream().mapToInt(AttemptOption::getPoints).max().orElse(0);
            maxScore += questionMax;
            if (!question.isMultiple()) {
                if (exact) correctCount++;
                score += question.getOptions().stream().filter(AttemptOption::isSelected)
                        .filter(AttemptOption::isCorrect).mapToInt(AttemptOption::getPoints).sum();
                continue;
            }
            var mode = answerMode(attempt.getAnswerMode());
            if (mode == MultiAnswerMode.NONE) {
                if (exact) {
                    score += questionMax;
                    correctCount++;
                }
            } else if (mode == MultiAnswerMode.FULL) {
                if (question.getOptions().stream().anyMatch(o -> o.isSelected() && o.isCorrect())) {
                    score += questionMax;
                    correctCount++;
                }
            } else {
                if (exact) correctCount++;
                score += question.getOptions().stream().filter(o -> o.isSelected() && o.isCorrect())
                        .mapToInt(AttemptOption::getPoints).sum();
            }
        }
        attempt.setCorrectCount(correctCount);
        attempt.setScore(score);
        attempt.setMaxScore(maxScore);
        attempt.setPercentage(maxScore == 0 ? 0 : Math.round(score / maxScore * 10000.0) / 100.0);
        attempt.setFinishedAt(finishedAt);
    }

    public void finishExpiredAttempts() {
        var now = Instant.now();
        for (var item : attempts.findByFinishedAtIsNullAndDeadlineLessThanEqual(now)) {
            finishLocked(item.getId(), item.getDeadline());
        }
    }

    public void finishOpenAttempts(Long testId, Instant finishedAt) {
        for (var item : attempts.findByTestIdAndFinishedAtIsNull(testId)) {
            finishLocked(item.getId(), finishedAt);
        }
    }

    public void finishAttemptsForUser(Long userId, Instant finishedAt) {
        for (var item : attempts.findByUserIdAndFinishedAtIsNull(userId)) {
            finishLocked(item.getId(), finishedAt);
        }
    }

    private void finishLocked(Long id, Instant finishedAt) {
        var attempt = attempts.findByIdForUpdate(id).orElse(null);
        if (attempt == null || attempt.isFinished()) return;
        attempt.getQuestions().forEach(question -> question.getOptions().size());
        var effectiveFinish = attempt.getDeadline() != null && attempt.getDeadline().isBefore(finishedAt)
                ? attempt.getDeadline() : finishedAt;
        finish(attempt, effectiveFinish);
    }

    private MultiAnswerMode answerMode(String value) {
        if (value == null || "POINTS".equals(value)) return MultiAnswerMode.PARTIAL;
        try {
            return MultiAnswerMode.valueOf(value);
        } catch (IllegalArgumentException error) {
            return MultiAnswerMode.PARTIAL;
        }
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
