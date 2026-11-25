package com.waterrepair.construction;

import com.waterrepair.construction.geom.GeoPoint;
import com.waterrepair.construction.geom.Polygon;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不依赖 Spring 容器/数据库的几何与冲突引擎测试，固定题面关键结论：
 * P17 中心线距开挖边界 1.6m、误差 ±0.8m、保护距离 2m => 可能侵入而非确定安全；
 * 并覆盖确定侵入/安全三态、许可缩界退出保护区、道路可达性两类影响不混淆。
 */
class ConflictEngineTest {

    private final ConflictEngine engine = new ConflictEngine();

    private ConstructionPermit permit(List<GeoPoint> polygon, List<String> closedRoads) {
        ConstructionPermit p = new ConstructionPermit();
        p.setPermitNo("WP-T");
        p.setContractor("测试承包商");
        p.setTitle("测试许可");
        p.setPolygon(polygon);
        p.setExcavationDepthMeters(1.5);
        p.setStartDate(LocalDate.parse("2026-11-25"));
        p.setEndDate(LocalDate.parse("2026-11-28"));
        p.setClosedRoadCodes(closedRoads);
        return p;
    }

    private PipeProtectionProfile pipe(String code, List<GeoPoint> line, double error,
                                       double protection, double depth) {
        return new PipeProtectionProfile(code, code, null, line, 1, error, protection,
                depth, false, null, "test", null);
    }

    private List<GeoPoint> basePolygon() {
        // 顶边 y=10，与 P17 中心线 y=11.6 相距 1.6m
        return List.of(new GeoPoint(15, 0), new GeoPoint(45, 0),
                new GeoPoint(45, 10), new GeoPoint(15, 10));
    }

    @Test
    void p17ScenarioIsPossibleIntrusionNotSafe() {
        ConstructionPermit p = permit(basePolygon(), List.of("R-JIANSHE"));
        PipeProtectionProfile p17 = pipe("P17",
                List.of(new GeoPoint(10, 11.6), new GeoPoint(70, 11.6)), 0.8, 2.0, 1.2);

        List<ConflictEngine.ProtectionFinding> findings =
                engine.evaluateProtection(p, List.of(p17));
        assertThat(findings).hasSize(1);
        ConflictEngine.ProtectionFinding f = findings.get(0);
        assertThat(f.nominalDistanceMeters()).isEqualTo(1.6);
        assertThat(f.uncertaintyOuterReachMeters()).isEqualTo(2.8);
        assertThat(f.severity()).isEqualTo(ConflictEngine.SEVERITY_POSSIBLE);
        assertThat(f.depthReached()).isTrue(); // 开挖 1.5m 达到管顶埋深 1.2m
        assertThat(f.evidence()).anyMatch(e -> e.contains("d_nom=1.60m"));
    }

    @Test
    void farAwayPipeIsSafeAndExcludedFromActionable() {
        ConstructionPermit p = permit(basePolygon(), List.of());
        // 中心线距顶边 4.6m，超过 e+P=2.8m
        PipeProtectionProfile far = pipe("PFAR",
                List.of(new GeoPoint(10, 14.6), new GeoPoint(70, 14.6)), 0.8, 2.0, 1.2);

        List<ConflictEngine.ProtectionFinding> findings =
                engine.evaluateProtection(p, List.of(far));
        assertThat(findings.get(0).severity()).isEqualTo(ConflictEngine.SEVERITY_SAFE);
        assertThat(engine.actionableProtection(findings)).isEmpty();
    }

    @Test
    void centerlineInsidePolygonIsDefiniteIntrusion() {
        ConstructionPermit p = permit(basePolygon(), List.of());
        PipeProtectionProfile crossing = pipe("PIN",
                List.of(new GeoPoint(10, 5), new GeoPoint(70, 5)), 0.8, 2.0, 1.2);

        ConflictEngine.ProtectionFinding f =
                engine.evaluateProtection(p, List.of(crossing)).get(0);
        assertThat(f.nominalDistanceMeters()).isZero();
        assertThat(f.severity()).isEqualTo(ConflictEngine.SEVERITY_DEFINITE);
    }

