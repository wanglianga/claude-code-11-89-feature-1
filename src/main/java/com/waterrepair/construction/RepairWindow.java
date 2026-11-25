package com.waterrepair.construction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 已排定的抢修窗口（来源可以是抢修调度系统的派工计划）。
 * 施工封路若阻断窗口班组的计划进场道路，则产生 ACCESS 类影响，由值班调度保留替代进场路径。
 */
@Entity
@Table(name = "repair_window")
public class RepairWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, length = 32)
    private String crewCode;

    @Column(nullable = false)
    private OffsetDateTime windowStart;

    @Column(nullable = false)
    private OffsetDateTime windowEnd;

    /** 计划进场道路 RoadSegment.code 列表（按优先顺序） */
    @Column(nullable = false, length = 512)
    private String plannedApproachRoadCodes;

    /** SCHEDULED / IN_PROGRESS / DONE / CANCELLED */
    @Column(nullable = false, length = 32)
    private String status = "SCHEDULED";

    @Column(nullable = false)
    private Integer revision = 1;

    @Column(length = 256)
    private String note;

    public RepairWindow() {
    }

    public RepairWindow(String code, String title, String crewCode, OffsetDateTime windowStart,
                        OffsetDateTime windowEnd, String plannedApproachRoadCodes,
                        String status, Integer revision, String note) {
        this.code = code;
        this.title = title;
        this.crewCode = crewCode;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.plannedApproachRoadCodes = plannedApproachRoadCodes;
        this.status = status;
        this.revision = revision;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getCrewCode() {
        return crewCode;
    }

    public OffsetDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(OffsetDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public OffsetDateTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(OffsetDateTime windowEnd) {
        this.windowEnd = windowEnd;
    }

    public String getPlannedApproachRoadCodes() {
        return plannedApproachRoadCodes;
    }

    public void setPlannedApproachRoadCodes(String plannedApproachRoadCodes) {
        this.plannedApproachRoadCodes = plannedApproachRoadCodes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
