# Nexus Agent RAG 扩展实施方案

> 本文档是后续严格执行的依据。每个阶段的交付物、文件清单、验收标准都已写明。
> 实施期间不偏离方案；如需调整必须回到本文档先改方案再动代码。

---

## 0. 总体目标

在现有 Nexus Agent 模块基础上，**真实落地** RAG（Retrieval-Augmented Generation）能力，覆盖以下校招 JD 高频关键词：

- ✅ **LangChain**（document loaders / text splitters / retrievers / chains）
- ✅ **LangGraph**（StateGraph 编排）
- ✅ **向量数据库**（ChromaDB 嵌入式 + 接口预留 Milvus 升级路径）
- ✅ **Embedding**（OpenAI text-embedding-3-small + 可选本地 BGE）
- ✅ **RAG**（会话历史 RAG + 文档问答 RAG 两种应用形态）
- ✅ **文档处理**（PDF / Markdown / Word / TXT 加载与切分）

**实施期**：2-3 周（17 个工作日）。

**实施原则**：
1. **不破坏现有**：当前手写 ReAct orchestrator + 7 个工具 + 短期记忆全部保留，新功能并行接入；
2. **渐进交付**：A / B / C 三个模块独立可交付，任何一个完成都能体现在简历上；
3. **零基础设施新增**：复用现有 MySQL + Redis；ChromaDB 用嵌入式（SQLite + Parquet 文件），不需要新部署服务；
4. **每个模块都对应"问题→方案→结果"简历句式**：实施完直接补到 `求职简历-项目经历.md`。

---

## 1. 三个模块分解

### 模块 A：会话历史 RAG（长期语义记忆）

**解决的问题**：现有短期记忆是 Redis List 滑动窗口（最近 40 条），超过就被 LTRIM 丢掉。但用户可能问"你上次帮我总结的那个会议是什么内容"——老对话已经被丢了，模型答不出。

**方案**：每轮对话结束后，把 user + assistant 两条消息合并为一个 "记忆片段"，调 OpenAI Embeddings 算向量，存进 ChromaDB；下一轮新对话开始时，根据当前用户输入做语义检索，召回 Top-3 相关历史片段，作为补充上下文塞进 system prompt。

**简历卖点**：长对话场景下解决了"短期记忆窗口溢出"问题，引入向量检索做语义记忆。

### 模块 B：知识库文档问答（Document RAG）

**解决的问题**：用户希望 AI 基于自己上传的 PDF / Markdown / Word 文档回答问题（例如"这份合同的违约条款是什么"），而不是凭通用知识答。

**方案**：
1. 用户在 Agent 设置里创建"知识库"（Knowledge Base），上传文档；
2. Python 用 LangChain Document Loaders 加载 → RecursiveCharacterTextSplitter 切分 → OpenAI Embeddings 入向量库；
3. 用户在某个会话里选择关联某个知识库，问答时触发 RAG 流程；
4. 检索召回 Top-K 文档片段，构造 prompt 让 LLM 基于这些片段回答。

**简历卖点**：完整 RAG pipeline 落地（加载 → 切分 → 向量化 → 检索 → 生成），引入 LangChain 文档处理生态。

### 模块 C：LangGraph 编排引擎（高级加分）

**解决的问题**：现有手写 orchestrator 工程上可控，但**不是 2024+ 行业最新姿势**——LangGraph（基于状态图的 Agent 编排）已成事实标准。面试官可能问"你了解 LangGraph 吗"。

**方案**：用 LangGraph 把 ReAct 流程重写一份，作为**可选编排引擎**（保留原 orchestrator 作为对照路径），通过 `settings.engine="handcrafted" | "langgraph"` 切换。

**简历卖点**：分场景选型——主链路手写保证可控性，对照实现 LangGraph 版本验证业界主流方案，体现架构思维。

---

## 2. 数据模型设计

