/*
 * Trusted roots for the Naive and WSS transports (fork-only, zsg_naive module).
 *
 * The system store comes first; the bundled roots are the fallback the old WSS code already carried: the ISRG Root X1
 * that eleven Cloudflare front domains chain to, and WebRTC's built-in root list, for devices whose system store is
 * old or incomplete.
 */
#ifndef ZSG_NAIVE_TRUST_ROOTS_H
#define ZSG_NAIVE_TRUST_ROOTS_H

#include <string>
#include <vector>

#include "zsg_naive/zsg_naive.h"

namespace tgnet {
namespace naive {

// Hashed certificate directories, newest location first: Android 14 moved the system roots into the conscrypt APEX.
const std::string &SystemCaDirs();
// Fills the trust fields of a config (ca_dir, extra_roots_pem, extra_roots_der); the strings live for the process.
void FillTrust(const char **caDir, const char **extraPem, const zsg_der_cert **extraDer, size_t *extraDerCount);

} // namespace naive
} // namespace tgnet

#endif
