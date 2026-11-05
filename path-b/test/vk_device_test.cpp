// Path B stage B0 harness (this project, GPL-3.0+): the smallest surfaceless
// Vulkan device-create test, built on PCSX2's own Vulkan loader
// (GS/Renderers/Vulkan/VKLoader).
//
// What it does, in order, printing one [vk-test] line per stage so a run on a
// device is self-explaining from logcat alone:
//   1. dlopen the system Vulkan library through PCSX2's VKLoader.
//   2. Create a VkInstance with ZERO instance extensions -- in particular no
//      VK_KHR_surface. This is the "surfaceless" property path B needs: the GS
//      will render offscreen and the host keeps owning the only window.
//   3. Load instance entry points, enumerate physical devices, print the
//      first device's name/vendor/API version, and note the presence of a few
//      extensions the full GSDeviceVK will ask about in stage B1.
//   4. Create a logical device with one graphics queue and ZERO device
//      extensions, load device entry points, fetch the queue, idle it.
//   5. Destroy everything and print PASS.
//
// Exit codes: 0 = pass, 10 = library load, 11 = instance, 12 = instance
// functions, 13 = no physical device, 14 = no graphics queue, 15 = device,
// 16 = device functions.
//
// This proves loader + instance + device + queue creation on whatever Vulkan
// implementation runs it. It does NOT prove GSDeviceVK's full feature set
// (that is B1), and running it on a desktop ICD does not prove anything about
// the Adreno 725 in the POCO F5.

#include "GS/Renderers/Vulkan/VKLoader.h"

#include "common/Error.h"

#include <cstdio>
#include <cstring>
#include <vector>

static int Fail(int code, const char* stage, const char* detail)
{
	std::printf("[vk-test] FAIL stage=%s code=%d detail=%s\n", stage, code, detail);
	std::fflush(stdout);
	return code;
}

static bool HasExtension(const std::vector<VkExtensionProperties>& exts, const char* name)
{
	for (const VkExtensionProperties& e : exts)
	{
		if (std::strcmp(e.extensionName, name) == 0)
			return true;
	}
	return false;
}

