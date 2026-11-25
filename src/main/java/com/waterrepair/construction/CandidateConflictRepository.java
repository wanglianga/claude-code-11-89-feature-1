package com.waterrepair.construction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandidateConflictRepository extends JpaRepository<CandidateConflict, Long> {

    List<CandidateConflict> findByPermitNoOrderByIdAsc(String permitNo);

    List<CandidateConflict> findByPermitNoAndStatusOrderByIdAsc(String permitNo, String status);

    List<CandidateConflict> findByStatus(String status);
}