### 2.1 新增 MySQL 表（migration `2026_05_15_agent_rag.sql`）

```sql
-- 模块 A 的元数据表（向量数据存 ChromaDB，这里只存 Java 侧能查询的元信息）
CREATE TABLE IF NOT EXISTS agent_memory_embedding (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    session_id      VARCHAR(64) NOT NULL,
    chunk_id        VARCHAR(64) NOT NULL,            -- ChromaDB 中的 ID
    user_text       TEXT NOT NULL,
    assistant_text  TEXT NOT NULL,
    summary         VARCHAR(500),                    -- 可选，用于展示
    created_at      DATETIME NOT NULL,
    UNIQUE KEY uk_chunk (chunk_id),
    INDEX idx_user_session (user_id, session_id),
    INDEX idx_user_created (user_id, created_at)
);

-- 模块 B：知识库
CREATE TABLE IF NOT EXISTS agent_knowledge_base (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL,
    kb_id           VARCHAR(64) NOT NULL,            -- "kb_xxxxx"
    name            VARCHAR(120) NOT NULL,
    description     VARCHAR(500),
    embedding_model VARCHAR(80) DEFAULT 'text-embedding-3-small',
    chunk_size      INT NOT NULL DEFAULT 512,
    chunk_overlap   INT NOT NULL DEFAULT 64,
    document_count  INT NOT NULL DEFAULT 0,
    chunk_count     INT NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_id (kb_id),
    INDEX idx_user (user_id)
);

-- 模块 B：知识库文档
CREATE TABLE IF NOT EXISTS agent_knowledge_document (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id           VARCHAR(64) NOT NULL,
    doc_id          VARCHAR(64) NOT NULL,            -- "doc_xxxxx"
    file_name       VARCHAR(255) NOT NULL,
    file_path       VARCHAR(500) NOT NULL,           -- 复用 FileUploadController 的路径
    file_size       BIGINT NOT NULL,
    file_type       VARCHAR(20) NOT NULL,            -- pdf / md / txt / docx
    chunk_count     INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / PROCESSING / READY / FAILED
    error_message   VARCHAR(500),
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME NOT NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doc_id (doc_id),
    INDEX idx_kb (kb_id),
    INDEX idx_status (status)
);

-- 模块 B：会话与知识库的关联
ALTER TABLE agent_session
    ADD COLUMN linked_kb_id VARCHAR(64) DEFAULT NULL COMMENT '关联的知识库 ID';
```

### 2.2 ChromaDB Collection 设计

**两个 Collection**（同一个 ChromaDB 实例内）：

| Collection | 用途 | metadata 字段 | embedding 模型 |
|---|---|---|---|
| `memory_chunks` | 模块 A 会话历史 | userId / sessionId / createdAt / chunkId | text-embedding-3-small |
| `kb_chunks` | 模块 B 文档片段 | userId / kbId / docId / fileName / chunkIndex | text-embedding-3-small（可按 kb 配置） |

**ChromaDB 持久化路径**：`./data/chroma/`（生产部署时挂载到 Docker volume）。

---

## 3. 文件改动清单

### 3.1 Python 服务 `nexus-agent-backend/`

#### 新增依赖（`requirements.txt` 增量）

```
# RAG 核心
langchain==0.3.7
langchain-core==0.3.21
langchain-openai==0.2.8
langchain-community==0.3.7
langchain-text-splitters==0.3.2
chromadb==0.5.20

# LangGraph
langgraph==0.2.45

# 文档加载
pypdf==5.1.0
unstructured==0.16.5
python-docx==1.1.2
markdown==3.7

# Embedding（可选本地，默认用 OpenAI）
# sentence-transformers==3.3.1
```

#### 新增文件

