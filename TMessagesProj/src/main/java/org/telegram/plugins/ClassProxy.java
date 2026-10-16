package org.telegram.plugins;

import com.android.dx.stock.ProxyBuilder;
import com.chaquo.python.PyObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime Java subclasses implemented in Python (exteraGram's extera_utils.classes API).
 *
 * <p>Classes are generated with DexMaker's ProxyBuilder. Only the methods a plugin overrides
 * (plus abstract ones, which must exist) are routed to Python; everything else stays the
 * parent's bytecode, so a proxied View does not pay a Python call for every draw pass. While the
 * parent constructor runs the handler is still unset and overridden methods fall through to the
 * parent, matching exteraGram's construction order.
 */
public final class ClassProxy {

    /** ProxyBuilder caches generated classes by (base, interfaces, loader) but not by method set. */
    private static final Map<String, ClassLoader> loaders = new HashMap<>();

    private ClassProxy() {
    }

    // ------------------------------------------------------------------ method resolution

    /**
     * Overridable methods named {@code name} on {@code base} and {@code interfaces}. With
     * {@code paramTypes} only the exact overload is returned. Final, static and private methods
     * can't be overridden and are skipped.
     */
    public static Method[] findMethods(Class<?> base, Class<?>[] interfaces, String name, Class<?>[] paramTypes) {
        LinkedHashMap<String, Method> found = new LinkedHashMap<>();
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = base; c != null; c = c.getSuperclass()) {
            types.add(c);
        }
        if (interfaces != null) {
            types.addAll(Arrays.asList(interfaces));
        }
        for (int i = 0; i < types.size(); i++) {
            for (Class<?> extra : types.get(i).getInterfaces()) {
                if (!types.contains(extra)) {
                    types.add(extra);
                }
            }
        }
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                int mods = method.getModifiers();
                if (!method.getName().equals(name) || method.isSynthetic() || method.isBridge()
                        || Modifier.isStatic(mods) || Modifier.isFinal(mods) || Modifier.isPrivate(mods)) {
                    continue;
                }
                if (!Modifier.isPublic(mods) && !Modifier.isProtected(mods)) {
                    continue; // package-private: not overridable from a generated class
                }
                if (paramTypes != null && !Arrays.equals(method.getParameterTypes(), paramTypes)) {
                    continue;
                }
                String key = methodKey(method);
                if (!found.containsKey(key)) {
                    found.put(key, method); // most-derived declaration wins
                }
            }
        }
        return found.values().toArray(new Method[0]);
    }

    /** Abstract methods the generated class must implement (from the base and interfaces). */
    public static Method[] abstractMethods(Class<?> base, Class<?>[] interfaces) {
        LinkedHashMap<String, Method> concrete = new LinkedHashMap<>();
        LinkedHashMap<String, Method> abstracts = new LinkedHashMap<>();
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = base; c != null; c = c.getSuperclass()) {
            types.add(c);
        }
        if (interfaces != null) {
            types.addAll(Arrays.asList(interfaces));
        }
        for (int i = 0; i < types.size(); i++) {
            for (Class<?> extra : types.get(i).getInterfaces()) {
                if (!types.contains(extra)) {
                    types.add(extra);
                }
            }
        }
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                int mods = method.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isPrivate(mods) || method.isSynthetic()) {
                    continue;
                }
                String key = methodKey(method);
                if (Modifier.isAbstract(mods)) {
                    if (!concrete.containsKey(key) && !abstracts.containsKey(key)) {
                        abstracts.put(key, method);
                    }
                } else if (!concrete.containsKey(key)) {
                    concrete.put(key, method);
                }
            }
        }
        for (String key : concrete.keySet()) {
            abstracts.remove(key); // implemented further down the hierarchy (or a default method)
        }
        return abstracts.values().toArray(new Method[0]);
    }

    /** "name(type,type)" — the dispatch key shared with Python. */
    public static String methodKey(Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getName());
        }
        return sb.append(')').toString();
    }

    // ------------------------------------------------------------------ class generation

    private static File dexCache() {
        long installed = 0;
        try {
            installed = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).lastUpdateTime;
        } catch (Throwable ignore) {
        }
        // A new APK may change parent classes: never reuse proxies generated for another install.
        File dir = new File(ApplicationLoader.applicationContext.getCodeCacheDir(),
                "plugin_proxies_" + BuildVars.BUILD_VERSION_STRING + "_" + installed);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static synchronized ClassLoader loaderFor(Class<?> base, Class<?>[] interfaces, Method[] methods) {
        StringBuilder key = new StringBuilder(base.getName());
        if (interfaces != null) {
            for (Class<?> i : interfaces) {
                key.append('+').append(i.getName());
            }
        }
        String[] keys = new String[methods.length];
        for (int i = 0; i < methods.length; i++) {
            keys[i] = methodKey(methods[i]);
        }
        Arrays.sort(keys);
        key.append('|').append(String.join(";", keys));
        ClassLoader loader = loaders.get(key.toString());
        if (loader == null) {
            loader = new ClassLoader(ClassProxy.class.getClassLoader()) {
            };
            loaders.put(key.toString(), loader);
        }
        return loader;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ProxyBuilder builder(Class<?> base, Class<?>[] interfaces, Method[] methods) {
        ProxyBuilder builder = ProxyBuilder.forClass(base)
                .parentClassLoader(loaderFor(base, interfaces, methods))
                .dexCache(dexCache())
                .onlyMethods(methods);
        if (interfaces != null && interfaces.length > 0) {
            builder.implementing(interfaces);
        }
        return builder;
    }

    public static Class<?> proxyClass(Class<?> base, Class<?>[] interfaces, Method[] methods) throws Exception {
        return builder(base, interfaces, methods).buildProxyClass();
    }

    /** Instantiate the generated class with {@code args} and route its overrides to {@code peer}. */
    public static Object newInstance(Class<?> base, Class<?>[] interfaces, Method[] methods,
                                     Object[] args, PyObject peer) throws Exception {
        Object[] values = args != null ? args : new Object[0];
        Class<?> proxy = proxyClass(base, interfaces, methods);
        Constructor<?> constructor = matchConstructor(proxy, values);
        if (constructor == null) {
            throw new IllegalArgumentException("No constructor of " + base.getName()
                    + " accepts " + describe(values));
        }
        Object[] converted = convertArgs(constructor.getParameterTypes(), values);
        constructor.setAccessible(true);
        Object instance;
        try {
            instance = constructor.newInstance(converted);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : e;
        }
        ProxyBuilder.setInvocationHandler(instance, new Handler(peer));
        return instance;
    }

    /** Attach a Python peer to a generated instance created elsewhere (from_java). */
    public static void attach(Object instance, PyObject peer) {
        ProxyBuilder.setInvocationHandler(instance, new Handler(peer));
    }

    /** The Python peer of a generated instance, or null (plain object or released). */
    public static PyObject peerOf(Object instance) {
        if (instance == null || !ProxyBuilder.isProxyClass(instance.getClass())) {
            return null;
        }
        try {
            InvocationHandler handler = ProxyBuilder.getInvocationHandler(instance);
            return handler instanceof Handler ? ((Handler) handler).peer : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True after jrelease(): the object keeps a detached handler and must not get a new peer. */
    public static boolean isReleased(Object instance) {
        if (!isProxy(instance)) {
            return false;
        }
        try {
            InvocationHandler handler = ProxyBuilder.getInvocationHandler(instance);
            return handler instanceof Handler && ((Handler) handler).peer == null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isProxy(Object instance) {
        return instance != null && ProxyBuilder.isProxyClass(instance.getClass());
    }

    /** jrelease(): overridden methods behave like the parent's from now on. */
    public static void release(Object instance) {
        try {
            InvocationHandler handler = ProxyBuilder.getInvocationHandler(instance);
            if (handler instanceof Handler) {
                ((Handler) handler).peer = null;
            }
        } catch (Throwable ignore) {
        }
    }

    /** super.method(args): the parent implementation of the first candidate matching {@code args}. */
    public static Object callSuper(Object instance, Method[] candidates, Object[] args) throws Throwable {
        Object[] values = args != null ? args : new Object[0];
        for (Method method : candidates) {
            if (method.getParameterTypes().length == values.length && accepts(method.getParameterTypes(), values)) {
                if (Modifier.isAbstract(method.getModifiers())) {
                    return defaultValue(method.getReturnType());
                }
                return ProxyBuilder.callSuper(instance, method, convertArgs(method.getParameterTypes(), values));
            }
        }
        throw new IllegalArgumentException("No parent overload accepts " + describe(values));
    }

    // ------------------------------------------------------------------ dispatch

    static final class Handler implements InvocationHandler {
        volatile PyObject peer;

        Handler(PyObject peer) {
            this.peer = peer;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            PyObject target = peer;
            if (target == null) {
                return parent(proxy, method, args);
            }
            PyObject result;
            try {
                result = target.callAttr("_jdispatch", methodKey(method), args != null ? args : new Object[0]);
            } catch (Throwable t) {
                FileLog.e("[plugin] class proxy " + method.getDeclaringClass().getName() + "." + methodKey(method), t);
                return parent(proxy, method, args);
            }
            return coerce(result, method.getReturnType());
        }

        private static Object parent(Object proxy, Method method, Object[] args) throws Throwable {
            if (Modifier.isAbstract(method.getModifiers())) {
                return defaultValue(method.getReturnType());
            }
            return ProxyBuilder.callSuper(proxy, method, args != null ? args : new Object[0]);
        }
    }

    // ------------------------------------------------------------------ conversions

    private static Object coerce(PyObject result, Class<?> type) {
        if (type == void.class) {
            return null;
        }
        if (result == null) {
            return defaultValue(type);
        }
        if (type.isPrimitive()) {
            Object value = result.toJava(Object.class);
            return convert(value, type);
        }
        return result.toJava(type);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) return Boolean.FALSE;
        if (type == char.class) return '\0';
        return convert(0, type);
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null) {
            return type.isPrimitive() ? defaultValue(type) : null;
        }
        if (type == boolean.class || type == Boolean.class) {
            return value instanceof Boolean ? value : Boolean.valueOf(String.valueOf(value));
        }
        if (value instanceof Number) {
            Number n = (Number) value;
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
            if (type == float.class || type == Float.class) return n.floatValue();
            if (type == double.class || type == Double.class) return n.doubleValue();
            if (type == short.class || type == Short.class) return n.shortValue();
            if (type == byte.class || type == Byte.class) return n.byteValue();
            if (type == char.class || type == Character.class) return (char) n.intValue();
        }
        if ((type == char.class || type == Character.class) && value instanceof String && ((String) value).length() == 1) {
            return ((String) value).charAt(0);
        }
        return value;
    }

    private static boolean accepts(Class<?>[] params, Object[] values) {
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            Object v = values[i];
            if (v == null) {
                if (p.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (p.isPrimitive()) {
                if (p == boolean.class) {
                    if (!(v instanceof Boolean)) return false;
                } else if (p == char.class) {
                    if (!(v instanceof Character) && !(v instanceof String && ((String) v).length() == 1)) return false;
                } else if (!(v instanceof Number)) {
                    return false;
                }
            } else if (!p.isInstance(v)) {
                if (v instanceof Number && Number.class.isAssignableFrom(boxed(p))) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        return type;
    }

    private static Object[] convertArgs(Class<?>[] params, Object[] values) {
        Object[] out = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] == null ? null : convert(values[i], params[i]);
        }
        return out;
    }

    private static Constructor<?> matchConstructor(Class<?> proxy, Object[] values) {
        Constructor<?> fallback = null;
        for (Constructor<?> constructor : proxy.getDeclaredConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length != values.length || !accepts(params, values)) {
                continue;
            }
            boolean exact = true;
            for (int i = 0; i < params.length; i++) {
                if (values[i] != null && !boxed(params[i]).isInstance(values[i])) {
                    exact = false;
                    break;
                }
            }
            if (exact) {
                return constructor;
            }
            if (fallback == null) {
                fallback = constructor;
            }
        }
        return fallback;
    }

    private static String describe(Object[] values) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i] == null ? "null" : values[i].getClass().getName());
        }
        return sb.append(')').toString();
    }
}
