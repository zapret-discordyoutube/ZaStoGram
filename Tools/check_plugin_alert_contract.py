#!/usr/bin/env python3
"""Static guard for exteraGram-facing ui.alert.AlertDialogBuilder compatibility.

Plugins build dialogs from their button handlers; a constructor argument, constant or
method missing here makes that handler raise, so the button plays its ripple and does
nothing.
"""

from __future__ import annotations

import ast
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ALERT = ROOT / "TMessagesProj/src/main/python/ui/alert.py"

CONSTANTS = {
    "ALERT_TYPE_MESSAGE", "ALERT_TYPE_LOADING", "ALERT_TYPE_SPINNER",
    "BUTTON_POSITIVE", "BUTTON_NEGATIVE", "BUTTON_NEUTRAL",
}
METHODS = {
    "set_title", "set_message", "set_message_text_view_clickable", "set_view", "set_items",
    "set_positive_button", "set_negative_button", "set_neutral_button", "make_button_red",
    "set_on_back_button_listener", "set_on_dismiss_listener", "set_on_cancel_listener",
    "set_top_image", "set_top_drawable", "set_top_animation", "set_dim_enabled",
    "set_dialog_button_color_key", "set_blurred_background", "set_cancelable",
    "set_canceled_on_touch_outside", "create", "show", "dismiss", "get_dialog", "get_button",
    "set_progress",
}
INIT_ARGS = ["self", "context", "progress_style", "resources_provider"]


def fail(errors: list[str]) -> int:
    print("Plugin ui.alert contract check failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    return 1


def main() -> int:
    try:
        source = ALERT.read_text(encoding="utf-8")
    except FileNotFoundError:
        return fail([f"Missing {ALERT.relative_to(ROOT)}"])

    tree = ast.parse(source, filename=str(ALERT))
    builder = next((n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "AlertDialogBuilder"), None)
    if builder is None:
        return fail(["ui.alert must define AlertDialogBuilder"])

    methods = {n.name: n for n in builder.body if isinstance(n, ast.FunctionDef)}
    constants = {t.id for n in builder.body if isinstance(n, ast.Assign)
                 for t in n.targets if isinstance(t, ast.Name)}
    errors: list[str] = []

    init = methods.get("__init__")
    if init is None or [a.arg for a in init.args.args] != INIT_ARGS:
        errors.append("AlertDialogBuilder.__init__ must accept (context, progress_style, resources_provider)")
    for name in sorted(CONSTANTS - constants):
        errors.append(f"AlertDialogBuilder.{name} is missing")
    for name in sorted(METHODS - methods.keys()):
        errors.append(f"AlertDialogBuilder.{name}() is missing")
    for name in ("create", "show"):
        fn = methods.get(name)
        returns = [n for n in ast.walk(fn) if isinstance(n, ast.Return)] if fn else []
        if fn and not any(isinstance(r.value, ast.Name) and r.value.id == "self" for r in returns):
            errors.append(f"AlertDialogBuilder.{name}() must return the builder, as in exteraGram")

    return fail(errors) if errors else 0


if __name__ == "__main__":
    sys.exit(main())
