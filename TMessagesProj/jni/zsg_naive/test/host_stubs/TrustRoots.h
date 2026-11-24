// Host-test stub of zsg_naive/TrustRoots.h: trusts only the lab CA (path in $ZSG_LAB_CA_FILE), no system store.
#pragma once
#include <string>
#include "zsg_naive/zsg_naive.h"
namespace tgnet { namespace naive {
void FillTrust(const char **caDir, const char **extraPem, const zsg_der_cert **extraDer, size_t *extraDerCount);
} }
