package com.ladygo.ratings.domain;

import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ratings")
@Getter
@Setter
public class Rating extends CreatedEntity {

    @Column(name = "ride_id", nullable = false)
    private UUID rideId;

    @Column(name = "rater_id", nullable = false)
    private UUID raterId;

    @Column(name = "ratee_id", nullable = false)
    private UUID rateeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rater_role", length = 10, nullable = false)
    private RaterRole raterRole;

    @Column(name = "score", nullable = false)
    private short score;

    @Column(name = "comment", length = 1000)
    private String comment;
}
