"""
_plugin_loader — host-side orchestration called from PluginsController (Java).

Owns the live plugin instances and their settings item lists. Keeps the messy parts
(exec, subclass discovery, callback dispatch, strategy handling) in Python where they
are simplest, and exposes a small, Java-friendly surface.
"""

import traceback

_INSTANCES = {}   # plugin_id -> BasePlugin instance
_SETTINGS = {}    # plugin_id -> list of ui.settings items (last rendered)
_SETTINGS_SCREENS = {}  # screen token -> (plugin_id, list of nested settings items)
_MODULES = {}     # plugin_id -> module globals dict (keeps the module alive)
_SETTINGS_SCREEN_COUNTER = 0

CANCEL_SENTINEL = "__zasto_cancel__"


def _report_click_error(plugin_id, where):
    """A plugin's button handler raised: log the traceback to FileLog and tell the user.

    traceback.print_exc() only reaches logcat, so a broken handler looked like a button
    that plays its ripple and does nothing, with no trace in the app's own log.
    """
    text = traceback.format_exc()
    try:
        from org.telegram.messenger import FileLog
        FileLog.e("[plugin:" + str(plugin_id) + "] " + where + "\n" + text)
    except Exception:
        traceback.print_exc()
    try:
        from ui.bulletin import BulletinHelper
        inst = _INSTANCES.get(plugin_id)
        name = (getattr(inst, "name", None) or plugin_id) if inst is not None else plugin_id
        last = text.strip().splitlines()[-1] if text.strip() else "error"
        BulletinHelper.show_error(f"Плагин «{name}»: {last}")
    except Exception:
        traceback.print_exc()


def configure(requirements_dir):
    """Configure the writable cache used by plugin ``__requirements__``."""
    from _plugin_requirements import configure as configure_requirements
    configure_requirements(requirements_dir)


def _read_source(path):
    # utf-8-sig so a leading BOM (common from Windows editors) is stripped, not fed to compile().
    with open(path, "r", encoding="utf-8-sig") as f:
        return f.read()


# Packages whose submodules import each other in a cycle that only resolves when a specific
# entry module is imported first. Plugins sometimes carry a stray IDE auto-import of such a
# submodule (e.g. "from debugpy.adapter.sessions import get"): importing it cold raises
# "partially initialized module ... (most likely due to a circular import)".
_CYCLE_ENTRY_MODULES = (
    ("debugpy.adapter", "debugpy.adapter.clients"),
)


def _prime_import_cycles(src):
    import importlib
    for prefix, entry in _CYCLE_ENTRY_MODULES:
        if prefix in src:
            try:
                importlib.import_module(entry)
            except Exception:
                traceback.print_exc()


def _plugin_file_for_id(plugin_id):
    """Installed plugin file for an id (<id>.plugin or <id>.py), or None."""
    import os
    from file_utils import get_plugins_dir
    if not isinstance(plugin_id, str) or not plugin_id or "." in plugin_id:
        return None
    directory = get_plugins_dir()
    for ext in (".plugin", ".py"):
        candidate = os.path.join(directory, plugin_id + ext)
        if os.path.isfile(candidate):
            return candidate
    return None


def _file_stamp(path):
    import os
    try:
        st = os.stat(path)
        return (st.st_mtime_ns, st.st_size)
    except OSError:
        return None


def _exec_plugin_module(path, plugin_id):
    """Execute a plugin file as a real module registered in sys.modules under its __id__.

    exteraGram exposes every plugin as an importable module named by its id, which is how
    library plugins (zwylib, cactuslib, quantahut, ...) are consumed: ``import zwylib``.
    """
    import sys
    import types

    src = _read_source(path)
    _prime_import_cycles(src)
    module = types.ModuleType(str(plugin_id))
    module.__file__ = str(path)
    module.__builtins__ = __builtins__
    module.__zasto_stamp__ = _file_stamp(path)
    module.__zasto_instantiated__ = False
    # exteraGram's plugin namespace already holds BasePlugin; some plugins never import it.
    from base_plugin import BasePlugin
    module.BasePlugin = BasePlugin
    previous = sys.modules.get(str(plugin_id))
    sys.modules[str(plugin_id)] = module
    try:
        exec(compile(src, str(path), "exec"), module.__dict__)
    except BaseException:
        if previous is not None:
            sys.modules[str(plugin_id)] = previous
        else:
            sys.modules.pop(str(plugin_id), None)
        raise
    return module


