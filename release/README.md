# YiyangzaiCQM · 代码质量监控平台

基于 **Java 字节码增强技术**的代码质量监控平台。

以一个 Java Agent 探针在类加载阶段对目标类做方法级插桩，在**不修改业务源码、不重新编译、
不重启应用**的前提下采集方法调用次数、执行耗时与异常抛出等运行期数据；
同时结合静态代码分析（圈复杂度、方法规模、异常处理与资源管理规范性），
按可配置规则检测违规并计算项目质量得分。

- 项目主页：<https://yiyangzai.cn/cqm/>
- 在线演示：<https://yiyangzai.cn/cqm/>（实时数据，非截图）

---

## 一、环境要求

| 项 | 要求 |
|---|---|
| JDK | **8 及以上**均可运行（本包内的 class 以 Java 17 编译，如需在 JDK 8 上运行请自行用 `build` 脚本重新编译） |
| 依赖 | 无。已内置 Byte Buddy 与 H2 数据库驱动 |
| 构建工具 | 可选。已提供预编译产物，直接用即可；重新编译只需 JDK 自带的 `javac` |

---

## 二、快速开始（3 步）

### 第 1 步：启动服务端

```bash
# Linux / macOS
cd server && ./start.sh 8080

# Windows
cd server && start.bat 8080
```

浏览器打开 <http://127.0.0.1:8080/>，即可看到监控页面（此时还没有数据）。

### 第 2 步：给目标应用挂载探针

在启动目标 Java 应用时加上 `-javaagent` 参数即可，**业务代码零改动**：

```bash
java -javaagent:/path/to/agent/cqm-agent.jar \
     -Dcqm.packages=com.yourcompany \
     -Dcqm.server=http://127.0.0.1:8080 \
     -Dcqm.app=your-app \
     -jar your-app.jar
```

### 第 3 步：观察数据

访问 <http://127.0.0.1:8080/>，页面每 4 秒自动刷新，
即可看到方法级调用次数、平均/最大耗时、异常数与异常率。

> 想先看效果？本包内含示例应用，直接运行：
> ```bash
> # Linux / macOS
> ./run-demo.sh
> # Windows
> run-demo.bat
> ```

---

## 三、探针配置项（全部通过 `-D` 注入）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `cqm.packages` | `com.demo` | **需要插桩的包前缀**，逗号分隔。填你的业务包名 |
| `cqm.exclude` | `com.cqm,net.bytebuddy,java.,javax.,sun.,jdk.,com.sun.` | 排除的包前缀，优先级高于包含 |
| `cqm.server` | `http://127.0.0.1:8080` | 服务端地址 |
| `cqm.interval` | `5000` | 上报周期（毫秒） |
| `cqm.sample` | `1.0` | 采样率 0~1。高流量场景可下调以降低开销 |
| `cqm.app` | `demo-app` | 应用名，显示在监控页面 |
| `cqm.token` | 空 | 写操作令牌。服务端启用令牌校验时必须一致 |
| `cqm.debug` | `false` | 打印探针调试日志 |

---

## 四、服务端配置项

| 参数 | 默认值 | 说明 |
|---|---|---|
| （命令行第 1 个参数） | `8080` | 监听端口 |
| `cqm.src` | `demo/src` | 静态代码分析的源码根目录 |
| `cqm.db` | `jdbc:h2:file:./data/cqm` | 数据库连接串（表结构见 `sql/schema.sql`） |
| `cqm.db.keepRows` | `5000` | 明细表保留行数，超出自动清理 |
| `cqm.token` | 空 | 写操作令牌。为空表示不校验（仅建议本地开发） |
| `cqm.autoscan` | `true` | 启动时是否立即执行一次静态分析 |
| `cqm.evalInterval` | `3000` | 两次评估之间的最小间隔（毫秒） |

---

## 五、服务端接口

