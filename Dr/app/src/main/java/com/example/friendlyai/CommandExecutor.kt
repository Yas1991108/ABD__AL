package com.example.friendlyai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.Surface

object CommandExecutor {

    fun execute(context: Context, command: String) {
        when {
            command.contains("whatsapp") -> openApp(context, "com.whatsapp")
            command.contains("youtube") -> openApp(context, "com.google.android.youtube")
            command.contains("rotate") -> rotateScreen(context)
        }
    }

    private fun openApp(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) context.startActivity(intent)
    }

    private fun rotateScreen(context: Context) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        )
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.USER_ROTATION,
            Surface.ROTATION_90
        )
    }
}
