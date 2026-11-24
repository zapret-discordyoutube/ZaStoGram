#include "TrustRoots.h"
#include <cstdlib>
#include <fstream>
#include <sstream>
namespace tgnet { namespace naive {
void FillTrust(const char **caDir, const char **extraPem, const zsg_der_cert **extraDer, size_t *extraDerCount) {
    static std::string pem;
    if (pem.empty()) {
        std::ifstream f(std::getenv("ZSG_LAB_CA_FILE"));
        std::stringstream ss;
        ss << f.rdbuf();
        pem = ss.str();
    }
    *caDir = nullptr;
    *extraPem = pem.c_str();
    *extraDer = nullptr;
    *extraDerCount = 0;
}
} }
#include <fstream>
extern "C" int RAND_bytes(uint8_t *buf, size_t len) {
    std::ifstream f("/dev/urandom", std::ios::binary);
    f.read(reinterpret_cast<char *>(buf), static_cast<std::streamsize>(len));
    return 1;
}
