"""
_elyx — host side of Elyx structured plugins (exteraGram).

An installed Elyx plugin is a directory (plugins/ElyxPlugins/<id>/) described by a refmap.
Its Python files load as the package ``ElyxPlugins.<id>``, so identically named files of two
plugins never share sys.modules entries. Plain imports inside plugin code (``from helpers import
x``) resolve to the plugin's own files first through a per-plugin ``__import__``; relative
imports work as usual. The plugin-bound environment (settings, metainfo, refmap, assets,
strings) is what ``import elyx`` exposes.
"""

import builtins
import importlib.abc
import importlib.machinery
import importlib.util
import json
import os
import sys
import threading
import traceback
import types

ROOT_PACKAGE = "ElyxPlugins"
_RESERVED = {"android", "androidx", "base_plugin", "client_utils", "com", "de", "elyx", "hook_utils",
             "importlib", "java", "org", "ui", "kotlin", "dalvik", "javax", "android_utils",
             "file_utils", "log_utils", "markdown_utils", "extera_utils", "intents"}
_DATA_EXTENSIONS = (".json", ".yaml", ".yml", ".txt")

_ENVIRONMENTS = {}   # plugin id -> environment dict
_PLUGINS = {}        # plugin id -> _ElyxPlugin
_lock = threading.RLock()


class _ElyxPlugin:
    def __init__(self, plugin_id, root, main_path):
        self.id = plugin_id
        self.root = root
        self.main_path = main_path
        self.package = f"{ROOT_PACKAGE}.{plugin_id}"
        self.builtins = None


# ---------------------------------------------------------------------- refmap / metadata

