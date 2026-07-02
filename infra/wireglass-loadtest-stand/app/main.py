"""
JMeter DSL test stand — a single FastAPI service exposing three latency tiers
(fast / medium / slow) designed to exercise real JMeter test constructs:
JSON extraction, header/auth correlation, query params and response assertions.

Latency is applied with `await asyncio.sleep()` so it is non-blocking: a slow
request parks its coroutine without tying up the worker, keeping throughput
measurements meaningful under concurrent load.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import random
import re
import secrets
import time
import uuid

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field

logger = logging.getLogger("teststand")
logging.basicConfig(level=logging.INFO, format="%(message)s")

# --- Configuration (all overridable via environment / .env) -----------------
FAST_MS = int(os.getenv("FAST_MS", "20"))
MED_MS = int(os.getenv("MED_MS", "300"))
SLOW_MS = int(os.getenv("SLOW_MS", "2000"))
JITTER_PCT = int(os.getenv("JITTER_PCT", "20"))
TOKEN_SECRET = os.getenv("TOKEN_SECRET", "change-me-teststand-secret")
TOKEN_TTL = int(os.getenv("TOKEN_TTL", "3600"))
REPORT_MAX_ROWS = int(os.getenv("REPORT_MAX_ROWS", "5000"))

app = FastAPI(
    title="JMeter DSL Test Stand",
    version="1.0.0",
    description=(
        "Three latency tiers for load-testing and for evaluating JMeter DSL "
        "generation/refactoring quality.\n\n"
        "- **fast**  `GET /api/fast/ping`\n"
        "- **medium** `POST /api/medium/login` -> token, then "
        "`GET /api/medium/orders/{order_id}` with `Authorization: Bearer <token>`\n"
        "- **slow**  `GET /api/slow/report?rows=N`\n\n"
        "The login -> token -> orders chain forces a JSON extractor, a Header "
        "Manager and a response assertion in any correct JMeter test."
    ),
)

bearer = HTTPBearer(auto_error=False)


# --- Correlation IDs (traceID / request-id / Server-Timing) -----------------
_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
_TRACEPARENT_RE = re.compile(r"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")


def _resolve_request_id(req: Request) -> str:
    """Echo a client-supplied X-Request-ID if it looks safe, else mint one."""
    incoming = req.headers.get("x-request-id", "")
    if _REQUEST_ID_RE.match(incoming):
        return incoming
    return str(uuid.uuid4())


def _resolve_trace_id(req: Request) -> str:
    """Continue an incoming W3C traceparent's trace-id, else start a new trace."""
    match = _TRACEPARENT_RE.match(req.headers.get("traceparent", ""))
    return match.group(1) if match else uuid.uuid4().hex


@app.middleware("http")
async def correlation_middleware(request: Request, call_next):
    request.state.request_id = _resolve_request_id(request)
    request.state.trace_id = _resolve_trace_id(request)
    request.state.span_id = secrets.token_hex(8)

    start = time.perf_counter()
    response = await call_next(request)
    duration_ms = round((time.perf_counter() - start) * 1000, 2)

    response.headers["X-Request-ID"] = request.state.request_id
    response.headers["Server-Timing"] = (
        f"total;dur={duration_ms}, "
        f'traceparent;desc="00-{request.state.trace_id}-{request.state.span_id}-01"'
    )
    logger.info(
        "%s %s -> %s request_id=%s trace_id=%s dur_ms=%s",
        request.method,
        request.url.path,
        response.status_code,
        request.state.request_id,
        request.state.trace_id,
        duration_ms,
    )
    return response


# --- Latency helper ---------------------------------------------------------
async def apply_delay(base_ms: int) -> int:
    """Sleep for base_ms +/- JITTER_PCT and return the actual delay in ms."""
    if JITTER_PCT > 0:
        factor = 1.0 + random.uniform(-JITTER_PCT / 100.0, JITTER_PCT / 100.0)
    else:
        factor = 1.0
    actual = max(0, int(base_ms * factor))
    await asyncio.sleep(actual / 1000.0)
    return actual


def tag(resp: Response, tier: str, delay_ms: int) -> None:
    """Expose tier + applied delay as response headers for observability."""
    resp.headers["X-Tier"] = tier
    resp.headers["X-Delay-Ms"] = str(delay_ms)


# --- Stateless HMAC token (no server-side store -> no memory growth) ---------
def _b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _b64u_decode(data: str) -> bytes:
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))


def create_token(username: str) -> str:
    payload = {"sub": username, "exp": int(time.time()) + TOKEN_TTL}
    body = _b64u(json.dumps(payload, separators=(",", ":")).encode())
    sig = hmac.new(TOKEN_SECRET.encode(), body.encode(), hashlib.sha256).hexdigest()
    return f"{body}.{sig}"


