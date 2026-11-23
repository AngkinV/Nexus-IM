# Python Agent 底座方案

> **2026-11-21 状态：本文是早期草稿，已被实际实现取代，只留作选型过程的记录。**
>
> 实际落地在主仓库 `/Users/anglv/Nexus`（未提交），与本文不同的地方：
>
> - 用的是 Python 3.11，不是 3.12；OpenAI SDK 和 Chroma 没有升级（1.109.1 / 0.5.20），
>   所以 7.1、7.2 两条风险没有发生。本文「升级 LangGraph 必须连带升级这两个」的说法不成立。
> - 没有保留双引擎过渡期，手写循环已删除，`ENGINE` 只接受 `langgraph`。
> - 持久化和「继续」已经做完（SQLite），本文把它排在阶段 4。人工确认没有做。
> - 工具层走 MCP：Java 提供工具，Python 发现并调用，Python 里不再有手写的工具定义。
>
> 当前事实以 `nexus-agent-backend/README.md` 为准。

2026-11-20 起草。范围：`nexus-agent-backend`。

文中标「已验证」的结论，都是当天在本机实际跑出来的；标「未验证」的是还没碰过的，执行到那一步要先验。

## 1. 结论

**底座选 LangGraph 1.x 做编排，模型层继续用现有的 `LLMClient`，工具层加一个统一注册表并接入 MCP。**

三句话说明白：

- **编排交给 LangGraph**：循环、状态、断点续跑、人工确认、以后的多智能体，都用它的图来表达。
- **模型调用不交给 LangChain**：继续用 `app/llm/` 里自己的适配器，否则思考过程（`reasoning`）会丢，见 3.2。
- **对外协议一个字不改**：SSE 的 9 种事件保持原样，所以 Java 和前端在前三个阶段不用动。

要先说清楚的一点：对现在这套「单智能体 + 7 个工具」的功能，换底座本身不会带来用户看得见的变化。收益在后面：接 MCP 工具、人工确认、断点续跑、多智能体。

「应用最广泛」我没有查下载量或采用率数据，所以没把它当理由。选 LangGraph 的理由是：它已经在你的代码里、有现成引擎和测试，并且下面几项关键能力都实测通过了。

## 2. 现状（已验证）

| 项 | 实际情况 |
|---|---|
| 正在跑的引擎 | `handcrafted`（健康检查接口返回） |
| 服务默认模型 | `deepseek-v4.1-flash`，走 OpenAI 兼容接口（健康检查接口返回） |
| 本机 Python | 3.9.6（Dockerfile 里是 3.11） |
| 已装版本 | langgraph 0.2.45、langchain-core 0.3.21、openai 1.109.1、chromadb 0.5.20 |
| 现有测试 | 145 个，现有环境下全部通过 |
| 事件协议 | Java 用 `streamPythonRaw` 原样转发，前端 `agentApi.js` 按 9 个事件名分发 |

### 两个引擎现在的差距

现有的 LangGraph 引擎不能直接切过去用，它比手写版少了这些：

| 事件 | 手写版 | 现有 LangGraph 版 |
|---|---|---|
| `delta` | 边生成边发 | 全部生成完才发一次 |
| `reasoning` | 有 | 没有 |
| `stage`（context / knowledge / model） | 有 | 没有 |
| `meta` | 检索前就发 | 检索做完才发 |

原因在写法：节点把文字攒在列表里，等节点返回后才靠 `stream_mode="values"` 对比状态取事件。这是写法问题，不是 LangGraph 做不到，见 3.1。

## 3. 关键验证

### 3.1 流式可以加回来（已验证）

在 langgraph 1.2.24 上写了个最小图：节点内部用 `get_stream_writer()` 发事件，外层用 `stream_mode="custom"` 接收。假模型每 50ms 吐一个字，收到的时间戳如下：

```
+   3ms stage
+  54ms reasoning   先
+ 105ms reasoning   查
+ 156ms reasoning   一下
+ 158ms tool_call
+ 259ms tool_result
+ 311ms delta       你
+ 362ms delta       好
  ...
+ 619ms delta       下
```

7 个 `delta` 相邻间隔 51–52ms，是逐字实时到达，不是攒完再发。模型循环这一段的事件顺序和手写版一致。

局限：这里用的是假模型，没有接真实的 DeepSeek。

### 3.2 模型层必须保留自己的（已验证）

langchain-openai 1.8.5 的 `ChatOpenAI` 不提取 `reasoning_content`。它的源码文档直接写了这类字段「not extracted」，建议用各厂商专用的子类。实测喂一个带 `reasoning_content` 的流式分片进去，出来的消息里内容为空。

