#!/bin/bash
# Builds the host test of the real WebProxy.cpp (stub trust roots). Usage: build_webproxy_host_test.sh OUTPUT
# Needs a built naive-core (libzsgnaive.so) in $NAIVE_CORE (default ~/zastogram/naive-core).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
CORE=${NAIVE_CORE:-$HOME/zastogram/naive-core}
T=$(mktemp -d)
cp $HERE/../WebProxy.cpp $HERE/../WebProxy.h $T/
sed -i 's#"TrustRoots.h"#"host_stubs/TrustRoots.h"#' $T/WebProxy.cpp
cp -r $HERE/host_stubs $T/host_stubs
mkdir $T/test && cp $HERE/webproxy_host_test.cpp $T/test/
g++ -std=c++17 -Wall -pthread -I$T/host_stubs/include -I$T -I$CORE/include $T/WebProxy.cpp $T/host_stubs/TrustRoots.cpp $T/test/webproxy_host_test.cpp \
    -L$CORE/build -lzsgnaive -Wl,-rpath,$CORE/build -o "$1"
rm -rf $T
