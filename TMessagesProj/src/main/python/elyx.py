"""
elyx — the public API of Elyx structured plugins (exteraGram SDK).

    from elyx import settings, metainfo, strings, assets   # bound to the calling plugin
    from elyx import Asset, Strings, SettingsController, OnClickListener, gen, import_module

``settings``/``metainfo``/``refmap``/``assets``/``strings`` resolve to the plugin whose code asks
for them; ``from elyx import assets`` fails when the plugin declares no assets (and likewise for
strings), exactly like exteraGram.
"""

import json
import os
import sys
import tempfile
import traceback

__all__ = (
    "Asset", "AssetNotFoundException", "Assets", "AssetsDirNotFoundException", "Callback",
    "Callback2", "Callback3", "CallbackReturn", "LazyDict", "OnClickListener", "Runnable",
    "SettingsController", "Strings", "gen", "gen2", "get_environment", "import_module",
    "mvel_execute",
)

_BOUND = ("settings", "metainfo", "refmap", "assets", "strings")


def _log_exception(where):
    try:
        from log_utils import Logger
        Logger("pysdk").error("elyx %s failed\n%s", where, traceback.format_exc())
    except Exception:
        traceback.print_exc()


# ---------------------------------------------------------------------- environment

def get_environment():
    from _elyx import environment_for_frame
    env = environment_for_frame(sys._getframe(1))
    if env is None:
        raise RuntimeError("elyx.get_environment() called outside Elyx plugin code")
    return env


def __getattr__(name):
    if name in _BOUND:
        from _elyx import environment_for_frame
        env = environment_for_frame(sys._getframe(1))
        if env is None:
            raise AttributeError(f"elyx.{name} is only available inside Elyx plugin code")
        if name not in env:
            raise AttributeError(f"this plugin has no elyx.{name}")
        return env[name]
    raise AttributeError(f"module 'elyx' has no attribute {name!r}")


def import_module(name, package=None):
    import importlib
    from _elyx import plugin_id_for_frame, import_local
    if package or name.startswith("."):
        return importlib.import_module(name, package)
    plugin_id = plugin_id_for_frame(sys._getframe(1))
    if plugin_id is None:
        return importlib.import_module(name)
    return import_local(plugin_id, name)


# ---------------------------------------------------------------------- LazyDict

