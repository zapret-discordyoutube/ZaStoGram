#!/usr/bin/env python3
"""Runtime guard for the exteraGram plugin SDK modules that work without a JVM.

Runs the real Python sources against a tiny stand-in for Chaquopy's ``java`` module:
- markdown_utils / text_formatting parse Markdown the way exteraGram plugins expect;
- log_utils levels, child loggers, handlers and dump();
- the plugin loader makes installed plugins importable by __id__ (library plugins such as
  ZwyLib) and survives stray imports of debugpy.adapter submodules.
"""

from __future__ import annotations

import os
import sys
import tempfile
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHON_DIR = ROOT / "TMessagesProj/src/main/python"


def install_java_stub() -> None:
    java = types.ModuleType("java")

    def jclass(name):
        raise ImportError(name)

    def dynamic_proxy(*_interfaces):
        class Proxy:
            def __init__(self, *args, **kwargs):
                pass
        return Proxy

    java.jclass = jclass
    java.dynamic_proxy = dynamic_proxy
    java.cast = lambda _t, value: value
    sys.modules["java"] = java
    for name in ("org", "org.telegram", "org.telegram.messenger"):
        sys.modules[name] = types.ModuleType(name)
    sys.modules["org.telegram.messenger"].ApplicationLoader = None


def entities(parsed):
    return [(e.type.name, e.offset, e.length) for e in parsed.entities]


def check_markdown(errors: list[str]) -> None:
    from markdown_utils import parse_markdown

    cases = {
        "*b* _i_ __u__ ~s~ ||p||": ("b i u s p", [("BOLD", 0, 1), ("ITALIC", 2, 1), ("UNDERLINE", 4, 1),
                                                  ("STRIKETHROUGH", 6, 1), ("SPOILER", 8, 1)]),
        "`snake_case_name` and 2 * 3 * 4": ("snake_case_name and 2 * 3 * 4", [("CODE", 0, 15)]),
        "snake_case_name": ("snake_case_name", []),
        "```python\nprint(1)\n```": ("print(1)", [("PRE", 0, 8)]),
        "> a\n> b\nnext": ("a\nb\nnext", [("BLOCKQUOTE", 0, 3)]),
        "hi 😀 *x*": ("hi 😀 x", [("BOLD", 6, 1)]),
        "unclosed *bold": ("unclosed *bold", []),
        "esc \\*not\\*": ("esc *not*", []),
        "***bi***": ("bi", [("BOLD", 0, 2), ("ITALIC", 0, 2)]),
    }
    for source, (text, expected) in cases.items():
        parsed = parse_markdown(source)
        if parsed.text != text or entities(parsed) != expected:
            errors.append(f"parse_markdown({source!r}) -> {parsed.text!r} {entities(parsed)}, "
                          f"expected {text!r} {expected}")

    link = parse_markdown("[x](https://e.com/a_(b))").entities[0]
    if link.type.name != "TEXT_LINK" or link.url != "https://e.com/a_(b)":
        errors.append("Markdown links must keep balanced parentheses in the URL")
    emoji = parse_markdown("[😀](5368324170671202286)").entities[0]
    if emoji.type.name != "CUSTOM_EMOJI" or emoji.document_id != 5368324170671202286:
        errors.append("[emoji](document_id) must produce a CUSTOM_EMOJI entity")
    if not hasattr(link, "to_tlrpc_object"):
        errors.append("RawEntity must expose to_tlrpc_object() for markdown_utils plugins")

    from extera_utils.text_formatting import _parse_html
    text, html_entities = _parse_html('<emoji id="abc">y</emoji> <b>open')
    if text != "y open" or [(e.type.name, e.offset, e.length) for e in html_entities] != [("BOLD", 2, 4)]:
        errors.append(f"HTML parser must ignore bad emoji ids and close open tags, got {text!r} {html_entities}")


def check_logging(errors: list[str]) -> None:
    import log_utils

    records = []
    logger = log_utils.Logger("plugin.test")
    logger.level = "debug"
    logger.add_handler(records.append)
    child = logger.child("net")
    child.info("hello %s", "world")
    logger.verbose("hidden")
    if [r.message for r in records] != ["hello world"] or records[0].tag != "plugin.test.net":
        errors.append(f"log_utils child/handler/level contract broken: {records}")
    elif not (records[0].location or "").endswith(":check_logging:" + str(records[0].location).rsplit(":", 1)[-1]):
        errors.append(f"log location must point at the caller, got {records[0].location}")
    if "'a': 'x'" not in log_utils.dump({"a": "x"}):
        errors.append("log_utils.dump must render dict entries")
    with logger.timed("block"):
        pass
    if not records[-1].message.startswith("block took"):
        errors.append("Logger.timed must log '<label> took N ms'")


