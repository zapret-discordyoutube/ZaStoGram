"""exteraGram compatibility namespace."""

from com import java_getattr

__getattr__ = java_getattr(__name__)
