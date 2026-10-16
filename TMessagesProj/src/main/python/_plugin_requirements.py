"""Runtime installer for pure-Python dependencies declared by plugins.

exteraGram plugins use ``__requirements__`` metadata.  Chaquopy's normal pip
integration only runs while the APK is built, so runtime dependencies are
resolved from PyPI here and installed from universal (``none-any``) wheels.
"""

from __future__ import annotations

import ast
import email
import hashlib
import importlib.metadata
import io
import json
import os
import shutil
import stat
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

import requests
from packaging.requirements import InvalidRequirement, Requirement
from packaging.specifiers import SpecifierSet
from packaging.tags import sys_tags
from packaging.utils import canonicalize_name, parse_wheel_filename
from packaging.version import InvalidVersion, Version


_PYPI_JSON = "https://pypi.org/pypi/{name}/json"
_MANIFEST_NAME = "active.json"
_MAX_WHEEL_BYTES = 50 * 1024 * 1024
_MAX_UNPACKED_BYTES = 100 * 1024 * 1024
_MAX_WHEEL_FILES = 10_000
_SUPPORTED_PURE_TAGS = {
    tag for tag in sys_tags() if tag.abi == "none" and tag.platform == "any"
}

_root: Path | None = None
_active: dict[str, dict[str, str]] = {}


class RequirementInstallError(RuntimeError):
    """A plugin dependency could not be resolved or installed safely."""


def configure(root):
    """Configure the writable library directory and activate cached wheels."""
    global _root, _active
    _root = Path(str(root))
    _root.mkdir(parents=True, exist_ok=True)
    _active = _read_manifest()
    dirty = False
    for name, record in list(_active.items()):
        if not isinstance(record, dict):
            _active.pop(name, None)
            dirty = True
            continue
        path = _root / record.get("path", "")
        if not path.is_dir():
            _active.pop(name, None)
            dirty = True
            continue
        _activate(path)
    if dirty:
        _write_manifest()


def requirements_from_source(path):
    """Read a literal ``__requirements__`` value without executing the plugin."""
    try:
        source = Path(str(path)).read_text(encoding="utf-8-sig")
        tree = ast.parse(source, filename=str(path))
    except (OSError, SyntaxError) as exc:
        raise RequirementInstallError(f"cannot read plugin requirements: {exc}") from exc

    value = None
    for node in tree.body:
        if not isinstance(node, (ast.Assign, ast.AnnAssign)):
            continue
        targets = node.targets if isinstance(node, ast.Assign) else [node.target]
        if any(isinstance(target, ast.Name) and target.id == "__requirements__"
               for target in targets):
            try:
                value = ast.literal_eval(node.value)
            except (TypeError, ValueError) as exc:
                raise RequirementInstallError("__requirements__ must be a literal string or list") from exc

    if value is None:
        return []
    if isinstance(value, str):
        value = value.split(",")  # "humanize, tinydb, cachetools" is a common spelling
    if not isinstance(value, (list, tuple)) or not all(isinstance(item, str) for item in value):
        raise RequirementInstallError("__requirements__ must be a string or a list of strings")
    return [item.strip() for item in value if item.strip()]


def ensure_requirements(path):
    """Install and activate every dependency declared by a plugin file."""
    if _root is None:
        raise RequirementInstallError("plugin dependency directory is not configured")
    visiting = set()
    for text in requirements_from_source(path):
        try:
            requirement = Requirement(text)
        except InvalidRequirement as exc:
            raise RequirementInstallError(f"invalid plugin requirement {text!r}: {exc}") from exc
        _ensure(requirement, visiting)


def ensure_requirement_strings(texts):
    """Install PEP 508 requirement strings (Elyx metainfo ``requirements``)."""
    if _root is None:
        raise RequirementInstallError("plugin dependency directory is not configured")
    visiting = set()
    for text in texts:
        try:
            requirement = Requirement(str(text).strip())
        except InvalidRequirement as exc:
            raise RequirementInstallError(f"invalid plugin requirement {text!r}: {exc}") from exc
        _ensure(requirement, visiting)


