from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import sqlite3
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


TASK_STATUSES = {"想做", "进行中", "完成", "放弃"}
TASK_CATEGORIES = {"游戏", "程序", "技能", "其他"}
TASK_PRIORITIES = {"高", "中", "低"}
TASK_VISIBILITIES = {"private", "circle"}
OPERATION_TYPES = {"create", "update", "delete"}


class DatabaseError(Exception):
    def __init__(self, message: str, status: int = 400, data: dict[str, Any] | None = None):
        super().__init__(message)
        self.message = message
        self.status = status
        self.data = data or {}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_cursor(cursor: str | None) -> int:
    if cursor in (None, ""):
        return 0
    try:
        value = int(cursor)
    except ValueError as exc:
        raise DatabaseError("cursor must be an integer string") from exc
    if value < 0:
        raise DatabaseError("cursor must be non-negative")
    return value


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def hash_password(password: str, salt: bytes | None = None) -> tuple[str, str]:
    salt = salt or secrets.token_bytes(16)
    password_hash = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 200_000)
    return (
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(password_hash).decode("ascii"),
    )


def verify_password(password: str, salt_b64: str, expected_hash_b64: str) -> bool:
    salt = base64.b64decode(salt_b64.encode("ascii"))
    _, actual_hash_b64 = hash_password(password, salt)
    return hmac.compare_digest(actual_hash_b64, expected_hash_b64)


def row_to_task(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "title": row["title"],
        "content": row["content"],
        "status": row["status"],
        "category": row["category"],
        "priority": row["priority"],
        "visibility": row["visibility"],
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
        "version": row["version"],
        "deletedAt": row["deleted_at"],
    }


def row_to_user(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "username": row["username"],
        "email": row["email"],
        "circleId": row["circle_id"],
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
    }


def row_to_public_user(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "username": row["username"],
        "circleId": row["circle_id"],
    }


def row_to_reminder(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "enabled": bool(row["enabled"]),
        "frequencySeconds": row["frequency_seconds"],
        "updatedAt": row["updated_at"],
    }


def row_to_comment(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "ideaId": row["idea_id"],
        "author": {
            "id": row["author_id"],
            "username": row["author_username"],
            "circleId": row["author_circle_id"],
        },
        "content": row["content"],
        "createdAt": row["created_at"],
        "deletedAt": row["deleted_at"],
    }


def row_to_friend_request(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "requester": {
            "id": row["requester_id"],
            "username": row["requester_username"],
            "circleId": row["requester_circle_id"],
        },
        "target": {
            "id": row["target_id"],
            "username": row["target_username"],
            "circleId": row["target_circle_id"],
        },
        "introduction": row["introduction"],
        "status": row["status"],
        "createdAt": row["created_at"],
        "decidedAt": row["decided_at"],
    }


def generate_circle_id() -> str:
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "ID-" + "".join(secrets.choice(alphabet) for _ in range(8))