| 方法 | 路径 | 说明 | 是否公开 |
|---|---|---|---|
| POST | `/api/metrics` | 探针上报运行期指标 | 需令牌 |
| GET | `/api/overview` | 页面汇总数据 | 公开 |
| GET | `/api/table` | 运行期指标明细 | 公开 |
| GET | `/api/static` | 静态指标明细 | 公开 |
| GET | `/api/violations` | 违规记录 | 公开 |
| GET | `/api/score` | 质量评分明细 | 公开 |
| GET | `/api/alarms` | 告警记录 | 公开 |
| GET | `/api/report` | 导出 HTML 质量报告 | 公开 |
| GET | `/api/analyze?path=` | 触发静态分析（路径须在 `cqm.src` 之内） | 需令牌或本机 |

---

## 六、质量规则（内置 6 条，阈值可调）

| 规则 | 判定条件 | 等级 |
|---|---|---|
| 方法复杂度过高 | 圈复杂度 > 15 | 高 |
| 方法体过长 | 有效代码行数 > 80 | 中 |
| 重复代码块 | 连续重复行数 > 10 | 中 |
| 异常被忽略 | 捕获异常后无任何处理 | 高 |
| 资源未关闭 | 打开流/连接后异常路径未释放 | 高 |
| 热点方法耗时偏高 | 平均耗时 > 100ms 且调用次数 > 1000 | 中 |

阈值可通过系统属性覆盖，例如 `-Dcqm.rule.complexity=20`。

质量得分 = 各项指标归一化后加权求和，权重同样可配置：

```bash
-Dcqm.weight.violation=0.35   # 高危违规数
-Dcqm.weight.complexity=0.20  # 平均圈复杂度
-Dcqm.weight.loc=0.15         # 平均方法行数
-Dcqm.weight.errRate=0.15     # 平均异常率
-Dcqm.weight.coverage=0.15    # 方法覆盖率
```

---

## 七、重新编译

```bash
# Linux / macOS
./build.sh

# Windows
build.bat
```

编译产物输出到 `out/`。脚本仅依赖 JDK 自带的 `javac` 与 `jar`，无需 Maven 或 Gradle。

---

## 八、目录结构

```
├── agent/            探针
│   ├── cqm-agent.jar           探针主 JAR（含 Byte Buddy）
│   └── cqm-agent-bootstrap.jar 引导类加载器辅助 JAR
├── server/
│   ├── classes/      服务端 class
│   ├── lib/          H2 数据库驱动
│   ├── start.sh      启动脚本
│   └── start.bat
├── demo/
│   ├── classes/      示例应用（被测对象）
│   └── src/          示例应用源码（供静态分析）
├── src/              全部源码
│   ├── agent/  server/  demo/  diag/
├── sql/schema.sql    数据库建表脚本（8 张表）
├── docs/             设计与部署文档
├── run-demo.sh/.bat  运行示例应用（带探针）
└── build.sh/.bat     重新编译
```

---

## 九、常见问题

**Q：挂上探针后业务应用启动报 `ClassNotFoundException: com.cqm.agent.MetricsAggregator`？**
A：`cqm-agent-bootstrap.jar` 必须与 `cqm-agent.jar` 放在**同一目录**。
探针会自动把它追加到引导类加载器，该 JAR 若缺失会导致采集不到数据。

**Q：页面一直没有数据？**
A：依次检查：① `-Dcqm.packages` 是否填对了业务包前缀（这是最常见的原因）；
② 应用是否真的执行了被插桩的方法；③ `cqm.server` 地址是否可达；
④ 加 `-Dcqm.debug=true` 观察探针日志。

**Q：探针会不会影响性能？**
A：实测单次方法调用的固定开销约 **0.107 微秒**；在毫秒级业务方法上额外耗时占比约 **0.1%**。
若在高频短方法场景下仍有顾虑，可下调 `-Dcqm.sample` 开启采样。

**Q：支持哪些 JDK 版本？**
A：探针基于 Byte Buddy，支持 JDK 8 及以上。本包内的 class 以 Java 17 编译，
若要在 JDK 8 上运行，请用 `build.sh` 在本机重新编译。

**Q：可以接入 Spring Boot 应用吗？**
A：可以。探针与框架无关，`-Dcqm.packages=com.yourcompany` 指向你的业务包即可，
无需任何代码改动。

---

## 十、许可

MIT License，详见 `LICENSE`。
