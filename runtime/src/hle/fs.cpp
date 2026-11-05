// coreinit FS and nn_save: map Wii U volume paths onto host directories.
//   /vol/content/...  -> <game>/content/...
//   /vol/code/...     -> <game>/code/...
//   /vol/meta/...     -> <game>/meta/...
//   /vol/save/...     -> <save dir>/...
#include <dirent.h>
#include <sys/stat.h>

#include <cstdio>
#include <cstring>
#include <strings.h>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "../mem_writes.h"
#include "../runtime.h"

// إعلان الدالة البرمجية لنظام الـ RTL الناقصة
namespace rtl_text { void language_pack_opened(const std::string& host_path); }

namespace {

enum FSStatus : int32_t {
    FS_OK = 0,
    FS_END = -2,
    FS_EXISTS = -5,
    FS_NOT_FOUND = -6,
    FS_NOT_FILE = -7,
    FS_NOT_DIR = -8,
    FS_ACCESS_ERROR = -10,
};

struct OpenFile {
    FILE* f;
    std::string path;  // guest path
    std::string mode;
};
struct OpenDir {
    DIR* d;
    std::string path;  // host path
    std::string gpath;
    uint32_t read = 0;  // entries returned so far
};

std::mutex g_fs_mutex;
std::unordered_map<uint32_t, OpenFile> g_files;
std::unordered_map<uint32_t, OpenDir> g_dirs;
uint32_t g_next_handle = 1;

std::string host_path_exact(const std::string& guest);

std::string case_insensitive(const std::string& root, const std::string& path) {
    struct stat st;
    if (stat(path.c_str(), &st) == 0 || path.size() <= root.size()) return path;
    static std::mutex m;
    static std::unordered_map<std::string, std::string> cache;
    std::lock_guard<std::mutex> lk(m);
    auto it = cache.find(path);
    if (it != cache.end()) return it->second;
    std::string out = root;
    size_t i = root.size();
    while (i < path.size()) {
        size_t j = path.find('/', i + 1);
        if (j == std::string::npos) j = path.size();
        std::string part = path.substr(i + 1, j - i - 1);  // path[i] is '/'
        std::string next = out + "/" + part;
        if (!part.empty() && stat(next.c_str(), &st) != 0)
            if (DIR* d = opendir(out.c_str())) {
                while (dirent* e = readdir(d))
                    if (!strcasecmp(e->d_name, part.c_str())) {
                        next = out + "/" + e->d_name;
                        break;
                    }
                closedir(d);
            }
        out = next;
        i = j;
    }
    cache[path] = out;
    return out;
}

std::string host_path(const std::string& guest) {
    std::string r = host_path_exact(guest);
    const std::string& g = config::game_dir;
    const std::string gc = g + "/content/";
    if (!config::content_overlay.empty() && r.compare(0, gc.size(), gc) == 0) {
        static const std::vector<std::string> overlays = [] {
            std::vector<std::string> v;
            const std::string& all = config::content_overlay;
            for (size_t a = 0; a < all.size();) {
                size_t b = all.find('\n', a);
                if (b == std::string::npos) b = all.size();
                if (b > a) v.push_back(all.substr(a, b - a));
                a = b + 1;
            }
            return v;
        }();
        for (const std::string& o : overlays) {
            std::string alt = case_insensitive(o, o + "/content/" + r.substr(gc.size()));
            struct stat st;
            if (stat(alt.c_str(), &st) == 0 && S_ISREG(st.st_mode)) return alt;
        }
    }
    if (r.compare(0, g.size(), g) == 0 && guest.compare(0, 9, "/vol/save") != 0) return case_insensitive(g, r);
    return r;
}

std::string host_path_exact(const std::string& guest) {
    std::string p = guest;
    auto map = [&](const char* prefix, const std::string& root) -> bool {
        size_t n = strlen(prefix);
        if (p.compare(0, n, prefix) == 0) {
            p = root + p.substr(n);
            return true;
        }
        return false;
    };
    if (map("/vol/content", config::game_dir + "/content")) return p;
    if (map("/vol/code", config::game_dir + "/code")) return p;
    if (map("/vol/meta", config::game_dir + "/meta")) return p;
    if (map("/vol/save", config::save_dir)) return p;
    if (!p.empty() && p[0] != '/') return config::game_dir + "/content/" + p;
    return config::game_dir + p;
}

void make_parent_dirs(const std::string& path) {
    for (size_t i = 1; i < path.size(); i++)
        if (path[i] == '/') mkdir(path.substr(0, i).c_str(), 0755);
}

void fill_stat(uint32_t out, const struct stat& st) {
    memset(mem::ptr(out), 0, 0x64);
    bool dir = S_ISDIR(st.st_mode);
    st32(out + 0x00, dir ? 0x80000000u : 0x01000000u);
    st32(out + 0x04, 0x666);
    st32(out + 0x10, dir ? 0 : (uint32_t)st.st_size);
    st32(out + 0x14, dir ? 0 : (uint32_t)st.st_size);
}

int32_t open_file(const std::string& gpath, const std::string& mode, uint32_t out_handle) {
    std::string hp = host_path(gpath);
    if (mode.find_first_of("wa") != std::string::npos) make_parent_dirs(hp);
    std::string m = mode;
    if (m.find('b') == std::string::npos) m += "b";
    FILE* f = fopen(hp.c_str(), m.c_str());
    TRACE("[fs] open %s (%s) -> %s", gpath.c_str(), mode.c_str(), f ? "ok" : "not found");
    if (!f) return FS_NOT_FOUND;
    
    // ⚡ الجسر البرمجي الناقص: إبلاغ نظام الـ RTL فور فتح ملفات اللغة والخطوط
    rtl_text::language_pack_opened(hp);

    std::lock_guard<std::mutex> lk(g_fs_mutex);
    uint32_t h = g_next_handle++;
    g_files[h] = {f, gpath, m};
    st32(out_handle, h);
    return FS_OK;
}

FILE* file(uint32_t h) {
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    auto it = g_files.find(h);
    return it == g_files.end() ? nullptr : it->second.f;
}

int32_t stat_path(const std::string& gpath, uint32_t out) {
    struct stat st;
    if (stat(host_path(gpath).c_str(), &st) != 0) return FS_NOT_FOUND;
    fill_stat(out, st);
    return FS_OK;
}

int32_t open_dir(const std::string& gpath, uint32_t out_handle) {
    DIR* d = opendir(host_path(gpath).c_str());
    TRACE("[fs] opendir %s -> %s", gpath.c_str(), d ? "ok" : "not found");
    if (!d) return FS_NOT_FOUND;
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    uint32_t h = g_next_handle++;
    g_dirs[h] = {d, host_path(gpath), gpath};
    st32(out_handle, h);
    return FS_OK;
}

}  // namespace

