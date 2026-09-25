"""
ZaStoGram plugin SDK — base classes (exteraGram-compatible).

A plugin is a single .plugin Python file with module-level metadata (__id__, __name__, ...)
and a subclass of BasePlugin. The host instantiates it, injects a Java PluginContext as
``self._context`` and drives its lifecycle.
"""

import ast
import json


def _is_structured_setting(value):
    return isinstance(value, (dict, list, tuple))


def _clone_structured_default(value):
    try:
        return json.loads(json.dumps(value, ensure_ascii=False))
    except Exception:
        try:
            return value.copy()
        except Exception:
            return value


def _java_map_to_dict(value):
    try:
        return {str(k): value.get(k) for k in value.keySet()}
    except Exception:
        return None


def _java_list_to_list(value):
    try:
        return [value.get(i) for i in range(value.size())]
    except Exception:
        return None


def _decode_structured_setting(value, default):
    if isinstance(default, dict):
        if isinstance(value, dict):
            return value
        java_map = _java_map_to_dict(value)
        if java_map is not None:
            return java_map
    elif isinstance(default, (list, tuple)):
        if isinstance(value, list):
            return value
        if isinstance(value, tuple):
            return list(value)
        java_list = _java_list_to_list(value)
        if java_list is not None:
            return java_list

    text = str(value or "").strip()
    if not text:
        return _clone_structured_default(default)
    for load in (json.loads, ast.literal_eval):
        try:
            parsed = load(text)
        except Exception:
            continue
        if isinstance(default, dict) and isinstance(parsed, dict):
            return parsed
        if isinstance(default, (list, tuple)) and isinstance(parsed, (list, tuple)):
            return list(parsed)
    return _clone_structured_default(default)


def _unbox(value):
    """Java boxed primitive/String -> the native Python value (no default to coerce by)."""
    if isinstance(value, (bool, int, float, str)):
        return value
    try:
        from java.lang import Boolean, Number, String
        if isinstance(value, Boolean):
            return bool(value.booleanValue())
        if isinstance(value, Number):
            text = str(value.toString())
            return float(text) if any(c in text for c in ".eE") else int(text)
        if isinstance(value, String):
            return str(value)
    except Exception:
        pass
    return value


class HookStrategy:
    """Return strategy for high-level request/response/update/message hooks."""
    DEFAULT = 0        # let the original flow through unchanged
    MODIFY = 1         # deliver the modified object (HookResult.request/response/update/updates/params)
    CANCEL = 2         # cancel/stop the operation
    MODIFY_FINAL = 3   # modify AND stop further plugin processing of this event
    REPLACE = 1        # exteraGram alias for MODIFY


class HookResult:
    def __init__(self, strategy=HookStrategy.DEFAULT, response=None, request=None,
                 update=None, updates=None, params=None):
        self.strategy = strategy
        self.response = response
        self.request = request
        self.update = update
        self.updates = updates
        self.params = params


class AppEvent:
    """Lifecycle events delivered to BasePlugin.on_app_event."""
    START = "start"
    STOP = "stop"
    PAUSE = "pause"
    RESUME = "resume"


class MethodHook:
    """Xposed-style method hook. ``param`` is a Java MethodHookParam."""

    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class XposedHook(MethodHook):
    """Convenience hook from plain callables: XposedHook(before=fn, after=fn)."""

    def __init__(self, before=None, after=None):
        self._before = before
        self._after = after

    def before_hooked_method(self, param):
        if self._before is not None:
            self._before(param)

    def after_hooked_method(self, param):
        if self._after is not None:
            self._after(param)


def _filters_pass(filters, param):
    for f in filters or ():
        try:
            if not f.test(param):
                return False
        except Exception:
            return False
    return True


def hook_filters(*filters):
    """Run the decorated before/after_hooked_method only when every filter matches."""
    def decorate(fn):
        def wrapper(self, param):
            if _filters_pass(filters, param):
                return fn(self, param)
            return None
        wrapper.__name__ = getattr(fn, "__name__", "hooked_method")
        wrapper.__doc__ = getattr(fn, "__doc__", None)
        wrapper._hook_filters = filters
        return wrapper
    return decorate


class _Filter:
    def __init__(self, test):
        self.test = test


def _arg(param, index):
    return param.args[index]


def _java_equals(a, b):
    if a is None or b is None:
        return a is b
    try:
        return bool(a == b) or bool(a.equals(b))
    except Exception:
        return a == b


