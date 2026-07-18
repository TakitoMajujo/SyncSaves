"""Ventana principal de SyncSaves (PC Windows)."""

from __future__ import annotations

import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from typing import Callable

from config_manager import load_config, set_saves_folder


class SyncSavesApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("SyncSaves")
        self.minsize(520, 320)
        self.configure(bg="#1a1d23")

        self._config = load_config()
        self._build_ui()
        self._refresh_labels()

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

        root = ttk.Frame(self, padding=28)
        root.pack(fill=tk.BOTH, expand=True)
        root.configure(style="Root.TFrame")
        style.configure("Root.TFrame", background="#1a1d23")

        ttk.Label(root, text="Código de este dispositivo", style="Title.TLabel").pack(anchor=tk.W)

        self.code_var = tk.StringVar()
        ttk.Label(root, textvariable=self.code_var, style="Code.TLabel").pack(anchor=tk.W, pady=(8, 4))

        ttk.Label(
            root,
            text="Otros dispositivos usarán este código de 8 caracteres para identificarte en la red local.",
            style="Hint.TLabel",
            wraplength=460,
        ).pack(anchor=tk.W, pady=(0, 24))

        ttk.Label(root, text="Carpeta de guardados", style="Title.TLabel").pack(anchor=tk.W)

        self.folder_var = tk.StringVar()
        ttk.Label(root, textvariable=self.folder_var, style="Path.TLabel", wraplength=460).pack(
            anchor=tk.W, pady=(8, 16)
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


def run_app(on_ready: Callable[[], None] | None = None) -> None:
    app = SyncSavesApp()
    if on_ready:
        on_ready()
    app.mainloop()
