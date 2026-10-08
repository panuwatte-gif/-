from __future__ import annotations

from pathlib import Path

from playwright.async_api import Page

from core import clean_text, safe_tag
from grab_worker import StoreWorker as BaseStoreWorker


class StoreWorker(BaseStoreWorker):
    """Production targeting fixes for Grab's virtualized order table.

    The full-table sweep restores the table to the top. Grab may virtualize rows, so an order
    discovered near the bottom may no longer exist in the DOM at capture/click time. Always seek
    the exact long order ID again before taking proof or opening detail.
    """

    async def _scroll_to_order(self, page: Page, order_id: str) -> bool:
        await self._find_scroll_root(page)
        await page.evaluate(
            """() => { const r=document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement; r.scrollTop=0; }"""
        )
        await page.wait_for_timeout(150)
        last_top = -1
        for _ in range(120):
            if await page.get_by_text(order_id, exact=True).count() > 0:
                return True
            metrics = await page.evaluate(
                """() => { const r=document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement; return {top:r.scrollTop,height:r.scrollHeight,client:r.clientHeight}; }"""
            )
            top = int(metrics.get("top", 0))
            height = int(metrics.get("height", 0))
            client = int(metrics.get("client", 0))
            if top >= max(0, height - client - 4):
                return False
            nxt = min(top + max(180, int(client * .65)), max(0, height - client))
            await page.evaluate(
                """v => { const r=document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement; r.scrollTop=v; }""",
                nxt,
            )
            await page.wait_for_timeout(250)
            new_top = int(
                (await page.evaluate(
                    """() => (document.querySelector('[data-readyproof-scroll="1"]') || document.scrollingElement).scrollTop"""
                ))
                or 0
            )
            if new_top == last_top:
                return False
            last_top = new_top
        return False

    async def _capture_ready(self, page: Page, order_id: str, gf: str | None) -> Path | None:
        try:
            if not await self._scroll_to_order(page, order_id):
                return None
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

    async def _click_order(self, page: Page, order_id: str, gf: str | None) -> bool:
        for mode in ("ready", "history"):
            if not await self._goto_mode(page, mode):
                continue
            if not await self._scroll_to_order(page, order_id):
                continue
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
                    {"oid": order_id, "gf": gf},
                    timeout=7000,
                )
                return True
            except Exception:
                await page.keyboard.press("Escape")
        return False
