package ru.morkamo.enterprisetester.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Table(name = "answers")
@Getter @Setter
public class Answer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String text;
    @Column(name = "is_correct_answer")
    private boolean correct;
    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal points = BigDecimal.ZERO;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    public String getPointsText() {
        return points.stripTrailingZeros().toPlainString();
    }
}
