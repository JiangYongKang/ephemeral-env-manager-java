# Ephemeral Environment Manager（本地临时环境租约与回收编排）

一套**完全本地可运行**的临时环境租约管理服务：按需占用（acquire）、续用（renew）、
归还（release）隔离环境，并自动回收到期租约。不依赖任何外部服务、真实凭据或付费资源——
“隔离环境”由进程内的内存集合（`LocalResourceBackend`）模拟，重启状态通过本地 JSON 快照恢复。

## 快速开始

```bash
mvn spring-boot:run          # 启动服务（默认端口 8080）
mvn test                     # 运行全部测试（并发/竞争/重试/恢复）
```

## HTTP 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/leases` | 创建/占用环境，body：`{"name","owner","ttlMillis"}` |
| POST | `/api/leases/{name}/renew` | 续租，body：`{"owner","ttlMillis"}` |
| POST | `/api/leases/{name}/release` | 释放，body：`{"owner"}`（body 可省略） |
| GET  | `/api/leases/{name}` | 查询单个租约 |
| GET  | `/api/leases` | 列出全部租约 |
| GET  | `/api/leases/reclaiming` | **只读**列出所有「还没回收干净」的环境及回收进度 |
| POST | `/api/leases/{name}/reclaim-retry` | 手动触发该环境的一次回收重试（定点补救） |

`ttlMillis` 传 0 或缺省使用默认租期；超过 `ephemeral.max-ttl-millis` 以 `INVALID_TTL` 拒绝。

### curl 示例

```bash
curl -s -X POST localhost:8080/api/leases -H 'Content-Type: application/json' \
  -d '{"name":"env-1","owner":"alice","ttlMillis":60000}'
curl -s -X POST localhost:8080/api/leases/env-1/renew -H 'Content-Type: application/json' \
  -d '{"owner":"alice","ttlMillis":120000}'
curl -s -X POST localhost:8080/api/leases/env-1/release -H 'Content-Type: application/json' \
  -d '{"owner":"alice"}'
```

### 回收进度查询与手动重试

```bash
# 列出所有还没回收干净的环境（只读，不影响任何进行中的操作）
curl -s localhost:8080/api/leases/reclaiming
# 返回示例：
# [{"name":"env-9","owner":"bob","state":"RELEASING","phase":"RETRY_PENDING",
#   "releaseAttempts":3,"lastFailureReason":"injected persistent deallocation failure: env=env-9",
#   "stateEnteredAtMillis":...,"stuckMillis":12001}]

# 对某个环境手动触发一次回收重试
curl -s -X POST localhost:8080/api/leases/env-9/reclaim-retry
# 返回示例：{"outcome":"TRIGGERED","name":"env-9","state":"RELEASED","releaseAttempts":4}
```

进度查询每条记录的字段：

| 字段 | 含义 |
|------|------|
| `name` / `owner` | 环境名称 / 归属方 |
| `state` | 生命周期状态（EXPIRED / RELEASING / QUARANTINED） |
| `phase` | 回收阶段：`WAITING_RECLAIM`（等待回收）、`RECLAIMING`（回收进行中）、`RETRY_PENDING`（失败待重试）、`QUARANTINED`（已隔离） |
| `releaseAttempts` | 归还已失败重试的次数 |
| `lastFailureReason` | 最近一次归还失败的原因 |
| `stateEnteredAtMillis` / `stuckMillis` | 进入当前阶段的时刻 / 已停留时长 |

手动重试的 `outcome` 可区分四种结果：

| outcome | HTTP | 含义 |
|---------|------|------|
| `TRIGGERED` | 200 | 本次确实触发了一次归还尝试（看 `state` 判断是否成功） |
| `NOT_DUE` | 200 | 环境仍在有效租期内，未做任何动作 |
| `ALREADY_RELEASED` | 200 | 环境已归还干净，无需重试 |
| —（`NOT_FOUND`） | 404 | 环境不存在 |

同一环境被并发触发多次时，每环境锁保证串行：只有一个调用真正执行归还，
其余看到 `ALREADY_RELEASED`，不会产生重复归还副作用；不同环境之间互不干扰。

## 生命周期状态机

```
                 acquire 成功
   (不存在/已释放) ───────────▶ ACTIVE
                                   │
             ┌─────────────────────┼──────────────────────┐
             │ renew               │ 到期(expiresAt<=now)  │ release
             ▼                     ▼                       ▼
          ACTIVE               EXPIRED ──(回收扫描)──▶ RELEASING
      (expiresAt 更新)                                  │
                                  ┌──────────────────────┤
                                  ▼                      │ 停留超过
                            归还底层资源                  │ reclaim-stuck-
                          成功 │      │ 失败（保持        │ threshold
                              ▼      │ RELEASING，       ▼
                          RELEASED ◀─┘ attempts+1，   QUARANTINED
                              ▲        下轮重试）        │（摘除容量占用，
                              │                        │ 不再自动重试）
                              └──── 手动重试成功 ◀───────┘
```

