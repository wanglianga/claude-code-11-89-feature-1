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

import java.util.ArrayList;
import java.util.List;

/**
 * 道路中心线（折线段）。抢修班组沿道路进场；许可封路段与道路中心线相交即视为该道路通行被阻断，
 * 调度需在道路图上保留替代进场路径。
 */
@Entity
@Table(name = "road_segment")
public class RoadSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Convert(converter = GeoPointListConverter.class)
    @Column(nullable = false, length = 8192)
    private List<GeoPoint> centerline = new ArrayList<>();

    public RoadSegment() {
    }

    public RoadSegment(String code, String name, List<GeoPoint> centerline) {
        this.code = code;
        this.name = name;
        this.centerline = centerline == null ? new ArrayList<>() : new ArrayList<>(centerline);
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

    public List<GeoPoint> getCenterline() {
        return centerline;
    }

    public void setCenterline(List<GeoPoint> centerline) {
        this.centerline = centerline == null ? new ArrayList<>() : new ArrayList<>(centerline);
    }
}
