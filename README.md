# 供水抢修调度服务（water-repair-service）

暴雨后的凌晨，值守人员从原始报修形成事故、核实隔离范围、派出合格班组并确认恢复。
本项目用 **Java 21 / Spring Boot 3.5 / PostgreSQL** 实现了一个真实可调用的后端服务，
覆盖：报修原文与归属判断、事故确认、图算法候选关阀组合与精确地址影响集合、
班组许可/当班窗口/车辆/工具校验、队伍与阀门操作许可的并发互斥、两种稀缺队伍调度路线、
五类现场回执的顺序约束、复压区间+校时容差核验、复压失败重新派工，以及压力复核推翻后的
范围/资源占用/通知全链路更正；并在此之上实现**道路施工许可审查**：
许可多边形/开挖深度/封路/承包商接收，结合管段测绘误差带、保护距离、埋深、重要用户与
抢修窗口，区分“保护区相交（管线工程师评估）”与“道路可达性（值班调度保留替代进场路径）”
两类独立影响，提供即时计算与物化候选两条计算路线，许可缩界/测绘升版/抢修窗口变化后
旧审查结论自动标待复核、禁止自动放行。

> 项目类型：**后端 API 服务**。自带 OpenAPI 3 文档与 Swagger UI，无独立前端页面。

---

## 1. 一分钟启动

### 方式 A：Docker Compose（一键，推荐验收）

```bash
docker compose up --build
```

- 自动启动 PostgreSQL 18 与应用，应用监听 <http://localhost:8080>
- 宿主机 PostgreSQL 端口冲突时：`DB_HOST_PORT=55432 APP_PORT=18080 docker compose up --build`
- 容器内已配置阿里云 Maven 镜像；样例数据在首次启动时自动播种

打开：

- Swagger UI：<http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 健康检查：<http://localhost:8080/api/health>

### 方式 B：本地开发启动（本工作区已验证）

环境：JDK 25（`/opt/jdk-25`，编译目标 21）、Maven 3.9（`/opt/maven`）、
PostgreSQL 18（本机 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`）。

```bash
# 1) 确保 PostgreSQL 可连（容器自带实例）
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev -c 'select 1;'

# 2) 开发模式运行（首次会自动建表+播种）
JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml spring-boot:run

# 或打包后运行
JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml -DskipTests package
/opt/jdk-25/bin/java -jar target/water-repair-service.jar
```

如需使用自己的数据库，用环境变量覆盖：

```bash
DB_URL='jdbc:postgresql://HOST:5432/DBNAME' DB_USER=u DB_PASSWORD=p \
  /opt/jdk-25/bin/java -jar target/water-repair-service.jar
```

---

## 2. 测试账号

| 用户名 | 密码 | 角色 | 用途 |
| --- | --- | --- | --- |
| `dispatcher` | `Disp@12345` | DISPATCHER | 值班调度员，全部调度写操作（受理/确认/派工/复核/关单/reset），负责施工许可的道路可达性影响与最终放行 |
| `engineer` | `Eng@12345` | ENGINEER | 管线工程师，负责施工许可的保护区相交评估、现场探测/保护条件/退回与测绘升版 |
| `crewsole` | `Crew@12345` | CREW（绑定 `C_SOLE`） | 唯一资质装备齐备的持证维修班班长，可提交回执 |
| `scout` | `Scout@12345` | CREW（绑定 `C_SCOUT`） | 勘查一班 |
| `viewer` | `View@12345` | VIEWER | 只读角色，写接口返回 403 |

登录换取 token：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"dispatcher","password":"Disp@12345"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
echo "$TOKEN"
```

之后所有 `/api/**`（除 `/api/auth/login`、`/api/health`）都要带：

```
Authorization: Bearer <token>
```

在 Swagger UI 中点右上角 **Authorize**，粘贴 token 原文（不要带 `Bearer ` 前缀）即可调试。

---

## 3. 自动回放验证（推荐，纯 Python 标准库）

脚本会先登录、调用 `POST /api/admin/reset` 清库重播，然后真实发起并发 HTTP 请求，
逐步断言题目要求的全部场景。可重复执行。

