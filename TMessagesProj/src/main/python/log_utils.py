"""
log_utils — the plugin logger (exteraGram SDK API).

Every plugin gets ``self.logger``; it writes to logcat under the tag ``plugin.<id>`` with a
``[file:function:line]`` prefix, splits long entries, supports child loggers, timers, handlers
and ``dump()`` of Python and Java objects.
"""

from __future__ import annotations

import os
import sys
import threading
import time
import traceback
from enum import IntEnum

__all__ = ["LogLevel", "LogRecord", "Logger", "get_logger", "dump"]

_MAX_ENTRY = 4000  # logcat truncates a single entry at ~4 KB


class LogLevel(IntEnum):
    VERBOSE = 2
    DEBUG = 3
    INFO = 4
    WARNING = 5
    ERROR = 6
    OFF = 100

    WARN = 5  # alias


def _coerce_level(value):
    if value is None:
        return None
    if isinstance(value, LogLevel):
        return value
    if isinstance(value, str):
        name = value.strip().upper()
        if name == "WARN":
            name = "WARNING"
        return LogLevel[name]
    return LogLevel(int(value))


class LogRecord:
    __slots__ = ("level", "tag", "message", "location", "traceback", "created", "text")

    def __init__(self, level, tag, message, location, tb, created, text):
        self.level = level
        self.tag = tag
        self.message = message
        self.location = location
        self.traceback = tb
        self.created = created
        self.text = text

    def __repr__(self):
        return f"LogRecord({self.level.name}, {self.tag!r}, {self.message!r})"


def _default_level():
    try:
        from org.telegram.messenger import BuildVars
        if BuildVars.LOGS_ENABLED or BuildVars.DEBUG_VERSION:
            return LogLevel.DEBUG
    except Exception:
        pass
    return LogLevel.INFO


def _write_logcat(level, tag, text):
    try:
        from android.util import Log
        Log.println(int(min(level, LogLevel.ERROR)), tag[:100], text)
    except Exception:
        print(f"{level.name[0]} {tag}: {text}")
    if level >= LogLevel.INFO:
        try:
            from org.telegram.messenger import FileLog
            if level >= LogLevel.ERROR:
                FileLog.e(f"[{tag}] {text}")
            else:
                FileLog.d(f"[{tag}] {text}")
        except Exception:
            pass


def _split(text):
    if len(text) <= _MAX_ENTRY:
        return [text]
    chunks, current = [], ""
    for line in text.splitlines(True):
        while len(line) > _MAX_ENTRY:
            if current:
                chunks.append(current)
                current = ""
            chunks.append(line[:_MAX_ENTRY])
            line = line[_MAX_ENTRY:]
        if len(current) + len(line) > _MAX_ENTRY:
            chunks.append(current)
            current = ""
        current += line
    if current:
        chunks.append(current)
    return [c.rstrip("\n") for c in chunks]


_handler_guard = threading.local()


class _Timer:
    def __init__(self, logger, label, level):
        self.logger = logger
        self.label = label
        self.level = level

    def __enter__(self):
        self.start = time.perf_counter()
        return self

    def __exit__(self, exc_type, exc, tb):
        elapsed = (time.perf_counter() - self.start) * 1000.0
        suffix = " (failed)" if exc_type is not None else ""
        self.logger.log(self.level, "%s took %.1f ms%s", self.label, elapsed, suffix, stacklevel=2)
        return False


