// Vulkan capability probe for SA Runner.
// Returns a plain "key=value" text block that the Kotlin side parses.
// Read-only: creates an instance, inspects physical devices, destroys the instance.

#include <jni.h>
#include <vulkan/vulkan.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

std::string versionString(uint32_t v) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%u.%u.%u",
                  VK_API_VERSION_MAJOR(v), VK_API_VERSION_MINOR(v), VK_API_VERSION_PATCH(v));
    return buf;
}

std::string hex(uint32_t v) {
    char buf[16];
    std::snprintf(buf, sizeof(buf), "0x%08X", v);
    return buf;
}

const char* deviceTypeName(VkPhysicalDeviceType t) {
    switch (t) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:    return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU:            return "cpu";
        default:                                     return "other";
    }
}

// Extensions worth knowing about for D3D9 -> Vulkan translation.
// The first six are the "required" list on the DXVK wiki for DXVK 3.x
// (https://github.com/doitsujin/dxvk/wiki/Driver-support). The rest are informational.
const char* kInterestingExtensions[] = {
    "VK_EXT_depth_clip_enable",
    "VK_KHR_maintenance5",
    "VK_KHR_maintenance6",
    "VK_KHR_load_store_op_none",
    "VK_EXT_robustness2",
    "VK_EXT_transform_feedback",
    "VK_EXT_graphics_pipeline_library",
    "VK_EXT_custom_border_color",
    "VK_KHR_swapchain",
    "VK_KHR_driver_properties",
    "VK_EXT_memory_budget",
    "VK_KHR_present_wait",
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_sarunner_launcher_VulkanProbe_nativeProbe(JNIEnv* env, jobject /*thiz*/) {
    std::string out;

    // vkEnumerateInstanceVersion is only exported by libvulkan on API 28+, so look it up.
    uint32_t instanceVersion = VK_API_VERSION_1_0;
    auto pfnEnumInstVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion"));
    if (pfnEnumInstVersion != nullptr) {
        pfnEnumInstVersion(&instanceVersion);
    }
    out += "instance_version=" + versionString(instanceVersion) + "\n";

    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "SA Runner Probe";
    app.applicationVersion = 1;
    app.apiVersion = instanceVersion >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;

    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = vkCreateInstance(&ci, nullptr, &instance);
    if (res != VK_SUCCESS) {
        out += "error=vkCreateInstance failed (" + std::to_string(static_cast<int>(res)) + ")\n";
        return env->NewStringUTF(out.c_str());
    }

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    out += "device_count=" + std::to_string(count) + "\n";
    std::vector<VkPhysicalDevice> devices(count);
    if (count > 0) {
        vkEnumeratePhysicalDevices(instance, &count, devices.data());
    }

    auto pfnProps2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2"));

    for (uint32_t i = 0; i < count; ++i) {
        const std::string p = "dev" + std::to_string(i) + ".";
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(devices[i], &props);

        out += p + "name=" + props.deviceName + "\n";
        out += p + "type=" + deviceTypeName(props.deviceType) + "\n";
        out += p + "api_version=" + versionString(props.apiVersion) + "\n";
        out += p + "driver_version_raw=" + hex(props.driverVersion) + "\n";
        out += p + "vendor_id=" + hex(props.vendorID) + "\n";
        out += p + "device_id=" + hex(props.deviceID) + "\n";
        out += p + "max_image_2d=" + std::to_string(props.limits.maxImageDimension2D) + "\n";

        // Extensions
        uint32_t extCount = 0;
        vkEnumerateDeviceExtensionProperties(devices[i], nullptr, &extCount, nullptr);
        std::vector<VkExtensionProperties> exts(extCount);
        if (extCount > 0) {
            vkEnumerateDeviceExtensionProperties(devices[i], nullptr, &extCount, exts.data());
        }
        out += p + "extension_count=" + std::to_string(extCount) + "\n";

        bool hasDriverProps = false;
        for (const char* want : kInterestingExtensions) {
            bool found = false;
            for (const auto& e : exts) {
                if (std::strcmp(e.extensionName, want) == 0) { found = true; break; }
            }
            if (std::strcmp(want, "VK_KHR_driver_properties") == 0) hasDriverProps = found;
            out += p + "ext." + want + "=" + (found ? "yes" : "no") + "\n";
        }

        // Driver name / info (Vulkan 1.2 core or VK_KHR_driver_properties).
        bool canQueryDriver = pfnProps2 != nullptr &&
                (props.apiVersion >= VK_API_VERSION_1_2 || hasDriverProps);
        if (canQueryDriver) {
            VkPhysicalDeviceDriverProperties drv{};
            drv.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;
            VkPhysicalDeviceProperties2 p2{};
            p2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            p2.pNext = &drv;
            pfnProps2(devices[i], &p2);
            out += p + "driver_name=" + std::string(drv.driverName) + "\n";
            out += p + "driver_info=" + std::string(drv.driverInfo) + "\n";
        }

        // Device-local memory (on phones this is shared system RAM, reported by the driver).
        VkPhysicalDeviceMemoryProperties mem{};
        vkGetPhysicalDeviceMemoryProperties(devices[i], &mem);
        uint64_t deviceLocal = 0;
        for (uint32_t h = 0; h < mem.memoryHeapCount; ++h) {
            if (mem.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
                deviceLocal += mem.memoryHeaps[h].size;
            }
        }
        out += p + "device_local_mb=" + std::to_string(deviceLocal / (1024 * 1024)) + "\n";
    }

    vkDestroyInstance(instance, nullptr);
    return env->NewStringUTF(out.c_str());
}
