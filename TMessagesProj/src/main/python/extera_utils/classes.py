"""
extera_utils.classes — Java subclasses implemented in Python (exteraGram class-proxy API).

    @java_subclass(FrameLayout)
    class MyCell(Base):
        counter = jfield("int", default=0)

        @joverride()
        def onMeasure(self, w, h):
            super().onMeasure(w, h)

    cell = MyCell.new_instance(context)   # Python peer
    parent.addView(cell.java)             # the generated Java object

Classes are generated at runtime with DexMaker (org.telegram.plugins.ClassProxy). Only the
methods marked with @joverride/@joverload (and abstract ones) call into Python; ``super()`` inside
an override runs the parent Java implementation. Attribute lookups the peer can't answer fall
through to the Java instance (``self.getContext()``).

Limits compared with exteraGram's own generator: @jmethod methods and jfield values live on the
Python peer (Java code can't see them by name), jgetmethod/jsetmethod only work for methods the
parent already declares, MVEL bodies and @jclassbuilder are not supported, and the Chaquopy-fork
modifiers (.JA, .JIR, ...) do not exist.
"""

import threading
import traceback
import weakref

__all__ = [
    "Base", "java_subclass", "joverride", "joverload", "jmethod", "jMVELmethod", "jMVELoverride",
    "jmvelmethod", "jmveloverride", "jclassbuilder", "jfield", "jgetmethod", "jsetmethod",
    "jconstructor", "jpreconstructor", "PyObj",
]

_PRIMITIVES = {"int", "long", "short", "byte", "char", "float", "double", "boolean", "void"}
_lock = threading.RLock()


def _bridge():
    from java import jclass
    return jclass("org.telegram.plugins.ClassProxy")


def _log_error(where):
    try:
        from log_utils import Logger
        Logger("pysdk").error("extera_utils.classes: %s\n%s", where, traceback.format_exc())
    except Exception:
        traceback.print_exc()


def _type_class(spec):
    """'int' / 'java.lang.String' / jclass(...) / java.lang.Class -> java.lang.Class."""
    from java import jclass
    if isinstance(spec, str):
        name = spec.strip()
        if name in _PRIMITIVES:
            boxes = {"int": "Integer", "long": "Long", "short": "Short", "byte": "Byte",
                     "char": "Character", "float": "Float", "double": "Double",
                     "boolean": "Boolean", "void": "Void"}
            return jclass("java.lang." + boxes[name]).TYPE
        loader = jclass("org.telegram.plugins.ClassProxy").getClass().getClassLoader()
        return jclass("java.lang.Class").forName(name, False, loader)
    return _as_class(spec)


def _as_class(value):
    """A jclass() proxy or a java.lang.Class instance -> java.lang.Class.

    Chaquopy spells Java's ``Foo.class`` as ``Foo.getClass()`` on the class proxy."""
    from java import jclass
    try:
        if isinstance(value, jclass("java.lang.Class")):
            return value
    except TypeError:
        pass
    if isinstance(value, str):
        return _type_class(value)
    return value.getClass()


def _class_array(classes):
    from java import jarray, jclass
    return jarray(jclass("java.lang.Class"))(list(classes))


def _object_array(values):
    from java import jarray, jclass
    return jarray(jclass("java.lang.Object"))(list(values))


# ---------------------------------------------------------------------- declarations

class _Override:
    def __init__(self, fn, java_name, arg_types):
        self.fn = fn
        self.java_name = java_name
        self.arg_types = arg_types


def joverride(name=None, arg_types=None, *more):
    """@joverride() / @joverride("javaName") / @joverride("javaName", ["int"])."""
    if callable(name) and arg_types is None:
        fn = name
        fn._jover = _Override(fn, fn.__name__, None)
        return fn

    def deco(fn):
        fn._jover = _Override(fn, name or fn.__name__, list(arg_types) if arg_types is not None else None)
        return fn
    return deco


def joverload(name, arg_types):
    return joverride(name, arg_types)


def jmethod(*args, **kwargs):
    """New Java method. Here it stays a Python method on the peer (Java can't call it by name)."""
    if len(args) == 1 and callable(args[0]) and not kwargs:
        return args[0]

    def deco(fn):
        fn._jmethod = (args, kwargs)
        return fn
    return deco


def jconstructor(arg_types=None):
    def deco(fn):
        fn._jconstructor = list(arg_types) if arg_types is not None else None
        return fn
    if callable(arg_types):
        fn, arg_types = arg_types, None
        return deco(fn)
    return deco


def jpreconstructor(arg_types=None):
    def deco(fn):
        fn._jpreconstructor = list(arg_types) if arg_types is not None else None
        return fn
    if callable(arg_types):
        fn, arg_types = arg_types, None
        return deco(fn)
    return deco


def jclassbuilder(*args, **kwargs):
    """DexMaker builder hook: accepted for compatibility, not called."""
    if len(args) == 1 and callable(args[0]) and not kwargs:
        return args[0]
    return lambda fn: fn