```bash
# 终端 1：启动服务（见上）

# 终端 2：抢修调度主线（114 项断言）
python3 scripts/replay.py

# 道路施工许可审查主线（68 项断言）
python3 scripts/replay_construction.py

# 或一键顺序执行两套回放：
bash scripts/verify.sh
# 远端/自定义地址：
BASE_URL=http://localhost:8080 python3 scripts/replay_construction.py
```

预期结尾：

```
##############################################################################
# 回放断言结果：PASS=114  FAIL=0
# 🎉 全部场景断言通过：同段重拨 / 相邻双支管 / 并发互斥 / 复压重派 / 更正链
ALL_DONE: true
...
# 施工许可回放断言结果：PASS=68  FAIL=0
# 🎉 全部场景断言通过：可能侵入三态 / 两类影响分流 / 三变化待复核 / 两路线一致
ALL_DONE: true
```

`scripts/replay.py` 覆盖并核对：

1. **同段重拨**：R1/R2 形成 I2 后，R3 热线重拨自动作为 `REDIAL` 来源挂入同一事故；
   事故数、派工数均不增加；重复追加来源幂等。
2. **相邻双支管**：相隔 <100m 的幸福路 8 号（A05→S2）与滨河巷 1 号（A06→S3）形成不同事故；
   路口地标与“幸福里 6 栋”同楼双地址都只给 `AMBIGUOUS [S2,S3]`，不自动合并；
   人工确认保留 v1 AUTO / v2 MANUAL 两级判断历史。
3. **精确影响集合**：单关 `V2 = A01..A05`；单关 `V1 = A01..A05 + A10..A13`（S1+S2，9 户）；
   `V3 = A06..A09`。I2 候选组合为 `[V2(5户), V1(9户)]`，I3 只有 `V3`，I1 只有 `V1`。
4. **资格门槛**：许可缺失（勘查班不能接维修单，422）、当班窗口外、
   车辆不可用、工具不可用都有逐项 `checks`。
5. **唯一持证班组两次并发派单**：两线程用 Barrier 同时请求 I2/I3 的维修派单，
   数据库行锁 + 时间窗重叠校验保证**恰好一个 200、一个 409 `CREW_ALREADY_BOOKED`**，
   409 细节给出占用方派工单/事故/窗口；取消后占用全部 `RELEASED` 且原因可查。
6. **两条调度路线**：
   - 路线 A（勘查先行，I3）：先派 `C_SCOUT`，04:25 到场确认 V3；
     跳序 `REPAIR` 被 `RECEIPT_ORDER_CONFLICT` 拒，勘查单提交 `ISOLATION` 被 422 拒；
     随后锁 `C_SOLE` 的 06:00-08:00 窗口被路线 B 的 I2 先占（409），
     最终改锁 09:30-11:30（与 I2 窗口 09:20 结束半开相接，不冲突）成功。
   - 路线 B（捆绑暂留，I2）：`holdOnly=true` 一次锁定
     `CREW C_SOLE + VALVE_PERMIT POP-V2 + VEHICLE VEH-1 + TOOL-*` 共 5 项 HELD 占用，
     派工单 `PROVISIONAL`，`issue` 后才 `DISPATCHED`。
7. **复压失败→重新派工**：I2 第一周期五回执齐全，`PR-S2-FAIL`（0.14MPa < S2 下限 0.22）
   核验 `FAILED`，关单 409 `REPRESSURE_NOT_PASSED`；`redispatch` 后
   旧派工单 5 项占用以 `REDISPATCH_AFTER_REPRESSURE_FAILURE` 释放，
   `cycleNo=2 / attemptNo=2 / parentAssignmentId` 成链；旧单再交回执报 `RECEIPT_WRONG_CYCLE`；
   新周期用 `PR-S2-OK` 通过并关单，新占用以 `INCIDENT_CLOSED` 释放。
8. **回执归属**：I3 的 `ARRIVAL` 永久归属勘查单/`C_SCOUT`，
   其余四条归属维修单/`C_SOLE`；每条回执都带 `assignmentId/assignmentCode/cycleNo`。
