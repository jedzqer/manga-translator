package com.manga.translate

import java.io.File

internal interface LibraryUiCallbacks {
    fun setFolderStatus(left: String, right: String = "")
    fun clearFolderStatus()
    fun showToast(resId: Int)
    fun showToastMessage(message: String)
    fun showApiError(code: String)
    fun showModelError(content: String)
    fun refreshFolders()
    fun refreshImages(folder: File)
    fun isFragmentActive(): Boolean
}
