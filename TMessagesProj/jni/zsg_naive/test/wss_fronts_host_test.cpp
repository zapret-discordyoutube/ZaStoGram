// Host test of the front catalog in the REAL WssSocket.cpp: a cached wss_fronts.txt replaces the baked list, a front that
// is not baked is dialled by name, a bad file falls back to the baked list. DC203 has no kws relay, so OfficialRoute
// returns a front straight away. Usage: wss_fronts_host_test   (writes into a temp directory)
#include <cstdio>
#include <cstdlib>
#include <string>
#include <unistd.h>

#include "WssSocket.h"

static int failures = 0;
#define CHECK_TRUE(c) do { if (!(c)) { std::printf("FAIL line %d: %s\n", __LINE__, #c); ++failures; } } while (0)

static void Write(const std::string &path, const std::string &text) {
    FILE *f = std::fopen(path.c_str(), "w");
    std::fputs(text.c_str(), f);
    std::fclose(f);
}

static bool Front(tgnet::wss::Route *route) {
    return tgnet::wss::OfficialRoute(203, false, false, "149.154.175.100", route) && route->cdnSlot >= 0;
}

int main() {
    char tmpl[] = "/tmp/wssfrontsXXXXXX";
    const std::string dir = std::string(mkdtemp(tmpl)) + "/";
    tgnet::wss::SetRouteHealthPath(dir + "wss_route_health.txt");
    const std::string file = dir + "wss_fronts.txt";

    // 1. No file: the baked list (pinned Cloudflare addresses).
    {
        tgnet::wss::Route r;
        CHECK_TRUE(Front(&r));
        CHECK_TRUE(r.domain.rfind("kws203.", 0) == 0 && r.domain.size() > 12);
        CHECK_TRUE(r.relayHost != r.domain);  // an address
        CHECK_TRUE(r.relayPort == 443 && !r.plain);
    }
    // 2. A valid file with fronts nobody baked: the route is one of them, dialled by name.
    Write(file, "# fronts\nalpha-front.example\nbeta-front.example\r\n\ngamma-front.example\n");
    sleep(1);
    for (int i = 0; i < 5; ++i) {
        tgnet::wss::SetRouteHealthPath(dir + "wss_route_health.txt");  // resets the recheck timer, as a restart would
        tgnet::wss::Route r;
        CHECK_TRUE(Front(&r));
        const bool listed = r.domain == "kws203.alpha-front.example" || r.domain == "kws203.beta-front.example"
                || r.domain == "kws203.gamma-front.example";
        CHECK_TRUE(listed);
        CHECK_TRUE(r.relayHost == r.domain);  // no pinned address: by name
    }
    // 3. A bad file (one malformed line) is rejected as a whole: back to the baked list.
    Write(file, "alpha-front.example\nbeta front with spaces\ngamma-front.example\n");
    tgnet::wss::SetRouteHealthPath(dir + "wss_route_health.txt");
    {
        tgnet::wss::Route r;
        CHECK_TRUE(Front(&r));
        CHECK_TRUE(r.domain.find("alpha-front") == std::string::npos);
        CHECK_TRUE(r.relayHost != r.domain);
    }
    // 4. Too short a list (below the floor) is rejected too.
    Write(file, "alpha-front.example\nbeta-front.example\n");
    tgnet::wss::SetRouteHealthPath(dir + "wss_route_health.txt");
    {
        tgnet::wss::Route r;
        CHECK_TRUE(Front(&r));
        CHECK_TRUE(r.domain.find("alpha-front") == std::string::npos);
    }
    // 5. The upstream (encoded) format decodes: virkgj.com is pclead.co.uk, which is baked, so it keeps its address.
    Write(file, "virkgj.com\nvmmzovy.com\nmkuosckvso.com\n");
    tgnet::wss::SetRouteHealthPath(dir + "wss_route_health.txt");
    {
        tgnet::wss::Route r;
        CHECK_TRUE(Front(&r));
        const bool listed = r.domain == "kws203.pclead.co.uk" || r.domain == "kws203.offshor.co.uk"
                || r.domain == "kws203.cakeisalie.co.uk";
        CHECK_TRUE(listed);
        CHECK_TRUE(r.relayHost != r.domain);
    }
    std::printf(failures == 0 ? "wss_fronts_host_test: OK\n" : "wss_fronts_host_test: %d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
