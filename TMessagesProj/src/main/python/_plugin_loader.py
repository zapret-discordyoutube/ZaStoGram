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


def instantiate(path, plugin_id, context):
    """Exec the plugin file, instantiate its BasePlugin subclass and run on_plugin_load()."""
    from base_plugin import BasePlugin
    from _plugin_requirements import ensure_requirements

    # Resolve metadata before executing imports from the plugin source. Both the
    # official list form and the legacy single-string form are supported.
    ensure_requirements(path)

    src = _read_source(path)
    g = {
        "__name__": "plugin_" + str(plugin_id),
        "__file__": str(path),
        "__builtins__": __builtins__,
    }
    code = compile(src, str(path), "exec")
    exec(code, g)

    candidates = [v for v in g.values()
                  if isinstance(v, type) and issubclass(v, BasePlugin) and v is not BasePlugin]
    if not candidates:
        raise RuntimeError("no BasePlugin subclass found in " + str(path))
    # Pick the most-derived class: drop any candidate that is a base of another candidate
    # (so an intermediate base/mixin defined before the real plugin is not chosen).
    leaves = [c for c in candidates
              if not any(c is not d and issubclass(d, c) for d in candidates)]
    pool = leaves or candidates
    # Prefer a class defined in THIS plugin file over an imported helper base.
    own = [c for c in pool if getattr(c, "__module__", None) == g.get("__name__")]
    pool = own or pool
    plugin_cls = pool[-1]  # last defined wins

    inst = plugin_cls()
    inst._context = context
    inst.id = plugin_id
    inst.name = g.get("__name__") if isinstance(g.get("__name__"), str) else plugin_id

    _MODULES[plugin_id] = g
    _INSTANCES[plugin_id] = inst
    try:
        inst.on_plugin_load()
    except Exception:
        _INSTANCES.pop(plugin_id, None)
        _MODULES.pop(plugin_id, None)
        raise
    return inst


def unload(plugin_id):
    inst = _INSTANCES.pop(plugin_id, None)
    _SETTINGS.pop(plugin_id, None)
    for token, (owner, _items) in list(_SETTINGS_SCREENS.items()):
        if owner == plugin_id:
            _SETTINGS_SCREENS.pop(token, None)
    _MODULES.pop(plugin_id, None)
    if inst is not None:
        try:
            inst.on_plugin_unload()
        except Exception:
            traceback.print_exc()


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


# ------------------------------------------------------------------ request hook bridge

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
    for inst in list(_INSTANCES.values()):
        try:
            matches = inst._matches_request(req_name)
        except Exception:
            matches = None
        if matches is False:
            continue
        if matches is None and type(inst).pre_request_hook is BasePlugin.pre_request_hook:
            continue
        try:
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
    for inst in list(_INSTANCES.values()):
        if not getattr(inst, "_send_message_hook", False) \
                and type(inst).on_send_message_hook is BasePlugin.on_send_message_hook:
            continue
        try:
            result = inst.on_send_message_hook(account, params)
        except Exception:
            traceback.print_exc()
            continue
        if result is None:
            continue
        strategy = getattr(result, "strategy", HookStrategy.DEFAULT)
        if strategy == HookStrategy.CANCEL:
            return True
        if strategy == HookStrategy.MODIFY_FINAL:
            break
        # MODIFY: params is mutated in place (same Java object) — nothing to do here.
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
    for inst in list(_INSTANCES.values()):
        if type(inst).on_updates_hook is BasePlugin.on_updates_hook:
            continue
        try:
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
        for idx, upd in enumerate(items):
            try:
                uname = upd.getClass().getSimpleName()
            except Exception:
                continue
            current_upd = upd
            for inst in list(_INSTANCES.values()):
                if type(inst).on_update_hook is BasePlugin.on_update_hook:
                    continue
                try:
                    res = inst.on_update_hook(uname, account, current_upd)
                except Exception:
                    traceback.print_exc()
                    continue
                if res is None:
                    continue
                strat = getattr(res, "strategy", HookStrategy.DEFAULT)
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
    except Exception:
        pass
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
    for inst in list(_INSTANCES.values()):
        try:
            matches = inst._matches_request(req_name)
        except Exception:
            matches = None
        if matches is False:
            continue
        if matches is None and type(inst).post_request_hook is BasePlugin.post_request_hook:
            continue
        try:
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