```
nexus-agent-backend/app/
├── rag/                                    # ★ 模块 A + B 共用
│   ├── __init__.py
│   ├── embeddings.py                       # OpenAI Embeddings 封装（含降级路径）
│   ├── vectorstore.py                      # ChromaDB 客户端单例 + Collection 管理
│   ├── memory_rag.py                       # 模块 A：会话历史 RAG（写入+检索）
│   └── knowledge_rag.py                    # 模块 B：文档 RAG（ingestion + retrieval）
├── knowledge/                              # ★ 模块 B 专属
│   ├── __init__.py
│   ├── loaders.py                          # LangChain Document Loaders 调度
│   ├── splitter.py                         # RecursiveCharacterTextSplitter 配置
│   ├── ingestion.py                        # 加载→切分→向量化→入库 pipeline
│   └── qa.py                               # QA Chain（检索+生成）
├── orchestrator_langgraph.py              # ★ 模块 C：LangGraph 实现
└── ...
```

#### 修改文件

| 文件 | 修改内容 |
|---|---|
| `requirements.txt` | 加上述 13 个依赖 |
| `app/config.py` | 增加 `chroma_persist_dir`、`embedding_model`、`memory_rag_enabled`、`memory_rag_top_k`、`engine` 等字段 |
| `app/orchestrator.py` | 在 `run_agent()` 开头调用 `memory_rag.retrieve()`，把召回内容传给 `build_messages()`；保持原 mock 路径 |
| `app/memory.py` | 写入短期记忆后**异步**调用 `memory_rag.write()`（不阻塞主流程） |
| `app/prompts.py` | `build_messages()` 加 `relevant_history` 参数，拼到 system prompt 的"相关历史"段 |
| `app/routes.py` | 新增 `/v1/knowledge/*` 路由（ingestion / query），保留 HMAC 验签 |
| `app/schemas.py` | 增加 `KnowledgeIngestRequest` / `KnowledgeQueryRequest` 等 DTO |

### 3.2 Java 服务 `nexus-chat-backend/`

#### 新增文件

```
src/main/java/com/nexus/chat/
├── controller/agent/
│   └── KnowledgeBaseController.java        # /api/agent/knowledge/* 浏览器接口
├── service/agent/
│   └── KnowledgeBaseService.java           # 知识库 CRUD + 文档管理
├── model/agent/
│   ├── AgentMemoryEmbedding.java           # 模块 A 元信息实体
│   ├── KnowledgeBase.java
│   └── KnowledgeDocument.java
├── repository/agent/
│   ├── AgentMemoryEmbeddingRepository.java
│   ├── KnowledgeBaseRepository.java
│   └── KnowledgeDocumentRepository.java
├── dto/agent/
│   └── KnowledgeBaseDtos.java
└── resources/migrations/
    └── 2026_05_15_agent_rag.sql            # ★ 上面 §2.1 的 schema
```

#### 修改文件

| 文件 | 修改内容 |
|---|---|
| `controller/agent/InternalAgentController.java` | 增加 `/internal/agent/knowledge/upload-callback` 接口（可选，给 Python 上报 ingestion 状态） |
| `service/agent/AgentGatewayService.java` | 转发到 Python 时附带 `linkedKbId`（如有） |
| `model/agent/AgentSession.java` | 增加 `linkedKbId` 字段 |
| `dto/agent/AgentSessionAndChatDtos.java` | 增加 `linkedKbId` 字段（在 SessionChatRequest 等 DTO） |

### 3.3 前端 `nexus-chat-frontend/`

#### 新增文件

```
src/
├── components/agent/
│   ├── KnowledgeBaseManager.vue            # 知识库管理对话框（创建/列表/上传文档）
│   └── KnowledgeBaseSelector.vue           # 在 AgentChatView 顶部选择关联 KB 的下拉
├── stores/
│   └── knowledge.js                        # Pinia store
└── services/
    └── knowledgeApi.js                     # /api/agent/knowledge/* 调用封装
```

#### 修改文件

