"""
ui.settings — declarative rows for a plugin's settings screen (exteraGram-compatible).

create_settings() returns a list of these items. Each one flattens itself via to_model() into
a dict the native screen (org.telegram.ui.Plugins.PluginSettingsActivity) renders as a UItem.
Callbacks never cross into Java: the screen reports clicks by row index and the loader calls
on_change / on_click / on_long_click / create_sub_fragment on the item here.
"""


def _text(value):
    return "" if value is None else str(value)


class _Item:
    type = "item"
    key = None
    icon = None
    on_change = None
    on_click = None
    on_long_click = None
    create_sub_fragment = None
    link_alias = None

    def _base(self, index, **extra):
        model = {
            "type": self.type,
            "index": index,
            "icon": self.icon,
            "link_alias": self.link_alias,
            "has_click": self.on_click is not None,
            "has_long_click": self.on_long_click is not None,
            "has_sub_fragment": self.create_sub_fragment is not None,
        }
        model.update(extra)
        return model

    def to_model(self, plugin, index):
        return self._base(index)

    def _value(self, plugin, default):
        return plugin.get_setting(self.key, default) if plugin is not None else default


class Header(_Item):
    type = "header"

    def __init__(self, text=""):
        self.text = text

    def to_model(self, plugin, index):
        return self._base(index, text=_text(self.text))


class Divider(_Item):
    """A section separator; optional text becomes the caption under it."""
    type = "divider"

    def __init__(self, text=None):
        self.text = text

    def to_model(self, plugin, index):
        return self._base(index, text=_text(self.text))


class Switch(_Item):
    type = "switch"

    def __init__(self, key, text, default=False, subtext=None, icon=None,
                 on_change=None, on_long_click=None, link_alias=None):
        self.key = key
        self.text = text
        self.default = default
        self.subtext = subtext
        self.icon = icon
        self.on_change = on_change
        self.on_long_click = on_long_click
        self.link_alias = link_alias

    def to_model(self, plugin, index):
        return self._base(index, key=self.key, text=_text(self.text), subtext=_text(self.subtext),
                          value=bool(self._value(plugin, bool(self.default))))


class Selector(_Item):
    """Single-choice row; the persisted value is the selected option index."""
    type = "selector"

    def __init__(self, key, text, items=None, default=0, subtext=None, icon=None, on_change=None,
                 on_long_click=None, link_alias=None, options=None):
        if isinstance(items, int) and isinstance(default, (list, tuple)):
            items, default = default, items  # Selector(key, text, default, items) order
        self.key = key
        self.text = text
        self.default = default
        # 'items' is exteraGram's name; 'options' is accepted for older plugins.
        self.items = list(items if items is not None else (options or []))
        self.icon = icon
        self.on_change = on_change
        self.on_long_click = on_long_click
        self.link_alias = link_alias
        self.subtext = subtext

    def to_model(self, plugin, index):
        from java.util import ArrayList
        options = ArrayList()
        for option in self.items:
            options.add(_text(option))
        try:
            value = int(self._value(plugin, int(self.default or 0)))
        except Exception:
            value = int(self.default or 0)
        if not 0 <= value < len(self.items):
            value = max(0, min(int(self.default or 0), len(self.items) - 1))
        return self._base(index, key=self.key, text=_text(self.text), subtext=_text(self.subtext),
                          options=options, value=value)


class Input(_Item):
    """Single-line value edited in a dialog."""
    type = "input"

    def __init__(self, key, text, default="", subtext=None, icon=None,
                 on_change=None, on_long_click=None, link_alias=None):
        self.key = key
        self.text = text
        self.default = default
        self.subtext = subtext
        self.icon = icon
        self.on_change = on_change
        self.on_long_click = on_long_click
        self.link_alias = link_alias

    def to_model(self, plugin, index):
        return self._base(index, key=self.key, text=_text(self.text), subtext=_text(self.subtext),
                          value=_text(self._value(plugin, _text(self.default))))


class EditText(_Item):
    """Inline text field; mask is a regex the text must keep matching while typing."""
    type = "edittext"

    def __init__(self, key, hint="", default="", multiline=False, max_length=0, mask=None,
                 on_change=None):
        self.key = key
        self.hint = hint
        self.default = default
        self.multiline = multiline
        self.max_length = max_length
        self.mask = mask
        self.on_change = on_change

    def to_model(self, plugin, index):
        return self._base(index, key=self.key, hint=_text(self.hint),
                          value=_text(self._value(plugin, _text(self.default))),
                          multiline=bool(self.multiline), max_length=int(self.max_length or 0),
                          mask=_text(self.mask))


