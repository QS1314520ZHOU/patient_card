# 患者卡片改造说明

## 外部配置

数据库信息不再硬编码；项目不再连接 DataCenter。部署时修改 JAR 同级的：

```text
config/application.yml
```

配置包含：

- SmartCare MongoDB：`patient`、`score`、`deviceBind` 等集合
- VTE MySQL
- 端口、风险阈值、大屏概览参数

主要格式：

```yaml
server:
  port: 18088

mongo:
  host: 192.168.5.155
  port: 32121
  database: SmartCare
  username: dxm
  password: 请填写实际密码
  authSource: SmartCare
  authMechanism: SCRAM-SHA-256
  collections:
    patient: patient
    score: score
    deviceBind: deviceBind

```

换医院时只改这个文件，不需要重新改代码或重新打包。

## 科室概览 / 大交班大屏（新增）

页面：`/overview.html`（与患者卡片同 JAR 部署）。
需求基线：《重症医学科科室概览与大交班大屏｜需求文档 V1.0》P0 范围。

功能：

- postMessage 握手：页面发送 `SmartCareReady`（2s×3 重试），宿主回传
  `{type:'SmartCare', account, patient, token}`，取
  `deptCode = patient.deptCode || patient.deptCode2`；兼容 HOSPITAL_CONTEXT
  与旧式 `{deptCode}` 消息；`?deptCode=112` 仅供调试。
- **布局总览（1920×1080 第一屏完整展示主内容）**：顶栏+KPI 合计约屏高 9%——
  左=科室名 + 班次时段胶囊 + 数据更新时间，右=交接模式/自动轮播/刷新/全屏/导出PDF；
  KPI 为**单行紧凑横条**（在科 `26/31床(84%)`、空床 `5张 09,21,28,29,31` 直接列床号、
  呼吸机、跌倒高、压疮高、新收、待闭环危急值红点），右侧内嵌快速筛选
  （全部/有创通气/休克·升压药/极危重/新入科）。主区 75% = 医疗组折叠栏 + 床位网格
  （`minmax(298px,1fr)` 自适应列宽）；右侧 25% = 今日交班重点待办 + 床位联动速览。
- 统计下钻：KPI 条与底栏辅助条（管道/营养/VTE 高风险、未评估人数、转出/出院/死亡、
  手术/转入、重点关注、跨班承接与本窗已处置危急值）均可点击，弹出同快照名单，
  「命中依据 / 时间」列给出每条的判定口径。
- 患者按 `patient.medicalGroup` 分组展示，未分组独立成组；姓名按现场要求明文展示。
- 交班顺序切换：按责任组（默认）/ 按床号 / 按病情严重度（生命支持·休克·即刻危险优先）/
  按入科时间（新收优先）；占用/空床数显示在顶部 KPI 条，工具栏右侧在启用快速筛选时
  显示“筛选「××」· 命中 N / M 人”。
- **事件窗切换**：工具栏「上一班（默认）/ 最近 24 小时」。窗口起止由**后端**按班次配置计算
  （前端只传 `windowPreset`，不传本地时钟算出的时间，避免时钟偏差进统计）；顶栏显示
  `事件窗（口径）起 ~ 止`，口径另存 `eventWindowLabel`。自定义区间仍可用
  `eventFrom`/`eventTo` 显式传入（联调/回看用）。
- **交班视图**：工具栏「综合（默认）/ 医生 / 护理」，只换展示不换口径——
  - 综合：卡片次要行=住院号·责任护士，评分四项与跌倒/压疮/营养/VTE/管路标签全开；
  - 医生：次要行=住院号·责任医生·责任组·入科，保留 RASS·GCS·SOFA·APACHE，
    隐藏跌倒/压疮/营养标签，辅助条去掉未评估类；
  - 护理：次要行=住院号·责任护士·责任医生，保留 RASS·GCS 与出入量，
    隐藏 SOFA/APACHE，辅助条保留管道/营养/未评估。
  切视图**不重新请求接口**（指标与下钻名单三视图同源）。
- **本班变化**：工具栏「本班变化」按钮 + 条数徽标，弹窗表格逐条列出事件窗内的结构化变化
  （新收 / 离科 / 危急值新增 / 危急值闭环 / 评分变化 / 管路置入 / 支持接入·解除 / 微泵启用），
  列=时间/床号/姓名/事件/说明，按时间倒序；标题行显示
  `事件窗（口径）起 ~ 止 · N 条（全量 M 条，已按视图过滤）`；有归属患者的行可点开交班详情，
  离科行（无在科患者归属）不可点击。变化条目带 `scope`（both/doctor/nurse）随视图过滤，
  徽标计数同步（综合 16 / 医生 15 / 护理 12 为演示值）；单次最多 300 条，超出写入质量说明。
  评分只记**有变化**的（与上一次相同不上清单），避免小时级评估刷屏。