9. **压力复核更正链（I1）**：复核 `OVERTURNS` 把边界 S1/V1 改为 S2/V2：
   更正记录保存新旧边界/阀门/地址全集，`removed=[A10..A13]`、`added=[]`；
   占用侧释放 `POP-V1`（原因含 `PRESSURE_REVIEW_CORRECTION`）并新占 `POP-V2`；
   9 条原始通知全部 `SUPERSEDED` 且挂 `supersededByCorrectionId`，
   新发 5 条 `CORRECTION` + 4 条 `CORRECTION_CANCEL`（A10-A13），
   取消通知用 `replacesNotificationId` 链回旧通知——旧通知不删除、不伪装未发生。
10. **校时容差**：`POST /api/pressure/verify-repressure` 可分别判定
    “压力值超出支管区间”和“校时后采样时刻与复压时刻偏差超过容差”。
11. 终态：所有 `resource_occupancy` 均为 `RELEASED`，3 个事故均 `CLOSED`。

---

## 4. 道路施工许可审查（随题新增，Swagger Tag 07）

### 4.1 领域建模与两类不同影响

许可图形按道路边界粗绘、管线中心线带测绘误差，因此**不能用“坐标最近点单阈值”给布尔结论**。
模块（`com.waterrepair.construction`，样例 `samples/construction.json`）把影响拆成两类，
分别建单、分别流转、分别授权：

| 影响类型 | 含义 | 评估角色 | 处置动作 |
| --- | --- | --- | --- |
| `PROTECTION` 保护区相交 | 开挖可能进入管线误差带+保护区，垂向还要比对开挖深度与管顶埋深 | 管线工程师 `ENGINEER` | 要求现场探测 / 附加保护条件 / 退回修改 |
| `ACCESS` 道路可达性 | 封路阻断抢修班组原定进场道路 | 值班调度 `DISPATCHER` | 保留替代进场路径（不适用现场探测） |

保护区判定为**三态**而非布尔（`ConflictEngine`）：

```
d_nom = 施工多边形边界到管线中心线的最近距离（米，局部工程坐标）
e     = 测绘位置误差带半宽；P = 法定保护距离
d_nom = 0            => DEFINITE_INTRUSION  中心线已在开挖界内
0 < d_nom <= e + P   => POSSIBLE_INTRUSION  真实管线受误差影响可能侵入，不能判安全
d_nom > e + P        => SAFE                误差带外缘仍远离施工区
```

题面 **P17**：中心线距开挖边界 **1.6m**、测绘误差 **±0.8m**、保护距离 **2m**，
`1.6 ≤ 0.8+2 = 2.8` → 回放固定断言结论为 `POSSIBLE_INTRUSION`（可能侵入），
且开挖 1.5m 已达到管顶埋深 1.2m，垂向接触风险成立；远离施工区的 P18 判 `SAFE` 不误报。
同一许可封闭建设路，抢修窗口 `RW-001` 原定沿建设路进场 → 生成 `ACCESS_REROUTE`，
提示保留光明路替代路径，与 P17 的保护区结论是两条独立记录，不合并。

### 4.2 两条计算路线（同一引擎，同输入同结论）

- **路线 A 即时计算** `GET /api/construction/permits/{no}/review/realtime`：
  每次打开按当前几何重算，结果最新；回放与审批动作均以此为准。
- **路线 B 物化候选** `GET .../review/materialized`：
  许可/管网/窗口变化时在同一事务内重建 `candidate_conflict`，旧关系置 `SUPERSEDED`
  （失效关系立即清除出候选集），每条 ACTIVE 关系快照**许可几何版本 + 对侧版本
  （管段测绘版本或窗口 revision）**，审批只引用两侧版本与当前一致的关系。
- `GET .../route-comparison` 逐条核对两条路线的“类型+对象+严重度”集合，
  回放每个变化节点都断言 `consistent=true`。

### 4.3 待复核：变化后旧结论不能自动放行

每项审批结论（`PermitReviewDecision`）快照两侧版本；以下变化把相关 ACTIVE 结论置
`PENDING_REVIEW` 并记录原因，放行闸门 `POST .../approve` 在待复核清零前返回 409：

