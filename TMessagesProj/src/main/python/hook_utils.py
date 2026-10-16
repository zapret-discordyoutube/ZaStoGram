"""hook_utils — reflection helpers for classes and private fields."""

from org.telegram.plugins import PluginUtils


def find_class(name):
    """Load a java.lang.Class by fully-qualified name using the app class loader, or None."""
    return PluginUtils.findClass(name)


def get_private_field(obj, name):
    """Read a (possibly private) field by name, walking up the superclass chain. None on miss."""
    return PluginUtils.getPrivateField(obj, name)


def set_private_field(obj, name, value):
    """Write a (possibly private) field by name. Returns True on success."""
    return PluginUtils.setPrivateField(obj, name, value)


def get_static_private_field(clazz, name):
    """Read a static field (private or not) of a java.lang.Class or class name."""
    if isinstance(clazz, str):
        clazz = find_class(clazz)
    return PluginUtils.getPrivateField(clazz, name)


def set_static_private_field(clazz, name, value):
    if isinstance(clazz, str):
        clazz = find_class(clazz)
    return PluginUtils.setPrivateField(clazz, name, value)


# Short names used by some community plugins.
get_field = get_private_field
set_field = set_private_field
get_static_field = get_static_private_field
set_static_field = set_static_private_field