你现在的模型走 OpenAI 兼容接口，前端有 `reasoning` 事件的展示。换成 `ChatOpenAI` 这块就没了。

现有 `OpenAILikeClient` 已经处理了 `reasoning_content`、空闲超时、工具参数拼接，并且有测试覆盖。所以保留它，LangGraph 节点里直接调它。

### 3.3 升级依赖几乎不用改代码（已验证）

在 Python 3.12 加最新依赖下跑现有 145 个测试：

| 条件 | 结果 |
|---|---|
| 文件句柄上限 256（macOS 默认） | 134 通过，11 失败 |
| 文件句柄上限 8192 | 145 全部通过，耗时 19.9 秒 |

11 个失败全是 chromadb 报 `Too many open files`，集中在 `test_memory_rag*` 和 `test_vectorstore`。业务代码没有改一行。

局限：测试里模型和 embedding 都是假的。真实模型调用在 openai 3.x 下能不能通，没验证。

### 3.4 其它能力（已验证）

| 能力 | 结果 | 用到的东西 |
|---|---|---|
| 跨轮状态持久化 | 通过，第二轮能接上第一轮的消息 | `InMemorySaver` + `thread_id` |
| 人工确认后继续 | 通过，暂停在确认节点，恢复后继续调工具并出答案 | `interrupt()` + `Command(resume=...)` |
| MCP 工具接入 | 通过，发现工具、转成 OpenAI 格式、调用成功 | `langchain-mcp-adapters` 0.3.2 |

局限：持久化只测了内存版，没测落盘的版本。多智能体（子图）完全没测。

## 4. 目标结构

```
app/
  agent/
    state.py          AgentState
    events.py         Event 定义 + emit()，内部包 get_stream_writer
    graph.py          图：context → reason ⇄ tool → finalize
    runner.py         run_agent()：astream(custom) 转成 Event
    nodes/
      context.py      短期记忆、记忆检索、知识库检索
      reason.py       调 LLMClient，实时发 reasoning / delta
      tool.py         通过 ToolRegistry 执行
      finalize.py     usage、写记忆、done、__final__
  tools/
    registry.py       统一的工具清单和执行入口
    internal.py       现有 7 个 Java 工具
    mcp.py            MCP 工具
  llm/                不动
  rag/ knowledge/     不动
  memory.py           不动
  routes.py security.py sse.py schemas.py   不动
```

节点的写法（API 已在 3.1 验证过）：

```python
from langgraph.config import get_stream_writer

async def reason(state):
    emit = get_stream_writer()
    emit({"event": "stage", "data": {"phase": "model", "status": "start", ...}})
    async for chunk in client.complete(state["messages"], tools=...):
        if chunk.kind == "text":
            emit({"event": "delta", "data": {"text": chunk.text}})
        elif chunk.kind == "reasoning":
            emit({"event": "reasoning", "data": {"iteration": ..., "text": chunk.text}})
        ...
```

```python
async def run_agent(request, provider=None):
    async for ev in graph.astream(initial_state(request, provider), stream_mode="custom"):
        yield Event(ev["event"], ev["data"])
```

`routes.py` 只认 `run_agent` 这个生成器，所以它不用改。

## 5. 分阶段计划

每个阶段都能单独停下，停下时服务是可用的。

### 阶段 0：环境和依赖升级

不改业务逻辑，引擎还是 `handcrafted`。

- 新建 Python 3.12 的虚拟环境，和现在的 `.venv` 并排放，旧的不动。uv 里已经装好了 3.12.13。
- 换依赖版本，清单见第 6 节。
- 修测试里 chromadb 的文件句柄问题（复用客户端，或在 conftest 里提高上限）。
- 升级前备份 `data/chroma`。

验收：145 个测试在默认句柄上限下全过；用真实模型聊一轮，流式和过程展示正常。

### 阶段 1：LangGraph 引擎补齐流式

- 按第 4 节重写 `orchestrator_langgraph.py`，把 2 节里那 4 项差距补上。
- 两个引擎现在各写了一份「取上下文」和「写记忆」，抽成公共节点。
- 加一组对齐测试：同一份假模型脚本分别喂给两个引擎，事件名和关键字段必须一致。

验收：对齐测试通过；`ENGINE=langgraph` 下真实聊天，界面上逐字输出、思考过程、工具调用都在。

### 阶段 2：切换和收敛