| 变化 | 触发接口 | 待复核原因 |
| --- | --- | --- |
| 许可缩界/扩界、深度、日期、封路变化（几何版本+1） | `POST /permits/{no}/changes` | `PERMIT_GEOMETRY_CHANGED`（全部结论） |
| 管网测绘升版（中心线/误差带更新，测绘版本+1） | `POST /pipes/{code}/survey-revision` | `PIPE_SURVEY_REVISED`（仅该管段 PROTECTION 结论） |
| 紧急抢修插入/窗口调整（窗口 revision+1） | `POST /windows`、`POST /windows/{code}/changes` | `REPAIR_WINDOW_CHANGED`（相关 ACCESS 结论） |

复核接口 `POST /permits/{no}/decisions/{id}/review`：影响仍成立用 `CONFIRM`
（关闭旧结论、按当前两侧版本另立 ACTIVE 新结论，历史不抹掉）；影响已消失用 `DISMISS`
（如许可缩界退出 P17 保护区，对已消失影响强行 CONFIRM 会被 422 拒绝）。

### 4.4 `replay_construction.py` 回放覆盖（68 项断言）

1. 角色分流：调度评保护区 403、工程师评可达性 403、ACCESS 用现场探测 422、VIEWER 403；
2. P17 三态判定的完整可解释证据链（d_nom/e+P/埋深/重要用户），P18 安全不误报；
3. 两路线候选集合、版本一致性、证据摘要完全相同；
4. **测绘升版**：P17 v1→v2 后旧 PROTECTION 结论待复核、ACCESS 不受影响、放行 409，
   物化关系按 v2 重建且旧关系 SUPERSEDED，复核 CONFIRM 后才重新放行；
5. **紧急抢修插入**：新窗口自动形成 `ACCESS_BLOCKED` 候选（唯一进场道路被封）；
   RW-001 改进场道路后旧 ACCESS 结论待复核，替代路径提示由光明路改为滨河巷；
6. **许可缩界**：全部旧结论待复核、放行被拒；P17 距离变 4.2m 转 SAFE 后 DISMISS，
   两条 ACCESS 结论 CONFIRM，物化候选清除失效 P17 关系，最终重新放行；
7. 反向校验：远离保护区与窗口的许可零冲突可直接放行；退回修改（RETURNED）态不能放行。

### 4.5 手工 API 走查

```bash
B=http://localhost:8080
DISP="Authorization: Bearer $DISPATCHER_TOKEN"; ENG="Authorization: Bearer $ENGINEER_TOKEN"
J='Content-Type: application/json'

# 即时/物化审查与两路线对比
curl -s $B/api/construction/permits/WP-2026-017/review/realtime -H "$DISP" | python3 -m json.tool
curl -s $B/api/construction/permits/WP-2026-017/review/materialized -H "$DISP"
curl -s $B/api/construction/permits/WP-2026-017/route-comparison -H "$DISP"

# 工程师对 P17 要求现场探测；调度对封路附加替代路径保护条件
curl -s -X POST $B/api/construction/permits/WP-2026-017/decisions -H "$ENG" -H "$J" -d '{
  "impactType":"PROTECTION","targetCode":"P17",
  "action":"FIELD_SURVEY_REQUIRED","conditionNote":"开挖前人工探明实际走向与埋深"}'
curl -s -X POST $B/api/construction/permits/WP-2026-017/decisions -H "$DISP" -H "$J" -d '{
  "impactType":"ACCESS","targetCode":"R-JIANSHE","repairWindowCode":"RW-001",
  "action":"PROTECTION_CONDITION","conditionNote":"保留光明路替代进场路径"}'

# 测绘升版 / 许可缩界 / 紧急窗口插入
curl -s -X POST $B/api/construction/pipes/P17/survey-revision -H "$ENG" -H "$J" -d '{
  "polygon":[[10,11.2],[70,11.2]],"surveyErrorMeters":0.6,
  "protectionDistanceMeters":2.0,"burialDepthMeters":1.2,"reason":"复测中心线北移"}'
curl -s -X POST $B/api/construction/permits/WP-2026-017/changes -H "$DISP" -H "$J" -d '{
  "polygon":[[18,3],[36,3],[36,7],[18,7]],"reason":"施工方案缩界"}'
curl -s -X POST $B/api/construction/windows -H "$DISP" -H "$J" -d '{
  "code":"RW-002","title":"紧急爆管抢修","crewCode":"C_SCOUT",
  "windowStart":"2026-11-27T02:00:00+08:00","windowEnd":"2026-11-27T06:00:00+08:00",
  "plannedApproachRoadCodes":"R-JIANSHE","note":"紧急插入"}'

# 放行（有待复核/未审批项时 409 PERMIT_NOT_APPROVABLE）
curl -s -X POST $B/api/construction/permits/WP-2026-017/approve -H "$DISP"
```