class _Accessor:
    def __init__(self, kind, name):
        self.kind = kind
        self.name = name


def jgetmethod(name):
    return _Accessor("get", name)


def jsetmethod(name):
    return _Accessor("set", name)


class jfield:
    """A field on the managed class; the value lives on the Python peer."""

    def __init__(self, jtype="java.lang.Object", default=None, methods=None):
        self.jtype = jtype
        self.default = default
        self.methods = list(methods or [])
        self.attr = None

    def __set_name__(self, owner, name):
        self.attr = name

    def __get__(self, instance, owner=None):
        if instance is None:
            return self
        return instance.__dict__.get(self.attr, self.default)

    def __set__(self, instance, value):
        instance.__dict__[self.attr] = value


class _MVEL:
    def __init__(self, override, args, kwargs):
        self.override = override
        self.args = args
        self.kwargs = kwargs

    def __call__(self, *args, **kwargs):
        raise NotImplementedError("MVEL methods are not supported by ZaStoGram")


def jMVELmethod(*args, **kwargs):
    return _MVEL(False, args, kwargs)


def jMVELoverride(*args, **kwargs):
    return _MVEL(True, args, kwargs)


jmvelmethod = jMVELmethod
jmveloverride = jMVELoverride


# ---------------------------------------------------------------------- binding

class _Binding:
    def __init__(self, base, interfaces):
        self.base = base
        self.interfaces = list(interfaces)
        self.methods = None        # java.lang.reflect.Method[] routed to Python
        self.dispatch = {}         # "name(types)" -> python attribute name
        self.super_methods = {}    # python attribute name -> Method[] (parent implementations)
        self.peers = weakref.WeakSet()


def _resolve(cls):
    """Resolve override declarations to Java methods (once, on first use)."""
    binding = cls.__dict__.get("_jbinding")
    if binding is None:
        raise TypeError(f"{cls.__name__} is not bound to a Java class (use @java_subclass)")
    if binding.methods is not None:
        return binding
    with _lock:
        if binding.methods is not None:
            return binding
        bridge = _bridge()
        base = binding.base
        interfaces = _class_array(binding.interfaces)
        selected = {}

        def add(java_name, arg_types, python_attr):
            params = None if arg_types is None else _class_array(_type_class(t) for t in arg_types)
            found = list(bridge.findMethods(base, interfaces, java_name, params))
            if not found:
                _log_error_msg(f"{cls.__name__}: no overridable Java method {java_name}"
                               f"{'' if arg_types is None else tuple(arg_types)}")
                return
            for method in found:
                key = str(bridge.methodKey(method))
                selected[key] = method
                binding.dispatch[key] = python_attr
            binding.super_methods.setdefault(python_attr, []).extend(found)

        for klass in reversed(cls.__mro__):
            for attr, value in list(klass.__dict__.items()):
                over = getattr(value, "_jover", None)
                if isinstance(over, _Override):
                    add(over.java_name, over.arg_types, attr)
                elif isinstance(value, jfield):
                    for accessor in value.methods:
                        if isinstance(accessor, _Accessor):
                            synthetic = f"_jfield_{accessor.kind}_{value.attr}"
                            setattr(cls, synthetic, _field_accessor(value.attr, accessor.kind))
                            add(accessor.name, None, synthetic)
                elif isinstance(value, _MVEL):
                    _log_error_msg(f"{cls.__name__}.{attr}: MVEL methods are not supported; skipped")

        for method in bridge.abstractMethods(base, interfaces):
            key = str(bridge.methodKey(method))
            selected.setdefault(key, method)

        from java import jarray, jclass
        binding.methods = jarray(jclass("java.lang.reflect.Method"))(list(selected.values()))
        for attr, methods in binding.super_methods.items():
            binding.super_methods[attr] = jarray(jclass("java.lang.reflect.Method"))(methods)
        _install_super_shim(cls, binding)
        return binding


def _log_error_msg(text):
    try:
        from log_utils import Logger
        Logger("pysdk").warning("extera_utils.classes: %s", text)
    except Exception:
        print(text)


def _field_accessor(attr, kind):
    if kind == "get":
        def getter(self, *args):
            return getattr(self, attr)
        return getter

    def setter(self, value):
        setattr(self, attr, value)
    return setter


def _install_super_shim(cls, binding):
    """Put a class between ``cls`` and Base whose methods call the parent Java implementation,
    so ``super().onMeasure(...)`` / ``super().add_item(...)`` inside an override work."""
    def make(attr):
        def call_parent(self, *args):
            return _bridge().callSuper(self.java, binding.super_methods[attr], _object_array(args))
        call_parent.__name__ = attr
        return call_parent

    namespace = {attr: make(attr) for attr in binding.super_methods}
    for key in binding.dispatch:
        java_name = key.split("(", 1)[0]
        attr = binding.dispatch[key]
        namespace.setdefault(java_name, namespace[attr])
    shim = type(f"_{cls.__name__}JavaParent", (Base,), namespace)
    bases = tuple(shim if b is Base else b for b in cls.__bases__)
    if bases != cls.__bases__:
        try:
            cls.__bases__ = bases
        except TypeError:
            _log_error("cannot install super() shim for " + cls.__name__)


