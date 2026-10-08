from __future__ import annotations

import asyncio
import queue
import re
import threading
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Callable

from playwright.async_api import async_playwright, Page

from core import (
    Database, Storage, STORES, clean_text, normalize_gf,
    parse_delay_minutes, is_delayed_text, parse_history_summary, parse_detail_fields, safe_tag,
)

ROW_JS = r"""
(root) => {
  const gfRe = /\bGF[-\s]?\d{2,6}\b/i;
  const idRe = /\b\d{8,}-[A-Z0-9-]{8,}\b/i;
  const out = [];
  const seen = new Set();
  const nodes = [...root.querySelectorAll('*')].filter(e => {
    const t = (e.innerText || '').trim();
    return t && gfRe.test(t) && e.children.length === 0;
  });
  for (const n of nodes) {
    const gf = ((n.innerText || '').match(gfRe) || [])[0];
    if (!gf) continue;
    let cur = n;
    let best = n;
    for (let i=0; i<8 && cur; i++, cur=cur.parentElement) {
      const txt = (cur.innerText || '').trim();
      if (idRe.test(txt) && txt.length < 1400) best = cur;
      if (cur.tagName === 'TR' || cur.getAttribute('role') === 'row') { best = cur; break; }
    }
    const text = (best.innerText || '').trim();
    const m = text.match(idRe);
    if (!m) continue;
    const key = m[0];
    if (seen.has(key)) continue;
    seen.add(key);
    const r = best.getBoundingClientRect();
    out.push({order_id:key, gf:gf.replace(/\s+/g,''), text, x:r.x, y:r.y, width:r.width, height:r.height});
  }
  return out;
}
"""

FIND_SCROLL_JS = r"""
() => {
  const gfRe = /\bGF[-\s]?\d{2,6}\b/ig;
  const cand = [...document.querySelectorAll('*')].filter(e => {
    const s = getComputedStyle(e);
    return e.scrollHeight > e.clientHeight + 30 && ['auto','scroll'].includes(s.overflowY);
  });
  let best = document.scrollingElement;
  let score = -1;
  for (const e of cand) {
    const t = e.innerText || '';
    const c = (t.match(gfRe) || []).length;
    if (c > score) { score=c; best=e; }
  }
  if (!best) return {doc:true, top:0, height:0, client:0};
  best.dataset.readyproofScroll = '1';
  return {doc: best === document.scrollingElement, top:best.scrollTop, height:best.scrollHeight, client:best.clientHeight};
}
"""

DETAIL_JS = r"""
({orderId,gf}) => {
  const all = [...document.querySelectorAll('aside,[role=dialog],div,section')];
  let best = null, bestScore = -1;
  for (const e of all) {
    const t = (e.innerText || '').trim();
    if (!t.includes(orderId) || (gf && !t.includes(gf))) continue;
    const r = e.getBoundingClientRect();
    if (r.width < 320 || r.height < 250) continue;
    let score = 0;
    if (r.x > innerWidth * .45) score += 10;
    if (r.right > innerWidth * .9) score += 5;
    score += Math.min(5, t.length / 1000);
    if (score > bestScore) { best=e; bestScore=score; }
  }
  if (!best) return null;
  const tables = [...best.querySelectorAll('table')].map(tab =>
    [...tab.querySelectorAll('tr')].map(tr => [...tr.querySelectorAll('th,td')].map(td => (td.innerText||'').trim()))
  );
  const scrollables = [...best.querySelectorAll('*')].filter(e => e.scrollHeight > e.clientHeight + 30);
  let scroll = scrollables.sort((a,b) => b.scrollHeight - a.scrollHeight)[0] || null;
  if (scroll) scroll.dataset.readyproofDetailScroll = '1';
  const r = best.getBoundingClientRect();
  return {text:(best.innerText||'').trim(), tables, x:r.x,y:r.y,width:r.width,height:r.height,
          scrollHeight:scroll?scroll.scrollHeight:0, clientHeight:scroll?scroll.clientHeight:0};
}
"""


@dataclass
class WorkerConfig:
    shop_key: str
    base_dir: Path
    sync_dir: str | None
    window_x: int
    window_y: int
    window_w: int
    window_h: int


