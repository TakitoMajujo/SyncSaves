"""Sincroniza saves entre PC y Recalbox emparejando ROMs por nombre."""

from __future__ import annotations

import filecmp
import shutil
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_ROMS_ROOT = Path(r"\\recalbox\share\roms")
DEFAULT_SAVES_ROOT = Path(r"\\recalbox\share\saves")

# Extensiones de save en PC (Android / emuladores).
LOCAL_EXTENSIONS = (".sav", ".srm")
# Extensiones de save en Recalbox (libretro y variantes comunes).
RECALBOX_EXTENSIONS = (".srm", ".sav")
# Preferida al escribir en Recalbox.
RECALBOX_WRITE_EXT = ".srm"
# Preferida al crear un save nuevo en PC.
LOCAL_WRITE_EXT = ".sav"

_SKIP_ROM_DIRS = {"media", "images", "manuals", "videos", "downloaded_images", "downloaded_videos"}
_SKIP_ROM_EXTS = {
    ".xml",
    ".txt",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".mp4",
    ".pdf",
    ".md5",
    ".sha1",
    ".json",
    ".cfg",
}


@dataclass
class SyncResult:
    to_recalbox: list[str] = field(default_factory=list)
    from_recalbox: list[str] = field(default_factory=list)
    already_synced: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    unmatched: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)

    @property
    def copied(self) -> list[str]:
        return [*self.to_recalbox, *self.from_recalbox]

    @property
    def summary(self) -> str:
        parts = [
            f"PC→Recalbox: {len(self.to_recalbox)}",
            f"Recalbox→PC: {len(self.from_recalbox)}",
            f"Al día: {len(self.already_synced)}",
            f"Sin coincidencia: {len(self.unmatched)}",
        ]
        if self.errors:
            parts.append(f"Errores: {len(self.errors)}")
        return " · ".join(parts)


def _mtime(path: Path) -> float:
    return path.stat().st_mtime


def _files_equal(a: Path, b: Path) -> bool:
    """True si ambos existen y el contenido binario es idéntico."""
    try:
        if not a.is_file() or not b.is_file():
            return False
        if a.stat().st_size != b.stat().st_size:
            return False
        return filecmp.cmp(a, b, shallow=False)
    except OSError:
        return False


def _build_rom_index(roms_root: Path) -> dict[str, str]:
    """Mapa stem.casefold() -> carpeta de consola (todas las consolas)."""
    index: dict[str, str] = {}
    if not roms_root.is_dir():
        raise FileNotFoundError(f"No se encuentra la carpeta de ROMs: {roms_root}")

    for console_dir in roms_root.iterdir():
        if not console_dir.is_dir():
            continue
        console = console_dir.name
        try:
            for rom in console_dir.rglob("*"):
                if not rom.is_file():
                    continue
                if any(part.casefold() in _SKIP_ROM_DIRS for part in rom.relative_to(console_dir).parts[:-1]):
                    continue
                if rom.suffix.casefold() in _SKIP_ROM_EXTS:
                    continue
                stem = rom.stem.strip()
                if not stem:
                    continue
                index.setdefault(stem.casefold(), console)
        except OSError:
            continue
    return index


def _find_local_save(source_dir: Path, stem: str) -> Path | None:
    for ext in LOCAL_EXTENSIONS:
        candidate = source_dir / f"{stem}{ext}"
        if candidate.is_file():
            return candidate
    # Búsqueda case-insensitive por si el stem difiere en mayúsculas.
    wanted = stem.casefold()
    try:
        for entry in source_dir.iterdir():
            if entry.is_file() and entry.suffix.casefold() in LOCAL_EXTENSIONS:
                if entry.stem.strip().casefold() == wanted:
                    return entry
    except OSError:
        pass
    return None


def _find_remote_save(remote_dir: Path, stem: str) -> Path | None:
    for ext in RECALBOX_EXTENSIONS:
        candidate = remote_dir / f"{stem}{ext}"
        if candidate.is_file():
            return candidate
    wanted = stem.casefold()
    try:
        if not remote_dir.is_dir():
            return None
        for entry in remote_dir.iterdir():
            if entry.is_file() and entry.suffix.casefold() in RECALBOX_EXTENSIONS:
                if entry.stem.strip().casefold() == wanted:
                    return entry
    except OSError:
        pass
    return None


