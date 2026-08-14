#include <jni.h>
#include <android/log.h>

#include <gphoto2/gphoto2.h>
#include <gphoto2/gphoto2-port-log.h>
#include <gphoto2/gphoto2-result.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <fcntl.h>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_set>
#include <unistd.h>
#include <vector>

namespace {
constexpr const char* kTag = "FlyPhotoGPhoto2";

std::mutex g_mutex;
std::mutex g_log_mutex;
bool g_initialized = false;
bool g_connected = false;
std::string g_plugin_dir;
std::string g_temp_dir;
int g_file_descriptor = -1;
int g_vendor_id = 0;
int g_product_id = 0;
Camera* g_camera = nullptr;
GPContext* g_context = nullptr;
std::string g_log_file_path;
int g_log_func_id = -1;

struct MediaScanReport {
    int root_files_result = GP_ERROR;
    int root_folders_result = GP_ERROR;
    int visited_folder_count = 0;
    int discovered_file_count = 0;
    std::vector<std::string> errors;
};

MediaScanReport g_last_media_scan_report;

std::string timestamp() {
    const auto now = std::chrono::system_clock::now();
    const std::time_t time = std::chrono::system_clock::to_time_t(now);
    std::tm tm{};
    localtime_r(&time, &tm);
    char buffer[32];
    std::strftime(buffer, sizeof(buffer), "%Y-%m-%d %H:%M:%S", &tm);
    return buffer;
}

void append_log_line(const std::string& line) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    if (g_log_file_path.empty()) return;
    FILE* file = std::fopen(g_log_file_path.c_str(), "a");
    if (file == nullptr) return;
    std::fprintf(file, "%s %s\n", timestamp().c_str(), line.c_str());
    std::fclose(file);
}

void native_log(const std::string& line) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", line.c_str());
    append_log_line("[native] " + line);
}

const char* log_level_name(GPLogLevel level) {
    switch (level) {
        case GP_LOG_ERROR:
            return "ERROR";
        case GP_LOG_VERBOSE:
            return "VERBOSE";
        case GP_LOG_DEBUG:
            return "DEBUG";
        case GP_LOG_DATA:
            return "DATA";
    }
    return "UNKNOWN";
}

void gphoto_log_callback(GPLogLevel level, const char* domain, const char* str, void* /* data */) {
    if (level != GP_LOG_ERROR) return;
    std::ostringstream stream;
    stream << "[libgphoto2] " << log_level_name(level) << " "
           << (domain == nullptr ? "" : domain) << " - "
           << (str == nullptr ? "" : str);
    append_log_line(stream.str());
}

jstring make_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string java_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string gp_error(int code) {
    if (code >= GP_OK) return "ok";
    const char* text = gp_result_as_string(code);
    return text == nullptr ? "libgphoto2 error " + std::to_string(code) : std::string(text);
}

bool is_canon_vendor(int vendor_id) {
    return (vendor_id & 0xffff) == 0x04a9;
}

std::string widget_type_name(CameraWidgetType type) {
    switch (type) {
        case GP_WIDGET_WINDOW:
            return "window";
        case GP_WIDGET_SECTION:
            return "section";
        case GP_WIDGET_TEXT:
            return "text";
        case GP_WIDGET_RANGE:
            return "range";
        case GP_WIDGET_TOGGLE:
            return "toggle";
        case GP_WIDGET_RADIO:
            return "radio";
        case GP_WIDGET_MENU:
            return "menu";
        case GP_WIDGET_BUTTON:
            return "button";
        case GP_WIDGET_DATE:
            return "date";
    }
    return "unknown";
}