class _PluginModuleFinder:
    """sys.meta_path hook: ``import <plugin id>`` loads that installed plugin's module."""

    def find_spec(self, fullname, path=None, target=None):
        if path is not None or "." in fullname:
            return None
        plugin_file = _plugin_file_for_id(fullname)
        if plugin_file is None:
            return None
        import importlib.util
        return importlib.util.spec_from_loader(fullname, _PluginModuleLoader(plugin_file))


class _PluginModuleLoader:
    def __init__(self, plugin_file):
        self.plugin_file = plugin_file

    def create_module(self, spec):
        return _exec_plugin_module(self.plugin_file, spec.name)

    def exec_module(self, module):
        pass  # already executed in create_module, where it is registered before its body runs


def _install_plugin_finder():
    import sys
    if not any(isinstance(f, _PluginModuleFinder) for f in sys.meta_path):
        sys.meta_path.append(_PluginModuleFinder())


def _reusable_module(path, plugin_id):
    """A module imported by another plugin before this plugin itself was loaded."""
    import sys
    module = sys.modules.get(str(plugin_id))
    if module is None or getattr(module, "__zasto_instantiated__", True):
        return None
    if getattr(module, "__file__", None) != str(path):
        return None
    if getattr(module, "__zasto_stamp__", None) != _file_stamp(path):
        return None
    return module


def instantiate(path, plugin_id, context):
    """Exec the plugin file, instantiate its BasePlugin subclass and run on_plugin_load()."""
    from base_plugin import BasePlugin
    from _plugin_requirements import ensure_requirements

    import os
    _install_plugin_finder()
    if os.path.isdir(str(path)):
        return _instantiate_elyx(path, plugin_id, context)
    # Resolve metadata before executing imports from the plugin source. Both the
    # official list form and the legacy single-string form are supported.
    ensure_requirements(path)

    module = _reusable_module(path, plugin_id) or _exec_plugin_module(path, plugin_id)
    module.__zasto_instantiated__ = True
    g = module.__dict__

    candidates = [v for v in g.values()
                  if isinstance(v, type) and issubclass(v, BasePlugin) and v is not BasePlugin]
    if not candidates:
        raise RuntimeError("no BasePlugin subclass found in " + str(path))
    # Pick the most-derived class: drop any candidate that is a base of another candidate
    # (so an intermediate base/mixin defined before the real plugin is not chosen).
    leaves = [c for c in candidates
              if not any(c is not d and issubclass(d, c) for d in candidates)]
    pool = leaves or candidates
    # Prefer a class defined in THIS plugin file over an imported helper base. The plugin's own
    # "__name__ = ..." metadata rebinds the module name, so accept both spellings.
    own_names = {str(plugin_id), g.get("__name__")}
    own = [c for c in pool if getattr(c, "__module__", None) in own_names]
    pool = own or pool
    plugin_cls = pool[-1]  # last defined wins

    inst = _construct(plugin_cls, context, plugin_id)
    name = g.get("__name__")
    inst.name = name if isinstance(name, str) and name != str(plugin_id) else plugin_id
    for attr, key in (("version", "__version__"), ("author", "__author__"),
                      ("description", "__description__"), ("icon", "__icon__")):
        value = g.get(key)
        if isinstance(value, str) and attr not in inst.__dict__:
            setattr(inst, attr, value)
    inst.enabled = True
    inst.error = None

    _MODULES[plugin_id] = g
    _INSTANCES[plugin_id] = inst
    try:
        inst.on_plugin_load()
    except Exception:
        _INSTANCES.pop(plugin_id, None)
        _MODULES.pop(plugin_id, None)
        raise
    return inst