class TodoDatabase:
    def __init__(self, db_path: str | Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self.init_schema()

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        return conn

    def init_schema(self) -> None:
        with self._lock, self.connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE,
                    email TEXT NOT NULL UNIQUE,
                    circle_id TEXT,
                    password_salt TEXT NOT NULL,
                    password_hash TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS auth_tokens (
                    token_hash TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    expires_at TEXT NOT NULL,
                    revoked_at TEXT,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS tasks (
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

                CREATE INDEX IF NOT EXISTS idx_tasks_user_sync ON tasks(user_id, sync_seq);
                CREATE INDEX IF NOT EXISTS idx_tasks_user_visible ON tasks(user_id, deleted_at);

                CREATE TABLE IF NOT EXISTS circle_members (
                    circle_id TEXT NOT NULL,
                    member_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    joined_at TEXT NOT NULL,
                    PRIMARY KEY(circle_id, member_user_id)
                );

                CREATE INDEX IF NOT EXISTS idx_circle_members_user ON circle_members(member_user_id);

                CREATE TABLE IF NOT EXISTS friend_requests (
                    id TEXT PRIMARY KEY,
                    requester_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    target_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    introduction TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    decided_at TEXT
                );

                CREATE INDEX IF NOT EXISTS idx_friend_requests_target_status ON friend_requests(target_id, status, created_at);
                CREATE INDEX IF NOT EXISTS idx_friend_requests_requester_status ON friend_requests(requester_id, status, created_at);

                CREATE TABLE IF NOT EXISTS idea_likes (
                    idea_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(idea_id, user_id)
                );

                CREATE INDEX IF NOT EXISTS idx_idea_likes_user ON idea_likes(user_id);

                CREATE TABLE IF NOT EXISTS idea_comments (
                    id TEXT PRIMARY KEY,
                    idea_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                    author_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    deleted_at TEXT
                );

                CREATE INDEX IF NOT EXISTS idx_idea_comments_idea ON idea_comments(idea_id, created_at);

                CREATE TABLE IF NOT EXISTS reminder_settings (
                    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                    enabled INTEGER NOT NULL,
                    frequency_seconds INTEGER NOT NULL,
                    updated_at TEXT NOT NULL,
                    sync_seq INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS processed_operations (
                    operation_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    task_id TEXT,
                    operation_type TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS sync_meta (
                    key TEXT PRIMARY KEY,
                    value INTEGER NOT NULL
                );
                """
            )
            conn.execute(
                "INSERT OR IGNORE INTO sync_meta(key, value) VALUES('global_seq', 0)"
            )
            self._migrate_schema(conn)

    def _migrate_schema(self, conn: sqlite3.Connection) -> None:
        user_columns = self._table_columns(conn, "users")
        if "circle_id" not in user_columns:
            conn.execute("ALTER TABLE users ADD COLUMN circle_id TEXT")

        task_columns = self._table_columns(conn, "tasks")
        if "visibility" not in task_columns:
            conn.execute("ALTER TABLE tasks ADD COLUMN visibility TEXT NOT NULL DEFAULT 'private'")

        users_without_circle = conn.execute(
            "SELECT id FROM users WHERE circle_id IS NULL OR circle_id = ''"
        ).fetchall()
        for row in users_without_circle:
            conn.execute(
                "UPDATE users SET circle_id = ? WHERE id = ?",
                (self._unique_circle_id(conn), row["id"]),
            )

        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_circle_id ON users(circle_id)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tasks_visibility ON tasks(visibility, updated_at)")
        self._backfill_bidirectional_friendships(conn)

    def _backfill_bidirectional_friendships(self, conn: sqlite3.Connection) -> None:
        now = utc_now()
        conn.execute(
            """
            INSERT OR IGNORE INTO circle_members(circle_id, member_user_id, joined_at)
            SELECT member.circle_id, owner.id, ?
            FROM circle_members existing
            JOIN users owner ON owner.circle_id = existing.circle_id
            JOIN users member ON member.id = existing.member_user_id
            WHERE owner.id != member.id
            """,
            (now,),
        )

    def _table_columns(self, conn: sqlite3.Connection, table_name: str) -> set[str]:
        return {row["name"] for row in conn.execute(f"PRAGMA table_info({table_name})").fetchall()}

    def _unique_circle_id(self, conn: sqlite3.Connection) -> str:
        for _ in range(20):
            circle_id = generate_circle_id()
            exists = conn.execute(
                "SELECT 1 FROM users WHERE circle_id = ?",
                (circle_id,),
            ).fetchone()
            if not exists:
                return circle_id
        raise DatabaseError("failed to generate circle id", 500)

    def _next_sync_seq(self, conn: sqlite3.Connection) -> int:
        row = conn.execute(
            "SELECT value FROM sync_meta WHERE key = 'global_seq'"
        ).fetchone()
        current = int(row["value"]) if row else 0
        next_value = current + 1
        conn.execute(
            "UPDATE sync_meta SET value = ? WHERE key = 'global_seq'",
            (next_value,),
        )
        return next_value

    def create_user(self, username: str, email: str, password: str) -> dict[str, Any]:
        username = (username or "").strip()
        email = (email or "").strip().lower()
        if not 3 <= len(username) <= 50:
            raise DatabaseError("username must be 3-50 characters")
        if "@" not in email or len(email) > 255:
            raise DatabaseError("email is invalid")
        if len(password or "") < 6:
            raise DatabaseError("password must be at least 6 characters")

        now = utc_now()
        user_id = str(uuid.uuid4())
        salt, password_hash = hash_password(password)
        with self._lock, self.connect() as conn:
            try:
                seq = self._next_sync_seq(conn)
                circle_id = self._unique_circle_id(conn)
                conn.execute(
                    """
                    INSERT INTO users(id, username, email, circle_id, password_salt, password_hash, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (user_id, username, email, circle_id, salt, password_hash, now, now),
                )
                conn.execute(
                    """
                    INSERT INTO reminder_settings(user_id, enabled, frequency_seconds, updated_at, sync_seq)
                    VALUES(?, 0, 3600, ?, ?)
                    """,
                    (user_id, now, seq),
                )
            except sqlite3.IntegrityError as exc:
                raise DatabaseError("username or email already exists", 409) from exc
            row = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
        return row_to_user(row)

    def authenticate(self, username: str, password: str) -> dict[str, Any]:
        username = (username or "").strip()
        with self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM users WHERE username = ? OR email = ?",
                (username, username.lower()),
            ).fetchone()
        if not row or not verify_password(password or "", row["password_salt"], row["password_hash"]):
            raise DatabaseError("invalid username or password", 401)
        return row_to_user(row)

    def issue_token(self, user_id: str) -> dict[str, Any]:
        token = secrets.token_urlsafe(32)
        now_dt = datetime.now(timezone.utc)
        expires_dt = now_dt + timedelta(hours=1)
        now = now_dt.isoformat(timespec="seconds").replace("+00:00", "Z")
        expires_at = expires_dt.isoformat(timespec="seconds").replace("+00:00", "Z")
        with self._lock, self.connect() as conn:
            conn.execute(
                """
                INSERT INTO auth_tokens(token_hash, user_id, expires_at, created_at)
                VALUES(?, ?, ?, ?)
                """,
                (hash_token(token), user_id, expires_at, now),
            )
        return {"token": token, "expiresAt": expires_at}

    def revoke_token(self, token: str) -> None:
        with self._lock, self.connect() as conn:
            conn.execute(
                "UPDATE auth_tokens SET revoked_at = ? WHERE token_hash = ?",
                (utc_now(), hash_token(token)),
            )

    def user_for_token(self, token: str) -> dict[str, Any] | None:
        if not token:
            return None
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT users.*
                FROM auth_tokens
                JOIN users ON users.id = auth_tokens.user_id
                WHERE auth_tokens.token_hash = ?
                  AND auth_tokens.revoked_at IS NULL
                  AND auth_tokens.expires_at > ?
                """,
                (hash_token(token), utc_now()),
            ).fetchone()
        return row_to_user(row) if row else None

    def create_task(self, user_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        task = normalize_task_payload(payload, require_title=True)
        task_id = payload.get("id") or str(uuid.uuid4())
        now = utc_now()
        created_at = payload.get("createdAt") or now
        updated_at = payload.get("updatedAt") or now
        version = int(payload.get("version") or 1)
        deleted_at = payload.get("deletedAt")
        with self._lock, self.connect() as conn:
            seq = self._next_sync_seq(conn)
            try:
                conn.execute(
                    """
                    INSERT INTO tasks(
                        id, user_id, title, content, status, category, priority,
                        visibility, created_at, updated_at, version, deleted_at, sync_seq
                    )
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        task_id,
                        user_id,
                        task["title"],
                        task["content"],
                        task["status"],
                        task["category"],
                        task["priority"],
                        task["visibility"],
                        created_at,
                        updated_at,
                        version,
                        deleted_at,
                        seq,
                    ),
                )
            except sqlite3.IntegrityError as exc:
                raise DatabaseError("task id already exists", 409) from exc
            row = conn.execute(
                "SELECT * FROM tasks WHERE id = ? AND user_id = ?",
                (task_id, user_id),
            ).fetchone()
        return row_to_task(row)

    def update_task(self, user_id: str, task_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        if "version" not in payload:
            raise DatabaseError("version is required")
        patch = normalize_task_payload(payload, require_title=False)
        with self._lock, self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM tasks WHERE id = ? AND user_id = ?",
                (task_id, user_id),
            ).fetchone()
            if not row:
                raise DatabaseError("task not found", 404)
            if int(payload["version"]) != int(row["version"]):
                raise DatabaseError(
                    "task version conflict",
                    409,
                    {"serverTask": row_to_task(row)},
                )

            values = {
                "title": row["title"],
                "content": row["content"],
                "status": row["status"],
                "category": row["category"],
                "priority": row["priority"],
                "visibility": row["visibility"],
            }
            values.update(patch)
            updated_at = payload.get("updatedAt") or utc_now()
            deleted_at = payload.get("deletedAt", row["deleted_at"])
            next_version = int(row["version"]) + 1
            seq = self._next_sync_seq(conn)
            conn.execute(
                """
                UPDATE tasks
                SET title = ?, content = ?, status = ?, category = ?, priority = ?, visibility = ?,
                    updated_at = ?, version = ?, deleted_at = ?, sync_seq = ?
                WHERE id = ? AND user_id = ?
                """,
                (
                    values["title"],
                    values["content"],
                    values["status"],
                    values["category"],
                    values["priority"],
                    values["visibility"],
                    updated_at,
                    next_version,
                    deleted_at,
                    seq,
                    task_id,
                    user_id,
                ),
            )
            updated = conn.execute(
                "SELECT * FROM tasks WHERE id = ? AND user_id = ?",
                (task_id, user_id),
            ).fetchone()
        return row_to_task(updated)

    def delete_task(self, user_id: str, task_id: str, version: int | None = None) -> dict[str, Any]:
        with self._lock, self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM tasks WHERE id = ? AND user_id = ?",
                (task_id, user_id),
            ).fetchone()
            if not row:
                raise DatabaseError("task not found", 404)
            if version is not None and int(version) != int(row["version"]):
                raise DatabaseError(
                    "task version conflict",
                    409,
                    {"serverTask": row_to_task(row)},
                )
            deleted_at = utc_now()
            seq = self._next_sync_seq(conn)
            conn.execute(
                """
                UPDATE tasks
                SET deleted_at = ?, updated_at = ?, version = ?, sync_seq = ?
                WHERE id = ? AND user_id = ?
                """,
                (deleted_at, deleted_at, int(row["version"]) + 1, seq, task_id, user_id),
            )
            updated = conn.execute(
                "SELECT * FROM tasks WHERE id = ? AND user_id = ?",
                (task_id, user_id),
            ).fetchone()
        return row_to_task(updated)

    def get_task(self, user_id: str, task_id: str, include_deleted: bool = False) -> dict[str, Any]:
        with self.connect() as conn:
            row = self._require_viewable_idea(user_id, task_id, conn, include_deleted=include_deleted)
            return self._idea_detail(conn, user_id, row["id"])

    def list_tasks(self, user_id: str, query: dict[str, list[str]]) -> dict[str, Any]:
        where = ["user_id = ?", "deleted_at IS NULL"]
        params: list[Any] = [user_id]

        for key, allowed in (
            ("status", TASK_STATUSES),
            ("category", TASK_CATEGORIES),
            ("priority", TASK_PRIORITIES),
        ):
            value = first(query.get(key))
            if value:
                if value not in allowed:
                    raise DatabaseError(f"{key} is invalid")
                where.append(f"{key} = ?")
                params.append(value)

        search = first(query.get("search"))
        if search:
            where.append("(title LIKE ? OR content LIKE ?)")
            like_value = f"%{search}%"
            params.extend([like_value, like_value])

        sort_map = {
            "createdAt": "created_at",
            "updatedAt": "updated_at",
            "title": "title",
            "status": "status",
            "category": "category",
            "priority": "priority",
        }
        sort_by = first(query.get("sortBy")) or "updatedAt"
        sort_column = sort_map.get(sort_by)
        if not sort_column:
            raise DatabaseError("sortBy is invalid")
        sort_order = (first(query.get("sortOrder")) or "desc").lower()
        if sort_order not in {"asc", "desc"}:
            raise DatabaseError("sortOrder must be asc or desc")

        page = max(1, parse_int(first(query.get("page")), 1))
        page_size = min(100, max(1, parse_int(first(query.get("pageSize")), 20)))
        offset = (page - 1) * page_size
        where_sql = " AND ".join(where)

        with self.connect() as conn:
            total = conn.execute(
                f"SELECT COUNT(*) AS total FROM tasks WHERE {where_sql}",
                params,
            ).fetchone()["total"]
            rows = conn.execute(
                f"""
                SELECT * FROM tasks
                WHERE {where_sql}
                ORDER BY {sort_column} {sort_order.upper()}
                LIMIT ? OFFSET ?
                """,
                [*params, page_size, offset],
            ).fetchall()
        return {
            "items": [row_to_task(row) for row in rows],
            "page": page,
            "pageSize": page_size,
            "total": total,
        }

    def random_task(self, user_id: str) -> dict[str, Any] | None:
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT * FROM tasks
                WHERE user_id = ?
                  AND deleted_at IS NULL
                  AND status NOT IN ('完成', '放弃')
                ORDER BY RANDOM()
                LIMIT 1
                """,
                (user_id,),
            ).fetchone()
        return row_to_task(row) if row else None

    def get_reminder(self, user_id: str) -> dict[str, Any]:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM reminder_settings WHERE user_id = ?",
                (user_id,),
            ).fetchone()
        if not row:
            raise DatabaseError("reminder settings not found", 404)
        return row_to_reminder(row)

    def update_reminder(self, user_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        enabled = payload.get("enabled")
        frequency_seconds = payload.get("frequencySeconds")
        if enabled is None or frequency_seconds is None:
            raise DatabaseError("enabled and frequencySeconds are required")
        frequency_seconds = int(frequency_seconds)
        if not 300 <= frequency_seconds <= 86_400:
            raise DatabaseError("frequencySeconds must be between 300 and 86400")
        now = utc_now()
        with self._lock, self.connect() as conn:
            seq = self._next_sync_seq(conn)
            conn.execute(
                """
                INSERT INTO reminder_settings(user_id, enabled, frequency_seconds, updated_at, sync_seq)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                  enabled = excluded.enabled,
                  frequency_seconds = excluded.frequency_seconds,
                  updated_at = excluded.updated_at,
                  sync_seq = excluded.sync_seq
                """,
                (user_id, 1 if bool(enabled) else 0, frequency_seconds, now, seq),
            )
            row = conn.execute(
                "SELECT * FROM reminder_settings WHERE user_id = ?",
                (user_id,),
            ).fetchone()
        return row_to_reminder(row)

    def pull_changes(self, user_id: str, cursor: str | None) -> dict[str, Any]:
        is_full_sync = cursor in (None, "")
        cursor_value = parse_cursor(cursor)
        task_where = "user_id = ?" if is_full_sync else "user_id = ? AND sync_seq > ?"
        task_params: list[Any] = [user_id] if is_full_sync else [user_id, cursor_value]
        reminder_where = "user_id = ?" if is_full_sync else "user_id = ? AND sync_seq > ?"
        reminder_params: list[Any] = [user_id] if is_full_sync else [user_id, cursor_value]

        with self.connect() as conn:
            task_rows = conn.execute(
                f"SELECT * FROM tasks WHERE {task_where} ORDER BY sync_seq ASC",
                task_params,
            ).fetchall()
            reminder_row = conn.execute(
                f"SELECT * FROM reminder_settings WHERE {reminder_where}",
                reminder_params,
            ).fetchone()
            next_cursor = self._user_max_sync_seq(conn, user_id)
        return {
            "tasks": [row_to_task(row) for row in task_rows],
            "reminderSettings": row_to_reminder(reminder_row) if reminder_row else None,
            "nextCursor": str(next_cursor),
            "isFullSync": is_full_sync,
        }

    def push_operations(self, user_id: str, operations: list[dict[str, Any]]) -> dict[str, Any]:
        if len(operations) > 100:
            raise DatabaseError("at most 100 operations can be pushed at once")

        accepted: list[str] = []
        conflicts: list[dict[str, Any]] = []
        with self._lock, self.connect() as conn:
            for operation in operations:
                operation_id = operation.get("operationId")
                operation_type = operation.get("operationType")
                task_id = operation.get("taskId")
                payload = operation.get("payload") or {}
                base_version = operation.get("baseVersion")
                local_updated_at = operation.get("localUpdatedAt") or utc_now()

                if not operation_id or not is_uuidish(operation_id):
                    raise DatabaseError("operationId must be a UUID")
                if operation_type not in OPERATION_TYPES:
                    raise DatabaseError("operationType is invalid")
                if not task_id or not is_uuidish(task_id):
                    raise DatabaseError("taskId must be a UUID")
                if not isinstance(payload, dict):
                    raise DatabaseError("payload must be an object")

                processed = conn.execute(
                    "SELECT operation_id FROM processed_operations WHERE operation_id = ? AND user_id = ?",
                    (operation_id, user_id),
                ).fetchone()
                if processed:
                    accepted.append(operation_id)
                    continue

                if operation_type == "create":
                    existing = conn.execute(
                        "SELECT * FROM tasks WHERE id = ? AND user_id = ?",
                        (task_id, user_id),
                    ).fetchone()
                    if existing:
                        conflicts.append(
                            {
                                "operationId": operation_id,
                                "taskId": task_id,
                                "serverTask": row_to_task(existing),
                                "message": "task already exists",
                            }
                        )
                        continue
                    task = normalize_task_payload({**payload, "id": task_id}, require_title=True)
                    seq = self._next_sync_seq(conn)
                    now = utc_now()
                    conn.execute(
                        """
                        INSERT INTO tasks(
                            id, user_id, title, content, status, category, priority,
                            visibility, created_at, updated_at, version, deleted_at, sync_seq
                        )
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            task_id,
                            user_id,
                            task["title"],
                            task["content"],
                            task["status"],
                            task["category"],
                            task["priority"],
                            task["visibility"],
                            payload.get("createdAt") or local_updated_at,
                            payload.get("updatedAt") or local_updated_at,
                            int(payload.get("version") or 1),
                            payload.get("deletedAt"),
                            seq,
                        ),
                    )
                    self._record_operation(conn, operation_id, user_id, task_id, operation_type, now)
                    accepted.append(operation_id)
                    continue

                row = conn.execute(
                    "SELECT * FROM tasks WHERE id = ? AND user_id = ?",
                    (task_id, user_id),
                ).fetchone()
                if not row:
                    conflicts.append(
                        {
                            "operationId": operation_id,
                            "taskId": task_id,
                            "serverTask": None,
                            "message": "task not found on server",
                        }
                    )
                    continue
                if base_version is None or int(base_version) != int(row["version"]):
                    conflicts.append(
                        {
                            "operationId": operation_id,
                            "taskId": task_id,
                            "serverTask": row_to_task(row),
                            "message": "task version conflict",
                        }
                    )
                    continue

                if operation_type == "update":
                    patch = normalize_task_payload(payload, require_title=False)
                    values = {
                        "title": row["title"],
                        "content": row["content"],
                        "status": row["status"],
                        "category": row["category"],
                        "priority": row["priority"],
                        "visibility": row["visibility"],
                    }
                    values.update(patch)
                    seq = self._next_sync_seq(conn)
                    conn.execute(
                        """
                        UPDATE tasks
                        SET title = ?, content = ?, status = ?, category = ?, priority = ?, visibility = ?,
                            updated_at = ?, version = ?, deleted_at = ?, sync_seq = ?
                        WHERE id = ? AND user_id = ?
                        """,
                        (
                            values["title"],
                            values["content"],
                            values["status"],
                            values["category"],
                            values["priority"],
                            values["visibility"],
                            payload.get("updatedAt") or local_updated_at,
                            int(row["version"]) + 1,
                            payload.get("deletedAt", row["deleted_at"]),
                            seq,
                            task_id,
                            user_id,
                        ),
                    )
                else:
                    seq = self._next_sync_seq(conn)
                    deleted_at = payload.get("deletedAt") or local_updated_at
                    conn.execute(
                        """
                        UPDATE tasks
                        SET deleted_at = ?, updated_at = ?, version = ?, sync_seq = ?
                        WHERE id = ? AND user_id = ?
                        """,
                        (deleted_at, deleted_at, int(row["version"]) + 1, seq, task_id, user_id),
                    )

                self._record_operation(conn, operation_id, user_id, task_id, operation_type, utc_now())
                accepted.append(operation_id)

            next_cursor = self._user_max_sync_seq(conn, user_id)

        return {
            "accepted": accepted,
            "conflicts": conflicts,
            "nextCursor": str(next_cursor),
        }

    def get_my_circle(self, user_id: str) -> dict[str, Any]:
        with self.connect() as conn:
            user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
            member_count = conn.execute(
                "SELECT COUNT(*) AS total FROM circle_members WHERE circle_id = ?",
                (user["circle_id"],),
            ).fetchone()["total"]
        return {
            "circleId": user["circle_id"],
            "owner": row_to_public_user(user),
            "memberCount": member_count,
        }

    def join_circle(self, user_id: str, circle_id: str) -> dict[str, Any]:
        return self.create_friend_request(user_id, circle_id, "请求添加好友")

    def create_friend_request(self, user_id: str, circle_id: str, introduction: str) -> dict[str, Any]:
        circle_id = normalize_circle_id(circle_id)
        introduction = (introduction or "").strip()
        if not 1 <= len(introduction) <= 500:
            raise DatabaseError("introduction must be 1-500 characters")
        now = utc_now()
        request_id = str(uuid.uuid4())
        with self._lock, self.connect() as conn:
            requester = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
            target = conn.execute(
                "SELECT * FROM users WHERE circle_id = ?",
                (circle_id,),
            ).fetchone()
            if not target:
                raise DatabaseError("friend id not found", 404)
            if target["id"] == user_id:
                raise DatabaseError("cannot add yourself")
            if self._is_circle_member(user_id, target["circle_id"], conn):
                raise DatabaseError("already friends", 409)
            pending = conn.execute(
                """
                SELECT 1
                FROM friend_requests
                WHERE requester_id = ?
                  AND target_id = ?
                  AND status = 'pending'
                """,
                (user_id, target["id"]),
            ).fetchone()
            if pending:
                raise DatabaseError("friend request already pending", 409)
            conn.execute(
                """
                INSERT INTO friend_requests(id, requester_id, target_id, introduction, status, created_at)
                VALUES(?, ?, ?, ?, 'pending', ?)
                """,
                (request_id, requester["id"], target["id"], introduction, now),
            )
            row = self._friend_request_row(conn, request_id)
        return row_to_friend_request(row)

    def list_friend_requests(self, user_id: str, direction: str) -> list[dict[str, Any]]:
        if direction not in {"incoming", "outgoing"}:
            raise DatabaseError("direction is invalid")
        column = "target_id" if direction == "incoming" else "requester_id"
        with self.connect() as conn:
            rows = conn.execute(
                f"""
                SELECT
                    friend_requests.*,
                    requester.id AS requester_id,
                    requester.username AS requester_username,
                    requester.circle_id AS requester_circle_id,
                    target.id AS target_id,
                    target.username AS target_username,
                    target.circle_id AS target_circle_id
                FROM friend_requests
                JOIN users requester ON requester.id = friend_requests.requester_id
                JOIN users target ON target.id = friend_requests.target_id
                WHERE friend_requests.{column} = ?
                ORDER BY friend_requests.created_at DESC
                """,
                (user_id,),
            ).fetchall()
        return [row_to_friend_request(row) for row in rows]

    def decide_friend_request(self, user_id: str, request_id: str, accept: bool) -> dict[str, Any]:
        now = utc_now()
        with self._lock, self.connect() as conn:
            request = conn.execute("SELECT * FROM friend_requests WHERE id = ?", (request_id,)).fetchone()
            if not request:
                raise DatabaseError("friend request not found", 404)
            if request["target_id"] != user_id:
                raise DatabaseError("cannot decide another user's friend request", 403)
            if request["status"] != "pending":
                raise DatabaseError("friend request already decided", 409)

            status = "accepted" if accept else "rejected"
            conn.execute(
                "UPDATE friend_requests SET status = ?, decided_at = ? WHERE id = ?",
                (status, now, request_id),
            )
            if accept:
                requester = conn.execute("SELECT * FROM users WHERE id = ?", (request["requester_id"],)).fetchone()
                target = conn.execute("SELECT * FROM users WHERE id = ?", (request["target_id"],)).fetchone()
                conn.execute(
                    """
                    INSERT OR IGNORE INTO circle_members(circle_id, member_user_id, joined_at)
                    VALUES(?, ?, ?)
                    """,
                    (target["circle_id"], requester["id"], now),
                )
                conn.execute(
                    """
                    INSERT OR IGNORE INTO circle_members(circle_id, member_user_id, joined_at)
                    VALUES(?, ?, ?)
                    """,
                    (requester["circle_id"], target["id"], now),
                )
            decided = self._friend_request_row(conn, request_id)
        return row_to_friend_request(decided)

    def _friend_request_row(self, conn: sqlite3.Connection, request_id: str) -> sqlite3.Row:
        row = conn.execute(
            """
            SELECT
                friend_requests.*,
                requester.id AS requester_id,
                requester.username AS requester_username,
                requester.circle_id AS requester_circle_id,
                target.id AS target_id,
                target.username AS target_username,
                target.circle_id AS target_circle_id
            FROM friend_requests
            JOIN users requester ON requester.id = friend_requests.requester_id
            JOIN users target ON target.id = friend_requests.target_id
            WHERE friend_requests.id = ?
            """,
            (request_id,),
        ).fetchone()
        if not row:
            raise DatabaseError("friend request not found", 404)
        return row

    def list_friends(self, user_id: str) -> list[dict[str, Any]]:
        return self.list_joined_circles(user_id)

    def friend_public_ideas(self, user_id: str, circle_id: str, query: dict[str, list[str]]) -> dict[str, Any]:
        circle_id = normalize_circle_id(circle_id)
        return self.feed(user_id, {**query, "circleId": [circle_id]})

    def remove_friend(self, user_id: str, circle_id: str) -> dict[str, Any]:
        circle_id = normalize_circle_id(circle_id)
        now = utc_now()
        with self._lock, self.connect() as conn:
            target = conn.execute("SELECT * FROM users WHERE circle_id = ?", (circle_id,)).fetchone()
            user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
            if not target:
                raise DatabaseError("friend not found", 404)
            if target["id"] == user_id:
                raise DatabaseError("cannot remove yourself")
            conn.execute(
                "DELETE FROM circle_members WHERE circle_id = ? AND member_user_id = ?",
                (target["circle_id"], user_id),
            )
            conn.execute(
                "DELETE FROM circle_members WHERE circle_id = ? AND member_user_id = ?",
                (user["circle_id"], target["id"]),
            )
        return {"removed": True, "circleId": circle_id, "removedAt": now}

    def list_joined_circles(self, user_id: str) -> list[dict[str, Any]]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT circle_members.joined_at, users.*
                FROM circle_members
                JOIN users ON users.circle_id = circle_members.circle_id
                WHERE circle_members.member_user_id = ?
                ORDER BY circle_members.joined_at DESC
                """,
                (user_id,),
            ).fetchall()
        return [
            {
                "circleId": row["circle_id"],
                "owner": row_to_public_user(row),
                "joinedAt": row["joined_at"],
            }
            for row in rows
        ]

    def leave_circle(self, user_id: str, circle_id: str) -> dict[str, Any]:
        removed = self.remove_friend(user_id, circle_id)
        return {"left": removed["removed"], "circleId": removed["circleId"]}

    def feed(self, user_id: str, query: dict[str, list[str]]) -> dict[str, Any]:
        circle_id = first(query.get("circleId"))
        with self.connect() as conn:
            user = conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
        if circle_id:
            circle_id = normalize_circle_id(circle_id)
            if circle_id != user["circle_id"] and not self._is_circle_member(user_id, circle_id):
                raise DatabaseError("circle not joined", 403)

        page = max(1, parse_int(first(query.get("page")), 1))
        page_size = min(100, max(1, parse_int(first(query.get("pageSize")), 20)))
        offset = (page - 1) * page_size

        where = [
            "tasks.visibility = 'circle'",
            "tasks.deleted_at IS NULL",
            "(tasks.user_id = ? OR circle_members.member_user_id = ?)",
        ]
        params: list[Any] = [user_id, user_id]
        if circle_id:
            where.append("users.circle_id = ?")
            params.append(circle_id)
        where_sql = " AND ".join(where)

        with self.connect() as conn:
            total = conn.execute(
                f"""
                SELECT COUNT(*) AS total
                FROM tasks
                JOIN users ON users.id = tasks.user_id
                LEFT JOIN circle_members
                  ON circle_members.circle_id = users.circle_id
                 AND circle_members.member_user_id = ?
                WHERE {where_sql}
                """,
                [user_id, *params],
            ).fetchone()["total"]
            rows = conn.execute(
                f"""
                SELECT
                    tasks.*,
                    users.id AS author_id,
                    users.username AS author_username,
                    users.circle_id AS author_circle_id,
                    (SELECT COUNT(*) FROM idea_likes WHERE idea_likes.idea_id = tasks.id) AS like_count,
                    (
                        SELECT COUNT(*)
                        FROM idea_comments
                        WHERE idea_comments.idea_id = tasks.id
                          AND idea_comments.deleted_at IS NULL
                    ) AS comment_count,
                    EXISTS(
                        SELECT 1
                        FROM idea_likes
                        WHERE idea_likes.idea_id = tasks.id
                          AND idea_likes.user_id = ?
                    ) AS liked_by_me
                FROM tasks
                JOIN users ON users.id = tasks.user_id
                LEFT JOIN circle_members
                  ON circle_members.circle_id = users.circle_id
                 AND circle_members.member_user_id = ?
                WHERE {where_sql}
                ORDER BY tasks.updated_at DESC
                LIMIT ? OFFSET ?
                """,
                [user_id, user_id, *params, page_size, offset],
            ).fetchall()
        return {
            "items": [self._feed_item(row) for row in rows],
            "page": page,
            "pageSize": page_size,
            "total": total,
        }

    def like_idea(self, user_id: str, idea_id: str) -> dict[str, Any]:
        self._require_viewable_idea(user_id, idea_id)
        with self._lock, self.connect() as conn:
            conn.execute(
                """
                INSERT OR IGNORE INTO idea_likes(idea_id, user_id, created_at)
                VALUES(?, ?, ?)
                """,
                (idea_id, user_id, utc_now()),
            )
            return self._idea_social_counts(conn, idea_id, user_id)

    def unlike_idea(self, user_id: str, idea_id: str) -> dict[str, Any]:
        self._require_viewable_idea(user_id, idea_id)
        with self._lock, self.connect() as conn:
            conn.execute(
                "DELETE FROM idea_likes WHERE idea_id = ? AND user_id = ?",
                (idea_id, user_id),
            )
            return self._idea_social_counts(conn, idea_id, user_id)

    def list_comments(self, user_id: str, idea_id: str) -> list[dict[str, Any]]:
        self._require_viewable_idea(user_id, idea_id)
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT
                    idea_comments.*,
                    users.id AS author_id,
                    users.username AS author_username,
                    users.circle_id AS author_circle_id
                FROM idea_comments
                JOIN users ON users.id = idea_comments.author_id
                WHERE idea_comments.idea_id = ?
                  AND idea_comments.deleted_at IS NULL
                ORDER BY idea_comments.created_at ASC
                """,
                (idea_id,),
            ).fetchall()
        return [row_to_comment(row) for row in rows]

    def create_comment(self, user_id: str, idea_id: str, content: str) -> dict[str, Any]:
        self._require_viewable_idea(user_id, idea_id)
        content = (content or "").strip()
        if not 1 <= len(content) <= 1000:
            raise DatabaseError("comment content must be 1-1000 characters")
        comment_id = str(uuid.uuid4())
        now = utc_now()
        with self._lock, self.connect() as conn:
            conn.execute(
                """
                INSERT INTO idea_comments(id, idea_id, author_id, content, created_at)
                VALUES(?, ?, ?, ?, ?)
                """,
                (comment_id, idea_id, user_id, content, now),
            )
            row = conn.execute(
                """
                SELECT
                    idea_comments.*,
                    users.id AS author_id,
                    users.username AS author_username,
                    users.circle_id AS author_circle_id
                FROM idea_comments
                JOIN users ON users.id = idea_comments.author_id
                WHERE idea_comments.id = ?
                """,
                (comment_id,),
            ).fetchone()
        return row_to_comment(row)

    def delete_comment(self, user_id: str, comment_id: str) -> dict[str, Any]:
        with self._lock, self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM idea_comments WHERE id = ? AND deleted_at IS NULL",
                (comment_id,),
            ).fetchone()
            if not row:
                raise DatabaseError("comment not found", 404)
            if row["author_id"] != user_id:
                raise DatabaseError("cannot delete another user's comment", 403)
            conn.execute(
                "UPDATE idea_comments SET deleted_at = ? WHERE id = ?",
                (utc_now(), comment_id),
            )
        return {"deleted": True, "commentId": comment_id}

    def _feed_item(self, row: sqlite3.Row) -> dict[str, Any]:
        item = row_to_task(row)
        item["author"] = {
            "id": row["author_id"],
            "username": row["author_username"],
            "circleId": row["author_circle_id"],
        }
        item["likeCount"] = row["like_count"]
        item["commentCount"] = row["comment_count"]
        item["likedByMe"] = bool(row["liked_by_me"])
        return item

    def _idea_social_counts(self, conn: sqlite3.Connection, idea_id: str, user_id: str) -> dict[str, Any]:
        counts = conn.execute(
            """
            SELECT
                (SELECT COUNT(*) FROM idea_likes WHERE idea_id = ?) AS like_count,
                (
                    SELECT COUNT(*)
                    FROM idea_comments
                    WHERE idea_id = ?
                      AND deleted_at IS NULL
                ) AS comment_count,
                EXISTS(
                    SELECT 1
                    FROM idea_likes
                    WHERE idea_id = ?
                      AND user_id = ?
                ) AS liked_by_me
            """,
            (idea_id, idea_id, idea_id, user_id),
        ).fetchone()
        return {
            "ideaId": idea_id,
            "likeCount": counts["like_count"],
            "commentCount": counts["comment_count"],
            "likedByMe": bool(counts["liked_by_me"]),
        }

    def _require_viewable_idea(
        self,
        user_id: str,
        idea_id: str,
        conn: sqlite3.Connection | None = None,
        include_deleted: bool = False,
    ) -> sqlite3.Row:
        owns_connection = conn is None
        conn = conn or self.connect()
        try:
            deleted_filter = "" if include_deleted else "AND tasks.deleted_at IS NULL"
            row = conn.execute(
                f"""
                SELECT tasks.*, users.circle_id AS author_circle_id
                FROM tasks
                JOIN users ON users.id = tasks.user_id
                WHERE tasks.id = ?
                  {deleted_filter}
                """,
                (idea_id,),
            ).fetchone()
            if not row:
                raise DatabaseError("idea not found", 404)
            if row["user_id"] == user_id:
                return row
            if row["visibility"] == "circle" and self._is_circle_member(user_id, row["author_circle_id"], conn):
                return row
            raise DatabaseError("idea not visible", 403)
        finally:
            if owns_connection:
                conn.close()

    def _idea_detail(self, conn: sqlite3.Connection, user_id: str, idea_id: str) -> dict[str, Any]:
        row = conn.execute(
            """
            SELECT
                tasks.*,
                users.id AS author_id,
                users.username AS author_username,
                users.circle_id AS author_circle_id,
                (SELECT COUNT(*) FROM idea_likes WHERE idea_likes.idea_id = tasks.id) AS like_count,
                (
                    SELECT COUNT(*)
                    FROM idea_comments
                    WHERE idea_comments.idea_id = tasks.id
                      AND idea_comments.deleted_at IS NULL
                ) AS comment_count,
                EXISTS(
                    SELECT 1
                    FROM idea_likes
                    WHERE idea_likes.idea_id = tasks.id
                      AND idea_likes.user_id = ?
                ) AS liked_by_me
            FROM tasks
            JOIN users ON users.id = tasks.user_id
            WHERE tasks.id = ?
            """,
            (user_id, idea_id),
        ).fetchone()
        if not row:
            raise DatabaseError("idea not found", 404)
        return self._feed_item(row)

    def _is_circle_member(
        self,
        user_id: str,
        circle_id: str,
        conn: sqlite3.Connection | None = None,
    ) -> bool:
        owns_connection = conn is None
        conn = conn or self.connect()
        try:
            row = conn.execute(
                "SELECT 1 FROM circle_members WHERE circle_id = ? AND member_user_id = ?",
                (circle_id, user_id),
            ).fetchone()
            return row is not None
        finally:
            if owns_connection:
                conn.close()

    def _record_operation(
        self,
        conn: sqlite3.Connection,
        operation_id: str,
        user_id: str,
        task_id: str,
        operation_type: str,
        created_at: str,
    ) -> None:
        conn.execute(
            """
            INSERT INTO processed_operations(operation_id, user_id, task_id, operation_type, created_at)
            VALUES(?, ?, ?, ?, ?)
            """,
            (operation_id, user_id, task_id, operation_type, created_at),
        )

    def _user_max_sync_seq(self, conn: sqlite3.Connection, user_id: str) -> int:
        task_max = conn.execute(
            "SELECT COALESCE(MAX(sync_seq), 0) AS value FROM tasks WHERE user_id = ?",
            (user_id,),
        ).fetchone()["value"]
        reminder_max = conn.execute(
            "SELECT COALESCE(MAX(sync_seq), 0) AS value FROM reminder_settings WHERE user_id = ?",
            (user_id,),
        ).fetchone()["value"]
        return max(int(task_max), int(reminder_max))


