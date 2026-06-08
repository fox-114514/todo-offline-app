FROM python:3.14-slim

WORKDIR /app
COPY todo_backend ./todo_backend

ENV TODO_BACKEND_HOST=0.0.0.0
ENV TODO_BACKEND_PORT=8000
ENV TODO_BACKEND_DB=/data/todo.sqlite3

VOLUME ["/data"]
EXPOSE 8000

CMD ["python", "-m", "todo_backend"]
