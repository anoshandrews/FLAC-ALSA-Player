package dev.anosh.musicplayer.usb

import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

class UsbDeviceManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val usbManager = context.getSystemService(UsbManager::class.java)

    fun getConnectedAudioDevices(): List<UsbAudioDeviceSummary> {
        val devices = usbManager?.deviceList?.values.orEmpty()
        return devices
            .filter(::isAudioDevice)
            .map { device ->
                val connection = usbManager?.openDevice(device)
                val rawDescriptors = connection?.rawDescriptors ?: ByteArray(0)
                connection?.close()
                UsbAudioDescriptorParser.parse(device, rawDescriptors)
            }
            .sortedBy { it.productName ?: it.manufacturerName ?: it.deviceName }
    }

    fun hasPermission(deviceName: String): Boolean {
        val device = usbManager?.deviceList?.values?.firstOrNull { it.deviceName == deviceName } ?: return false
        return usbManager.hasPermission(device)
    }

    fun requestPermission(deviceName: String, action: String) {
        val device = usbManager?.deviceList?.values?.firstOrNull { it.deviceName == deviceName } ?: return
        val intent = Intent(action).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            device.deviceId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) {
            return true
        }

        repeat(device.interfaceCount) { index ->
            if (device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }

        return false
    }
}
