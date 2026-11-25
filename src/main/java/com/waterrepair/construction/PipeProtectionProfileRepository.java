package com.waterrepair.construction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PipeProtectionProfileRepository extends JpaRepository<PipeProtectionProfile, Long> {
    Optional<PipeProtectionProfile> findByCode(String code);

    List<PipeProtectionProfile> findAllByOrderByCodeAsc();
}
