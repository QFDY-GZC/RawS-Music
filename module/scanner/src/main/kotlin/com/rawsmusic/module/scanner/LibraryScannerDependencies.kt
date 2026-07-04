package com.rawsmusic.module.scanner

import android.content.Context

object LibraryScannerDependencies {
    private var repositoryFactory: ((Context) -> AudioLibraryRepository)? = null

    fun install(factory: (Context) -> AudioLibraryRepository) {
        repositoryFactory = factory
    }

    fun repository(context: Context): AudioLibraryRepository {
        return repositoryFactory?.invoke(context.applicationContext)
            ?: error("LibraryScannerDependencies is not installed")
    }

    fun repositoryOrNull(context: Context): AudioLibraryRepository? {
        return repositoryFactory?.invoke(context.applicationContext)
    }

    fun isInstalled(): Boolean = repositoryFactory != null
}
