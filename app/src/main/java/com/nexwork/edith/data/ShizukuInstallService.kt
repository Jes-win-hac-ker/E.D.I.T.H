package com.nexwork.edith.data

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's privileged `shell` (`uid 2000`) process via [rikka.shizuku.Shizuku.bindUserService].
 * Accepts a read-only APK [ParcelFileDescriptor] and streams it directly to `pm install -r -S`,
 * avoiding hidden APIs, reflection, and system package-installer popups.
 */
@Keep
class ShizukuInstallService : Binder {

  @Keep
  constructor() : super()

  @Keep
  constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    if (code == TRANSACTION_DESTROY) {
      reply?.writeNoException()
      exitProcess(0)
    }
    if (code != TRANSACTION_INSTALL) {
      return super.onTransact(code, data, reply, flags)
    }

    data.enforceInterface(DESCRIPTOR)
    val pfd = data.readFileDescriptor()
    val size = data.readLong()
    if (pfd == null || size <= 0L) {
      reply?.writeNoException()
      reply?.writeInt(0)
      reply?.writeString("Invalid APK descriptor")
      return true
    }

    return try {
      val process = ProcessBuilder(
        "pm",
        "install",
        "-r",
        "-i",
        "com.android.shell",
        "-S",
        size.toString(),
      )
        .redirectErrorStream(true)
        .start()

      ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
        process.outputStream.use { output ->
          input.copyTo(output, bufferSize = 32 * 1024)
          output.flush()
        }
      }

      val output = process.inputStream.bufferedReader().readText().trim()
      val exit = process.waitFor()
      val ok = exit == 0 && output.contains("Success", ignoreCase = true)
      reply?.writeNoException()
      reply?.writeInt(if (ok) 1 else 0)
      reply?.writeString(output)
      true
    } catch (t: Throwable) {
      reply?.writeNoException()
      reply?.writeInt(0)
      reply?.writeString(t.message ?: t.javaClass.simpleName)
      true
    }
  }

  companion object {
    const val DESCRIPTOR = "com.nexwork.edith.IShizukuInstallService"
    const val TRANSACTION_INSTALL = FIRST_CALL_TRANSACTION
    private const val TRANSACTION_DESTROY = 16777214
  }
}
