package com.simplereader.sync

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.concurrent.AtomicBoolean
import androidx.work.WorkInfo
import androidx.work.WorkManager

// singleton to observe/report whether we are currently syncing with the server or not
object SyncStatus {
    private val _isSyncing = MutableLiveData(false)
    val isSyncing: LiveData<Boolean> = _isSyncing

    private var started = AtomicBoolean(false)

    fun start(appContext: Context) {
        if (!started.compareAndSet(false, true)) return

        val wm = WorkManager.getInstance(appContext)
        wm.getWorkInfosByTagLiveData(SyncManager.TAG_SYNC)
          .observeForever { infos ->

            // are there any running or enqueued workers?
            val syncing = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

            // flip sync status if it has changed
            if (_isSyncing.value != syncing)
                _isSyncing.value = syncing

          }
    }
}
