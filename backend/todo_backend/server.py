from __future__ import annotations

import argparse
import json
import os
import re
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from .database import DatabaseError, TodoDatabase, json_dumps


class ApiError(Exception):
    def __init__(self, message: str, status: int = 400, data: dict[str, Any] | None = None):
        super().__init__(message)
        self.message = message
        self.status = status
        self.data = data or {}


class TodoRequestHandler(BaseHTTPRequestHandler):
    server_version = "TodoBackend/0.1"

    @property
    def db(self) -> TodoDatabase:
        return self.server.db  # type: ignore[attr-defined]

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self._cors_headers()
        self.end_headers()

    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_POST(self) -> None:
        self._dispatch("POST")

    def do_PUT(self) -> None:
        self._dispatch("PUT")

    def do_DELETE(self) -> None:
        self._dispatch("DELETE")

    def log_message(self, fmt: str, *args: Any) -> None:
        if os.getenv("TODO_BACKEND_ACCESS_LOG", "0") == "1":
            super().log_message(fmt, *args)

    def _dispatch(self, method: str) -> None:
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        query = parse_qs(parsed.query)
        try:
            result = self._route(method, path, query)
            self._json_response(200, "success", result)
        except DatabaseError as exc:
            self._json_response(exc.status, exc.message, exc.data)
        except ApiError as exc:
            self._json_response(exc.status, exc.message, exc.data)
        except json.JSONDecodeError:
            self._json_response(400, "request body must be valid JSON", {})
        except Exception as exc:  # pragma: no cover - defensive HTTP boundary
            self._json_response(500, "internal server error", {"detail": str(exc)})

    def _route(self, method: str, path: str, query: dict[str, list[str]]) -> Any:
        if method == "GET" and path == "/api/health":
            return {"ok": True}

        if method == "POST" and path == "/api/auth/register":
            body = self._read_json()
            user = self.db.create_user(
                body.get("username", ""),
                body.get("email", ""),
                body.get("password", ""),
            )
            token = self.db.issue_token(user["id"])
            return {"user": user, **token}

        if method == "POST" and path == "/api/auth/login":
            body = self._read_json()
            user = self.db.authenticate(
                body.get("username", ""),
                body.get("password", ""),
            )
            token = self.db.issue_token(user["id"])
            return {"user": user, **token}

        user, token = self._require_user()

        if method == "POST" and path == "/api/auth/logout":
            self.db.revoke_token(token)
            return {"loggedOut": True}

        if method == "GET" and path == "/api/me/circle":
            return self.db.get_my_circle(user["id"])

        if method == "POST" and path == "/api/friends/requests":
            body = self._read_json()
            return self.db.create_friend_request(
                user["id"],
                body.get("circleId", ""),
                body.get("introduction", ""),
            )

        if method == "GET" and path == "/api/friends/requests/incoming":
            return {"items": self.db.list_friend_requests(user["id"], "incoming")}

        if method == "GET" and path == "/api/friends/requests/outgoing":
            return {"items": self.db.list_friend_requests(user["id"], "outgoing")}

        friend_request_decision_match = re.fullmatch(r"/api/friends/requests/([^/]+)/(accept|reject)", path)
        if friend_request_decision_match and method == "POST":
            request_id = friend_request_decision_match.group(1)
            decision = friend_request_decision_match.group(2)
            return self.db.decide_friend_request(user["id"], request_id, accept=decision == "accept")

        if method == "GET" and path == "/api/friends":
            return {"items": self.db.list_friends(user["id"])}

        friend_ideas_match = re.fullmatch(r"/api/friends/([^/]+)/ideas", path)
        if friend_ideas_match and method == "GET":
            return self.db.friend_public_ideas(user["id"], friend_ideas_match.group(1), query)

        friend_match = re.fullmatch(r"/api/friends/([^/]+)", path)
        if friend_match and method == "DELETE":
            return self.db.remove_friend(user["id"], friend_match.group(1))

        if method == "POST" and path == "/api/circles/join":
            body = self._read_json()
            return self.db.join_circle(user["id"], body.get("circleId", ""))

        if method == "GET" and path == "/api/circles/joined":
            return {"items": self.db.list_friends(user["id"])}

        circle_leave_match = re.fullmatch(r"/api/circles/([^/]+)/leave", path)
        if circle_leave_match and method == "DELETE":
            return self.db.leave_circle(user["id"], circle_leave_match.group(1))

        if method == "GET" and path == "/api/feed":
            return self.db.feed(user["id"], query)

        idea_like_match = re.fullmatch(r"/api/ideas/([^/]+)/like", path)
        if idea_like_match:
            idea_id = idea_like_match.group(1)
            if method == "POST":
                return self.db.like_idea(user["id"], idea_id)
            if method == "DELETE":
                return self.db.unlike_idea(user["id"], idea_id)

        idea_comments_match = re.fullmatch(r"/api/ideas/([^/]+)/comments", path)
        if idea_comments_match:
            idea_id = idea_comments_match.group(1)
            if method == "GET":
                return {"items": self.db.list_comments(user["id"], idea_id)}
            if method == "POST":
                body = self._read_json()
                return self.db.create_comment(user["id"], idea_id, body.get("content", ""))

        comment_match = re.fullmatch(r"/api/comments/([^/]+)", path)
        if comment_match and method == "DELETE":
            return self.db.delete_comment(user["id"], comment_match.group(1))

        if method == "GET" and path in {"/api/tasks", "/api/ideas"}:
            return self.db.list_tasks(user["id"], query)

        if method == "GET" and path in {"/api/tasks/random", "/api/ideas/random"}:
            return {"task": self.db.random_task(user["id"])}

        if method == "POST" and path in {"/api/tasks", "/api/ideas"}:
            return self.db.create_task(user["id"], self._read_json())

        task_match = re.fullmatch(r"/api/(?:tasks|ideas)/([^/]+)", path)
        if task_match:
            task_id = task_match.group(1)
            if method == "GET":
                return self.db.get_task(user["id"], task_id)
            if method == "PUT":
                return self.db.update_task(user["id"], task_id, self._read_json())
            if method == "DELETE":
                body = self._read_json(optional=True)
                version = body.get("version") if body else None
                return self.db.delete_task(user["id"], task_id, version)

        if method == "GET" and path == "/api/reminders":
            return self.db.get_reminder(user["id"])

        if method == "PUT" and path == "/api/reminders":
            return self.db.update_reminder(user["id"], self._read_json())

        if method == "GET" and path == "/api/sync/pull":
            cursor = query.get("cursor", [None])[0]
            return self.db.pull_changes(user["id"], cursor)

        if method == "POST" and path == "/api/sync/push":
            body = self._read_json()
            operations = body.get("operations")
            if not isinstance(operations, list):
                raise ApiError("operations must be an array")
            return self.db.push_operations(user["id"], operations)

        raise ApiError("route not found", 404)

    def _read_json(self, optional: bool = False) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length == 0:
            if optional:
                return {}
            raise ApiError("request body is required")
        data = self.rfile.read(length)
        body = json.loads(data.decode("utf-8"))
        if not isinstance(body, dict):
            raise ApiError("request body must be a JSON object")
        return body

    def _require_user(self) -> tuple[dict[str, Any], str]:
        auth = self.headers.get("Authorization", "")
        prefix = "Bearer "
        if not auth.startswith(prefix):
            raise ApiError("missing bearer token", 401)
        token = auth[len(prefix) :].strip()
        user = self.db.user_for_token(token)
        if not user:
            raise ApiError("invalid or expired token", 401)
        return user, token

    def _json_response(self, status: int, message: str, data: Any) -> None:
        payload = json_dumps({"code": status, "message": message, "data": data}).encode("utf-8")
        self.send_response(status)
        self._cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _cors_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")


class TodoHTTPServer(ThreadingHTTPServer):
    def __init__(self, server_address: tuple[str, int], db_path: str | Path):
        super().__init__(server_address, TodoRequestHandler)
        self.db = TodoDatabase(db_path)


def create_server(host: str, port: int, db_path: str | Path) -> TodoHTTPServer:
    return TodoHTTPServer((host, port), db_path)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the offline-first Todo backend.")
    parser.add_argument("--host", default=os.getenv("TODO_BACKEND_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("TODO_BACKEND_PORT", "8000")))
    parser.add_argument(
        "--db",
        default=os.getenv("TODO_BACKEND_DB", "data/todo.sqlite3"),
        help="SQLite database path",
    )
    args = parser.parse_args()

    server = create_server(args.host, args.port, args.db)
    print(f"Todo backend listening on http://{args.host}:{args.port}")
    print(f"SQLite database: {Path(args.db).resolve()}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
