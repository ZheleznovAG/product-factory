#!/usr/bin/env python3
"""
Neural gateway: единый OpenAI-совместимый API.
Бэкенд переключается через NEURAL_BACKEND: codex | openai | proxy.
Хост может выступать в роли «нейросервиса» (режим codex) — по ответам не видно, Codex это или облако.
"""
from __future__ import annotations

import asyncio
import os
import subprocess
from typing import Any, Dict, List, Optional

import httpx
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field
from pydantic import ValidationError as PydanticValidationError

app = FastAPI(title="Neural Gateway", version="0.1.0")

# Режим: codex = вызов Codex CLI на хосте; openai = прокси в api.openai.com; proxy = прокси на PROXY_TARGET_URL
NEURAL_BACKEND = os.environ.get("NEURAL_BACKEND", "codex").lower()
CODEX_PATH = os.environ.get("CODEX_PATH", "codex")
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")
PROXY_TARGET_URL = (os.environ.get("PROXY_TARGET_URL", "").rstrip("/") or None)
MAX_PROMPT_LENGTH = int(os.environ.get("NEURAL_GATEWAY_MAX_PROMPT_LENGTH", "50000"))


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatCompletionRequest(BaseModel):
    model: Optional[str] = Field(default="gpt-4o-mini", description="Игнорируется в режиме codex")
    messages: List[Dict[str, Any]] = Field(..., min_length=1)
    stream: bool = False
    max_tokens: Optional[int] = None
    temperature: Optional[float] = None


def _last_user_content(messages: List[Dict[str, Any]]) -> str:
    for m in reversed(messages):
        if m.get("role") == "user" and "content" in m:
            c = m["content"]
            return c if isinstance(c, str) else str(c)
    return ""


def _codex_prompt(messages: List[Dict[str, Any]]) -> str:
    """Собирает полный промпт из всех сообщений (system + user), чтобы Codex видел инструкции по формату JSON."""
    parts: List[str] = []
    for m in messages:
        role = (m.get("role") or "").strip()
        content = m.get("content")
        if content is None:
            content = ""
        elif not isinstance(content, str):
            content = str(content)
        content = content.strip()
        if not content:
            continue
        if role == "system":
            parts.append(f"System:\n{content}")
        elif role == "user":
            parts.append(f"User:\n{content}")
        elif role == "assistant":
            parts.append(f"Assistant:\n{content}")
    return "\n\n".join(parts) if parts else _last_user_content(messages)


def _strip_markdown_json(content: str) -> str:
    """Убирает обёртку ```json ... ``` или ``` ... ``` из ответа, чтобы фабрика получила голый JSON."""
    s = (content or "").strip()
    for fence in ("```json", "```"):
        if fence in s:
            start = s.find(fence)
            if start >= 0:
                rest = s[start + len(fence) :].lstrip("\n\r")
                end = rest.find("```")
                if end >= 0:
                    return rest[:end].strip()
                return rest.strip()
    return s


def _run_codex_sync(prompt: str) -> str:
    """Синхронный вызов Codex (запускается в executor, чтобы не блокировать event loop)."""
    if len(prompt) > MAX_PROMPT_LENGTH:
        raise HTTPException(
            status_code=400,
            detail=f"Prompt length {len(prompt)} exceeds max {MAX_PROMPT_LENGTH}. Set NEURAL_GATEWAY_MAX_PROMPT_LENGTH to override.",
        )
    cwd = os.environ.get("NEURAL_GATEWAY_REPO_ROOT", os.getcwd())
    cmd = [CODEX_PATH, "exec", "--cd", cwd, "--sandbox", "workspace-write", "--", prompt]
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=300,
            cwd=cwd,
        )
        if result.returncode != 0 and result.stderr:
            return f"[Codex error exit {result.returncode}]\n{result.stderr}\n{result.stdout or ''}"
        return (result.stdout or "").strip()
    except FileNotFoundError:
        raise HTTPException(
            status_code=503,
            detail=f"Codex not found: {CODEX_PATH}. Set CODEX_PATH or install codex.",
        )
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="Codex execution timeout")


