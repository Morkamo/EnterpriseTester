package ru.morkamo.enterprisetester.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.morkamo.enterprisetester.config.TestSettings;
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

    public TestService(TestRepository tests, TestAttemptRepository attempts, TestSettings settings) {
        this.tests = tests;
        this.attempts = attempts;
        this.settings = settings;
    }

    public List<TestDefinition> listTests() {
        return tests.findByTestClosedFalseOrderByIdAsc();
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
        var test = getTest(testId);
        var active = attempts.findFirstByTestIdAndUserIdAndFinishedAtIsNullOrderByIdDesc(testId, userId);
        if (active.isPresent()) {
            var previous = getAttempt(active.get().getId(), userId);
            if (!previous.isFinished()) return previous;
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
        attempt.setShowCountdown(settings.isShowCountdown());
        var minutes = timeLimit(test);
        if (settings.isEnableTimeLimit() && test.isTemporaryTest()) {
            if (minutes == null || minutes < 1) {
                throw error(HttpStatus.CONFLICT, "Для временного теста укажите время в минутах");
            }
            attempt.setDeadline(attempt.getStartedAt().plusSeconds(minutes * 60L));
        }

        // Copy the questions so later edits to the test do not change an existing attempt.
        for (var question : questions.subList(0, Math.min(questions.size(), settings.getDefaultQuestionsCount()))) {
            if (question.getAnswers().stream().noneMatch(Answer::isCorrect)) {
                throw error(HttpStatus.CONFLICT, "У вопроса нет правильных вариантов ответа");
            }
            var copy = new AttemptQuestion();
            copy.setText(question.getText());
            copy.setMultiple(question.getAnswers().stream().filter(Answer::isCorrect).count() > 1);
            var answers = new ArrayList<>(question.getAnswers());
            Collections.shuffle(answers);
            for (var answer : answers) {
                var option = new AttemptOption();
                option.setText(answer.getText());
                option.setCorrect(answer.isCorrect());
                copy.getOptions().add(option);
            }
            attempt.getQuestions().add(copy);
        }
        return attempts.save(attempt);
    }

    public TestAttempt getAttempt(Long id, Long userId) {
        // Saving answers and finishing use the same database lock.
        var attempt = attempts.findForUpdate(id, userId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Прохождение не найдено"));
        attempt.getQuestions().forEach(q -> q.getOptions().size());
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
        for (var question : attempt.getQuestions()) {
            long correct = question.getOptions().stream().filter(AttemptOption::isCorrect).count();
            long selectedCorrect = question.getOptions().stream()
                    .filter(o -> o.isSelected() && o.isCorrect()).count();
            boolean exact = question.getOptions().stream().allMatch(o -> o.isSelected() == o.isCorrect());
            if (exact) {
                correctCount++;
            }
            score += switch (TestSettings.AnswerMode.valueOf(attempt.getAnswerMode())) {
                case NONE -> exact ? 1 : 0;
                case PARTIAL -> (double) selectedCorrect / correct;
                case FULL -> selectedCorrect > 0 ? 1 : 0;
            };
        }
        attempt.setCorrectCount(correctCount);
        attempt.setScore(score);
        attempt.setPercentage(Math.round(score / attempt.getQuestions().size() * 10000.0) / 100.0);
        attempt.setFinishedAt(finishedAt);
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
