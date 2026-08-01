# N51 小程序分级探索分类契约规格

## 目标

为微信与支付宝小程序探索报告建立严格、离线、机器可读的分类契约。报告必须绑定宿主
package/version、页面身份和样例身份，并从受控探针内容重算
`full-semantic`、`hybrid`、`visual-only`、`unsupported` 四级结论与统计。

本 change spec 只交付分类 Schema、validator、测试和使用说明，不执行设备操作，不进入
真实小程序，也不产生 N51 真实宿主验收证据。

## 报告边界

- 真实宿主仅允许微信 `com.tencent.mm` 与支付宝 `com.eg.android.AlipayGphone`。
- N42/N43 的 `dev.aiauto.webfixture` 只能声明为 `fixture`，并携带固定 fixture 来源；
  fixture 可以验证契约，但不能计入微信或支付宝真实样例与真实分类统计。
- 每个探针重复绑定 sample、宿主 package/version、page 和页面 fingerprint；任一绑定
  漂移都失败关闭。
- 动作只允许记录 `semantic-click`、`semantic-input`、`semantic-scroll` 和
  `visual-candidate` 四种探针类别，不保存输入、selector、坐标、命令或动作参数。
- hierarchy 与 screenshot 只保存有界计数、尺寸、截断状态和 SHA-256；原始内容、
  原始文件、路径和自由文本均不得进入报告。

## 分类规则

1. 页面未知、任一低置信度探针、宿主/页面/sample 绑定漂移或 fingerprint 漂移时，
   样例必须分类为 `unsupported`，并输出排序后的固定 reason code。
2. 低置信度探针的 `actionCommits` 必须为零；未验证、阻塞或不确定探针也不得声明提交。
3. 三种语义探针均高置信度验证成功时为 `full-semantic`。
4. 一至两种语义探针与视觉候选均高置信度验证成功时为 `hybrid`。
5. 没有语义探针验证成功、但视觉候选高置信度验证成功时为 `visual-only`。
6. 其余组合均为 `unsupported`。

validator 必须忽略报告自带结论作为事实来源，从探针重算每个样例的 decision，再从样例
重算总数、fixture 数、真实宿主数、四级总统计、真实四级统计与失败关闭数量。

## 安全边界

- 不运行设备、ADB、宿主 App、N47 Runner 或任何动作端口。
- 不下载 APK，不登录账号，不搜索或猜测 deep link，不调用宿主私有调试接口。
- 不注入 JavaScript，不抓取私有 DOM，不持久化 hierarchy/screenshot 原始内容。
- Schema 和 validator 拒绝 unknown keys、自由文本 secret、路径、URL、任意动作与
  非固定 reason code。
- 畸形输入只返回稳定校验错误，不回显原始值或异常文本。

## 允许修改

- `.trae/specs/n51-miniapp-classification-contracts/`
- `test-lab/scenarios/miniapps/`

## 禁止修改

- `docs/next-phase-tasks.md` 与 N45/N47/N52
- 共享 Runner Schema、Android/Go 生产代码、现有 APK manifest、SDK/cache
- 真实设备、宿主数据、下载制品与账号状态

## 验收

- 先以缺失实现运行 RED 测试，再实现 Schema 与 validator 进入 GREEN。
- Node 定向测试覆盖四级分类、统计重算、fixture 排除、低置信度、未知页面、身份漂移、
  摘要限额、unknown keys、secret、路径和任意动作。
- 专用 schema-check 递归检查所有 object 均为严格对象，并运行正反例。
- `make comments` 与 `git diff --check` 通过；方便时运行 N47/N52 现有离线 smoke。
- 微信/支付宝真实小程序样例与每类真实证据保持未验收，N51 不得勾选。