class Logger:
    def __init__(self, tag, parent=None):
        self.tag = str(tag)
        self._parent = parent
        self._level = None
        self._handlers = []
        self._children = {}

    # ------------------------------------------------------------------ levels

    @property
    def level(self):
        return self._level

    @level.setter
    def level(self, value):
        self._level = _coerce_level(value)

    @property
    def effective_level(self):
        logger = self
        while logger is not None:
            if logger._level is not None:
                return logger._level
            logger = logger._parent
        return _default_level()

    def is_enabled_for(self, level):
        level = _coerce_level(level)
        effective = self.effective_level
        return effective != LogLevel.OFF and level >= effective

    # ------------------------------------------------------------------ hierarchy

    def child(self, name):
        name = str(name)
        logger = self._children.get(name)
        if logger is None:
            logger = Logger(f"{self.tag}.{name}", parent=self)
            self._children[name] = logger
        return logger

    def add_handler(self, handler):
        if handler not in self._handlers:
            self._handlers.append(handler)

    def remove_handler(self, handler):
        try:
            self._handlers.remove(handler)
            return True
        except ValueError:
            return False

    def _reset(self):
        self._level = None
        self._handlers.clear()
        for child in self._children.values():
            child._reset()

    # ------------------------------------------------------------------ emit

    def log(self, level, msg, *args, exc_info=None, stacklevel=1):
        level = _coerce_level(level)
        if not self.is_enabled_for(level):
            return
        try:
            message = str(msg) % args if args else str(msg)
        except Exception:
            message = " ".join(str(part) for part in (msg,) + args)

        tb_text = None
        if exc_info:
            if exc_info is True:
                exc_info = sys.exc_info()
            if isinstance(exc_info, BaseException):
                tb_text = "".join(traceback.format_exception(type(exc_info), exc_info, exc_info.__traceback__))
            elif isinstance(exc_info, tuple) and exc_info[0] is not None:
                tb_text = "".join(traceback.format_exception(*exc_info))

        location = None
        try:
            frame = sys._getframe(max(1, int(stacklevel)))
            location = f"{os.path.basename(frame.f_code.co_filename)}:{frame.f_code.co_name}:{frame.f_lineno}"
        except Exception:
            pass

        text = f"[{location}] {message}" if location else message
        if tb_text:
            text = text + "\n" + tb_text.rstrip("\n")
        for chunk in _split(text):
            _write_logcat(level, self.tag, chunk)

        if getattr(_handler_guard, "active", False):
            return
        record = LogRecord(level, self.tag, message, location, tb_text, time.time(), text)
        logger = self
        _handler_guard.active = True
        try:
            while logger is not None:
                for handler in list(logger._handlers):
                    try:
                        handler(record)
                    except Exception:
                        _write_logcat(LogLevel.ERROR, "pysdk", "log handler failed\n" + traceback.format_exc())
                logger = logger._parent
        finally:
            _handler_guard.active = False

    def verbose(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.VERBOSE, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    def debug(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.DEBUG, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    def info(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.INFO, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    def warning(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.WARNING, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    warn = warning

    def error(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.ERROR, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    def exception(self, msg, *args, stacklevel=1, exc_info=True):
        self.log(LogLevel.ERROR, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    critical = error

    def dump(self, obj, label=None, *, level=LogLevel.DEBUG, depth=2, max_items=20, stacklevel=1):
        if not self.is_enabled_for(level):
            return
        text = dump(obj, depth=depth, max_items=max_items)
        if label:
            text = f"{label}: {text}"
        self.log(level, "%s", text, stacklevel=stacklevel + 1)

    def timed(self, label, level=LogLevel.DEBUG):
        return _Timer(self, label, _coerce_level(level))

    def __repr__(self):
        return f"<Logger {self.tag} level={self.effective_level.name}>"


# ---------------------------------------------------------------------- plugin loggers

_PLUGIN_LOGGERS = {}
_lock = threading.Lock()


def plugin_logger(plugin_id):
    plugin_id = str(plugin_id)
    with _lock:
        logger = _PLUGIN_LOGGERS.get(plugin_id)
        if logger is None:
            logger = Logger(f"plugin.{plugin_id}")
            _PLUGIN_LOGGERS[plugin_id] = logger
        return logger


def reset_plugin_logger(plugin_id):
    """Called by the host on unload: drop handlers and levels."""
    logger = _PLUGIN_LOGGERS.get(str(plugin_id))
    if logger is not None:
        logger._reset()


def _calling_plugin_id(frame):
    try:
        from _plugin_loader import plugin_id_for_globals
    except Exception:
        return None
    while frame is not None:
        plugin_id = plugin_id_for_globals(frame.f_globals)
        if plugin_id is not None:
            return plugin_id
        frame = frame.f_back
    return None


def get_logger(name=None):
    """Logger of the calling plugin (or a standalone one outside plugin code)."""
    frame = sys._getframe(1)
    plugin_id = _calling_plugin_id(frame)
    if plugin_id is not None:
        logger = plugin_logger(plugin_id)
        if name and str(name) not in (plugin_id, frame.f_globals.get("__name__")):
            return logger.child(str(name).rsplit(".", 1)[-1])
        return logger
    return Logger(name or "pysdk")


# ---------------------------------------------------------------------- dump

_PLATFORM_PREFIXES = ("java.", "javax.", "android.", "androidx.", "kotlin.", "dalvik.", "sun.", "libcore.")


def _java_class_name(cls):
    try:
        name = str(cls.getName())
    except Exception:
        return type(cls).__name__
    if name.startswith("org.telegram.tgnet.TLRPC$"):
        return "TLRPC." + name.split("$", 1)[1]
    return name.rsplit(".", 1)[-1].replace("$", ".")


def _scalar(value):
    if value is None or isinstance(value, (bool, int, float)):
        return repr(value)
    if isinstance(value, str):
        return repr(value if len(value) <= 200 else value[:200] + "…")
    if isinstance(value, (bytes, bytearray)):
        return f"bytes[{len(value)}]"
    return None


def dump(obj, depth=2, max_items=20):
    lines = []
    _dump_into(lines, obj, int(depth), int(max_items), 0, set())
    return "\n".join(lines)


def _dump_into(lines, obj, depth, max_items, indent, seen, prefix=""):
    pad = "  " * indent
    scalar = _scalar(obj)
    if scalar is not None:
        lines.append(f"{pad}{prefix}{scalar}")
        return

    ident = id(obj)
    if ident in seen:
        lines.append(f"{pad}{prefix}<cycle>")
        return

    # Python containers
    if isinstance(obj, dict):
        lines.append(f"{pad}{prefix}dict[{len(obj)}]")
        if depth <= 0:
            lines[-1] += " {…}"
            return
        seen.add(ident)
        for i, (k, v) in enumerate(obj.items()):
            if i >= max_items:
                lines.append(f"{pad}  …")
                break
            _dump_into(lines, v, depth - 1, max_items, indent + 1, seen, f"{k!r}: ")
        seen.discard(ident)
        return
    if isinstance(obj, (list, tuple, set, frozenset)):
        lines.append(f"{pad}{prefix}{type(obj).__name__}[{len(obj)}]")
        if depth <= 0:
            lines[-1] += " {…}"
            return
        seen.add(ident)
        for i, v in enumerate(obj):
            if i >= max_items:
                lines.append(f"{pad}  …")
                break
            _dump_into(lines, v, depth - 1, max_items, indent + 1, seen, f"[{i}] ")
        seen.discard(ident)
        return

    if hasattr(obj, "getClass"):
        _dump_java(lines, obj, depth, max_items, indent, seen, prefix)
        return

    fields = getattr(obj, "__dict__", None)
    head = f"{type(obj).__name__} @{ident & 0xffffff:06x}"
    if not isinstance(fields, dict):
        try:
            lines.append(f"{pad}{prefix}{obj!r}")
        except Exception:
            lines.append(f"{pad}{prefix}{head}")
        return
    lines.append(f"{pad}{prefix}{head}")
    if depth <= 0:
        lines[-1] += " {…}"
        return
    seen.add(ident)
    for i, (k, v) in enumerate(sorted(fields.items())):
        if i >= max_items * 5:
            lines.append(f"{pad}  …")
            break
        _dump_into(lines, v, depth - 1, max_items, indent + 1, seen, f"{k} = ")
    seen.discard(ident)


def _dump_java(lines, obj, depth, max_items, indent, seen, prefix):
    pad = "  " * indent
    try:
        cls = obj.getClass()
        full = str(cls.getName())
    except Exception:
        lines.append(f"{pad}{prefix}{obj!r}")
        return
    try:
        identity = int(__import__("java").jclass("java.lang.System").identityHashCode(obj))
    except Exception:
        identity = id(obj)
    head = f"{_java_class_name(cls)} @{identity & 0xffffff:06x}"

    try:
        if cls.isArray():
            from java.lang.reflect import Array
            size = int(Array.getLength(obj))
            component = str(cls.getComponentType().getName())
            if cls.getComponentType().isPrimitive():
                lines.append(f"{pad}{prefix}{component}[{size}]")
                return
            lines.append(f"{pad}{prefix}{component.rsplit('.', 1)[-1]}[{size}]")
            if depth > 0:
                for i in range(min(size, max_items)):
                    _dump_into(lines, Array.get(obj, i), depth - 1, max_items, indent + 1, seen, f"[{i}] ")
            return
    except Exception:
        pass

    try:
        from java.util import Collection, Map
        if isinstance(obj, Map):
            lines.append(f"{pad}{prefix}{head} size={obj.size()}")
            if depth > 0:
                for i, key in enumerate(obj.keySet()):
                    if i >= max_items:
                        lines.append(f"{pad}  …")
                        break
                    _dump_into(lines, obj.get(key), depth - 1, max_items, indent + 1, seen, f"{key}: ")
            return
        if isinstance(obj, Collection):
            lines.append(f"{pad}{prefix}{head} size={obj.size()}")
            if depth > 0:
                for i, value in enumerate(obj):
                    if i >= max_items:
                        lines.append(f"{pad}  …")
                        break
                    _dump_into(lines, value, depth - 1, max_items, indent + 1, seen, f"[{i}] ")
            return
    except Exception:
        pass

    if full.startswith(_PLATFORM_PREFIXES):
        try:
            lines.append(f"{pad}{prefix}{obj.toString()}")
        except Exception:
            lines.append(f"{pad}{prefix}{head}")
        return

    lines.append(f"{pad}{prefix}{head}")
    if depth <= 0:
        lines[-1] += " {…}"
        return
    ident = id(obj)
    seen.add(ident)
    try:
        from java.lang.reflect import Modifier
        fields = []
        c = cls
        while c is not None and not str(c.getName()).startswith(_PLATFORM_PREFIXES):
            for field in c.getDeclaredFields():
                if Modifier.isStatic(field.getModifiers()):
                    continue
                fields.append(field)
            c = c.getSuperclass()
        for field in sorted(fields, key=lambda f: str(f.getName())):
            try:
                field.setAccessible(True)
                value = field.get(obj)
            except Exception:
                continue
            _dump_into(lines, value, depth - 1, max_items, indent + 1, seen, f"{field.getName()} = ")
    except Exception:
        lines.append(f"{pad}  <unreadable>")
    finally:
        seen.discard(ident)