| 文件 | 修改内容 |
|---|---|
| `components/agent/AgentChatView.vue` | 顶部加 `KnowledgeBaseSelector`，发送消息时带 `linkedKbId` |
| `stores/agent.js` | `sendMessage` 的 payload 加 `linkedKbId` 字段 |
| `services/agentApi.js` | `streamAgentChat` 的 payload schema 加 `linkedKbId` |
| `locales/zh.json` / `en.json` | 增加知识库相关文案 |

---

## 4. API 契约

### 4.1 模块 B：浏览器→Java 知识库接口

```
POST   /api/agent/knowledge                      # 创建知识库
GET    /api/agent/knowledge                      # 我的知识库列表
GET    /api/agent/knowledge/{kbId}               # 知识库详情
PATCH  /api/agent/knowledge/{kbId}               # 重命名/改描述
DELETE /api/agent/knowledge/{kbId}               # 删除知识库（含所有文档）

POST   /api/agent/knowledge/{kbId}/documents     # 上传文档（multipart）
GET    /api/agent/knowledge/{kbId}/documents     # 文档列表
DELETE /api/agent/knowledge/{kbId}/documents/{docId}  # 删除单个文档
GET    /api/agent/knowledge/{kbId}/documents/{docId}/status  # 查询 ingestion 状态
```

### 4.2 Java→Python RAG 处理接口

```
POST /v1/knowledge/ingest                        # 触发文档 ingestion
  body: { kbId, docId, filePath, fileType, chunkSize, chunkOverlap, embeddingModel }
  返回: { docId, chunkCount, status }

POST /v1/knowledge/delete                        # 删文档（含向量）
  body: { kbId, docId? }                         # docId 不传则删整个 kb

POST /v1/knowledge/query                         # 检索（独立调用，非 Agent 流程）
  body: { kbId, query, topK }
  返回: { chunks: [{text, score, metadata}] }
```

### 4.3 Agent 调用时自动 RAG（不需要新接口）

`POST /api/agent/sessions/{sid}/chat/stream` 的 body 增加 `linkedKbId` 字段，Java 透传给 Python，Python orchestrator 自动触发 RAG 检索并融合到 prompt。

---

## 5. 关键代码骨架（执行参考）

### 5.1 `app/rag/embeddings.py`

```python
"""Embedding 服务：默认 OpenAI Embeddings，可降级为本地 BGE。"""
from langchain_openai import OpenAIEmbeddings
from .config import get_settings

_embeddings_cache: dict = {}

def get_embeddings(model: str | None = None):
    settings = get_settings()
    name = model or settings.embedding_model
    if name in _embeddings_cache:
        return _embeddings_cache[name]
    if name.startswith("text-embedding-"):
        emb = OpenAIEmbeddings(
            model=name,
            api_key=settings.openai_api_key,
            base_url=settings.openai_base_url,
        )
    else:
        from langchain_community.embeddings import HuggingFaceEmbeddings
        emb = HuggingFaceEmbeddings(model_name=name)
    _embeddings_cache[name] = emb
    return emb
```

### 5.2 `app/rag/vectorstore.py`

```python
"""ChromaDB 持久化客户端单例。"""
import chromadb
from chromadb.config import Settings as ChromaSettings
from .config import get_settings

_client: chromadb.PersistentClient | None = None

def get_chroma_client():
    global _client
    if _client is None:
        settings = get_settings()
        _client = chromadb.PersistentClient(
            path=settings.chroma_persist_dir,
            settings=ChromaSettings(anonymized_telemetry=False),
        )
    return _client

def get_or_create_collection(name: str, metadata: dict | None = None):
    return get_chroma_client().get_or_create_collection(
        name=name, metadata=metadata or {}
    )
```

### 5.3 `app/rag/memory_rag.py`

