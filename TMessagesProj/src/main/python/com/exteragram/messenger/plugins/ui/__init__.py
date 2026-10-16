"""Java class shims for exteraGram plugin UI imports."""

from java import jclass


PluginSettingsActivity = jclass("com.exteragram.messenger.plugins.ui.PluginSettingsActivity")

from com import java_getattr

__getattr__ = java_getattr(__name__)
