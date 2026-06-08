from __future__ import annotations

import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from todo_backend.server import create_server


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url
        self.token: str | None = None

    def request(self, method: str, path: str, body: dict | None = None) -> dict:
        data = None
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if body is not None:
            data = json.dumps(body).encode("utf-8")
        encoded_path = urllib.parse.quote(path, safe="/?=&")
        req = urllib.request.Request(
            f"{self.base_url}{encoded_path}",
            data=data,
            method=method,
            headers=headers,
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            try:
                return json.loads(exc.read().decode("utf-8"))
            finally:
                exc.close()


class TodoApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        db_path = Path(self.tmp.name) / "todo.sqlite3"
        self.server = create_server("127.0.0.1", 0, db_path)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.client = ApiClient(f"http://{host}:{port}")

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.tmp.cleanup()

    def register(self) -> None:
        response = self.client.request(
            "POST",
            "/api/auth/register",
            {"username": "alice", "email": "alice@example.com", "password": "secret123"},
        )
        self.assertEqual(response["code"], 200)
        self.client.token = response["data"]["token"]

    def test_task_crud_conflict_and_pull(self) -> None:
        self.register()
        task_id = str(uuid.uuid4())
        created = self.client.request(
            "POST",
            "/api/tasks",
            {
                "id": task_id,
                "title": "写后端",
                "content": "先把接口跑通",
                "status": "进行中",
                "category": "程序",
                "priority": "高",
            },
        )
        self.assertEqual(created["code"], 200)
        self.assertEqual(created["data"]["version"], 1)

        listed = self.client.request("GET", "/api/tasks?status=进行中")
        self.assertEqual(listed["data"]["total"], 1)

        conflict = self.client.request(
            "PUT",
            f"/api/tasks/{task_id}",
            {"version": 99, "title": "冲突标题"},
        )
        self.assertEqual(conflict["code"], 409)
        self.assertEqual(conflict["data"]["serverTask"]["id"], task_id)

        updated = self.client.request(
            "PUT",
            f"/api/tasks/{task_id}",
            {"version": 1, "status": "完成"},
        )
        self.assertEqual(updated["code"], 200)
        self.assertEqual(updated["data"]["version"], 2)

        pulled = self.client.request("GET", "/api/sync/pull")
        self.assertTrue(pulled["data"]["isFullSync"])
        self.assertEqual(len(pulled["data"]["tasks"]), 1)

    def test_sync_push_is_idempotent_and_deletes_tombstone(self) -> None:
        self.register()
        task_id = str(uuid.uuid4())
        operation_id = str(uuid.uuid4())
        pushed = self.client.request(
            "POST",
            "/api/sync/push",
            {
                "operations": [
                    {
                        "operationId": operation_id,
                        "taskId": task_id,
                        "operationType": "create",
                        "baseVersion": 0,
                        "localUpdatedAt": "2026-06-08T12:00:00Z",
                        "payload": {
                            "title": "离线创建",
                            "content": "",
                            "status": "想做",
                            "category": "其他",
                            "priority": "中",
                        },
                    }
                ]
            },
        )
        self.assertEqual(pushed["code"], 200)
        self.assertEqual(pushed["data"]["accepted"], [operation_id])

        repeated = self.client.request(
            "POST",
            "/api/sync/push",
            {
                "operations": [
                    {
                        "operationId": operation_id,
                        "taskId": task_id,
                        "operationType": "create",
                        "baseVersion": 0,
                        "localUpdatedAt": "2026-06-08T12:00:00Z",
                        "payload": {"title": "重复提交"},
                    }
                ]
            },
        )
        self.assertEqual(repeated["data"]["accepted"], [operation_id])
        self.assertEqual(repeated["data"]["conflicts"], [])

        deleted = self.client.request("DELETE", f"/api/tasks/{task_id}", {"version": 1})
        self.assertEqual(deleted["code"], 200)
        self.assertIsNotNone(deleted["data"]["deletedAt"])

        pulled = self.client.request("GET", "/api/sync/pull?cursor=0")
        tombstones = [task for task in pulled["data"]["tasks"] if task["id"] == task_id]
        self.assertEqual(len(tombstones), 1)
        self.assertIsNotNone(tombstones[0]["deletedAt"])


if __name__ == "__main__":
    unittest.main()