```python
"""模块 A：会话历史 RAG。"""
import uuid
from datetime import datetime, timezone
from langchain_core.documents import Document
from langchain_community.vectorstores import Chroma
from .embeddings import get_embeddings
from .vectorstore import get_chroma_client

COLLECTION_NAME = "memory_chunks"

def _store():
    return Chroma(
        client=get_chroma_client(),
        collection_name=COLLECTION_NAME,
        embedding_function=get_embeddings(),
    )

async def write(user_id: int, session_id: str, user_text: str, assistant_text: str):
    chunk = f"User: {user_text}\nAssistant: {assistant_text}"
    chunk_id = f"mem_{uuid.uuid4().hex[:16]}"
    doc = Document(
        page_content=chunk,
        metadata={
            "userId": user_id, "sessionId": session_id,
            "chunkId": chunk_id,
            "createdAt": datetime.now(timezone.utc).isoformat(),
        },
    )
    _store().add_documents([doc], ids=[chunk_id])

async def retrieve(user_id: int, query: str, top_k: int = 3) -> list[str]:
    results = _store().similarity_search(
        query, k=top_k, filter={"userId": user_id},
    )
    return [d.page_content for d in results]
```

### 5.4 `app/orchestrator.py` 集成点

```python
# run_agent() 开头
from .rag.memory_rag import retrieve as retrieve_memory_rag

settings = get_settings()
memory = get_memory()
short_term = memory.recent(request.actor.userId, request.session.sessionId)

# ★ 新增：RAG 召回
relevant_history: list[str] = []
if settings.memory_rag_enabled:
    try:
        relevant_history = await retrieve_memory_rag(
            request.actor.userId,
            request.input.text,
            top_k=settings.memory_rag_top_k,
        )
    except Exception as e:
        log.warning("memory rag retrieval failed: %s", e)  # 降级：失败不阻断

base_messages = build_messages(request, short_term, long_term, relevant_history)
```

### 5.5 `app/orchestrator_langgraph.py`（模块 C 骨架）

```python
"""LangGraph 版 ReAct 编排，作为可切换的对照实现。"""
from typing import Annotated, TypedDict
from langgraph.graph import StateGraph, END
from langchain_core.messages import AnyMessage
import operator

class AgentState(TypedDict):
    messages: Annotated[list[AnyMessage], operator.add]
    iteration: int
    final_answer: str

async def reason_node(state: AgentState) -> dict:
    # 调 LLM，生成 assistant message（可能含 tool_calls）
    ...

async def tool_node(state: AgentState) -> dict:
    # 执行 tool_calls，返回 tool messages
    ...

def route(state: AgentState) -> str:
    last = state["messages"][-1]
    if state["iteration"] >= 6:
        return "finish"
    if last.tool_calls:
        return "tool"
    return "finish"

graph = StateGraph(AgentState)
graph.add_node("reason", reason_node)
graph.add_node("tool", tool_node)
graph.set_entry_point("reason")
graph.add_conditional_edges("reason", route, {"tool": "tool", "finish": END})
graph.add_edge("tool", "reason")
agent_graph = graph.compile()
```

---

## 6. 时间表（17 个工作日）

### Sprint 1：基础设施 + 模块 A（Day 1-7）

| Day | 任务 | 交付物 |
|---|---|---|
| 1 | 升级 requirements；新增 `app/config.py` 字段；ChromaDB 初始化测试 | `requirements.txt`、`config.py` |
| 2 | 实现 `rag/embeddings.py`、`rag/vectorstore.py` | 两个 helper，pytest 通过 |
| 3 | 实现 `rag/memory_rag.py` 写入 + 检索路径；写单元测试 | `memory_rag.py` + `tests/test_memory_rag.py` |
| 4 | 集成到 `orchestrator.py` 和 `prompts.py`；调通本地端到端 | 流式聊天能看到"相关历史"段被注入 |
| 5 | 数据库迁移 `2026_05_15_agent_rag.sql`；Java `AgentMemoryEmbedding` 实体（可选元信息表） | migration + 实体 |
| 6 | 联调测试：连续 50 轮对话测试，验证早期消息能被检索召回 | 测试报告（手工） |
| 7 | 简历更新（项目一加一条 RAG 长期记忆 bullet） | `求职简历-项目经历.md` |

