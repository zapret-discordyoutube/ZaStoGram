"""Java class shim for exteraGram's delegate-backed fragment."""

from java import jclass


UniversalFragment = jclass(
    "com.exteragram.messenger.plugins.ui.components.templates.UniversalFragment"
)

from com import java_getattr

__getattr__ = java_getattr(__name__)