坐标约定：施工/管线几何使用**局部工程坐标（米）**录入（`[x, y]` 数组，x 东向、y 北向），
所有距离与相交判断在同一坐标系内完成，避免经纬度球面距离歧义；坐标以 JSON 数组提交，
后端 `PointDto` 负责反序列化。

---

## 5. 手工 API 走查（核心端点）

所有写接口都需要调度员 token。完整 schema 见 Swagger UI。

```bash
B=http://localhost:8080
H="Authorization: Bearer $TOKEN"; J='Content-Type: application/json'

# 复位样例（回放脚本开头也会调用）
curl -s -X POST $B/api/admin/reset -H "$H" -H "$J" -d '{}'

# 管网与精确影响
curl -s $B/api/network/addresses -H "$H" | python3 -m json.tool
curl -s -X POST $B/api/network/impact -H "$H" -H "$J" -d '{"valveCodes":["V2"]}'
curl -s -X POST $B/api/network/candidate-isolations -H "$H" -H "$J" \
  -d '{"boundarySegmentCodes":["S2"]}'

# 受理报修（自动归属判断）
curl -s -X POST $B/api/reports -H "$H" -H "$J" -d '{
  "callerPhone":"13900000009","rawAddress":"幸福路3号",
  "description":"低压","receivedAt":"2026-11-26T03:05:00+08:00"}'

# 调度确认事故
curl -s -X POST $B/api/incidents -H "$H" -H "$J" -d '{
  "title":"S2 低压","reportIds":[1],"boundarySegmentCodes":["S2"]}'

# 候选方案 → 选 V2 → 通知 A01-A05
curl -s $B/api/incidents/1/candidate-isolations -H "$H"
curl -s -X POST $B/api/incidents/1/isolation-plans -H "$H" -H "$J" -d '{"valveCodes":["V2"]}'
curl -s -X POST $B/api/incidents/1/notify -H "$H"

# 资格预检 → 捆绑暂留派工 → 下达
curl -s -X POST $B/api/incidents/1/dispatch/eligibility -H "$H" -H "$J" -d '{
  "kind":"REPAIR","crewCode":"C_SOLE",
  "windowStart":"2026-11-26T05:00:00+08:00","windowEnd":"2026-11-26T07:00:00+08:00"}'
curl -s -X POST $B/api/incidents/1/dispatch/repair -H "$H" -H "$J" -d '{
  "crewCode":"C_SOLE","strategy":"RESERVE_BUNDLE","holdOnly":true,
  "windowStart":"2026-11-26T05:00:00+08:00","windowEnd":"2026-11-26T07:00:00+08:00"}'

# 按顺序提交回执（assignmentId 从上一步响应取）
curl -s -X POST $B/api/incidents/1/receipts -H "$H" -H "$J" -d '{
  "assignmentId":1,"type":"ARRIVAL","eventAt":"2026-11-26T05:05:00+08:00","note":"到场"}'

# 事故全状态聚合（核对来源/判断/方案/通知/更正/派工/占用/回执）
curl -s $B/api/incidents/1 -H "$H" | python3 -m json.tool
```

主要资源分组（Swagger Tag 与之一致）：

| 分组 | 代表端点 |
| --- | --- |
| 01 鉴权 | `POST /api/auth/login` |
| 02 管网图与影响 | `/api/network/*`、`POST /api/network/impact`、`/api/network/candidate-isolations` |
| 03 报修与归属 | `POST /api/reports`、`POST /api/reports/{id}/judgments` |
| 04 事故/方案/通知/复核 | `POST /api/incidents`、`/isolation-plans`、`/notify`、`/pressure-reviews`、`GET /{id}` |
| 05 派工与互斥 | `/dispatch/scout`、`/dispatch/repair`、`/redispatch`、`/api/assignments/{id}/cancel|complete|issue` |
| 06 回执与复压 | `POST /api/incidents/{id}/receipts`、`/close`、`POST /api/pressure/verify-repressure` |
| 07 道路施工许可审查 | `/api/construction/permits`、`/review/realtime|materialized`、`/decisions`、`/pipes/{code}/survey-revision`、`/windows` |
| 00 回放支持 | `POST /api/admin/reset` |

