"""file_utils — app directories and small file I/O helpers for plugins."""

import os

from org.telegram.messenger import ApplicationLoader


def get_files_dir():
    return ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath()


def get_cache_dir():
    return ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath()


def plugin_data_dir(plugin_id):
    """A private, persistent directory a plugin can use for its own data."""
    path = os.path.join(get_files_dir(), "plugins_data", str(plugin_id))
    os.makedirs(path, exist_ok=True)
    return path


def get_plugins_dir():
    """Directory where installed .plugin files live (matches the engine's pluginsDir())."""
    path = os.path.join(get_files_dir(), "plugins")
    os.makedirs(path, exist_ok=True)
    return path


def read_text(path, default=""):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return default


def write_text(path, text):
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def _log_failure(what, path):
    try:
        from log_utils import Logger
        Logger("pysdk").exception("file_utils.%s(%r) failed", what, path, stacklevel=3)
    except Exception:
        pass


def _media_dir(kind):
    """Telegram's public media directory (FileLoader.MEDIA_DIR_*), created on demand."""
    try:
        from org.telegram.messenger import FileLoader
        directory = FileLoader.getDirectory(getattr(FileLoader, kind))
        if directory is not None:
            path = directory.getAbsolutePath()
            os.makedirs(path, exist_ok=True)
            return path
    except Exception:
        pass
    return get_cache_dir()


def get_images_dir():
    return _media_dir("MEDIA_DIR_IMAGE")


def get_videos_dir():
    return _media_dir("MEDIA_DIR_VIDEO")


def get_audios_dir():
    return _media_dir("MEDIA_DIR_AUDIO")


def get_documents_dir():
    return _media_dir("MEDIA_DIR_DOCUMENT")


def ensure_dir_exists(path):
    """Create ``path`` (and missing parents) if needed."""
    os.makedirs(str(path), exist_ok=True)
    return path


def list_dir(path, recursive=False, include_files=True, include_dirs=False, extensions=None):
    """List absolute paths under ``path`` (optionally recursive / filtered by suffix)."""
    if extensions:
        extensions = tuple(str(e).lower() if str(e).startswith(".") else "." + str(e).lower()
                           for e in extensions)
    result = []
    try:
        if recursive:
            walker = os.walk(str(path))
        else:
            names = os.listdir(str(path))
            walker = [(str(path),
                       [n for n in names if os.path.isdir(os.path.join(str(path), n))],
                       [n for n in names if not os.path.isdir(os.path.join(str(path), n))])]
        for root, dirs, files in walker:
            if include_dirs:
                result.extend(os.path.join(root, d) for d in dirs)
            if include_files:
                for name in files:
                    if extensions and not name.lower().endswith(extensions):
                        continue
                    result.append(os.path.join(root, name))
    except Exception:
        _log_failure("list_dir", path)
    return result


def write_file_bytes(path, content):
    return write_file(path, bytes(content))


def read_file_bytes(path):
    try:
        with open(path, "rb") as f:
            return f.read()
    except Exception:
        _log_failure("read_file_bytes", path)
        return None


def delete_file(path):
    """Delete a file; True when it existed and was removed."""
    try:
        if os.path.isfile(path) or os.path.islink(path):
            os.remove(path)
            return True
    except Exception:
        _log_failure("delete_file", path)
    return False


def write_file(path, content):
    """Write str or bytes to path (creating parent dirs as needed). Returns the path."""
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    if isinstance(content, (bytes, bytearray)):
        with open(path, "wb") as f:
            f.write(content)
    else:
        with open(path, "w", encoding="utf-8") as f:
            f.write(str(content))
    return path


def read_file(path, binary=False, default=None):
    """Read a file as text (default) or bytes; returns `default` on any error."""
    try:
        if binary:
            with open(path, "rb") as f:
                return f.read()
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        if default is None:
            _log_failure("read_file", path)
        return default


def exists(path):
    return os.path.exists(path)


# ---------------------------------------------------------------------- FilesController

def _calling_plugin_id():
    import sys
    try:
        from log_utils import _calling_plugin_id as find
        return find(sys._getframe(2))
    except Exception:
        return None


