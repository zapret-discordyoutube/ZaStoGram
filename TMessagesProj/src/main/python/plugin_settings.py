"""
plugin_settings — exteraGram's module-level settings store, keyed by plugin id.

Library plugins (ZwyLib, cactuslib) read and write other plugins' settings through it:
``plugin_settings.get_setting(plugin_id, key, default)``. The values live in the same store as
BasePlugin.get_setting/set_setting, so both views always agree.
"""

import threading

_lock = threading.RLock()


class _SettingsCache(dict):
    """``_settings_cache[plugin_id]`` -> a live dict view of that plugin's stored settings."""

    def __missing__(self, plugin_id):
        return _PluginSettingsView(str(plugin_id))

    def __contains__(self, plugin_id):
        return True


class _PluginSettingsView(dict):
    def __init__(self, plugin_id):
        super().__init__(get_all_settings(plugin_id))
        self.plugin_id = plugin_id

    def __setitem__(self, key, value):
        super().__setitem__(key, value)
        set_setting(self.plugin_id, key, value)

    def pop(self, key, *default):
        value = super().pop(key, *default)
        remove_setting(self.plugin_id, key)
        return value

    def __delitem__(self, key):
        super().__delitem__(key)
        remove_setting(self.plugin_id, key)


_settings_cache = _SettingsCache()


def _context(plugin_id):
    from org.telegram.plugins import PluginContext
    return PluginContext(str(plugin_id))


def _instance(plugin_id):
    try:
        from _plugin_loader import get_instance
        return get_instance(str(plugin_id))
    except Exception:
        return None


def get_setting(plugin_id, key, default=None):
    inst = _instance(plugin_id)
    if inst is not None:
        return inst.get_setting(key, default)
    from base_plugin import BasePlugin
    proxy = BasePlugin.__new__(BasePlugin)
    proxy._context = _context(plugin_id)
    return BasePlugin.get_setting(proxy, key, default)


def set_setting(plugin_id, key, value, reload_settings=False):
    inst = _instance(plugin_id)
    if inst is not None:
        return inst.set_setting(key, value, reload_settings)
    from base_plugin import BasePlugin
    proxy = BasePlugin.__new__(BasePlugin)
    proxy._context = _context(plugin_id)
    return BasePlugin.set_setting(proxy, key, value, reload_settings)


def remove_setting(plugin_id, key):
    try:
        _context(plugin_id).removeSetting(str(key))
    except Exception:
        pass


def get_all_settings(plugin_id):
    from base_plugin import _unbox
    out = {}
    try:
        values = _context(plugin_id).getAllSettings()
        for k in values.keySet():
            out[str(k)] = _unbox(values.get(k))
    except Exception:
        pass
    return out


def clear_settings(plugin_id):
    try:
        _context(plugin_id).clearSettings()
    except Exception:
        pass


def _save_settings_to_file():
    """Settings are persisted on every write; kept for plugins that call it explicitly."""
    return None


def _load_settings_from_file():
    return None