class HookFilter:
    """Conditions for hook callbacks (exteraGram API): pass them to @hook_filters(...) or
    hook_method(..., before_filters=[...], after_filters=[...])."""

    RESULT_IS_NULL = _Filter(lambda p: p.getResult() is None)
    RESULT_NOT_NULL = _Filter(lambda p: p.getResult() is not None)
    RESULT_IS_TRUE = _Filter(lambda p: p.getResult() is True or str(p.getResult()) == "true")
    RESULT_IS_FALSE = _Filter(lambda p: p.getResult() is False or str(p.getResult()) == "false")

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return _Filter(lambda p: p.getResult() is not None and clazz.isInstance(p.getResult()))

    @staticmethod
    def ResultEqual(value):
        return _Filter(lambda p: _java_equals(p.getResult(), value))

    @staticmethod
    def ResultNotEqual(value):
        return _Filter(lambda p: not _java_equals(p.getResult(), value))

    @staticmethod
    def ArgumentIsNull(index):
        return _Filter(lambda p: _arg(p, index) is None)

    @staticmethod
    def ArgumentNotNull(index):
        return _Filter(lambda p: _arg(p, index) is not None)

    @staticmethod
    def ArgumentIsTrue(index):
        return _Filter(lambda p: _arg(p, index) is True or str(_arg(p, index)) == "true")

    @staticmethod
    def ArgumentIsFalse(index):
        return _Filter(lambda p: _arg(p, index) is False or str(_arg(p, index)) == "false")

    @staticmethod
    def ArgumentIsInstanceOf(index, clazz):
        return _Filter(lambda p: _arg(p, index) is not None and clazz.isInstance(_arg(p, index)))

    @staticmethod
    def ArgumentEqual(index, value):
        return _Filter(lambda p: _java_equals(_arg(p, index), value))

    @staticmethod
    def ArgumentNotEqual(index, value):
        return _Filter(lambda p: not _java_equals(_arg(p, index), value))

    @staticmethod
    def Condition(condition, object=None):
        """A Python callable(param[, object]) is evaluated as-is. exteraGram also accepts MVEL
        strings, which this host cannot evaluate: those always pass so the hook still runs."""
        if callable(condition):
            def test(p):
                try:
                    return bool(condition(p, object))
                except TypeError:
                    return bool(condition(p))
            return _Filter(test)
        return _Filter(lambda p: True)

    @staticmethod
    def Or(*filters):
        return _Filter(lambda p: any(_filters_pass([f], p) for f in filters))


class BaseHook(MethodHook):
    """Functional hook behind hook_method(member, before=..., after=...)."""

    def __init__(self, before=None, after=None, before_filters=None, after_filters=None):
        self._before = before
        self._after = after
        self._before_filters = list(before_filters or [])
        self._after_filters = list(after_filters or [])

    def before_hooked_method(self, param):
        if self._before is not None and _filters_pass(self._before_filters, param):
            self._before(param)

    def after_hooked_method(self, param):
        if self._after is not None and _filters_pass(self._after_filters, param):
            self._after(param)


class MethodReplacement(MethodHook):
    """
    Xposed-style full method replacement. The return value of replace_hooked_method(param) becomes
    the hooked method's result and the original implementation does NOT run. Pass an instance to
    self.hook_method(member, MethodReplacement(...)) just like a MethodHook.
    """
    _is_replacement = True

    def replace_hooked_method(self, param):
        return None


class MenuItemType:
    """Which app menu an added item appears in (see BasePlugin.add_menu_item)."""
    MESSAGE_CONTEXT_MENU = "message_context_menu"  # long-press on a message
    DRAWER_MENU = "drawer_menu"                     # main navigation drawer
    CHAT_ACTION_MENU = "chat_action_menu"           # 3-dot menu inside a chat
    PROFILE_ACTION_MENU = "profile_action_menu"     # 3-dot menu on a profile
    MAIN_MENU = "main_menu"                         # exteraGram main menu (not rendered by ZaStoGram)


class MenuItemData:
    """
    A menu item added via BasePlugin.add_menu_item(). on_click(context) receives a dict with
    context-specific data (e.g. message / dialog_id / user / fragment for the relevant menu).
    """

    def __init__(self, menu_type, text, on_click, item_id=None, icon=None, subtext=None,
                 condition=None, priority=0):
        self.menu_type = menu_type
        self.text = text
        self.on_click = on_click
        self.item_id = item_id
        self.icon = icon
        self.subtext = subtext
        self.condition = condition   # MVEL visibility expression (not yet evaluated by host)
        self.priority = priority


