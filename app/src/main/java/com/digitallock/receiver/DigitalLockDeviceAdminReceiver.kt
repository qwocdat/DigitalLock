package com.digitallock.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * DeviceAdminReceiver used when the user explicitly provisions DigitalLock as a Device Owner
 * (`adb shell dpm set-device-owner com.digitallock/.receiver.DigitalLockDeviceAdminReceiver`)
 * to enable hardware/OS-backed Lock Task Mode (`startLockTask()`).
 */
class DigitalLockDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
