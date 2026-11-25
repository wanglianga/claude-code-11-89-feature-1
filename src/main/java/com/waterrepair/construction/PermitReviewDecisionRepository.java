package com.waterrepair.construction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PermitReviewDecisionRepository extends JpaRepository<PermitReviewDecision, Long> {

    List<PermitReviewDecision> findByPermitNoOrderByIdDesc(String permitNo);

    List<PermitReviewDecision> findByPermitNoAndReviewState(String permitNo, String reviewState);
}
