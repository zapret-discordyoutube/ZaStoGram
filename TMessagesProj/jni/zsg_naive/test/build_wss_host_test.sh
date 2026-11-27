#!/bin/bash
# Builds the host test of the real WssSocket.cpp (stub logger + lab trust root). Usage: build_wss_host_test.sh OUTPUT
# WSS_TEST_SRC=other.cpp builds a different test against the same WssSocket.cpp.
# Needs a built naive-core (libzsgnaive.so) in $NAIVE_CORE (default ~/zastogram/naive-core).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WSS=$HERE/../../tgnet/wss
CORE=${NAIVE_CORE:-$HOME/zastogram/naive-core}
T=$(mktemp -d)
cp $WSS/WssSocket.cpp $WSS/WssSocket.h $T/
cp -r $WSS/../transport $T/transport
sed -i 's#"../FileLog.h"#"host_stubs/FileLog.h"#; s#"../../zsg_naive/TrustRoots.h"#"host_stubs/TrustRoots.h"#; s#"../transport/TransportSocket.h"#"transport/TransportSocket.h"#' $T/WssSocket.cpp $T/WssSocket.h
cp -r $HERE/host_stubs $T/host_stubs
g++ -std=c++17 -Wall -DNETWORK_TYPE_WIFI=1 -I$T/host_stubs/include -I$T -I$CORE/include $T/WssSocket.cpp $T/host_stubs/TrustRoots.cpp ${WSS_TEST_SRC:-$HERE/wss_socket_host_test.cpp} \
    -L$CORE/build -lzsgnaive -Wl,-rpath,$CORE/build -o "$1"
rm -rf $T
