"""
intents — react to links, deeplinks and intents handled by the app (exteraGram SDK).

    from intents import IntentsManager as IM
    handle = IM.new_global_before_handler(on_chat, scheme="tg", host="chat",
                                          required_path_args_names=["id"])
    handle.unhandle()

Handlers run around LaunchActivity.handleIntent. A before handler returning True stops later
handlers and the app's own handling of the intent.
"""

import inspect
import re
import threading
import traceback
import uuid
from urllib.parse import parse_qsl, urlsplit

__all__ = ["IntentsManager", "HandlerHandle"]

_STANDARD = ("intent", "scheme", "host", "path", "query_args", "action", "flags", "type", "categories")


class HandlerHandle:
    def __init__(self, handler_id):
        self.handler_id = handler_id

    def unhandle(self):
        try:
            IntentsManager.unhandle(self.handler_id)
            return True
        except IntentsManager.HandlerNotRegistered:
            return False

    def __repr__(self):
        return f"HandlerHandle({self.handler_id!r})"


class _Handler:
    def __init__(self, stage, callback, priority, owner, filters):
        self.id = uuid.uuid4().hex
        self.stage = stage
        self.callback = callback
        self.priority = int(priority or 0)
        self.owner = owner
        self.filters = filters
        path = filters.get("path")
        self.path_regex = None
        if path:
            if not path.startswith("/"):
                path = "/" + path
            filters["path"] = path
            pattern = re.sub(r"\\\{(\w+)\\\}", r"(?P<\1>[^/]+)", re.escape(path))
            self.path_regex = re.compile("^" + pattern + "$")


def _intent_context(intent):
    ctx = {"intent": intent, "scheme": None, "host": None, "path": None, "query_args": {},
           "action": None, "flags": 0, "type": None, "categories": []}
    try:
        ctx["action"] = None if intent.getAction() is None else str(intent.getAction())
        ctx["flags"] = int(intent.getFlags())
        ctx["type"] = None if intent.getType() is None else str(intent.getType())
        categories = intent.getCategories()
        ctx["categories"] = [str(c) for c in categories] if categories is not None else []
        data = intent.getData()
        if data is not None:
            parts = IntentsManager.parse(str(data.toString()))
            ctx["scheme"] = parts.get("scheme")
            ctx["host"] = parts.get("host")
            ctx["path"] = parts.get("path") or None
            ctx["query_args"] = dict(parse_qsl(parts.get("query") or "", keep_blank_values=True))
    except Exception:
        traceback.print_exc()
    return ctx


def _matches(handler, ctx):
    f = handler.filters
    if f.get("scheme") is not None and (ctx["scheme"] or "").lower() != str(f["scheme"]).lower():
        return None
    if f.get("host") is not None and (ctx["host"] or "").lower() != str(f["host"]).lower():
        return None
    extra = {}
    if handler.path_regex is not None:
        match = handler.path_regex.match(ctx["path"] or "")
        if match is None:
            return None
        extra.update(match.groupdict())
    for name in f.get("required_path_args_names") or ():
        if name not in ctx["query_args"]:
            return None
    if f.get("action") is not None and ctx["action"] != str(f["action"]):
        return None
    flags = ctx["flags"]
    for flag in f.get("whitelist_flags") or ():
        if flags & int(flag) != int(flag):
            return None
    for flag in f.get("blacklist_flags") or ():
        if flags & int(flag):
            return None
    if f.get("type") is not None and ctx["type"] != str(f["type"]):
        return None
    for category in f.get("categories") or ():
        if str(category) not in ctx["categories"]:
            return None
    return extra


def _call(callback, ctx, extra):
    named = dict(ctx["query_args"])
    named.update(extra)
    named.update({k: ctx[k] for k in _STANDARD})
    try:
        signature = inspect.signature(callback)
    except (TypeError, ValueError):
        return callback()
    args, kwargs = [], {}
    has_varargs = any(p.kind is p.VAR_POSITIONAL for p in signature.parameters.values())
    has_varkw = any(p.kind is p.VAR_KEYWORD for p in signature.parameters.values())
    for name, param in signature.parameters.items():
        if param.kind in (param.VAR_POSITIONAL, param.VAR_KEYWORD):
            continue
        if name in named:
            if param.kind is param.POSITIONAL_ONLY:
                args.append(named[name])
            else:
                kwargs[name] = named[name]
        elif param.default is param.empty:
            return None  # a required value is not available for this intent
    if has_varkw:
        for key, value in named.items():
            kwargs.setdefault(key, value)
    if has_varargs:
        positional = [ctx[k] for k in _STANDARD]
        for key in _STANDARD:
            kwargs.pop(key, None)
        return callback(*positional, *args, **kwargs)
    return callback(*args, **kwargs)


