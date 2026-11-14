package com.nexwork.edith.data

import android.content.Context
import android.content.om.FabricatedOverlay
import android.content.om.OverlayIdentifier
import android.content.om.OverlayManagerTransaction
import android.os.IBinder
import androidx.annotation.Keep
import kotlin.system.exitProcess
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Shizuku UserService running in the privileged `shell` (`uid 2000`) process.
 *
 * On Android 14 QPR2 / Android 15 (`b/202768292`), `OverlayManagerService` blocks `uid 2000` from
 * calling `registerFabricatedOverlay` (`Non-root shell cannot fabricate overlays`), while SELinux
 * blocks `untrusted_app` from calling `ServiceManager.getService("overlay")`.
 *
 * This service bridges both constraints without root:
 * 1. Exposes the raw `overlay` `IBinder` (`getOverlayManagerBinder()`) so `com.nexwork.edith`
 *    (`uid 10xxx`) can invoke `registerFabricatedOverlay` / `unregisterFabricatedOverlay` directly
 *    under its own app UID.
 * 2. Executes `OverlayManagerTransaction.Builder.setEnabled(..., userId)` from `uid 2000`
 *    (`CHANGE_OVERLAY_PACKAGES`), which enables the fabricated overlay and promotes it to highest
 *    priority above `com.android.systemui:neutral`, `:accent`, and `:dynamic`.
 * 3. Reads and writes `Settings.Secure.theme_customization_overlay_packages` safely via `uid 2000`.
 */
@Keep
class ShizukuMonetService : IShizukuMonetService.Stub {

  @Keep
  constructor() : super() {
    exemptHiddenApis()
  }

  @Keep
  constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super() {
    exemptHiddenApis()
  }

  private fun exemptHiddenApis() {
    runCatching {
      HiddenApiBypass.addHiddenApiExemptions(
        "Landroid/content/om/",
        "Landroid/os/",
      )
    }
  }

  override fun destroy() {
    exitProcess(0)
  }

  override fun getOverlayManagerBinder(): IBinder? {
    return runCatching {
      exemptHiddenApis()
      val smClass = Class.forName("android.os.ServiceManager")
      val getService = smClass.getMethod("getService", String::class.java)
      getService.invoke(null, "overlay") as? IBinder
    }.getOrNull()
  }

  override fun enableAndPrioritizeOverlay(
    packageName: String,
    overlayName: String,
    userId: Int,
  ): Boolean {
    return setOverlayEnabledInternal(packageName, overlayName, true, userId)
  }

  override fun disableOverlay(
    packageName: String,
    overlayName: String,
    userId: Int,
  ): Boolean {
    return setOverlayEnabledInternal(packageName, overlayName, false, userId)
  }

  private fun setOverlayEnabledInternal(
    packageName: String,
    overlayName: String,
    enabled: Boolean,
    userId: Int,
  ): Boolean {
    return runCatching {
      exemptHiddenApis()
      val binder = getOverlayManagerBinder() ?: return false
      val stubClass = Class.forName("android.content.om.IOverlayManager\$Stub")
      val iom = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
      val id = createOverlayIdentifier(packageName, overlayName)

      val builderClass = Class.forName("android.content.om.OverlayManagerTransaction\$Builder")
      val builder = builderClass.getDeclaredConstructor().newInstance()
      val setEnabled = builderClass.getDeclaredMethod(
        "setEnabled",
        OverlayIdentifier::class.java,
        Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
      )
      if (enabled) {
        for (sysOverlay in listOf("neutral", "accent", "dynamic")) {
          val sysId = createOverlayIdentifier("com.android.systemui", sysOverlay)
          setEnabled.invoke(builder, sysId, true, userId)
        }
      }
      setEnabled.invoke(builder, id, enabled, userId)
      val tx = builderClass.getDeclaredMethod("build").invoke(builder) as OverlayManagerTransaction

      val commit = iom.javaClass.getMethod("commit", OverlayManagerTransaction::class.java)
      commit.invoke(iom, tx)
      true
    }.getOrDefault(false)
  }

