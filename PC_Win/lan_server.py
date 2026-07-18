"""Servidor HTTP local para recibir saves desde Android."""

from __future__ import annotations

import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import unquote

from config_manager import is_valid_device_id, load_config

_SAFE_NAME = re.compile(r"[^A-Za-z0-9._\- ()\[\]]+")


def _safe_filename(name: str) -> str:
    cleaned = Path(unquote(name)).name.strip()
    cleaned = _SAFE_NAME.sub("_", cleaned)
    return cleaned or "save.bin"


class SyncHandler(BaseHTTPRequestHandler):
    server_version = "SyncSaves/0.1"

    def log_message(self, format: str, *args) -> None:  # noqa: A003
        if self.server.on_log:
            self.server.on_log(format % args)

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.rstrip("/") == "/ping":
            config = load_config()
            self._json(
                200,
                {
                    "ok": True,
                    "device_id": config.get("device_id"),
                    "saves_folder": config.get("saves_folder") or "",
                },
            )
            return
        self._json(404, {"ok": False, "error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path.rstrip("/") != "/upload":
            self._json(404, {"ok": False, "error": "not_found"})
            return

        config = load_config()
        expected = str(config.get("device_id", "")).upper()
        provided = (self.headers.get("X-Device-Id") or "").strip().upper()
        filename = _safe_filename(self.headers.get("X-Filename") or "save.bin")

        if not is_valid_device_id(expected):
            self._json(500, {"ok": False, "error": "pc_device_id_invalid"})
            return
        if provided != expected:
            self._json(403, {"ok": False, "error": "device_id_mismatch"})
            return

        folder = (config.get("saves_folder") or "").strip()
        if not folder:
            self._json(400, {"ok": False, "error": "saves_folder_not_configured"})
            return

        target_dir = Path(folder)
        try:
            target_dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            self._json(500, {"ok": False, "error": f"cannot_create_folder: {exc}"})
            return

        length = int(self.headers.get("Content-Length") or "0")
        if length <= 0:
            self._json(400, {"ok": False, "error": "empty_body"})
            return

        data = self.rfile.read(length)
        destination = target_dir / filename
        try:
            destination.write_bytes(data)
        except OSError as exc:
            self._json(500, {"ok": False, "error": f"write_failed: {exc}"})
            return

        if self.server.on_upload:
            self.server.on_upload(str(destination))

        self._json(
            200,
            {
                "ok": True,
                "saved_as": str(destination),
                "bytes": len(data),
            },
        )


class SyncHTTPServer(ThreadingHTTPServer):
    def __init__(
        self,
        server_address: tuple[str, int],
        on_log: Callable[[str], None] | None = None,
        on_upload: Callable[[str], None] | None = None,
    ) -> None:
        super().__init__(server_address, SyncHandler)
        self.on_log = on_log
        self.on_upload = on_upload


class LanSyncServer:
    def __init__(self, host: str = "0.0.0.0", port: int | None = None) -> None:
        config = load_config()
        self.host = host
        self.port = int(port or config.get("server_port") or 8765)
        self._httpd: SyncHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self.last_status = "Detenido"

    @property
    def running(self) -> bool:
        return self._httpd is not None and self._thread is not None and self._thread.is_alive()

    def start(
        self,
        on_log: Callable[[str], None] | None = None,
        on_upload: Callable[[str], None] | None = None,
    ) -> None:
        if self.running:
            return
        self._httpd = SyncHTTPServer((self.host, self.port), on_log=on_log, on_upload=on_upload)
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()
        self.last_status = f"Escuchando en puerto {self.port}"

    def stop(self) -> None:
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
        self._httpd = None
        self._thread = None
        self.last_status = "Detenido"
