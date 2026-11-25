package com.waterrepair.construction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoadSegmentRepository extends JpaRepository<RoadSegment, Long> {
    Optional<RoadSegment> findByCode(String code);

    List<RoadSegment> findAllByOrderByCodeAsc();
}