- **ACTIVE**：在 `expiresAtMillis` 之前持有资源，可续租；到期后自动判定失效。
- **EXPIRED**：可解释的中间态。当续租请求与到期竞争失败时（锁内时钟判定 `expiresAtMillis <= now`），
  租约落入此态并以 `NOT_ACTIVE` 拒绝续租；后台扫描随即进入 `RELEASING`。
- **RELEASING**：正在归还资源。归还失败会停留在此态并累计 `releaseAttempts`、
  记录 `lastFailureReason`，等待重试；该进度会写入快照，服务重启后继续。
- **QUARANTINED**：卡死兜底态。在 `RELEASING` 停留超过
  `ephemeral.reclaim-stuck-threshold-millis`（默认 60s，<=0 关闭）后，后台扫描将其升级到此态，
  并通过 `evict` 把它从容量账目中摘除——正常创建不再被它冤枉地拒绝。
  该态不再被后台扫描自动重试，但会出现在进度查询中（`phase=QUARANTINED`），
  且可通过手动重试补救：重试会先迁回 `RELEASING` 再尝试归还，成功即 `RELEASED`。
  兜底只处理已在 `RELEASING` 的租约，**仍在有效租期内的 ACTIVE 环境绝不会被升级或提前回收**。
- **RELEASED**：终结态。资源已归还、容量已释放；重复 release 幂等；
  同归属方/不同归属方的语义见下；同名可在 RELEASED 后被原子重建。

判定基准统一为**可注入时钟** `Clock`：`expiresAtMillis > now` 才视为有效租期。
时钟回拨不会误回收有效租约（有单测覆盖时钟异常场景）。

## 并发与幂等语义

- **并发同名创建**：存储基于 `ConcurrentHashMap.putIfAbsent`，只有一个请求获胜。
  - 同归属方的重复请求 → 幂等返回同一租约（不重复占用资源）。
  - 不同归属方 → `NAME_CONFLICT`（409），底层资源不增加占用。
  - 已释放名称的并发重建 → 引用级 CAS（`replace(expected, replacement)`），只有一个重建成功。
- **并发续租 / 续租与到期竞争**：续租的“查状态→查归属→查到期→写新到期时间/版本”
  在 `ConcurrentHashMap.compute` 的 bin 锁内原子完成，因此无丢失更新；
  每次续租 `leaseVersion + 1`。是否到期的结论在锁内一次判定，确定无歧义。
- **重复 release**：已是 RELEASED 时幂等返回，不重复归还（归还接口本身也幂等）。

## 拒绝原因（可区分）

响应体统一为 `{"reason": "...", "message": "..."}`：

| reason | HTTP | 触发条件 |
|--------|------|----------|
| `NAME_CONFLICT` | 409 | 名称被其他归属方的未终结租约占用 |
| `QUOTA_EXCEEDED` | 503 | 已占用资源数达到容量上限 |
| `NOT_OWNER` | 403 | 续租/释放方与租约 owner 不一致 |
| `NOT_FOUND` | 404 | 租约名称不存在 |
| `ALREADY_RELEASED` | 409 | 对已 RELEASED 租约续租 |
| `NOT_ACTIVE` | 409 | 租约已过期/回收中（含续租竞争失败） |
| `INVALID_TTL` | 400 | TTL 超过最大允许值 |

## 配额与归属规则

- 容量由 `ephemeral.max-active-environments`（默认 4）控制。容量判定与占用在
  `LocalResourceBackend` 的同步块内原子完成，并发创建不会击穿配额；配额不足时不残留占用。
- 归属由创建时的 `owner` 标识；续租必须带相同 owner，释放默认校验 owner
  （运维侧省略 body 的释放不校验）。
- 单个环境归还失败**只影响该环境**：其他到期/释放的环境照常回收，新环境也能继续创建，
  失败环境在后续扫描中重试。

## 回收与恢复语义

- 后台调度器按 `ephemeral.reclaim-interval-millis`（默认 2s）调用一次全量回收扫描；
  release 也会立即触发一次回收，失败不抛给调用方，由后台兜底重试。
- 回收步骤幂等且可重试：状态迁移幂等、`deallocate` 幂等（重复/不存在均无副作用）；
  失败计数 `releaseAttempts`、最近失败原因 `lastFailureReason`、阶段进入时刻
  `stateEnteredAtMillis` 均可观察、可持久化。
- 同一环境的回收关键段由每环境锁串行（后台扫描与手动重试共用），
  并发触发不产生重复归还；不同环境之间不互相阻塞。
- 卡死兜底：每轮扫描检查 `RELEASING` 停留时长，超过
  `ephemeral.reclaim-stuck-threshold-millis` 即升级为 `QUARANTINED` 并 `evict` 摘除容量占用
  （`evict` 只动容量账目、幂等，不受归还故障影响）。
