package com.waterrepair.construction;

import com.waterrepair.auth.AuthService;
import com.waterrepair.auth.Roles;
import com.waterrepair.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 道路施工许可审查接口。
 * 角色分流：PROTECTION（保护区相交）由管线工程师 ENGINEER 评估；
 * ACCESS（道路可达性）由值班调度 DISPATCHER 保留替代进场路径；两类影响分别建单，不合并为一个布尔冲突。
 */
@Tag(name = "07-道路施工许可审查")
@RestController
@RequestMapping("/api/construction")
public class ConstructionController {

    private final ConstructionReviewService service;

    public ConstructionController(ConstructionReviewService service) {
        this.service = service;
    }

    // ---------------- 许可 ----------------

    @Operation(summary = "接收道路施工许可（编号、施工多边形、开挖深度、日期、封路段、承包商）")
    @Roles({"DISPATCHER", "ENGINEER"})
    @PostMapping("/permits")
    public ConstructionPermit create(@Valid @RequestBody ConstructionDtos.CreatePermitRequest req) {
        return service.createPermit(req);
    }

    @Operation(summary = "全部施工许可（含几何版本与审查状态）")
    @GetMapping("/permits")
    public List<ConstructionPermit> permits() {
        return service.listPermits();
    }

    @Operation(summary = "单个施工许可详情")
    @GetMapping("/permits/{permitNo}")
    public ConstructionPermit permit(@PathVariable String permitNo) {
        return service.requirePermit(permitNo);
    }

    @Operation(summary = "施工范围变化（缩界/扩界/深度/日期/封路）：几何升版，旧审查结论标记待复核")
    @Roles({"DISPATCHER", "ENGINEER"})
    @PostMapping("/permits/{permitNo}/changes")
    public ConstructionPermit change(@PathVariable String permitNo,
                                     @Valid @RequestBody ConstructionDtos.PermitChangeRequest req) {
        return service.changePermit(permitNo, req);
    }

    // ---------------- 两条计算路线 ----------------

    @Operation(summary = "路线A：按当前几何即时计算保护区相交与道路可达性（每次打开都重算，结果最新）")
    @GetMapping("/permits/{permitNo}/review/realtime")
    public Map<String, Object> realtimeReview(@PathVariable String permitNo) {
        return service.realtimeReview(permitNo);
    }

    @Operation(summary = "路线B：读取物化候选冲突关系（检索快；关系携带两侧版本，失效关系已清除）")
    @GetMapping("/permits/{permitNo}/review/materialized")
    public Map<String, Object> materializedReview(@PathVariable String permitNo) {
        return service.materializedReview(permitNo);
    }

    @Operation(summary = "手动重建某许可的物化候选关系（正常在许可/管网/窗口变化时已自动维护）")
    @Roles({"DISPATCHER", "ENGINEER"})
    @PostMapping("/permits/{permitNo}/refresh-candidates")
    public List<CandidateConflict> refresh(@PathVariable String permitNo) {
        return service.refreshCandidates(permitNo);
    }

    @Operation(summary = "两条计算路线同输入结论一致性核对（类型+对象+严重度集合须完全相同）")
    @GetMapping("/permits/{permitNo}/route-comparison")
    public Map<String, Object> compareRoutes(@PathVariable String permitNo) {
        return service.compareRoutes(permitNo);
    }

    // ---------------- 逐项审批与放行 ----------------

    @Operation(summary = "逐项审批：要求现场探测 / 附加保护条件 / 退回修改（PROTECTION 工程师，ACCESS 调度）")
    @Roles({"DISPATCHER", "ENGINEER"})
    @PostMapping("/permits/{permitNo}/decisions")
    public PermitReviewDecision decide(@PathVariable String permitNo,
                                       @Valid @RequestBody ConstructionDtos.DecisionRequest req,
                                       HttpServletRequest http) {
        String role = (String) http.getAttribute(AuthService.ATTR_ROLE);
        if (ConflictEngine.TYPE_PROTECTION.equals(req.impactType()) && !"ENGINEER".equals(role)) {
            throw ApiException.forbidden("ENGINEER_REQUIRED",
                    "保护区相交影响必须由管线工程师（ENGINEER）评估");
        }
        if (ConflictEngine.TYPE_ACCESS.equals(req.impactType()) && !"DISPATCHER".equals(role)) {
            throw ApiException.forbidden("DISPATCHER_REQUIRED",
                    "道路可达性影响必须由值班调度（DISPATCHER）确认替代进场路径");
        }
        return service.decide(permitNo, req, (String) http.getAttribute(AuthService.ATTR_USERNAME));
    }

