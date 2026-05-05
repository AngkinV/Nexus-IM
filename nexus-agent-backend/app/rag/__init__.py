"""RAG infrastructure shared by Module A (memory) and Module B (knowledge base).

Submodules:
- embeddings   — OpenAI Embeddings wrapper with BYOK + per-tuple caching.
- vectorstore  — ChromaDB persistent client lifecycle + canonical collections.
- memory_rag   — (Day 3) per-turn write + similarity retrieval over `memory_chunks`.
- knowledge_rag — (Sprint 2) document ingestion + retrieval over `kb_chunks`.
"""