void log_config_widget(CameraWidget* config, const char* name) {
    if (config == nullptr || name == nullptr) return;
    CameraWidget* widget = nullptr;
    int result = gp_widget_get_child_by_name(config, name, &widget);
    if (result < GP_OK || widget == nullptr) {
        native_log(std::string("canon config ") + name + " missing result=" + std::to_string(result) + " " + gp_error(result));
        return;
    }

    CameraWidgetType type = GP_WIDGET_WINDOW;
    gp_widget_get_type(widget, &type);
    std::ostringstream stream;
    stream << "canon config " << name << " type=" << widget_type_name(type);

    switch (type) {
        case GP_WIDGET_TEXT:
        case GP_WIDGET_RADIO:
        case GP_WIDGET_MENU: {
            const char* value = nullptr;
            result = gp_widget_get_value(widget, &value);
            stream << " value=" << (result >= GP_OK && value != nullptr ? value : "")
                   << " valueResult=" << result << " " << gp_error(result);
            const int choices = gp_widget_count_choices(widget);
            stream << " choices=" << choices;
            for (int i = 0; i < choices; ++i) {
                const char* choice = nullptr;
                if (gp_widget_get_choice(widget, i, &choice) >= GP_OK && choice != nullptr) {
                    stream << " [" << i << "]=" << choice;
                }
            }
            break;
        }
        case GP_WIDGET_TOGGLE: {
            int value = 0;
            result = gp_widget_get_value(widget, &value);
            stream << " value=" << value << " valueResult=" << result << " " << gp_error(result);
            break;
        }
        case GP_WIDGET_RANGE: {
            float value = 0;
            result = gp_widget_get_value(widget, &value);
            stream << " value=" << value << " valueResult=" << result << " " << gp_error(result);
            break;
        }
        case GP_WIDGET_DATE: {
            int value = 0;
            result = gp_widget_get_value(widget, &value);
            stream << " value=" << value << " valueResult=" << result << " " << gp_error(result);
            break;
        }
        default:
            break;
    }
    native_log(stream.str());
}

void log_canon_diagnostics() {
    if (!is_canon_vendor(g_vendor_id) || g_camera == nullptr || g_context == nullptr) return;
    native_log("canon diagnostics start");

    CameraText summary{};
    int result = gp_camera_get_summary(g_camera, &summary, g_context);
    native_log("canon summary result=" + std::to_string(result) + " " + gp_error(result));
    if (result >= GP_OK) {
        std::string text(summary.text);
        if (text.size() > 600) text = text.substr(0, 600) + "...";
        native_log("canon summary text=" + text);
    }

    CameraWidget* config = nullptr;
    result = gp_camera_get_config(g_camera, &config, g_context);
    native_log("canon get_config result=" + std::to_string(result) + " " + gp_error(result));
    if (result >= GP_OK && config != nullptr) {
        log_config_widget(config, "capturetarget");
        log_config_widget(config, "eosremoterelease");
        log_config_widget(config, "capture");
        log_config_widget(config, "viewfinder");
        log_config_widget(config, "eventmode");
        log_config_widget(config, "output");
        gp_widget_free(config);
    }
    native_log("canon diagnostics end");
}

std::string usb_id(int vendor_id, int product_id) {
    std::ostringstream stream;
    stream << "usb:" << std::hex << std::setfill('0') << std::setw(4)
           << (vendor_id & 0xffff) << "," << std::setw(4) << (product_id & 0xffff);
    return stream.str();
}

