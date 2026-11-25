package com.waterrepair.construction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.waterrepair.construction.geom.GeoPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 道路施工许可审查模块的请求 DTO。 */
public final class ConstructionDtos {

    private ConstructionDtos() {
    }

    /** 顶点入参统一为 [x, y] 数组（局部工程坐标，米）。 */
    public record PointDto(double x, double y) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PointDto fromArray(double[] xy) {
            if (xy == null || xy.length < 2) {
                throw new IllegalArgumentException("坐标点必须是包含 x、y 两个数值的数组");
            }
            return new PointDto(xy[0], xy[1]);
        }

        public GeoPoint toPoint() {
            return new GeoPoint(x, y);
        }
    }

    public record CreatePermitRequest(
            @NotBlank String permitNo,
            @NotBlank String contractor,
            @NotBlank String title,
            @NotEmpty @Valid List<PointDto> polygon,
            @NotNull @Positive Double excavationDepthMeters,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            List<String> closedRoadCodes) {
    }

    /** 许可施工范围变化（缩界/扩界/深度/日期/封路）；几何或封路变化会升版并使旧结论待复核。 */
    public record PermitChangeRequest(
            @Valid List<PointDto> polygon,
            @Positive Double excavationDepthMeters,
            LocalDate startDate,
            LocalDate endDate,
            List<String> closedRoadCodes,
            @NotBlank String reason) {
    }

    /** 逐项审批动作：FIELD_SURVEY_REQUIRED / PROTECTION_CONDITION / RETURNED。 */
    public record DecisionRequest(
            @NotBlank String impactType,
            @NotBlank String targetCode,
            String repairWindowCode,
            @NotBlank String action,
            String conditionNote) {
    }

    /** 管网测绘升版：提交新的中心线与误差带，surveyVersion 自动 +1。 */
    public record SurveyRevisionRequest(
            @NotEmpty @Valid List<PointDto> polygon,
            @NotNull @Positive Double surveyErrorMeters,
            @NotNull @Positive Double protectionDistanceMeters,
            @NotNull @Positive Double burialDepthMeters,
            Boolean importantUser,
            String importantUserNote,
            @NotBlank String reason) {
    }

    public record WindowCreateRequest(
            @NotBlank String code,
            @NotBlank String title,
            @NotBlank String crewCode,
            @NotNull OffsetDateTime windowStart,
            @NotNull OffsetDateTime windowEnd,
            @NotBlank String plannedApproachRoadCodes,
            String note) {
    }

    /** 紧急抢修插入/窗口调整：改期、改进场道路或状态。 */
    public record WindowChangeRequest(
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            String plannedApproachRoadCodes,
            String status,
            @NotBlank String reason) {
    }

    /** 待复核结论的复核处理：CONFIRM（影响仍成立，按新版本另立新结论）/ DISMISS（影响已消失，关闭）。 */
    public record ReviewDecisionRequest(
            @NotBlank String verdict,
            String note) {
    }
}
