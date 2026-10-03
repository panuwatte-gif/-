from __future__ import annotations

import json
import os
import queue
import subprocess
import tkinter as tk
from pathlib import Path
from tkinter import filedialog
import webbrowser

from core import STORES, summary_text
from grab_worker import StoreWorker, WorkerConfig

APP_NAME = "ReadyProofPC"


def app_data_dir() -> Path:
    root = Path(os.environ.get("APPDATA") or Path.home()) / APP_NAME
    root.mkdir(parents=True, exist_ok=True)
    return root


def default_data_dir() -> Path:
    p = Path.home() / "Documents" / APP_NAME
    p.mkdir(parents=True, exist_ok=True)
    return p


class Settings:
    def __init__(self):
        self.path = app_data_dir() / "settings.json"
        self.data = {"base_dir": str(default_data_dir()), "stores": {k: {"sync_dir": ""} for k in STORES}}
        self.load()

    def load(self):
        if self.path.exists():
            try:
                loaded = json.loads(self.path.read_text(encoding="utf-8"))
                self.data.update(loaded)
                self.data.setdefault("stores", {})
                for k in STORES:
                    self.data["stores"].setdefault(k, {"sync_dir": ""})
            except Exception:
                pass

    def save(self):
        self.path.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("ReadyProof PC — 2 ร้าน")
        self.geometry("980x650")
        self.minsize(900, 580)
        self.settings = Settings()
        self.events: queue.Queue[tuple[str, str, str, dict | None]] = queue.Queue()
        self.workers: dict[str, StoreWorker] = {}
        self.labels: dict[str, dict[str, tk.StringVar]] = {}
        self._build()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(500, self.process_events)
        self.after(1200, self.start_all)

    def _build(self):
        top = tk.Frame(self, padx=12, pady=10)
        top.pack(fill="x")
        tk.Label(top, text="ReadyProof PC", font=("Segoe UI", 18, "bold")).pack(side="left")
        tk.Label(top, text="  2 ร้าน · READY + DETAIL + HISTORY + DELAY", font=("Segoe UI", 10)).pack(side="left")
        tk.Button(top, text="เริ่ม 2 ร้าน", command=self.start_all).pack(side="right", padx=4)
        tk.Button(top, text="หยุด", command=self.stop_all).pack(side="right", padx=4)

        wrap = tk.Frame(self, padx=10)
        wrap.pack(fill="x")
        for key in ("kaprao", "luksao"):
            self._store_panel(wrap, key).pack(side="left", fill="both", expand=True, padx=5)

        info = tk.Frame(self, padx=12, pady=8)
        info.pack(fill="x")
        tk.Label(info, text="ระบบบันทึกลง PC ก่อน แล้วคัดลอกไฟล์ใหม่ไปโฟลเดอร์ Google Drive for desktop ที่ตั้งไว้ (ต้องเป็นออเดอร์รอตรวจของร้านนั้น)", anchor="w").pack(fill="x")
        tk.Label(info, text="ครั้งแรก Chrome ของแต่ละร้านจะเปิด profile แยกกัน ให้ล็อกอิน/เลือกร้านให้ถูก 1 ครั้ง จากนั้น session จะค้างไว้", anchor="w").pack(fill="x")

        log_frame = tk.LabelFrame(self, text="Log", padx=6, pady=6)
        log_frame.pack(fill="both", expand=True, padx=12, pady=6)
        self.logbox = tk.Text(log_frame, height=12, wrap="word", state="disabled", font=("Consolas", 9))
        self.logbox.pack(fill="both", expand=True)

    def _store_panel(self, parent, key: str):
        store = STORES[key]
        f = tk.LabelFrame(parent, text=store["name"], padx=10, pady=8)
        status = tk.StringVar(value="ยังไม่เริ่ม")
        summary = tk.StringVar(value="-")
        sync = tk.StringVar(value=self.settings.data["stores"].get(key, {}).get("sync_dir") or "ยังไม่ได้ตั้ง Drive sync path")
        self.labels[key] = {"status": status, "summary": summary, "sync": sync}
        tk.Label(f, textvariable=status, font=("Segoe UI", 11, "bold"), anchor="w").pack(fill="x")
        tk.Label(f, textvariable=summary, justify="left", anchor="w", wraplength=420).pack(fill="x", pady=(4, 8))
        row = tk.Frame(f)
        row.pack(fill="x")
        tk.Button(row, text="สแกน History ตอนนี้", command=lambda k=key: self.scan_history(k)).pack(side="left", padx=(0, 4))
        tk.Button(row, text="เปิด Drive รอตรวจ", command=lambda k=key: webbrowser.open(STORES[k]["drive_link"])).pack(side="left")
        row2 = tk.Frame(f)
        row2.pack(fill="x", pady=(6, 0))
        tk.Button(row2, text="ตั้ง Drive sync path", command=lambda k=key: self.choose_sync(k)).pack(side="left", padx=(0, 4))
        tk.Button(row2, text="เปิดข้อมูลใน PC", command=lambda k=key: self.open_local(k)).pack(side="left")
        tk.Label(f, textvariable=sync, anchor="w", fg="#555", wraplength=420).pack(fill="x", pady=(6, 0))
        return f

    def callback(self, key: str):
        def cb(event: str, message: str, data: dict | None = None):
            self.events.put((key, event, message, data))
        return cb

    def start_all(self):
        if self.workers:
            return
        base = Path(self.settings.data["base_dir"])
        sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
        half = max(720, sw // 2)
        for idx, key in enumerate(("kaprao", "luksao")):
            sync_dir = self.settings.data["stores"].get(key, {}).get("sync_dir") or None
            cfg = WorkerConfig(
                shop_key=key, base_dir=base, sync_dir=sync_dir,
                window_x=idx * half, window_y=0, window_w=half, window_h=max(700, sh - 80),
            )
            w = StoreWorker(cfg, self.callback(key))
            self.workers[key] = w
            w.start()
            self.labels[key]["status"].set("กำลังเปิด Chrome")
        self.write_log("SYSTEM", "เปิด 2 Chrome profiles แยกร้านแล้ว")

    def stop_all(self):
        for w in self.workers.values():
            w.stop()
        self.workers.clear()
        for key in self.labels:
            self.labels[key]["status"].set("หยุดแล้ว")

    def scan_history(self, key: str):
        w = self.workers.get(key)
        if not w:
            self.write_log(STORES[key]["name"], "ยังไม่ได้เริ่ม worker")
            return
        w.scan_history()
        self.labels[key]["status"].set("สั่งสแกน History แล้ว")

    def choose_sync(self, key: str):
        p = filedialog.askdirectory(title=f"เลือก Google Drive for desktop > ออเดอร์รอตรวจ > {STORES[key]['name']}")
        if not p:
            return
        self.settings.data["stores"].setdefault(key, {})["sync_dir"] = p
        self.settings.save()
        self.labels[key]["sync"].set(p)
        if key in self.workers:
            self.workers[key].refresh_sync_dir(p)
        self.write_log(STORES[key]["name"], f"ตั้ง Drive sync path: {p}")

    def open_local(self, key: str):
        p = Path(self.settings.data["base_dir"]) / STORES[key]["name"]
        p.mkdir(parents=True, exist_ok=True)
        try:
            os.startfile(p)  # type: ignore[attr-defined]
        except Exception:
            subprocess.Popen(["explorer", str(p)])

    def process_events(self):
        while True:
            try:
                key, event, message, data = self.events.get_nowait()
            except queue.Empty:
                break
            name = STORES[key]["name"]
            if event in {"status", "error"}:
                self.labels[key]["status"].set(message)
            self.write_log(name, message)
            w = self.workers.get(key)
            if w:
                try:
                    self.labels[key]["summary"].set(summary_text(w.summary()))
                except Exception:
                    pass
        self.after(700, self.process_events)

    def write_log(self, who: str, msg: str):
        self.logbox.configure(state="normal")
        self.logbox.insert("end", f"[{who}] {msg}\n")
        self.logbox.see("end")
        self.logbox.configure(state="disabled")

    def on_close(self):
        self.stop_all()
        self.after(300, self.destroy)


if __name__ == "__main__":
    App().mainloop()
