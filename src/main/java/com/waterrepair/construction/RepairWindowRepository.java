package com.waterrepair.construction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepairWindowRepository extends JpaRepository<RepairWindow, Long> {
    Optional<RepairWindow> findByCode(String code);

    List<RepairWindow> findAllByOrderByWindowStartAsc();
}
