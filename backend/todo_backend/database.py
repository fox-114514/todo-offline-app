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
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
    }


def row_to_reminder(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "enabled": bool(row["enabled"]),
        "frequencySeconds": row["frequency_seconds"],
        "updatedAt": row["updated_at"],
    }


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
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    deleted_at TEXT,
                    sync_seq INTEGER NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_tasks_user_sync ON tasks(user_id, sync_seq);
                CREATE INDEX IF NOT EXISTS idx_tasks_user_visible ON tasks(user_id, deleted_at);

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
                conn.execute(
                    """
                    INSERT INTO users(id, username, email, password_salt, password_hash, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    """,
                    (user_id, username, email, salt, password_hash, now, now),
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
                        created_at, updated_at, version, deleted_at, sync_seq
                    )
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        task_id,
                        user_id,
                        task["title"],
                        task["content"],
                        task["status"],
                        task["category"],
                        task["priority"],
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
            }
            values.update(patch)
            updated_at = payload.get("updatedAt") or utc_now()
            deleted_at = payload.get("deletedAt", row["deleted_at"])
            next_version = int(row["version"]) + 1
            seq = self._next_sync_seq(conn)
            conn.execute(
                """
                UPDATE tasks
                SET title = ?, content = ?, status = ?, category = ?, priority = ?,
                    updated_at = ?, version = ?, deleted_at = ?, sync_seq = ?
                WHERE id = ? AND user_id = ?
                """,
                (
                    values["title"],
                    values["content"],
                    values["status"],
                    values["category"],
                    values["priority"],
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
        sql = "SELECT * FROM tasks WHERE id = ? AND user_id = ?"
        params: list[Any] = [task_id, user_id]
        if not include_deleted:
            sql += " AND deleted_at IS NULL"
        with self.connect() as conn:
            row = conn.execute(sql, params).fetchone()
        if not row:
            raise DatabaseError("task not found", 404)
        return row_to_task(row)

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
                            created_at, updated_at, version, deleted_at, sync_seq
                        )
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            task_id,
                            user_id,
                            task["title"],
                            task["content"],
                            task["status"],
                            task["category"],
                            task["priority"],
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
                    }
                    values.update(patch)
                    seq = self._next_sync_seq(conn)
                    conn.execute(
                        """
                        UPDATE tasks
                        SET title = ?, content = ?, status = ?, category = ?, priority = ?,
                            updated_at = ?, version = ?, deleted_at = ?, sync_seq = ?
                        WHERE id = ? AND user_id = ?
                        """,
                        (
                            values["title"],
                            values["content"],
                            values["status"],
                            values["category"],
                            values["priority"],
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


def is_uuidish(value: str) -> bool:
    try:
        uuid.UUID(str(value))
    except ValueError:
        return False
    return True


def json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