std::string configure_camera_for_usb(int vendor_id, int product_id) {
    CameraAbilitiesList* abilities_list = nullptr;
    GPPortInfoList* port_info_list = nullptr;
    CameraList* detected = nullptr;

    int result = gp_abilities_list_new(&abilities_list);
    if (result < GP_OK) return gp_error(result);
    result = gp_abilities_list_load(abilities_list, g_context);
    native_log("gp_abilities_list_load result=" + std::to_string(result) + " " + gp_error(result));
    if (result < GP_OK) {
        gp_abilities_list_free(abilities_list);
        return "load camera abilities failed: " + gp_error(result);
    }

    result = gp_port_info_list_new(&port_info_list);
    if (result < GP_OK) {
        gp_abilities_list_free(abilities_list);
        return gp_error(result);
    }
    result = gp_port_info_list_load(port_info_list);
    native_log("gp_port_info_list_load result=" + std::to_string(result) + " " + gp_error(result));
    if (result < GP_OK) {
        gp_port_info_list_free(port_info_list);
        gp_abilities_list_free(abilities_list);
        return "load port info failed: " + gp_error(result);
    }

    result = gp_list_new(&detected);
    if (result < GP_OK) {
        gp_port_info_list_free(port_info_list);
        gp_abilities_list_free(abilities_list);
        return gp_error(result);
    }

    const int port_count = gp_port_info_list_count(port_info_list);
    native_log("port_info regular count=" + std::to_string(port_count));
    if (port_count > 0) {
        for (int i = 0; i < port_count; ++i) {
            GPPortInfo info;
            if (gp_port_info_list_get_info(port_info_list, i, &info) >= GP_OK) {
                char* port_name = nullptr;
                char* port_path = nullptr;
                GPPortType port_type = GP_PORT_NONE;
                gp_port_info_get_name(info, &port_name);
                gp_port_info_get_path(info, &port_path);
                gp_port_info_get_type(info, &port_type);
                std::ostringstream stream;
                stream << "port_info[" << i << "] name=" << (port_name == nullptr ? "" : port_name)
                       << " path=" << (port_path == nullptr ? "" : port_path)
                       << " type=" << port_type;
                native_log(stream.str());
            }
        }
    }

    std::string matched_model;
    std::string matched_port;
    result = gp_abilities_list_detect(abilities_list, port_info_list, detected, g_context);
    native_log("gp_abilities_list_detect result=" + std::to_string(result) + " " + gp_error(result));
    if (result >= GP_OK) {
        const int count = gp_list_count(detected);
        native_log("detected camera count=" + std::to_string(count));
        for (int i = 0; i < count; ++i) {
            const char* model = nullptr;
            const char* port = nullptr;
            gp_list_get_name(detected, i, &model);
            gp_list_get_value(detected, i, &port);
            if (model != nullptr && port != nullptr) {
                native_log(std::string("detected camera model=") + model + " port=" + port);
                matched_model = model;
                matched_port = port;
                break;
            }
        }
    }

    CameraAbilities abilities{};
    if (matched_model.empty()) {
        const int count = gp_abilities_list_count(abilities_list);
        for (int i = 0; i < count; ++i) {
            CameraAbilities candidate{};
            result = gp_abilities_list_get_abilities(abilities_list, i, &candidate);
            if (result < GP_OK) continue;
            if (candidate.usb_vendor == vendor_id && candidate.usb_product == product_id) {
                abilities = candidate;
                matched_model = candidate.model;
                native_log("matched camera by VID/PID model=" + matched_model);
                break;
            }
        }
    }

    if (matched_model.empty()) {
        const int ptp_index = gp_abilities_list_lookup_model(abilities_list, "USB PTP Class Camera");
        if (ptp_index >= GP_OK) {
            result = gp_abilities_list_get_abilities(abilities_list, ptp_index, &abilities);
            if (result >= GP_OK) {
                matched_model = abilities.model;
                native_log("fallback camera model=" + matched_model);
            }
        }
    } else {
        const int ability_index = gp_abilities_list_lookup_model(abilities_list, matched_model.c_str());
        result = ability_index < GP_OK
            ? ability_index
            : gp_abilities_list_get_abilities(abilities_list, ability_index, &abilities);
    }

    if (matched_model.empty() || result < GP_OK) {
        gp_list_unref(detected);
        gp_port_info_list_free(port_info_list);
        gp_abilities_list_free(abilities_list);
        return "unknown model: no libgphoto2 ability matched " + usb_id(vendor_id, product_id);
    }

    result = gp_camera_set_abilities(g_camera, abilities);
    if (result < GP_OK) {
        gp_list_unref(detected);
        gp_port_info_list_free(port_info_list);
        gp_abilities_list_free(abilities_list);
        return "set camera abilities failed: " + gp_error(result);
    }

    if (matched_port.empty()) matched_port = "usb:";
    int port_index = gp_port_info_list_lookup_path(port_info_list, matched_port.c_str());
    native_log("lookup matched port=" + matched_port + " index=" + std::to_string(port_index) + " " + gp_error(port_index));
    if (port_index < GP_OK) {
        port_index = gp_port_info_list_lookup_path(port_info_list, "usb:");
        native_log("lookup fallback port=usb: index=" + std::to_string(port_index) + " " + gp_error(port_index));
    }

    if (port_index < GP_OK) {
        gp_list_unref(detected);
        gp_port_info_list_free(port_info_list);
        gp_abilities_list_free(abilities_list);
        return "usb port not found after loading " + g_plugin_dir + "/lib/libgphoto2_port/0.12.2: " + gp_error(port_index);
    }

    GPPortInfo port_info;
    result = gp_port_info_list_get_info(port_info_list, port_index, &port_info);
    if (result >= GP_OK) result = gp_camera_set_port_info(g_camera, port_info);
    native_log("gp_camera_set_port_info result=" + std::to_string(result) + " " + gp_error(result));

    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "Configured libgphoto2 model=%s port=%s vendor=%04x product=%04x",
        matched_model.c_str(),
        matched_port.c_str(),
        vendor_id,
        product_id);

    gp_list_unref(detected);
    gp_port_info_list_free(port_info_list);
    gp_abilities_list_free(abilities_list);
    return gp_error(result);
}

