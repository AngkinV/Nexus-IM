"""Module B knowledge-base ingestion pipeline.

Submodules:
- loaders   — extension-dispatched LangChain Document Loaders
              (pdf / md / txt / docx).
- splitter  — RecursiveCharacterTextSplitter pre-configured per knowledge base.
- ingestion — end-to-end load → split → embed → write to ChromaDB
              `kb_chunks` collection.

The Python service is the only writer of the `kb_chunks` collection. Java
side owns the row-level metadata in agent_knowledge_document and is
responsible for status reconciliation via the upload-callback channel.
"""
