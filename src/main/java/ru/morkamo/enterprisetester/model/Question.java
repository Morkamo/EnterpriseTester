package ru.morkamo.enterprisetester.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "questions")
@Getter @Setter
public class Question {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "question", columnDefinition = "TEXT")
    private String text;
    @OneToMany
    @JoinColumn(name = "question_id")
    private List<Answer> answers = new ArrayList<>();
}