- **未闭环危急值跨班承接**：未闭环（`criticalValue.status` 非已闭环取值）**不受事件窗限制**，
  上一班没闭环的本班仍显示；已闭环只统计事件窗内的。角标 `危急值N待闭环（承M）`，
  悬浮列出每条的跨班承接时长与闭环时间；「今日交班重点」待办清单中待闭环项置顶（红色），
  按承接/时效排序，交班先督办。
  指标三项：`criticalPending`（未闭环，项）/ `criticalCarried`（跨班承接，项）/
  `criticalHandled`（本窗已处置，项），单位为“项”的另返 `affectedPatientCount`（涉及人数）。

- 空床卡：configBed 未占用床位渲染为**半高浅灰虚线卡**（`09床 (备用空床)`，背景 `#F8FAFC`），
  随网格排布不抢主视觉，便于现场调度。
- 卡片高频重症指标（来源见括号）：
  - 生命支持：呼吸机（deviceBind；固定呼吸机无绑定记录，另按 `bedside` 近
    `capture-window-hours` 小时有 `param_HuXiMoShi` 采集计入“呼吸机在用”）、
    CRRT（deviceBind type=crrt）、
    **高级脏器支持**（tubeExe type=ECMO/IABP 且 endTime 为空）、
    血管活性微泵（drugExe status=working + 药名关键词，如 去甲NE/垂体）→ 红色角标；
  - 三管留置天数（tubeExe，endTime 为空）：气道 ≥7d 黄 / ≥14d 红，CVC/PICC >14d 红，
    尿管 ≥7d 黄，对应 VAP/CRBSI/CAUTI 交班提醒；
  - 24h 出入量：`出入 ±Xml · 尿量 Xml`（bedside，码表读 configLiquidBalance，
    **窗口读 initSystemConfig.banCiInfoList 全天班 balanceStart/EndHour，取最近完整平衡窗，不写死**）；
  - 意识与评分生命线：RASS / GCS / SOFA(含↑↓趋势) / APACHE II(含趋势)（score 集合，取最新两次）；
  - 隔离胶囊（床号旁）：DataCenter `VI_ICU_ZYYZ` 隔离医嘱按 orderTime/orderName 判启停
    （`停*` 前缀或 status=已停止 为关闭）；接触=黄、空气=紫、保护性=蓝、飞沫=橙；
    医嘱名内嵌 MRSA/CRAB/CRKP/念珠菌/结核 等菌名时另打红色菌名胶囊；
  - 危急值（criticalValue）：未闭环**跨班承接**（不看窗口）/ 本窗已处置（看事件窗），
    区分“待闭环 N（含跨班承 M）”（红）与“已处置 N”（灰），交班只督办待闭环；
  - 夜间抢救（ZYYZ 除颤/心肺复苏/抢救医嘱，夜班窗）：卡片右上角红色闪烁“夜间抢救”；
  - UEX 高危自动标红：RASS +1~+2 且气管插管在用（提示检查约束与牙垫）；
  - 过敏红角标“敏”（patient.allergic），悬浮显示过敏源；
  - 长程角标：入科天数 ≥14 天（可配）显示橙色“长程”，提示晨会评估 MDT。
- 视觉分级（防告警疲劳）：红=即刻危险（血管活性/UEX/管路高危/危急值待闭环）、
  黄=关注重点（跌倒/压疮/VTE/营养高危）、蓝=设备与常规（呼吸机/CRRT/高级脏器支持/新术转）；
  “未评估”一律弱化为灰色小圆点，不再使用带框标签。
- **右侧联动抽屉（25% 宽）**：
  - 上半部「今日交班重点」Checklist：合并**系统预警**（外出检查、拔管计划、待闭环危急值、
    管路留置超期等）与后端 `manualTodos` 手工待办，带复选框与床号定位，计数实时显示；
  - 下半部「床位速览」Quick Inspector：悬浮或点击左侧床位卡即平滑切换为该患者的
    **24 小时出入量**（总入/总出/尿量/净平衡）与**最近一次动脉血气**（pH、PaO2、PaCO2、
    Lac、OI + 采集时间），「交班详情」按钮打开完整交班抽屉；
  - 60s 自动刷新（保持列表滚动位置）；交班模式停轮播；全屏；导出PDF 走浏览器打印；
    失败时保留最后数据并标记。