**Sprint 1 验收标准**：
- 用户连续 50 轮对话后，问"还记得我们最早聊过什么吗"，模型能基于召回内容答得上。
- ChromaDB 持久化目录存在数据。
- 关闭 `memory_rag_enabled` 后行为完全回到原状（降级路径完整）。

### Sprint 2：模块 B 知识库文档 RAG（Day 8-14）

| Day | 任务 | 交付物 |
|---|---|---|
| 8 | Java schema 迁移；`KnowledgeBase` / `KnowledgeDocument` 实体 + Repository | 实体 + repo |
| 9 | Java `KnowledgeBaseService` + Controller（CRUD） | Controller 测通 |
| 10 | Python `knowledge/loaders.py`、`splitter.py`、`ingestion.py` | ingestion pipeline |
| 11 | Python `knowledge/qa.py`、`rag/knowledge_rag.py` 检索；`routes.py` 增加 `/v1/knowledge/*` | 端到端检索 |
| 12 | 前端 `KnowledgeBaseManager.vue`：创建/列表/上传文档 UI | 前端能上传 |
| 13 | 前端 `KnowledgeBaseSelector.vue`：在 ChatView 关联 KB；`stores/knowledge.js` | 选择 KB 后能问答 |
| 14 | 联调：上传 PDF → 问答；简历更新（项目一加文档 RAG bullet） | 端到端可用 + 简历 |

**Sprint 2 验收标准**：
- 用户能上传 PDF / Markdown / TXT 创建知识库；
- 在 Agent 会话里关联某个 KB 后，提问能基于文档内容回答；
- 文档 ingestion 状态在前端可见（PENDING / READY / FAILED）；
- 删除文档后向量库同步删除。

### Sprint 3（可选）：模块 C LangGraph（Day 15-17）

| Day | 任务 | 交付物 |
|---|---|---|
| 15 | `orchestrator_langgraph.py` 骨架：StateGraph + reason / tool / route | LangGraph 跑通基础 |
| 16 | 把现有 7 个工具适配为 LangChain Tool；接入 ChatModel | 工具调用通 |
| 17 | `routes.py` 增加 `engine` 配置切换；简历更新；性能/trace 对比 | 双引擎可切换 + 简历 |

**Sprint 3 验收标准**：
- `settings.engine = "langgraph"` 时走 LangGraph 路径，事件流形态与原 orchestrator 一致；
- 切换为 `"handcrafted"` 时回到原实现；
- 文档里写一段两种实现的对比（trace 可读性 / 代码量 / 性能差异）。

---

## 7. 风险与降级方案

| 风险 | 概率 | 影响 | 降级方案 |
|---|---|---|---|
| OpenAI Embeddings API 不可用（用户 Key 无 embedding 权限） | 中 | 模块 A/B 全部不可用 | 切到本地 `bge-small-zh-v1.5`，启动时检测可用 embedding 模型 |
| ChromaDB 在 Apple Silicon 安装失败 | 低 | 阻塞 | 备选 FAISS-cpu（接口兼容） |
| LangChain 版本不兼容（0.3 系列变化大） | 中 | 引入功能变慢 | 锁定到具体 patch 版本，不升 minor |
| LangGraph 与现有流式 SSE 整合困难 | 中 | 模块 C 推迟 | 模块 C 标记为可选，先交付 A/B |
| 文档 ingestion 慢（大 PDF） | 中 | 用户体验差 | 异步任务（FastAPI BackgroundTasks），前端轮询 status |
| ChromaDB 持久化文件 corruption | 低 | 数据丢失 | 关键索引同时记到 MySQL `agent_memory_embedding` 表，可重建 |

---

