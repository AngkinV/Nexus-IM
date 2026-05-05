# Nexus Agent — RAG 与记忆对话架构图

本文档用 Mermaid 描述三张架构图：

- **图 A**：整体系统拓扑（前端 / Java / Python / 三类存储 / 两个外部网关）
- **图 B**：单次聊天请求时序（三层记忆 + 双模块 RAG + 编排引擎 + 异步回写）
- **图 C**：知识库文档写入时序（BYOK 凭证 + 切分嵌入 + 维度锁定）

> 渲染方式：GitHub / VS Code（Markdown Preview Mermaid Support 插件）/ Typora 直接预览即可；导出图片可用 [mermaid.live](https://mermaid.live) 粘贴源码。

---

## 图 A：整体系统拓扑

```mermaid
flowchart TB
    subgraph Client["客户端"]
        FE["nexus-chat-frontend<br/>Vue 3 + Pinia + Element Plus"]
    end

    NGINX["nginx<br/>静态托管 + 反向代理"]

    subgraph JavaBE["nexus-chat-backend (Java / Spring Boot)"]
        AGW["AgentGatewayService<br/>聊天调用 / X-Model-* (chat)"]
        KGW["KnowledgeGatewayService<br/>KB ingest / query / X-Model-* (embedding)"]
        APS["AgentProviderService<br/>凭证 CRUD / purpose=chat or embedding"]
        KBS["KnowledgeBaseService<br/>findByKbIdAndUserId 跨租户拒绝"]
    end

    subgraph PyBE["nexus-agent-backend (Python / FastAPI)"]
        ROUTES["routes.py<br/>/v1/agent/invoke + /v1/knowledge/*"]
        SEC["security.py<br/>HMAC 验签 + X-Model-* 解析"]
        ORCH_HC["orchestrator.py<br/>handcrafted ReAct (production)"]
        ORCH_LG["orchestrator_langgraph.py<br/>LangGraph StateGraph (备选)"]
        MEM_RAG["memory_rag.py<br/>Module A 语义召回"]
        KB_RAG["knowledge_rag.py<br/>Module B 知识库检索"]
        INGEST["ingestion.py<br/>load → split → embed → store"]
    end

    subgraph Storage["持久化"]
        MYSQL[("MySQL<br/>users / model_credentials<br/>agent_knowledge_base<br/>agent_knowledge_document")]
        REDIS[("Redis<br/>短期记忆滑动窗口")]
        CHROMA[("ChromaDB<br/>memory_chunks<br/>kb_chunks_*")]
        UPLOADS[("Java uploads<br/>/agent-kb/kb_*")]
    end

    subgraph External["外部模型网关"]
        CHAT_GW["Chat 网关<br/>new-api.abrdns.com<br/>(国产分组 / MiMo-V2.5-Pro)"]
        EMB_GW["Embedding 网关<br/>router.tumuer.me<br/>(text-embedding-3-large)"]
    end

    FE -->|HTTPS / SSE| NGINX
    NGINX --> AGW
    NGINX --> KGW
    NGINX --> APS
    NGINX --> KBS

    AGW -->|"HMAC + X-Model-* (chat key)"| SEC
    KGW -->|"HMAC + X-Model-* (embedding key)"| SEC
    SEC --> ROUTES
    ROUTES -->|engine 切换| ORCH_HC
    ROUTES -.engine=langgraph.-> ORCH_LG
    ROUTES --> INGEST
    ORCH_HC --> MEM_RAG
    ORCH_HC --> KB_RAG
    ORCH_LG --> MEM_RAG
    ORCH_LG --> KB_RAG

    APS --> MYSQL
    KBS --> MYSQL
    KGW -->|文件路径| UPLOADS

    ORCH_HC -.短期记忆.-> REDIS
    ORCH_LG -.短期记忆.-> REDIS
    MEM_RAG --> CHROMA
    KB_RAG --> CHROMA
    INGEST --> CHROMA
    INGEST -.读取文件.-> UPLOADS

    ORCH_HC -->|chat 凭证| CHAT_GW
    ORCH_LG -->|chat 凭证| CHAT_GW
    MEM_RAG -->|服务端 EMB 默认| EMB_GW
    KB_RAG -->|服务端 EMB 默认| EMB_GW
    INGEST -->|BYOK embedding 凭证| EMB_GW

    ORCH_HC -.反向调用 Java 内部 API.-> JavaBE
    ORCH_LG -.反向调用 Java 内部 API.-> JavaBE

    classDef store fill:#fef3c7,stroke:#d97706
    classDef ext fill:#dbeafe,stroke:#2563eb
    class MYSQL,REDIS,CHROMA,UPLOADS store
    class CHAT_GW,EMB_GW ext
```

### 关键设计点

1. **两个外部网关物理分离**：chat 走 `new-api.abrdns.com`、embedding 走 `router.tumuer.me`，因为 BYOK chat 厂商（DeepSeek / Moonshot / 国产分组）通常不暴露 OpenAI 兼容的 `/embeddings`。
2. **凭证按 purpose 分家**：`model_credentials.purpose ∈ {chat, embedding}`，唯一索引 `(user_id, provider, purpose)`。
3. **双编排引擎并存**：production 默认 `handcrafted`；`ENGINE=langgraph` 可整体切换，SSE 输出格式逐字节兼容。

---

## 图 B：单次聊天请求时序（含三层记忆 + RAG）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant FE as 前端
    participant J as Java AgentGatewayService
    participant P as Python orchestrator
    participant R as Redis
    participant C as ChromaDB
    participant E as Embedding 网关
    participant L as LLM Chat 网关
    participant T as ToolExecutor → Java

    U->>FE: 输入问题
    FE->>J: POST /agent/invoke (含 sessionId, linkedKbId)
    J->>J: 解密 chat 凭证 + HMAC 签名
    J->>P: POST /v1/agent/invoke/stream<br/>X-Model-* (chat key)

    Note over P: ===== 三层记忆并行召回 =====

    P->>R: LRANGE messages key
    R-->>P: 短期对话最后 12 条

    P->>E: embed_query(用户问题)
    E-->>P: query 向量
    P->>C: similarity_search(memory_chunks)<br/>filter={userId}
    C-->>P: top-K 历史片段

    P->>C: similarity_search(kb_chunks_<kbId>)<br/>filter={kbId, userId}
    C-->>P: top-K (Document, score)

    Note over P: ===== Prompt 组装 =====
    Note right of P: [system] 角色+业务<br/>[system] 长期记忆 (FACT)<br/>[system] 相关历史 (memory_rag)<br/>[system] 知识库参考 [1][2]…<br/>[user/assistant] 短期对话<br/>[user] 当前问题+actorUserId

    Note over P: ===== ReAct 循环 (engine 二选一) =====

    loop 直到 final_answer 或 iter ≥ N
        P->>L: chat.completions(messages, tools)
        L-->>P: {text, tool_calls?}

        alt 有 tool_calls
            P->>FE: SSE event: tool_call
            P->>T: HMAC 调 Java 内部 API
            T-->>P: tool 结果
            P->>FE: SSE event: tool_result
            P->>P: 把 tool 结果回灌 messages
        else 拿到 final_answer
            P->>FE: SSE event: delta + usage + done
        end
    end

    Note over P: ===== 异步回写 (fire-and-forget) =====

    par 不阻塞 SSE done
        P->>R: RPUSH user / assistant<br/>LTRIM 滑动窗口
    and
        P->>E: embed (User+Assistant 整段)
        E-->>P: 向量
        P->>C: add_documents(memory_chunks)<br/>metadata={userId, sessionId}
    end

    FE-->>U: 流式渲染答案
```

### 关键设计点

1. **三层记忆并行**：短期（Redis）+ 语义召回（Chroma）+ 长期（Java 注入）三路并行检索后再组装 prompt。
2. **RAG 失败一律降级**：embedding 服务挂掉 → `try/except` → 返回 `[]` → 编排层无感知，自动只用短期记忆。**RAG 不能因为向量服务挂了把整个对话搞死**。
3. **写入异步化**：`asyncio.create_task(memory_rag.write())` 不阻塞 SSE `done` 事件；用模块级 `set` 持有强引用防 GC 回收。
4. **跨用户隔离**：所有 Chroma 检索都强制 metadata filter（`{userId}` / `{kbId, userId}`），数据边界在向量库层兜底；Java 端 `findByKbIdAndUserId` 是网络边界第一道。
5. **工具调用回 Java**：Python 不直接读 MySQL，通过 `ToolExecutor` 反向 HMAC 调 Java 内部 API（`get_user_profile` / `list_my_chats` / `get_recent_messages` ...），保证业务数据访问全部走 Java 权限校验。

---

## 图 C：知识库文档写入时序（BYOK + 维度锁定）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant FE as 前端
    participant J as Java KnowledgeBaseController
    participant SCH as KnowledgeIngestionScheduler
    participant KGW as KnowledgeGatewayService
    participant DB as MySQL
    participant FS as Java uploads/
    participant P as Python ingestion
    participant E as Embedding 网关
    participant C as ChromaDB

    U->>FE: 上传文件 (绑定 KB)
    FE->>J: POST /agent/kb/{kbId}/documents (multipart)
    J->>FS: 保存文件 → file_path
    J->>DB: INSERT agent_knowledge_document (status=PENDING)
    J-->>FE: 202 Accepted (异步处理中)

    SCH->>DB: SELECT pending docs
    SCH->>DB: SELECT KB.embedding_credential_id
    SCH->>DB: SELECT credential 解密 → 明文 api_key
    SCH->>KGW: ingest(IngestRequest)

    KGW->>KGW: 组装 X-Model-* 头<br/>(embedding 凭证, 不是 chat!)
    KGW->>P: POST /v1/knowledge/ingest<br/>HMAC + X-Model-*

    Note over P: ===== load → split → embed → store =====

    P->>FS: 读取 file_path
    FS-->>P: 原始文档
    P->>P: loaders.py: PDF/MD/TXT loader
    P->>P: RecursiveCharacterTextSplitter<br/>(cl100k_base, size=512, overlap=64)
    P->>E: embed_query(第一个 chunk)<br/>探测 dimension
    E-->>P: 样本向量 (e.g. 3072 维)
    P->>C: get_or_create_collection<br/>(kb_chunks_<kbId>)
    P->>E: embed_documents(全部 chunks)
    E-->>P: 向量数组
    P->>C: add_documents(chunks, metadata)<br/>{kbId, docId, chunkIndex, userId, fileName}
    C-->>P: ok
    P-->>KGW: {chunkCount, embeddingDimension}

    KGW->>DB: UPDATE document.status = READY<br/>chunk_count = N
    KGW->>DB: UPDATE KB.embedding_dimension<br/>(首次 ingest 才写, 之后只读)
    DB-->>FE: 轮询查 status (READY)

    Note over FE,DB: ===== 维度锁定 =====
    Note right of FE: 此后前端创建 KB 表单里<br/>当 embedding_dimension != null<br/>embedding select 强制 disabled<br/>从产品层防止换模型导致维度不匹配
```

### 关键设计点

1. **异步处理**：`POST /documents` 立刻返回 202，重活交给 `KnowledgeIngestionScheduler`；前端轮询文档 status 字段直到 READY。
2. **BYOK embedding 凭证一路传递**：Java 解密 → `X-Model-*` 头携带 → Python `security.py` 解出 `provider` → `ingestion.py` 显式传给 `get_embeddings(api_key=..., base_url=..., model=...)`。**这条路径上的 X-Model-\* 是 embedding 凭证，不是 chat 凭证**——是与 chat 路径的最大区别。
3. **维度探测 + 锁定**：第一个 chunk `embed_query` 拿到向量后取 `len(vec)` 作为该 KB 的固定维度，回写 MySQL。之后前端 select `disabled`，从 UX 层防误改。
4. **Per-KB 独立 collection**：`kb_chunks_<kb_id>`，避免不同 KB 维度冲突（Chroma 集合维度首次写入即锁死）。

---

## 附：双编排引擎切换

```mermaid
flowchart LR
    REQ["POST /v1/agent/invoke/stream"] --> ROUTES["routes._resolve_engine()"]
    ROUTES -->|"settings.engine == 'handcrafted' (default)"| HC["orchestrator.run_agent<br/>手写 while 循环"]
    ROUTES -->|"settings.engine == 'langgraph'"| LG["orchestrator_langgraph.run_agent<br/>StateGraph"]

    HC --> SAME_EVT["相同 SSE 事件序列<br/>meta → tool_call → tool_result → delta → usage → done"]
    LG --> SAME_EVT

    SAME_EVT --> FE["前端无感知"]

    classDef prod fill:#dcfce7,stroke:#16a34a
    classDef demo fill:#fef9c3,stroke:#ca8a04
    class HC prod
    class LG demo
```

### 切换实操

```bash
# .env
ENGINE=handcrafted    # 默认 / production
# ENGINE=langgraph    # 切到 LangGraph 引擎

# 重启 Python 服务后生效（get_settings 是 lru_cache）
```

### 选择依据

| 场景 | 选谁 |
|---|---|
| 当前简单线性 ReAct | **handcrafted**（易调试、无外部依赖） |
| 未来要做多 agent 协作 / 节点级人工审批 / 长任务 checkpointer | **langgraph**（StateGraph + Checkpointer 表达力强） |

> 双引擎并存的真正收益：(1) 验证迁移可行性 (2) 同输入下两个实现做一致性对比，发现潜在 bug。production 不切换是因为业务复杂度还没到需要 LangGraph 的阈值——**过早抽象是技术负债**。
