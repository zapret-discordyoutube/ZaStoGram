#!/usr/bin/env python3
"""Static guard for exteraGram-facing client_utils compatibility exports."""

from __future__ import annotations

import ast
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CLIENT_UTILS = ROOT / "TMessagesProj/src/main/python/client_utils.py"
PLUGIN_UTILS = ROOT / "TMessagesProj/src/main/java/org/telegram/plugins/PluginUtils.java"


def fail(errors: list[str]) -> int:
    print("Plugin client_utils contract check failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    return 1


def call_name(node: ast.AST) -> str | None:
    if isinstance(node, ast.Call):
        return call_name(node.func)
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return None


def has_dynamic_proxy_base(cls: ast.ClassDef, interface: str) -> bool:
    for base in cls.bases:
        if not isinstance(base, ast.Call):
            continue
        if call_name(base.func) != "dynamic_proxy":
            continue
        if base.args and call_name(base.args[0]) == interface:
            return True
    return False


def main() -> int:
    try:
        source = CLIENT_UTILS.read_text(encoding="utf-8")
    except FileNotFoundError:
        return fail([f"Missing {CLIENT_UTILS.relative_to(ROOT)}"])

    tree = ast.parse(source, filename=str(CLIENT_UTILS))
    classes = {node.name: node for node in tree.body if isinstance(node, ast.ClassDef)}
    functions = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
    errors: list[str] = []

    request_callback = classes.get("RequestCallback")
    if request_callback is None:
        errors.append("client_utils must export RequestCallback for exteraGram plugins")
    else:
        if not has_dynamic_proxy_base(request_callback, "RequestDelegate"):
            errors.append("RequestCallback must proxy org.telegram.tgnet.RequestDelegate")
        if not any(isinstance(node, ast.FunctionDef) and node.name == "run"
                   for node in request_callback.body):
            errors.append("RequestCallback must implement run(response, error)")

    if "RequestCallback(on_complete" not in source:
        errors.append("send_request must wrap Python callbacks with RequestCallback")

    for queue_name in (
        "STAGE_QUEUE", "GLOBAL_QUEUE", "CACHE_CLEAR_QUEUE", "SEARCH_QUEUE",
        "PHONE_BOOK_QUEUE", "THEME_QUEUE", "EXTERNAL_NETWORK_QUEUE", "PLUGINS_QUEUE",
    ):
        if queue_name not in {node.targets[0].id for node in tree.body
                              if isinstance(node, ast.Assign)
                              and len(node.targets) == 1
                              and isinstance(node.targets[0], ast.Name)}:
            errors.append(f"client_utils must export {queue_name} for exteraGram run_on_queue")

    if "get_queue_by_name" not in functions:
        errors.append("client_utils must export get_queue_by_name for raw DispatchQueue access")

    run_on_queue = functions.get("run_on_queue")
    if run_on_queue is None:
        errors.append("client_utils must export run_on_queue")
    else:
        args = [arg.arg for arg in run_on_queue.args.args]
        if args[:3] != ["fn", "queue", "delay"]:
            errors.append("run_on_queue signature must accept fn, queue, delay")
        if "PluginUtils.runOnQueue(str(queue or PLUGINS_QUEUE), _Runnable(fn), int(delay))" not in source:
            errors.append("run_on_queue must pass queue name and delay to PluginUtils.runOnQueue")

    for helper in ("send_text", "send_audio", "send_photo", "send_video", "edit_message"):
        if helper not in functions:
            errors.append(f"client_utils must export {helper} for common exteraGram plugins")
    if "PluginUtils.editMessage(" not in source:
        errors.append("edit_message must delegate through PluginUtils.editMessage")

    for helper in ("get_notifications_settings", "get_media_controller"):
        if helper not in functions:
            errors.append(f"client_utils must export {helper} for exteraGram controller helpers")

    notification_delegate = classes.get("NotificationCenterDelegate")
    if notification_delegate is None:
        errors.append("client_utils must export NotificationCenterDelegate")
    else:
        if not has_dynamic_proxy_base(notification_delegate, "NotificationCenterDelegateInterface"):
            errors.append("NotificationCenterDelegate must proxy NotificationCenter.NotificationCenterDelegate")
        if not any(isinstance(node, ast.FunctionDef) and node.name == "didReceivedNotification"
                   for node in notification_delegate.body):
            errors.append("NotificationCenterDelegate must implement didReceivedNotification")

    if "from extera_utils.text_formatting import parse_text" not in source:
        errors.append("client_utils must import parse_text for parse_mode support")
    if "parse_mode" not in (functions.get("send_message").args.args[-1].arg if functions.get("send_message") else ""):
        errors.append("send_message must accept parse_mode")
    if "_apply_parse_mode(params, parse_mode)" not in source:
        errors.append("send_message/send_text must apply parse_mode through parse_text")
    if "caption_entities" not in source:
        errors.append("send_document must preserve parsed caption entities")
    if "PluginUtils.sendDocument(" in source and "caption_entities" not in source:
        errors.append("send_document must pass caption_entities to PluginUtils.sendDocument")

    try:
        java_source = PLUGIN_UTILS.read_text(encoding="utf-8")
    except FileNotFoundError:
        errors.append(f"Missing {PLUGIN_UTILS.relative_to(ROOT)}")
        java_source = ""
    if java_source:
        if "Object captionEntities" not in java_source:
            errors.append("PluginUtils.sendDocument must accept captionEntities")
        if "coerceMessageEntities(captionEntities)" not in java_source:
            errors.append("PluginUtils.sendDocument must coerce captionEntities")
        if "SendMessagesHelper.prepareSendingDocuments(" not in java_source:
            errors.append("PluginUtils.sendDocument must call prepareSendingDocuments overload with entities")

    for name, kind in (("send_audio", "audio"), ("send_photo", "photo"), ("send_video", "video"),
                       ("send_document", "document")):
        fn = functions.get(name)
        if fn is None:
            errors.append(f"client_utils must export {name}")
            continue
        args = [arg.arg for arg in fn.args.args]
        if args[:2] != ["peer_id", args[1]] or len(args) < 2:
            errors.append(f"{name} signature must start with peer_id and a file path")
        if "account" not in args:
            errors.append(f"{name} must accept account= (exteraGram SDK 1.4.5.0 multi-account)")
        if not any(isinstance(node, ast.Constant) and node.value == kind for node in ast.walk(fn)):
            errors.append(f"{name} must send through _send_media({kind!r}, ...)")
    if "PluginUtils.sendMedia(" not in source:
        errors.append("_send_media must call PluginUtils.sendMedia")
    if java_source and "prepareSendingMedia(" not in java_source:
        errors.append("PluginUtils.sendMedia must send photos/videos through prepareSendingMedia")

    if errors:
        return fail(errors)

    print("Plugin client_utils contract check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
