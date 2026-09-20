package ru.morkamo.enterprisetester.service;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class TestDeadlineScheduler {
    private final TestService tests;

    public TestDeadlineScheduler(TestService tests) {
        this.tests = tests;
    }

    @Scheduled(fixedDelay = 15000)
    public void finishExpiredAttempts() {
        tests.finishExpiredAttempts();
    }
}