def normalize_task_payload(payload: dict[str, Any], require_title: bool) -> dict[str, Any]:
    normalized: dict[str, Any] = {}
    if require_title or "title" in payload:
        title = str(payload.get("title", "")).strip()
        if not 1 <= len(title) <= 200:
            raise DatabaseError("title must be 1-200 characters")
        normalized["title"] = title

    if require_title or "content" in payload:
        content = str(payload.get("content", ""))
        if len(content) > 5000:
            raise DatabaseError("content must be at most 5000 characters")
        normalized["content"] = content

    status = payload.get("status", "想做" if require_title else None)
    if status is not None:
        if status not in TASK_STATUSES:
            raise DatabaseError("status is invalid")
        normalized["status"] = status

    category = payload.get("category", "其他" if require_title else None)
    if category is not None:
        if category not in TASK_CATEGORIES:
            raise DatabaseError("category is invalid")
        normalized["category"] = category

    priority = payload.get("priority", "中" if require_title else None)
    if priority is not None:
        if priority not in TASK_PRIORITIES:
            raise DatabaseError("priority is invalid")
        normalized["priority"] = priority

    visibility = payload.get("visibility", "private" if require_title else None)
    if visibility is not None:
        if visibility not in TASK_VISIBILITIES:
            raise DatabaseError("visibility must be private or circle")
        normalized["visibility"] = visibility

    return normalized


def first(values: list[str] | None) -> str | None:
    return values[0] if values else None


def parse_int(value: str | None, default: int) -> int:
    if value in (None, ""):
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise DatabaseError("integer query parameter is invalid") from exc


def normalize_circle_id(circle_id: str) -> str:
    circle_id = (circle_id or "").strip().upper()
    if not 4 <= len(circle_id) <= 32:
        raise DatabaseError("circleId is invalid")
    return circle_id


def is_uuidish(value: str) -> bool:
    try:
        uuid.UUID(str(value))
    except ValueError:
        return False
    return True


def json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