int main()
{
	std::printf("[vk-test] start: surfaceless Vulkan device-create test (PCSX2 VKLoader)\n");
	std::fflush(stdout);

	Error error;
	if (!Vulkan::LoadVulkanLibrary(&error))
		return Fail(10, "load-library", error.GetDescription().c_str());
	std::printf("[vk-test] stage=load-library ok\n");

	u32 instance_version = VK_API_VERSION_1_0;
	if (vkEnumerateInstanceVersion)
		vkEnumerateInstanceVersion(&instance_version);
	std::printf("[vk-test] instance version %u.%u.%u\n", VK_VERSION_MAJOR(instance_version),
		VK_VERSION_MINOR(instance_version), VK_VERSION_PATCH(instance_version));

	VkApplicationInfo app_info = {};
	app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
	app_info.pApplicationName = "pcsx2gs path-b vk device test";
	app_info.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
	app_info.pEngineName = "pcsx2gs-pathb";
	app_info.engineVersion = VK_MAKE_VERSION(0, 1, 0);
	app_info.apiVersion = (instance_version >= VK_API_VERSION_1_3) ? VK_API_VERSION_1_3 : instance_version;

	VkInstanceCreateInfo inst_ci = {};
	inst_ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
	inst_ci.pApplicationInfo = &app_info;
	// Surfaceless on purpose: enabledExtensionCount stays 0.
	VkInstance instance = VK_NULL_HANDLE;
	VkResult res = vkCreateInstance(&inst_ci, nullptr, &instance);
	if (res != VK_SUCCESS)
	{
		char buf[64];
		std::snprintf(buf, sizeof(buf), "vkCreateInstance returned %d", static_cast<int>(res));
		Vulkan::UnloadVulkanLibrary();
		return Fail(11, "create-instance", buf);
	}
	std::printf("[vk-test] stage=create-instance ok (0 extensions, surfaceless)\n");

	if (!Vulkan::LoadVulkanInstanceFunctions(instance))
	{
		vkDestroyInstance(instance, nullptr);
		Vulkan::UnloadVulkanLibrary();
		return Fail(12, "load-instance-functions", "LoadVulkanInstanceFunctions failed");
	}
	std::printf("[vk-test] stage=load-instance-functions ok\n");

	u32 gpu_count = 0;
	vkEnumeratePhysicalDevices(instance, &gpu_count, nullptr);
	if (gpu_count == 0)
	{
		vkDestroyInstance(instance, nullptr);
		Vulkan::UnloadVulkanLibrary();
		return Fail(13, "enumerate-physical-devices", "no physical devices");
	}
	std::vector<VkPhysicalDevice> gpus(gpu_count);
	vkEnumeratePhysicalDevices(instance, &gpu_count, gpus.data());
	const VkPhysicalDevice gpu = gpus[0];

	VkPhysicalDeviceProperties props = {};
	vkGetPhysicalDeviceProperties(gpu, &props);
	std::printf("[vk-test] physical devices=%u first=\"%s\" vendor=0x%04x device=0x%04x api=%u.%u.%u driver=%u\n",
		gpu_count, props.deviceName, props.vendorID, props.deviceID, VK_VERSION_MAJOR(props.apiVersion),
		VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion), props.driverVersion);

	u32 ext_count = 0;
	vkEnumerateDeviceExtensionProperties(gpu, nullptr, &ext_count, nullptr);
	std::vector<VkExtensionProperties> exts(ext_count);
	vkEnumerateDeviceExtensionProperties(gpu, nullptr, &ext_count, exts.data());
	std::printf("[vk-test] device extensions=%u dynamic_rendering=%d swapchain=%d attachment_feedback_loop=%d "
				"rasterization_order_attachment_access=%d\n",
		ext_count, HasExtension(exts, VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME) ? 1 : 0,
		HasExtension(exts, VK_KHR_SWAPCHAIN_EXTENSION_NAME) ? 1 : 0,
		HasExtension(exts, VK_EXT_ATTACHMENT_FEEDBACK_LOOP_LAYOUT_EXTENSION_NAME) ? 1 : 0,
		HasExtension(exts, "VK_ARM_rasterization_order_attachment_access") ? 1 : 0);

	u32 qf_count = 0;
	vkGetPhysicalDeviceQueueFamilyProperties(gpu, &qf_count, nullptr);
	std::vector<VkQueueFamilyProperties> qfs(qf_count);
	vkGetPhysicalDeviceQueueFamilyProperties(gpu, &qf_count, qfs.data());
	u32 gfx_family = UINT32_MAX;
	for (u32 i = 0; i < qf_count; i++)
	{
		if (qfs[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)
		{
			gfx_family = i;
			break;
		}
	}
	if (gfx_family == UINT32_MAX)
	{
		vkDestroyInstance(instance, nullptr);
		Vulkan::UnloadVulkanLibrary();
		return Fail(14, "find-graphics-queue", "no queue family with VK_QUEUE_GRAPHICS_BIT");
	}
	std::printf("[vk-test] stage=find-graphics-queue ok family=%u queues=%u\n", gfx_family, qfs[gfx_family].queueCount);

	const float queue_priority = 1.0f;
	VkDeviceQueueCreateInfo queue_ci = {};
	queue_ci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
	queue_ci.queueFamilyIndex = gfx_family;
	queue_ci.queueCount = 1;
	queue_ci.pQueuePriorities = &queue_priority;

	VkDeviceCreateInfo dev_ci = {};
	dev_ci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
	dev_ci.queueCreateInfoCount = 1;
	dev_ci.pQueueCreateInfos = &queue_ci;
	// No device extensions either: plain core device creation.
	VkDevice device = VK_NULL_HANDLE;
	res = vkCreateDevice(gpu, &dev_ci, nullptr, &device);
	if (res != VK_SUCCESS)
	{
		char buf[64];
		std::snprintf(buf, sizeof(buf), "vkCreateDevice returned %d", static_cast<int>(res));
		vkDestroyInstance(instance, nullptr);
		Vulkan::UnloadVulkanLibrary();
		return Fail(15, "create-device", buf);
	}
	std::printf("[vk-test] stage=create-device ok (0 extensions, surfaceless)\n");

	if (!Vulkan::LoadVulkanDeviceFunctions(device))
	{
		vkDestroyDevice(device, nullptr);
		vkDestroyInstance(instance, nullptr);
		Vulkan::UnloadVulkanLibrary();
		return Fail(16, "load-device-functions", "LoadVulkanDeviceFunctions failed");
	}

	VkQueue queue = VK_NULL_HANDLE;
	vkGetDeviceQueue(device, gfx_family, 0, &queue);
	vkQueueWaitIdle(queue);
	std::printf("[vk-test] stage=get-queue ok queue idle\n");

	vkDestroyDevice(device, nullptr);
	vkDestroyInstance(instance, nullptr);
	Vulkan::UnloadVulkanLibrary();
	std::printf("[vk-test] PASS surfaceless device created and destroyed cleanly\n");
	std::fflush(stdout);
	return 0;
}
