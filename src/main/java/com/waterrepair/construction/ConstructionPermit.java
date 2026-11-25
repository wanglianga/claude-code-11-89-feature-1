package com.waterrepair.construction;

import com.waterrepair.construction.geom.GeoPoint;
import com.waterrepair.construction.geom.GeoPointListConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 道路施工许可。几何/封路/深度每次修改 geometryVersion+1；
 * 审查结论引用许可版本与管网测绘版本，任一侧升版后旧结论转为 PENDING_REVIEW，不得自动放行。
 */
@Entity
@Table(name = "construction_permit")
public class ConstructionPermit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String permitNo;

    @Column(nullable = false, length = 128)
    private String contractor;

    @Column(nullable = false, length = 256)
    private String title;

    @Convert(converter = GeoPointListConverter.class)
    @Column(nullable = false, length = 8192)
    private List<GeoPoint> polygon = new ArrayList<>();

    /** 开挖深度（米，自地面向下） */
    @Column(nullable = false)
    private Double excavationDepthMeters;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** 被封闭道路的 RoadSegment.code 列表 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "permit_closed_road", joinColumns = @JoinColumn(name = "permit_id"))
    @OrderColumn(name = "idx")
    @Column(name = "road_code", length = 32)
    private List<String> closedRoadCodes = new ArrayList<>();

    @Column(nullable = false)
    private Integer geometryVersion = 1;

    /** REVIEWING / APPROVED / RETURNED / WITHDRAWN */
    @Column(nullable = false, length = 32)
    private String status = "REVIEWING";

    @Column(length = 512)
    private String lastChangeReason;

    public ConstructionPermit() {
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

    public String getContractor() {
        return contractor;
    }

    public void setContractor(String contractor) {
        this.contractor = contractor;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<GeoPoint> getPolygon() {
        return polygon;
    }

    public void setPolygon(List<GeoPoint> polygon) {
        this.polygon = polygon == null ? new ArrayList<>() : new ArrayList<>(polygon);
    }

    public Double getExcavationDepthMeters() {
        return excavationDepthMeters;
    }

    public void setExcavationDepthMeters(Double excavationDepthMeters) {
        this.excavationDepthMeters = excavationDepthMeters;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public List<String> getClosedRoadCodes() {
        return closedRoadCodes;
    }

    public void setClosedRoadCodes(List<String> closedRoadCodes) {
        this.closedRoadCodes = closedRoadCodes == null ? new ArrayList<>() : new ArrayList<>(closedRoadCodes);
    }

    public Integer getGeometryVersion() {
        return geometryVersion;
    }

    public void setGeometryVersion(Integer geometryVersion) {
        this.geometryVersion = geometryVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastChangeReason() {
        return lastChangeReason;
    }

    public void setLastChangeReason(String lastChangeReason) {
        this.lastChangeReason = lastChangeReason;
    }
}
