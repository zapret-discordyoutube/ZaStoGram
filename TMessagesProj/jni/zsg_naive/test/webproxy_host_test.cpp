// Host test of WebProxy.cpp: a browser-like client speaks `CONNECT` to the loopback proxy, which tunnels through a real
// Naive server (Caddy + forwardproxy, naive-ref-lab) to a local HTTP server.
// Usage: webproxy_host_test NAIVE_PORT TARGET_HTTP_PORT     (proxy "127.0.0.1", labuser:labpass, certificate check off)
#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "../WebProxy.h"

static int Connect(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
    if (connect(fd, reinterpret_cast<sockaddr *>(&a), sizeof(a)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

// Reads until the peer closes or |maxMs| passes.
static std::string ReadAll(int fd, int maxMs, const std::string &stopAfter = "") {
    std::string out;
    for (int waited = 0; waited < maxMs;) {
        pollfd p{fd, POLLIN, 0};
        if (poll(&p, 1, 100) <= 0) {
            waited += 100;
            continue;
        }
        char buf[8192];
        const ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) break;
        out.append(buf, static_cast<size_t>(n));
        if (!stopAfter.empty() && out.find(stopAfter) != std::string::npos) break;
    }
    return out;
}

int main(int argc, char **argv) {
    tgnet::naive::WebProxyConfig config;
    config.proxyHost = "127.0.0.1";
    config.proxyPort = static_cast<uint16_t>(std::atoi(argv[1]));
    config.user = "labuser";
    config.password = "labpass";
    config.insecureSkipVerify = true;
    const std::string target = std::string("127.0.0.1:") + argv[2];
    const int port = tgnet::naive::StartWebProxy(config);
    std::printf("web proxy port=%d\n", port);
    if (port == 0) return 2;
    int failures = 0;

    {  // 1. one CONNECT, one request through the tunnel
        int fd = Connect(port);
        const std::string req = "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n";
        write(fd, req.data(), req.size());
        std::string head = ReadAll(fd, 15000, "\r\n\r\n");
        const bool ok200 = head.rfind("HTTP/1.1 200", 0) == 0;
        const std::string get = "GET / HTTP/1.0\r\n\r\n";
        write(fd, get.data(), get.size());
        std::string body = ReadAll(fd, 10000);
        const bool welcome = body.find("Welcome") != std::string::npos;
        std::printf("[1] connect 200=%s page=%s (%zu bytes)\n", ok200 ? "yes" : "NO", welcome ? "yes" : "NO", body.size());
        failures += !(ok200 && welcome);
        close(fd);
    }
    {  // 2. eight concurrent tunnels over the one shared Naive connection
        std::atomic<int> good{0};
        std::vector<std::thread> threads;
        for (int i = 0; i < 8; ++i) {
            threads.emplace_back([&] {
                int fd = Connect(port);
                const std::string req = "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n";
                write(fd, req.data(), req.size());
                std::string head = ReadAll(fd, 15000, "\r\n\r\n");
                const std::string get = "GET / HTTP/1.0\r\n\r\n";
                write(fd, get.data(), get.size());
                std::string body = ReadAll(fd, 10000);
                if (head.rfind("HTTP/1.1 200", 0) == 0 && body.find("Welcome") != std::string::npos) good++;
                close(fd);
            });
        }
        for (auto &t : threads) t.join();
        std::printf("[2] concurrent tunnels ok=%d/8\n", good.load());
        failures += good.load() != 8;
    }
    {  // 3. plain http through the proxy must be refused (fail closed)
        int fd = Connect(port);
        const std::string req = "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target + "\r\n\r\n";
        write(fd, req.data(), req.size());
        std::string resp = ReadAll(fd, 5000);
        const bool refused = resp.rfind("HTTP/1.1 501", 0) == 0;
        std::printf("[3] plain http refused=%s\n", refused ? "yes" : "NO");
        failures += !refused;
        close(fd);
    }
    {  // 4. a closed target port: 502, or 200 followed by an immediate close (Caddy answers before it dials)
        int fd = Connect(port);
        const std::string req = "CONNECT 127.0.0.1:1 HTTP/1.1\r\nHost: 127.0.0.1:1\r\n\r\n";
        write(fd, req.data(), req.size());
        std::string resp = ReadAll(fd, 10000);
        const bool sane = resp.rfind("HTTP/1.1 502", 0) == 0 || resp.rfind("HTTP/1.1 200", 0) == 0;
        std::printf("[4] dead target handled=%s (%.20s)\n", sane ? "yes" : "NO", resp.c_str());
        failures += !sane;
        close(fd);
    }
    tgnet::naive::StopWebProxy();
    std::printf("stopped; failures=%d\n", failures);
    return failures == 0 ? 0 : 1;
}
