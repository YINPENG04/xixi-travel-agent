"""初始化嘻嘻出行知识库并写入示例数据。"""

from __future__ import annotations

import json
import os
from pathlib import Path

from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility
from sentence_transformers import SentenceTransformer


COLLECTION_NAME = os.getenv("XIXI_MILVUS_COLLECTION", "xixi_travel_knowledge")
MILVUS_HOST = os.getenv("MILVUS_HOST", "localhost")
MILVUS_PORT = os.getenv("MILVUS_PORT", "19530")
MODEL_NAME = os.getenv(
    "XIXI_EMBEDDING_MODEL",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
DATA_PATH = Path(__file__).parent / "data" / "xixi_knowledge.jsonl"


def load_documents() -> list[dict]:
    with DATA_PATH.open("r", encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def recreate_collection(dimension: int) -> Collection:
    if utility.has_collection(COLLECTION_NAME):
        utility.drop_collection(COLLECTION_NAME)

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


def main() -> None:
    documents = load_documents()
    model = SentenceTransformer(MODEL_NAME)
    vectors = model.encode(
        [f"{document['title']}。{document['content']}" for document in documents],
        normalize_embeddings=True,
    )

    connections.connect(alias="default", host=MILVUS_HOST, port=MILVUS_PORT)
    collection = recreate_collection(int(vectors.shape[1]))
    collection.insert(
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
    print(f"已写入 {collection.num_entities} 条嘻嘻出行知识数据。")


if __name__ == "__main__":
    main()
