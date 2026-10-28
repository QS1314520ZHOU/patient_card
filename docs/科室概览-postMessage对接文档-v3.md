# 科室概览 / 病区概览 · 宿主 postMessage 对接文档 v3

> 适用对象：重症宿主系统（SmartCare）与患者卡片 / 科室概览（patient-card，部署于 18088）  
> 通信方式：`window.postMessage`（不是 postmassage）  
> 版本：基于现场联调与宿主源码梳理，2026-10-28

---

## 一、通信总览

宿主通过 **iframe** 加载科室概览（病区概览）页面。概览加载完成后先通知宿主「我已就绪」；宿主回传当前账号、当前患者与 token。概览点击患者卡时，反过来通知宿主选中患者并跳转到指定模块（如病情概览）。

| 方向 | 消息 type | 含义 |
|------|-----------|------|
| 概览 iframe → 宿主 | `SmartCareReady` | 握手：我已就绪，请下发当前上下文 |
| 宿主 → 概览 iframe | `SmartCare` | 下发账号、当前患者、token |
| 概览 iframe → 宿主 | `select-patient` | 选中指定患者（等价于在线病人列表双击） |
| 概览 iframe → 宿主 | `SmartCareNavigate` | 跳转到指定模块页签（不换患者时也可单独使用） |

**注意：** 正确名称是 `postMessage`，不是 `postmassage`。

---

## 二、消息结构（IframeData）

宿主侧统一接收结构：

```ts
export interface IframeData {
  type: string;      // 消息类型，必填
  patientId?: string; // 患者 id，select-patient 时必填
  moduleId?: string;  // 模块 id，用于跳转到指定菜单，如 doctor-diagnosis-patient-review
}
```

常用 `moduleId`：

| 模块 | moduleId | 路由 hash（仅供理解） |
|------|----------|------------------------|
| 病情概览 | `doctor-diagnosis-patient-review` | `#/main/doctor/diagnosis-analysis/patient-review` |
| 执行用药 | 见宿主 `patientSelectMenu`（护士默认） | `#/main/nurse/order-manage/execute-drug` |

> 现场若以「菜单 id」配置自定义 iframe，请以宿主 `getMenuByModuleId` 实际返回为准。

---

## 三、握手：概览 → 宿主（SmartCareReady）

概览 iframe 加载完成后发送：

```js
window.parent.postMessage({ type: 'SmartCareReady' }, '*');
// 正式环境建议改为明确宿主来源：
// window.parent.postMessage({ type: 'SmartCareReady' }, 'http://宿主系统地址');
```

当前实现为兼容多环境，可能使用 `'*'` 作为 `targetOrigin`。正式部署建议改为明确宿主 Origin。

---

## 四、宿主 → 概览（SmartCare）

宿主收到 `SmartCareReady` 后回传：

```js
{
  type: 'SmartCare',
  account: /* 当前登录账号 */,
  patient: /* 当前患者对象，可为 null */,
  token: /* 当前登录令牌 */
}
```

宿主 Angular 参考（与打印程序同源逻辑）：

```ts
private onHostMessage = (e: MessageEvent) => {
  if (e?.data?.type === 'SmartCareReady' && e.source) {
    const patient = this.system.currentPatient;
    (e.source as Window).postMessage({
      type: 'SmartCare',
      account: this.system.currentAccount,
      patient,
      token: this.system.token,
    }, '*');
  }
};
```

患者切换后，宿主应再次向 iframe 发送最新 `SmartCare`，避免概览使用旧患者上下文。

### patient 关键字段（与打印程序一致）

| 含义 | 首选字段 | 备用字段 |
|------|----------|----------|
| 姓名 | `name` | |
| 性别 | `genderStr`（男/女） | `gender`（Male/Female/1/2/M/F） |
| 科室名 | `dept` | |
| 科室编码 | `deptCode` | `deptCode2` |
| 床号 | `showBed` | `hisBed` |
| 住院号 | `mrn` | |
| HIS 患者号 | `hisPid` | |
| 住院次数 | `hospitalTime` | |
| **患者 id（选人用）** | `id`（Mongo ObjectId） | |

推荐读取：

```js
const deptCode = patient.deptCode || patient.deptCode2 || '';
const bed = patient.showBed || patient.hisBed || '';
const patientId = patient.id; // select-patient 使用
```

---

## 五、概览 → 宿主：选中患者（select-patient）

