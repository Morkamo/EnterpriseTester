package ru.morkamo.enterprisetester.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "test_attempts")
@Getter @Setter
public class TestAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long testId;
    private Long userId;
    private String testName;
    private Instant startedAt;
    private Instant deadline;
    private Instant finishedAt;
    private int currentQuestion;
    private int correctCount;
    private double score;
    private double percentage;
    private String answerMode;
    private boolean showCountdown;
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "attempt_id", nullable = false)
    @OrderColumn(name = "position")
    private List<AttemptQuestion> questions = new ArrayList<>();

    public boolean isFinished() {
        return finishedAt != null;
    }
}