- 离线预览：`overview-demo.js` 仅在 `file://` 双击打开或追加 `?demo=1` 时加载
  （顶栏显示“演示数据”徽标）；经 http(s) 访问后端时该文件不参与渲染，可随部署删除。
  演示数据由 `backend/target/gen_demo.py` 生成（含 16 条事件窗内「本班变化」样本，
  与后端 ChangeItem 的 kind/label/detail/scope 口径一致）。

接口（对齐需求文档 9.3 的 P0 子集）：

```text
GET /api/v1/icu-overview?deptCode=&windowPreset=prevShift|last24h   # 快照（默认上一班）
GET /api/v1/icu-overview?deptCode=&eventFrom=&eventTo=              # 快照（自定义区间）
GET /api/v1/icu-overview/context/{deptCode}                         # 范围解析
GET /api/v1/icu-overview/snapshots/{id}/metrics/{key}/items         # 统计下钻
GET /api/v1/icu-overview/patients/{id}/handover?snapshotId=         # 单患者交班详情（必带 snapshotId）
```

`handover` 必须携带调用方持有的 `snapshotId`：快照即本次授权范围，患者不在该快照内返回 404，
快照过期返回 410——不允许用患者 id 跨科室取数。


大屏相关配置（`config/application.yml` 可覆盖）：

```yaml
overview:
  refresh-seconds: 60
  ventilator:
    unbind-field: unBindTime   # deviceBind 解绑时间字段，空=未解绑（本地库实际大小写）
    type-field: type           # 设备类型字段：deviceBind 含 vent/crrt/monitor
    type-values: vent          # 只统计呼吸机
    mode-codes: param_HuXiMoShi,param_vent_mode   # 呼吸机模式参数码（bedside.strVal）
    capture-window-hours: 24   # 固定呼吸机判定窗：bedside 近 N 小时有采集即计为在用
  departure:
    time-fields: icuDischargeTime,dischargeTime
risk:
  score-types:
    fall: patientFallDangerFactor  # 跌倒评估 scoreType；有 conclusion 字段时优先用院内结论分级
    pressure: pressureDamageScore,bradenScore,bradenNurseScore  # 按优先级取第一个有记录的
  fall:
    medium-min: 25             # 跌倒中危/高危阈值（按院内量表确认）
    high-min: 45
```

### 联调待确认清单（上线前）

1. ✅（本地库已核实）`deviceBind` 混有 vent/crrt/monitor，已按 `type=vent` 过滤；`pid` 对应
   patient `_id`（hex）；解绑字段实际为 `unBindTime`（大写 B）。
2. ✅（本地库已核实）跌倒评估使用 `patientFallDangerFactor`（另有历史 `murry`）；
   高危阈值 45 仍需院内按量表确认。
3. 离科字段语义：`dischargedType` 取值与转出/出院/死亡分类是否准确（本地库未见在科患者
   离科字段样本，需院内核对）。
4. 宿主 Origin 白名单（`overview.js` 顶部 `ALLOWED_ORIGINS`）；正式部署禁止留空。
5. SSO 后端鉴权未接入：deptCode 不是授权凭证，需按院内方案补会话校验。
6. 事件窗班次定义：当前默认业务日 08:00 起（Asia/Shanghai）。
7. 当前本地快照中在科患者 `medicalGroup` 多为空（分组依赖上游 medicalGroup 更新任务），
   页面按“未分组”展示属预期。
8. MDRO 菌名胶囊只取**隔离医嘱名内嵌**的菌名（如 “CRAB接触隔离”）；培养/药敏结果不在
   ZYYZ 医嘱里，若院内以检验报告为准需另接 DataCenter `VI_ICU_REPORT`。
9. 夜间抢救关键词含“抢救”，院内存在“危重病人小抢救”类计费医嘱，可能造成多计；
   按院内实际医嘱字典调整 `overview.rescue-keywords`。
10. `mongo.center.*` 指向 DataCenter 库（host/port/username/password 缺省跟随主库，
    `authSource` 缺省为 `mongo.center.database`）；DataCenter 与 SmartCare 不同实例或不同账号时
    在 `config/application.yml` 显式覆盖。`database` 留空则隔离与夜间抢救降级为质量说明中的“不可用”。