class FilesController:
    """Open files of a given extension with plugin code instead of the default viewer.

        secret = FilesController.register(FilesController.FileInfo(ext="zip", on_click=handler))
        FilesController.unregister("zip", secret)
    """

    SUPPORT_ICONS = False  # custom document icons are not drawn by ZaStoGram yet

    class ExtensionAlreadyRegistered(Exception):
        pass

    class ExtensionNotRegistered(Exception):
        pass

    class SecretInvalid(Exception):
        pass

    class Place:
        UNKNOWN = "UNKNOWN"
        ChatActivity = "ChatActivity"
        FilteredSearchView = "FilteredSearchView"
        SharedMediaLayout = "SharedMediaLayout"
        SearchDownloadsContainer = "SearchDownloadsContainer"
        ChannelAdminLogActivity = "ChannelAdminLogActivity"

    class FileInfo:
        def __init__(self, ext, on_click, whitelist_places=None, blacklist_places=None, get_icon=None):
            if whitelist_places and blacklist_places:
                raise ValueError("whitelist_places and blacklist_places cannot be used together")
            if get_icon is not None and not FilesController.SUPPORT_ICONS:
                get_icon = None  # accepted but not drawn
            self.ext = str(ext).lower().lstrip(".")
            self.on_click = on_click
            self.whitelist_places = list(whitelist_places or [])
            self.blacklist_places = list(blacklist_places or [])
            self.get_icon = get_icon

    class OnClickArgs:
        def __init__(self, place, file, file_name, message, activity, parent_fragment):
            self.place = place
            self.file = file
            self.file_name = file_name
            self.message = message
            self.activity = activity
            self.parent_fragment = parent_fragment

    _handlers = {}  # ext -> (FileInfo, secret, owner plugin id)

    @classmethod
    def register(cls, file_info):
        ext = file_info.ext
        if ext in cls._handlers:
            raise cls.ExtensionAlreadyRegistered(ext)
        import uuid
        secret = uuid.uuid4().hex
        cls._handlers[ext] = (file_info, secret, _calling_plugin_id())
        cls._sync()
        return secret

    @classmethod
    def unregister(cls, ext, secret):
        ext = str(ext).lower().lstrip(".")
        entry = cls._handlers.get(ext)
        if entry is None:
            raise cls.ExtensionNotRegistered(ext)
        if entry[1] != secret:
            raise cls.SecretInvalid(ext)
        del cls._handlers[ext]
        cls._sync()

    @classmethod
    def _remove_plugin(cls, plugin_id):
        for ext, entry in list(cls._handlers.items()):
            if entry[2] == plugin_id:
                del cls._handlers[ext]
        cls._sync()

    @classmethod
    def _sync(cls):
        try:
            from org.telegram.plugins import PluginsController
            PluginsController.setFileHandlersActive(bool(cls._handlers))
        except Exception:
            pass

    @classmethod
    def _place_of(cls, fragment):
        try:
            name = str(fragment.getClass().getSimpleName())
        except Exception:
            return cls.Place.UNKNOWN
        if name == "ChatActivity":
            return cls.Place.ChatActivity
        if name == "ChannelAdminLogActivity":
            return cls.Place.ChannelAdminLogActivity
        if name in ("MediaActivity", "ProfileActivity"):
            return cls.Place.SharedMediaLayout
        if name == "DialogsActivity":
            return cls.Place.FilteredSearchView
        return cls.Place.UNKNOWN


def _dispatch_file_open(file, file_name, message, activity):
    """Called by AndroidUtilities.openForView: True when a plugin handler took the file."""
    name = str(file_name or (file.getName() if file is not None else ""))
    if "." not in name:
        return False
    entry = FilesController._handlers.get(name.rsplit(".", 1)[1].lower())
    if entry is None:
        return False
    info = entry[0]
    try:
        from org.telegram.plugins import PluginUtils
        fragment = PluginUtils.getLastFragment()
    except Exception:
        fragment = None
    place = FilesController._place_of(fragment)
    if info.whitelist_places and place not in info.whitelist_places:
        return False
    if info.blacklist_places and place in info.blacklist_places:
        return False
    try:
        info.on_click(FilesController.OnClickArgs(place, file, name, message, activity, fragment))
    except Exception:
        _log_failure("FilesController handler", name)
    return True
