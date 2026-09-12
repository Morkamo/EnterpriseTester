package ru.morkamo.enterprisetester.service;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.morkamo.enterprisetester.repository.TestAttemptRepository;
import java.time.Instant;

@Component
@EnableScheduling
public class TestDeadlineScheduler {
    private final TestAttemptRepository attempts;
    private final TestService tests;

    public TestDeadlineScheduler(TestAttemptRepository attempts, TestService tests) {
        this.attempts = attempts;
        this.tests = tests;
    }

    @Scheduled(fixedDelay = 15000)
    public void finishExpiredAttempts() {
        for (var attempt : attempts.findByFinishedAtIsNullAndDeadlineLessThanEqual(Instant.now())) {
            tests.getAttempt(attempt.getId(), attempt.getUserId());
        }
    }
}
