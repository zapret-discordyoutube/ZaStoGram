#!/usr/bin/env python3
"""Guard: every fork hook of the Naive module outside jni/zsg_naive/ must still be present.

An upstream merge that silently drops one of these lines would leave the module compiled but unused (or not
compiling). Run from anywhere: python3 TMessagesProj/jni/zsg_naive/tests/check_hooks.py
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]  # repository root (android/)
JNI = ROOT / "TMessagesProj/jni"
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
RES = ROOT / "TMessagesProj/src/main/res"

HOOKS = {
    JNI / "CMakeLists.txt": ["include(zsg_naive/zsg_naive.cmake)", "zsg_naive_socket"],
    JNI / "tgnet/ConnectionSocket.cpp": [
        '#include "../zsg_naive/NaiveSocket.h"',
        "const bool useNaive = proxyOptions.naive && !proxyAddress->empty();",
        "tgnet::naive::RouteFor(*proxyAddress, proxyPort)",
        "tgnet::naive::CreateFor(*proxyAddress, proxyPort,",
        'std::strcmp(currentWssTransport->transportName(), "naive") == 0',
    ],
    JNI / "mtproxy/MtProxyOptions.h": ["bool naive = false;", "naive == other.naive", "normalized.naive = options.naive;"],
    JNI / "TgNetWrapper.cpp": ["jclass_MtProxyOptions_naive", '"naive", "Z"'],
    JAVA / "tgnet/MtProxyOptions.java": ["public final boolean naive;", "public static MtProxyOptions naive()"],
    JAVA / "tgnet/ConnectionsManager.java": ["MtProxyOptions.naive()", "MtProxyOptions.naive(), activationGeneration"],
    JAVA / "proxy/ProxySettings.java": ["NAIVE", "case NAIVE:", "Type.NAIVE"],
    JAVA / "messenger/ProxyLinkHelper.java": ["TYPE_NAIVE", "ProxySettings.Type.NAIVE"],
    JAVA / "ui/ProxySettingsActivity.java": ["TYPE_NAIVE", "R.string.UseProxyNaive"],
    RES / "values/strings.xml": ['name="UseProxyNaive"', 'name="UseProxyNaiveInfo"'],
}

missing = []
for path, needles in HOOKS.items():
    text = path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""
    for needle in needles:
        if needle not in text:
            missing.append(f"{path.relative_to(ROOT)}: missing `{needle}`")
if missing:
    print("Naive module hooks LOST (see jni/zsg_naive/README.md):")
    print("\n".join(" - " + m for m in missing))
    sys.exit(1)
print(f"all Naive hooks present ({sum(len(v) for v in HOOKS.values())} checks in {len(HOOKS)} files)")
