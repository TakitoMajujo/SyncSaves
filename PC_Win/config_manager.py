"""Persistencia de configuración local del dispositivo."""

from __future__ import annotations

import json
import secrets
import sys
from pathlib import Path
from typing import Any

DEVICE_ID_LENGTH = 8
# Sin 0/O ni 1/I para que sea más fácil de leer y dictar.
_DEVICE_ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def _app_dir() -> Path:
    # Con PyInstaller, la config debe vivir junto al .exe, no en _MEIPASS.
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


APP_DIR = _app_dir()
CONFIG_PATH = APP_DIR / "config.json"
DEFAULT_PORT = 8765


def generate_device_id() -> str:
    return "".join(secrets.choice(_DEVICE_ID_ALPHABET) for _ in range(DEVICE_ID_LENGTH))


def is_valid_device_id(value: object) -> bool:
    if not isinstance(value, str) or len(value) != DEVICE_ID_LENGTH:
        return False
    return all(ch in _DEVICE_ID_ALPHABET for ch in value)


def _default_config() -> dict[str, Any]:
    return {
        "device_id": generate_device_id(),
        "saves_folder": "",
        "server_port": DEFAULT_PORT,
    }


def load_config() -> dict[str, Any]:
    if not CONFIG_PATH.exists():
        config = _default_config()
        save_config(config)
        return config

    with CONFIG_PATH.open("r", encoding="utf-8") as handle:
        data = json.load(handle)

    # Garantiza campos mínimos si el archivo es viejo o incompleto.
    defaults = _default_config()
    changed = False
    for key, value in defaults.items():
        if key not in data:
            data[key] = value
            changed = True

    # Migra UUID antiguos (u otros formatos) a código corto de 8 caracteres.
    if not is_valid_device_id(data.get("device_id")):
        data["device_id"] = generate_device_id()
        changed = True

    if changed:
        save_config(data)
    return data


def save_config(config: dict[str, Any]) -> None:
    with CONFIG_PATH.open("w", encoding="utf-8") as handle:
        json.dump(config, handle, indent=2, ensure_ascii=False)


def set_saves_folder(folder: str) -> dict[str, Any]:
    config = load_config()
    config["saves_folder"] = folder
    save_config(config)
    return config
