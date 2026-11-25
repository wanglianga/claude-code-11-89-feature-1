#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
道路施工许可审查端到端回放（仅依赖 Python 标准库）。

固定题面场景：
  P17 管线中心线距开挖边界 1.6m、测绘误差 ±0.8m、保护距离 2m => 可能侵入（POSSIBLE_INTRUSION），
  不能判成确定安全；许可同时封闭抢修班组原定进场道路（建设路），值班调度须保留替代路径（光明路）。

回放主线：
  0. 角色分流：保护区相交由管线工程师评估，道路可达性由值班调度处理，两类影响不合并；
  1. 两条计算路线（即时计算 / 物化候选）对同一输入给出一致且可解释的结论；
  2. 变化一·管网测绘升版：旧 PROTECTION 结论标待复核，按新版本重审；
  3. 变化二·紧急抢修插入/窗口调整：旧 ACCESS 结论标待复核，新增窗口自动形成候选；
  4. 变化三·许可缩界：旧结论全部待复核、放行被拒；退出保护区的项 DISMISS，可达性项 CONFIRM；
  5. 远离保护区的施工不误报，可直接放行；物化关系随变化清除失效项并保持两侧版本一致。

用法：
  python3 scripts/replay_construction.py
  BASE_URL=http://127.0.0.1:8080 python3 scripts/replay_construction.py
退出码 0 表示全部断言通过。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080").rstrip("/")

PASS_COUNT = 0
FAIL_COUNT = 0
FAIL_LINES = []


def section(title):
    print("\n" + "=" * 78)
    print("▶ " + title)
    print("=" * 78)


def check(name, cond, detail=""):
    global PASS_COUNT, FAIL_COUNT
    if cond:
        PASS_COUNT += 1
        print(f"  ✅ {name}")
    else:
        FAIL_COUNT += 1
        FAIL_LINES.append(f"{name} :: {detail}")
        print(f"  ❌ {name}  {('- ' + str(detail)) if detail else ''}")


def require(name, cond, detail=""):
    check(name, cond, detail)
    if not cond:
        raise AssertionError(f"关键断言失败，回放中止: {name} {detail}")


def http(method, path, body=None, token=None, expect=None):
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            status = resp.status
            payload = json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {"raw": raw}
        status = e.code
    if expect is not None and status != expect:
        raise AssertionError(f"{method} {path} 期望 {expect}，实际 {status}: {payload}")
    return status, payload


def wait_healthy(timeout_sec=90):
    deadline = time.time() + timeout_sec
    last = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/api/health", timeout=5) as r:
                if r.status == 200:
                    return True
        except Exception as e:  # noqa
            last = e
            time.sleep(2)
    print("服务未就绪:", last)
    return False


def login(username, password):
    _, data = http("POST", "/api/auth/login",
                   {"username": username, "password": password}, expect=200)
    return data["token"]


PERMIT_NO = "WP-2026-017"
# 缩界后顶边 y=7（距升版后 P17 中心线 y=11.2 为 4.2m），x 范围 18~36 不再覆盖滨河巷(x=40)
SHRUNK_POLYGON = [[18, 3], [36, 3], [36, 7], [18, 7]]


def decisions_map(decisions):
    return {(d["impactType"], d.get("targetCode"), d.get("repairWindowCode")): d for d in decisions}