  override fun isOverlayActiveAtTop(
    packageName: String,
    overlayName: String,
    userId: Int,
  ): Boolean {
    return runCatching {
      exemptHiddenApis()
      val binder = getOverlayManagerBinder() ?: return false
      val stubClass = Class.forName("android.content.om.IOverlayManager\$Stub")
      val iom = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
      val getInfos = iom.javaClass.getMethod(
        "getOverlayInfosForTarget",
        String::class.java,
        Int::class.javaPrimitiveType,
      )
      val list = getInfos.invoke(iom, "android", userId) as? List<*> ?: return false
      var ourIndex = -1
      var ourFabricated = false
      var maxSystemUiIndex = -1
      var sysUiEnabledCount = 0
      for ((idx, item) in list.withIndex()) {
        if (item == null) continue
        val cls = item.javaClass
        val pkg = cls.getMethod("getPackageName").invoke(item) as? String ?: continue
        val name = cls.getMethod("getOverlayName").invoke(item) as? String
        val enabled = cls.getMethod("isEnabled").invoke(item) as? Boolean ?: false
        val fabricated = runCatching {
          cls.getMethod("isFabricated").invoke(item) as? Boolean ?: false
        }.getOrDefault(true)
        if (pkg == packageName && name == overlayName) {
          ourIndex = idx
          ourFabricated = fabricated
        } else if (pkg == "com.android.systemui" && enabled) {
          maxSystemUiIndex = maxOf(maxSystemUiIndex, idx)
          if (name == "neutral" || name == "accent" || name == "dynamic") {
            sysUiEnabledCount++
          }
        }
      }
      val json = readThemeCustomizationJson()
      val jsonActive = json.contains("MOTO") && json.contains("DC143C", ignoreCase = true)
      val atTop = ourFabricated && ourIndex > maxSystemUiIndex
      (atTop || sysUiEnabledCount >= 3) && jsonActive
    }.getOrDefault(false)
  }

  override fun readThemeCustomizationJson(): String {
    return readSecureSetting("theme_customization_overlay_packages")
  }

  override fun writeThemeCustomizationJson(json: String): Boolean {
    return writeSecureSetting("theme_customization_overlay_packages", json)
  }

  override fun readSecureSetting(key: String): String {
    return runCatching {
      val proc = ProcessBuilder(
        "cmd",
        "settings",
        "get",
        "secure",
        key,
      ).redirectErrorStream(true).start()
      val out = proc.inputStream.bufferedReader().use { it.readText().trim() }
      proc.waitFor()
      if (out == "null") "" else out
    }.getOrDefault("")
  }

  override fun writeSecureSetting(key: String, value: String): Boolean {
    return runCatching {
      val proc = ProcessBuilder(
        "cmd",
        "settings",
        "put",
        "secure",
        key,
        value,
      ).redirectErrorStream(true).start()
      proc.inputStream.bufferedReader().use { it.readText() }
      proc.waitFor() == 0
    }.getOrDefault(false)
  }

  override fun ensureNightModeYes(): Boolean {
    return runCatching {
      val proc = ProcessBuilder(
        "cmd",
        "uimode",
        "night",
        "yes",
      ).redirectErrorStream(true).start()
      proc.inputStream.bufferedReader().use { it.readText() }
      proc.waitFor() == 0
    }.getOrDefault(false)
  }

  private fun createOverlayIdentifier(packageName: String, overlayName: String): OverlayIdentifier {
    val fo = FabricatedOverlay(overlayName, "android")
    val setOwningPkg = FabricatedOverlay::class.java.getDeclaredMethod(
      "setOwningPackage",
      String::class.java,
    )
    setOwningPkg.isAccessible = true
    setOwningPkg.invoke(fo, packageName)
    return fo.identifier
  }
}