class LazyDict(dict):
    """A dict with attribute access (nested dicts are wrapped too)."""

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return self[name]

    def __getitem__(self, key):
        value = dict.__getitem__(self, key)
        return LazyDict(value) if isinstance(value, dict) and not isinstance(value, LazyDict) else value

    def get(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            return default


# ---------------------------------------------------------------------- settings

class SettingsController:
    """Persistent settings of one plugin (same store as BasePlugin.get_setting/set_setting)."""

    def __init__(self, plugin_id):
        self.plugin_id = str(plugin_id)

    def get_settings(self):
        from plugin_settings import get_all_settings
        return get_all_settings(self.plugin_id)

    def get_setting(self, key, default=None):
        from plugin_settings import get_setting
        return get_setting(self.plugin_id, key, default)

    get = get_setting

    def set_setting(self, key, value, reload_settings=False):
        from plugin_settings import set_setting
        set_setting(self.plugin_id, key, value, reload_settings)

    set = set_setting

    def clear_settings(self):
        from plugin_settings import clear_settings
        clear_settings(self.plugin_id)

    def __getitem__(self, key):
        return self.get_setting(key)

    def __setitem__(self, key, value):
        self.set_setting(key, value)

    def __call__(self, key, default=None):
        return self.get_setting(key, default)


# ---------------------------------------------------------------------- strings

class Strings:
    """Locale-aware lookup: selected locale, then English, then the key itself."""

    def __init__(self, all_strings):
        self._all = {str(k).lower(): dict(v or {}) for k, v in (all_strings or {}).items()}

    @staticmethod
    def _language():
        from _elyx import _system_language
        return _system_language().lower()

    def _lookup(self, key, locale=None, default=None):
        for candidate in ((locale or self._language()).lower(), "en"):
            values = self._all.get(candidate)
            if values and key in values:
                return values[key]
        return key if default is None else default

    def get(self, key, default=None):
        return self._lookup(key, default=default)

    def get_with_locale(self, key, locale="en", default=None):
        return self._lookup(key, locale=locale, default=default)

    def __getitem__(self, key):
        return self._lookup(key)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return self._lookup(name)

    def __contains__(self, key):
        return any(key in values for values in self._all.values())

    def __call__(self, key, *args, default=None, locale=None, **fmt):
        value = self._lookup(key, locale=locale, default=default)
        if isinstance(value, str) and (args or fmt):
            try:
                return value.format(*args, **fmt)
            except (IndexError, KeyError, ValueError):
                return value
        return value

    def pluralize(self, count, key, locale=None):
        forms = self._lookup(key, locale=locale)
        if not isinstance(forms, (list, tuple)) or not forms:
            return f"{count} {forms}"
        n = abs(int(count))
        if n % 10 == 1 and n % 100 != 11:
            index = 0
        elif 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
            index = 1
        else:
            index = 2
        return f"{count} {forms[min(index, len(forms) - 1)]}"


# ---------------------------------------------------------------------- assets

class AssetNotFoundException(Exception):
    pass


class AssetsDirNotFoundException(Exception):
    pass


def _normalize(name):
    import re
    return re.sub(r"[^0-9a-zA-Z_]", "_", os.path.splitext(name)[0]).lower()


class Asset:
    def __init__(self, dir_path, filename, name=None):
        self.dir_path = str(dir_path)
        self.filename = str(filename)
        self.name = name or os.path.splitext(self.filename)[0]
        self.path = os.path.join(self.dir_path, self.filename)

    def __repr__(self):
        return f"Asset({self.path!r})"

    @classmethod
    def from_path(cls, path):
        path = str(path)
        if not os.path.isfile(path):
            raise AssetNotFoundException(path)
        return cls(os.path.dirname(path), os.path.basename(path))

    @classmethod
    def temp_asset_from_url(cls, url, filename):
        import requests
        directory = tempfile.mkdtemp(prefix="elyx_asset_")
        response = requests.get(str(url), timeout=(15, 60))
        response.raise_for_status()
        with open(os.path.join(directory, filename), "wb") as f:
            f.write(response.content)
        return cls(directory, filename)

    # content
    def content_bytes(self):
        with open(self.path, "rb") as f:
            return f.read()

    def content_string(self):
        with open(self.path, "r", encoding="utf-8-sig") as f:
            return f.read()

    def content_json(self):
        return json.loads(self.content_string())

    def content_yaml(self):
        import yaml
        return yaml.safe_load(self.content_string())

    def content(self):
        lower = self.filename.lower()
        if lower.endswith(".json"):
            return self.content_json()
        if lower.endswith((".yaml", ".yml")):
            return self.content_yaml()
        if lower.endswith((".txt", ".md", ".html", ".css", ".js", ".xml", ".svg", ".csv")):
            return self.content_string()
        return self.content_bytes()

    def java_file(self):
        from java.io import File
        return File(self.path)

    # Android conversions
    def to_image_location(self):
        from org.telegram.messenger import ImageLocation
        return ImageLocation.getForPath(self.path)

    def to_drawable(self):
        lower = self.filename.lower()
        if lower.endswith(".svg"):
            return self.to_svg_drawable()
        if lower.endswith((".json", ".tgs")):
            return self.to_lottie_drawable()
        from android.graphics.drawable import Drawable
        return Drawable.createFromPath(self.path)

    def to_bitmap_drawable(self, width=32, height=32):
        from android.graphics import Bitmap, BitmapFactory
        from android.graphics.drawable import BitmapDrawable
        from org.telegram.messenger import AndroidUtilities, ApplicationLoader
        bitmap = BitmapFactory.decodeFile(self.path)
        if bitmap is None:
            raise AssetNotFoundException(self.path)
        if width and height:
            bitmap = Bitmap.createScaledBitmap(bitmap, AndroidUtilities.dp(width), AndroidUtilities.dp(height), True)
        return BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap)

    def to_lottie_drawable(self, width=32, height=32):
        from org.telegram.ui.Components import RLottieDrawable
        from org.telegram.messenger import AndroidUtilities
        # json=None makes the native side read (and un-gzip .tgs) the file itself
        return RLottieDrawable(self.java_file(), None, AndroidUtilities.dp(width), AndroidUtilities.dp(height),
                               None, False, None, 0, False)

    def to_svg_drawable(self, width=None, height=None):
        from org.telegram.messenger import SvgHelper
        if width and height:
            from android.graphics.drawable import BitmapDrawable
            from org.telegram.messenger import ApplicationLoader
            return BitmapDrawable(ApplicationLoader.applicationContext.getResources(),
                                  self.to_svg_bitmap(width, height))
        return SvgHelper.getDrawable(self.content_string())

    def to_svg_thumb(self, color_key, alpha):
        """SVG drawable tinted with a theme color key, for placeholders."""
        from org.telegram.messenger import SvgHelper
        from org.telegram.ui.ActionBar import Theme
        drawable = SvgHelper.getDrawable(self.content_string())
        if drawable is not None and color_key is not None:
            drawable.setupGradient(int(color_key), float(alpha), False)
        return drawable

    def to_svg_bitmap(self, width=32, height=32, white=False):
        from org.telegram.messenger import AndroidUtilities, SvgHelper
        return SvgHelper.getBitmap(self.java_file(), AndroidUtilities.dp(width), AndroidUtilities.dp(height),
                                   bool(white))