std::string join_folder(const std::string& parent, const char* child) {
    const std::string child_name = child == nullptr ? "" : child;
    if (child_name.empty()) return parent.empty() ? "/" : parent;
    if (parent.empty() || parent == "/") return "/" + child_name;
    return parent + "/" + child_name;
}

std::string scan_error(
    const std::string& folder,
    const char* operation,
    int result) {
    std::string message = folder + " " + operation + ": " + gp_error(result);
    for (char& character : message) {
        if (character == '\n' || character == '\r' || character == '\t') character = ' ';
    }
    return message;
}

void record_scan_error(
    MediaScanReport& report,
    const std::string& folder,
    const char* operation,
    int result) {
    if (report.errors.size() >= 20) return;
    report.errors.push_back(scan_error(folder, operation, result));
}

void collect_camera_files(
    const std::string& folder,
    std::vector<std::string>& encoded,
    MediaScanReport& report,
    std::unordered_set<std::string>& visited_folders,
    std::unordered_set<std::string>& discovered_files,
    int depth = 0) {
    if (depth > 32 || visited_folders.size() >= 512) {
        record_scan_error(report, folder, "scan limit", GP_ERROR);
        return;
    }
    if (!visited_folders.insert(folder).second) return;
    report.visited_folder_count = static_cast<int>(visited_folders.size());

    CameraList* files = nullptr;
    int result = gp_list_new(&files);
    if (result >= GP_OK) {
        result = gp_camera_folder_list_files(g_camera, folder.c_str(), files, g_context);
        if (depth == 0) report.root_files_result = result;
        native_log("list files folder=" + folder + " result=" + std::to_string(result) + " " + gp_error(result));
        if (result >= GP_OK) {
            const int count = gp_list_count(files);
            for (int i = 0; i < count; ++i) {
                const char* name = nullptr;
                gp_list_get_name(files, i, &name);
                const std::string file_name = name == nullptr ? "" : name;
                const std::string file_key = folder + "/" + file_name;
                if (!file_name.empty() && discovered_files.insert(file_key).second) {
                    encoded.push_back(folder + "|" + file_name + "|0|");
                }
            }
        } else {
            record_scan_error(report, folder, "list files", result);
        }
        gp_list_unref(files);
    } else {
        if (depth == 0) report.root_files_result = result;
        record_scan_error(report, folder, "create file list", result);
    }

    CameraList* folders = nullptr;
    result = gp_list_new(&folders);
    if (result < GP_OK) {
        if (depth == 0) report.root_folders_result = result;
        record_scan_error(report, folder, "create folder list", result);
        return;
    }
    result = gp_camera_folder_list_folders(g_camera, folder.c_str(), folders, g_context);
    if (depth == 0) report.root_folders_result = result;
    native_log("list folders folder=" + folder + " result=" + std::to_string(result) + " " + gp_error(result));
    if (result >= GP_OK) {
        const int count = gp_list_count(folders);
        for (int i = 0; i < count; ++i) {
            const char* name = nullptr;
            gp_list_get_name(folders, i, &name);
            collect_camera_files(
                join_folder(folder, name),
                encoded,
                report,
                visited_folders,
                discovered_files,
                depth + 1);
        }
    } else {
        record_scan_error(report, folder, "list folders", result);
    }
    gp_list_unref(folders);
    report.discovered_file_count = static_cast<int>(encoded.size());
}

