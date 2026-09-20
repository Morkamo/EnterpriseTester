package ru.morkamo.enterprisetester.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
    private int points;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;
}