点击患者卡时，概览通知宿主选中患者。宿主行为与 **在线病人列表双击** 一致：

1. 按 `patientId` 查询完整患者信息  
2. 设置 `currentPatient`  
3. 打开该角色默认患者页签  
   - **护士**：默认 `执行用药`  
   - **医生等其他角色**：默认 `病情概览`

示例：

```js
window.parent.postMessage(
  {
    type: 'select-patient',
    patientId: '6aa54f284978ea2dd8c0ec0c',
  },
  '*'
);
```

宿主侧处理逻辑（与现场沟通一致）：

```ts
case 'select-patient':
  this.handleSelectPatient(data);
  break;

handleSelectPatient(data) {
  if (!data.patientId) return;
  this.mainService.choosePatient(data.patientId, (patient) => {
    this.mainService.setPatient(patient, false);
    this.system.currentPatient = patient;
    this.system.$tabPageChange.next({
      type: 'add',
      menu: this.system.patientSelectMenu, // 护士 → 执行用药
    });
  });
}
```

---

## 六、概览 → 宿主：跳转模块（SmartCareNavigate）

当需要**指定打开某个模块页签**（而不依赖角色默认页）时，发送：

```js
window.parent.postMessage(
  {
    type: 'SmartCareNavigate',
    moduleId: 'doctor-diagnosis-patient-review',
  },
  '*'
);
```

宿主侧：

```ts
case 'SmartCareNavigate':
  if (data.moduleId) this.remindNavigate(data.moduleId);
  break;

remindNavigate(moduleId: string) {
  const menu = this.system.getMenuByModuleId(moduleId);
  if (menu) this.mainService.navigateTab(menu);
}
```

**说明：**

- `SmartCareNavigate` **只跳模块，不改当前患者**。  
- 若要「先换患者，再打开病情概览」，应先发 `select-patient`，再发 `SmartCareNavigate`。  
- 若只需跳转、不换患者，可只发 `SmartCareNavigate`。

---

## 七、点击卡片推荐时序（换患者 + 打开病情概览）

```js
function openPatientReview(patientId) {
  // 1) 选中患者（护士默认会打开执行用药页签）
  window.parent.postMessage(
    { type: 'select-patient', patientId },
    '*'
  );

  // 2) 再打开病情概览新页签
  window.parent.postMessage(
    {
      type: 'SmartCareNavigate',
      moduleId: 'doctor-diagnosis-patient-review',
    },
    '*'
  );
}
```

也可将两条合并为一条（若宿主 `select-patient` 已支持 `moduleId` 则优先走模块；否则仍走角色默认页，需再补第二条）：

```js
window.parent.postMessage(
  {
    type: 'select-patient',
    patientId,
    moduleId: 'doctor-diagnosis-patient-review',
  },
  '*'
);
```

**已知行为：** 护士角色下 `select-patient` 会打开「执行用药」；紧接着 `SmartCareNavigate` 会新增/激活「病情概览」页签。两页签可并存，用户可点击页签切换。

---

## 八、宿主 Angular 参考实现（合并）

```ts
export class IframeViewComponent implements OnInit, OnDestroy {
  private readonly overviewOrigin = 'http://192.168.31.167:18088'; // 按现场修改

  private onHostMessage = (e: MessageEvent) => {
    // 正式环境建议校验来源
    // if (e.origin !== this.overviewOrigin) return;

    const data = e.data as {
      type?: string;
      patientId?: string;
      moduleId?: string;
    } | null;

    if (!data?.type) return;

    switch (data.type) {
      case 'SmartCareReady':
        if (e.source) {
          (e.source as Window).postMessage(
            {
              type: 'SmartCare',
              account: this.system.currentAccount,
              patient: this.system.currentPatient,
              token: this.system.token,
            },
            '*'
          );
        }
        break;

      case 'select-patient':
        if (data.patientId) {
          this.mainService.choosePatient(data.patientId, (patient) => {
            this.mainService.setPatient(patient, false);
            this.system.currentPatient = patient;
            this.system.$tabPageChange.next({
              type: 'add',
              menu: this.system.patientSelectMenu,
            });
          });
        }
        break;

      case 'SmartCareNavigate':
        if (data.moduleId) {
          const menu = this.system.getMenuByModuleId(data.moduleId);
          if (menu) this.mainService.navigateTab(menu);
        }
        break;
    }
  };

  ngOnInit(): void {
    window.addEventListener('message', this.onHostMessage);
  }

  ngOnDestroy(): void {
    window.removeEventListener('message', this.onHostMessage);
  }
}
```