def activate_wheels(plugin_id, wheels_dir):
    """Extract an Elyx plugin's bundled pure-Python wheels and put them on sys.path."""
    if _root is None or not os.path.isdir(str(wheels_dir)):
        return
    target_root = _root.parent / "plugins" / "elyx_local_libs" / str(plugin_id)
    target_root.mkdir(parents=True, exist_ok=True)
    wanted = set()
    for name in sorted(os.listdir(str(wheels_dir))):
        if not name.endswith(".whl"):
            continue
        stem = name[:-4]
        wanted.add(stem)
        destination = target_root / stem
        if not destination.is_dir():
            content = Path(str(wheels_dir), name).read_bytes()
            if len(content) > _MAX_WHEEL_BYTES:
                raise RequirementInstallError(f"bundled wheel is too large: {name}")
            staging = Path(tempfile.mkdtemp(prefix=".install-", dir=str(target_root)))
            try:
                _extract_wheel(content, staging)
                os.replace(str(staging), str(destination))
            except Exception:
                shutil.rmtree(staging, ignore_errors=True)
                raise
        _activate(destination)
    for child in target_root.iterdir():
        if child.is_dir() and child.name not in wanted and not child.name.startswith(".install-"):
            shutil.rmtree(child, ignore_errors=True)  # wheels dropped by a plugin update


def _ensure(requirement, visiting):
    if requirement.url:
        raise RequirementInstallError(
            f"direct-URL requirement is not allowed: {requirement}"
        )
    if requirement.marker is not None and not requirement.marker.evaluate():
        return

    name = canonicalize_name(requirement.name)
    active = _active.get(name)
    if active is not None:
        try:
            if requirement.specifier.contains(active["version"], prereleases=True):
                _activate(_root / active["path"])
                return
        except (InvalidVersion, KeyError):
            pass
        raise RequirementInstallError(
            f"dependency conflict: {requirement} but {requirement.name} "
            f"{active.get('version', '?')} is already active"
        )

    try:
        bundled_version = importlib.metadata.version(requirement.name)
    except importlib.metadata.PackageNotFoundError:
        bundled_version = None
    if bundled_version is not None:
        if requirement.specifier.contains(bundled_version, prereleases=True):
            return
        raise RequirementInstallError(
            f"dependency conflict: {requirement} but APK contains "
            f"{requirement.name} {bundled_version}"
        )

    if name in visiting:
        return
    visiting.add(name)
    try:
        release = _select_release(requirement)
        wheel_bytes = _download_wheel(release)
        dependencies = _wheel_dependencies(wheel_bytes, requirement.extras)
        for dependency in dependencies:
            _ensure(dependency, visiting)
        _install_wheel(name, release["version"], release["filename"], wheel_bytes)
    finally:
        visiting.remove(name)


def _select_release(requirement):
    try:
        data = _fetch_json(_PYPI_JSON.format(name=requirement.name))
    except Exception as exc:
        raise RequirementInstallError(f"cannot query PyPI for {requirement.name}: {exc}") from exc

    candidates = []
    for version_text, files in data.get("releases", {}).items():
        try:
            version = Version(version_text)
        except InvalidVersion:
            continue
        if not requirement.specifier.contains(version, prereleases=None):
            continue
        for item in files or []:
            filename = item.get("filename", "")
            if item.get("packagetype") != "bdist_wheel" or item.get("yanked"):
                continue
            if not _is_universal_wheel(filename):
                continue
            requires_python = item.get("requires_python")
            if requires_python:
                try:
                    py_version = Version(".".join(map(str, sys.version_info[:3])))
                    if not SpecifierSet(requires_python).contains(py_version, prereleases=True):
                        continue
                except Exception:
                    continue
            candidates.append((version, filename, item))

    if not candidates:
        raise RequirementInstallError(
            f"no universal pure-Python wheel found for {requirement}"
        )
    version, filename, item = max(candidates, key=lambda row: (row[0], row[1]))
    return {
        "version": str(version),
        "filename": filename,
        "url": item.get("url"),
        "sha256": (item.get("digests") or {}).get("sha256"),
    }


def _is_universal_wheel(filename):
    try:
        _name, _version, _build, tags = parse_wheel_filename(filename)
    except Exception:
        return False
    return bool(tags) and any(tag in _SUPPORTED_PURE_TAGS for tag in tags)


def _download_wheel(release):
    if not release.get("url") or not release.get("sha256"):
        raise RequirementInstallError(f"PyPI returned incomplete metadata for {release['filename']}")
    try:
        content = _fetch_bytes(release["url"])
    except Exception as exc:
        raise RequirementInstallError(f"cannot download {release['filename']}: {exc}") from exc
    if len(content) > _MAX_WHEEL_BYTES:
        raise RequirementInstallError(f"wheel is too large: {release['filename']}")
    digest = hashlib.sha256(content).hexdigest()
    if digest.lower() != release["sha256"].lower():
        raise RequirementInstallError(f"SHA-256 mismatch for {release['filename']}")
    return content


