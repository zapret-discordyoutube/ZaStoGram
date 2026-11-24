// Host-test stub of <openssl/rand.h>: the route code only needs random bytes.
#pragma once
#include <stddef.h>
#include <stdint.h>
extern "C" int RAND_bytes(uint8_t *buf, size_t len);