async def _forward_openai(body: Dict[str, Any], auth: Optional[str]) -> Dict[str, Any]:
    url = "https://api.openai.com/v1/chat/completions"
    headers: Dict[str, str] = {"Content-Type": "application/json"}
    if auth:
        headers["Authorization"] = auth
    async with httpx.AsyncClient(timeout=120.0) as client:
        r = await client.post(url, json=body, headers=headers)
        r.raise_for_status()
        return r.json()


async def _forward_proxy(body: Dict[str, Any], base_url: str, auth: Optional[str]) -> Dict[str, Any]:
    if not base_url:
        raise HTTPException(status_code=500, detail="PROXY_TARGET_URL is not set")
    url = f"{base_url}/v1/chat/completions"
    headers: Dict[str, str] = {"Content-Type": "application/json"}
    if auth:
        headers["Authorization"] = auth
    async with httpx.AsyncClient(timeout=120.0) as client:
        r = await client.post(url, json=body, headers=headers)
        r.raise_for_status()
        return r.json()


def _parse_messages_from_body(body: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Извлекает messages из тела запроса (совместимость с разными клиентами)."""
    raw = body.get("messages")
    if not isinstance(raw, list) or len(raw) == 0:
        raise HTTPException(status_code=400, detail="messages must be a non-empty array")
    out: List[Dict[str, Any]] = []
    for i, m in enumerate(raw):
        if not isinstance(m, dict):
            raise HTTPException(status_code=400, detail=f"messages[{i}] must be an object")
        role = m.get("role") or ""
        content = m.get("content")
        if content is None:
            content = ""
        elif not isinstance(content, str):
            content = str(content)
        out.append({"role": role, "content": content})
    return out


@app.post("/v1/chat/completions")
async def chat_completions(raw_request: Request):
    try:
        body = await raw_request.json()
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid JSON body: {e}")

    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="Body must be a JSON object")

    request: Optional[ChatCompletionRequest] = None
    try:
        request = ChatCompletionRequest(**body)
        messages = request.messages
    except PydanticValidationError:
        messages = _parse_messages_from_body(body)

    if body.get("stream") is True:
        raise HTTPException(status_code=400, detail="Streaming not supported in this gateway")

    auth = os.environ.get("NEURAL_SERVICE_API_KEY") or OPENAI_API_KEY
    auth_header = f"Bearer {auth}" if auth else None

    if NEURAL_BACKEND == "codex":
        prompt = _codex_prompt(messages)
        if not prompt.strip():
            raise HTTPException(status_code=400, detail="No user message in messages")
        loop = asyncio.get_event_loop()
        raw_content = await loop.run_in_executor(None, _run_codex_sync, prompt)
        content = _strip_markdown_json(raw_content)
        model = (request.model if request else None) or body.get("model") or "codex"
        return {
            "id": "gateway-codex-1",
            "object": "chat.completion",
            "model": model,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }

    if NEURAL_BACKEND == "openai":
        if not request:
            request = ChatCompletionRequest(messages=messages)
        if not OPENAI_API_KEY and not auth:
            raise HTTPException(
                status_code=500,
                detail="OPENAI_API_KEY or NEURAL_SERVICE_API_KEY required for openai backend",
            )
        body = request.model_dump(exclude_none=True)
        return await _forward_openai(body, auth_header or f"Bearer {OPENAI_API_KEY}")

    if NEURAL_BACKEND == "proxy":
        if not request:
            request = ChatCompletionRequest(messages=messages)
        body = request.model_dump(exclude_none=True)
        return await _forward_proxy(body, PROXY_TARGET_URL, auth_header)

    raise HTTPException(
        status_code=500,
        detail=f"Unknown NEURAL_BACKEND={NEURAL_BACKEND}. Use codex, openai, or proxy.",
    )


@app.get("/health")
def health():
    return {"status": "ok", "backend": NEURAL_BACKEND}


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("NEURAL_GATEWAY_PORT", "8090"))
    uvicorn.run(app, host="0.0.0.0", port=port)