def _wheel_dependencies(content, selected_extras=()):
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as wheel:
            metadata_files = [name for name in wheel.namelist()
                              if name.endswith(".dist-info/METADATA")]
            if len(metadata_files) != 1:
                raise RequirementInstallError("wheel must contain exactly one METADATA file")
            message = email.message_from_bytes(wheel.read(metadata_files[0]))
    except (OSError, zipfile.BadZipFile, KeyError) as exc:
        raise RequirementInstallError(f"invalid wheel metadata: {exc}") from exc

    dependencies = []
    for text in message.get_all("Requires-Dist", []):
        try:
            dependency = Requirement(text)
        except InvalidRequirement as exc:
            raise RequirementInstallError(f"invalid wheel dependency {text!r}: {exc}") from exc
        marker_matches = dependency.marker is None
        if dependency.marker is not None:
            extras = selected_extras or ("",)
            marker_matches = any(dependency.marker.evaluate({"extra": extra}) for extra in extras)
        if marker_matches:
            dependencies.append(dependency)
    return dependencies


def _install_wheel(name, version, filename, content):
    directory_name = f"{name}-{version}"
    destination = _root / directory_name
    if not destination.is_dir():
        staging = Path(tempfile.mkdtemp(prefix=".install-", dir=str(_root)))
        try:
            _extract_wheel(content, staging)
            os.replace(str(staging), str(destination))
        except Exception:
            shutil.rmtree(staging, ignore_errors=True)
            raise

    _active[name] = {"version": version, "path": directory_name, "wheel": filename}
    _write_manifest()
    _activate(destination)


def _extract_wheel(content, destination):
    try:
        wheel = zipfile.ZipFile(io.BytesIO(content))
    except zipfile.BadZipFile as exc:
        raise RequirementInstallError(f"invalid wheel archive: {exc}") from exc
    with wheel:
        infos = wheel.infolist()
        if len(infos) > _MAX_WHEEL_FILES:
            raise RequirementInstallError("wheel contains too many files")
        if sum(info.file_size for info in infos) > _MAX_UNPACKED_BYTES:
            raise RequirementInstallError("wheel expands beyond the size limit")
        for info in infos:
            relative = _wheel_member_path(info.filename)
            if relative is None:
                continue
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise RequirementInstallError("wheel contains a symbolic link")
            target = destination.joinpath(*relative.parts)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with wheel.open(info) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, length=64 * 1024)


def _wheel_member_path(raw_name):
    name = raw_name.replace("\\", "/")
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise RequirementInstallError(f"unsafe path in wheel: {raw_name}")
    parts = path.parts
    if not parts:
        return None
    if parts[0].endswith(".data"):
        if len(parts) >= 3 and parts[1] == "purelib":
            parts = parts[2:]
        else:
            return None
    return PurePosixPath(*parts) if parts else None


def _activate(path):
    text = str(path)
    while text in sys.path:
        sys.path.remove(text)
    sys.path.insert(0, text)
    importlib.invalidate_caches()


def _read_manifest():
    try:
        value = json.loads((_root / _MANIFEST_NAME).read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return {}
    return value if isinstance(value, dict) else {}


def _write_manifest():
    temporary = _root / (_MANIFEST_NAME + ".tmp")
    temporary.write_text(json.dumps(_active, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    os.replace(str(temporary), str(_root / _MANIFEST_NAME))


def _fetch_json(url):
    response = requests.get(url, timeout=(15, 60))
    response.raise_for_status()
    return response.json()


def _fetch_bytes(url):
    with requests.get(url, timeout=(15, 60), stream=True) as response:
        response.raise_for_status()
        try:
            declared_size = int(response.headers.get("Content-Length", "0"))
        except (TypeError, ValueError):
            declared_size = 0
        if declared_size > _MAX_WHEEL_BYTES:
            raise RequirementInstallError("download exceeds the wheel size limit")
        content = bytearray()
        for chunk in response.iter_content(chunk_size=64 * 1024):
            if not chunk:
                continue
            content.extend(chunk)
            if len(content) > _MAX_WHEEL_BYTES:
                raise RequirementInstallError("download exceeds the wheel size limit")
        return bytes(content)
