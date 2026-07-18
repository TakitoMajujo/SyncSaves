"""Ventana principal de SyncSaves (PC Windows)."""

from __future__ import annotations

import socket
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from typing import Callable

from config_manager import load_config, set_saves_folder
from lan_server import LanSyncServer


def _local_ip() -> str:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        return ip
    except OSError:
        return "127.0.0.1"


class SyncSavesApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("SyncSaves")
        self.minsize(560, 420)
        self.configure(bg="#1a1d23")

        self._config = load_config()
        self._server = LanSyncServer(port=self._config.get("server_port"))
        self._build_ui()
        self._refresh_labels()
        self._start_server()
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_ui(self) -> None:
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass

        style.configure("Title.TLabel", background="#1a1d23", foreground="#e8eaed", font=("Segoe UI", 12))
        style.configure("Code.TLabel", background="#1a1d23", foreground="#7dd3fc", font=("Consolas", 36, "bold"))
        style.configure("Path.TLabel", background="#1a1d23", foreground="#9aa0a6", font=("Segoe UI", 10))
        style.configure("Hint.TLabel", background="#1a1d23", foreground="#6b7280", font=("Segoe UI", 9))
        style.configure("Action.TButton", font=("Segoe UI", 10), padding=(14, 8))
        style.configure("Root.TFrame", background="#1a1d23")

        root = ttk.Frame(self, padding=28, style="Root.TFrame")
        root.pack(fill=tk.BOTH, expand=True)

        ttk.Label(root, text="Código de este dispositivo", style="Title.TLabel").pack(anchor=tk.W)

        self.code_var = tk.StringVar()
        ttk.Label(root, textvariable=self.code_var, style="Code.TLabel").pack(anchor=tk.W, pady=(8, 4))

        ttk.Label(
            root,
            text="En Android escribe este código para autorizar el envío de saves.",
            style="Hint.TLabel",
            wraplength=500,
        ).pack(anchor=tk.W, pady=(0, 20))

        ttk.Label(root, text="Carpeta de guardados", style="Title.TLabel").pack(anchor=tk.W)

        self.folder_var = tk.StringVar()
        ttk.Label(root, textvariable=self.folder_var, style="Path.TLabel", wraplength=500).pack(
            anchor=tk.W, pady=(8, 12)
        )

        ttk.Label(root, text="Red local", style="Title.TLabel").pack(anchor=tk.W)
        self.network_var = tk.StringVar()
        ttk.Label(root, textvariable=self.network_var, style="Path.TLabel", wraplength=500).pack(
            anchor=tk.W, pady=(8, 8)
        )
        self.status_var = tk.StringVar()
        ttk.Label(root, textvariable=self.status_var, style="Hint.TLabel", wraplength=500).pack(
            anchor=tk.W, pady=(0, 16)
        )

        actions = ttk.Frame(root, style="Root.TFrame")
        actions.pack(anchor=tk.W)

        ttk.Button(
            actions,
            text="Configurar carpeta…",
            style="Action.TButton",
            command=self._choose_folder,
        ).pack(side=tk.LEFT)

        ttk.Button(
            actions,
            text="Copiar código",
            style="Action.TButton",
            command=self._copy_code,
        ).pack(side=tk.LEFT, padx=(10, 0))

    def _refresh_labels(self) -> None:
        self.code_var.set(self._config.get("device_id", ""))
        folder = self._config.get("saves_folder") or "(sin configurar)"
        self.folder_var.set(folder)
        port = self._config.get("server_port", 8765)
        self.network_var.set(f"IP: {_local_ip()}   Puerto: {port}")
        self.status_var.set(self._server.last_status)

    def _start_server(self) -> None:
        try:
            self._server.start(
                on_log=lambda msg: self.after(0, lambda m=msg: self.status_var.set(m)),
                on_upload=lambda path: self.after(
                    0, lambda p=path: self.status_var.set(f"Recibido: {p}")
                ),
            )
            self.status_var.set(self._server.last_status)
        except OSError as exc:
            self.status_var.set(f"No se pudo iniciar el servidor: {exc}")

    def _choose_folder(self) -> None:
        initial = self._config.get("saves_folder") or None
        selected = filedialog.askdirectory(
            title="Selecciona la carpeta de archivos de guardado",
            initialdir=initial if initial else None,
            mustexist=True,
        )
        if not selected:
            return
        self._config = set_saves_folder(selected)
        self._refresh_labels()

    def _copy_code(self) -> None:
        device_id = self._config.get("device_id", "")
        if not device_id:
            return
        self.clipboard_clear()
        self.clipboard_append(device_id)
        messagebox.showinfo("SyncSaves", "Código copiado al portapapeles.")

    def _on_close(self) -> None:
        self._server.stop()
        self.destroy()


def run_app(on_ready: Callable[[], None] | None = None) -> None:
    app = SyncSavesApp()
    if on_ready:
        on_ready()
    app.mainloop()