def _construct(plugin_cls, context, plugin_id):
    """Create the plugin with its context already attached: plugins read settings in __init__."""
    inst = plugin_cls.__new__(plugin_cls)
    inst._context = context
    inst.id = plugin_id
    inst.__init__()
    inst._context = context  # an __init__ that reset attributes must not drop the context
    inst.id = plugin_id
    return inst


def _instantiate_elyx(path, plugin_id, context):
    """Elyx structured plugin: the first BasePlugin subclass of the entry module."""
    import _elyx
    from base_plugin import BasePlugin
    g = _elyx.load(path, plugin_id)
    meta = _elyx._ENVIRONMENTS.get(plugin_id, {}).get("metainfo", {})
    plugin_cls = next((v for v in g.values()
                       if isinstance(v, type) and issubclass(v, BasePlugin) and v is not BasePlugin
                       and getattr(v, "__module__", None) == g.get("__name__")), None)
    if plugin_cls is None:
        _elyx.unload(plugin_id)
        raise RuntimeError("No BasePlugin subclass found in the Elyx entry module")
    inst = _construct(plugin_cls, context, plugin_id)
    inst.name = str(meta.get("name") or plugin_id)
    for attr in ("version", "author", "description", "icon"):
        if meta.get(attr) is not None and attr not in inst.__dict__:
            setattr(inst, attr, str(meta[attr]))
    inst.enabled = True
    inst.error = None
    _MODULES[plugin_id] = g
    _INSTANCES[plugin_id] = inst
    try:
        inst.on_plugin_load()
    except Exception:
        _INSTANCES.pop(plugin_id, None)
        _MODULES.pop(plugin_id, None)
        _elyx.unload(plugin_id)
        raise
    return inst


def unload(plugin_id):
    import sys
    inst = _INSTANCES.get(plugin_id)
    if inst is not None:
        try:
            inst.on_plugin_unload()
        except Exception:
            traceback.print_exc()
        try:
            inst.enabled = False
        except Exception:
            pass
    _INSTANCES.pop(plugin_id, None)
    _SETTINGS.pop(plugin_id, None)
    try:
        from log_utils import reset_plugin_logger
        reset_plugin_logger(plugin_id)
    except Exception:
        traceback.print_exc()
    for module_name, owner in (("file_utils", "FilesController"), ("intents", "IntentsManager")):
        import sys as _sys
        module = _sys.modules.get(module_name)
        registry = getattr(module, owner, None) if module is not None else None
        if registry is not None:
            try:
                registry._remove_plugin(plugin_id)
            except Exception:
                traceback.print_exc()
    for token, (owner, _items) in list(_SETTINGS_SCREENS.items()):
        if owner == plugin_id:
            _SETTINGS_SCREENS.pop(token, None)
    g = _MODULES.pop(plugin_id, None)
    _release_class_proxies(g)
    if "_elyx" in sys.modules:
        try:
            sys.modules["_elyx"].unload(plugin_id)
        except Exception:
            traceback.print_exc()
    module = sys.modules.get(str(plugin_id))
    if g is not None and module is not None and getattr(module, "__dict__", None) is g:
        sys.modules.pop(str(plugin_id), None)


def _release_class_proxies(g):
    """Detach generated Java objects of the plugin's extera_utils.classes types (jrelease)."""
    if not g:
        return
    try:
        from extera_utils.classes import Base
    except Exception:
        return
    for value in list(g.values()):
        if isinstance(value, type) and issubclass(value, Base) and "_jbinding" in value.__dict__:
            try:
                value.release_all()
            except Exception:
                traceback.print_exc()


