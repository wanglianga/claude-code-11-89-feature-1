package com.waterrepair.construction;

import com.waterrepair.construction.geom.GeoPoint;
import com.waterrepair.construction.geom.Polygon;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 施工冲突计算引擎（无状态纯计算）。
 *
 * <p>两条计算路线共用同一引擎，保证同一输入结论一致：
 * <ul>
 *   <li>路线 A（REALTIME）：审批打开时按当前几何即时计算，结果最新但每次重算；</li>
 *   <li>路线 B（MATERIALIZED）：许可/管网/窗口变化时调用同一引擎维护候选冲突关系，检索快。</li>
 * </ul>
 *
 * <p>保护区判定不做“最近距离单阈值”的布尔判断，而是三态：
 * <pre>
 *   d_nom = 施工多边形到管线中心线的最近距离
 *   e     = 测绘位置误差带半宽；P = 保护距离
 *   d_nom = 0               => DEFINITE_INTRUSION 中心线已在开挖界内
 *   0 &lt; d_nom &lt;= e + P     => POSSIBLE_INTRUSION 误差带/保护区可能进入施工范围
 *   d_nom &gt; e + P           => SAFE 名义与误差带外缘均远离施工区
 * </pre>
 * 题面 P17：d_nom=1.6m，e=±0.8m，P=2m，1.6 ≤ 0.8+2=2.8，故为“可能侵入”，不得判安全。
 */
@Component
public class ConflictEngine {

    public static final String SEVERITY_DEFINITE = "DEFINITE_INTRUSION";
    public static final String SEVERITY_POSSIBLE = "POSSIBLE_INTRUSION";
    public static final String SEVERITY_SAFE = "SAFE";

    public static final String TYPE_PROTECTION = "PROTECTION";
    public static final String TYPE_ACCESS = "ACCESS";

    /** 单根管段的保护区相交评估依据。 */
    public record ProtectionFinding(
            String pipeCode,
            String pipeName,
            int surveyVersion,
            double nominalDistanceMeters,
            double surveyErrorMeters,
            double protectionDistanceMeters,
            double uncertaintyOuterReachMeters,
            double burialDepthMeters,
            double excavationDepthMeters,
            boolean depthReached,
            boolean importantUser,
            String importantUserNote,
            String severity,
            String rationale,
            List<String> evidence) {
    }

    /** 一条抢修窗口的道路可达性评估依据。 */
    public record AccessFinding(
            String roadCode,
            String roadName,
            String repairWindowCode,
            String repairWindowTitle,
            String crewCode,
            List<String> blockedRoadCodes,
            List<String> alternativeRoadCodes,
            boolean allPlannedRoadsBlocked,
            String advisory,
            List<String> evidence) {
    }

    public List<ProtectionFinding> evaluateProtection(ConstructionPermit permit,
                                                       List<PipeProtectionProfile> pipes) {
        Polygon polygon = new Polygon(permit.getPolygon());
        List<ProtectionFinding> findings = new ArrayList<>();
        for (PipeProtectionProfile pipe : pipes) {
            List<GeoPoint> line = pipe.getCenterline();
            double nominal = polygon.distanceToPolyline(line);
            double reach = pipe.getSurveyErrorMeters() + pipe.getProtectionDistanceMeters();
            String severity;
            String rationale;
            if (nominal <= 1e-9) {
                severity = SEVERITY_DEFINITE;
                rationale = "管线中心线已进入施工多边形（名义距离 0m），无论测绘误差方向如何均与开挖区相交";
            } else if (nominal <= reach + 1e-9) {
                severity = SEVERITY_POSSIBLE;
                rationale = String.format(
                        "名义最近距离 %.2fm 未超出 误差带 %.2fm + 保护距离 %.2fm = %.2fm，"
                                + "真实管线受测绘误差影响可能进入保护区/开挖范围",
                        nominal, pipe.getSurveyErrorMeters(),
                        pipe.getProtectionDistanceMeters(), reach);
            } else {
                severity = SEVERITY_SAFE;
                rationale = String.format(
                        "名义最近距离 %.2fm > 误差带+保护距离 %.2fm，误差带外缘仍远离施工边界",
                        nominal, reach);
            }
            boolean depthReached = permit.getExcavationDepthMeters() >= pipe.getBurialDepthMeters();
            List<String> evidence = new ArrayList<>();
            evidence.add(String.format("名义最近距离 d_nom=%.2fm（施工多边形边界↔管线中心线）", nominal));
            evidence.add(String.format("测绘误差带 ±%.2fm，保护距离 %.2fm，误差带外缘控制值 e+P=%.2fm",
                    pipe.getSurveyErrorMeters(), pipe.getProtectionDistanceMeters(), reach));
            evidence.add(String.format("开挖深度 %.2fm，管顶埋深 %.2fm，%s",
                    permit.getExcavationDepthMeters(), pipe.getBurialDepthMeters(),
                    depthReached ? "开挖底面达到或超过管顶埋深，垂向存在接触风险"
                            : "开挖底面尚浅于管顶埋深，垂向不接触，但水平侵入判断不变"));
            if (Boolean.TRUE.equals(pipe.getImportantUser())) {
                evidence.add("重要用户管线：" + (pipe.getImportantUserNote() == null
                        ? "" : pipe.getImportantUserNote()));
            }
            findings.add(new ProtectionFinding(
                    pipe.getCode(), pipe.getName(), pipe.getSurveyVersion(),
                    round(nominal), pipe.getSurveyErrorMeters(), pipe.getProtectionDistanceMeters(),
                    round(reach), pipe.getBurialDepthMeters(), permit.getExcavationDepthMeters(),
                    depthReached, Boolean.TRUE.equals(pipe.getImportantUser()),
                    pipe.getImportantUserNote(), severity, rationale, evidence));
        }
        return findings;
    }