    @Test
    void shrunkenPolygonLeavesProtectionZoneAndBecomesSafe() {
        // 缩界后顶边降到 y=7，与 P17(y=11.6) 相距 4.6m > 2.8m
        List<GeoPoint> shrunk = List.of(new GeoPoint(18, 3), new GeoPoint(42, 3),
                new GeoPoint(42, 7), new GeoPoint(18, 7));
        ConstructionPermit p = permit(shrunk, List.of());
        PipeProtectionProfile p17 = pipe("P17",
                List.of(new GeoPoint(10, 11.6), new GeoPoint(70, 11.6)), 0.8, 2.0, 1.2);

        ConflictEngine.ProtectionFinding f =
                engine.evaluateProtection(p, List.of(p17)).get(0);
        assertThat(f.nominalDistanceMeters()).isEqualTo(4.6);
        assertThat(f.severity()).isEqualTo(ConflictEngine.SEVERITY_SAFE);
    }

    @Test
    void inwardBufferMovesTopEdgeAwayFromPipe() {
        Polygon base = new Polygon(basePolygon());
        Polygon smaller = base.buffer(3.0, true);
        // 内缩 3m 后顶边 y=10 → y=7
        assertThat(smaller.vertices().stream().mapToDouble(GeoPoint::y).max().orElseThrow())
                .isCloseTo(7.0, org.assertj.core.data.Offset.offset(1e-9));
        Polygon bigger = base.buffer(3.0, false);
        assertThat(bigger.vertices().stream().mapToDouble(GeoPoint::y).max().orElseThrow())
                .isCloseTo(13.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void accessImpactIsSeparateFromProtectionAndKeepsAlternativeRoute() {
        ConstructionPermit p = permit(basePolygon(), List.of("R-JIANSHE"));
        RoadSegment jianshe = new RoadSegment("R-JIANSHE", "建设路",
                List.of(new GeoPoint(20, -20), new GeoPoint(20, 0), new GeoPoint(20, 30)));
        RoadSegment guangming = new RoadSegment("R-GUANGMING", "光明路",
                List.of(new GeoPoint(0, 0), new GeoPoint(40, 0), new GeoPoint(80, 0)));
        RepairWindow window = new RepairWindow("RW-001", "凌晨抢修", "C_SOLE",
                OffsetDateTime.parse("2026-11-26T04:00:00+08:00"),
                OffsetDateTime.parse("2026-11-26T09:00:00+08:00"),
                "R-JIANSHE,R-GUANGMING", "SCHEDULED", 1, "note");

        List<ConflictEngine.AccessFinding> findings =
                engine.evaluateAccess(p, List.of(jianshe, guangming), List.of(window));
        assertThat(findings).hasSize(1);
        ConflictEngine.AccessFinding f = findings.get(0);
        assertThat(f.roadCode()).isEqualTo("R-JIANSHE");
        assertThat(f.repairWindowCode()).isEqualTo("RW-001");
        assertThat(f.blockedRoadCodes()).containsExactly("R-JIANSHE");
        assertThat(f.alternativeRoadCodes()).containsExactly("R-GUANGMING");
        assertThat(f.allPlannedRoadsBlocked()).isFalse();
        assertThat(f.advisory()).contains("替代进场路径");
    }

    @Test
    void windowOutsidePermitDatesHasNoAccessImpact() {
        ConstructionPermit p = permit(basePolygon(), List.of("R-JIANSHE"));
        RoadSegment jianshe = new RoadSegment("R-JIANSHE", "建设路",
                List.of(new GeoPoint(20, -20), new GeoPoint(20, 30)));
        RepairWindow window = new RepairWindow("RW-OLD", "过期窗口", "C_SOLE",
                OffsetDateTime.parse("2026-12-20T04:00:00+08:00"),
                OffsetDateTime.parse("2026-12-20T09:00:00+08:00"),
                "R-JIANSHE", "SCHEDULED", 1, "note");
        assertThat(engine.evaluateAccess(p, List.of(jianshe), List.of(window))).isEmpty();
    }
}