def plugin_id_for_globals(g):
    """Plugin id whose module globals are ``g`` (used by log_utils.get_logger), or None."""
    for plugin_id, module_globals in _MODULES.items():
        if module_globals is g:
            return plugin_id
    elyx_id = g.get("__elyx_id__") if isinstance(g, dict) else None
    if elyx_id is not None:
        return elyx_id
    import sys
    for plugin_id in list(_MODULES) + [k for k, m in list(sys.modules.items())
                                       if getattr(m, "__zasto_stamp__", None) is not None]:
        module = sys.modules.get(str(plugin_id))
        if module is not None and getattr(module, "__dict__", None) is g:
            return plugin_id
    return None


def get_instance(plugin_id):
    return _INSTANCES.get(plugin_id)


def is_loaded(plugin_id):
    return plugin_id in _INSTANCES


# ------------------------------------------------------------------ settings bridge

def _settings_model(inst, items):
    from java.util import ArrayList, HashMap

    out = ArrayList()
    for idx, item in enumerate(items):
        try:
            model = item.to_model(inst, idx)
        except Exception:
            traceback.print_exc()
            continue
        if not isinstance(model, dict):
            continue  # skip a malformed row instead of blanking the whole screen
        row = HashMap()
        for key, value in model.items():
            row.put(key, value)
        out.add(row)
    return out


def get_settings_model(plugin_id):
    """Return a java.util.List<HashMap<String,Object>> describing the root settings rows."""
    from java.util import ArrayList

    inst = _INSTANCES.get(plugin_id)
    if inst is None:
        return ArrayList()
    try:
        items = list(inst.create_settings() or [])
    except Exception:
        traceback.print_exc()
        return ArrayList()
    _SETTINGS[plugin_id] = items
    return _settings_model(inst, items)


def get_settings_model_for_screen(plugin_id, screen_token):
    from java.util import ArrayList

    inst = _INSTANCES.get(plugin_id)
    screen = _SETTINGS_SCREENS.get(str(screen_token))
    if inst is None or screen is None or screen[0] != plugin_id:
        return ArrayList()
    return _settings_model(inst, screen[1])


def create_sub_settings(plugin_id, screen_token, index):
    """Materialize a Text.create_sub_fragment factory and return its screen identity."""
    global _SETTINGS_SCREEN_COUNTER
    from java.util import HashMap

    result = HashMap()
    inst = _INSTANCES.get(plugin_id)
    if inst is None:
        return result
    if screen_token:
        screen = _SETTINGS_SCREENS.get(str(screen_token))
        items = screen[1] if screen is not None and screen[0] == plugin_id else []
    else:
        items = _SETTINGS.get(plugin_id, [])
    if not (0 <= index < len(items)):
        return result
    item = items[index]
    factory = getattr(item, "create_sub_fragment", None)
    if factory is None:
        return result
    try:
        nested_items = list(factory() or [])
    except Exception:
        _report_click_error(plugin_id, "create_sub_fragment")
        return result
    _SETTINGS_SCREEN_COUNTER += 1
    token = f"{plugin_id}:{_SETTINGS_SCREEN_COUNTER}"
    _SETTINGS_SCREENS[token] = (plugin_id, nested_items)
    result.put("token", token)
    result.put("title", str(getattr(item, "text", "")))
    return result


def _items_for_screen(plugin_id, screen_token):
    if screen_token:
        screen = _SETTINGS_SCREENS.get(str(screen_token))
        return screen[1] if screen is not None and screen[0] == plugin_id else []
    return _SETTINGS.get(plugin_id, [])


def on_setting_change(plugin_id, key, value, screen_token=None):
    inst = _INSTANCES.get(plugin_id)
    if inst is None:
        return
    try:
        inst.set_setting(key, value)
    except Exception:
        traceback.print_exc()
    for item in _items_for_screen(plugin_id, screen_token):
        if getattr(item, "key", None) == key:
            callback = getattr(item, "on_change", None)
            if callback is not None:
                try:
                    callback(value)
                except Exception:
                    _report_click_error(plugin_id, "on_change")
            break


def on_setting_click(plugin_id, index, view=None, screen_token=None):
    items = _items_for_screen(plugin_id, screen_token)
    if 0 <= index < len(items):
        callback = getattr(items[index], "on_click", None)
        if callback is not None:
            try:
                callback(view)
            except Exception:
                _report_click_error(plugin_id, "on_click")


