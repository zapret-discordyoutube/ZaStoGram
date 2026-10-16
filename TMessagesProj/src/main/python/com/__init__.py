"""Python namespace shim for plugins importing Java packages under ``com``.

This package shadows the Java ``com`` package, so it must forward everything it does not define:
``__getattr__`` on each shim package resolves Java classes (``from com.x import SomeClass``) and
``_JavaPackageFinder`` materialises any ``com.*`` subpackage that has no Python shim
(``from com.exteragram.messenger.utils import AppUtils``).
"""

import importlib.abc
import importlib.machinery
import sys
import types


def _java_class(package, name):
    from java import jclass
    full = f"{package}.{name}"
    try:
        return jclass(full)
    except Exception:
        pass
    # Nested class spelled with a dot: com.x.Outer.Inner -> com.x.Outer$Inner
    if "." in package:
        outer_package, outer = package.rsplit(".", 1)
        try:
            return jclass(f"{outer_package}.{outer}${name}")
        except Exception:
            pass
    raise AttributeError(f"module {package!r} has no attribute {name!r}")


def java_getattr(package):
    """Module-level __getattr__ (PEP 562) for a shim package named ``package``."""
    def __getattr__(name):
        if name.startswith("__"):
            raise AttributeError(name)
        value = _java_class(package, name)
        module = sys.modules.get(package)
        if module is not None:
            setattr(module, name, value)
        return value
    return __getattr__


class _JavaPackageLoader(importlib.abc.Loader):
    def create_module(self, spec):
        module = types.ModuleType(spec.name)
        module.__path__ = []
        module.__getattr__ = java_getattr(spec.name)
        return module

    def exec_module(self, module):
        pass


class _JavaPackageFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if not fullname.startswith("com."):
            return None
        parent = fullname.rsplit(".", 1)[0]
        if parent not in sys.modules:
            return None
        # A Java class, not a package: let ``from parent import Name`` use __getattr__ instead.
        tail = fullname.rsplit(".", 1)[1]
        if tail[:1].isupper():
            return None
        return importlib.machinery.ModuleSpec(fullname, _JavaPackageLoader(), is_package=True)


if not any(isinstance(f, _JavaPackageFinder) for f in sys.meta_path):
    sys.meta_path.append(_JavaPackageFinder())

__getattr__ = java_getattr(__name__)
