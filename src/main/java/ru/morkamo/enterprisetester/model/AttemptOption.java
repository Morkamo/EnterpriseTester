package ru.morkamo.enterprisetester.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "attempt_options")
@Getter @Setter
public class AttemptOption {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition = "TEXT")
    private String text;
    private boolean correct;
    private boolean selected;
}
