from __future__ import annotations

import hashlib
import json
import re
import shutil
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime, date
from pathlib import Path
from typing import Any, Iterable

GF_RE = re.compile(r"\bGF[-\s]?(\d{2,6})\b", re.I)
ORDER_ID_RE = re.compile(r"\b\d{8,}-[A-Z0-9-]{8,}\b", re.I)
DELAY_PATTERNS = [
    re.compile(r"Delayed\s+by\s+(\d+)\s*(?:min|mins|minute|minutes)", re.I),
    re.compile(r"ล่าช้า(?:ไป)?\s*(\d+)\s*นาที", re.I),
]

STORES: dict[str, dict[str, str]] = {
    "kaprao": {
        "name": "กะเพรา",
        "marker": "กะเพรา",
        "drive_folder_id": "1sHM16q_xVgBZS-_uESPMnr3JWVRLrtfY",
        "drive_link": "https://drive.google.com/drive/folders/1sHM16q_xVgBZS-_uESPMnr3JWVRLrtfY",
    },
    "luksao": {
        "name": "ลูกสาวทำเอง",
        "marker": "ลูกสาว",
        "drive_folder_id": "1uLaXX6M0Cmb_gtdaKOeTVzcIiHQFCosq",
        "drive_link": "https://drive.google.com/drive/folders/1uLaXX6M0Cmb_gtdaKOeTVzcIiHQFCosq",
    },
}


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def clean_text(s: str | None) -> str:
    return re.sub(r"[\t\r ]+", " ", (s or "").replace("\u00a0", " ")).strip()


def normalize_gf(text: str | None) -> str | None:
    m = GF_RE.search(text or "")
    return f"GF-{m.group(1)}" if m else None


def extract_order_id(text: str | None) -> str | None:
    m = ORDER_ID_RE.search(text or "")
    return m.group(0) if m else None


def parse_delay_minutes(text: str | None) -> int | None:
    t = text or ""
    for p in DELAY_PATTERNS:
        m = p.search(t)
        if m:
            return int(m.group(1))
    return None


def is_delayed_text(text: str | None) -> bool:
    t = text or ""
    return parse_delay_minutes(t) is not None or bool(re.search(r"\bDelayed\b|ล่าช้า", t, re.I))


def parse_history_summary(text: str | None) -> tuple[int | None, int | None]:
    t = re.sub(r"\s+", " ", text or "")
    completed = None
    cancelled = None
    patterns = [
        (r"คำสั่งซื้อที่เสร็จสมบูรณ์\s*[:：]?\s*(\d+)", "completed"),
        (r"Completed\s+orders?\s*[:：]?\s*(\d+)", "completed"),
        (r"คำสั่งซื้อที่ยกเลิก\s*[:：]?\s*(\d+)", "cancelled"),
        (r"Cancelled\s+orders?\s*[:：]?\s*(\d+)", "cancelled"),
        (r"Canceled\s+orders?\s*[:：]?\s*(\d+)", "cancelled"),
    ]
    for p, kind in patterns:
        m = re.search(p, t, re.I)
        if m:
            if kind == "completed":
                completed = int(m.group(1))
            else:
                cancelled = int(m.group(1))
    return completed, cancelled


def _next_value(lines: list[str], labels: Iterable[str]) -> str | None:
    keys = {clean_text(x).lower() for x in labels}
    for i, line in enumerate(lines):
        if clean_text(line).lower() in keys:
            for nxt in lines[i + 1 : i + 5]:
                v = clean_text(nxt)
                if v and v.lower() not in keys:
                    return v
    return None


def parse_detail_fields(raw_text: str, tables: list[list[list[str]]] | None = None) -> dict[str, Any]:
    lines = [clean_text(x) for x in (raw_text or "").splitlines() if clean_text(x)]
    customer = _next_value(lines, ["ลูกค้า", "Customer"])
    note = _next_value(lines, ["หมายเหตุจากลูกค้า", "Customer note", "Note from customer"])
    total = None
    for i, line in enumerate(lines):
        if line.lower() in {"ทั้งหมด", "total"} and i + 1 < len(lines):
            m = re.search(r"(?:฿|THB\s*)?([\d,]+(?:\.\d{1,2})?)", lines[i + 1], re.I)
            if m:
                total = float(m.group(1).replace(",", ""))
    rows: list[list[str]] = []
    for table in tables or []:
        for row in table:
            cleaned = [clean_text(c) for c in row if clean_text(c)]
            if cleaned:
                rows.append(cleaned)
    return {
        "customer_name": customer,
        "customer_note": note,
        "total": total,
        "table_rows": rows,
    }


def safe_tag(order_id: str, gf: str | None, when: datetime | None = None) -> str:
    stamp = (when or datetime.now()).strftime("%H%M")
    digest = hashlib.sha1(order_id.encode("utf-8")).hexdigest()[:7]
    return f"{gf or 'NOGF'}_{stamp}_{digest}"