class StoreWorker:
    def __init__(self, cfg: WorkerConfig, callback: Callable[[str, str, dict[str, Any] | None], None]):
        self.cfg = cfg
        self.callback = callback
        self.store = STORES[cfg.shop_key]
        self.db = Database(cfg.base_dir / self.store["name"] / "orders.db", cfg.shop_key)
        self.storage = Storage(cfg.base_dir, cfg.shop_key, cfg.sync_dir)
        self.commands: queue.Queue[tuple[str, Any]] = queue.Queue()
        self.detail_queue: asyncio.Queue[tuple[str, str | None]] | None = None
        self.thread: threading.Thread | None = None
        self.stop_flag = threading.Event()
        self.store_id: str | None = None

    def start(self) -> None:
        if self.thread and self.thread.is_alive():
            return
        self.stop_flag.clear()
        self.thread = threading.Thread(target=self._thread_main, name=f"ReadyProof-{self.cfg.shop_key}", daemon=True)
        self.thread.start()

    def stop(self) -> None:
        self.stop_flag.set()
        self.commands.put(("stop", None))

    def scan_history(self) -> None:
        self.commands.put(("history", None))

    def refresh_sync_dir(self, path: str | None) -> None:
        self.commands.put(("sync", path))

    def summary(self):
        return self.db.summary()

    def _emit(self, event: str, message: str, data: dict[str, Any] | None = None) -> None:
        try:
            self.callback(event, message, data)
        except Exception:
            pass

    def _thread_main(self) -> None:
        try:
            asyncio.run(self._main())
        except Exception as e:
            self._emit("error", f"{type(e).__name__}: {e}")

    async def _main(self) -> None:
        self.detail_queue = asyncio.Queue()
        profile = self.cfg.base_dir / "profiles" / self.cfg.shop_key
        profile.mkdir(parents=True, exist_ok=True)
        self._emit("status", "กำลังเปิด Chrome")
        async with async_playwright() as p:
            context = await p.chromium.launch_persistent_context(
                user_data_dir=str(profile), channel="chrome", headless=False, no_viewport=True,
                args=[
                    f"--window-position={self.cfg.window_x},{self.cfg.window_y}",
                    f"--window-size={self.cfg.window_w},{self.cfg.window_h}",
                    "--disable-session-crashed-bubble",
                ],
            )
            pages = context.pages
            ready_page = pages[0] if pages else await context.new_page()
            detail_page = await context.new_page()
            if not ready_page.url.startswith("https://merchant.grab.com"):
                await ready_page.goto("https://merchant.grab.com/", wait_until="domcontentloaded", timeout=60_000)
            tasks = [
                asyncio.create_task(self._monitor_loop(ready_page)),
                asyncio.create_task(self._detail_loop(detail_page)),
                asyncio.create_task(self._command_loop(detail_page)),
            ]
            while not self.stop_flag.is_set():
                await asyncio.sleep(.5)
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            await context.close()

    def _store_id_from_url(self, url: str) -> str | None:
        m = re.search(r"merchant\.grab\.com/order/([^/?#]+)/", url)
        return m.group(1) if m else None

    async def _ensure_store(self, page: Page) -> bool:
        sid = self._store_id_from_url(page.url)
        if not sid:
            self._emit("status", "รอให้ล็อกอินและเลือกร้านใน Chrome")
            return False
        body = clean_text(await page.locator("body").inner_text(timeout=10_000))
        marker = self.store["marker"]
        if marker not in body:
            self._emit("error", f"หน้าต่างนี้ไม่ใช่ร้าน {self.store['name']} — หยุดเก็บข้อมูล")
            return False
        self.store_id = sid
        return True

    async def _goto_mode(self, page: Page, mode: str) -> bool:
        if not self.store_id:
            if not await self._ensure_store(page):
                return False
        target = f"https://merchant.grab.com/order/{self.store_id}/{mode}"
        if not page.url.startswith(target):
            await page.goto(target, wait_until="domcontentloaded", timeout=60_000)
            await page.wait_for_timeout(1000)
        return await self._ensure_store(page)

    async def _monitor_loop(self, page: Page) -> None:
        while not self.stop_flag.is_set():
            try:
                if not await self._ensure_store(page):
                    await asyncio.sleep(2)
                    continue
                if not await self._goto_mode(page, "ready"):
                    await asyncio.sleep(2)
                    continue
                rows = await self._sweep_rows(page)
                new_count = 0
                for row in rows:
                    oid = row["order_id"]
                    gf = normalize_gf(row.get("gf") or row.get("text"))
                    if self.db.has_ready(oid):
                        continue
                    path = await self._capture_ready(page, oid, gf)
                    if not path:
                        continue
                    self.db.upsert_ready(oid, gf, str(path))
                    self.storage.sync(path)
                    self.storage.write_order_json(self.db, oid)
                    new_count += 1
                    if self.detail_queue:
                        await self.detail_queue.put((oid, gf))
                self._emit("summary", f"Ready scan: {len(rows)} ออเดอร์ / ใหม่ {new_count}")
            except asyncio.CancelledError:
                raise
            except Exception as e:
                self._emit("error", f"Ready scan: {e}")
            await asyncio.sleep(2.0)

    async def _find_scroll_root(self, page: Page) -> None:
        await page.evaluate(FIND_SCROLL_JS)

    async def _sweep_rows(self, page: Page) -> list[dict[str, Any]]:
        await self._find_scroll_root(page)
        all_rows: dict[str, dict[str, Any]] = {}
        last_top = -1
        repeats = 0
        for _ in range(120):
            rows = await page.evaluate(
                """() => { const r=document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement || document.body; return (""" + ROW_JS + """)(r); }"""
            )
            for row in rows or []:
                if row.get("order_id"):
                    all_rows[row["order_id"]] = row
            metrics = await page.evaluate(
                """() => { const r=document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement; return {top:r.scrollTop,height:r.scrollHeight,client:r.clientHeight}; }"""
            )
            top = int(metrics.get("top", 0))
            height = int(metrics.get("height", 0))
            client = int(metrics.get("client", 0))
            if top >= max(0, height - client - 4):
                break
            nxt = min(top + max(200, int(client * .75)), max(0, height - client))
            await page.evaluate("""v => { const r=document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement; r.scrollTop=v; }""", nxt)
            await page.wait_for_timeout(350)
            new_top = int((await page.evaluate("""() => (document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement).scrollTop""")) or 0)
            if new_top == last_top:
                repeats += 1
            else:
                repeats = 0
            last_top = new_top
            if repeats >= 3:
                break
        await page.evaluate("""() => { const r=document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement; r.scrollTop=0; }""")
        return list(all_rows.values())

    async def _capture_ready(self, page: Page, order_id: str, gf: str | None) -> Path | None:
        try:
            loc = page.get_by_text(order_id, exact=True).first
            await loc.scroll_into_view_if_needed(timeout=5000)
            await page.wait_for_timeout(250)
            text = clean_text(await page.locator("body").inner_text())
            if order_id not in text or (gf and gf not in text):
                return None
            tag = safe_tag(order_id, gf)
            path = self.storage.path("READY", f"{tag}_READY.png")
            await page.screenshot(path=str(path), full_page=False)
            return path
        except Exception as e:
            self._emit("error", f"READY {gf or order_id}: แคปไม่สำเร็จ {e}")
            return None

    async def _detail_loop(self, page: Page) -> None:
        assert self.detail_queue is not None
        while not self.stop_flag.is_set():
            try:
                oid, gf = await asyncio.wait_for(self.detail_queue.get(), timeout=1.0)
            except asyncio.TimeoutError:
                continue
            try:
                if self.db.has_detail(oid):
                    continue
                await self._capture_detail(page, oid, gf)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                self._emit("error", f"DETAIL {gf or oid}: {e}")

    async def _click_order(self, page: Page, order_id: str, gf: str | None) -> bool:
        for mode in ("ready", "history"):
            if not await self._goto_mode(page, mode):
                continue
            await self._sweep_rows(page)
            loc = page.get_by_text(order_id, exact=True)
            if await loc.count() == 0 and gf:
                loc = page.get_by_text(gf, exact=True)
            if await loc.count() == 0:
                continue
            target = loc.first
            try:
                row = target.locator("xpath=ancestor::tr[1]")
                if await row.count():
                    target = row
            except Exception:
                pass
            await target.scroll_into_view_if_needed(timeout=5000)
            await target.click(timeout=5000)
            try:
                await page.wait_for_function(
                    """({oid,gf}) => { const t=document.body.innerText||''; return t.includes(oid) && (!gf || t.includes(gf)); }""",
                    {"oid": order_id, "gf": gf}, timeout=7000,
                )
                return True
            except Exception:
                await page.keyboard.press("Escape")
        return False

    async def _detail_snapshot(self, page: Page, order_id: str, gf: str | None) -> dict[str, Any] | None:
        return await page.evaluate(DETAIL_JS, {"orderId": order_id, "gf": gf})

    async def _capture_detail(self, page: Page, order_id: str, gf: str | None) -> dict[str, Any] | None:
        if not await self._click_order(page, order_id, gf):
            self._emit("error", f"DETAIL {gf or order_id}: หาออเดอร์ไม่เจอ")
            return None
        await page.wait_for_timeout(500)
        snap = await self._detail_snapshot(page, order_id, gf)
        if not snap:
            self._emit("error", f"DETAIL {gf or order_id}: หาแผงรายละเอียดไม่เจอ")
            await page.keyboard.press("Escape")
            return None
        raw = snap.get("text", "")
        tables = snap.get("tables") or []
        detail = parse_detail_fields(raw, tables)
        images: list[str] = []
        tag = safe_tag(order_id, gf)
        scroll_h = int(snap.get("scrollHeight") or 0)
        client_h = int(snap.get("clientHeight") or 0)
        positions = [0]
        if scroll_h > client_h > 0:
            step = max(300, int(client_h * .75))
            positions = list(range(0, max(1, scroll_h - client_h + 1), step))
            end = max(0, scroll_h - client_h)
            if positions[-1] != end:
                positions.append(end)
        for idx, pos in enumerate(positions[:20], 1):
            if scroll_h > client_h > 0:
                await page.evaluate("""v => { const e=document.querySelector('[data-readyproof-detail-scroll="1"]'); if(e) e.scrollTop=v; }""", pos)
                await page.wait_for_timeout(250)
            path = self.storage.path("DETAIL", f"{tag}_DETAIL_{idx}.png")
            await page.screenshot(path=str(path), full_page=False)
            self.storage.sync(path)
            images.append(str(path))
        self.db.upsert_detail(order_id, gf, raw, detail, images)
        self.storage.write_order_json(self.db, order_id)
        await page.keyboard.press("Escape")
        self._emit("summary", f"เก็บรายละเอียด {gf or order_id} แล้ว")
        return {"raw": raw, "detail": detail, "images": images}

    async def _command_loop(self, page: Page) -> None:
        while not self.stop_flag.is_set():
            try:
                cmd, value = self.commands.get_nowait()
            except queue.Empty:
                await asyncio.sleep(.25)
                continue
            if cmd == "stop":
                return
            if cmd == "sync":
                self.storage.sync_dir = Path(value) if value else None
            if cmd == "history":
                try:
                    await self._scan_history(page)
                except Exception as e:
                    self._emit("error", f"History scan: {e}")

    async def _scan_history(self, page: Page) -> None:
        self._emit("status", "กำลังกวาด History")
        if not await self._goto_mode(page, "history"):
            return
        await page.wait_for_timeout(1000)
        body = clean_text(await page.locator("body").inner_text())
        completed, cancelled = parse_history_summary(body)
        rows = await self._sweep_rows(page)
        delayed_count = 0
        for i, row in enumerate(rows, 1):
            oid = row["order_id"]
            gf = normalize_gf(row.get("gf") or row.get("text"))
            row_text = row.get("text") or ""
            status = row_text
            delay_min = parse_delay_minutes(row_text)
            try:
                if await self._click_order(page, oid, gf):
                    await page.wait_for_timeout(250)
                    snap = await self._detail_snapshot(page, oid, gf)
                    if snap:
                        detail_raw = snap.get("text", "")
                        delay_min = delay_min if delay_min is not None else parse_delay_minutes(detail_raw)
                        status = detail_raw if is_delayed_text(detail_raw) else row_text
                        if not self.db.has_detail(oid):
                            detail = parse_detail_fields(detail_raw, snap.get("tables") or [])
                            self.db.upsert_detail(oid, gf, detail_raw, detail, [])
                    await page.keyboard.press("Escape")
            except Exception:
                try:
                    await page.keyboard.press("Escape")
                except Exception:
                    pass
            delay_path = None
            if delay_min is not None or is_delayed_text(status):
                delayed_count += 1
                if await self._click_order(page, oid, gf):
                    await page.wait_for_timeout(300)
                    visible = clean_text(await page.locator("body").inner_text())
                    if oid in visible and (not gf or gf in visible) and is_delayed_text(visible):
                        tag = safe_tag(oid, gf)
                        delay_path = self.storage.path("DELAY", f"{tag}_DELAY.png")
                        await page.screenshot(path=str(delay_path), full_page=False)
                        self.storage.sync(delay_path)
                    await page.keyboard.press("Escape")
            self.db.mark_history(oid, gf, status, delay_min, str(delay_path) if delay_path else None)
            self.storage.write_order_json(self.db, oid)
            if i % 10 == 0:
                self._emit("status", f"History {i}/{len(rows)}")
        day = date.today().isoformat()
        expected = None
        if completed is not None or cancelled is not None:
            expected = int(completed or 0) + int(cancelled or 0)
        complete = expected is not None and len(rows) == expected
        self.db.set_daily_scan(day, completed, cancelled, len(rows), complete)
        self._emit("summary", f"History เสร็จ: สแกน {len(rows)} / summary {expected if expected is not None else '?'} / Delayed {delayed_count}", {"complete": complete})
        self._emit("status", "History ครบ" if complete else "History ยังไม่ครบ — ห้ามถือว่ายอดจบ")