def check_loader(errors: list[str]) -> None:
    import file_utils
    import _plugin_loader

    with tempfile.TemporaryDirectory() as tmp:
        file_utils.get_plugins_dir = lambda: tmp
        Path(tmp, "libplug.plugin").write_text(
            '__id__ = "libplug"\n__name__ = "Lib"\nVALUE = 42\n'
            "from base_plugin import BasePlugin\nclass Lib(BasePlugin):\n    pass\n",
            encoding="utf-8")
        user = Path(tmp, "userplug.plugin")
        user.write_text(
            '__id__ = "userplug"\n__name__ = "User"\nimport libplug\n'
            "from base_plugin import BasePlugin\n"
            "class User(BasePlugin):\n"
            "    def on_plugin_load(self):\n"
            "        self.value = libplug.VALUE\n",
            encoding="utf-8")

        class Context:
            def log(self, _msg):
                pass

        _plugin_loader._install_plugin_finder()
        import _plugin_requirements
        _plugin_requirements.ensure_requirements = lambda _path: None
        inst = _plugin_loader.instantiate(str(user), "userplug", Context())
        if getattr(inst, "value", None) != 42 or inst.name != "User":
            errors.append("plugins must be importable by __id__ (import libplug) and keep __name__")
        lib = _plugin_loader.instantiate(str(Path(tmp, "libplug.plugin")), "libplug", Context())
        if sys.modules.get("libplug") is None or lib.name != "Lib":
            errors.append("a library plugin imported first must be reused when it loads itself")
        _plugin_loader.unload("userplug")
        _plugin_loader.unload("libplug")
        if "libplug" in sys.modules:
            errors.append("unload must drop the plugin module from sys.modules")

    if ("debugpy.adapter", "debugpy.adapter.clients") not in _plugin_loader._CYCLE_ENTRY_MODULES:
        errors.append("loader must prime debugpy.adapter before plugins import its submodules")


def check_elyx(errors: list[str]) -> None:
    import json
    import _plugin_loader

    class Context:
        def __init__(self, plugin_id):
            self.values = {}

        def getSetting(self, key, default):
            return self.values.get(key, default)

        def setSetting(self, key, value):
            self.values[key] = value

        def log(self, _msg):
            pass

    sys.modules["org.telegram.plugins"] = types.ModuleType("org.telegram.plugins")
    sys.modules["org.telegram.plugins"].PluginContext = Context

    with tempfile.TemporaryDirectory() as tmp:
        reports = {}
        for plugin_id in ("elyx_one", "elyx_two"):
            root = Path(tmp, plugin_id)
            (root / "src" / "features").mkdir(parents=True)
            (root / "locales").mkdir()
            (root / "refmap.json").write_text(json.dumps(
                {"metainfo": "meta.json", "main": "src/main.py", "strings": "locales"}), encoding="utf-8")
            (root / "meta.json").write_text(json.dumps(
                {"id": plugin_id, "name": "N " + plugin_id, "description": "{desc}", "version": "2.0"}),
                encoding="utf-8")
            (root / "locales" / "strings_en.json").write_text(json.dumps(
                {"desc": "D " + plugin_id, "hi": "Hi {name}"}), encoding="utf-8")
            (root / "src" / "helpers.py").write_text(f"WHO = {plugin_id!r}\n", encoding="utf-8")
            (root / "src" / "features" / "__init__.py").write_text("", encoding="utf-8")
            (root / "src" / "features" / "links.py").write_text(
                "from helpers import WHO\nVALUE = WHO + '-links'\n", encoding="utf-8")
            (root / "src" / "config.json").write_text('{"limits": {"size": 5}}', encoding="utf-8")
            (root / "src" / "main.py").write_text(
                "from base_plugin import BasePlugin\n"
                "from helpers import WHO\n"
                "from .features.links import VALUE\n"
                "import config\n"
                "from elyx import strings, metainfo, settings\n"
                "class P(BasePlugin):\n"
                "    def on_plugin_load(self):\n"
                "        settings['k'] = 1\n"
                "        self.report = (WHO, VALUE, config['limits'].size, strings('hi', name='x'),\n"
                "                       metainfo['description'], self.get_setting('k'))\n",
                encoding="utf-8")
            inst = _plugin_loader.instantiate(str(root), plugin_id, Context(plugin_id))
            reports[plugin_id] = (inst.name, inst.report)
        expected = {pid: ("N " + pid, (pid, pid + "-links", 5, "Hi x", "D " + pid, None))
                    for pid in ("elyx_one", "elyx_two")}
        # settings[...] writes through plugin_settings to the plugin's own store (a new context
        # in this stub), so only check that the call itself works.
        got = {pid: (name, report[:5] + (None,)) for pid, (name, report) in reports.items()}
        if got != expected:
            errors.append(f"Elyx plugins must load isolated local modules, data modules and strings: {reports}")
        _plugin_loader.unload("elyx_one")
        if any(name.startswith("ElyxPlugins.elyx_one") for name in sys.modules):
            errors.append("unloading an Elyx plugin must purge its ElyxPlugins.<id> modules")
        _plugin_loader.unload("elyx_two")