11. 过敏史读 `patient.allergic`（空值不显示）；本机快照该字段为空属正常。
12. SOFA/APACHE/RASS/GCS 显示源系统原值（本地数据存在 GCS=2 等非临床常规值，属源数据质量问题）。
13. **危急值闭环判定字段**：当前只认源系统 `criticalValue.status`（true/True/1/已处理 = 已闭环），
    `handleTime` 仅作展示与“闭环变化”的时间依据，不参与改判。本地快照中 142 条 `status=false`
    记录都带有 `handleTime`（集中在 2026-08-31），语义是“未闭环”还是“已处置未审核”需院内确认；
    若以 `handleTime` 为准，改动点只有 `CardDetailService.isCriticalHandled`。
14. 医生/护理视图的字段取舍（卡片次要行 meta、评分与标签 vitals/tags、辅助条 aux）
    为按需求角色职责的默认裁剪，院内可直接改 `overview.js` 顶部 `VIEW_CONFIG`；
    三视图共用同一份指标与下钻名单。
15. 交班详情的「下一班行动清单」目前只列未闭环危急值 + 线下待办说明；
    待办数据源接入（P1）后应在此合并结构化待办。


## 四道关验证（上线前必跑）

`backend/target/verify.py` 对运行中的服务做**独立重算**核对（不复用后端代码，直接查源库）：

```powershell
# 1) 连本机快照库启动（仅测试环境）
.\backend\target\dev-start.ps1
# 2) 跑四道关
python -X utf8 backend\target\verify.py
```

| 关 | 核对内容 |
| --- | --- |
| ① 安全与患者身份 | 交班详情无 snapshotId → 400；伪造快照 → 410；跨科室患者 id → 404；快照患者姓名/住院号/hisPid 与源库逐条一致、无越科；下钻名单 ⊆ 本快照 |
| ② 统计可信 | 在科/呼吸机/跌倒高/未评估独立重算；未闭环·跨班承接·本窗已处置与源库对账；每个可下钻指标 = 名单条数（“人”按人数、“项”按条数+涉及人数）；变化清单每条落在窗内并逐类与源库对账 |
| ③ 交班流程 | 三种窗口径与标签正确、最近24h 恰为 24h；当前状态指标不随窗口漂移；未闭环在任意窗口可见；交班详情含危急值/本班变化/下一班行动清单 |
| ④ 性能 | 快照接口均值 < 3s、报文 < 512KB、交班详情 < 1s |

当前结果：**47 项全部 PASS**（本机快照库 deptCode=3439）。

## 已完成

- `postMessage` 接收 `deptCode`，移除科室下拉选择。
- 患者固定按 `deptCode` 和 `status=admitted` 查询。
- `patient`、`score`、`deviceBind` 集合名称可以配置。
- 增加“新、术、VTE、管、疮、养、转”标识。
- “新”按 Asia/Shanghai 时区每日 08:00 至次日 08:00 判断。
- 评分按患者 `_id` 关联，筛选 `valid=true`，按 `time` 取最新。
- VTE按 `patient.hisPid = view_his_vte.patient_guid` 关联并取最新。
- 前端已放入后端静态资源，最终只部署一个Spring Boot JAR和一个YML。
- 新增大交班大屏 `/overview.html` 与 `/api/v1/icu-overview` 快照接口。
- 未闭环危急值跨班承接 + 事件窗切换（上一班/最近24小时）+ 医生/护理/综合视图 + 本班变化清单。
- 交班详情改为快照范围授权（必带 snapshotId），新增危急值/本班变化/下一班行动清单三块。
- 前端患者字段全部经 `esc()` 转义后再进 innerHTML；接口注入载荷验证不执行。
- 自动刷新保持列表滚动位置，不再把交班讲解视角拽回顶部。
- 大屏前端重构为浅色紧凑交班工作台：顶栏+KPI 单行横条（约屏高 9%）、空床半高虚线卡、
  右侧「今日交班重点待办 + 床位速览」双面板、「本班变化」工具栏按钮弹窗；
  评分变化/离科/危急值等 8 类事件的演示数据与后端 ChangeItem 口径逐字段对齐。
- Lombok 升级 1.18.42 并显式声明注解处理器，支持在 JDK 15–25 下构建。

## 构建与部署

要求 JDK 15+（本机以 JDK 25 验证编译，Lombok 配置兼容 JDK 15）、Maven 3.8+：

```bash
cd backend
chmod +x build.sh
./build.sh
```

构建完成后，将整个 `target` 目录复制到服务器。先修改：

```text
target/config/application.yml
```

然后启动：

```bash
cd target
./start.sh
```

`start.sh` 会明确读取同级 `config/application.yml`，因此从其他目录调用也不会丢失配置。

## postMessage

```js
iframe.contentWindow.postMessage({ deptCode: '112' }, targetOrigin);
```

后端固定查询：

```javascript
{ deptCode: '112', status: 'admitted' }
```
