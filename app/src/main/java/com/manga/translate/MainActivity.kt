package com.manga.translate

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.manga.translate.BuildConfig
import com.google.android.material.tabs.TabLayoutMediator
import com.manga.translate.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var crashStateStore: CrashStateStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        crashStateStore = CrashStateStore(this)

        pagerAdapter = MainPagerAdapter(this)
        binding.mainPager.adapter = pagerAdapter
        binding.mainPager.isUserInputEnabled = true
        TabLayoutMediator(binding.mainTabs, binding.mainPager) { tab, position ->
            tab.setText(pagerAdapter.getTitleRes(position))
        }.attach()
        binding.mainPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.mainPager.isUserInputEnabled = position != MainPagerAdapter.READING_INDEX
            }
        })
        maybeShowCrashDialog()
        checkForUpdate()
    }

    fun switchToTab(index: Int) {
        binding.mainPager.setCurrentItem(index, true)
    }

    private fun checkForUpdate() {
        if (hasCheckedUpdate) return
        hasCheckedUpdate = true
        lifecycleScope.launch {
            val updateInfo = UpdateChecker.fetchUpdateInfo()
            if (updateInfo == null) return@launch
            if (!isNewerVersion(updateInfo.versionName, BuildConfig.VERSION_NAME)) return@launch
            if (isFinishing || isDestroyed) return@launch
            showUpdateDialog(updateInfo)
        }
    }

    private fun showUpdateDialog(updateInfo: UpdateInfo) {
        val versionLabel = updateInfo.versionName.ifBlank { updateInfo.versionCode.toString() }
        val message = if (updateInfo.changelog.isNotBlank()) {
            getString(R.string.update_dialog_message, updateInfo.changelog)
        } else {
            getString(R.string.update_dialog_message_default)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_dialog_title, versionLabel))
            .setMessage(message)
            .setNegativeButton(R.string.update_dialog_cancel, null)
            .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                startDownload(updateInfo)
            }
            .setNeutralButton(R.string.update_dialog_open_release) { _, _ ->
                openUrl(RELEASES_URL)
            }
            .show()
    }

    private fun startDownload(updateInfo: UpdateInfo) {
        val request = DownloadManager.Request(Uri.parse(updateInfo.apkUrl))
            .setTitle(getString(R.string.update_download_title, updateInfo.versionName))
            .setDescription(getString(R.string.update_download_description))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val downloadManager = getSystemService(DownloadManager::class.java)
        if (downloadManager == null) {
            AppLogger.log("MainActivity", "DownloadManager not available")
            return
        }
        downloadManager.enqueue(request)
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            AppLogger.log("MainActivity", "No activity to open url: $url", e)
        }
    }

    private fun maybeShowCrashDialog() {
        if (!crashStateStore.wasCrashedLastRun()) return
        crashStateStore.clearCrashFlag()
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_dialog_title)
            .setMessage(R.string.crash_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.crash_dialog_share) { _, _ ->
                shareLatestLog()
            }
            .show()
    }

    private fun shareLatestLog() {
        val latest = AppLogger.listLogFiles().firstOrNull()
        if (latest == null || !latest.exists()) {
            AppLogger.log("MainActivity", "No crash logs available to share")
            android.widget.Toast.makeText(this, R.string.logs_empty, android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            latest
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, getString(R.string.crash_dialog_share))
        val manager = packageManager
        if (chooser.resolveActivity(manager) != null) {
            startActivity(chooser)
        } else {
            AppLogger.log("MainActivity", "No activity to share crash logs")
        }
    }

    companion object {
        private const val RELEASES_URL = "https://github.com/jedzqer/manga-translator/releases"
        private var hasCheckedUpdate = false
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        return compareVersionName(remote, local) > 0
    }

    private fun compareVersionName(left: String, right: String): Int {
        val leftParts = extractVersionParts(left)
        val rightParts = extractVersionParts(right)
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (i in 0 until maxSize) {
            val l = leftParts.getOrElse(i) { 0 }
            val r = rightParts.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun extractVersionParts(version: String): List<Int> {
        val matcher = Regex("\\d+").findAll(version)
        val parts = mutableListOf<Int>()
        for (match in matcher) {
            parts.add(match.value.toIntOrNull() ?: 0)
        }
        return parts
    }
}