def _sync_pair(
    result: SyncResult,
    *,
    stem: str,
    console: str,
    local: Path | None,
    remote: Path | None,
    source_dir: Path,
    remote_dir: Path,
) -> None:
    local_path = local
    remote_path = remote or (remote_dir / f"{stem}{RECALBOX_WRITE_EXT}")

    if local_path is None and remote is None:
        return

    if local_path is None:
        # Solo en Recalbox: copiar a PC.
        local_path = source_dir / f"{stem}{LOCAL_WRITE_EXT}"
        try:
            shutil.copy2(remote_path, local_path)
            result.from_recalbox.append(f"{console}/{remote_path.name} → {local_path.name}")
        except OSError as exc:
            result.errors.append(f"{stem}: {exc}")
        return

    if remote is None:
        # Solo en PC: copiar a Recalbox como .srm.
        try:
            remote_dir.mkdir(parents=True, exist_ok=True)
            dest = remote_dir / f"{stem}{RECALBOX_WRITE_EXT}"
            shutil.copy2(local_path, dest)
            result.to_recalbox.append(f"{local_path.name} → {console}/{dest.name}")
        except OSError as exc:
            result.errors.append(f"{local_path.name}: {exc}")
        return

    # Ambos existen: comparar contenido y fechas.
    try:
        if _files_equal(local_path, remote_path):
            result.already_synced.append(local_path.name)
            return

        local_mtime = _mtime(local_path)
        remote_mtime = _mtime(remote_path)
    except OSError as exc:
        result.errors.append(f"{stem}: {exc}")
        return

    try:
        if local_mtime > remote_mtime:
            remote_dir.mkdir(parents=True, exist_ok=True)
            dest = remote_dir / f"{stem}{RECALBOX_WRITE_EXT}"
            shutil.copy2(local_path, dest)
            result.to_recalbox.append(f"{local_path.name} → {console}/{dest.name}")
        elif remote_mtime > local_mtime:
            shutil.copy2(remote_path, local_path)
            result.from_recalbox.append(f"{console}/{remote_path.name} → {local_path.name}")
        else:
            # Misma fecha, contenido distinto: prioriza PC.
            remote_dir.mkdir(parents=True, exist_ok=True)
            dest = remote_dir / f"{stem}{RECALBOX_WRITE_EXT}"
            shutil.copy2(local_path, dest)
            result.to_recalbox.append(f"{local_path.name} → {console}/{dest.name}")
    except OSError as exc:
        result.errors.append(f"{stem}: {exc}")


def sync_saves_to_recalbox(
    local_saves_folder: str | Path,
    roms_root: Path | str = DEFAULT_ROMS_ROOT,
    saves_root: Path | str = DEFAULT_SAVES_ROOT,
) -> SyncResult:
    """Deja el save más reciente en ambos lados para todas las consolas."""
    result = SyncResult()
    source_dir = Path(local_saves_folder)
    roms = Path(roms_root)
    dest_root = Path(saves_root)

    if not source_dir.is_dir():
        result.errors.append(f"Carpeta local no válida: {source_dir}")
        return result

    try:
        rom_index = _build_rom_index(roms)
    except (OSError, FileNotFoundError) as exc:
        result.errors.append(str(exc))
        return result

    if not rom_index:
        result.errors.append(f"No se encontraron ROMs en: {roms}")
        return result

    # stem.casefold() -> stem original preferido / consola
    jobs: dict[str, tuple[str, str]] = {}

    # 1) Saves locales emparejados con ROMs de cualquier consola.
    try:
        for entry in source_dir.iterdir():
            if not entry.is_file():
                continue
            if entry.suffix.casefold() not in LOCAL_EXTENSIONS:
                result.skipped.append(entry.name)
                continue
            stem = entry.stem.strip()
            key = stem.casefold()
            console = rom_index.get(key)
            if not console:
                result.unmatched.append(entry.name)
                continue
            jobs.setdefault(key, (stem, console))
    except OSError as exc:
        result.errors.append(f"No se pudo leer la carpeta local: {exc}")
        return result

    # 2) Saves en Recalbox (todas las carpetas de consola) que tengan ROM.
    try:
        if dest_root.is_dir():
            for console_dir in dest_root.iterdir():
                if not console_dir.is_dir():
                    continue
                console = console_dir.name
                try:
                    entries = console_dir.iterdir()
                except OSError:
                    continue
                for entry in entries:
                    if not entry.is_file():
                        continue
                    if entry.suffix.casefold() not in RECALBOX_EXTENSIONS:
                        continue
                    stem = entry.stem.strip()
                    key = stem.casefold()
                    rom_console = rom_index.get(key)
                    if not rom_console:
                        continue
                    # Usa la consola de la ROM (fuente de verdad).
                    jobs.setdefault(key, (stem, rom_console))
    except OSError as exc:
        result.errors.append(f"No se pudo leer saves de Recalbox: {exc}")

    for key, (stem, console) in sorted(jobs.items(), key=lambda item: item[1][0].casefold()):
        remote_dir = dest_root / console
        local = _find_local_save(source_dir, stem)
        remote = _find_remote_save(remote_dir, stem)
        _sync_pair(
            result,
            stem=stem,
            console=console,
            local=local,
            remote=remote,
            source_dir=source_dir,
            remote_dir=remote_dir,
        )

    return result