class Text(_Item):
    """A clickable row. on_click(view) gets the row's View; create_sub_fragment() opens a page."""
    type = "text"

    def __init__(self, text="", icon=None, subtext=None, on_click=None, on_long_click=None,
                 accent=False, red=False, create_sub_fragment=None, link_alias=None):
        self.text = text
        self.icon = icon
        self.accent = accent
        self.red = red
        self.on_click = on_click
        self.create_sub_fragment = create_sub_fragment
        self.on_long_click = on_long_click
        self.link_alias = link_alias
        self.subtext = subtext

    def to_model(self, plugin, index):
        return self._base(index, text=_text(self.text), subtext=_text(self.subtext),
                          accent=bool(self.accent), red=bool(self.red))


class Custom(_Item):
    """A row backed by a ready UItem, a plain View, or a CustomSetting.Factory."""
    type = "custom"

    def __init__(self, item=None, view=None, factory=None, factory_args=None, on_click=None,
                 on_long_click=None, create_sub_fragment=None, link_alias=None):
        self.item = item
        self.view = view
        self.factory = factory
        self.factory_args = factory_args
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.create_sub_fragment = create_sub_fragment
        self.link_alias = link_alias

    def to_model(self, plugin, index):
        factory = getattr(self.factory, "java", self.factory)  # accept the Python wrapper too
        return self._base(index, item=self.item, view=self.view, factory=factory,
                          factory_args=self.factory_args)


class _FactoryBridge:
    """What CustomSetting.PythonFactory calls; returns None where the plugin gave no callback."""

    def __init__(self, owner):
        self._owner = owner

    @staticmethod
    def _plugin(java_plugin):
        try:
            from _plugin_loader import _INSTANCES
            return _INSTANCES.get(str(java_plugin.getId()), java_plugin)
        except Exception:
            return java_plugin

    def create_view(self, context, list_view, current_account, class_guid, resources_provider):
        return self._owner.create_view_fn(context, list_view, current_account, class_guid, resources_provider)

    def bind_view(self, view, item, divider, adapter, list_view):
        if self._owner.bind_view_fn is not None:
            self._owner.bind_view_fn(view, item, divider, adapter, list_view)

    def attached_view(self, list_view, view, item):
        if self._owner.attached_view is not None:
            self._owner.attached_view(list_view, view, item)

    def equals(self, a, b):
        return None if self._owner.equals is None else bool(self._owner.equals(a, b))

    def content_equals(self, a, b):
        return None if self._owner.content_equals is None else bool(self._owner.content_equals(a, b))

    def create_item(self, plugin, setting, args):
        if self._owner.create_item is None:
            return None
        return self._owner.create_item(self._plugin(plugin), setting, args)

    def on_click(self, plugin, item, view):
        if self._owner.on_click is not None:
            self._owner.on_click(self._plugin(plugin), item, view)

    def on_long_click(self, plugin, item, view):
        if self._owner.on_long_click is None:
            return False
        return bool(self._owner.on_long_click(self._plugin(plugin), item, view))


class SimpleSettingFactory:
    """
    Custom-row factory from plain callables (exteraGram API). The Java factory lives in
    .instance.java, which is what Custom(factory=...) expects; calling the factory builds that
    Custom row directly: MyFactory(arg1, link_alias="x").
    """

    def __init__(self, create_view, bind_view=None, is_clickable=False, is_shadow=False,
                 create_item=None, on_click=None, on_long_click=None, attached_view=None,
                 equals=None, content_equals=None):
        from com.exteragram.messenger.plugins.models import CustomSetting
        self.create_view_fn = create_view
        self.bind_view_fn = bind_view
        self.create_item = create_item
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.attached_view = attached_view
        self.equals = equals
        self.content_equals = content_equals
        self._bridge = _FactoryBridge(self)
        self.java = CustomSetting.PythonFactory(self._bridge, bool(is_clickable), bool(is_shadow))
        self.instance = self

    def __call__(self, *args, **kwargs):
        return Custom(factory=self.java, factory_args=args or None, **kwargs)