---

## 6. 领域模型与关键实现

### 6.1 随题样例管网（`src/main/resources/samples/network.json`）

```
NSRC ─ M0 ─ NT0 ─ F1 ─ NV1(V1) ─ F1D ─ NT1 ─ S1 ─ NE1   地址 A10-A13
                │                    └ F2 ─ NV2(V2) ─ S2 ─ NE2  地址 A01-A05
                └ F3 ─ NV3(V3) ─ S3 ─ NE3                      地址 A06-A09
```

- 节点：`SOURCE / TEE 三通 / VALVE 阀门 / END 末端`；管段连接两节点。
- 地址点唯一映射管段；`幸福里6栋`同时是 A04(S2)/A07(S3) 的别名，
  路口 `LM-XF-BH` 候选 `[S2,S3]`——用于证明“同楼/最近坐标 ≠ 同一事故”。
- 每段配置复压正常区间（如 S2 `[0.22, 0.45] MPa`）与校时容差（600s）。
- 压力读数带设备号、采样时刻 `sampledAt`、校时偏差 `clockOffsetSeconds`；
  约定 **校时后采样时刻 = sampledAt + clockOffsetSeconds**。

影响算法（`network/ImpactService.java`）：把关闭的阀门视为不可穿越节点，
从所有 SOURCE 做可达性遍历；一条管段两端都不可达即判为失压，其上地址全部受影响。
候选方案枚举全部阀门子集（≤4 阀组合），返回能隔离边界管段的“包含意义最小”组合。

### 6.2 归属与事故边界

- 每条报修永久保存原文（地址、描述、照片引用、接听时刻）。
- 自动归属按“最长别名命中 → 路口地标 → 坐标半径”的优先级产出
  `MATCHED / AMBIGUOUS / UNMATCHED` 判断；人工判断只追加新版本，不覆盖历史。
- 报修受理**不会**自动建事故；只有调度调用 `POST /api/incidents` 显式确认边界后才成案。
- 热线重拨带 `redialOfReportId/hotlineCallId`：若原线索已在事故中，
  新线索自动以 `REDIAL` 加入同一事故，不产生新事故或派工。

### 6.3 互斥与资格

- 资格逐项检查：班组 active、申请窗口落在当班时间（按 Asia/Shanghai）、
  所需资质（`Q_INSPECT/Q_REPAIR/Q_VALVE_STD/Q_VALVE_LARGE`，V1 要大阀许可）、
  绑定车辆 `available`、标配工具全部 `available`。
- 占用资源类型：`CREW / VALVE_PERMIT / VEHICLE / TOOL`。
- 预留时先对资源主行 `SELECT ... FOR UPDATE`，再用半开区间条件
  `existing.start < :end AND existing.end > :start AND status='HELD'` 查重叠。
  因此同一队伍（或同一阀门操作票）的两个并发派单在数据库层串行化，恰好一成一败；
  端点相接的窗口（如 09:20 结束 / 09:30 开始）不冲突。
- 释放不删行：占用行置 `RELEASED` 并写 `releaseReason/releasedAt`。

### 6.4 回执周期与更正链

- 顺序固定：`ARRIVAL → ISOLATION → REPAIR → FLUSH → REPRESSURE`，按 `(incident, cycleNo)` 成链。
- `ISOLATION` 及之后必须由 `REPAIR` 派工单提交；勘查单只能交 `ARRIVAL`。
- 复压必须引用压力读数，核验：压力值在该支管区间内、校时后采样时刻与回执时刻偏差 ≤ 容差、
  读数设备管段与当前边界一致；任一不过即 `FAILED`，`/close` 返回 409。
- `redispatch` 释放上一维修单占用、`cycleNo+1`、建立新 `Assignment(attemptNo,parent)`；
  旧周期回执与失败结论原样保留，旧单不能再写新周期。
