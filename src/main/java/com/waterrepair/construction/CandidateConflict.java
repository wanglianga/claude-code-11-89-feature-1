package com.waterrepair.construction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 物化候选冲突关系（计算路线 B：在许可/管网/抢修窗口变化时维护）。
 * 每条关系同时记录许可几何版本与管段测绘版本，审批只允许引用两侧版本与当前一致的 ACTIVE 关系；
 * 任一侧升版后旧关系置 SUPERSEDED 并清除出候选集，保证“审批引用的两侧版本一致”。
 */
@Entity
@Table(name = "candidate_conflict")
public class CandidateConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String permitNo;

    /** PROTECTION（保护区相交，管线工程师评估）/ ACCESS（道路可达性，值班调度保留替代进场路径） */
    @Column(nullable = false, length = 16)
    private String impactType;

    /** PROTECTION 时为管段 code；ACCESS 时为道路 code */
    @Column(nullable = false, length = 32)
    private String targetCode;

    /** ACCESS 影响关联的抢修窗口（PROTECTION 时可为空） */
    @Column(length = 32)
    private String repairWindowCode;

    @Column(nullable = false)
    private Integer permitGeometryVersion;

    /** PROTECTION 关系记录的测绘版本；ACCESS 关系记录抢修窗口 revision */
    @Column(nullable = false)
    private Integer sideVersion;

    /** ACTIVE / SUPERSEDED（许可缩界、管网升版、窗口变化后失效） */
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(length = 256)
    private String severity;

    @Column(nullable = false, length = 1024)
    private String evidenceSummary;

    @Column(nullable = false)
    private OffsetDateTime computedAt;

    @Column(length = 256)
    private String supersededReason;

    private OffsetDateTime supersededAt;

    public CandidateConflict() {
    }

    public Long getId() {
        return id;
    }

    public String getPermitNo() {
        return permitNo;
    }

    public void setPermitNo(String permitNo) {
        this.permitNo = permitNo;
    }

    public String getImpactType() {
        return impactType;
    }

    public void setImpactType(String impactType) {
        this.impactType = impactType;
    }

    public String getTargetCode() {
        return targetCode;
    }

    public void setTargetCode(String targetCode) {
        this.targetCode = targetCode;
    }

    public String getRepairWindowCode() {
        return repairWindowCode;
    }

    public void setRepairWindowCode(String repairWindowCode) {
        this.repairWindowCode = repairWindowCode;
    }

    public Integer getPermitGeometryVersion() {
        return permitGeometryVersion;
    }

    public void setPermitGeometryVersion(Integer permitGeometryVersion) {
        this.permitGeometryVersion = permitGeometryVersion;
    }

    public Integer getSideVersion() {
        return sideVersion;
    }

    public void setSideVersion(Integer sideVersion) {
        this.sideVersion = sideVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getEvidenceSummary() {
        return evidenceSummary;
    }

    public void setEvidenceSummary(String evidenceSummary) {
        this.evidenceSummary = evidenceSummary;
    }

    public OffsetDateTime getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(OffsetDateTime computedAt) {
        this.computedAt = computedAt;
    }

    public String getSupersededReason() {
        return supersededReason;
    }

    public void setSupersededReason(String supersededReason) {
        this.supersededReason = supersededReason;
    }

    public OffsetDateTime getSupersededAt() {
        return supersededAt;
    }

    public void setSupersededAt(OffsetDateTime supersededAt) {
        this.supersededAt = supersededAt;
    }
}
