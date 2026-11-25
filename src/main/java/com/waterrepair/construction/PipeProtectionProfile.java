package com.waterrepair.construction;

import com.waterrepair.construction.geom.GeoPoint;
import com.waterrepair.construction.geom.GeoPointListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管段防护台账：中心线（局部工程坐标，米）、测绘版本与位置误差带（±surveyErrorMeters）、
 * 法定保护距离、埋深、重要用户。测绘升版时新增版本而非覆盖历史结论。
 */
@Entity
@Table(name = "pipe_protection_profile")
public class PipeProtectionProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 可选：关联管网图中的 PipeSegment.code（施工模块几何以自身中心线为准） */
    @Column(length = 32)
    private String segmentCode;

    @Convert(converter = GeoPointListConverter.class)
    @Column(nullable = false, length = 8192)
    private List<GeoPoint> centerline = new ArrayList<>();

    @Column(nullable = false)
    private Integer surveyVersion = 1;

    /** 测绘位置误差带半宽（米），真实管线位于中心线 ±该值范围内 */
    @Column(nullable = false)
    private Double surveyErrorMeters;

    /** 保护距离（米）：真实管线外缘向外的保护区宽度 */
    @Column(nullable = false)
    private Double protectionDistanceMeters;

    /** 管顶埋深（米） */
    @Column(nullable = false)
    private Double burialDepthMeters;

    @Column(nullable = false)
    private Boolean importantUser = false;

    @Column(length = 256)
    private String importantUserNote;

    @Column(length = 256)
    private String updatedReason;

    private OffsetDateTime updatedAt;

    public PipeProtectionProfile() {
    }

    public PipeProtectionProfile(String code, String name, String segmentCode, List<GeoPoint> centerline,
                                 Integer surveyVersion, Double surveyErrorMeters,
                                 Double protectionDistanceMeters, Double burialDepthMeters,
                                 Boolean importantUser, String importantUserNote,
                                 String updatedReason, OffsetDateTime updatedAt) {
        this.code = code;
        this.name = name;
        this.segmentCode = segmentCode;
        this.centerline = centerline == null ? new ArrayList<>() : new ArrayList<>(centerline);
        this.surveyVersion = surveyVersion;
        this.surveyErrorMeters = surveyErrorMeters;
        this.protectionDistanceMeters = protectionDistanceMeters;
        this.burialDepthMeters = burialDepthMeters;
        this.importantUser = importantUser;
        this.importantUserNote = importantUserNote;
        this.updatedReason = updatedReason;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSegmentCode() {
        return segmentCode;
    }

    public List<GeoPoint> getCenterline() {
        return centerline;
    }

    public void setCenterline(List<GeoPoint> centerline) {
        this.centerline = centerline == null ? new ArrayList<>() : new ArrayList<>(centerline);
    }

    public Integer getSurveyVersion() {
        return surveyVersion;
    }

    public void setSurveyVersion(Integer surveyVersion) {
        this.surveyVersion = surveyVersion;
    }

    public Double getSurveyErrorMeters() {
        return surveyErrorMeters;
    }

    public void setSurveyErrorMeters(Double surveyErrorMeters) {
        this.surveyErrorMeters = surveyErrorMeters;
    }

    public Double getProtectionDistanceMeters() {
        return protectionDistanceMeters;
    }

    public void setProtectionDistanceMeters(Double protectionDistanceMeters) {
        this.protectionDistanceMeters = protectionDistanceMeters;
    }

    public Double getBurialDepthMeters() {
        return burialDepthMeters;
    }

    public void setBurialDepthMeters(Double burialDepthMeters) {
        this.burialDepthMeters = burialDepthMeters;
    }

    public Boolean getImportantUser() {
        return importantUser;
    }

    public void setImportantUser(Boolean importantUser) {
        this.importantUser = importantUser;
    }

    public String getImportantUserNote() {
        return importantUserNote;
    }

    public void setImportantUserNote(String importantUserNote) {
        this.importantUserNote = importantUserNote;
    }

    public String getUpdatedReason() {
        return updatedReason;
    }

    public void setUpdatedReason(String updatedReason) {
        this.updatedReason = updatedReason;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