class IntentsManager:
    class HandlerNotRegistered(Exception):
        pass

    _handlers = {}
    _lock = threading.RLock()
    _hooked = False

    @classmethod
    def new_global_before_handler(cls, callback, scheme=None, host=None, path=None,
                                  required_path_args_names=None, action=None, whitelist_flags=None,
                                  blacklist_flags=None, type=None, categories=None, priority=0):
        return cls._add("before", callback, priority, dict(
            scheme=scheme, host=host, path=path, required_path_args_names=required_path_args_names,
            action=action, whitelist_flags=whitelist_flags, blacklist_flags=blacklist_flags,
            type=type, categories=categories))

    @classmethod
    def new_global_after_handler(cls, callback, scheme=None, host=None, path=None,
                                 required_path_args_names=None, action=None, whitelist_flags=None,
                                 blacklist_flags=None, type=None, categories=None, priority=0):
        return cls._add("after", callback, priority, dict(
            scheme=scheme, host=host, path=path, required_path_args_names=required_path_args_names,
            action=action, whitelist_flags=whitelist_flags, blacklist_flags=blacklist_flags,
            type=type, categories=categories))

    @classmethod
    def unhandle(cls, handler_id):
        with cls._lock:
            if cls._handlers.pop(str(handler_id), None) is None:
                raise cls.HandlerNotRegistered(str(handler_id))

    @staticmethod
    def parse(url):
        parts = urlsplit(str(url))
        result = {"scheme": parts.scheme or None, "host": parts.hostname or parts.netloc or None}
        if parts.path:
            result["path"] = parts.path
        if parts.query:
            result["query"] = parts.query
        return result

    # ------------------------------------------------------------------ internals

    @classmethod
    def _add(cls, stage, callback, priority, filters):
        owner = None
        try:
            import sys
            from log_utils import _calling_plugin_id
            owner = _calling_plugin_id(sys._getframe(2))
        except Exception:
            pass
        handler = _Handler(stage, callback, priority, owner, filters)
        with cls._lock:
            cls._handlers[handler.id] = handler
            cls._ensure_hook()
        return HandlerHandle(handler.id)

    @classmethod
    def _remove_plugin(cls, plugin_id):
        with cls._lock:
            for handler_id, handler in list(cls._handlers.items()):
                if handler.owner == plugin_id:
                    del cls._handlers[handler_id]

    @classmethod
    def _run(cls, stage, intent, param=None):
        with cls._lock:
            handlers = sorted((h for h in cls._handlers.values() if h.stage == stage),
                              key=lambda h: h.priority)
        if not handlers or intent is None:
            return False
        ctx = _intent_context(intent)
        for handler in handlers:
            extra = _matches(handler, ctx)
            if extra is None:
                continue
            try:
                result = _call(handler.callback, ctx, extra)
            except Exception:
                traceback.print_exc()
                continue
            if stage == "before" and result is True:
                return True
        return False

    @classmethod
    def _ensure_hook(cls):
        if cls._hooked:
            return
        from base_plugin import MethodHook
        from java import jclass
        from org.telegram.plugins import PluginContext

        launch = jclass("org.telegram.ui.LaunchActivity").getClass()
        target = None
        for method in launch.getDeclaredMethods():
            if str(method.getName()) == "handleIntent" and len(method.getParameterTypes()) == 7:
                target = method
                break
        if target is None:
            raise RuntimeError("LaunchActivity.handleIntent not found")

        class _IntentHook(MethodHook):
            def before_hooked_method(self, param):
                if IntentsManager._run("before", param.args[0], param):
                    param.setResult(True)

            def after_hooked_method(self, param):
                IntentsManager._run("after", param.args[0], param)

        cls._context = PluginContext("__intents__")
        cls._hook = _IntentHook()
        cls._context.hookMethod(target, cls._hook, 50)
        cls._hooked = True
