"""Java class shims for exteraGram plugin-list UI components."""

from java import jclass


PluginCell = jclass("com.exteragram.messenger.plugins.ui.components.PluginCell")
PluginCellDelegate = jclass("com.exteragram.messenger.plugins.ui.components.PluginCellDelegate")

from com import java_getattr

__getattr__ = java_getattr(__name__)