void release_camera() {
    if (g_camera != nullptr) {
        if (g_context != nullptr) gp_camera_exit(g_camera, g_context);
        gp_camera_unref(g_camera);
        g_camera = nullptr;
    }
    if (g_context != nullptr) {
        gp_context_unref(g_context);
        g_context = nullptr;
    }
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeSetLogFile(
    JNIEnv* env,
    jobject /* thiz */,
    jstring log_file_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_log_file_path = java_string(env, log_file_path);
    if (g_log_func_id >= 0) {
        gp_log_remove_func(g_log_func_id);
        g_log_func_id = -1;
    }
    if (g_log_file_path.empty()) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "File logging disabled");
        return;
    }
    g_log_func_id = gp_log_add_func(GP_LOG_ERROR, gphoto_log_callback, nullptr);
    append_log_line("\n========== camera connection log start ==========");
    native_log("Log file configured path=" + g_log_file_path + " gp_log_func_id=" + std::to_string(g_log_func_id));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring plugin_dir,
    jstring temp_dir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_plugin_dir = java_string(env, plugin_dir);
    g_temp_dir = java_string(env, temp_dir);
    setenv("CAMLIBS", (g_plugin_dir + "/lib/libgphoto2/2.5.34.1").c_str(), 1);
    setenv("IOLIBS", (g_plugin_dir + "/lib/libgphoto2_port/0.12.2").c_str(), 1);
    setenv("LD_LIBRARY_PATH", (g_plugin_dir + "/lib").c_str(), 1);
    native_log("nativeInit pluginDir=" + g_plugin_dir + " tempDir=" + g_temp_dir);
    native_log(std::string("CAMLIBS=") + getenv("CAMLIBS"));
    native_log(std::string("IOLIBS=") + getenv("IOLIBS"));
    native_log(std::string("LD_LIBRARY_PATH=") + getenv("LD_LIBRARY_PATH"));
    release_camera();
    g_context = gp_context_new();
    int camera_result = gp_camera_new(&g_camera);
    if (camera_result < GP_OK) {
        g_initialized = false;
        return make_string(env, gp_error(camera_result));
    }
    g_initialized = true;
    native_log("Initialized with pluginDir=" + g_plugin_dir + " tempDir=" + g_temp_dir);
    return make_string(env, "ok");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeConnectCamera(
    JNIEnv* env,
    jobject /* thiz */,
    jint file_descriptor,
    jint vendor_id,
    jint product_id,
    jstring device_name) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) {
        return make_string(env, "Native bridge is not initialized");
    }

    g_file_descriptor = file_descriptor;
    g_vendor_id = vendor_id;
    g_product_id = product_id;
    const int fd_result = gp_port_usb_set_sys_device(g_file_descriptor);
    native_log("gp_port_usb_set_sys_device fd=" + std::to_string(g_file_descriptor) +
               " result=" + std::to_string(fd_result) + " " + gp_error(fd_result));
    if (fd_result < GP_OK) {
        g_connected = false;
        return make_string(env, "set Android USB fd failed: " + gp_error(fd_result));
    }
    g_connected = true;

    const std::string name = java_string(env, device_name);
    native_log("nativeConnectCamera fd=" + std::to_string(g_file_descriptor) +
               " vendor=" + std::to_string(g_vendor_id) +
               " product=" + std::to_string(g_product_id) +
               " usbId=" + usb_id(g_vendor_id, g_product_id) +
               " device=" + name);

    const std::string setup_result = configure_camera_for_usb(g_vendor_id, g_product_id);
    native_log("configure_camera_for_usb result=" + setup_result);
    if (setup_result != "ok") {
        g_connected = false;
        return make_string(env, setup_result);
    }

    // Android has already granted and opened this USB device. libgphoto2 still
    // needs explicit model/port abilities above; otherwise gp_camera_init can
    // fail with GP_ERROR_MODEL_NOT_FOUND ("Unknown model") before it reaches USB I/O.
    // If initialization later fails with a USB access/open error, the remaining
    // work is to patch the usb1 port to consume g_file_descriptor directly.
    int init_result = g_camera == nullptr ? GP_ERROR : gp_camera_init(g_camera, g_context);
    native_log("gp_camera_init result=" + std::to_string(init_result) + " " + gp_error(init_result));
    if (init_result < GP_OK) {
        g_connected = false;
        return make_string(env, gp_error(init_result));
    }
    log_canon_diagnostics();
    return make_string(env, "ok");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeListFiles(
    JNIEnv* env,
    jobject /* thiz */,
    jstring folder) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const jclass string_class = env->FindClass("java/lang/String");
    if (!g_connected || g_camera == nullptr || g_context == nullptr) {
        return env->NewObjectArray(0, string_class, nullptr);
    }

    const std::string requested_folder = java_string(env, folder);
    const std::string root = requested_folder.empty() ? "/" : requested_folder;
    std::vector<std::string> encoded;
    MediaScanReport report;
    std::unordered_set<std::string> visited_folders;
    std::unordered_set<std::string> discovered_files;
    collect_camera_files(
        root,
        encoded,
        report,
        visited_folders,
        discovered_files);
    report.discovered_file_count = static_cast<int>(encoded.size());
    g_last_media_scan_report = report;
    native_log(
        "media scan complete root=" + root +
        " folders=" + std::to_string(report.visited_folder_count) +
        " files=" + std::to_string(report.discovered_file_count) +
        " errors=" + std::to_string(report.errors.size()));

    jobjectArray array = env->NewObjectArray(static_cast<jsize>(encoded.size()), string_class, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(encoded.size()); ++i) {
        env->SetObjectArrayElement(array, i, make_string(env, encoded[static_cast<size_t>(i)]));
    }
    return array;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeGetLastMediaScanReport(
    JNIEnv* env,
    jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const jclass string_class = env->FindClass("java/lang/String");
    std::vector<std::string> values = {
        "rootFilesResult=" + std::to_string(g_last_media_scan_report.root_files_result),
        "rootFoldersResult=" + std::to_string(g_last_media_scan_report.root_folders_result),
        "visitedFolderCount=" + std::to_string(g_last_media_scan_report.visited_folder_count),
        "discoveredFileCount=" + std::to_string(g_last_media_scan_report.discovered_file_count),
    };
    for (const std::string& error : g_last_media_scan_report.errors) {
        values.push_back("error=" + error);
    }
    jobjectArray array = env->NewObjectArray(
        static_cast<jsize>(values.size()),
        string_class,
        nullptr);
    for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
        env->SetObjectArrayElement(array, i, make_string(env, values[static_cast<size_t>(i)]));
    }
    return array;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeWaitForEvent(
    JNIEnv* env,
    jobject /* thiz */,
    jint timeout_ms) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_connected || g_camera == nullptr || g_context == nullptr) {
        native_log("wait event skipped: disconnected");
        return make_string(env, "disconnected");
    }

    CameraEventType event_type = GP_EVENT_UNKNOWN;
    void* event_data = nullptr;
    const int timeout = timeout_ms <= 0 ? 750 : timeout_ms;
    const int result = gp_camera_wait_for_event(g_camera, timeout, &event_type, &event_data, g_context);
    if (result < GP_OK) {
        if (event_data != nullptr) std::free(event_data);
        return make_string(env, "error|" + gp_error(result));
    }

    std::string response;
    switch (event_type) {
        case GP_EVENT_TIMEOUT:
            response = "timeout";
            break;
        case GP_EVENT_FILE_ADDED: {
            auto* path = static_cast<CameraFilePath*>(event_data);
            const std::string folder = path == nullptr ? "/" : path->folder;
            const std::string name = path == nullptr ? "" : path->name;
            native_log("wait event file added folder=" + folder + " name=" + name);
            response = "fileAdded|" + folder + "|" + name;
            break;
        }
        case GP_EVENT_FOLDER_ADDED: {
            auto* path = static_cast<CameraFilePath*>(event_data);
            const std::string folder = path == nullptr ? "/" : path->folder;
            const std::string name = path == nullptr ? "" : path->name;
            native_log("wait event folder added folder=" + folder + " name=" + name);
            response = "folderAdded|" + folder + "|" + name;
            break;
        }
        case GP_EVENT_CAPTURE_COMPLETE:
            native_log("wait event capture complete");
            response = "captureComplete";
            break;
        case GP_EVENT_UNKNOWN:
        default:
            native_log("wait event unknown type=" + std::to_string(event_type));
            response = "unknown";
            break;
    }
    if (event_data != nullptr) std::free(event_data);
    return make_string(env, response);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeCapture(
    JNIEnv* env,
    jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_connected || g_camera == nullptr || g_context == nullptr) {
        return make_string(env, "Camera is not connected");
    }

    native_log("capture start");
    CameraFilePath path{};
    int result = gp_camera_capture(g_camera, GP_CAPTURE_IMAGE, &path, g_context);
    native_log("gp_camera_capture result=" + std::to_string(result) + " " + gp_error(result));
    if (result < GP_OK) return make_string(env, gp_error(result));
    const std::string camera_path = std::string(path.folder) + "/" + path.name;
    native_log("capture success path=" + camera_path);
    return make_string(env, camera_path);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeDownload(
    JNIEnv* env,
    jobject /* thiz */,
    jstring folder,
    jstring name,
    jstring destination_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_connected || g_camera == nullptr || g_context == nullptr) {
        return make_string(env, "Camera is not connected");
    }

    const std::string folder_path = java_string(env, folder);
    const std::string file_name = java_string(env, name);
    const std::string destination = java_string(env, destination_path);
    const int destination_fd = open(
        destination.c_str(),
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
        0600);
    if (destination_fd < 0) {
        return make_string(env, "Unable to open destination file");
    }
    CameraFile* file = nullptr;
    int result = gp_file_new_from_fd(&file, destination_fd);
    if (result < GP_OK) {
        close(destination_fd);
        std::remove(destination.c_str());
        return make_string(env, gp_error(result));
    }

    result = gp_camera_file_get(
        g_camera,
        folder_path.empty() ? "/" : folder_path.c_str(),
        file_name.c_str(),
        GP_FILE_TYPE_NORMAL,
        file,
        g_context);
    gp_file_unref(file);
    if (result < GP_OK) {
        std::remove(destination.c_str());
        return make_string(env, gp_error(result));
    }
    return make_string(env, destination);
}

extern "C" JNIEXPORT void JNICALL
Java_com_flyphoto_usb_1camera_1sdk_GPhoto2Bridge_nativeDisconnect(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_connected = false;
    gp_port_usb_set_sys_device(-1);
    g_file_descriptor = -1;
    g_vendor_id = 0;
    g_product_id = 0;
    release_camera();
    __android_log_print(ANDROID_LOG_INFO, kTag, "Disconnected");
}