## 8. 简历更新预览（实施完成后写入）

### 项目一新增 bullet（接在现有 7 条之后）

> - **问题**：现有短期记忆采用 Redis List 滑动窗口（最近 40 条），超过窗口的早期对话被 LTRIM 丢弃；用户提问"还记得上周聊过的某事"时模型无法回忆。
>   **方案**：引入**会话历史 RAG**——每轮对话结束后用 OpenAI Embeddings 算向量存入 **ChromaDB**，下一轮根据当前用户输入做语义检索（Top-3）召回相关历史片段，融合到 system prompt；通过 `memory_rag_enabled` 配置可降级为纯滑动窗口。
>   **结果**：长对话场景下模型能回忆 40 轮以前的内容；语义检索比时间窗口更精确（相关 vs 时间相邻）。

> - **问题**：用户希望让 AI **基于自己上传的 PDF/Markdown/Word 文档**回答问题（如合同条款、产品说明），而不是凭模型通用知识答。
>   **方案**：基于 **LangChain** 实现完整 RAG pipeline——`Document Loaders`（PyPDFLoader / UnstructuredLoader）加载文档 → `RecursiveCharacterTextSplitter` 按 token 切分 → OpenAI Embeddings 向量化入 **ChromaDB**；问答时用 `as_retriever()` 召回 Top-K 片段构造 prompt；MySQL 维护知识库元信息（`agent_knowledge_base` / `agent_knowledge_document` 两张表，含 ingestion 状态机 PENDING→PROCESSING→READY/FAILED）。
>   **结果**：用户可创建多个知识库、关联到 Agent 会话；端到端文档问答闭环。

> - **问题**：现有手写 ReAct orchestrator 工程上可控，但**不是 2024+ 行业最新姿势**——LangGraph 已成为 Agent 编排事实标准。
>   **方案**：用 **LangGraph StateGraph** 实现 ReAct 流程作为对照引擎——定义 `AgentState`（messages / iteration / final_answer）+ `reason_node` / `tool_node` 两个节点 + 条件路由；通过 `engine` 配置在"手写"和"LangGraph"两种实现间切换。
>   **结果**：分场景选型——主链路保留手写保证可观测性，对照实现 LangGraph 验证业界主流方案；面试时可对比两种 trace 的可读性与性能差异。

### 技术栈段补充

```
LangChain / LangGraph / ChromaDB（嵌入式向量库）/ OpenAI Embeddings / RAG（会话历史 + 文档问答）
```

---

## 9. 执行规则

实施期间严格遵守：

1. **每完成一个 Day 的任务**，提交一个 commit，commit message 格式 `feat(rag): Day N - <内容>`；
2. **任何改动必须先在本方案中找到对应**——不在方案里的改动需要先回来更新方案；
3. **每个模块完成后立刻更新简历文档**（`求职简历-项目经历.md`），不要等全部做完再写；
4. **遇到方案错误必须停下**，改完方案再继续，不要边做边改；
5. **测试用例**：模块 A 至少 3 个 pytest 用例；模块 B 至少 5 个；模块 C 至少 2 个；
6. **每个 Sprint 结束做一次端到端手工验收**，符合本方案 §6 的"验收标准"才算完成；
7. **代码注释保持现有风格**：不写 What 注释，只写 Why；不在代码里堆叠 emoji。

---

## 10. 实施前的最后确认

执行前需要用户确认以下选择：

1. **Embedding 模型默认值**：建议 OpenAI `text-embedding-3-small`（便宜、快、效果够）。是否同意？
2. **向量库**：建议 **ChromaDB 嵌入式**（零部署、SQLite 持久化）。是否同意？也可改 Milvus（需 docker）。
3. **是否做模块 C（LangGraph）**：建议做，简历多一个亮点。是否同意？
4. **实施起止时间**：从 2026-05-02（今天）起？

确认后我会按 §6 时间表的 Day 1 开始实施。
