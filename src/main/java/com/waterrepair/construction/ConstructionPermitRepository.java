package com.waterrepair.construction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConstructionPermitRepository extends JpaRepository<ConstructionPermit, Long> {
    Optional<ConstructionPermit> findByPermitNo(String permitNo);

    List<ConstructionPermit> findAllByOrderByIdAsc();
}