    @Operation(summary = "许可下全部逐项审批结论（含待复核原因与版本快照）")
    @GetMapping("/permits/{permitNo}/decisions")
    public List<PermitReviewDecision> decisions(@PathVariable String permitNo) {
        return service.listDecisions(permitNo);
    }

    @Operation(summary = "复核待复核结论：CONFIRM 按当前几何新版本另立结论，DISMISS 在影响消失时关闭")
    @Roles({"DISPATCHER", "ENGINEER"})
    @PostMapping("/permits/{permitNo}/decisions/{decisionId}/review")
    public PermitReviewDecision reviewDecision(@PathVariable String permitNo,
                                               @PathVariable Long decisionId,
                                               @Valid @RequestBody ConstructionDtos.ReviewDecisionRequest req,
                                               HttpServletRequest http) {
        PermitReviewDecision target = service.listDecisions(permitNo).stream()
                .filter(d -> d.getId().equals(decisionId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("DECISION_NOT_FOUND",
                        "审批结论不存在: " + decisionId));
        String role = (String) http.getAttribute(AuthService.ATTR_ROLE);
        if (ConflictEngine.TYPE_PROTECTION.equals(target.getImpactType()) && !"ENGINEER".equals(role)) {
            throw ApiException.forbidden("ENGINEER_REQUIRED", "保护区结论的复核必须由管线工程师执行");
        }
        if (ConflictEngine.TYPE_ACCESS.equals(target.getImpactType()) && !"DISPATCHER".equals(role)) {
            throw ApiException.forbidden("DISPATCHER_REQUIRED", "可达性结论的复核必须由值班调度执行");
        }
        return service.reviewDecision(permitNo, decisionId, req.verdict(),
                req.note() == null ? "" : req.note(),
                (String) http.getAttribute(AuthService.ATTR_USERNAME));
    }

    @Operation(summary = "最终放行：存在待复核结论或未审批冲突项时拒绝自动放行")
    @Roles({"DISPATCHER"})
    @PostMapping("/permits/{permitNo}/approve")
    public Map<String, Object> approve(@PathVariable String permitNo) {
        return service.approve(permitNo);
    }

    // ---------------- 管网测绘升版 ----------------

    @Operation(summary = "管段防护台账（中心线、误差带、保护距离、埋深、重要用户）")
    @GetMapping("/pipes")
    public List<PipeProtectionProfile> pipes() {
        return service.listPipes();
    }

    @Operation(summary = "管网测绘升版：提交新中心线/误差带，版本+1，相关许可旧结论标记待复核")
    @Roles({"ENGINEER"})
    @PostMapping("/pipes/{pipeCode}/survey-revision")
    public PipeProtectionProfile reviseSurvey(@PathVariable String pipeCode,
                                              @Valid @RequestBody ConstructionDtos.SurveyRevisionRequest req) {
        return service.reviseSurvey(pipeCode, req);
    }

    // ---------------- 道路与抢修窗口 ----------------

    @Operation(summary = "道路中心线台账")
    @GetMapping("/roads")
    public List<RoadSegment> roads() {
        return service.listRoads();
    }

    @Operation(summary = "已排定抢修窗口（含计划进场道路与版本）")
    @GetMapping("/windows")
    public List<RepairWindow> windows() {
        return service.listWindows();
    }

    @Operation(summary = "登记抢修窗口")
    @Roles({"DISPATCHER"})
    @PostMapping("/windows")
    public RepairWindow createWindow(@Valid @RequestBody ConstructionDtos.WindowCreateRequest req) {
        return service.createWindow(req);
    }

    @Operation(summary = "紧急抢修插入/窗口调整：版本+1，受影响许可的 ACCESS 旧结论标记待复核")
    @Roles({"DISPATCHER"})
    @PostMapping("/windows/{windowCode}/changes")
    public RepairWindow changeWindow(@PathVariable String windowCode,
                                     @Valid @RequestBody ConstructionDtos.WindowChangeRequest req) {
        return service.changeWindow(windowCode, req);
    }
}
