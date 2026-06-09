from __future__ import annotations

import json
import sqlite3
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

from todo_backend.server import create_server
from todo_backend.database import TodoDatabase


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

    def register(self, client: ApiClient | None = None, username: str = "alice") -> dict:
        client = client or self.client
        response = client.request(
            "POST",
            "/api/auth/register",
            {"username": username, "email": f"{username}@example.com", "password": "secret123"},
        )
        self.assertEqual(response["code"], 200)
        client.token = response["data"]["token"]
        return response["data"]

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

    def test_circle_feed_likes_and_comments(self) -> None:
        alice = self.register(username="alice")
        circle = self.client.request("GET", "/api/me/circle")
        self.assertEqual(circle["code"], 200)
        self.assertEqual(circle["data"]["circleId"], alice["user"]["circleId"])

        host, port = self.server.server_address
        bob_client = ApiClient(f"http://{host}:{port}")
        self.register(bob_client, username="bob")

        request = bob_client.request(
            "POST",
            "/api/friends/requests",
            {"circleId": alice["user"]["circleId"], "introduction": "我是 bob，想互相看看公开 idea"},
        )
        self.assertEqual(request["code"], 200)
        self.assertEqual(request["data"]["status"], "pending")

        duplicate = bob_client.request(
            "POST",
            "/api/friends/requests",
            {"circleId": alice["user"]["circleId"], "introduction": "重复申请"},
        )
        self.assertEqual(duplicate["code"], 409)

        incoming = self.client.request("GET", "/api/friends/requests/incoming")
        self.assertEqual(incoming["code"], 200)
        self.assertEqual(incoming["data"]["items"][0]["requester"]["username"], "bob")

        accepted = self.client.request(
            "POST",
            f"/api/friends/requests/{request['data']['id']}/accept",
        )
        self.assertEqual(accepted["code"], 200)
        self.assertEqual(accepted["data"]["status"], "accepted")

        alice_friends = self.client.request("GET", "/api/friends")
        bob_friends = bob_client.request("GET", "/api/friends")
        self.assertEqual(alice_friends["data"]["items"][0]["owner"]["username"], "bob")
        self.assertEqual(bob_friends["data"]["items"][0]["owner"]["username"], "alice")

        private_idea = self.client.request(
            "POST",
            "/api/ideas",
            {
                "id": str(uuid.uuid4()),
                "title": "私密 idea",
                "status": "想做",
                "category": "其他",
                "priority": "中",
                "visibility": "private",
            },
        )
        public_idea = self.client.request(
            "POST",
            "/api/ideas",
            {
                "id": str(uuid.uuid4()),
                "title": "公开 idea",
                "content": "给朋友看看",
                "status": "想做",
                "category": "程序",
                "priority": "高",
                "visibility": "circle",
            },
        )
        self.assertEqual(private_idea["code"], 200)
        self.assertEqual(public_idea["code"], 200)

        feed = bob_client.request("GET", "/api/feed")
        self.assertEqual(feed["code"], 200)
        titles = [item["title"] for item in feed["data"]["items"]]
        self.assertIn("公开 idea", titles)
        self.assertNotIn("私密 idea", titles)
        self.assertEqual(feed["data"]["items"][0]["author"]["username"], "alice")

        own_feed = self.client.request("GET", "/api/feed")
        own_titles = [item["title"] for item in own_feed["data"]["items"]]
        self.assertIn("公开 idea", own_titles)

        idea_id = public_idea["data"]["id"]
        detail = bob_client.request("GET", f"/api/ideas/{idea_id}")
        self.assertEqual(detail["code"], 200)
        self.assertEqual(detail["data"]["author"]["username"], "alice")

        liked = bob_client.request("POST", f"/api/ideas/{idea_id}/like")
        self.assertEqual(liked["data"]["likeCount"], 1)
        self.assertTrue(liked["data"]["likedByMe"])

        comment = bob_client.request(
            "POST",
            f"/api/ideas/{idea_id}/comments",
            {"content": "这个点子可以先做 MVP"},
        )
        self.assertEqual(comment["code"], 200)
        self.assertEqual(comment["data"]["author"]["username"], "bob")

        comments = self.client.request("GET", f"/api/ideas/{idea_id}/comments")
        self.assertEqual(comments["code"], 200)
        self.assertEqual(comments["data"]["items"][0]["content"], "这个点子可以先做 MVP")

        unliked = bob_client.request("DELETE", f"/api/ideas/{idea_id}/like")
        self.assertEqual(unliked["data"]["likeCount"], 0)

        removed = self.client.request("DELETE", f"/api/friends/{alice_friends['data']['items'][0]['circleId']}")
        self.assertEqual(removed["code"], 200)
        hidden = bob_client.request("GET", f"/api/ideas/{idea_id}")
        self.assertEqual(hidden["code"], 403)

    def test_migrates_v1_database_before_visibility_index(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "old.sqlite3"
            with sqlite3.connect(db_path) as conn:
                conn.executescript(
                    """
                    CREATE TABLE users (
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE,
                        email TEXT NOT NULL UNIQUE,
                        password_salt TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    );

                    CREATE TABLE tasks (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL,
                        category TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        deleted_at TEXT,
                        sync_seq INTEGER NOT NULL
                    );

                    CREATE TABLE reminder_settings (
                        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                        enabled INTEGER NOT NULL,
                        frequency_seconds INTEGER NOT NULL,
                        updated_at TEXT NOT NULL,
                        sync_seq INTEGER NOT NULL
                    );

                    CREATE TABLE processed_operations (
                        operation_id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        task_id TEXT,
                        operation_type TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    );

                    CREATE TABLE sync_meta (
                        key TEXT PRIMARY KEY,
                        value INTEGER NOT NULL
                    );
                    """
                )
                conn.execute(
                    """
                    INSERT INTO users(id, username, email, password_salt, password_hash, created_at, updated_at)
                    VALUES('u1', 'olduser', 'old@example.com', 'salt', 'hash', '2026-06-08T12:00:00Z', '2026-06-08T12:00:00Z')
                    """
                )
                conn.execute(
                    """
                    INSERT INTO tasks(id, user_id, title, content, status, category, priority, created_at, updated_at, version, deleted_at, sync_seq)
                    VALUES('t1', 'u1', '旧 idea', '', '想做', '其他', '中', '2026-06-08T12:00:00Z', '2026-06-08T12:00:00Z', 1, NULL, 1)
                    """
                )

            TodoDatabase(db_path)

            with sqlite3.connect(db_path) as conn:
                user_columns = {row[1] for row in conn.execute("PRAGMA table_info(users)").fetchall()}
                task_columns = {row[1] for row in conn.execute("PRAGMA table_info(tasks)").fetchall()}
                indexes = {row[1] for row in conn.execute("PRAGMA index_list(tasks)").fetchall()}
                circle_id = conn.execute("SELECT circle_id FROM users WHERE id = 'u1'").fetchone()[0]
                visibility = conn.execute("SELECT visibility FROM tasks WHERE id = 't1'").fetchone()[0]

            self.assertIn("circle_id", user_columns)
            self.assertIn("visibility", task_columns)
            self.assertIn("idx_tasks_visibility", indexes)
            self.assertTrue(circle_id.startswith("ID-"))
            self.assertEqual(visibility, "private")

    def test_migrates_old_single_direction_circle_members_to_friends(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "old-friends.sqlite3"
            with sqlite3.connect(db_path) as conn:
                conn.executescript(
                    """
                    CREATE TABLE users (
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE,
                        email TEXT NOT NULL UNIQUE,
                        circle_id TEXT,
                        password_salt TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    );

                    CREATE TABLE circle_members (
                        circle_id TEXT NOT NULL,
                        member_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        joined_at TEXT NOT NULL,
                        PRIMARY KEY(circle_id, member_user_id)
                    );

                    CREATE TABLE tasks (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL,
                        category TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        visibility TEXT NOT NULL DEFAULT 'private',
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        deleted_at TEXT,
                        sync_seq INTEGER NOT NULL
                    );

                    CREATE TABLE reminder_settings (
                        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                        enabled INTEGER NOT NULL,
                        frequency_seconds INTEGER NOT NULL,
                        updated_at TEXT NOT NULL,
                        sync_seq INTEGER NOT NULL
                    );

                    CREATE TABLE processed_operations (
                        operation_id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        task_id TEXT,
                        operation_type TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    );

                    CREATE TABLE sync_meta (
                        key TEXT PRIMARY KEY,
                        value INTEGER NOT NULL
                    );
                    """
                )
                conn.execute(
                    """
                    INSERT INTO users(id, username, email, circle_id, password_salt, password_hash, created_at, updated_at)
                    VALUES('alice-id', 'alice', 'alice@example.com', 'ID-ALICE123', 'salt', 'hash', '2026-06-08T12:00:00Z', '2026-06-08T12:00:00Z')
                    """
                )
                conn.execute(
                    """
                    INSERT INTO users(id, username, email, circle_id, password_salt, password_hash, created_at, updated_at)
                    VALUES('bob-id', 'bob', 'bob@example.com', 'ID-BOB12345', 'salt', 'hash', '2026-06-08T12:00:00Z', '2026-06-08T12:00:00Z')
                    """
                )
                conn.execute(
                    """
                    INSERT INTO circle_members(circle_id, member_user_id, joined_at)
                    VALUES('ID-ALICE123', 'bob-id', '2026-06-08T12:00:00Z')
                    """
                )

            TodoDatabase(db_path)

            with sqlite3.connect(db_path) as conn:
                rows = conn.execute(
                    """
                    SELECT circle_id, member_user_id
                    FROM circle_members
                    ORDER BY circle_id, member_user_id
                    """
                ).fetchall()

            self.assertEqual(
                rows,
                [
                    ("ID-ALICE123", "bob-id"),
                    ("ID-BOB12345", "alice-id"),
                ],
            )


if __name__ == "__main__":
    unittest.main()
