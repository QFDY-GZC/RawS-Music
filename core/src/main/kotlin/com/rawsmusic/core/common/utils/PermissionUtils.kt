package com.rawsmusic.core.common.utils

import android.content.Context
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object PermissionUtils {

    suspend fun requestPermissions(
        context: Context,
        vararg permissions: String
    ): Boolean {
        return suspendCancellableCoroutine { continuation ->
            Dexter.withContext(context)
                .withPermissions(*permissions)
                .withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport) {
                        continuation.resume(report.areAllPermissionsGranted())
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        requests: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        token?.continuePermissionRequest()
                    }
                })
                .check()
        }
    }

    suspend fun requestStoragePermission(context: Context): Boolean {
        return requestPermissions(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    suspend fun requestMediaPermission(context: Context): Boolean {
        return requestPermissions(
            context,
            android.Manifest.permission.READ_MEDIA_AUDIO
        )
    }
}