@dataclass
class DailySummary:
    date: str
    history_scanned: int
    completed_summary: int | None
    cancelled_summary: int | None
    total_orders: int
    ready_count: int
    delayed: int
    with_ready: int
    without_ready: int
    actual_delayed: int
    grab_pct: float | None
    actual_pct: float | None
    history_complete: bool


class Database:
    def __init__(self, path: Path, shop_key: str):
        self.path = Path(path)
        self.shop_key = shop_key
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._init()

    def _connect(self) -> sqlite3.Connection:
        con = sqlite3.connect(self.path, timeout=30)
        con.row_factory = sqlite3.Row
        return con

    def _init(self) -> None:
        with self._connect() as con:
            con.executescript(
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS orders (
                    order_id TEXT PRIMARY KEY,
                    shop_key TEXT NOT NULL,
                    gf TEXT,
                    first_ready_at TEXT,
                    ready_image TEXT,
                    detail_at TEXT,
                    detail_raw_text TEXT,
                    detail_json TEXT,
                    customer_name TEXT,
                    customer_note TEXT,
                    total REAL,
                    detail_images_json TEXT,
                    history_seen_at TEXT,
                    history_status TEXT,
                    completed_at TEXT,
                    delayed_minutes INTEGER,
                    delay_image TEXT,
                    last_updated TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS daily (
                    day TEXT PRIMARY KEY,
                    completed_summary INTEGER,
                    cancelled_summary INTEGER,
                    history_scanned INTEGER NOT NULL DEFAULT 0,
                    history_complete INTEGER NOT NULL DEFAULT 0,
                    last_scan_at TEXT
                );
                """
            )

    def upsert_ready(self, order_id: str, gf: str | None, image: str) -> None:
        ts = now_iso()
        with self._lock, self._connect() as con:
            con.execute(
                """
                INSERT INTO orders(order_id,shop_key,gf,first_ready_at,ready_image,last_updated)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(order_id) DO UPDATE SET
                    gf=COALESCE(excluded.gf,orders.gf),
                    first_ready_at=COALESCE(orders.first_ready_at,excluded.first_ready_at),
                    ready_image=COALESCE(orders.ready_image,excluded.ready_image),
                    last_updated=excluded.last_updated
                """,
                (order_id, self.shop_key, gf, ts, image, ts),
            )

    def upsert_detail(self, order_id: str, gf: str | None, raw_text: str, detail: dict[str, Any], images: list[str]) -> None:
        ts = now_iso()
        with self._lock, self._connect() as con:
            con.execute(
                """
                INSERT INTO orders(order_id,shop_key,gf,detail_at,detail_raw_text,detail_json,customer_name,customer_note,total,detail_images_json,last_updated)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(order_id) DO UPDATE SET
                    gf=COALESCE(excluded.gf,orders.gf), detail_at=excluded.detail_at,
                    detail_raw_text=excluded.detail_raw_text, detail_json=excluded.detail_json,
                    customer_name=excluded.customer_name, customer_note=excluded.customer_note,
                    total=excluded.total, detail_images_json=excluded.detail_images_json,
                    last_updated=excluded.last_updated
                """,
                (
                    order_id, self.shop_key, gf, ts, raw_text,
                    json.dumps(detail, ensure_ascii=False), detail.get("customer_name"), detail.get("customer_note"),
                    detail.get("total"), json.dumps(images, ensure_ascii=False), ts,
                ),
            )

    def mark_history(self, order_id: str, gf: str | None, status: str | None, delayed_minutes: int | None, delay_image: str | None = None) -> None:
        ts = now_iso()
        with self._lock, self._connect() as con:
            con.execute(
                """
                INSERT INTO orders(order_id,shop_key,gf,history_seen_at,history_status,delayed_minutes,delay_image,last_updated)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(order_id) DO UPDATE SET
                    gf=COALESCE(excluded.gf,orders.gf), history_seen_at=excluded.history_seen_at,
                    history_status=COALESCE(excluded.history_status,orders.history_status),
                    delayed_minutes=COALESCE(excluded.delayed_minutes,orders.delayed_minutes),
                    delay_image=COALESCE(excluded.delay_image,orders.delay_image),
                    last_updated=excluded.last_updated
                """,
                (order_id, self.shop_key, gf, ts, status, delayed_minutes, delay_image, ts),
            )

    def has_ready(self, order_id: str) -> bool:
        with self._connect() as con:
            row = con.execute("SELECT ready_image FROM orders WHERE order_id=?", (order_id,)).fetchone()
            return bool(row and row[0])

    def has_detail(self, order_id: str) -> bool:
        with self._connect() as con:
            row = con.execute("SELECT detail_raw_text FROM orders WHERE order_id=?", (order_id,)).fetchone()
            return bool(row and row[0])

    def set_daily_scan(self, day: str, completed: int | None, cancelled: int | None, scanned: int, complete: bool) -> None:
        with self._lock, self._connect() as con:
            con.execute(
                """
                INSERT INTO daily(day,completed_summary,cancelled_summary,history_scanned,history_complete,last_scan_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(day) DO UPDATE SET completed_summary=excluded.completed_summary,
                    cancelled_summary=excluded.cancelled_summary, history_scanned=excluded.history_scanned,
                    history_complete=excluded.history_complete, last_scan_at=excluded.last_scan_at
                """,
                (day, completed, cancelled, scanned, 1 if complete else 0, now_iso()),
            )

    def get_order(self, order_id: str) -> dict[str, Any] | None:
        with self._connect() as con:
            row = con.execute("SELECT * FROM orders WHERE order_id=?", (order_id,)).fetchone()
            return dict(row) if row else None

    def summary(self, day: str | None = None) -> DailySummary:
        day = day or date.today().isoformat()
        with self._connect() as con:
            d = con.execute("SELECT * FROM daily WHERE day=?", (day,)).fetchone()
            orders = con.execute(
                "SELECT * FROM orders WHERE substr(COALESCE(history_seen_at,first_ready_at,detail_at,''),1,10)=?",
                (day,),
            ).fetchall()
        completed = d["completed_summary"] if d else None
        cancelled = d["cancelled_summary"] if d else None
        scanned = int(d["history_scanned"] if d else sum(1 for r in orders if r["history_seen_at"]))
        if completed is not None or cancelled is not None:
            total = int(completed or 0) + int(cancelled or 0)
        else:
            total = scanned
        ready_count = sum(1 for r in orders if r["ready_image"])
        delayed_rows = [r for r in orders if r["delayed_minutes"] is not None or is_delayed_text(r["history_status"])]
        with_ready = sum(1 for r in delayed_rows if r["ready_image"])
        delayed = len(delayed_rows)
        actual = max(delayed - with_ready, 0)
        denom = total if total > 0 else None
        return DailySummary(
            date=day,
            history_scanned=scanned,
            completed_summary=completed,
            cancelled_summary=cancelled,
            total_orders=total,
            ready_count=ready_count,
            delayed=delayed,
            with_ready=with_ready,
            without_ready=delayed - with_ready,
            actual_delayed=actual,
            grab_pct=(delayed / denom * 100.0) if denom else None,
            actual_pct=(actual / denom * 100.0) if denom else None,
            history_complete=bool(d and d["history_complete"]),
        )


class Storage:
    def __init__(self, base: Path, shop_key: str, sync_dir: str | None = None):
        self.base = Path(base)
        self.shop_key = shop_key
        self.sync_dir = Path(sync_dir) if sync_dir else None

    @property
    def shop_root(self) -> Path:
        return self.base / STORES[self.shop_key]["name"]

    def day_dir(self, day: str | None = None) -> Path:
        p = self.shop_root / (day or date.today().isoformat())
        for child in ("READY", "DETAIL", "DELAY", "DATA"):
            (p / child).mkdir(parents=True, exist_ok=True)
        return p

    def path(self, kind: str, name: str, day: str | None = None) -> Path:
        p = self.day_dir(day) / kind.upper() / name
        p.parent.mkdir(parents=True, exist_ok=True)
        return p

    def sync(self, path: Path) -> Path | None:
        if not self.sync_dir:
            return None
        try:
            self.sync_dir.mkdir(parents=True, exist_ok=True)
            dst = self.sync_dir / path.name
            shutil.copy2(path, dst)
            return dst
        except Exception:
            return None

    def write_order_json(self, db: Database, order_id: str) -> Path | None:
        row = db.get_order(order_id)
        if not row:
            return None
        gf = row.get("gf") or "NOGF"
        tag = safe_tag(order_id, gf)
        p = self.path("DATA", f"{tag}_ORDER.json")
        payload = dict(row)
        for k in ("detail_json", "detail_images_json"):
            if payload.get(k):
                try:
                    payload[k] = json.loads(payload[k])
                except Exception:
                    pass
        p.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        self.sync(p)
        return p


def summary_text(s: DailySummary) -> str:
    grab = "-" if s.grab_pct is None else f"{s.grab_pct:.2f}%"
    actual = "-" if s.actual_pct is None else f"{s.actual_pct:.2f}%"
    return (
        f"Orders {s.total_orders} | Ready {s.ready_count} | History {s.history_scanned} | "
        f"Delayed {s.delayed} | มี Ready {s.with_ready} | ไม่มี Ready {s.without_ready} | "
        f"Grab {grab} | Actual {actual} | History {'ครบ' if s.history_complete else 'ยังไม่ยืนยันครบ'}"
    )
