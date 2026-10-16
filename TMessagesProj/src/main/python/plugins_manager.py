"""
plugins_manager — exteraGram's Python-side plugin registry (PluginsManager).

Plugin managers and helpers enumerate plugins with ``PluginsManager._plugins.values()`` and read
``.id``, ``.name``, ``.enabled``; loaded plugins are their live BasePlugin instances, installed
but disabled ones are lightweight records with the same attributes.
"""

import os


class _PluginRecord:
    def __init__(self, info):
        self.id = info.id
        self.name = info.displayName()
        self.description = info.description
        self.author = info.author
        self.version = info.version
        self.icon = info.icon
        self.min_version = info.minVersion
        self.enabled = bool(info.enabled)
        self.error = info.error
        self.file_path = info.filePath

    def __repr__(self):
        return f"<plugin {self.id} enabled={self.enabled}>"


def _host():
    from org.telegram.plugins import PluginsController
    return PluginsController.getInstance()


def _snapshot():
    from _plugin_loader import get_instance
    plugins = {}
    for info in _host().getPlugins():
        inst = get_instance(info.id)
        if inst is not None:
            try:
                inst.enabled = bool(info.enabled)
                inst.error = info.error
                inst.file_path = info.filePath
            except Exception:
                pass
            plugins[str(info.id)] = inst
        else:
            plugins[str(info.id)] = _PluginRecord(info)
    return plugins


class _ManagerMeta(type):
    @property
    def _plugins(cls):
        return _snapshot()

    @property
    def plugins(cls):
        return _snapshot()

    @property
    def _plugins_dir(cls):
        return str(_host().pluginsDir().getAbsolutePath())

    @property
    def plugins_dir(cls):
        return str(_host().pluginsDir().getAbsolutePath())


class PluginsManager(metaclass=_ManagerMeta):

    @classmethod
    def init(cls, plugins_dir=None):
        _host().reloadPluginsFromDisk()

    @classmethod
    def shutdown(cls):
        return None

    @classmethod
    def get_plugin(cls, plugin_id):
        return _snapshot().get(str(plugin_id))

    @classmethod
    def set_plugin_enabled(cls, plugin_id, enabled):
        if _host().findById(str(plugin_id)) is None:
            return False
        _host().setEnabled(str(plugin_id), bool(enabled))
        return True

    @classmethod
    def delete_plugin(cls, plugin_id):
        if _host().findById(str(plugin_id)) is None:
            return False
        _host().deletePlugin(str(plugin_id))
        return True

    @classmethod
    def load_plugin(cls, path):
        from java.io import File
        info = _host().installFromFile(File(str(path)))
        return info is not None

    @classmethod
    def reload_plugins(cls):
        _host().reloadPluginsFromDisk()

    @classmethod
    def get_plugin_path(cls, plugin_id):
        info = _host().findById(str(plugin_id))
        return None if info is None else info.filePath

    @classmethod
    def plugin_file_exists(cls, plugin_id):
        path = cls.get_plugin_path(plugin_id)
        return bool(path) and os.path.exists(path)