def java_subclass(base, *interfaces, **kwargs):
    """Bind a Base subclass to a Java base class (and optional interfaces)."""
    def deco(cls):
        cls.bind(base, *interfaces, **kwargs)
        return cls
    return deco


class Base:
    """Python peer of a generated Java object."""

    # ------------------------------------------------------------------ class API

    @classmethod
    def bind(cls, base, *interfaces, custom_name=None, methods=None, constructors=None, **_ignored):
        java_base = _as_class(base)
        java_interfaces = [_as_class(i) for i in interfaces]
        if java_base.isInterface():
            java_interfaces.insert(0, java_base)
            from java import jclass
            java_base = jclass("java.lang.Object").getClass()
        cls._jbinding = _Binding(java_base, java_interfaces)
        return cls

    @classmethod
    def java_class(cls):
        binding = _resolve(cls)
        return _bridge().proxyClass(binding.base, _class_array(binding.interfaces), binding.methods)

    @classmethod
    def new_instance(cls, *args, init_args=None, **kwargs):
        binding = _resolve(cls)
        peer = cls.__new__(cls)
        object.__setattr__(peer, "_java", None)
        java_args = list(args)
        for klass in reversed(cls.__mro__):
            for value in list(klass.__dict__.values()):
                pre = getattr(value, "_jpreconstructor", "missing")
                if pre != "missing" and (pre is None or len(pre) == len(java_args)):
                    fn = getattr(value, "__func__", value)
                    replaced = fn(cls, *java_args)
                    if replaced is not None:
                        java_args = list(replaced) if isinstance(replaced, (list, tuple)) else [replaced]
        instance = _bridge().newInstance(binding.base, _class_array(binding.interfaces), binding.methods,
                                         _object_array(java_args), peer)
        object.__setattr__(peer, "_java", instance)
        binding.peers.add(peer)

        py_args = list(init_args) if init_args is not None else list(args)
        if type(peer).__init__ is not Base.__init__:
            peer.__init__(*py_args, **kwargs)
        for klass in reversed(cls.__mro__):
            for attr, value in list(klass.__dict__.items()):
                types_ = getattr(value, "_jconstructor", "missing")
                if types_ != "missing" and (types_ is None or len(types_) == len(args)):
                    getattr(peer, attr)(*args)
        post = getattr(type(peer), "on_post_init", None)
        if post is not None:
            post(peer, *py_args)
        return peer

    @classmethod
    def new_java_instance(cls, *args, **kwargs):
        return cls.new_instance(*args, **kwargs).java

    @classmethod
    def from_java(cls, obj):
        if obj is None:
            return None
        if isinstance(obj, Base):
            return obj
        bridge = _bridge()
        peer = bridge.peerOf(obj)
        if peer is not None:
            return peer
        if not bridge.isProxy(obj) or bridge.isReleased(obj):
            return None
        _resolve(cls)
        peer = cls.__new__(cls)
        object.__setattr__(peer, "_java", obj)
        bridge.attach(obj, peer)
        cls.__dict__["_jbinding"].peers.add(peer)
        return peer

    @classmethod
    def release_all(cls):
        binding = cls.__dict__.get("_jbinding")
        if binding is not None:
            for peer in list(binding.peers):
                peer.jrelease()

    # ------------------------------------------------------------------ instance API

    def __init__(self, *args, **kwargs):
        pass

    @property
    def java(self):
        return self.__dict__.get("_java")

    @property
    def this(self):
        return self.__dict__.get("_java")

    def jrelease(self):
        java = self.__dict__.get("_java")
        if java is None:
            return
        try:
            self.on_release()
        except Exception:
            _log_error("on_release")
        _bridge().release(java)

    def on_release(self):
        pass

    def _jdispatch(self, key, args):
        binding = None
        for klass in type(self).__mro__:
            binding = klass.__dict__.get("_jbinding")
            if binding is not None:
                break
        attr = binding.dispatch.get(str(key)) if binding is not None else None
        if attr is None:
            return None  # abstract method without a Python implementation
        result = getattr(self, attr)(*list(args))
        if isinstance(result, Base):
            return result.java  # Java expects the generated object, not its Python peer
        return result

    def __getattr__(self, name):
        if name.startswith("__") or name in ("_java", "_jbinding"):
            raise AttributeError(name)
        java = self.__dict__.get("_java")
        if java is None:
            raise AttributeError(name)
        return getattr(java, name)

    def __repr__(self):
        return f"<{type(self).__name__} peer of {self.__dict__.get('_java')!r}>"


class PyObj:
    """Carries an arbitrary Python object through Java-facing APIs (as a PyObject)."""

    @staticmethod
    def create(value):
        """Python values already cross into Java as com.chaquo.python.PyObject; nothing to wrap."""
        return value
