package com.photovault.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.photovault.app.data.EndpointRouter
import com.photovault.app.data.NasApi
import com.photovault.app.data.NasConfig
import com.photovault.app.data.SettingsRepo
import com.photovault.app.work.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepo(app)

    val stored: StateFlow<NasConfig> =
        repo.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NasConfig())

    var draft by mutableStateOf(NasConfig())
        private set

    var testing by mutableStateOf(false)
    var testResult by mutableStateOf<String?>(null)

    /** 上一条提示是好消息还是坏消息：null 表示中性（比如「已退出登录」）。 */
    var testOk by mutableStateOf<Boolean?>(null)
    var savedTick by mutableStateOf(0)

    /** 稳定的设备 ID（由 Android ID 派生，卸载重装不变）。 */
    var deviceId by mutableStateOf("")
        private set
    var deviceIdMsg by mutableStateOf<String?>(null)
        private set

    /** 回收站数量（登录后拉取，清空后归零）。 */
    var trashCount by mutableStateOf(0)
        private set
    var emptying by mutableStateOf(false)
        private set
    var trashMsg by mutableStateOf<String?>(null)
        private set

    /** 登录表单（不落盘，除非用户勾选记住密码） */
    var loginUser by mutableStateOf("")
        private set
    var loginPass by mutableStateOf("")
        private set
    var rememberPw by mutableStateOf(true)
        private set
    var loggingIn by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    private var loaded = false

    init {
        viewModelScope.launch {
            repo.config.collect {
                if (!loaded) {
                    draft = it
                    loginUser = it.username
                    if (it.savedPassword.isNotBlank()) loginPass = it.savedPassword
                    loaded = true
                    if (it.loggedIn) refreshTrash()
                }
            }
        }
        viewModelScope.launch { deviceId = repo.deviceId() }
    }

    /** 拉取回收站数量。未登录 / 网络异常时静默失败（保持 0），不影响其它设置。 */
    fun refreshTrash() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { NasApi(draft).trashCount() } }
                .onSuccess { trashCount = it }
        }
    }

    /** 一键清空回收站（不可逆）。成功后会把数量归零。 */
    fun emptyTrash() {
        viewModelScope.launch {
            emptying = true
            trashMsg = runCatching {
                val n = withContext(Dispatchers.IO) { NasApi(draft).emptyTrash() }
                trashCount = 0
                "已清空回收站，共删除 $n 张"
            }.getOrElse { "清空失败：${it.message}" }
            emptying = false
        }
    }

    fun edit(fn: (NasConfig) -> NasConfig) {
        draft = fn(draft)
        testResult = null
    }

    /**
     * 备份相关设置（开关/周期/还原策略）改动后立即落盘并应用调度，
     * 避免用户改了「检查周期」却忘点顶部「保存」而丢失。
     * 只持久化备份相关字段，绝不覆盖服务器卡片里可能正在改、但还没点保存的地址/登录态。
     */
    fun editBackup(fn: (NasConfig) -> NasConfig) {
        draft = fn(draft)
        testResult = null
        viewModelScope.launch {
            val storedCfg = repo.current()
            val merged = storedCfg.copy(
                backupEnabled = draft.backupEnabled,
                wifiOnly = draft.wifiOnly,
                chargingOnly = draft.chargingOnly,
                includeVideo = draft.includeVideo,
                syncDeletions = draft.syncDeletions,
                periodMinutes = draft.periodMinutes,
                restoreConflict = draft.restoreConflict,
            )
            repo.save(merged)
            UploadScheduler.apply(getApplication(), merged)
            savedTick++
        }
    }

    // 方法名不能叫 setXxx：`var loginUser by mutableStateOf()` 已经生成了 setLoginUser
    fun typeUser(v: String) { loginUser = v; loginError = null }
    fun typePass(v: String) { loginPass = v; loginError = null }
    fun toggleRemember(v: Boolean) { rememberPw = v }

    /** 账号密码登录：成功后把 token 和用户信息落盘。 */
    fun login() {
        viewModelScope.launch {
            loggingIn = true
            loginError = null
            runCatching {
                val r = withContext(Dispatchers.IO) { NasApi.loginDual(draft, loginUser, loginPass) }
                repo.save(draft)
                repo.saveLogin(r, if (rememberPw) loginPass else "")
                draft = repo.current()
                UploadScheduler.apply(getApplication(), draft)
                savedTick++
                "已登录：${r.displayName}（${if (r.isAdmin) "管理员" else "普通用户"}）"
            }.onSuccess { testResult = it; testOk = true; refreshTrash() }
                .onFailure {
                    loginError = com.photovault.app.util.Errors.humanize(it)
                    testResult = null
                    testOk = false
                }
            loggingIn = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            // 先让服务端吊销当前会话，再清本地；否则 token 还能用满 30 天
            runCatching { withContext(Dispatchers.IO) { NasApi(draft).logout() } }
            repo.logout()
            draft = repo.current()
            loginPass = ""
            savedTick++
            testResult = "已退出登录"
            testOk = null
        }
    }

    fun test() {
        viewModelScope.launch {
            testing = true
            testResult = runCatching {
                if (!draft.configured) throw IllegalArgumentException("请先填写内网或外网地址")
                withContext(Dispatchers.IO) {
                    val started = System.nanoTime()
                    val version = NasApi(draft).health()
                    val ms = ((System.nanoTime() - started) / 1_000_000).toInt()
                    val active = EndpointRouter.active(draft)
                    "连接成功 · ${if (EndpointRouter.isLan(draft, active)) "内网" else "外网"} · ${ms}ms" +
                        (version?.let { " · v$it" } ?: "")
                }
            }.getOrElse { "连接失败：${com.photovault.app.util.Errors.humanize(it)}" }
            // 结果是好是坏单独记一个标记：以前靠文案 startsWith("连接成功") 判断，
            // 登录成功的「已登录：xxx」匹配不上，被当成错误涂成红色。
            testOk = testResult?.startsWith("连接成功") == true
            testing = false
        }
    }

    /**
     * 只把设置页真正编辑的字段合并回已存储的配置。
     *
     * 以前是 `repo.save(draft)`，而 draft 只在页面首次加载时读一次：
     * 用户在「备份」页改了相册选择/自动备份开关、或刚登录换了 token，
     * 回来点一下「保存」就会被这份旧 draft 全部覆盖掉。
     */
    fun save() {
        viewModelScope.launch {
            val stored = repo.current()
            val merged = stored.copy(
                lanUrl = draft.lanUrl.trim(),
                wanUrl = draft.wanUrl.trim(),
                baseUrl = draft.lanUrl.trim().ifBlank { draft.wanUrl.trim() },
                backupEnabled = draft.backupEnabled,
                wifiOnly = draft.wifiOnly,
                chargingOnly = draft.chargingOnly,
                includeVideo = draft.includeVideo,
                syncDeletions = draft.syncDeletions,
                periodMinutes = draft.periodMinutes,
                restoreConflict = draft.restoreConflict,
            )
            repo.save(merged)
            draft = merged
            EndpointRouter.invalidate()
            UploadScheduler.apply(getApplication(), merged)
            savedTick++
        }
    }

    fun resetSyncPoint() {
        viewModelScope.launch {
            repo.resetSync()
            savedTick++
        }
    }

    /** 用户自定义设备 ID：先让服务端把旧 ID 的照片迁过来，再落盘。 */
    fun changeDeviceId(newId: String) {
        val id = newId.trim()
        if (!Regex("^[A-Za-z0-9._-]{4,64}$").matches(id)) {
            deviceIdMsg = "只能包含字母、数字、点、下划线、短横线，长度 4~64"
            return
        }
        viewModelScope.launch {
            deviceIdMsg = "正在迁移设备记录…"
            runCatching {
                val old = repo.deviceId()
                if (old != id) {
                    withContext(Dispatchers.IO) { NasApi(repo.current()).renameDevice(old, id) }
                }
                repo.setDeviceId(id, custom = true)
                deviceId = id
                deviceIdMsg = "已保存，自定义设备 ID 会优先于自动 ID"
            }.onFailure {
                deviceIdMsg = "修改失败：${it.message}"
            }
        }
    }
}
