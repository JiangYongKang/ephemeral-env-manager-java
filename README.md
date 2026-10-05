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
| GET  | `/api/leases/reclaim-pending` | **只读**查询所有“还没回收干净”的环境进度 |
| POST | `/api/leases/{name}/reclaim-retry` | 手动触发一次回收重试（定点补救） |

`ttlMillis` 传 0 或缺省使用默认租期；超过 `ephemeral.max-ttl-millis` 以 `INVALID_TTL` 拒绝。

### curl 示例

```bash
curl -s -X POST localhost:8080/api/leases -H 'Content-Type: application/json' \
  -d '{"name":"env-1","owner":"alice","ttlMillis":60000}'
curl -s -X POST localhost:8080/api/leases/env-1/renew -H 'Content-Type: application/json' \
  -d '{"owner":"alice","ttlMillis":120000}'
curl -s -X POST localhost:8080/api/leases/env-1/release -H 'Content-Type: application/json' \
  -d '{"owner":"alice"}'
# 查看所有还没回收干净的环境（阶段/重试次数/最近失败原因/停留时长）
curl -s localhost:8080/api/leases/reclaim-pending
# 对某个环境手动催一次回收
curl -s -X POST localhost:8080/api/leases/env-1/reclaim-retry
```

## 回收进度查询（只读）

`GET /api/leases/reclaim-pending` 返回当前所有“还没回收干净”的环境，每条包含：

```json
[{
  "name": "env-1",
  "owner": "alice",
  "stage": "RELEASING",
  "releaseAttempts": 2,
  "lastReclaimError": "injected transient deallocation failure: env=env-1",
  "millisInStage": 5300
}]
```

- **覆盖范围**：等待回收（`ACTIVE` 已到期未被扫描 / `EXPIRED`）、回收进行中（`RELEASING`）、
  回收失败等待重试（`RELEASING` 且 `releaseAttempts>0`）、已升级兜底（`RECLAIM_STUCK`）。
  有效租期内的 `ACTIVE` 与已终结的 `RELEASED` 不会出现。
- **只读与非阻塞**：实现只基于存储的弱一致快照做纯计算，不取任何写锁、不改任何状态；
  即使某个环境的归还调用卡住，查询、创建、续租与其他环境的回收都不被阻塞
  （`ReclaimProgressQueryTest` 用闩锁卡住归还调用的场景验证了这一点）。

## 手动回收重试

`POST /api/leases/{name}/reclaim-retry` 对单个环境定点催一次回收，响应体通过
`outcome` 字段明确区分五种结果（HTTP 均为 200）：

| outcome | 含义 |
|---------|------|
| `RETRY_SUCCEEDED` | 本次确实触发了重试且归还完成（进入 RELEASED） |
| `RETRY_FAILED` | 本次确实触发了重试但仍失败，`detail` 带最近失败原因 |
| `ALREADY_RELEASED` | 已归还干净，本次未产生任何归还动作 |
| `NOT_FOUND` | 环境不存在 |
| `NOT_IN_RECLAIM` | 不在回收阶段（如 ACTIVE 未到期），本次未产生归还动作 |

并发语义：同一环境的回收动作按名串行（per-name 锁）。同一环境被并发触发多次时，
只有真正执行归还的那次计为 `RETRY_SUCCEEDED`，其余看到 `ALREADY_RELEASED`，
**归还副作用只发生一次**；不同环境之间完全并行，互不干扰（含与后台扫描的竞争）。

## 卡死兜底（RECLAIM_STUCK）

回收长期失败的环境不能无限期占着容量。触发条件（满足其一即升级，均可配置）：

- 回收失败次数达到 `ephemeral.reclaim-stuck-after-attempts`（默认 5）；
- 在 `RELEASING` 停留时长达到 `ephemeral.reclaim-stuck-after-millis`（默认 300000）。

升级规则（升级动作会打 WARN 日志，含触发原因）：

1. 状态从 `RELEASING` 升级为 **`RECLAIM_STUCK`**（可区分、可识别、可追溯：
   进度查询可见，重试次数与最近失败原因持续累计并持久化）；
2. 同时把该环境**从容量账目中摘除**（`releaseAccounting`，只动账目、不执行真实归还），
   正常创建随即能拿到本该空出的容量，不再被卡住的环境冤枉拒绝；
3. 真实归还义务不丢：后台扫描与手动重试仍会继续尝试，成功后进入 `RELEASED`。

安全边界：兜底只在**回收失败路径**上触发，只作用于已到期/已释放进入回收流程的环境；
**仍在有效租期内的 ACTIVE 环境绝不会被这套兜底提前回收或摘除**（有单测覆盖）。

