package ru.morkamo.enterprisetester.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "attempt_questions")
@Getter @Setter
public class AttemptQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition = "TEXT")
    private String text;
    @ElementCollection
    @CollectionTable(name = "attempt_question_images", joinColumns = @JoinColumn(name = "attempt_question_id"))
    @Column(name = "image_name", nullable = false)
    @OrderColumn(name = "position")
    private List<String> imageNames = new ArrayList<>();
    private boolean multiple;
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "attempt_question_id", nullable = false)
    @OrderColumn(name = "position")
    private List<AttemptOption> options = new ArrayList<>();
}