def _read_structured(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        text = f.read()
    lower = path.lower()
    if lower.endswith(".json"):
        return json.loads(text) if text.strip() else {}
    if lower.endswith(".py"):
        namespace = {}
        exec(compile(text, path, "exec"), namespace)
        return {k: v for k, v in namespace.items()
                if not k.startswith("_") or (k.startswith("__") and k.endswith("__") and k not in ("__builtins__",))}
    import yaml
    value = yaml.safe_load(text)
    return value if isinstance(value, dict) else {}


def _inside(root, relative):
    path = os.path.normpath(os.path.join(root, str(relative).replace("\\", "/").lstrip("/")))
    if os.path.commonpath([root, path]) != os.path.normpath(root):
        raise RuntimeError(f"refmap path escapes the plugin directory: {relative}")
    return path


def read_refmap(root):
    for name in ("refmap.yaml", "refmap.yml", "refmap.json"):
        path = os.path.join(root, name)
        if os.path.isfile(path):
            value = _read_structured(path)
            return value if isinstance(value, dict) else {}
    return {}


def _normalize_meta(meta):
    out = {}
    for key, value in (meta or {}).items():
        key = str(key)
        if key.startswith("__") and key.endswith("__") and len(key) > 4:
            key = key[2:-2]
        out[key] = value
    return out


def read_metainfo(root, refmap):
    path = refmap.get("metainfo")
    if path:
        path = _inside(root, path)
    else:
        path = next((os.path.join(root, n) for n in ("metainfo.yaml", "metainfo.yml", "metainfo.json")
                     if os.path.isfile(os.path.join(root, n))), None)
    if not path or not os.path.isfile(path):
        raise RuntimeError("Elyx plugin has no metainfo file")
    meta = _normalize_meta(_read_structured(path))
    meta.setdefault("description", "Description not provided")
    meta.setdefault("author", "Unknown author")
    meta.setdefault("version", "1.0.0")
    return meta


# ---------------------------------------------------------------------- strings

def _system_language():
    try:
        from org.telegram.messenger import LocaleController
        locale = LocaleController.getInstance().getCurrentLocale()
        if locale is not None:
            return str(locale.getLanguage())
    except Exception:
        pass
    return "en"


def load_strings(root, refmap):
    target = refmap.get("strings")
    if target:
        target = _inside(root, target)
    else:
        target = os.path.join(root, "strings")
    files = []
    if os.path.isdir(target):
        files = [os.path.join(target, n) for n in sorted(os.listdir(target))]
    elif os.path.isfile(target):
        files = [target]
    catalog = {}
    for path in files:
        name = os.path.basename(path)
        stem, ext = os.path.splitext(name)
        if ext.lower() not in (".yaml", ".yml", ".json", ".py"):
            continue
        locale = stem.rsplit("_", 1)[1] if "_" in stem else "en"
        try:
            values = _read_structured(path)
        except Exception:
            traceback.print_exc()
            continue
        if ext.lower() == ".py":
            values = {k: v for k, v in values.items() if not k.startswith("_") and not callable(v)}
        catalog.setdefault(locale.lower(), {}).update(values or {})
    return catalog


# ---------------------------------------------------------------------- imports

def _module_for_file(plugin, path, fullname, is_package=False):
    lower = path.lower()
    if lower.endswith(".pyc"):
        loader = _SourcelessLoader(fullname, path, plugin)
    elif lower.endswith(_DATA_EXTENSIONS):
        loader = _DataLoader(fullname, path)
    else:
        loader = _SourceLoader(fullname, path, plugin)
    spec = importlib.util.spec_from_file_location(
        fullname, path, loader=loader,
        submodule_search_locations=[os.path.dirname(path)] if is_package else None)
    return spec


class _SourceLoader(importlib.machinery.SourceFileLoader):
    def __init__(self, fullname, path, plugin):
        super().__init__(fullname, path)
        self.plugin = plugin

    def exec_module(self, module):
        module.__builtins__ = self.plugin.builtins
        module.__elyx_id__ = self.plugin.id
        super().exec_module(module)


class _SourcelessLoader(importlib.machinery.SourcelessFileLoader):
    def __init__(self, fullname, path, plugin):
        super().__init__(fullname, path)
        self.plugin = plugin

    def exec_module(self, module):
        module.__builtins__ = self.plugin.builtins
        module.__elyx_id__ = self.plugin.id
        super().exec_module(module)


def _wrap(value):
    from elyx import LazyDict
    return LazyDict(value) if isinstance(value, dict) else value


class _DataModule(types.ModuleType):
    """``import config`` for config.json/.yaml: attribute and item access to the data."""

    def __getitem__(self, key):
        return _wrap(self.__dict__["data"][key])

    def get(self, key, default=None):
        data = self.__dict__.get("data")
        return _wrap(data.get(key, default)) if isinstance(data, dict) else default

    def __getattr__(self, name):
        data = self.__dict__.get("data")
        if isinstance(data, dict) and name in data:
            return _wrap(data[name])
        raise AttributeError(name)


class _DataLoader(importlib.abc.Loader):
    def __init__(self, fullname, path):
        self.fullname = fullname
        self.path = path

    def create_module(self, spec):
        return _DataModule(spec.name)

    def exec_module(self, module):
        lower = self.path.lower()
        with open(self.path, "r", encoding="utf-8-sig") as f:
            text = f.read()
        module.__file__ = self.path
        if lower.endswith(".txt"):
            module.data = text
            module.content = text
        elif lower.endswith(".json"):
            module.data = json.loads(text) if text.strip() else {}
        else:
            import yaml
            module.data = yaml.safe_load(text)


class _ElyxFinder(importlib.abc.MetaPathFinder):
    """Resolves ElyxPlugins.<id>.<module> names to files of that plugin."""

    def find_spec(self, fullname, path=None, target=None):
        if fullname == ROOT_PACKAGE:
            spec = importlib.machinery.ModuleSpec(fullname, None, is_package=True)
            spec.submodule_search_locations = []
            return spec
        if not fullname.startswith(ROOT_PACKAGE + "."):
            return None
        parts = fullname.split(".")
        plugin = _PLUGINS.get(parts[1])
        if plugin is None:
            return None
        if len(parts) == 2:
            spec = importlib.machinery.ModuleSpec(fullname, None, is_package=True)
            spec.submodule_search_locations = [plugin.root]
            return spec
        base = os.path.join(plugin.root, *parts[2:])
        for init in ("__init__.py", "__init__.pyc"):
            candidate = os.path.join(base, init)
            if os.path.isfile(candidate):
                return _module_for_file(plugin, candidate, fullname, is_package=True)
        for ext in (".py", ".pyc") + _DATA_EXTENSIONS:
            if os.path.isfile(base + ext):
                return _module_for_file(plugin, base + ext, fullname)
        if os.path.isdir(base):
            spec = importlib.machinery.ModuleSpec(fullname, None, is_package=True)
            spec.submodule_search_locations = [base]
            return spec
        return None


def _install_finder():
    if not any(isinstance(f, _ElyxFinder) for f in sys.meta_path):
        sys.meta_path.insert(0, _ElyxFinder())


def _local_module_name(plugin, name, globals_):
    """ElyxPlugins.<id>.<...> for a plain import that names one of the plugin's own files."""
    top = name.split(".", 1)[0]
    if top in _RESERVED:
        return None
    importer = (globals_ or {}).get("__name__", "")
    search = []
    if importer.startswith(plugin.package + "."):
        package = (globals_ or {}).get("__package__") or importer.rsplit(".", 1)[0]
        if package.startswith(plugin.package):
            search.append(package)
    main_package = plugin.main_package
    if main_package not in search:
        search.append(main_package)
    if plugin.package not in search:
        search.append(plugin.package)
    for package in search:
        relative = package[len(plugin.package):].lstrip(".")
        directory = os.path.join(plugin.root, *relative.split(".")) if relative else plugin.root
        base = os.path.join(directory, top)
        if os.path.isdir(base) or any(os.path.isfile(base + ext) for ext in (".py", ".pyc") + _DATA_EXTENSIONS):
            return f"{package}.{name}"
    return None


def _make_builtins(plugin):
    real_import = builtins.__import__
    namespace = dict(builtins.__dict__)

    def plugin_import(name, globals=None, locals=None, fromlist=(), level=0):
        if level == 0 and name:
            local = _local_module_name(plugin, name, globals)
            if local is not None:
                module = importlib.import_module(local)
                if fromlist:
                    return module
                # "import a.b" binds the top-level local package "a"
                top_local = local[: len(local) - len(name)] + name.split(".", 1)[0]
                return sys.modules[top_local]
        return real_import(name, globals, locals, fromlist, level)

    namespace["__import__"] = plugin_import
    return namespace


def import_local(plugin_id, name):
    plugin = _PLUGINS.get(plugin_id)
    if plugin is not None:
        local = _local_module_name(plugin, name, None)
        if local is not None:
            return importlib.import_module(local)
    return importlib.import_module(name)


# ---------------------------------------------------------------------- dependencies

def _ensure_requirements(plugin_id, meta, root, refmap):
    from _plugin_requirements import ensure_requirement_strings, activate_wheels
    wheels = refmap.get("wheels")
    if wheels:
        activate_wheels(plugin_id, _inside(root, wheels))
    requirements = meta.get("requirements")
    if isinstance(requirements, str):
        requirements = [part.strip() for part in requirements.split(",")]
    if requirements:
        ensure_requirement_strings([r for r in requirements if r])


def _check_requires(meta):
    requires = meta.get("requires") or {}
    if not isinstance(requires, dict) or not requires:
        return
    import re
    from org.telegram.plugins import PluginsController
    host = PluginsController.getInstance()
    missing = []
    for key, link in requires.items():
        match = re.match(r"^\s*([A-Za-z0-9_]+)\s*(?:\(([^)]*)\))?\s*$", str(key))
        if not match:
            continue
        required_id, min_version = match.group(1), (match.group(2) or "").strip()
        info = host.findById(required_id)
        problem = None
        if info is None:
            problem = "not installed"
        elif not info.enabled:
            problem = "disabled"
        elif min_version and PluginsController.compareVersions(info.version or "0", min_version) < 0:
            problem = f"{info.version} < {min_version}"
        if problem:
            missing.append(f"{required_id} ({problem}){' — ' + str(link) if link else ''}")
    if missing:
        raise RuntimeError("required plugins missing: " + "; ".join(missing))


# ---------------------------------------------------------------------- loading

def load(root, plugin_id):
    """Load an extracted Elyx plugin; returns the entry module's globals."""
    _install_finder()
    root = os.path.realpath(str(root))
    refmap = read_refmap(root)
    meta = read_metainfo(root, refmap)
    strings_catalog = load_strings(root, refmap)
    if isinstance(meta.get("description"), str) and "{" in meta["description"]:
        meta["description"] = _resolve_placeholders(meta["description"], strings_catalog)

    main = refmap.get("main") or "main.py"
    main_path = _inside(root, main)
    if not os.path.isfile(main_path):
        raise RuntimeError(f"Elyx entry module not found: {main}")

    with _lock:
        unload(plugin_id)
        plugin = _ElyxPlugin(plugin_id, root, main_path)
        plugin.builtins = _make_builtins(plugin)
        relative = os.path.relpath(main_path, root)
        module_parts = os.path.splitext(relative)[0].replace(os.sep, "/").split("/")
        plugin.main_module = ".".join([plugin.package] + module_parts)
        plugin.main_package = plugin.main_module.rsplit(".", 1)[0]
        _PLUGINS[plugin_id] = plugin

    _ensure_requirements(plugin_id, meta, root, refmap)
    _check_requires(meta)

    from elyx import Assets, SettingsController, Strings
    environment = {"settings": SettingsController(plugin_id), "metainfo": meta, "refmap": refmap,
                   "plugin_id": plugin_id, "root": root}
    assets_dir = _inside(root, refmap["assets"]) if refmap.get("assets") else os.path.join(root, "assets")
    if os.path.isdir(assets_dir) and os.listdir(assets_dir):
        environment["assets"] = Assets(assets_dir)
    if any(strings_catalog.values()):
        environment["strings"] = Strings(strings_catalog)
    _ENVIRONMENTS[plugin_id] = environment

    module = importlib.import_module(plugin.main_module)
    return module.__dict__


def _resolve_placeholders(text, catalog):
    import re
    language = _system_language().lower()

    def replace(match):
        key = match.group(1)
        for locale in (language, "en"):
            value = (catalog.get(locale) or {}).get(key)
            if value is not None:
                return str(value)
        return key
    return re.sub(r"\{([A-Za-z_][A-Za-z0-9_]*)\}", replace, text)


def unload(plugin_id):
    with _lock:
        plugin = _PLUGINS.pop(plugin_id, None)
        _ENVIRONMENTS.pop(plugin_id, None)
        if plugin is None:
            return
        prefix = plugin.package
        for name in list(sys.modules):
            if name == prefix or name.startswith(prefix + "."):
                sys.modules.pop(name, None)
        importlib.invalidate_caches()


def plugin_id_for_frame(frame):
    while frame is not None:
        g = frame.f_globals
        plugin_id = g.get("__elyx_id__")
        if plugin_id is None:
            name = g.get("__name__", "")
            if isinstance(name, str) and name.startswith(ROOT_PACKAGE + "."):
                plugin_id = name.split(".")[1]
        if plugin_id is not None and plugin_id in _ENVIRONMENTS:
            return plugin_id
        frame = frame.f_back
    return None


def environment_for_frame(frame):
    plugin_id = plugin_id_for_frame(frame)
    return None if plugin_id is None else _ENVIRONMENTS.get(plugin_id)
