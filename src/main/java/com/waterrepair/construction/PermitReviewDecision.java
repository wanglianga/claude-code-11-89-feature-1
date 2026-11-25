package com.waterrepair.construction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 许可审查结论（逐项审批动作）。
 * 结论快照当时的许可几何版本与管段测绘/窗口版本；变化发生后 reviewState 置 PENDING_REVIEW，
 * 审批接口拒绝在待复核结论上继续放行，必须重新计算并形成新结论。
 */
@Entity
@Table(name = "permit_review_decision")
public class PermitReviewDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String permitNo;

    /** PROTECTION / ACCESS，两类影响分别建单、分别流转，不合并为一个布尔冲突 */
    @Column(nullable = false, length = 16)
    private String impactType;

    @Column(nullable = false, length = 32)
    private String targetCode;

    @Column(length = 32)
    private String repairWindowCode;

    /** FIELD_SURVEY_REQUIRED（要求现场探测）/ PROTECTION_CONDITION（附加保护条件）/ RETURNED（退回修改）/ NO_ACTION */
    @Column(nullable = false, length = 32)
    private String action;

    @Column(nullable = false, length = 32)
    private String severityAtDecision;

    @Column(nullable = false)
    private Integer permitGeometryVersion;

    @Column(nullable = false)
    private Integer sideVersionAtDecision;

    /** ACTIVE（结论仍可作为放行依据）/ PENDING_REVIEW（依据已变化，待复核）/ CLOSED */
    @Column(nullable = false, length = 16)
    private String reviewState = "ACTIVE";

    @Column(nullable = false, length = 1024)
    private String evidenceSummary;

    @Column(length = 512)
    private String conditionNote;

    @Column(nullable = false, length = 64)
    private String decidedBy;

    @Column(nullable = false)
    private OffsetDateTime decidedAt;

    /** 置 PENDING_REVIEW 时的原因分类：PERMIT_GEOMETRY_CHANGED / PIPE_SURVEY_REVISED / REPAIR_WINDOW_CHANGED */
    @Column(length = 32)
    private String pendingReason;

    @Column(length = 1024)
    private String pendingDetail;

    private OffsetDateTime pendingAt;

    public PermitReviewDecision() {
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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getSeverityAtDecision() {
        return severityAtDecision;
    }

    public void setSeverityAtDecision(String severityAtDecision) {
        this.severityAtDecision = severityAtDecision;
    }

    public Integer getPermitGeometryVersion() {
        return permitGeometryVersion;
    }

    public void setPermitGeometryVersion(Integer permitGeometryVersion) {
        this.permitGeometryVersion = permitGeometryVersion;
    }

    public Integer getSideVersionAtDecision() {
        return sideVersionAtDecision;
    }

    public void setSideVersionAtDecision(Integer sideVersionAtDecision) {
        this.sideVersionAtDecision = sideVersionAtDecision;
    }

    public String getReviewState() {
        return reviewState;
    }

    public void setReviewState(String reviewState) {
        this.reviewState = reviewState;
    }

    public String getEvidenceSummary() {
        return evidenceSummary;
    }

    public void setEvidenceSummary(String evidenceSummary) {
        this.evidenceSummary = evidenceSummary;
    }

    public String getConditionNote() {
        return conditionNote;
    }

    public void setConditionNote(String conditionNote) {
        this.conditionNote = conditionNote;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(OffsetDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getPendingReason() {
        return pendingReason;
    }

    public void setPendingReason(String pendingReason) {
        this.pendingReason = pendingReason;
    }

    public String getPendingDetail() {
        return pendingDetail;
    }

    public void setPendingDetail(String pendingDetail) {
        this.pendingDetail = pendingDetail;
    }

    public OffsetDateTime getPendingAt() {
        return pendingAt;
    }

    public void setPendingAt(OffsetDateTime pendingAt) {
        this.pendingAt = pendingAt;
    }
}