def on_setting_long_click(plugin_id, index, view=None, screen_token=None):
    """Run a row's on_long_click(view); True unless the callback explicitly returned False."""
    items = _items_for_screen(plugin_id, screen_token)
    if not 0 <= index < len(items):
        return False
    callback = getattr(items[index], "on_long_click", None)
    if callback is None:
        return False
    try:
        return callback(view) is not False
    except Exception:
        _report_click_error(plugin_id, "on_long_click")
        return True


# ------------------------------------------------------------------ request hook bridge

def _account_scope(account):
    """Hooks run with the account they handle in scope (client_utils.get_hook_account())."""
    try:
        from client_utils import account_scope
        return account_scope(account)
    except Exception:
        import contextlib
        return contextlib.nullcontext()


def _by_priority(instances, priority_of):
    """Stable order: higher priority first, then load order."""
    ranked = []
    for order, inst in enumerate(instances):
        try:
            priority = int(priority_of(inst) or 0)
        except Exception:
            priority = 0
        ranked.append((-priority, order, inst))
    ranked.sort(key=lambda row: (row[0], row[1]))
    return [row[2] for row in ranked]


def _update_filter(inst, name):
    """False when the plugin registered update names via add_hook() and ``name`` is not one of
    them. Plugins that registered only request names (or nothing) keep receiving every update."""
    entries = [e for e in (inst.__dict__.get("_request_hooks") or ())
               if str(e[0]).startswith(("TL_update", "update", "Update"))]
    if not entries:
        return True
    for entry in entries:
        if (entry[1] and entry[0] in str(name)) or entry[0] == str(name):
            return True
    return False


def _copy_public_fields(target, source):
    """Copy public non-final instance fields (HookResult.params given as a new object)."""
    try:
        from java.lang.reflect import Modifier
        for field in target.getClass().getFields():
            mods = field.getModifiers()
            if Modifier.isStatic(mods) or Modifier.isFinal(mods):
                continue
            try:
                field.set(target, field.get(source))
            except Exception:
                pass
    except Exception:
        traceback.print_exc()


def has_request_hooks():
    from base_plugin import BasePlugin
    for inst in _INSTANCES.values():
        if getattr(inst, "_request_hooks", None):
            return True
        if type(inst).post_request_hook is not BasePlugin.post_request_hook:
            return True
        if type(inst).pre_request_hook is not BasePlugin.pre_request_hook:
            return True
    return False


def has_send_message_hooks():
    from base_plugin import BasePlugin
    for inst in _INSTANCES.values():
        if getattr(inst, "_send_message_hook", False):
            return True
        if type(inst).on_send_message_hook is not BasePlugin.on_send_message_hook:
            return True
    return False


def dispatch_pre_request(req_name, account, request):
    """Run pre_request_hook for interested plugins. Returns the (possibly modified) request, or CANCEL_SENTINEL."""
    from base_plugin import BasePlugin, HookStrategy
    current = request
    for inst in _by_priority(list(_INSTANCES.values()), lambda i: i._hook_priority(req_name)):
        try:
            matches = inst._matches_request(req_name)
        except Exception:
            matches = None
        if matches is False:
            continue
        if matches is None and type(inst).pre_request_hook is BasePlugin.pre_request_hook:
            continue
        try:
            with _account_scope(account):
                result = inst.pre_request_hook(req_name, account, current)
        except Exception:
            traceback.print_exc()
            continue
        if result is None:
            continue
        strategy = getattr(result, "strategy", HookStrategy.DEFAULT)
        if strategy == HookStrategy.CANCEL:
            return CANCEL_SENTINEL
        if strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
            new_request = getattr(result, "request", None)
            if new_request is not None:
                current = new_request
            if strategy == HookStrategy.MODIFY_FINAL:
                break
    return current


