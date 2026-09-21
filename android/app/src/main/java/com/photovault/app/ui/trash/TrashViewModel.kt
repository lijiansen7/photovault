package com.photovault.app.ui.trash

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.app.data.NasApi
import com.photovault.app.data.NasConfig
import com.photovault.app.data.PhotoDto
import com.photovault.app.data.SettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrashViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepo(app)

    val config: StateFlow<NasConfig> =
        repo.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NasConfig())

    var items by mutableStateOf<List<PhotoDto>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var selectMode by mutableStateOf(false)
    var selection by mutableStateOf(setOf<String>())
    var msg by mutableStateOf<String?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            val cfg = repo.current()
            if (!cfg.configured) {
                error = "还没有配置 NAS 地址"
                loading = false
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { NasApi(cfg).trashList(300) }
                    .onSuccess { (list, t) -> items = list; total = t }
                    .onFailure { error = it.message ?: "加载失败" }
            }
            loading = false
        }
    }

    fun toggleSelect(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun toggleSelectAll() {
        selection = if (selection.size == items.size) emptySet() else items.map { it.id }.toSet()
    }

    fun exitSelect() {
        selectMode = false
        selection = emptySet()
    }

    /** 选择性删除：只永久删掉勾选的这几条，其余留在回收站。 */
    fun deleteSelected() {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            busy = true
            msg = null
            val cfg = repo.current()
            withContext(Dispatchers.IO) {
                runCatching { NasApi(cfg).purgeTrash(ids) }
                    .onSuccess { n ->
                        msg = "已永久删除 $n 张"
                        selection = emptySet()
                        selectMode = false
                        refresh()
                    }
                    .onFailure { msg = "删除失败：${it.message}" }
            }
            busy = false
        }
    }

    /** 清空全部（不可逆）。 */
    fun emptyAll() {
        viewModelScope.launch {
            busy = true
            msg = null
            val cfg = repo.current()
            withContext(Dispatchers.IO) {
                runCatching { NasApi(cfg).emptyTrash() }
                    .onSuccess { n ->
                        msg = "已清空回收站，共删除 $n 张"
                        selection = emptySet()
                        selectMode = false
                        refresh()
                    }
                    .onFailure { msg = "清空失败：${it.message}" }
            }
            busy = false
        }
    }

    fun thumbUrl(id: String): String = NasApi(config.value).thumbUrl(id)
}