- 压力复核 `OVERTURNS` 在一个事务内完成：旧方案 `CORRECTED` + 新方案版本、
  事故边界更新、地址集合 diff、阀门操作票“释放旧票/新占新票”（新票冲突则整体回滚）、
  旧通知 `SUPERSEDED` + 更正/取消通知链、`CorrectionRecord` 留全量快照。

---

## 7. 本地开发与验证方式（实际执行记录）

以下验证均在本工作区容器内**实际执行通过**（日期 2026-11-25）：

| 验证 | 命令 | 实际结果 |
| --- | --- | --- |
| 编译 | `JAVA_HOME=/opt/jdk-25 /opt/maven/bin/mvn -s settings.xml compile` | BUILD SUCCESS |
| 单元测试 | `... mvn -s settings.xml test` | Tests run: 11, Failures: 0, Errors: 0（`ImpactServiceTest` 4 项 + `ConflictEngineTest` 7 项） |
| 打包 | `... mvn -s settings.xml -DskipTests package` | 生成 `target/water-repair-service.jar`（约 57 MB fat jar） |
| 真实运行 | `/opt/jdk-25/bin/java -jar target/water-repair-service.jar` | 8080 启动成功，Hibernate 连 PostgreSQL 18.1 建表/播种，日志无 ERROR |
| 调度主线回放 | `python3 scripts/replay.py` | `PASS=114 FAIL=0 ALL_DONE: true`（含真实双线程并发派单） |
| 施工许可回放 | `python3 scripts/replay_construction.py` | `PASS=68 FAIL=0 ALL_DONE: true`（三态判定/两类分流/三变化待复核/两路线一致） |
| 一键回放 | `bash scripts/verify.sh` | 两套回放顺序执行，均 `ALL_DONE: true` |
| 接口文档 | `GET /v3/api-docs`、`/swagger-ui/index.html` | 均 200，OpenAPI 含全部 `/api/**` 路径（含 07 施工许可审查分组） |

说明与限制（如实记录）：

- 当前容器**未安装 Docker**，因此 `docker compose up --build` 未能在本机实际执行；
  `Dockerfile`（多阶段 Maven 21 构建 + JRE 21 运行）与 `docker-compose.yml`
  （`postgres:18-alpine` + 健康检查 + 依赖编排）已按标准方式提供，需在有 Docker 的环境验收。
- 回放使用显式 ISO 时间（样例事故日 `2026-11-26`，时区 `+08:00`），不依赖系统时钟，可重复。
- 短信通道为 `SIMULATED_SMS`：通知落库并在接口/聚合详情中可查，不接真实短信网关。

---

## 8. 目录速览

```
pom.xml / settings.xml / Dockerfile / docker-compose.yml
scripts/replay.py                    # 抢修调度主线 114 断言的端到端回放（纯标准库）
scripts/replay_construction.py       # 施工许可审查 68 断言回放（三态/两类影响/三变化/两路线）
src/main/resources/application.yml
src/main/resources/samples/          # 管网/班组/账号/报修/回执/施工许可样本
  └─ construction.json               # P17 浅埋支管、道路、封路许可、抢修窗口
src/main/java/com/waterrepair/
  auth/          账号、登录 token、角色拦截（DISPATCHER/ENGINEER/CREW/VIEWER）
  common/        统一异常、健康检查、首页跳转
  config/        Web/OpenAPI/播种/reset
  network/       节点/管段/阀门/地址/压力读数 + 图影响算法
  report/        报修原文 + 归属判断版本
  incident/      事故/来源/方案/通知/更正/压力复核
  crew/          班组/车辆/工具/阀门操作许可
  dispatch/      派工单 + 资源占用 + 行锁互斥 + 资格校验
  receipt/       五类顺序回执 + 复压核验
  construction/  施工许可/管段防护台账/道路/抢修窗口/候选冲突/审查结论 + ConflictEngine
    └─ geom/     GeoPoint/Polygon（点包含、折线相交、最近距离、缓冲）等几何原语
src/test/java/com/waterrepair/
  network/ImpactServiceTest.java      # 图影响算法单测（4 项）
  construction/ConflictEngineTest.java# 三态判定/缩界/可达性单测（7 项，含 P17 1.6m 场景）
```