def dispatch_on_send_message(account, params):
    """Run on_send_message_hook. Plugins mutate params in place (MODIFY); returns True to CANCEL the send."""
    from base_plugin import BasePlugin, HookStrategy
    instances = _by_priority(list(_INSTANCES.values()),
                             lambda i: getattr(i, "_send_message_priority", 0))
    for inst in instances:
        if not getattr(inst, "_send_message_hook", False) \
                and type(inst).on_send_message_hook is BasePlugin.on_send_message_hook:
            continue
        try:
            with _account_scope(account):
                result = inst.on_send_message_hook(account, params)
        except Exception:
            traceback.print_exc()
            continue
        if result is None:
            continue
        strategy = getattr(result, "strategy", HookStrategy.DEFAULT)
        if strategy == HookStrategy.CANCEL:
            return True
        if strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
            new_params = getattr(result, "params", None)
            if new_params is not None and new_params is not params:
                _copy_public_fields(params, new_params)  # the host keeps its own object
        if strategy == HookStrategy.MODIFY_FINAL:
            break
    return False


def has_update_hooks():
    from base_plugin import BasePlugin
    for inst in _INSTANCES.values():
        if type(inst).on_update_hook is not BasePlugin.on_update_hook:
            return True
        if type(inst).on_updates_hook is not BasePlugin.on_updates_hook:
            return True
    return False


def dispatch_updates(container_name, account, updates):
    """on_updates_hook (container; CANCEL skips the whole batch) + per-update on_update_hook (observe/modify)."""
    from base_plugin import BasePlugin, HookStrategy

    # Container-level hook (CANCEL skips the batch; MODIFY replaces updates.updates).
    for inst in _by_priority(list(_INSTANCES.values()), lambda i: i._hook_priority(container_name)):
        if type(inst).on_updates_hook is BasePlugin.on_updates_hook:
            continue
        if not _update_filter(inst, container_name):
            continue
        try:
            with _account_scope(account):
                result = inst.on_updates_hook(container_name, account, updates)
        except Exception:
            traceback.print_exc()
            continue
        if result is None:
            continue
        strategy = getattr(result, "strategy", HookStrategy.DEFAULT)
        if strategy == HookStrategy.CANCEL:
            return True
        if strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
            new_updates = getattr(result, "updates", None)
            if new_updates is not None:
                try:
                    updates.updates = new_updates
                except Exception:
                    pass
            if strategy == HookStrategy.MODIFY_FINAL:
                break

    # Per-update hook. A MODIFY .update replacement is written back into the container.
    try:
        update_list = getattr(updates, "updates", None)
        items = []
        if update_list is not None:
            for i in range(update_list.size()):
                items.append(update_list.get(i))
        else:
            single = getattr(updates, "update", None)
            if single is not None:
                items = [single]
        cancelled = []
        for idx, upd in enumerate(items):
            try:
                uname = upd.getClass().getSimpleName()
            except Exception:
                continue
            current_upd = upd
            for inst in _by_priority(list(_INSTANCES.values()), lambda i: i._hook_priority(uname)):
                if type(inst).on_update_hook is BasePlugin.on_update_hook:
                    continue
                if not _update_filter(inst, uname):
                    continue
                try:
                    with _account_scope(account):
                        res = inst.on_update_hook(uname, account, current_upd)
                except Exception:
                    traceback.print_exc()
                    continue
                if res is None:
                    continue
                strat = getattr(res, "strategy", HookStrategy.DEFAULT)
                if strat == HookStrategy.CANCEL:
                    cancelled.append(idx)
                    break
                if strat in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
                    new_upd = getattr(res, "update", None)
                    if new_upd is not None:
                        current_upd = new_upd
                        try:
                            if update_list is not None:
                                update_list.set(idx, new_upd)
                            else:
                                updates.update = new_upd
                        except Exception:
                            pass
                    if strat == HookStrategy.MODIFY_FINAL:
                        break
        if cancelled:
            if update_list is None:
                return True  # the only update of a short container was cancelled
            for idx in reversed(cancelled):
                try:
                    update_list.remove(int(idx))
                except Exception:
                    traceback.print_exc()
    except Exception:
        traceback.print_exc()
    return False