class Assets:
    """A resource directory: assets.icon, assets["icon.svg"], assets.get("sub/x"), "x" in assets."""

    def __init__(self, dir_path):
        self.dir_path = str(dir_path)
        if not os.path.isdir(self.dir_path):
            raise AssetsDirNotFoundException(self.dir_path)

    def __repr__(self):
        return f"Assets({self.dir_path!r})"

    def _entries(self):
        return sorted(os.listdir(self.dir_path))

    def _find(self, key):
        key = str(key).replace("\\", "/").strip("/")
        if "/" in key:
            head, rest = key.split("/", 1)
            sub = self._find(head)
            if not isinstance(sub, Assets):
                raise AssetNotFoundException(key)
            return sub._find(rest)
        names = self._entries()
        if key in names:
            match = key
        else:
            stems = [n for n in names if os.path.splitext(n)[0] == key] or \
                    [n for n in names if _normalize(n) == _normalize(key)]
            if not stems:
                raise AssetNotFoundException(key)
            match = stems[0]
        full = os.path.join(self.dir_path, match)
        if os.path.isdir(full):
            return Assets(full)
        return Asset(self.dir_path, match)

    def get(self, key, default=None):
        try:
            return self._find(key)
        except AssetNotFoundException:
            return default

    def __getitem__(self, key):
        return self._find(key)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        try:
            return self._find(name)
        except AssetNotFoundException:
            raise AttributeError(name)

    def __contains__(self, key):
        return self.get(key) is not None

    def __iter__(self):
        for name in self._entries():
            full = os.path.join(self.dir_path, name)
            yield Assets(full) if os.path.isdir(full) else Asset(self.dir_path, name)

    def __len__(self):
        return len(self._entries())


# ---------------------------------------------------------------------- callback proxies

_GENERATED = {}


def gen(java_class, method_name, return_value=False, default_value=None):
    """A Python proxy class for a one-method Java interface: Cls(fn, *extra_args)."""
    from java import dynamic_proxy
    key = (getattr(java_class, "__name__", str(java_class)), method_name, bool(return_value))
    cached = _GENERATED.get(key)
    if cached is not None:
        return cached

    def handler(self, *args):
        try:
            result = self._fn(*(args + self._extra))
            return result if return_value else None
        except Exception:
            _log_exception(f"{method_name} callback")
            return default_value if return_value else None

    class Proxy(dynamic_proxy(java_class)):
        def __init__(self, fn, *extra):
            super().__init__()
            self._fn = fn
            self._extra = tuple(extra)

        locals()[method_name] = handler

    Proxy.__name__ = f"{method_name}_proxy"
    _GENERATED[key] = Proxy
    return Proxy


def gen2(java_class, return_value=False, **methods):
    """A proxy instance factory for a multi-method interface: gen2(I, onStart=f, onFinish=g)()."""
    from java import dynamic_proxy

    def make(name, fn):
        def handler(self, *args):
            try:
                result = fn(*args)
                return result if return_value else None
            except Exception:
                _log_exception(f"{name} callback")
                return None
        return handler

    handlers = {name: make(name, fn) for name, fn in methods.items()}

    class Gen2Proxy(dynamic_proxy(java_class)):
        locals().update(handlers)

    return Gen2Proxy


def _interface(name):
    from java import jclass
    return jclass(name)


class _LazyProxy:
    """Callback classes are built on first use so importing elyx never touches the JVM."""

    def __init__(self, interface_name, method, returns=False):
        self.interface_name = interface_name
        self.method = method
        self.returns = returns
        self.cls = None

    def __call__(self, fn, *extra):
        if self.cls is None:
            self.cls = gen(_interface(self.interface_name), self.method, self.returns)
        return self.cls(fn, *extra)


OnClickListener = _LazyProxy("android.view.View$OnClickListener", "onClick")
Runnable = _LazyProxy("java.lang.Runnable", "run")
Callback = _LazyProxy("org.telegram.messenger.Utilities$Callback", "run")
Callback2 = _LazyProxy("org.telegram.messenger.Utilities$Callback2", "run")
Callback3 = _LazyProxy("org.telegram.messenger.Utilities$Callback3", "run")
CallbackReturn = _LazyProxy("org.telegram.messenger.Utilities$CallbackReturn", "run", True)


def mvel_execute(script, data, to_type=None, java_instance=None):
    raise NotImplementedError("MVEL is not available in ZaStoGram")