def verify_token(token: str) -> dict:
    try:
        body, sig = token.split(".", 1)
    except ValueError:
        raise HTTPException(status_code=401, detail="Malformed token")
    expected = hmac.new(
        TOKEN_SECRET.encode(), body.encode(), hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(sig, expected):
        raise HTTPException(status_code=401, detail="Invalid token signature")
    payload = json.loads(_b64u_decode(body))
    if payload.get("exp", 0) < int(time.time()):
        raise HTTPException(status_code=401, detail="Token expired")
    return payload


async def require_auth(
    creds: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> dict:
    if creds is None:
        raise HTTPException(
            status_code=401,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return verify_token(creds.credentials)


# --- Schemas (make the OpenAPI spec rich for the model under test) ----------
class HealthResponse(BaseModel):
    status: str = "ok"
    tiers_ms: dict[str, int]
    jitter_pct: int
    request_id: str


class PingResponse(BaseModel):
    id: str
    ts: int = Field(..., description="server epoch milliseconds")
    tier: str = "fast"
    request_id: str


class LoginRequest(BaseModel):
    username: str = Field(..., examples=["loadtest"])
    password: str = Field(..., examples=["secret"])


class LoginResponse(BaseModel):
    token: str
    token_type: str = "Bearer"
    expires_in: int
    request_id: str


class OrderItem(BaseModel):
    sku: str
    qty: int
    price: float


class OrderResponse(BaseModel):
    order_id: int
    user: str
    items: list[OrderItem]
    total: float
    tier: str = "medium"
    request_id: str


class ReportRow(BaseModel):
    idx: int
    value: float
    label: str


class ReportResponse(BaseModel):
    count: int
    generated_ms: int
    rows: list[ReportRow]
    tier: str = "slow"
    request_id: str


# --- Health -----------------------------------------------------------------
@app.get("/health", response_model=HealthResponse, tags=["meta"])
async def health(req: Request) -> HealthResponse:
    return HealthResponse(
        tiers_ms={"fast": FAST_MS, "medium": MED_MS, "slow": SLOW_MS},
        jitter_pct=JITTER_PCT,
        request_id=req.state.request_id,
    )


# --- Fast tier --------------------------------------------------------------
@app.get("/api/fast/ping", response_model=PingResponse, tags=["fast"])
async def fast_ping(resp: Response, req: Request) -> PingResponse:
    delay = await apply_delay(FAST_MS)
    tag(resp, "fast", delay)
    return PingResponse(
        id=str(uuid.uuid4()),
        ts=int(time.time() * 1000),
        request_id=req.state.request_id,
    )


# --- Medium tier (login -> token -> orders correlation chain) ---------------
@app.post("/api/medium/login", response_model=LoginResponse, tags=["medium"])
async def login(body: LoginRequest, resp: Response, req: Request) -> LoginResponse:
    delay = await apply_delay(MED_MS)
    tag(resp, "medium", delay)
    if not body.username or not body.password:
        raise HTTPException(status_code=400, detail="username and password required")
    return LoginResponse(
        token=create_token(body.username),
        expires_in=TOKEN_TTL,
        request_id=req.state.request_id,
    )


@app.get(
    "/api/medium/orders/{order_id}",
    response_model=OrderResponse,
    tags=["medium"],
)
async def get_order(
    order_id: int,
    resp: Response,
    req: Request,
    auth: dict = Depends(require_auth),
) -> OrderResponse:
    delay = await apply_delay(MED_MS)
    tag(resp, "medium", delay)
    rng = random.Random(order_id)
    items = [
        OrderItem(
            sku=f"SKU-{rng.randint(1000, 9999)}",
            qty=rng.randint(1, 5),
            price=round(rng.uniform(5, 200), 2),
        )
        for _ in range(rng.randint(1, 4))
    ]
    total = round(sum(i.qty * i.price for i in items), 2)
    return OrderResponse(
        order_id=order_id,
        user=auth.get("sub", "unknown"),
        items=items,
        total=total,
        request_id=req.state.request_id,
    )


# --- Slow tier --------------------------------------------------------------
@app.get("/api/slow/report", response_model=ReportResponse, tags=["slow"])
async def slow_report(
    resp: Response,
    req: Request,
    rows: int = Query(500, ge=1, le=REPORT_MAX_ROWS),
) -> ReportResponse:
    delay = await apply_delay(SLOW_MS)
    tag(resp, "slow", delay)
    data = [
        ReportRow(idx=i, value=round(random.uniform(0, 1000), 4), label=f"row-{i}")
        for i in range(rows)
    ]
    return ReportResponse(
        count=rows,
        generated_ms=int(time.time() * 1000),
        rows=data,
        request_id=req.state.request_id,
    )