def main():
    if not wait_healthy():
        print("ALL_DONE: false")
        sys.exit(2)

    # ---------- 0. 鉴权、角色与 reset ----------
    section("0. 鉴权与重新播种：dispatcher / engineer / viewer 三个角色")
    st, _ = http("GET", "/api/construction/permits")
    check("未登录访问施工许可接口返回 401", st == 401, f"status={st}")

    disp = login("dispatcher", "Disp@12345")
    eng = login("engineer", "Eng@12345")
    viewer = login("viewer", "View@12345")
    check("三个测试账号均可登录", bool(disp and eng and viewer))

    st, reset_body = http("POST", "/api/admin/reset", {}, token=disp, expect=200)
    seeded = reset_body.get("seeded", {})
    check("reset 后播种施工样例（许可1/管段2/道路4/窗口1）",
          seeded.get("constructionPermits") == 1 and seeded.get("pipeProfiles") == 2
          and seeded.get("roads") == 4 and seeded.get("repairWindows") == 1, seeded)

    st, doc = http("GET", "/v3/api-docs")
    check("OpenAPI 文档包含施工许可审查接口",
          st == 200 and "/api/construction/permits" in json.dumps(doc), f"status={st}")

    st, _ = http("POST", f"/api/construction/permits/{PERMIT_NO}/decisions",
                 {"impactType": "PROTECTION", "targetCode": "P17",
                  "action": "FIELD_SURVEY_REQUIRED", "conditionNote": "x"}, token=viewer)
    check("VIEWER 不能审批（403）", st == 403, f"status={st}")

    # ---------- 1. 初始审查：P17 可能侵入 + 封路可达性，两类影响分开 ----------
    section("1. 初始审查：P17（1.6m / ±0.8m / 保护2m）判可能侵入；建设路封路影响 RW-001 进场")
    _, pipes = http("GET", "/api/construction/pipes", token=disp)
    p17 = next(p for p in pipes if p["code"] == "P17")
    check("P17 台账：误差带 0.8m、保护距离 2.0m、埋深 1.2m、重要用户=true",
          p17["surveyErrorMeters"] == 0.8 and p17["protectionDistanceMeters"] == 2.0
          and p17["burialDepthMeters"] == 1.2 and p17["importantUser"] is True, p17)

    _, rt = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/realtime", token=disp)
    pf = {f["pipeCode"]: f for f in rt["protectionFindings"]}
    require("即时路线返回 P17/P18 两项保护区评估", set(pf) == {"P17", "P18"}, list(pf))
    f17 = pf["P17"]
    check("P17 名义最近距离=1.6m，e+P=2.8m，结论 POSSIBLE_INTRUSION（非确定安全）",
          f17["nominalDistanceMeters"] == 1.6
          and f17["uncertaintyOuterReachMeters"] == 2.8
          and f17["severity"] == "POSSIBLE_INTRUSION", f17)
    check("P17 开挖深度 1.5m 达到管顶埋深 1.2m，垂向接触风险成立",
          f17["depthReached"] is True and any("埋深" in e for e in f17["evidence"]), f17)
    check("P17 依据可解释：证据链含名义距离/误差带/保护距离/埋深",
          len(f17["evidence"]) >= 3 and any("d_nom=1.60m" in e for e in f17["evidence"]),
          f17["evidence"])
    check("远离施工区的 P18 判 SAFE，不误报成冲突",
          pf["P18"]["severity"] == "SAFE", pf["P18"])
    check("即时路线可行动保护区冲突恰为 1 项（P17）",
          rt["actionableProtectionCount"] == 1 and rt["accessImpactCount"] == 1, rt)

    access = rt["accessFindings"]
    require("道路可达性影响恰为 1 项", len(access) == 1, access)
    a1 = access[0]
    check("封路阻断 RW-001 班组原定进场道路 R-JIANSHE，备选 R-GUANGMING 保留",
          a1["roadCode"] == "R-JIANSHE" and a1["repairWindowCode"] == "RW-001"
          and a1["blockedRoadCodes"] == ["R-JIANSHE"]
          and a1["alternativeRoadCodes"] == ["R-GUANGMING"]
          and a1["allPlannedRoadsBlocked"] is False
          and "替代进场路径" in a1["advisory"], a1)
    check("保护区与可达性是两类独立影响，没有被合并成一个布尔冲突",
          {f["impactType"] if "impactType" in f else "PROTECTION" for f in rt["protectionFindings"]}
          and all("roadCode" in x for x in rt["accessFindings"]))

    _, mat = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/materialized", token=disp)
    keys = sorted((c["impactType"], c["targetCode"], c.get("repairWindowCode"))
                  for c in mat["candidates"])
    check("物化路线候选恰为 PROTECTION/P17 + ACCESS/R-JIANSHE/RW-001 两条",
          keys == [("ACCESS", "R-JIANSHE", "RW-001"), ("PROTECTION", "P17", None)], keys)
    check("物化关系两侧版本一致（许可 v1，P17 测绘 v1，窗口 rev1）且无失效项",
          all(c["versionConsistent"] for c in mat["candidates"])
          and mat["stale"] is False and mat["activeCount"] == 2
          and mat["supersededCount"] == 0, mat)
    check("物化候选保留可解释证据摘要",
          all(len(c["evidenceSummary"]) > 20 for c in mat["candidates"]))

    _, cmp1 = http("GET", f"/api/construction/permits/{PERMIT_NO}/route-comparison", token=disp)
    check("两条计算路线对同一输入结论完全一致",
          cmp1["consistent"] is True and not cmp1["onlyInRealtime"]
          and not cmp1["onlyInMaterialized"] and not cmp1["severityMismatch"], cmp1)

    # ---------- 2. 角色分流的逐项审批 ----------
    section("2. 逐项审批：工程师管 PROTECTION，调度管 ACCESS，动作含现场探测/保护条件/退回")
    st, err = http("POST", f"/api/construction/permits/{PERMIT_NO}/decisions",
                   {"impactType": "PROTECTION", "targetCode": "P17",
                    "action": "FIELD_SURVEY_REQUIRED",
                    "conditionNote": "开挖前人工探明 P17 实际走向与埋深"}, token=disp)
    check("调度不能评估保护区相交（403 ENGINEER_REQUIRED）",
          st == 403 and err["code"] == "ENGINEER_REQUIRED", (st, err))
    st, err = http("POST", f"/api/construction/permits/{PERMIT_NO}/decisions",
                   {"impactType": "ACCESS", "targetCode": "R-JIANSHE",
                    "repairWindowCode": "RW-001", "action": "PROTECTION_CONDITION",
                    "conditionNote": "保留光明路备选"}, token=eng)
    check("工程师不能确认道路可达性（403 DISPATCHER_REQUIRED）",
          st == 403 and err["code"] == "DISPATCHER_REQUIRED", (st, err))
    st, err = http("POST", f"/api/construction/permits/{PERMIT_NO}/decisions",
                   {"impactType": "ACCESS", "targetCode": "R-JIANSHE",
                    "repairWindowCode": "RW-001", "action": "FIELD_SURVEY_REQUIRED"}, token=disp)
    check("ACCESS 类不适用现场探测动作（422 ACTION_NOT_APPLICABLE）",
          st == 422 and err["code"] == "ACTION_NOT_APPLICABLE", (st, err))

    _, d_prot = http("POST", f"/api/construction/permits/{PERMIT_NO}/decisions",
                     {"impactType": "PROTECTION", "targetCode": "P17",
                      "action": "FIELD_SURVEY_REQUIRED",
                      "conditionNote": "开挖前人工探明 P17 实际走向与埋深，浅埋重要用户管线"},
                     token=eng, expect=200)
    check("工程师对 P17 下发现场探测要求，快照许可 v1/测绘 v1",
          d_prot["action"] == "FIELD_SURVEY_REQUIRED"
          and d_prot["severityAtDecision"] == "POSSIBLE_INTRUSION"
          and d_prot["permitGeometryVersion"] == 1
          and d_prot["sideVersionAtDecision"] == 1
          and d_prot["reviewState"] == "ACTIVE", d_prot)

    _, d_acc = http("POST", f"/api/construction/permits/{PERMIT_NO}/decisions",
                    {"impactType": "ACCESS", "targetCode": "R-JIANSHE",
                     "repairWindowCode": "RW-001", "action": "PROTECTION_CONDITION",
                     "conditionNote": "值班调度保留光明路替代进场路径并提前通知 C_SOLE"},
                    token=disp, expect=200)
    check("调度对可达性影响附加保护条件（替代路径），快照许可 v1/窗口 rev1",
          d_acc["action"] == "PROTECTION_CONDITION"
          and d_acc["permitGeometryVersion"] == 1 and d_acc["sideVersionAtDecision"] == 1
          and d_acc["reviewState"] == "ACTIVE", d_acc)

    st, err = http("POST", f"/api/construction/permits/{PERMIT_NO}/decisions",
                   {"impactType": "PROTECTION", "targetCode": "P17",
                    "action": "PROTECTION_CONDITION", "conditionNote": "重复立项"}, token=eng)
    check("同一冲突项重复立项被 409 拒绝",
          st == 409 and err["code"] == "DECISION_ALREADY_ACTIVE", (st, err))

    _, gate0 = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/realtime", token=disp)
    check("两类影响逐项审批完成后放行闸门打开",
          gate0["canApprove"] is True and gate0["blockReasons"] == [], gate0)
    _, approved = http("POST", f"/api/construction/permits/{PERMIT_NO}/approve", {},
                       token=disp, expect=200)
    check("许可放行 APPROVED", approved["status"] == "APPROVED", approved)

    # ---------- 3. 变化一：管网测绘升版 ----------
    section("3. 变化一·测绘升版：P17 中心线修正后 v1→v2，旧 PROTECTION 结论待复核，ACCESS 不受影响")
    _, revised = http("POST", "/api/construction/pipes/P17/survey-revision",
                      {"polygon": [[10, 11.2], [70, 11.2]],
                       "surveyErrorMeters": 0.6,
                       "protectionDistanceMeters": 2.0,
                       "burialDepthMeters": 1.2,
                       "importantUser": True,
                       "importantUserNote": "市第三医院供水支管，浅埋",
                       "reason": "复测后中心线北移 0.4m、误差带收窄至 ±0.6m"},
                      token=eng, expect=200)
    check("P17 测绘版本升到 v2", revised["surveyVersion"] == 2
          and revised["surveyErrorMeters"] == 0.6, revised)

    _, decs = http("GET", f"/api/construction/permits/{PERMIT_NO}/decisions", token=disp)
    dmap = decisions_map(decs)
    prot_old = dmap[("PROTECTION", "P17", None)]
    acc_old = dmap[("ACCESS", "R-JIANSHE", "RW-001")]
    check("旧 PROTECTION 结论置 PENDING_REVIEW，原因 PIPE_SURVEY_REVISED",
          prot_old["reviewState"] == "PENDING_REVIEW"
          and prot_old["pendingReason"] == "PIPE_SURVEY_REVISED"
          and "v1→v2" in prot_old["pendingDetail"], prot_old)
    check("ACCESS 结论与测绘升版无关，保持 ACTIVE",
          acc_old["reviewState"] == "ACTIVE", acc_old)

    st, err = http("POST", f"/api/construction/permits/{PERMIT_NO}/approve", {}, token=disp)
    check("存在待复核结论时放行被 409 拒绝，不能自动放行",
          st == 409 and err["code"] == "PERMIT_NOT_APPROVABLE"
          and any("待复核" in r for r in err["details"]["blockReasons"]), (st, err))

    _, rt2 = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/realtime", token=disp)
    f17v2 = next(f for f in rt2["protectionFindings"] if f["pipeCode"] == "P17")
    check("升版后即时重算：名义距离 1.2m、e+P=2.6m，仍为 POSSIBLE_INTRUSION",
          f17v2["nominalDistanceMeters"] == 1.2
          and f17v2["uncertaintyOuterReachMeters"] == 2.6
          and f17v2["severity"] == "POSSIBLE_INTRUSION" and f17v2["surveyVersion"] == 2, f17v2)
    _, mat2 = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/materialized", token=disp)
    p17_cand = next(c for c in mat2["candidates"] if c["targetCode"] == "P17")
    check("物化关系已按 v2 重建：sideVersion=2 且与许可版本一致，旧关系 SUPERSEDED",
          p17_cand["sideVersion"] == 2 and p17_cand["versionConsistent"] is True
          and mat2["supersededCount"] >= 2, mat2)
    _, cmp2 = http("GET", f"/api/construction/permits/{PERMIT_NO}/route-comparison", token=disp)
    check("测绘升版后两条路线结论仍一致", cmp2["consistent"] is True, cmp2)

    _, d_prot2 = http("POST",
                      f"/api/construction/permits/{PERMIT_NO}/decisions/{prot_old['id']}/review",
                      {"verdict": "CONFIRM", "note": "复测后仍处误差带+保护区内，维持现场探测要求"},
                      token=eng, expect=200)
    check("工程师复核 CONFIRM：按许可 v1/测绘 v2 另立 ACTIVE 结论，旧结论关闭",
          d_prot2["reviewState"] == "ACTIVE" and d_prot2["sideVersionAtDecision"] == 2
          and d_prot2["action"] == "FIELD_SURVEY_REQUIRED", d_prot2)
    _, decs = http("GET", f"/api/construction/permits/{PERMIT_NO}/decisions", token=disp)
    check("原 PROTECTION 结论已 CLOSED，历史可追溯",
          any(d["id"] == prot_old["id"] and d["reviewState"] == "CLOSED" for d in decs)
          and any(d["impactType"] == "PROTECTION" and d["reviewState"] == "ACTIVE"
                  and d["sideVersionAtDecision"] == 2 for d in decs), decs)
    _, approved2 = http("POST", f"/api/construction/permits/{PERMIT_NO}/approve", {},
                        token=disp, expect=200)
    check("复核完成后许可可再次放行", approved2["status"] == "APPROVED", approved2)

    # ---------- 4. 变化二：紧急抢修插入 + 窗口调整 ----------
    section("4. 变化二·紧急抢修插入：新窗口自动生成 ACCESS 候选；RW-001 调整后旧 ACCESS 结论待复核")
    _, win_new = http("POST", "/api/construction/windows",
                      {"code": "RW-002",
                       "title": "第三医院方向紧急爆管抢修（临时插入）",
                       "crewCode": "C_SCOUT",
                       "windowStart": "2026-11-27T02:00:00+08:00",
                       "windowEnd": "2026-11-27T06:00:00+08:00",
                       "plannedApproachRoadCodes": "R-JIANSHE",
                       "note": "紧急插入，唯一计划进场道路为建设路"},
                      token=disp, expect=200)
    check("紧急抢修窗口 RW-002 登记成功（rev1）",
          win_new["code"] == "RW-002" and win_new["revision"] == 1, win_new)
    _, permit_after_insert = http("GET", f"/api/construction/permits/{PERMIT_NO}", token=disp)
    check("已放行许可因新窗口产生 ACCESS 冲突后回到 REVIEWING，原批准不再有效",
          permit_after_insert["status"] == "REVIEWING", permit_after_insert)
    _, mat3 = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/materialized", token=disp)
    rw002 = [c for c in mat3["candidates"]
             if c["impactType"] == "ACCESS" and c["repairWindowCode"] == "RW-002"]
    check("新窗口自动进入物化候选：建设路被封且无替代道路 => ACCESS_BLOCKED",
          len(rw002) == 1 and rw002[0]["severity"] == "ACCESS_BLOCKED", rw002)
    _, rt3 = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/realtime", token=disp)
    f002 = next(f for f in rt3["accessFindings"] if f["repairWindowCode"] == "RW-002")
    check("即时路线提示 RW-002 全部计划道路被封，调度须重新制定进场路径",
          f002["allPlannedRoadsBlocked"] is True and "重新制定进场路径" in f002["advisory"], f002)
    _, cmp3 = http("GET", f"/api/construction/permits/{PERMIT_NO}/route-comparison", token=disp)
    check("插入窗口后两条路线结论仍一致", cmp3["consistent"] is True, cmp3)

    # RW-001 调整：光明路也占用，改走滨河巷
    _, win_changed = http("POST", "/api/construction/windows/RW-001/changes",
                          {"plannedApproachRoadCodes": "R-JIANSHE,R-BINHE",
                           "reason": "光明路同期施工，抢修进场备选改为滨河巷"},
                          token=disp, expect=200)
    check("RW-001 调整后 revision=2", win_changed["revision"] == 2, win_changed)
    _, decs = http("GET", f"/api/construction/permits/{PERMIT_NO}/decisions", token=disp)
    acc_active = [d for d in decs if d["impactType"] == "ACCESS"
                  and d["repairWindowCode"] == "RW-001" and d["reviewState"] == "ACTIVE"]
    check("RW-001 原 ACCESS 结论置 PENDING_REVIEW，原因 REPAIR_WINDOW_CHANGED",
          len(acc_active) == 0
          and any(d.get("repairWindowCode") == "RW-001"
                  and d["reviewState"] == "PENDING_REVIEW"
                  and d["pendingReason"] == "REPAIR_WINDOW_CHANGED" for d in decs), decs)
    pending_acc = next(d for d in decs if d.get("repairWindowCode") == "RW-001"
                       and d["reviewState"] == "PENDING_REVIEW")
    _, rt4 = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/realtime", token=disp)
    f001 = next(f for f in rt4["accessFindings"] if f["repairWindowCode"] == "RW-001")
    check("即时重算：替代进场路径提示更新为滨河巷 R-BINHE",
          f001["alternativeRoadCodes"] == ["R-BINHE"]
          and f001["blockedRoadCodes"] == ["R-JIANSHE"], f001)

    # 新窗口 RW-002 先逐项处理，再复核 RW-001 旧结论
    _, d_002 = http("POST", f"/api/construction/permits/{PERMIT_NO}/decisions",
                    {"impactType": "ACCESS", "targetCode": "R-JIANSHE",
                     "repairWindowCode": "RW-002", "action": "PROTECTION_CONDITION",
                     "conditionNote": "协调临时打开滨河巷作为 RW-002 应急通道"},
                    token=disp, expect=200)
    check("RW-002 可达性影响逐项立项 ACTIVE", d_002["reviewState"] == "ACTIVE", d_002)
    _, d_acc2 = http("POST",
                     f"/api/construction/permits/{PERMIT_NO}/decisions/{pending_acc['id']}/review",
                     {"verdict": "CONFIRM", "note": "按调整后窗口确认滨河巷替代路径"},
                     token=disp, expect=200)
    check("调度复核 RW-001：新结论引用窗口 rev2 并保持 ACTIVE",
          d_acc2["reviewState"] == "ACTIVE" and d_acc2["sideVersionAtDecision"] == 2, d_acc2)

    # ---------- 5. 变化三：许可缩界 ----------
    section("5. 变化三·许可缩界：几何 v1→v2，全部旧结论待复核、放行被拒；P17 退出保护区后 DISMISS")
    st, changed = http("POST", f"/api/construction/permits/{PERMIT_NO}/changes",
                       {"polygon": SHRUNK_POLYGON,
                        "reason": "施工方案优化，开挖范围缩界，避开北侧管线带"},
                       token=disp)
    check("调度/工程师均可提交范围变化，许可几何版本升为 v2 且状态回到 REVIEWING",
          st == 200 and changed["geometryVersion"] == 2
          and changed["status"] == "REVIEWING", changed)
    _, permit_v2 = http("GET", f"/api/construction/permits/{PERMIT_NO}", token=disp)
    check("许可 geometryVersion=2、status=REVIEWING",
          permit_v2["geometryVersion"] == 2 and permit_v2["status"] == "REVIEWING", permit_v2)

    _, decs = http("GET", f"/api/construction/permits/{PERMIT_NO}/decisions", token=disp)
    active_left = [d for d in decs if d["reviewState"] == "ACTIVE"]
    check("缩界后全部 ACTIVE 旧结论均置 PENDING_REVIEW（原因 PERMIT_GEOMETRY_CHANGED）",
          not active_left and all(d["pendingReason"] == "PERMIT_GEOMETRY_CHANGED"
                                  for d in decs if d["reviewState"] == "PENDING_REVIEW"),
          [(d["impactType"], d["reviewState"], d.get("pendingReason")) for d in decs])
    st, err = http("POST", f"/api/construction/permits/{PERMIT_NO}/approve", {}, token=disp)
    check("待复核未处理时再次放行被 409 拒绝",
          st == 409 and err["code"] == "PERMIT_NOT_APPROVABLE", (st, err))

    _, rt5 = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/realtime", token=disp)
    f17_safe = next(f for f in rt5["protectionFindings"] if f["pipeCode"] == "P17")
    check("缩界后 P17 名义距离 4.2m > e+P=2.6m，结论转 SAFE（可能侵入被解除）",
          f17_safe["nominalDistanceMeters"] == 4.2
          and f17_safe["severity"] == "SAFE", f17_safe)
    check("可达性影响仍存在：建设路中心线穿过缩界后多边形",
          {f["repairWindowCode"] for f in rt5["accessFindings"]} == {"RW-001", "RW-002"},
          rt5["accessFindings"])
    _, mat5 = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/materialized", token=disp)
    check("物化候选已清除失效的 P17 关系，仅剩两条 ACCESS 且版本一致",
          mat5["activeCount"] == 2
          and {c["impactType"] for c in mat5["candidates"]} == {"ACCESS"}
          and all(c["versionConsistent"] for c in mat5["candidates"])
          and mat5["stale"] is False, mat5)
    _, cmp5 = http("GET", f"/api/construction/permits/{PERMIT_NO}/route-comparison", token=disp)
    check("缩界后两条路线结论仍一致（含失效关系清除后的集合）",
          cmp5["consistent"] is True, cmp5)

    pending_prot = next(d for d in decs if d["impactType"] == "PROTECTION"
                        and d["reviewState"] == "PENDING_REVIEW")
    st, err = http("POST",
                   f"/api/construction/permits/{PERMIT_NO}/decisions/{pending_prot['id']}/review",
                   {"verdict": "CONFIRM", "note": "试图对已消失影响强制立项"}, token=eng)
    check("影响已消失时 CONFIRM 被 422 拒绝，提示应 DISMISS",
          st == 422 and err["code"] == "IMPACT_GONE_SHOULD_DISMISS", (st, err))
    _, dismissed = http("POST",
                        f"/api/construction/permits/{PERMIT_NO}/decisions/{pending_prot['id']}/review",
                        {"verdict": "DISMISS", "note": "缩界后退出 P17 保护区，冲突解除"},
                        token=eng, expect=200)
    check("工程师 DISMISS 关闭 P17 旧结论，不再阻塞放行",
          dismissed["reviewState"] == "CLOSED", dismissed)

    for window_code in ("RW-001", "RW-002"):
        pending = next(d for d in http("GET",
                       f"/api/construction/permits/{PERMIT_NO}/decisions", token=disp)[1]
                       if d["impactType"] == "ACCESS"
                       and d["repairWindowCode"] == window_code
                       and d["reviewState"] == "PENDING_REVIEW")
        _, confirmed = http("POST",
                            f"/api/construction/permits/{PERMIT_NO}/decisions/{pending['id']}/review",
                            {"verdict": "CONFIRM", "note": f"{window_code} 替代路径按缩界后方案确认"},
                            token=disp, expect=200)
        check(f"{window_code} 可达性结论复核后按许可 v2 重新 ACTIVE",
              confirmed["reviewState"] == "ACTIVE"
              and confirmed["permitGeometryVersion"] == 2, confirmed)

    _, final_gate = http("GET", f"/api/construction/permits/{PERMIT_NO}/review/realtime", token=disp)
    check("全部待复核项处理完毕，放行闸门重新打开",
          final_gate["canApprove"] is True and final_gate["pendingReviewCount"] == 0,
          final_gate["blockReasons"])
    _, final_ok = http("POST", f"/api/construction/permits/{PERMIT_NO}/approve", {},
                       token=disp, expect=200)
    check("缩界复核后许可最终 APPROVED", final_ok["status"] == "APPROVED", final_ok)

    # ---------- 6. 远离保护区的施工不误报 ----------
    section("6. 反向校验：远离管线与封路窗口的许可零冲突，可直接放行")
    _, far_permit = http("POST", "/api/construction/permits",
                         {"permitNo": "WP-2026-018",
                          "contractor": "远通路桥",
                          "title": "东南角小区内部铺装（远离管网保护区）",
                          "polygon": [[60, 40], [75, 40], [75, 55], [60, 55]],
                          "excavationDepthMeters": 0.8,
                          "startDate": "2026-11-25",
                          "endDate": "2026-11-28",
                          "closedRoadCodes": []}, token=disp, expect=200)
    check("新许可播种即物化候选，初始无候选关系", far_permit["geometryVersion"] == 1)
    _, far_rt = http("GET", "/api/construction/permits/WP-2026-018/review/realtime", token=disp)
    check("即时路线：全部管段 SAFE、无可达性影响",
          far_rt["actionableProtectionCount"] == 0 and far_rt["accessImpactCount"] == 0
          and all(f["severity"] == "SAFE" for f in far_rt["protectionFindings"]), far_rt)
    _, far_mat = http("GET", "/api/construction/permits/WP-2026-018/review/materialized", token=disp)
    check("物化路线候选为空且两条路线一致",
          far_mat["activeCount"] == 0 and far_mat["canApprove"] is True, far_mat)
    _, far_cmp = http("GET", "/api/construction/permits/WP-2026-018/route-comparison", token=disp)
    check("零冲突许可两条路线结论一致", far_cmp["consistent"] is True, far_cmp)
    _, far_approved = http("POST", "/api/construction/permits/WP-2026-018/approve", {},
                           token=disp, expect=200)
    check("无冲突许可直接放行", far_approved["status"] == "APPROVED", far_approved)

    # ---------- 7. 退回修改动作 ----------
    section("7. 退回修改：工程师可将许可退回承包商，退回态不能放行")
    _, ret_permit = http("POST", "/api/construction/permits",
                         {"permitNo": "WP-2026-019",
                          "contractor": "宏基市政工程有限公司",
                          "title": "资料不全的开挖申请",
                          "polygon": [[15, 0], [45, 0], [45, 10], [15, 10]],
                          "excavationDepthMeters": 1.5,
                          "startDate": "2026-11-25",
                          "endDate": "2026-11-28",
                          "closedRoadCodes": ["R-JIANSHE"]}, token=disp, expect=200)
    http("POST", "/api/construction/permits/WP-2026-019/decisions",
         {"impactType": "PROTECTION", "targetCode": "P17",
          "action": "RETURNED", "conditionNote": "多边形按道路边界粗绘，要求补测边界后重新报送"},
         token=eng, expect=200)
    _, returned_permit = http("GET", "/api/construction/permits/WP-2026-019", token=disp)
    check("退回动作把许可状态置为 RETURNED",
          returned_permit["status"] == "RETURNED", returned_permit)
    st, err = http("POST", "/api/construction/permits/WP-2026-019/approve", {}, token=disp)
    check("RETURNED 许可放行被 409 拒绝",
          st == 409 and err["code"] == "PERMIT_NOT_APPROVABLE", (st, err))

    print("\n" + "#" * 78)
    print(f"# 施工许可回放断言结果：PASS={PASS_COUNT}  FAIL={FAIL_COUNT}")
    if FAIL_COUNT:
        print("# 失败项：")
        for line in FAIL_LINES:
            print("  - " + line)
        print("ALL_DONE: false")
        sys.exit(1)
    print("# 🎉 全部场景断言通过：可能侵入三态 / 两类影响分流 / 三变化待复核 / 两路线一致")
    print("ALL_DONE: true")
    sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("\n回放因关键断言失败中止:", e)
        print("ALL_DONE: false")
        sys.exit(1)
    except Exception as e:  # noqa
        import traceback
        traceback.print_exc()
        print("ALL_DONE: false")
        sys.exit(2)