def check_intents(errors: list[str]) -> None:
    from intents import IntentsManager

    class Data:
        def __init__(self, url):
            self.url = url

        def toString(self):
            return self.url

    class Intent:
        def __init__(self, url):
            self.url = url

        def getAction(self):
            return "android.intent.action.VIEW"

        def getFlags(self):
            return 0

        def getType(self):
            return None

        def getCategories(self):
            return None

        def getData(self):
            return Data(self.url)

    IntentsManager._ensure_hook = classmethod(lambda cls: None)
    seen = []
    handle = IntentsManager.new_global_before_handler(
        lambda id: seen.append(id) or True, scheme="tg", host="chat", required_path_args_names=["id"])
    IntentsManager.new_global_before_handler(
        lambda user_id: seen.append(user_id), scheme="tg", host="profile", path="user/{user_id}")
    stopped = IntentsManager._run("before", Intent("tg://chat?id=5"))
    IntentsManager._run("before", Intent("tg://chat"))
    IntentsManager._run("before", Intent("tg://profile/user/7"))
    if not stopped or seen != ["5", "7"]:
        errors.append(f"IntentsManager filters/arguments/stop contract broken: stopped={stopped} seen={seen}")
    if not handle.unhandle() or handle.unhandle():
        errors.append("HandlerHandle.unhandle must remove once")


def check_class_proxies(errors: list[str]) -> None:
    """extera_utils.classes routing with a fake Java bridge: overrides, overloads, super(),
    jfield accessors, constructor hooks, from_java and jrelease."""
    import extera_utils.classes as C

    class Method:
        def __init__(self, name, params):
            self.name, self.params = name, params

    methods = {"onMeasure": [Method("onMeasure", ("int", "int"))],
               "add": [Method("add", ("java.lang.Object",)), Method("add", ("int", "java.lang.Object"))],
               "isShadow": [Method("isShadow", ())]}

    class Parent:
        @staticmethod
        def isInterface():
            return False

        def onMeasure(self, w, h):
            return f"parent{w}{h}"

        def add(self, value):
            return f"parent:{value}"

    class JavaObject:
        def __init__(self, peer):
            self.peer, self.released = peer, False

        def call(self, name, *args):
            for m in methods[name]:
                if len(m.params) == len(args):
                    return self.peer._jdispatch(f"{name}({','.join(m.params)})", list(args))

    class Bridge:
        findMethods = staticmethod(lambda base, ifaces, name, params: [
            m for m in methods.get(name, []) if params is None or tuple(params) == m.params])
        methodKey = staticmethod(lambda m: f"{m.name}({','.join(m.params)})")
        abstractMethods = staticmethod(lambda base, ifaces: [])
        newInstance = staticmethod(lambda base, ifaces, ms, args, peer: JavaObject(peer))
        peerOf = staticmethod(lambda obj: None if obj.released else obj.peer)
        isProxy = staticmethod(lambda obj: True)
        isReleased = staticmethod(lambda obj: obj.released)
        attach = staticmethod(lambda obj, peer: setattr(obj, "peer", peer))
        release = staticmethod(lambda obj: setattr(obj, "released", True))

        @staticmethod
        def callSuper(obj, candidates, args):
            for m in candidates:
                if len(m.params) == len(args):
                    return getattr(Parent(), m.name)(*args)

    java = sys.modules["java"]
    java.jarray = lambda _t: (lambda items: list(items))
    real_jclass = java.jclass
    java.jclass = lambda name: type(name, (), {}) if name == "java.lang.reflect.Method" else real_jclass(name)
    C._bridge = lambda: Bridge
    C._as_class = lambda value: value
    C._type_class = lambda value: value
    C._class_array = list
    C._object_array = list

    calls = []

    @C.java_subclass(Parent)
    class Cell(C.Base):
        shadow = C.jfield("boolean", default=True, methods=[C.jgetmethod("isShadow")])

        @C.jconstructor()
        def post(self, ctx):
            calls.append(ctx)

        @C.joverride()
        def onMeasure(self, w, h):
            return "child+" + super().onMeasure(w, h)

        @C.joverload("add", ["java.lang.Object"])
        def add_one(self, value):
            return "child+" + super().add_one(value)

    cell = Cell.new_instance("ctx")
    got = (cell.java.call("onMeasure", 1, 2), cell.java.call("add", "x"), cell.java.call("isShadow"), calls)
    if got != ("child+parent12", "child+parent:x", True, ["ctx"]):
        errors.append(f"class proxy override/super/jfield/jconstructor routing broken: {got}")
    if Cell.from_java(cell.java) is not cell:
        errors.append("from_java must return the existing peer")
    cell.jrelease()
    if Cell.from_java(cell.java) is not None:
        errors.append("from_java must return None after jrelease()")


def main() -> int:
    sys.path.insert(0, str(PYTHON_DIR))
    install_java_stub()
    errors: list[str] = []
    for check in (check_markdown, check_logging, check_loader, check_elyx, check_intents, check_class_proxies):
        try:
            check(errors)
        except Exception as exc:  # report, do not crash the guard run
            import traceback
            errors.append(f"{check.__name__} crashed: {exc}\n{traceback.format_exc()}")
    if errors:
        print("Plugin SDK runtime check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("Plugin SDK runtime check passed")
    return 0


if __name__ == "__main__":
    os.environ.setdefault("PYTHONDONTWRITEBYTECODE", "1")
    sys.dont_write_bytecode = True
    sys.exit(main())
