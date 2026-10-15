#!/usr/bin/env python3
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
CONNECTIONS = ROOT / "TMessagesProj/jni/tgnet/ConnectionsManager.cpp"
JAVA_CONNECTIONS = ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java"
TLRPC = ROOT / "TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java"
MTPROXY_ALL = ROOT / "Tools/check_mtproxy_all.py"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def main() -> int:
    failures: list[str] = []
    connections = read(CONNECTIONS)
    java_connections = read(JAVA_CONNECTIONS)
    tlrpc = read(TLRPC)
    mtproxy_all = read(MTPROXY_ALL)

    require(
        "not found request to parse constructor" not in connections
        and "received unparsed packet" not in connections
        and "not found file" not in connections,
        "debug parser schema gaps must not use failure-looking not-found/unparsed-packet wording",
        failures,
    )
    require(
        "debug_parser_unmapped constructor=0x%x" not in connections
        and "java_debug_parser_unmapped constructor=0x%x" not in java_connections,
        "old debug_parser_unmapped wording must be replaced by tl_debug_unmapped_* counters",
        failures,
    )
    require(
        "tl_debug_unmapped_constructor constructor=0x%x context=%s action=ignored" in connections,
        "unmapped TL constructors must be logged with neutral tl_debug_unmapped_constructor wording",
        failures,
    )
    require(
        "tl_debug_unmapped_packet req_msg_id=0x%" in connections,
        "packets delegated to the Java parser must be logged as neutral tl_debug_unmapped_packet events",
        failures,
    )
    require(
        "TL_UNMAPPED_CONSTRUCTOR_LOG_INTERVAL_MS = 30 * 1000" in connections
        and "TL_UNMAPPED_SUMMARY_INTERVAL_MS = 60 * 1000" in connections
        and "unmappedConstructorStats" in connections
        and "tl_debug_unmapped_summary constructor=0x%x count=%u" in connections,
        "native unmapped constructors must be rate-limited per constructor and summarized every 60s",
        failures,
    )
    require(
        "java received unknown constructor" not in java_connections
        and "tl_debug_unmapped_constructor constructor=0x%x context=java_parser action=ignored" in java_connections,
        "Java parser fallback must use neutral tl_debug_unmapped_constructor wording for unmapped constructors",
        failures,
    )
    require(
        "TL_UNMAPPED_CONSTRUCTOR_LOG_INTERVAL_MS = 30_000" in java_connections
        and "TL_UNMAPPED_SUMMARY_INTERVAL_MS = 60_000" in java_connections
        and "unmappedConstructorStats" in java_connections
        and "tl_debug_unmapped_summary constructor=0x%x count=%d" in java_connections,
        "Java unmapped constructor logs must be rate-limited and summarized",
        failures,
    )
    require(
        "constructor == 0x96a18d5" not in connections,
        "ConnectionsManager must not special-case upload.File as a fake not-found condition",
        failures,
    )
    require(
        "case 0x96a18d5:" in tlrpc
        and "TL_upload_file" in tlrpc
        # Upstream 13.0 dispatches Updates by the class constants (case TL_updateShort.constructor:)
        # instead of the numeric literal; accept both, as long as the class still carries 0x78d4dec1.
        and ("case 0x78d4dec1:" in tlrpc
             or ("case TL_updateShort.constructor:" in tlrpc
                 and "class TL_updateShort extends Updates {\n        public static final int constructor = 0x78d4dec1;" in tlrpc)),
        "Java TL schema must already know the repeated upload.File/updateShort constructors that native debug parser may skip",
        failures,
    )
    require(
        '"check_debug_parser_unmapped_logs.py"' in mtproxy_all,
        "full MTProxy guard suite must include debug-parser unmapped log guard",
        failures,
    )

    if failures:
        print("Debug parser unmapped log guard failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print("Debug parser unmapped log guard passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