def dispatch_app_event(event_type):
    from base_plugin import BasePlugin
    for inst in list(_INSTANCES.values()):
        if type(inst).on_app_event is BasePlugin.on_app_event:
            continue
        try:
            inst.on_app_event(event_type)
        except Exception:
            traceback.print_exc()


def dispatch_post_request(req_name, account, response, error):
    """
    Run every interested plugin's post_request_hook. Returns the (possibly replaced)
    response object to deliver, or CANCEL_SENTINEL to drop it.
    """
    from base_plugin import BasePlugin, HookStrategy

    current = response
    for inst in _by_priority(list(_INSTANCES.values()), lambda i: i._hook_priority(req_name)):
        try:
            matches = inst._matches_request(req_name)
        except Exception:
            matches = None
        if matches is False:
            continue
        if matches is None and type(inst).post_request_hook is BasePlugin.post_request_hook:
            continue
        try:
            with _account_scope(account):
                result = inst.post_request_hook(req_name, account, current, error)
        except Exception:
            traceback.print_exc()
            continue
        if result is None:
            continue
        strategy = getattr(result, "strategy", HookStrategy.DEFAULT)
        if strategy == HookStrategy.CANCEL:
            return CANCEL_SENTINEL
        if strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
            new_response = getattr(result, "response", None)
            if new_response is not None:
                current = new_response
            if strategy == HookStrategy.MODIFY_FINAL:
                break
    return current


# ------------------------------------------------------------------ menu items

def get_menu_items(menu_type):
    """Java-friendly list of menu items registered for a MenuItemType (for host rendering)."""
    from java.util import ArrayList, HashMap
    out = ArrayList()
    for plugin_id, inst in list(_INSTANCES.items()):
        for idx, item in enumerate(getattr(inst, "_menu_items", None) or []):
            if getattr(item, "menu_type", None) != menu_type:
                continue
            row = HashMap()
            row.put("plugin_id", str(plugin_id))
            iid = getattr(item, "item_id", None)
            row.put("item_id", str(iid) if iid is not None else str(idx))
            row.put("text", str(getattr(item, "text", "")))
            ic = getattr(item, "icon", None)
            if ic is not None:
                row.put("icon", str(ic))
            sub = getattr(item, "subtext", None)
            if sub is not None:
                row.put("subtext", str(sub))
            try:
                row.put("priority", int(getattr(item, "priority", 0) or 0))
            except Exception:
                row.put("priority", 0)
            out.add(row)
    return out


def has_menu_items(menu_type):
    for inst in _INSTANCES.values():
        for item in getattr(inst, "_menu_items", None) or []:
            if getattr(item, "menu_type", None) == menu_type:
                return True
    return False


def remove_menu_item(plugin_id, item_id):
    """Drop a registered menu item by its id; True if something was removed."""
    inst = _INSTANCES.get(plugin_id)
    items = getattr(inst, "_menu_items", None) if inst is not None else None
    if not items:
        return False
    before = len(items)
    inst.remove_menu_item(str(item_id))
    return len(getattr(inst, "_menu_items", None) or []) < before


def invoke_menu_item(plugin_id, item_id, context=None):
    """Call a registered menu item's on_click(context_dict)."""
    inst = _INSTANCES.get(plugin_id)
    if inst is None:
        return
    ctx = {}
    if context is not None:
        try:
            for k in context.keySet():  # Java Map -> native dict
                ctx[str(k)] = context.get(k)
        except Exception:
            if isinstance(context, dict):
                ctx = context
    for idx, item in enumerate(getattr(inst, "_menu_items", None) or []):
        iid = getattr(item, "item_id", None)
        iid = str(iid) if iid is not None else str(idx)
        if iid == str(item_id):
            cb = getattr(item, "on_click", None)
            if cb is not None:
                try:
                    cb(ctx)
                except Exception:
                    _report_click_error(plugin_id, "menu_item.on_click")
            return
