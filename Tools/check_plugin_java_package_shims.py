#!/usr/bin/env python3
"""Guard Python shim packages for plugins importing com.exteragram Java classes."""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PY_ROOT = ROOT / "TMessagesProj/src/main/python"
JAVA_ROOT = ROOT / "TMessagesProj/src/main/java"

REQUIRED_FILES = {
    "com/__init__.py": [],
    "com/exteragram/__init__.py": [],
    "com/exteragram/messenger/__init__.py": [],
    "com/exteragram/messenger/plugins/__init__.py": [
        'Plugin = jclass("com.exteragram.messenger.plugins.Plugin")',
        'PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")',
    ],
    "com/exteragram/messenger/plugins/ui/__init__.py": [
        'PluginSettingsActivity = jclass("com.exteragram.messenger.plugins.ui.PluginSettingsActivity")',
    ],
    "com/exteragram/messenger/plugins/ui/components/__init__.py": [
        'PluginCell = jclass("com.exteragram.messenger.plugins.ui.components.PluginCell")',
        'PluginCellDelegate = jclass("com.exteragram.messenger.plugins.ui.components.PluginCellDelegate")',
    ],
    "com/exteragram/messenger/plugins/ui/components/templates/__init__.py": [
        '"com.exteragram.messenger.plugins.ui.components.templates.UniversalFragment"',
    ],
}

REQUIRED_JAVA_FILES = {
    "com/exteragram/messenger/plugins/PythonPluginsEngine.java": [
        "loadPlugins(Object ignored)", "reloadPluginsFromDisk()",
        "Map<String, Object> pluginInstances", "getPluginInstance(", "getAllPluginSettings(String id)",
    ],
    "com/exteragram/messenger/plugins/PluginsController.java": [
        "public static final LinkedHashMap<String, PythonPluginsEngine> engines",
        "engines.put(PluginsConstants.PYTHON", "getEngines()", "getPluginPath(String id)",
        "loadPluginSettings(String id)", "setPluginEnabled(String id, boolean enabled, Object ignored)",
    ],
    "com/exteragram/messenger/plugins/PluginsConstants.java": [
        "PYTHON = \"python\"", "APP_START = \"app_start\"", "ON_APP_EVENT", "class MenuItemTypes",
    ],
    "org/telegram/plugins/PluginsController.java": [
        "installedFileForId(String id)", "id + \".plugin\"", "id + \".py\"",
        "reloadPluginsFromDisk()",
    ],
    "com/exteragram/messenger/plugins/ui/components/PluginCell.java": [
        "class PluginCell", "class Factory", "asPlugin(Plugin plugin, PluginCellDelegate delegate)",
    ],
    "com/exteragram/messenger/plugins/ui/components/PluginCellDelegate.java": [
        "interface PluginCellDelegate",
    ],
    "com/exteragram/messenger/plugins/ui/components/templates/UniversalFragment.java": [
        "class UniversalFragment", "interface UniversalFragmentDelegate",
    ],
    "org/telegram/messenger/NotificationCenter.java": [
        "pluginsUpdated = pluginsDidLoad",
    ],
}


def main() -> int:
    errors: list[str] = []
    for relative, literals in REQUIRED_FILES.items():
        path = PY_ROOT / relative
        if not path.exists():
            errors.append(f"Missing Python package shim: {path.relative_to(ROOT)}")
            continue
        text = path.read_text(encoding="utf-8")
        for literal in literals:
            if literal not in text:
                errors.append(f"{path.relative_to(ROOT)} must contain {literal}")

    for relative, literals in REQUIRED_JAVA_FILES.items():
        path = JAVA_ROOT / relative
        if not path.exists():
            errors.append(f"Missing Java compatibility bridge: {path.relative_to(ROOT)}")
            continue
        text = path.read_text(encoding="utf-8")
        for literal in literals:
            if literal not in text:
                errors.append(f"{path.relative_to(ROOT)} must contain {literal}")

    if errors:
        print("Plugin Java package shim guard failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print("Plugin Java package shim guard passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