- 默认引擎改成 `langgraph`，`ENGINE` 开关留着当回退。
- 稳定后删掉手写循环。
- 7 个工具现在定义了两遍（`tools.py` 的 `TOOL_SCHEMAS` 和 `langchain_tools.py` 的 `@tool`），描述文字还不完全一样，合成一份。

验收：只剩一个引擎、一份工具定义，测试全过。

### 阶段 3：工具注册表和 MCP

- `ToolRegistry` 统一两类工具：内部工具（走 Java）和 MCP 工具。
- MCP 工具返回的是内容块列表，不是字典，要先转成字符串再喂回模型。
- MCP 工具不经过 Java 的权限复核，所以默认一个都不开，按配置白名单启用。
- 工具名加前缀防重名。

验收：配一个 MCP 服务后，模型能调到它的工具，界面上有对应的 `tool_call` / `tool_result`。

### 阶段 4：持久化和人工确认

**这一阶段要动 Java 和前端**，需要一个新事件和一个「继续执行」的接口，做之前要先和你对界面。

- 人工确认依赖持久化，两者一起上。
- 持久化后端的选择见 7.3。

### 阶段 5：多智能体

等真有需求再做。子图这部分我没验证过，到时候要先做一轮验证。

## 6. 依赖清单

Python 3.12 下解析出来、并且 145 个测试跑通的组合：

| 包 | 现在 | 目标 |
|---|---|---|
| langgraph | 0.2.45 | 1.2.24 |
| langchain-core | 0.3.21 | 1.8.4 |
| langchain-openai | 0.2.8 | 1.8.5 |
| langchain-chroma | 0.1.4 | 1.1.0 |
| langchain-community | 0.3.7 | 0.4.2 |
| langchain-text-splitters | 0.3.2 | 1.1.3 |
| chromadb | 0.5.20 | 1.5.9 |
| openai | 1.109.1 | 3.57.0 |
| fastapi | 0.115.12 | 0.165.0 |
| pydantic | 2.10.6 | 2.14.4 |
| redis | 5.2.1 | 8.1.0 |
| unstructured | 0.16.5 | 0.27.37 |
| langchain-mcp-adapters | 无 | 0.3.2 |
| mcp | 无 | 1.30.0 |

几个连带约束：

- **Python 至少 3.11**：langchain-core 1.8.4 和 langchain-openai 1.8.5 都要求 3.11 以上。本机的 3.9 装不了，pip 在 3.9 下能看到的 langgraph 最高只到 0.6.11。
- **openai 必须跨大版本**：langchain-openai 1.8.5 要求 `openai>=2.45.0`，现在 `<2.0.0` 的限制要去掉。
- **chromadb 必须升到 1.x**：langchain-chroma 1.1.0 要求 `chromadb>=1.3.5`。
- **mcp 不能用最新版**：MCP 的 SDK 最新是 2.3.0，但适配器 0.3.2 要求 `mcp<2.0.0`。

## 7. 风险

### 7.1 真实模型调用没验证

openai SDK 从 1.x 跳到 3.x，测试是用假对象过的。阶段 0 的验收必须包含一次真实聊天。

### 7.2 chromadb 1.x

- 文件句柄占用明显变多，测试里已经撞到上限。生产上只有一个客户端，会不会有问题没测。
- 0.5.20 写的数据目录能不能被 1.5.9 直接打开，没测，我没碰你的数据。
- 你目前没有可用的向量模型，这两条路径本来就不在主流程上，但升级前还是要备份。

### 7.3 持久化后端

| 选项 | 情况 |
|---|---|
| Redis | `langgraph-checkpoint-redis` 0.6.0 要求 RedisJSON 和 RediSearch。你本机的 Redis 8.0.1 没有 `JSON.SET` 和 `FT.CREATE` 命令，docker-compose 里是 `redis:7-alpine`，也没有。两边都要换 Redis 才能用。 |
| SQLite | `langgraph-checkpoint-sqlite` 3.1.3，不需要额外服务，单实例够用。没实测。 |

建议阶段 4 先用 SQLite，等要多实例部署再换。

### 7.4 没评估过的替代框架

OpenAI Agents SDK（0.25.0）、Pydantic AI（2.71.0）、Google ADK（2.15.0）我只确认了它们还在更新，没有试用。不选它们不是因为它们差，而是换过去要从零重写，而且思考过程能不能透传也得重新验。

## 8. 需要你做的

- **阶段 0 开始前**：确认可以新建 3.12 的虚拟环境。之后启动服务要改用新环境，这个得你来切。
- **每次我改完 Python 代码**：你重启 Python 服务。
- **阶段 4 开始前**：定一下人工确认在界面上怎么呈现。