- 每次租约变更与每次回收结果都会写入本地快照文件（临时文件 + 原子 rename）。
- **重启恢复**（`RecoveryService`，启动时执行）：
  1. 读取快照（文件缺失/损坏按空状态启动，不阻断服务）；
  2. 对所有非 RELEASED、非 QUARANTINED 租约恢复底层资源占用，使容量视图与重启前一致
     （QUARANTINED 已被摘除容量，恢复后继续保持不占配额，重试进度原样恢复）；
  3. 立即跑一轮回收：到期租约、停留在 RELEASING 的租约继续完成归还；
  4. 恢复后状态完全可解释：有效 ACTIVE 保留，待回收进度续跑，隔离环境保持隔离可查可补救。

## 对原有生命周期与容量规则的兼容范围

- 创建/续租/释放/到期回收的状态机与拒绝原因（7 类 reason）完全不变；
  `QUARANTINED` 只是 `RELEASING` 的一个“不再自动重试 + 不占容量”的分支终态前的可逆升级。
- 容量规则不变：仍由 `max-active-environments` 控制、占用/判定原子完成；
  唯一变化是 QUARANTINED 环境不再计入容量账目（这正是兜底的目的）。
- 名称占用规则不变：QUARANTINED 名称仍被原归属方持有（同名重建需先重试到 RELEASED）。
- 续租/释放对 QUARANTINED 的行为沿用既有规则：续租得 `NOT_ACTIVE`；释放等价于再触发一次归还尝试。
- 旧版本快照可直接加载：缺失的新字段按 0/null 处理，停留时长退化为从创建时刻起算。

## 配置项（application.properties）

| 键 | 默认值 | 说明 |
|----|--------|------|
| `ephemeral.max-active-environments` | 4 | 同时存活环境容量上限 |
| `ephemeral.default-ttl-millis` | 60000 | ttlMillis<=0 时使用的默认租期 |
| `ephemeral.max-ttl-millis` | 3600000 | 单次续租/创建允许的最大租期 |
| `ephemeral.reclaim-interval-millis` | 2000 | 后台回收扫描间隔 |
| `ephemeral.reclaim-stuck-threshold-millis` | 60000 | RELEASING 停留超过该时长升级为 QUARANTINED 并摘除容量；<=0 关闭 |
| `ephemeral.snapshot-path` | `data/leases-snapshot.json` | 本地快照文件路径 |

## 测试与本地验证

```bash
mvn test
```

测试均为本地完成，打印 `[input]`（关键输入）与 `[judge]`（判定依据）：

- `LeaseServiceUnitTest`
  - 16 线程并发同名**同归属方**创建：单占用、单记录、全部幂等成功；
  - 12 线程并发同名**不同归属方**创建：恰 1 成功、其余全部 `NAME_CONFLICT`；
  - 配额超限 `QUOTA_EXCEEDED`，且资源无残留；
  - `NOT_OWNER` / `NOT_FOUND` / `ALREADY_RELEASED` 原因可区分；
  - 10 线程并发续租无丢失更新（version 精确累加），到期后续租竞争失败转 `EXPIRED`；
  - 时钟异常回拨不误回收有效租约。
- `ReclaimRetryTest`：注入一次性归还失败 → RELEASING + attempts 计数 + 资源不丢失；
  重试成功后 RELEASED、容量归还；重复回收无副作用；单环境失败不阻塞其他环境，且失败环境
  重试成功后新环境可立即创建。
- `ReclaimProgressQueryTest`：进度查询覆盖 EXPIRED/RELEASING/QUARANTINED 三阶段，
  字段（阶段/次数/失败原因/停留时长）逐一校验；2 线程回收并发 4 线程 x200 次查询，
  查询零异常、视图结构完整、不干扰回收结果；静止后 500 次查询前后全部租约字段逐位一致（只读性）。
- `ManualReclaimRetryTest`：16 线程并发重试同一环境 → 恰 1 次 TRIGGERED、归还调用恰 1 次、
  其余 ALREADY_RELEASED；NOT_FOUND / NOT_DUE / ALREADY_RELEASED 结果可区分且无副作用；
  两环境并发重试互不干扰（一个成功、一个持续失败各自独立计数）。
- `QuarantineCapacityTest`：RELEASING 停留超阈值 → QUARANTINED + 摘除容量，
  之前被 QUOTA_EXCEEDED 拒绝的创建随即成功；有效租期内的环境不被升级；
  QUARANTINED 不被后台扫描重试，手动重试可补救到 RELEASED。
- `QuarantineRecoveryTest`：进程 1 落盘 QUARANTINED（attempts=3 + 失败原因 + 进入时刻），
  进程 2 空内存恢复：隔离状态/重试进度/容量视图一致，恢复后可正常创建、可手动补救。
- `RestartRecoveryTest`：进程 1 落盘（含 RELEASING 失败计数与已到期未回收租约），
  进程 2 以空内存 + 同快照恢复：有效租约与占用恢复、待回收/到期租约立即回收、容量一致。
- `LeaseApiIntegrationTest`：REST 全链路，验证 7 类 reason 与 HTTP 状态码、幂等行为，
  以及 `/reclaiming` 进度查询与 `/reclaim-retry` 四种结果的 REST 表现。
- `ApplicationTests`：原有 Spring 上下文启动测试保持通过。