重启恢复：`RECLAIM_STUCK` 状态、`releaseAttempts`、`lastReclaimError`、阶段进入时间
全部随快照持久化；重启后照旧恢复，且已摘除容量的环境**不会回补占用**，配额账目保持一致。

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
                                  ┌──────────────────────┘
                                  ▼
                            归还底层资源
                          成功 │      │ 失败（保持 RELEASING，
                              ▼      │  attempts+1，下轮重试）
                          RELEASED   │      │ 失败次数/停留时长超阈值
                              ▲      ▼      ▼
                              │   RECLAIM_STUCK（升级兜底：
                              └──重试成功── 已从容量账目摘除，
                                 后台/手动重试继续直至归还）
```

- **ACTIVE**：在 `expiresAtMillis` 之前持有资源，可续租；到期后自动判定失效。
- **EXPIRED**：可解释的中间态。当续租请求与到期竞争失败时（锁内时钟判定 `expiresAtMillis <= now`），
  租约落入此态并以 `NOT_ACTIVE` 拒绝续租；后台扫描随即进入 `RELEASING`。
- **RELEASING**：正在归还资源。归还失败会停留在此态并累计 `releaseAttempts`，等待重试；
  该进度会写入快照，服务重启后继续。
- **RECLAIM_STUCK**：卡死兜底升级态（见上节）。已从容量账目摘除，不再阻挡新创建；
  后台扫描与手动重试仍会继续归还，成功后进入 RELEASED。
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
  失败计数 `releaseAttempts` 可观察、可持久化。
- 每次租约变更与每次回收结果都会写入本地快照文件（临时文件 + 原子 rename）。
- **重启恢复**（`RecoveryService`，启动时执行）：
  1. 读取快照（文件缺失/损坏按空状态启动，不阻断服务）；
  2. 对所有非 RELEASED 且非 RECLAIM_STUCK 的租约恢复底层资源占用，使容量视图与重启前一致
     （RECLAIM_STUCK 已摘除容量，不回补）；旧快照缺失的进度时间戳按恢复时刻归一化；
  3. 立即跑一轮回收：到期租约、停留在 RELEASING / RECLAIM_STUCK 的租约继续完成归还；
  4. 恢复后状态完全可解释：有效 ACTIVE 保留，待回收进度（次数/失败原因/升级结果）续跑。

## 配置项（application.properties）

| 键 | 默认值 | 说明 |
|----|--------|------|
| `ephemeral.max-active-environments` | 4 | 同时存活环境容量上限 |
| `ephemeral.default-ttl-millis` | 60000 | ttlMillis<=0 时使用的默认租期 |
| `ephemeral.max-ttl-millis` | 3600000 | 单次续租/创建允许的最大租期 |
| `ephemeral.reclaim-interval-millis` | 2000 | 后台回收扫描间隔 |
| `ephemeral.snapshot-path` | `data/leases-snapshot.json` | 本地快照文件路径 |
| `ephemeral.reclaim-stuck-after-attempts` | 5 | 回收失败达到该次数升级为 RECLAIM_STUCK |
| `ephemeral.reclaim-stuck-after-millis` | 300000 | 在 RELEASING 停留超过该时长升级为 RECLAIM_STUCK |

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
- `RestartRecoveryTest`：进程 1 落盘（含 RELEASING 失败计数与已到期未回收租约），
  进程 2 以空内存 + 同快照恢复：有效租约与占用恢复、待回收/到期租约立即回收、容量一致。
- `ReclaimProgressQueryTest`：进度查询覆盖等待回收/回收中/失败待重试/已升级四类阶段，
  字段（归属/次数/最近失败原因/停留时长）正确；用闩锁卡住归还调用验证查询只读、
  不阻塞创建/续租/回收，且 100 次连续查询前后状态完全一致。
- `ManualReclaimRetryTest`：五种 outcome 可区分（含有效租期内环境不被提前回收）；
  16 线程并发手动重试同一环境：归还副作用恰好一次、仅 1 次 RETRY_SUCCEEDED、
  其余 ALREADY_RELEASED；与后台扫描并发竞争同一环境仍不重复归还、不干扰其他环境。
- `StuckEscalationTest`：失败次数/停留时长两类阈值触发升级 RECLAIM_STUCK；
  升级后容量释放、正常创建立即成功；有效租期内环境不受影响；故障恢复后重试至 RELEASED。
- `StuckRecoveryTest`：RECLAIM_STUCK 状态、重试次数、最近失败原因、阶段进入时间
  随快照重启恢复；已摘除容量不回补，恢复后新创建立即可用，故障恢复后终态一致。
- `LeaseApiIntegrationTest`：REST 全链路，验证 6 类 reason 与 HTTP 状态码、幂等行为，
  以及 `reclaim-pending` / `reclaim-retry` 两个新端点。
- `ApplicationTests`：原有 Spring 上下文启动测试保持通过。