_DEFAULT_HOOK_PRIORITY = 50  # XCallback.PRIORITY_DEFAULT


class BasePlugin:
    # Class-level defaults: many community plugins define __init__ without calling
    # super().__init__(), so nothing here may depend on BasePlugin.__init__ having run.
    _context = None           # Java org.telegram.plugins.PluginContext
    id = None
    name = None
    _send_message_hook = False  # set by add_on_send_message_hook()

    def __init__(self):
        self._request_hooks = []  # (name, match_substring) filters from add_hook()
        self._menu_items = []     # MenuItemData registered via add_menu_item()

    def _own_list(self, attr):
        value = self.__dict__.get(attr)
        if value is None:
            value = []
            setattr(self, attr, value)
        return value

    # ------------------------------------------------------------------ lifecycle

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_app_event(self, event_type):
        """Called on AppEvent.START/STOP/PAUSE/RESUME."""
        pass

    def create_settings(self):
        return []

    # ------------------------------------------------------------------ high-level hooks

    def pre_request_hook(self, request_name, account, request):
        """Before an outgoing request is sent. Return HookResult (CANCEL / MODIFY w/ .request)."""
        return HookResult(strategy=HookStrategy.DEFAULT)

    def post_request_hook(self, request_name, account, response, error):
        """Just before a response is delivered. Return HookResult (MODIFY w/ .response / CANCEL)."""
        return HookResult(strategy=HookStrategy.DEFAULT)

    def on_update_hook(self, update_name, account, update):
        """A single incoming update. Return HookResult (MODIFY w/ .update)."""
        return HookResult(strategy=HookStrategy.DEFAULT)

    def on_updates_hook(self, container_name, account, updates):
        """An updates container. Return HookResult (MODIFY w/ .updates)."""
        return HookResult(strategy=HookStrategy.DEFAULT)

    def on_send_message_hook(self, account, params):
        """Outgoing message params just before send. Return HookResult (MODIFY w/ .params / CANCEL)."""
        return HookResult(strategy=HookStrategy.DEFAULT)

    @staticmethod
    def parse_plugin_metadata(content):
        """Parse literal exteraGram metadata from source without executing it."""
        if isinstance(content, (bytes, bytearray)):
            source = bytes(content).decode("utf-8-sig", errors="replace")
        else:
            source = str(content)
        tree = ast.parse(source)
        aliases = {
            "id": "id", "name": "name", "description": "description",
            "author": "author", "version": "version", "icon": "icon",
            "min_version": "min_version", "app_version": "app_version",
            "requirements": "requirements", "sdk_version": "sdk_version",
        }
        result = {}
        for node in tree.body:
            if not isinstance(node, (ast.Assign, ast.AnnAssign)):
                continue
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            try:
                value = ast.literal_eval(node.value)
            except (TypeError, ValueError):
                continue
            for target in targets:
                if not isinstance(target, ast.Name):
                    continue
                name = target.id
                if name.startswith("__") and name.endswith("__"):
                    key = aliases.get(name[2:-2])
                    if key:
                        result[key] = value
        if "min_version" not in result and "app_version" in result:
            result["min_version"] = str(result["app_version"]).lstrip(">= ")
        return result

    # ------------------------------------------------------------------ method hooking

    @staticmethod
    def _make_hook(hook, before, after, before_filters, after_filters):
        if hook is not None:
            return hook
        if before is None and after is None:
            raise ValueError("hook_method needs a hook object or before=/after= callables")
        return BaseHook(before, after, before_filters, after_filters)

    def hook_method(self, method, hook=None, priority=_DEFAULT_HOOK_PRIORITY, before=None,
                    after=None, before_filters=None, after_filters=None):
        """Hook one Method/Constructor with a MethodHook/MethodReplacement or before=/after=."""
        hook = self._make_hook(hook, before, after, before_filters, after_filters)
        return self._context.hookMethod(method, hook, int(priority))

    def hook_all_constructors(self, clazz, hook=None, priority=_DEFAULT_HOOK_PRIORITY, before=None,
                              after=None, before_filters=None, after_filters=None):
        hook = self._make_hook(hook, before, after, before_filters, after_filters)
        return self._context.hookAllConstructors(clazz, hook, int(priority))

    def hook_all_methods(self, clazz, method_name=None, hook=None, priority=_DEFAULT_HOOK_PRIORITY,
                         before=None, after=None, before_filters=None, after_filters=None):
        """
        Hook all overloads of method_name on clazz (Xposed-style hookAllMethods). clazz may be a
        java.lang.Class or a fully-qualified class-name string. The shorter form
        hook_all_methods(clazz, hook) hooks every declared method. Returns a list of Unhooks.
        """
        if hook is None and method_name is not None and not isinstance(method_name, str):
            hook = method_name      # called as hook_all_methods(clazz, hook)
            method_name = None
        hook = self._make_hook(hook, before, after, before_filters, after_filters)
        return self._context.hookAllMethods(clazz, method_name, hook, int(priority))

    def unhook_method(self, unhook):
        if unhook is not None:
            self._context.unhook(unhook)

    def add_hook(self, request_name, match_substring=False, priority=0):
        """Register interest in a (TL) request name so pre/post_request_hook fires for it."""
        self._own_list("_request_hooks").append((str(request_name), bool(match_substring)))
        return (request_name, match_substring)

    def add_on_send_message_hook(self, priority=0):
        """Enable on_send_message_hook for this plugin."""
        self._send_message_hook = True

    def remove_hook(self, hook_name):
        """Remove a named high-level hook using exteraGram's compatibility API."""
        if hook_name == "on_send_message_hook":
            self._send_message_hook = False
            return True
        if hook_name in ("pre_request_hook", "post_request_hook"):
            self._own_list("_request_hooks").clear()
            return True
        return False

    def _matches_request(self, request_name):
        if not self.__dict__.get("_request_hooks"):
            return None  # no filters → caller decides
        for name, substring in self._request_hooks:
            if substring:
                if name in str(request_name):
                    return True
            elif name == str(request_name):
                return True
        return False

    # ------------------------------------------------------------------ menu items

    def add_menu_item(self, item):
        """Register a MenuItemData; it appears in the menu named by item.menu_type."""
        self._own_list("_menu_items").append(item)
        return item.item_id if getattr(item, "item_id", None) is not None else item

    def remove_menu_item(self, item_id):
        """Remove a previously added menu item by its item_id (or the object returned by add)."""
        self._menu_items = [m for m in self._own_list("_menu_items")
                            if m is not item_id and getattr(m, "item_id", None) != item_id]

    # ------------------------------------------------------------------ settings storage

    def get_setting(self, key, default=None):
        structured = _is_structured_setting(default)
        value = self._context.getSetting(key, None if structured else default)
        if value is None:
            return _clone_structured_default(default) if structured else default
        if structured:
            return _decode_structured_setting(value, default)
        # getSetting is declared to return Object, so Chaquopy hands back a Java-typed proxy
        # (e.g. java.lang.Boolean) that does NOT unbox via bool()/int(). Coerce to the native
        # Python type implied by `default`. NB: check bool before int — bool subclasses int.
        if isinstance(default, bool):
            return str(value).strip().lower() == "true"
        if isinstance(default, int):
            try:
                return int(value)
            except Exception:
                return default
        if isinstance(default, float):
            try:
                return float(value)
            except Exception:
                return default
        if default is None:
            return _unbox(value)
        return str(value)

    def set_setting(self, key, value, reload_settings=False):
        if _is_structured_setting(value):
            try:
                value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
            except Exception:
                value = str(value)
        self._context.setSetting(key, value)
        if reload_settings:
            try:
                self._context.reloadSettings()
            except Exception:
                pass

    def export_settings(self):
        """Return a dict of all persisted settings for this plugin."""
        out = {}
        try:
            m = self._context.getAllSettings()
            if m is not None:
                for k in m.keySet():
                    out[str(k)] = m.get(k)
        except Exception:
            pass
        return out

    def import_settings(self, settings, reload_settings=True):
        if not settings:
            return
        try:
            for k, v in settings.items():
                self._context.setSetting(str(k), v)
        except Exception:
            pass
        if reload_settings:
            try:
                self._context.reloadSettings()
            except Exception:
                pass

    # ------------------------------------------------------------------ misc

    def getName(self):
        return self.name or self.id

    def log(self, msg):
        try:
            self._context.log(str(msg))
        except Exception:
            pass