// ---------------------------------------------------------------- FS
HLE(coreinit, FSInit) {}
HLE(coreinit, FSShutdown) {}
HLE(coreinit, FSAddClient) { ret(c, 0); }
HLE(coreinit, FSDelClient) { ret(c, 0); }
HLE(coreinit, FSInitCmdBlock) { memset(mem::ptr(arg(c, 0)), 0, 0xA80); }
HLE(coreinit, FSSetCmdPriority) { ret(c, 0); }
HLE(coreinit, FSSetStateChangeNotification) {}
HLE(coreinit, FSGetVolumeState) { ret(c, 1); }
HLE(coreinit, FSGetLastError) { ret(c, 0); }
HLE(coreinit, FSGetLastErrorCodeForViewer) { ret(c, 0); }
HLE(coreinit, FSGetCwd) { mem::write_cstr(arg(c, 2), "/vol/content", arg(c, 3)); ret(c, FS_OK); }

HLE(coreinit, FSOpenFile) {
    ret(c, open_file(mem::read_cstr(arg(c, 2)), mem::read_cstr(arg(c, 3)), arg(c, 4)));
}

HLE(coreinit, FSCloseFile) {
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    auto it = g_files.find(arg(c, 2));
    if (it != g_files.end()) {
        fclose(it->second.f);
        g_files.erase(it);
    }
    ret(c, FS_OK);
}

HLE(coreinit, FSReadFile) {
    uint32_t dst = arg(c, 2), size = arg(c, 3), count = arg(c, 4);
    FILE* f = file(arg(c, 5));
    if (!f || size == 0) { ret(c, 0); return; }
    size_t n;
    {
        BlockingScope b;
        n = fread(mem::ptr(dst), 1, (size_t)size * count, f);
        memw::mark(dst, (uint32_t)n);
    }
    ret(c, (uint32_t)(n / size));
}
