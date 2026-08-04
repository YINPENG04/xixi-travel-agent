"""初始化嘻嘻出行知识库并写入示例数据。"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility
from sentence_transformers import SentenceTransformer


COLLECTION_NAME = os.getenv("XIXI_MILVUS_COLLECTION", "xixi_travel_knowledge")
USER_MEMORY_COLLECTION_NAME = os.getenv(
    "XIXI_USER_MEMORY_COLLECTION", "xixi_user_memories"
)
MILVUS_HOST = os.getenv("MILVUS_HOST", "localhost")
MILVUS_PORT = os.getenv("MILVUS_PORT", "19530")
MODEL_NAME = os.getenv(
    "XIXI_EMBEDDING_MODEL",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
DATA_PATH = Path(__file__).parent / "data" / "xixi_knowledge.jsonl"
CONNECT_ATTEMPTS = int(os.getenv("XIXI_MILVUS_CONNECT_ATTEMPTS", "30"))
CONNECT_INTERVAL_SECONDS = float(
    os.getenv("XIXI_MILVUS_CONNECT_INTERVAL_SECONDS", "2")
)
RECREATE_COLLECTION = os.getenv("XIXI_RECREATE_COLLECTION", "false").lower() == "true"


def load_documents() -> list[dict]:
    with DATA_PATH.open("r", encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def prepare_collection(dimension: int) -> Collection:
    if utility.has_collection(COLLECTION_NAME) and RECREATE_COLLECTION:
        utility.drop_collection(COLLECTION_NAME)

    if utility.has_collection(COLLECTION_NAME):
        return Collection(COLLECTION_NAME)

    schema = CollectionSchema(
        fields=[
            FieldSchema("id", DataType.INT64, is_primary=True, auto_id=False),
            FieldSchema("category", DataType.VARCHAR, max_length=64),
            FieldSchema("title", DataType.VARCHAR, max_length=256),
            FieldSchema("content", DataType.VARCHAR, max_length=2048),
            FieldSchema("embedding", DataType.FLOAT_VECTOR, dim=dimension),
        ],
        description="嘻嘻出行地点、车型、规则与政策知识库",
    )
    collection = Collection(COLLECTION_NAME, schema=schema)
    collection.create_index(
        field_name="embedding",
        index_params={
            "metric_type": "COSINE",
            "index_type": "HNSW",
            "params": {"M": 16, "efConstruction": 200},
        },
    )
    return collection


def prepare_user_memory_collection(dimension: int) -> Collection:
    """创建与公共知识库隔离的用户长期记忆集合。"""
    if utility.has_collection(USER_MEMORY_COLLECTION_NAME):
        return Collection(USER_MEMORY_COLLECTION_NAME)

    schema = CollectionSchema(
        fields=[
            FieldSchema(
                "memory_id",
                DataType.VARCHAR,
                max_length=36,
                is_primary=True,
                auto_id=False,
            ),
            FieldSchema(
                "user_id",
                DataType.VARCHAR,
                max_length=128,
                is_partition_key=True,
            ),
            FieldSchema("category", DataType.VARCHAR, max_length=32),
            FieldSchema("memory_key", DataType.VARCHAR, max_length=64),
            FieldSchema("memory_value", DataType.VARCHAR, max_length=1000),
            FieldSchema("memory_version", DataType.INT64),
            FieldSchema("updated_at", DataType.INT64),
            FieldSchema("embedding", DataType.FLOAT_VECTOR, dim=dimension),
        ],
        description="嘻嘻出行按用户隔离的跨会话长期记忆",
    )
    collection = Collection(USER_MEMORY_COLLECTION_NAME, schema=schema)
    collection.create_index(
        field_name="embedding",
        index_params={
            "metric_type": "COSINE",
            "index_type": "HNSW",
            "params": {"M": 16, "efConstruction": 200},
        },
    )
    return collection


def connect_milvus() -> None:
    """等待 Milvus 就绪，避免容器首次启动时初始化脚本抢跑。"""
    for attempt in range(1, CONNECT_ATTEMPTS + 1):
        try:
            connections.connect(alias="default", host=MILVUS_HOST, port=MILVUS_PORT)
            utility.list_collections()
            return
        except Exception:
            connections.disconnect("default")
            if attempt == CONNECT_ATTEMPTS:
                raise
            time.sleep(CONNECT_INTERVAL_SECONDS)


def main() -> None:
    documents = load_documents()
    model = SentenceTransformer(MODEL_NAME)
    vectors = model.encode(
        [f"{document['title']}。{document['content']}" for document in documents],
        normalize_embeddings=True,
    )

    connect_milvus()
    collection = prepare_collection(int(vectors.shape[1]))
    memory_collection = prepare_user_memory_collection(int(vectors.shape[1]))
    collection.upsert(
        [
            [document["id"] for document in documents],
            [document["category"] for document in documents],
            [document["title"] for document in documents],
            [document["content"] for document in documents],
            vectors.tolist(),
        ]
    )
    collection.flush()
    collection.load()
    memory_collection.load()
    print(f"已写入 {collection.num_entities} 条嘻嘻出行知识数据。")
    print(
        f"已准备用户长期记忆集合 {USER_MEMORY_COLLECTION_NAME}，"
        f"当前 {memory_collection.num_entities} 条。"
    )


if __name__ == "__main__":
    main()