---

## 九、概览侧接收 SmartCare 示例

```js
window.addEventListener('message', (e) => {
  // 正式环境：if (e.origin !== 'http://宿主系统地址') return;

  if (e?.data?.type !== 'SmartCare') return;

  const patient = e.data.patient || {};
  const deptCode = patient.deptCode || patient.deptCode2 || '';
  const bed = patient.showBed || patient.hisBed || '';

  // 用于加载科室数据
  if (deptCode) loadByDeptCode(deptCode);
});
```

---

## 十、样式与业务约定（概览页）

### 1. 卡片头部颜色

- 背景色：`#5392FF`  
- 文字：白色  

### 2. 跌倒高危标识

- 文字/描边：`#E53935`  
- 背景：`#FEE2E2`  

### 3. 隔离医嘱标识颜色（标准色）

| 类型 | 十六进制 | 说明 |
|------|----------|------|
| 空气隔离 | `#FFD60A` | 黄 |
| 飞沫隔离 | `#F48FB1` | 粉 |
| 接触隔离 | `#4292C6` | 蓝 |
| 保护隔离 | 紫色系 | 与前三者区分即可 |

隔离数据来源（仅此一处，与患者基本信息无关）：

- **DataCenter 库 `VI_ICU_ZYYZ` 医嘱表**  
- 条件：`orderName` 正则匹配「隔离」  
- 规则：按 `orderTime` 倒序，按类型（空气/飞沫/接触/保护等）取最新事件；医嘱名以「停」开头或状态为已停止则该类型关闭，否则生效  
- **不读取** SmartCare `patient` 集合里的 `isolation` 等基本信息字段  
- 若 `mongo.center.database` 为空或连不上 DataCenter，隔离角标显示为不可用，不得用 0/无掩盖

### 4. 入科天数

- 口径：**自然日、含当天**  
- 当天入科 = **1 天**（不允许显示 0 天）  

### 5. 医疗组筛选排序

- 顺序：一组 → 二组 → 三组 → … → 未分组（垫底）  
- 不可用 `localeCompare('zh')`（会按拼音把「二/三」排在「一」前）

---

## 十一、联调检查

1. 打开宿主并登录，F12 → Console。  
2. 执行：

```js
window.addEventListener('message', (e) => {
  console.log('postMessage 来源:', e.origin);
  console.log('postMessage 数据:', e.data);
});
```

3. 打开「病人 → 病人信息 → 科室概览」。  
4. 应依次看到：  
   - `SmartCareReady`（概览发出）  
   - `SmartCare`（宿主回传，含 patient）  
5. 点击任意患者卡，应看到：  
   - `select-patient`（含 patientId）  
   - `SmartCareNavigate`（moduleId = doctor-diagnosis-patient-review）  
6. 宿主出现「病情概览」页签，且患者为所点卡片患者。  
7. 检查卡片：入科天数、跌倒红标、隔离色、医疗组顺序是否符合上节约定。

---

## 十二、安全要求

1. 正式环境不要长期使用 `'*'` 作为 `targetOrigin`。  
2. 宿主与概览收消息时均应校验 `e.origin`。  
3. 不要在控制台打印完整 `token`。  
4. `patient` 仅用于当前业务查询，不要写入浏览器长期存储。  
5. 切换患者后宿主应重发最新 `SmartCare`，概览应按最新患者刷新列表。

---

## 十三、部署说明

| 组件 | 说明 |
|------|------|
| 概览服务 | Spring Boot JAR，默认端口 `18088`，静态页 `/`（病区概览）与 `/overview.html`（交班大屏） |
| 配置 | JAR 同级 `config/application.yml`（Mongo / DataCenter / 端口等） |
| 宿主菜单 | 病人 → 病人信息 → 科室概览，iframe 地址指向概览服务根路径 |
| 资源缓存 | 更换 JAR 后宿主浏览器需 **Ctrl+F5** 强刷，避免旧 `patient-card.js` 缓存 |

---

## 十四、变更记录

| 日期 | 说明 |
|------|------|
| 2026-10-28 | v3：在打印程序 postMessage 文档体例上，补齐科室概览选人/跳转协议、样式与入科天数等业务约定 |
