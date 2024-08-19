package icu.nullptr.hidemyapplist.xposed

import android.content.pm.IPackageManager
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import icu.nullptr.hidemyapplist.common.Constants
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import kotlin.concurrent.thread

private const val TAG = "HMA-XposedEntry"

@Suppress("unused")
class XposedEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    private var whitelist: Set<String> = HashSet()
    private var lastModifiedTime: Long = 0
    private var configFile: File? = null

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXHelperInit.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == Constants.APP_PACKAGE_NAME) {
            EzXHelperInit.initHandleLoadPackage(lpparam)
            hookAllConstructorAfter("icu.nullptr.hidemyapplist.MyApp") {
                getFieldByDesc("Licu/nullptr/hidemyapplist/MyApp;->isHooked:Z").setBoolean(it.thisObject, true)
            }
        } else if (lpparam.packageName == "android") {
            EzXHelperInit.initHandleLoadPackage(lpparam)
            logI(TAG, "Hook entry")

            var serviceManagerHook: XC_MethodHook.Unhook? = null
            serviceManagerHook = findMethod("android.os.ServiceManager") {
                name == "addService"
            }.hookBefore { param ->
                if (param.args[0] == "package") {
                    serviceManagerHook?.unhook()
                    val pms = param.args[1] as IPackageManager
                    logD(TAG, "Got pms: $pms")
                    thread {
                        runCatching {
                            UserService.register(pms)
                            logI(TAG, "User service started")
                        }.onFailure {
                            logE(TAG, "System service crashed", it)
                        }
                    }
                }
            }

            // 初始化时加载白名单
            loadWhitelist()

            // Hook ClipboardService 中的 clipboardAccessAllowed 方法
            XposedHelpers.findAndHookMethod(
                "com.android.server.clipboard.ClipboardService",
                lpparam.classLoader,
                "clipboardAccessAllowed",
                Int::class.javaPrimitiveType, String::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[1] as String

                        // 动态加载白名单
                        loadWhitelist()

                        // 判断包名是否在白名单中
                        if (whitelist.contains(packageName)) {
                            param.result = true // 如果在白名单中，则允许访问
                        }
                    }
                })
        }
    }

    // 从文件中加载白名单，使用缓存机制并缓存 config.json 路径
    private fun loadWhitelist() {
        if (configFile == null || !configFile!!.exists() || !configFile!!.canRead()) {
            val dir = File("/data/system/")
            val files = dir.listFiles { _, name -> name.startsWith("hide_my_applist_") }
            if (files != null && files.isNotEmpty()) {
                configFile = File(files[0], "config.json")
            } else {
                XposedBridge.log("No hide_my_applist_ directory found in /data/system/")
                return
            }
        }

        if (configFile!!.exists() && configFile!!.canRead()) {
            val currentModifiedTime = configFile!!.lastModified()

            if (currentModifiedTime == lastModifiedTime) {
                return
            }

            lastModifiedTime = currentModifiedTime

            try {
                BufferedReader(FileReader(configFile)).use { reader ->
                    val jsonContent = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        jsonContent.append(line)
                    }

                    val config = JSONObject(jsonContent.toString())
                    val templates = config.getJSONObject("templates")
                    val clipboardTemplate = templates.getJSONObject("剪贴板白名单")
                    if (clipboardTemplate.getBoolean("isWhitelist")) {
                        val appList = clipboardTemplate.getJSONArray("appList")
                        val tempWhitelist = HashSet<String>()
                        for (i in 0 until appList.length()) {
                            tempWhitelist.add(appList.getString(i))
                        }
                        whitelist = tempWhitelist
                    }
                }
            } catch (e: IOException) {
                XposedBridge.log("Error reading config.json: ${e.message}")
            } catch (e: Exception) {
                XposedBridge.log("Error parsing JSON: ${e.message}")
            }
        } else {
            XposedBridge.log("config.json file not found or unreadable: ${configFile!!.path}")
        }
    }
}
