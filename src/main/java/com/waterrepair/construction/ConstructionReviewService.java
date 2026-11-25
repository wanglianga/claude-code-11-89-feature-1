package com.waterrepair.construction;

import com.waterrepair.common.ApiException;
import com.waterrepair.construction.geom.GeoPoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 施工许可审查服务：
 * <ul>
 *   <li>路线 A（REALTIME）：{@link #realtimeReview} 每次按当前几何即时计算；</li>
 *   <li>路线 B（MATERIALIZED）：许可/管网/窗口变化时 {@link #refreshCandidates} 维护候选关系，
 *       关系携带两侧版本，失效关系立即置 SUPERSEDED，审批只引用版本一致的 ACTIVE 关系；</li>
 *   <li>两条路线调用同一个 {@link ConflictEngine}，{@link #compareRoutes} 核对同输入同结论。</li>
 * </ul>
 * 许可范围变化、测绘升版、抢修窗口变化都会把旧审查结论置 PENDING_REVIEW，放行接口据此拒绝自动放行。
 */
@Service
public class ConstructionReviewService {

    public static final String REASON_PERMIT_CHANGED = "PERMIT_GEOMETRY_CHANGED";
    public static final String REASON_SURVEY_REVISED = "PIPE_SURVEY_REVISED";
    public static final String REASON_WINDOW_CHANGED = "REPAIR_WINDOW_CHANGED";

    private final ConstructionPermitRepository permitRepo;
    private final PipeProtectionProfileRepository pipeRepo;
    private final RoadSegmentRepository roadRepo;
    private final RepairWindowRepository windowRepo;
    private final CandidateConflictRepository candidateRepo;
    private final PermitReviewDecisionRepository decisionRepo;
    private final ConflictEngine engine;

    public ConstructionReviewService(ConstructionPermitRepository permitRepo,
                                     PipeProtectionProfileRepository pipeRepo,
                                     RoadSegmentRepository roadRepo,
                                     RepairWindowRepository windowRepo,
                                     CandidateConflictRepository candidateRepo,
                                     PermitReviewDecisionRepository decisionRepo,
                                     ConflictEngine engine) {
        this.permitRepo = permitRepo;
        this.pipeRepo = pipeRepo;
        this.roadRepo = roadRepo;
        this.windowRepo = windowRepo;
        this.candidateRepo = candidateRepo;
        this.decisionRepo = decisionRepo;
        this.engine = engine;
    }

    // ---------------- 许可接收与变更 ----------------

    @Transactional
    public ConstructionPermit createPermit(ConstructionDtos.CreatePermitRequest req) {
        if (permitRepo.findByPermitNo(req.permitNo()).isPresent()) {
            throw ApiException.conflict("PERMIT_NO_EXISTS", "许可编号已存在: " + req.permitNo(), null);
        }
        if (req.endDate().isBefore(req.startDate())) {
            throw ApiException.badRequest("PERMIT_DATE_INVALID", "结束日期不能早于开始日期");
        }
        validateRoadCodes(req.closedRoadCodes());
        ConstructionPermit permit = new ConstructionPermit();
        permit.setPermitNo(req.permitNo());
        permit.setContractor(req.contractor());
        permit.setTitle(req.title());
        permit.setPolygon(req.polygon().stream().map(ConstructionDtos.PointDto::toPoint).toList());
        permit.setExcavationDepthMeters(req.excavationDepthMeters());
        permit.setStartDate(req.startDate());
        permit.setEndDate(req.endDate());
        permit.setClosedRoadCodes(req.closedRoadCodes() == null ? List.of() : req.closedRoadCodes());
        permit.setGeometryVersion(1);
        permit.setStatus("REVIEWING");
        permit.setLastChangeReason("许可送达水务调度，初始录入");
        permitRepo.save(permit);
        refreshCandidates(permit.getPermitNo());
        return permit;
    }

    @Transactional
    public ConstructionPermit changePermit(String permitNo, ConstructionDtos.PermitChangeRequest req) {
        ConstructionPermit permit = requirePermit(permitNo);
        boolean geometryChanged = false;
        if (req.polygon() != null && !req.polygon().isEmpty()) {
            List<GeoPoint> newPolygon = req.polygon().stream()
                    .map(ConstructionDtos.PointDto::toPoint).toList();
            if (!samePoints(newPolygon, permit.getPolygon())) {
                permit.setPolygon(newPolygon);
                geometryChanged = true;
            }
        }
        if (req.excavationDepthMeters() != null
                && !req.excavationDepthMeters().equals(permit.getExcavationDepthMeters())) {
            permit.setExcavationDepthMeters(req.excavationDepthMeters());
            geometryChanged = true;
        }
        if (req.startDate() != null) {
            permit.setStartDate(req.startDate());
            geometryChanged = true;
        }
        if (req.endDate() != null) {
            permit.setEndDate(req.endDate());
            geometryChanged = true;
        }
        if (req.closedRoadCodes() != null) {
            validateRoadCodes(req.closedRoadCodes());
            if (!new ArrayList<>(req.closedRoadCodes()).equals(permit.getClosedRoadCodes())) {
                permit.setClosedRoadCodes(req.closedRoadCodes());
                geometryChanged = true;
            }
        }
        if (permit.getEndDate().isBefore(permit.getStartDate())) {
            throw ApiException.badRequest("PERMIT_DATE_INVALID", "结束日期不能早于开始日期");
        }
        if (!geometryChanged) {
            throw ApiException.badRequest("PERMIT_NO_CHANGE", "提交内容与当前许可一致，没有产生新版本");
        }
        permit.setGeometryVersion(permit.getGeometryVersion() + 1);
        permit.setStatus("REVIEWING");
        permit.setLastChangeReason(req.reason());
        permitRepo.save(permit);

        markDecisionsPending(permitNo, null, null, null, REASON_PERMIT_CHANGED,
                "许可施工范围/深度/日期/封路变化（v" + permit.getGeometryVersion()
                        + "）：" + req.reason());
        refreshCandidates(permitNo);
        return permit;
    }

    // ---------------- 管网测绘升版 ----------------

    @Transactional
    public PipeProtectionProfile reviseSurvey(String pipeCode, ConstructionDtos.SurveyRevisionRequest req) {
        PipeProtectionProfile pipe = pipeRepo.findByCode(pipeCode)
                .orElseThrow(() -> ApiException.notFound("PIPE_NOT_FOUND", "管段不存在: " + pipeCode));
        int oldVersion = pipe.getSurveyVersion();
        pipe.setCenterline(req.polygon().stream().map(ConstructionDtos.PointDto::toPoint).toList());
        pipe.setSurveyErrorMeters(req.surveyErrorMeters());
        pipe.setProtectionDistanceMeters(req.protectionDistanceMeters());
        pipe.setBurialDepthMeters(req.burialDepthMeters());
        if (req.importantUser() != null) {
            pipe.setImportantUser(req.importantUser());
        }
        if (req.importantUserNote() != null) {
            pipe.setImportantUserNote(req.importantUserNote());
        }
        pipe.setSurveyVersion(oldVersion + 1);
        pipe.setUpdatedReason(req.reason());
        pipe.setUpdatedAt(OffsetDateTime.now());
        pipeRepo.save(pipe);

        // 测绘升版可能让原本 SAFE 的管段进入误差带，也可能让冲突消失：
        // 所有许可的物化关系统一重算；待复核只标记存在该管段旧结论的许可，无旧结论者直接得到新候选。
        for (ConstructionPermit permit : permitRepo.findAll()) {
            boolean hadDecision = decisionRepo.findByPermitNoOrderByIdDesc(permit.getPermitNo()).stream()
                    .anyMatch(d -> "ACTIVE".equals(d.getReviewState())
                            && ConflictEngine.TYPE_PROTECTION.equals(d.getImpactType())
                            && pipeCode.equals(d.getTargetCode()));
            if (hadDecision) {
                markDecisionsPending(permit.getPermitNo(), ConflictEngine.TYPE_PROTECTION, pipeCode,
                        null, REASON_SURVEY_REVISED,
                        String.format("管段 %s 测绘升版 v%d→v%d：%s", pipeCode, oldVersion,
                                pipe.getSurveyVersion(), req.reason()));
            }
            refreshCandidates(permit.getPermitNo());
        }
        return pipe;
    }

    // ---------------- 抢修窗口 ----------------

    @Transactional
    public RepairWindow createWindow(ConstructionDtos.WindowCreateRequest req) {
        if (windowRepo.findByCode(req.code()).isPresent()) {
            throw ApiException.conflict("WINDOW_EXISTS", "抢修窗口编号已存在: " + req.code(), null);
        }
        validateRoadCodes(splitCsv(req.plannedApproachRoadCodes()));
        RepairWindow window = new RepairWindow(req.code(), req.title(), req.crewCode(),
                req.windowStart(), req.windowEnd(), req.plannedApproachRoadCodes(),
                "SCHEDULED", 1, req.note());
        windowRepo.save(window);
        // 新窗口可能与既有许可封路形成新的 ACCESS 候选，全部许可重算一次物化关系
        for (ConstructionPermit permit : permitRepo.findAll()) {
            refreshCandidates(permit.getPermitNo());
        }
        return window;
    }

    @Transactional
    public RepairWindow changeWindow(String windowCode, ConstructionDtos.WindowChangeRequest req) {
        RepairWindow window = windowRepo.findByCode(windowCode)
                .orElseThrow(() -> ApiException.notFound("WINDOW_NOT_FOUND", "抢修窗口不存在: " + windowCode));
        boolean changed = false;
        if (req.windowStart() != null) {
            window.setWindowStart(req.windowStart());
            changed = true;
        }
        if (req.windowEnd() != null) {
            window.setWindowEnd(req.windowEnd());
            changed = true;
        }
        if (req.plannedApproachRoadCodes() != null) {
            validateRoadCodes(splitCsv(req.plannedApproachRoadCodes()));
            window.setPlannedApproachRoadCodes(req.plannedApproachRoadCodes());
            changed = true;
        }
        if (req.status() != null) {
            window.setStatus(req.status());
            changed = true;
        }
        if (!changed) {
            throw ApiException.badRequest("WINDOW_NO_CHANGE", "提交内容与当前窗口一致");
        }
        if (!window.getWindowEnd().isAfter(window.getWindowStart())) {
            throw ApiException.badRequest("WINDOW_DATE_INVALID", "窗口结束时间必须晚于开始时间");
        }
        window.setRevision(window.getRevision() + 1);
        window.setNote((window.getNote() == null ? "" : window.getNote() + " | ") + req.reason());
        windowRepo.save(window);

        // 窗口改期/改道可能产生新的可达性影响或使旧影响消失：所有许可统一重算物化关系，
        // 待复核只标记引用了该窗口且仍有 ACTIVE 旧结论的许可。
        for (ConstructionPermit permit : permitRepo.findAll()) {
            boolean hadDecision = decisionRepo.findByPermitNoOrderByIdDesc(permit.getPermitNo()).stream()
                    .anyMatch(d -> "ACTIVE".equals(d.getReviewState())
                            && ConflictEngine.TYPE_ACCESS.equals(d.getImpactType())
                            && windowCode.equals(d.getRepairWindowCode()));
            if (hadDecision) {
                markDecisionsPending(permit.getPermitNo(), ConflictEngine.TYPE_ACCESS, null,
                        windowCode, REASON_WINDOW_CHANGED,
                        String.format("抢修窗口 %s 调整（v%d）：%s", windowCode, window.getRevision(),
                                req.reason()));
            }
            refreshCandidates(permit.getPermitNo());
        }
        return window;
    }

    // ---------------- 路线 A：即时计算 ----------------

    @Transactional(readOnly = true)
    public Map<String, Object> realtimeReview(String permitNo) {
        ConstructionPermit permit = requirePermit(permitNo);
        List<ConflictEngine.ProtectionFinding> protection =
                engine.evaluateProtection(permit, pipeRepo.findAllByOrderByCodeAsc());
        List<ConflictEngine.AccessFinding> access =
                engine.evaluateAccess(permit, roadRepo.findAllByOrderByCodeAsc(),
                        windowRepo.findAllByOrderByWindowStartAsc());
        Map<String, Object> out = baseReview(permit, "REALTIME");
        out.put("protectionFindings", protection);
        out.put("accessFindings", access);
        out.put("actionableProtectionCount", engine.actionableProtection(protection).size());
        out.put("accessImpactCount", access.size());
        out.put("computedAt", OffsetDateTime.now());
        out.put("decisions", decisionRepo.findByPermitNoOrderByIdDesc(permitNo));
        out.putAll(approvalGate(permit, protection, access));
        return out;
    }

    // ---------------- 路线 B：物化候选关系 ----------------

    @Transactional
    public List<CandidateConflict> refreshCandidates(String permitNo) {
        ConstructionPermit permit = requirePermit(permitNo);
        OffsetDateTime now = OffsetDateTime.now();
        for (CandidateConflict c : candidateRepo.findByPermitNoOrderByIdAsc(permitNo)) {
            if ("ACTIVE".equals(c.getStatus())) {
                c.setStatus("SUPERSEDED");
                c.setSupersededReason("重新计算候选冲突（许可 v" + permit.getGeometryVersion() + "）");
                c.setSupersededAt(now);
                candidateRepo.save(c);
            }
        }
        List<CandidateConflict> fresh = new ArrayList<>();
        for (ConflictEngine.ProtectionFinding f : engine.actionableProtection(
                engine.evaluateProtection(permit, pipeRepo.findAllByOrderByCodeAsc()))) {
            CandidateConflict c = new CandidateConflict();
            c.setPermitNo(permitNo);
            c.setImpactType(ConflictEngine.TYPE_PROTECTION);
            c.setTargetCode(f.pipeCode());
            c.setPermitGeometryVersion(permit.getGeometryVersion());
            c.setSideVersion(f.surveyVersion());
            c.setStatus("ACTIVE");
            c.setSeverity(f.severity());
            c.setEvidenceSummary(f.rationale() + " | " + String.join("；", f.evidence()));
            c.setComputedAt(now);
            fresh.add(candidateRepo.save(c));
        }
        for (ConflictEngine.AccessFinding f : engine.evaluateAccess(permit,
                roadRepo.findAllByOrderByCodeAsc(), windowRepo.findAllByOrderByWindowStartAsc())) {
            RepairWindow window = windowRepo.findByCode(f.repairWindowCode()).orElseThrow();
            CandidateConflict c = new CandidateConflict();
            c.setPermitNo(permitNo);
            c.setImpactType(ConflictEngine.TYPE_ACCESS);
            c.setTargetCode(f.roadCode());
            c.setRepairWindowCode(f.repairWindowCode());
            c.setPermitGeometryVersion(permit.getGeometryVersion());
            c.setSideVersion(window.getRevision());
            c.setStatus("ACTIVE");
            c.setSeverity(f.allPlannedRoadsBlocked() ? "ACCESS_BLOCKED" : "ACCESS_REROUTE");
            c.setEvidenceSummary(f.advisory() + " | " + String.join("；", f.evidence()));
            c.setComputedAt(now);
            fresh.add(candidateRepo.save(c));
        }
        // 已放行许可因管网/窗口外部变化出现新冲突时，批准不再有效，回到审查态
        if ("APPROVED".equals(permit.getStatus()) && !fresh.isEmpty()) {
            permit.setStatus("REVIEWING");
            permit.setLastChangeReason("外部管网/抢修窗口变化产生新的候选冲突，原批准结论待复核");
            permitRepo.save(permit);
        }
        return fresh;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> materializedReview(String permitNo) {
        ConstructionPermit permit = requirePermit(permitNo);
        List<CandidateConflict> active =
                candidateRepo.findByPermitNoAndStatusOrderByIdAsc(permitNo, "ACTIVE");
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean anyStale = false;
        for (CandidateConflict c : active) {
            boolean versionConsistent = c.getPermitGeometryVersion().equals(permit.getGeometryVersion())
                    && sideVersionConsistent(c);
            anyStale |= !versionConsistent;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("impactType", c.getImpactType());
            row.put("targetCode", c.getTargetCode());
            row.put("repairWindowCode", c.getRepairWindowCode());
            row.put("severity", c.getSeverity());
            row.put("permitGeometryVersion", c.getPermitGeometryVersion());
            row.put("sideVersion", c.getSideVersion());
            row.put("currentPermitGeometryVersion", permit.getGeometryVersion());
            row.put("versionConsistent", versionConsistent);
            row.put("evidenceSummary", c.getEvidenceSummary());
            row.put("computedAt", c.getComputedAt());
            rows.add(row);
        }
        Map<String, Object> out = baseReview(permit, "MATERIALIZED");
        out.put("candidates", rows);
        out.put("stale", anyStale);
        out.put("activeCount", active.size());
        out.put("supersededCount", candidateRepo.findByPermitNoOrderByIdAsc(permitNo).stream()
                .filter(c -> "SUPERSEDED".equals(c.getStatus())).count());
        out.put("decisions", decisionRepo.findByPermitNoOrderByIdDesc(permitNo));
        out.putAll(approvalGateFromState(permit));
        return out;
    }

    /** 两条路线对同一输入的结论一致性核对：类型+对象+严重度集合必须完全相同。 */
    @Transactional(readOnly = true)
    public Map<String, Object> compareRoutes(String permitNo) {
        ConstructionPermit permit = requirePermit(permitNo);
        List<ConflictEngine.ProtectionFinding> protection =
                engine.evaluateProtection(permit, pipeRepo.findAllByOrderByCodeAsc());
        List<ConflictEngine.AccessFinding> access =
                engine.evaluateAccess(permit, roadRepo.findAllByOrderByCodeAsc(),
                        windowRepo.findAllByOrderByWindowStartAsc());
        Map<String, String> realtimeKeys = new LinkedHashMap<>();
        for (ConflictEngine.ProtectionFinding f : engine.actionableProtection(protection)) {
            realtimeKeys.put(ConflictEngine.TYPE_PROTECTION + ":" + f.pipeCode(), f.severity());
        }
        for (ConflictEngine.AccessFinding f : access) {
            realtimeKeys.put(ConflictEngine.TYPE_ACCESS + ":" + f.roadCode() + ":" + f.repairWindowCode(),
                    f.allPlannedRoadsBlocked() ? "ACCESS_BLOCKED" : "ACCESS_REROUTE");
        }
        Map<String, String> materializedKeys = new LinkedHashMap<>();
        for (CandidateConflict c : candidateRepo.findByPermitNoAndStatusOrderByIdAsc(permitNo, "ACTIVE")) {
            String key = ConflictEngine.TYPE_ACCESS.equals(c.getImpactType())
                    ? c.getImpactType() + ":" + c.getTargetCode() + ":" + c.getRepairWindowCode()
                    : c.getImpactType() + ":" + c.getTargetCode();
            materializedKeys.put(key, c.getSeverity());
        }
        boolean consistent = realtimeKeys.equals(materializedKeys);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("permitNo", permitNo);
        out.put("permitGeometryVersion", permit.getGeometryVersion());
        out.put("consistent", consistent);
        out.put("realtimeRoute", realtimeKeys);
        out.put("materializedRoute", materializedKeys);
        out.put("onlyInRealtime", minusKeys(realtimeKeys, materializedKeys));
        out.put("onlyInMaterialized", minusKeys(materializedKeys, realtimeKeys));
        out.put("severityMismatch", realtimeKeys.entrySet().stream()
                .filter(e -> materializedKeys.containsKey(e.getKey())
                        && !materializedKeys.get(e.getKey()).equals(e.getValue()))
                .map(Map.Entry::getKey).toList());
        return out;
    }

    // ---------------- 逐项审批 ----------------

    @Transactional
    public PermitReviewDecision decide(String permitNo, ConstructionDtos.DecisionRequest req,
                                       String operator) {
        ConstructionPermit permit = requirePermit(permitNo);
        String impactType = req.impactType();
        if (!List.of(ConflictEngine.TYPE_PROTECTION, ConflictEngine.TYPE_ACCESS).contains(impactType)) {
            throw ApiException.badRequest("IMPACT_TYPE_INVALID", "impactType 必须是 PROTECTION 或 ACCESS");
        }
        String action = req.action();
        if (!List.of("FIELD_SURVEY_REQUIRED", "PROTECTION_CONDITION", "RETURNED").contains(action)) {
            throw ApiException.badRequest("ACTION_INVALID",
                    "action 必须是 FIELD_SURVEY_REQUIRED / PROTECTION_CONDITION / RETURNED");
        }
        if (ConflictEngine.TYPE_ACCESS.equals(impactType) && "FIELD_SURVEY_REQUIRED".equals(action)) {
            throw ApiException.unprocessable("ACTION_NOT_APPLICABLE",
                    "道路可达性影响由值班调度保留替代进场路径，不适用现场探测动作", null);
        }

        String severity;
        int sideVersion;
        String evidence;
        if (ConflictEngine.TYPE_PROTECTION.equals(impactType)) {
            ConflictEngine.ProtectionFinding finding = engine.evaluateProtection(
                            permit, pipeRepo.findAllByOrderByCodeAsc()).stream()
                    .filter(f -> f.pipeCode().equals(req.targetCode())).findFirst()
                    .orElseThrow(() -> ApiException.notFound("PIPE_NOT_FOUND",
                            "管段不存在: " + req.targetCode()));
            if (ConflictEngine.SEVERITY_SAFE.equals(finding.severity())) {
                throw ApiException.unprocessable("NO_CONFLICT_TO_DECIDE",
                        "该管段当前计算结论为安全，无需逐项审批", null);
            }
            severity = finding.severity();
            sideVersion = finding.surveyVersion();
            evidence = finding.rationale();
        } else {
            if (req.repairWindowCode() == null || req.repairWindowCode().isBlank()) {
                throw ApiException.badRequest("WINDOW_REQUIRED",
                        "ACCESS 类审批必须指定 repairWindowCode");
            }
            ConflictEngine.AccessFinding finding = engine.evaluateAccess(permit,
                            roadRepo.findAllByOrderByCodeAsc(),
                            windowRepo.findAllByOrderByWindowStartAsc()).stream()
                    .filter(f -> f.roadCode().equals(req.targetCode())
                            && f.repairWindowCode().equals(req.repairWindowCode()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.unprocessable("NO_ACCESS_IMPACT",
                            "当前几何下该道路/窗口不存在可达性影响，无法审批", null));
            RepairWindow window = windowRepo.findByCode(req.repairWindowCode()).orElseThrow();
            severity = finding.allPlannedRoadsBlocked() ? "ACCESS_BLOCKED" : "ACCESS_REROUTE";
            sideVersion = window.getRevision();
            evidence = finding.advisory();
        }

        boolean alreadyActive = decisionRepo.findByPermitNoOrderByIdDesc(permitNo).stream()
                .anyMatch(d -> d.getReviewState().equals("ACTIVE")
                        && d.getImpactType().equals(impactType)
                        && d.getTargetCode().equals(req.targetCode())
                        && java.util.Objects.equals(d.getRepairWindowCode(), req.repairWindowCode()));
        if (alreadyActive) {
            throw ApiException.conflict("DECISION_ALREADY_ACTIVE",
                    "该冲突项已有生效审批结论，请先等待复核或调整原结论", null);
        }

        PermitReviewDecision decision = new PermitReviewDecision();
        decision.setPermitNo(permitNo);
        decision.setImpactType(impactType);
        decision.setTargetCode(req.targetCode());
        decision.setRepairWindowCode(req.repairWindowCode());
        decision.setAction(action);
        decision.setSeverityAtDecision(severity);
        decision.setPermitGeometryVersion(permit.getGeometryVersion());
        decision.setSideVersionAtDecision(sideVersion);
        decision.setReviewState("ACTIVE");
        decision.setEvidenceSummary(evidence);
        decision.setConditionNote(req.conditionNote());
        decision.setDecidedBy(operator);
        decision.setDecidedAt(OffsetDateTime.now());
        decisionRepo.save(decision);

        if ("RETURNED".equals(action)) {
            permit.setStatus("RETURNED");
            permitRepo.save(permit);
        }
        return decision;
    }

    /**
     * 复核已置 PENDING_REVIEW 的旧结论：
     * <ul>
     *   <li>CONFIRM：当前几何重算后影响仍成立，关闭旧结论并按两侧新版本生成新的 ACTIVE 结论（沿用原审批动作）；</li>
     *   <li>DISMISS：当前几何下影响已消失（如许可缩界退出保护区），关闭旧结论，不再阻塞放行。</li>
     * </ul>
     */
    @Transactional
    public PermitReviewDecision reviewDecision(String permitNo, Long decisionId,
                                               String verdict, String note, String operator) {
        requirePermit(permitNo);
        PermitReviewDecision old = decisionRepo.findById(decisionId)
                .orElseThrow(() -> ApiException.notFound("DECISION_NOT_FOUND",
                        "审批结论不存在: " + decisionId));
        if (!permitNo.equals(old.getPermitNo())) {
            throw ApiException.badRequest("DECISION_PERMIT_MISMATCH", "该结论不属于许可 " + permitNo);
        }
        if (!"PENDING_REVIEW".equals(old.getReviewState())) {
            throw ApiException.conflict("DECISION_NOT_PENDING",
                    "只有待复核结论可以执行复核操作，当前状态 " + old.getReviewState(), null);
        }
        ConstructionPermit permit = requirePermit(permitNo);

        if ("DISMISS".equals(verdict)) {
            old.setReviewState("CLOSED");
            old.setPendingDetail((old.getPendingDetail() == null ? "" : old.getPendingDetail()
                    + " | ") + "复核关闭：" + note);
            return decisionRepo.save(old);
        }
        if (!"CONFIRM".equals(verdict)) {
            throw ApiException.badRequest("VERDICT_INVALID", "verdict 必须是 CONFIRM 或 DISMISS");
        }

        String severity;
        int sideVersion;
        String evidence;
        if (ConflictEngine.TYPE_PROTECTION.equals(old.getImpactType())) {
            ConflictEngine.ProtectionFinding finding = engine.evaluateProtection(
                            permit, pipeRepo.findAllByOrderByCodeAsc()).stream()
                    .filter(f -> f.pipeCode().equals(old.getTargetCode())).findFirst()
                    .orElseThrow(() -> ApiException.notFound("PIPE_NOT_FOUND",
                            "管段不存在: " + old.getTargetCode()));
            if (ConflictEngine.SEVERITY_SAFE.equals(finding.severity())) {
                throw ApiException.unprocessable("IMPACT_GONE_SHOULD_DISMISS",
                        "当前几何下该管段已无保护区影响，应使用 DISMISS 关闭旧结论", null);
            }
            severity = finding.severity();
            sideVersion = finding.surveyVersion();
            evidence = finding.rationale();
        } else {
            ConflictEngine.AccessFinding finding = engine.evaluateAccess(permit,
                            roadRepo.findAllByOrderByCodeAsc(),
                            windowRepo.findAllByOrderByWindowStartAsc()).stream()
                    .filter(f -> f.roadCode().equals(old.getTargetCode())
                            && f.repairWindowCode().equals(old.getRepairWindowCode()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.unprocessable("IMPACT_GONE_SHOULD_DISMISS",
                            "当前几何下该道路可达性影响已消失，应使用 DISMISS 关闭旧结论", null));
            RepairWindow window = windowRepo.findByCode(old.getRepairWindowCode()).orElseThrow();
            severity = finding.allPlannedRoadsBlocked() ? "ACCESS_BLOCKED" : "ACCESS_REROUTE";
            sideVersion = window.getRevision();
            evidence = finding.advisory();
        }

        old.setReviewState("CLOSED");
        old.setPendingDetail((old.getPendingDetail() == null ? "" : old.getPendingDetail()
                + " | ") + "复核后按新版本另立新结论：" + note);
        decisionRepo.save(old);

        PermitReviewDecision next = new PermitReviewDecision();
        next.setPermitNo(permitNo);
        next.setImpactType(old.getImpactType());
        next.setTargetCode(old.getTargetCode());
        next.setRepairWindowCode(old.getRepairWindowCode());
        next.setAction(old.getAction());
        next.setSeverityAtDecision(severity);
        next.setPermitGeometryVersion(permit.getGeometryVersion());
        next.setSideVersionAtDecision(sideVersion);
        next.setReviewState("ACTIVE");
        next.setEvidenceSummary(evidence);
        next.setConditionNote((old.getConditionNote() == null ? "" : old.getConditionNote()
                + " | 复核备注：") + note);
        next.setDecidedBy(operator);
        next.setDecidedAt(OffsetDateTime.now());
        return decisionRepo.save(next);
    }

    @Transactional
    public Map<String, Object> approve(String permitNo) {
        ConstructionPermit permit = requirePermit(permitNo);
        Map<String, Object> gate = approvalGateFromState(permit);
        if (!Boolean.TRUE.equals(gate.get("canApprove"))) {
            throw ApiException.conflict("PERMIT_NOT_APPROVABLE",
                    "许可当前不满足放行条件，不能自动放行", gate);
        }
        permit.setStatus("APPROVED");
        permitRepo.save(permit);
        Map<String, Object> out = new LinkedHashMap<>(gate);
        out.put("permitNo", permitNo);
        out.put("status", permit.getStatus());
        out.put("approvedAt", OffsetDateTime.now());
        return out;
    }

    // ---------------- 汇总与辅助 ----------------

    @Transactional(readOnly = true)
    public List<ConstructionPermit> listPermits() {
        return permitRepo.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public ConstructionPermit requirePermit(String permitNo) {
        return permitRepo.findByPermitNo(permitNo)
                .orElseThrow(() -> ApiException.notFound("PERMIT_NOT_FOUND", "许可不存在: " + permitNo));
    }

    @Transactional(readOnly = true)
    public List<PipeProtectionProfile> listPipes() {
        return pipeRepo.findAllByOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<RoadSegment> listRoads() {
        return roadRepo.findAllByOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<RepairWindow> listWindows() {
        return windowRepo.findAllByOrderByWindowStartAsc();
    }

    @Transactional(readOnly = true)
    public List<PermitReviewDecision> listDecisions(String permitNo) {
        requirePermit(permitNo);
        return decisionRepo.findByPermitNoOrderByIdDesc(permitNo);
    }

    private Map<String, Object> approvalGate(ConstructionPermit permit,
                                             List<ConflictEngine.ProtectionFinding> protection,
                                             List<ConflictEngine.AccessFinding> access) {
        List<String> blockers = new ArrayList<>();
        long actionable = engine.actionableProtection(protection).size();
        List<PermitReviewDecision> decisions =
                decisionRepo.findByPermitNoAndReviewState(permit.getPermitNo(), "ACTIVE");
        long pending = decisionRepo.findByPermitNoOrderByIdDesc(permit.getPermitNo()).stream()
                .filter(d -> "PENDING_REVIEW".equals(d.getReviewState())).count();
        if (pending > 0) {
            blockers.add("存在 " + pending + " 项旧审查结论已标待复核，必须重新审查后方可放行");
        }
        long decidedProtection = decisions.stream()
                .filter(d -> ConflictEngine.TYPE_PROTECTION.equals(d.getImpactType())).count();
        if (decidedProtection < actionable) {
            blockers.add("尚有 " + (actionable - decidedProtection) + " 项保护区冲突未逐项审批");
        }
        long decidedAccess = decisions.stream()
                .filter(d -> ConflictEngine.TYPE_ACCESS.equals(d.getImpactType())).count();
        if (decidedAccess < access.size()) {
            blockers.add("尚有 " + (access.size() - decidedAccess) + " 项道路可达性影响未由值班调度确认替代路径");
        }
        if ("RETURNED".equals(permit.getStatus())) {
            blockers.add("许可已退回修改，需重新提交后再审查");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canApprove", blockers.isEmpty());
        out.put("blockReasons", blockers);
        out.put("pendingReviewCount", pending);
        return out;
    }

    private Map<String, Object> approvalGateFromState(ConstructionPermit permit) {
        List<ConflictEngine.ProtectionFinding> protection =
                engine.evaluateProtection(permit, pipeRepo.findAllByOrderByCodeAsc());
        List<ConflictEngine.AccessFinding> access = engine.evaluateAccess(permit,
                roadRepo.findAllByOrderByCodeAsc(), windowRepo.findAllByOrderByWindowStartAsc());
        return approvalGate(permit, protection, access);
    }

    private void markDecisionsPending(String permitNo, String impactType, String targetCode,
                                      String repairWindowCode, String reason, String detail) {
        for (PermitReviewDecision d : decisionRepo.findByPermitNoOrderByIdDesc(permitNo)) {
            if (!"ACTIVE".equals(d.getReviewState())) {
                continue;
            }
            if (impactType != null && !impactType.equals(d.getImpactType())) {
                continue;
            }
            if (targetCode != null && !targetCode.equals(d.getTargetCode())) {
                continue;
            }
            if (repairWindowCode != null
                    && !repairWindowCode.equals(d.getRepairWindowCode())) {
                continue;
            }
            d.setReviewState("PENDING_REVIEW");
            d.setPendingReason(reason);
            d.setPendingDetail(detail);
            d.setPendingAt(OffsetDateTime.now());
            decisionRepo.save(d);
        }
    }

    private boolean sideVersionConsistent(CandidateConflict c) {
        if (ConflictEngine.TYPE_PROTECTION.equals(c.getImpactType())) {
            return pipeRepo.findByCode(c.getTargetCode())
                    .map(p -> p.getSurveyVersion().equals(c.getSideVersion())).orElse(false);
        }
        return windowRepo.findByCode(c.getRepairWindowCode())
                .map(w -> w.getRevision().equals(c.getSideVersion())).orElse(false);
    }

    private void validateRoadCodes(List<String> codes) {
        if (codes == null) {
            return;
        }
        for (String code : codes) {
            if (roadRepo.findByCode(code).isEmpty()) {
                throw ApiException.badRequest("ROAD_NOT_FOUND", "道路编号不存在: " + code);
            }
        }
    }

    private static boolean samePoints(List<GeoPoint> a, List<GeoPoint> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (Math.abs(a.get(i).x() - b.get(i).x()) > 1e-9
                    || Math.abs(a.get(i).y() - b.get(i).y()) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitCsv(String csv) {
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

    private static Map<String, Object> baseReview(ConstructionPermit permit, String route) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("route", route);
        out.put("permit", permit);
        return out;
    }

    private static Map<String, String> minusKeys(Map<String, String> a, Map<String, String> b) {
        Map<String, String> out = new LinkedHashMap<>();
        a.forEach((k, v) -> {
            if (!b.containsKey(k)) {
                out.put(k, v);
            }
        });
        return out;
    }
}