    public List<AccessFinding> evaluateAccess(ConstructionPermit permit,
                                              List<RoadSegment> roads,
                                              List<RepairWindow> windows) {
        Polygon polygon = new Polygon(permit.getPolygon());
        List<AccessFinding> findings = new ArrayList<>();
        LocalDate permitStart = permit.getStartDate();
        LocalDate permitEnd = permit.getEndDate();

        for (RepairWindow window : windows) {
            if (!"SCHEDULED".equals(window.getStatus()) && !"IN_PROGRESS".equals(window.getStatus())) {
                continue;
            }
            LocalDate wStart = window.getWindowStart().toLocalDate();
            LocalDate wEnd = window.getWindowEnd().toLocalDate();
            if (wEnd.isBefore(permitStart) || wStart.isAfter(permitEnd)) {
                continue; // 窗口与施工日期不重叠，封路不影响该次进场
            }
            List<String> planned = splitRoads(window.getPlannedApproachRoadCodes());
            List<String> blocked = new ArrayList<>();
            List<String> open = new ArrayList<>();
            List<String> evidence = new ArrayList<>();
            for (String roadCode : planned) {
                RoadSegment road = roads.stream().filter(r -> r.getCode().equals(roadCode)).findFirst()
                        .orElse(null);
                if (road == null) {
                    continue;
                }
                boolean closureListed = permit.getClosedRoadCodes().contains(roadCode);
                boolean geometryHit = polygon.intersectsPolyline(road.getCenterline());
                if (closureListed) {
                    blocked.add(roadCode);
                    evidence.add(String.format(
                            "道路 %s（%s）在许可封路清单内，且施工多边形与其中心线%s相交，通行被阻断",
                            roadCode, road.getName(), geometryHit ? "" : "未"));
                } else {
                    open.add(roadCode);
                    evidence.add(String.format("道路 %s（%s）不在封路清单，可作为替代进场路径",
                            roadCode, road.getName()));
                }
            }
            if (blocked.isEmpty()) {
                continue; // 该窗口的计划进场路径未受封路影响
            }
            boolean allBlocked = open.isEmpty();
            String advisory;
            if (allBlocked) {
                advisory = String.format(
                        "窗口 %s（班组 %s）的全部计划进场道路 %s 均被封闭，值班调度须重新制定进场路径"
                                + "或协调调整封路方案/抢修窗口",
                        window.getCode(), window.getCrewCode(), blocked);
            } else {
                advisory = String.format(
                        "封路 %s 阻断窗口 %s（班组 %s）原定进场道路，须保留替代进场路径 %s 并提前通知班组",
                        blocked, window.getCode(), window.getCrewCode(), open);
            }
            String firstBlocked = blocked.get(0);
            String roadName = roads.stream().filter(r -> r.getCode().equals(firstBlocked))
                    .map(RoadSegment::getName).findFirst().orElse(firstBlocked);
            findings.add(new AccessFinding(
                    firstBlocked, roadName, window.getCode(), window.getTitle(), window.getCrewCode(),
                    blocked, open, allBlocked, advisory, evidence));
        }
        return findings;
    }

    /** 只保留需要审批介入的非安全结论（SAFE 管段不生成候选冲突）。 */
    public List<ProtectionFinding> actionableProtection(List<ProtectionFinding> findings) {
        return findings.stream().filter(f -> !SEVERITY_SAFE.equals(f.severity())).toList();
    }

    private static List<String> splitRoads(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
