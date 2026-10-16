"""Java class shims for exteraGram plugin imports (any other class resolves via __getattr__)."""

from java import jclass

from com import java_getattr

Plugin = jclass("com.exteragram.messenger.plugins.Plugin")
PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")
PluginsConstants = jclass("com.exteragram.messenger.plugins.PluginsConstants")
PythonPluginsEngine = jclass("com.exteragram.messenger.plugins.PythonPluginsEngine")

__getattr__ = java_getattr(__name__)
