package com.sportfit.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sportfit.sync.AppContainer
import com.sportfit.sync.cache.CacheManager
import com.sportfit.sync.cache.CachePolicy
import com.sportfit.sync.cache.CacheSnapshot
import com.sportfit.sync.data.local.AccountEntity
import com.sportfit.sync.data.local.CachedActivityEntity
import com.sportfit.sync.data.local.StrategyEntity
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.data.local.SyncRecordEntity
import com.sportfit.sync.data.prefs.AppSettings
import com.sportfit.sync.domain.ActivityFingerprint
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.SyncDirection
import com.sportfit.sync.domain.SyncStatus
import com.sportfit.sync.sync.SyncEvent
import com.sportfit.sync.sync.SyncRequest
import com.sportfit.sync.sync.SyncSummary
import com.sportfit.sync.platform.strava.StravaOAuth
import com.sportfit.sync.sync.toUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Live state of the sync screen. */
data class SyncRunState(
    val running: Boolean = false,
    val jobId: Long? = null,
    val direction: SyncDirection? = null,
    val total: Int = 0,
    val index: Int = 0,
    val currentName: String? = null,
    val logs: List<LogLine> = emptyList(),
    val summary: SyncSummary? = null,
    val error: String? = null,
) {
    data class LogLine(val level: String, val message: String)

    val progress: Float
        get() = if (total <= 0) 0f else (index.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Single view model for the whole app.
 *
 * The domain is small (one account per platform, one sync at a time) and the
 * sync is a single long-running operation, so splitting this across many view
 * models would add plumbing without adding clarity.
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val accountRepository = container.accountRepository
    private val syncEngine = container.syncEngine
    private val cacheManager = container.cacheManager
    private val settingsStore = container.settingsStore

    val accounts: StateFlow<List<AccountEntity>> = accountRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val jobs: StateFlow<List<SyncJobEntity>> = container.database.syncDao()
        .observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 每个平台本地缓存的活动条数，用于「账号管理」页展示已拉取多少历史。 */
    val activityCounts: StateFlow<Map<String, Int>> = container.database.activityDao()
        .observeCounts()
        .map { rows -> rows.associate { it.platform to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val strategies: StateFlow<List<StrategyEntity>> = container.database.strategyDao()
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingMfa: StateFlow<Platform?> = container.mfaCoordinator.pending

    private val _source = MutableStateFlow(Platform.GARMIN)
    val source: StateFlow<Platform> = _source.asStateFlow()

    private val _target = MutableStateFlow(Platform.COROS)
    val target: StateFlow<Platform> = _target.asStateFlow()

    /**
     * 当前方向（源平台 → 目标平台）。
     *
     * 合成一条流而不是让下游各自去 combine：以前切一次方向会先发一次 _source、
     * 再发一次 _target，下游的 flatMapLatest 因此**重启两次**，等于把同一批
     * 数据库查询和指纹计算白做一遍。合成一条流 + distinctUntilChanged 之后，
     * 一次切换只重启一次。
     */
    private val directionFlow: Flow<SyncDirection> =
        kotlinx.coroutines.flow.combine(_source, _target) { source, target ->
            SyncDirection(source, target)
        }.distinctUntilChanged()

    private val _runState = MutableStateFlow(SyncRunState())
    val runState: StateFlow<SyncRunState> = _runState.asStateFlow()

    private val _cache = MutableStateFlow(CacheSnapshot(emptyList()))
    val cache: StateFlow<CacheSnapshot> = _cache.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Derived counts for the home screen's "what will be synced" summary. */
    data class ActivitySummary(
        val cached: Int = 0,
        val pending: Int = 0,
        val synced: Int = 0,
        val failed: Int = 0,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activitySummary: StateFlow<ActivitySummary> =
        directionFlow
            .flatMapLatest { direction ->
                kotlinx.coroutines.flow.combine(
                    container.database.activityDao().observeFor(direction.source.key, PICKER_LIMIT),
                    container.database.syncDao()
                        .observeRecordsFor(direction.source.key, direction.target.key),
                    container.database.exclusionDao()
                        .observeFor(direction.source.key, direction.target.key),
                    container.database.activityDao().observeCounts(),
                ) { activities, records, exclusions, counts ->
                    val statusById = records.associate { it.activityId to SyncStatus.fromCode(it.status) }
                    val syncedFingerprints = records.syncedFingerprints()
                    val excluded = exclusions.map { it.activityId }.toSet()
                    // 一趟遍历把三个计数一起算出来。
                    // 以前是三个 count 各算一遍，等于同一条活动算了三次指纹
                    // （每次是一次 SHA-256），一千条活动就是三千次 —— 而且
                    // 这段计算跑在主线程上，切一次方向能把整个界面卡住半秒。
                    var pending = 0
                    var failed = 0
                    var eligible = 0
                    for (activity in activities) {
                        if (activity.activityId in excluded) continue
                        eligible++
                        when (statusOf(activity, statusById, syncedFingerprints)) {
                            SyncStatus.SYNCED -> Unit
                            SyncStatus.FAILED -> {
                                pending++
                                failed++
                            }
                            else -> pending++
                        }
                    }
                    ActivitySummary(
                        // 本地一共存了多少条：用真实计数，而不是这一页显示的条数。
                        cached = counts.firstOrNull { it.platform == direction.source.key }?.count
                            ?: activities.size,
                        pending = pending,
                        synced = eligible - pending,
                        failed = failed,
                    )
                }
            }
            // 指纹计算是纯 CPU 活，挪到后台线程：切方向时界面不能被它堵住。
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ActivitySummary(),
            )

    private var syncJob: Job? = null

    // ---- 内容指纹（跨 activityId 识别同一份数据）--------------------------

    /**
     * 该方向上已同步活动的「内容指纹」集合。
     *
     * 源平台可能给同一份活动发一个新的 activityId（重新导入 FIT、换同平台的
     * 另一个账号、平台自身重发 id），这时只有内容能证明「这条已经同步过了」。
     */
    private fun List<SyncRecordEntity>.syncedFingerprints(): Set<String> =
        filter { it.status == SyncStatus.SYNCED.code }
            .mapNotNull { it.fingerprint.takeIf { fp -> fp.isNotEmpty() } }
            .toSet()

    /** 一条活动的实际状态：内容与已同步的数据相同，就按已同步处理。 */
    private fun statusOf(
        activity: CachedActivityEntity,
        statusById: Map<String, SyncStatus>,
        syncedFingerprints: Set<String>,
    ): SyncStatus {
        val fingerprint = ActivityFingerprint.of(
            activity.startTime,
            activity.sportType,
            activity.distanceMeters,
            activity.durationSec,
            activity.name,
        )
        if (fingerprint.isNotEmpty() && fingerprint in syncedFingerprints) return SyncStatus.SYNCED
        return statusById[activity.activityId] ?: SyncStatus.UNSYNCED
    }

    init {
        // Seed the direction from the persisted defaults, once.
        viewModelScope.launch {
            val current = settingsStore.current()
            _source.value = current.defaultSource
            _target.value = current.defaultTarget
            refreshCache()
        }
    }

    // ---- direction --------------------------------------------------------

    /**
     * Selecting the platform that currently sits on the *other* side is a
     * request to swap, not a mistake.
     *
     * The previous behaviour disabled those chips, which made the common
     * "I want the other direction" gesture appear broken: with Garmin→Coros
     * active, both the source-row Coros chip and the target-row Garmin chip
     * were greyed out, so the only way through was the small swap button.
     */
    fun setSource(platform: Platform) {
        swapAware(
            incoming = platform,
            other = _target.value,
            apply = { _source.value = it },
            otherCanMove = { it.canBeTarget },
            moveOther = { _target.value = it },
            previous = _source.value,
        )
        persistDirection()
    }

    fun setTarget(platform: Platform) {
        swapAware(
            incoming = platform,
            other = _source.value,
            apply = { _target.value = it },
            otherCanMove = { it.canBeSource },
            moveOther = { _source.value = it },
            previous = _target.value,
        )
        persistDirection()
    }

    private inline fun swapAware(
        incoming: Platform,
        other: Platform,
        apply: (Platform) -> Unit,
        otherCanMove: (Platform) -> Boolean,
        moveOther: (Platform) -> Unit,
        previous: Platform,
    ) {
        apply(incoming)
        // Rehearsal mode is allowed to be both sides so it can run offline.
        if (incoming == other && incoming != Platform.DEMO && otherCanMove(previous)) {
            moveOther(previous)
        }
    }

    fun swapDirection() {
        val currentSource = _source.value
        val currentTarget = _target.value
        if (currentTarget.canBeSource) {
            _source.value = currentTarget
            if (currentSource.canBeTarget) _target.value = currentSource
            persistDirection()
        }
    }

    /**
     * Remembers the direction the user last chose, so reopening the app does
     * not silently fall back to a different pair of platforms.
     */
    private fun persistDirection() {
        viewModelScope.launch {
            settingsStore.setDefaults(_source.value, _target.value, settings.value.listLimit)
        }
    }

    fun currentDirection(): SyncDirection = SyncDirection(_source.value, _target.value)

    // ---- accounts ---------------------------------------------------------

    fun saveAccount(
        platform: Platform,
        email: String?,
        password: String?,
        region: String?,
        token: String? = null,
    ) {
        viewModelScope.launch {
            accountRepository.save(
                platform = platform,
                email = email?.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() },
                region = region,
                token = token,
                status = AccountEntity.STATUS_NEEDS_LOGIN,
            )
            _message.value = "${platform.displayName} 账号已保存，正在拉取历史活动…"
            warmUpAccount(platform)
        }
    }

    /**
     * 配置完账号立刻把该账号的历史活动全量拉进本地库。
     *
     * 之后的每次同步只做增量（网络上只取最新的一批），所以这一步决定了
     * 「选择活动同步」页能看到多少历史数据，也决定了老活动能不能被同步到。
     */
    private suspend fun warmUpAccount(platform: Platform) {
        try {
            val count = syncEngine.warmUpPlatform(platform)
            _message.value = "${platform.displayName} 已拉取 $count 条历史活动到本地"
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 账号本身可能是好的，只是列表拉取失败：把原因记到账号上，
            // 用户在「账号管理」页能看到，而不是只飘一条转瞬即逝的提示。
            accountRepository.updateStatus(platform, AccountEntity.STATUS_ERROR, error.toUserMessage())
            _message.value = "${platform.displayName} 拉取历史活动失败：${error.toUserMessage()}"
        }
    }

    fun deleteAccount(platform: Platform) {
        viewModelScope.launch {
            accountRepository.delete(platform)
            _message.value = "已删除 ${platform.displayName} 账号"
        }
    }

    suspend fun account(platform: Platform): AccountEntity? = accountRepository.get(platform)

    suspend fun maskedEmail(row: AccountEntity): String = accountRepository.maskedEmail(row)

    // ---- sync -------------------------------------------------------------

    fun startSync(
        selectedIds: Set<String>? = null,
        includeAlreadySynced: Boolean = false,
    ) {
        if (_runState.value.running) return
        val direction = currentDirection()

        _runState.value = SyncRunState(running = true, direction = direction)
        // A long run must survive the user leaving the app, so it is backed by
        // a foreground service that renders progress in the notification shade.
        com.sportfit.sync.sync.SyncProgressBus.publish(
            com.sportfit.sync.sync.SyncProgress(
                directionLabel = direction.label,
                index = 0,
                total = 0,
                currentName = null,
                running = true,
            ),
        )
        com.sportfit.sync.sync.SyncService.start(container.context)
        syncJob = viewModelScope.launch {
            try {
                syncEngine
                    .run(
                        SyncRequest(
                            source = direction.source,
                            target = direction.target,
                            selectedIds = selectedIds,
                            includeAlreadySynced = includeAlreadySynced,
                            listLimit = settings.value.listLimit,
                        ),
                    )
                    .collect { event -> handleEvent(event) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _runState.update { it.copy(running = false) }
                throw cancelled
            } catch (error: Exception) {
                _runState.update {
                    it.copy(running = false, error = error.toUserMessage())
                }
            } finally {
                com.sportfit.sync.sync.SyncService.stop(container.context)
                refreshCache()
            }
        }
    }

    private fun handleEvent(event: SyncEvent) {
        when (event) {
            is SyncEvent.Log -> _runState.update { state ->
                // Keep the in-memory tail bounded; the DB holds the full log.
                val next = (state.logs + SyncRunState.LogLine(event.level, event.message))
                state.copy(logs = next.takeLast(MAX_UI_LOG_LINES))
            }

            is SyncEvent.Started -> {
                _runState.update {
                    it.copy(jobId = event.jobId, direction = event.direction, total = event.total, index = 0)
                }
                publishProgress(event.direction.label, 0, event.total, null)
            }

            is SyncEvent.ActivityStarted -> {
                val name = event.activity.name.ifBlank { event.activity.id }
                _runState.update { it.copy(index = event.index, currentName = name) }
                publishProgress(
                    directionLabel = _runState.value.direction?.label ?: "",
                    index = event.index,
                    total = event.total,
                    currentName = name,
                )
            }

            is SyncEvent.ActivityFinished -> _runState.update {
                it.copy(index = event.index + 1)
            }

            is SyncEvent.Finished -> _runState.update {
                it.copy(running = false, summary = event.summary, currentName = null)
            }
        }
    }

    private fun publishProgress(
        directionLabel: String,
        index: Int,
        total: Int,
        currentName: String?,
    ) {
        com.sportfit.sync.sync.SyncProgressBus.publish(
            com.sportfit.sync.sync.SyncProgress(
                directionLabel = directionLabel,
                index = index,
                total = total,
                currentName = currentName,
                running = true,
            ),
        )
    }

    /**
     * Exports the selected source activities as `.fit` files into the user's
     * Downloads folder. Reuses the sync progress/log pipeline so the UI needs
     * no special casing.
     */
    fun startExport(selectedIds: Set<String>) {
        if (_runState.value.running || selectedIds.isEmpty()) return
        val source = _source.value
        val direction = SyncDirection(source, Platform.LOCAL_FILES)

        _runState.value = SyncRunState(running = true, direction = direction)
        com.sportfit.sync.sync.SyncProgressBus.publish(
            com.sportfit.sync.sync.SyncProgress(
                directionLabel = "${source.displayName} → 本地文件",
                index = 0,
                total = selectedIds.size,
                currentName = null,
                running = true,
            ),
        )
        com.sportfit.sync.sync.SyncService.start(container.context)

        syncJob = viewModelScope.launch {
            try {
                container.exportEngine
                    .run(
                        com.sportfit.sync.sync.ExportRequest(
                            source = source,
                            selectedIds = selectedIds,
                            listLimit = settings.value.listLimit,
                        ),
                    )
                    .collect { event -> handleEvent(event) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _runState.update { it.copy(running = false) }
                throw cancelled
            } catch (error: Exception) {
                _runState.update { it.copy(running = false, error = error.toUserMessage()) }
            } finally {
                com.sportfit.sync.sync.SyncService.stop(container.context)
                refreshCache()
            }
        }
    }

    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        com.sportfit.sync.sync.SyncService.stop(container.context)
        _runState.update { it.copy(running = false) }
    }

    fun clearRunState() {
        _runState.value = SyncRunState()
    }

    fun retryFailed() {
        val direction = _runState.value.direction ?: currentDirection()
        viewModelScope.launch {
            val requeued = syncEngine.requeueFailed(direction.source, direction.target)
            _message.value = if (requeued > 0) "已重置 $requeued 条失败记录，开始重试" else "没有失败的记录"
            if (requeued > 0) {
                _source.value = direction.source
                _target.value = direction.target
                startSync()
            }
        }
    }

    // ---- saved strategies -------------------------------------------------

    /**
     * Saves the currently selected direction so it can be re-used in one tap.
     * Idempotent per direction: saving the same pair twice updates the name
     * instead of creating a duplicate row.
     */
    fun saveCurrentAsStrategy(name: String? = null) {
        val direction = currentDirection()
        viewModelScope.launch {
            val dao = container.database.strategyDao()
            val existing = dao.find(direction.source.key, direction.target.key)
            dao.upsert(
                StrategyEntity(
                    id = existing?.id ?: 0,
                    name = name?.takeIf { it.isNotBlank() } ?: direction.label,
                    sourcePlatform = direction.source.key,
                    targetPlatform = direction.target.key,
                    enabled = existing?.enabled ?: true,
                    useForBackground = existing?.useForBackground ?: false,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            _message.value = if (existing == null) {
                "已保存策略「${name ?: direction.label}」"
            } else {
                "已更新策略「${name ?: direction.label}」"
            }
        }
    }

    fun applyStrategy(strategy: StrategyEntity) {
        val source = Platform.fromKey(strategy.sourcePlatform) ?: return
        val target = Platform.fromKey(strategy.targetPlatform) ?: return
        _source.value = source
        _target.value = target
        persistDirection()
        _message.value = "已切换到 ${strategy.name}"
    }

    fun deleteStrategy(id: Long) {
        viewModelScope.launch {
            container.database.strategyDao().delete(id)
            _message.value = "已删除该策略"
            container.applyBackgroundSchedule()
        }
    }

    /** Marks one strategy as the direction used by the scheduled run. */
    fun setStrategyBackground(id: Long, flag: Boolean) {
        viewModelScope.launch {
            val dao = container.database.strategyDao()
            if (flag) dao.clearBackgroundExcept(id)
            dao.setBackground(id, flag)
            container.applyBackgroundSchedule()
            _message.value = if (flag) "已设为后台同步方向" else "已取消后台同步方向"
        }
    }

    // ---- ledger -----------------------------------------------------------

    fun ledger(direction: SyncDirection) =
        container.database.syncDao().observeRecordsFor(direction.source.key, direction.target.key)

    suspend fun ledgerSnapshot(direction: SyncDirection): Map<String, SyncStatus> =
        container.database.syncDao()
            .recordsFor(direction.source.key, direction.target.key)
            .associate { it.activityId to SyncStatus.fromCode(it.status) }

    suspend fun exclusions(direction: SyncDirection): Set<String> =
        container.database.exclusionDao()
            .listFor(direction.source.key, direction.target.key)
            .map { it.activityId }
            .toSet()

    fun exclude(direction: SyncDirection, activityId: String) {
        viewModelScope.launch {
            container.database.exclusionDao().upsert(
                com.sportfit.sync.data.local.ExclusionEntity(
                    sourcePlatform = direction.source.key,
                    targetPlatform = direction.target.key,
                    activityId = activityId,
                ),
            )
            _message.value = "已排除该活动，后续同步将跳过"
        }
    }

    fun unexclude(direction: SyncDirection, activityId: String) {
        viewModelScope.launch {
            container.database.exclusionDao()
                .remove(direction.source.key, direction.target.key, activityId)
        }
    }

    /**
     * 批量排除：把一批活动一次性标记为「不再同步」。
     *
     * 一次事务写入，避免几百条活动几百次往返；写完给一句反馈，
     * 让用户知道到底排除了多少条。
     */
    fun excludeMany(direction: SyncDirection, activityIds: Collection<String>) {
        val ids = activityIds.toSet()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.database.exclusionDao().upsertAll(
                ids.map {
                    com.sportfit.sync.data.local.ExclusionEntity(
                        sourcePlatform = direction.source.key,
                        targetPlatform = direction.target.key,
                        activityId = it,
                    )
                },
            )
            _message.value = "已排除 ${ids.size} 条活动，后续同步将跳过"
        }
    }

    /** 批量恢复：把一批已排除的活动放回可同步列表。 */
    fun unexcludeMany(direction: SyncDirection, activityIds: Collection<String>) {
        val ids = activityIds.toSet()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val removed = container.database.exclusionDao()
                .removeAll(direction.source.key, direction.target.key, ids.toList())
            _message.value = "已恢复 ${if (removed > 0) removed else ids.size} 条活动"
        }
    }

    suspend fun cachedActivities(platform: Platform, limit: Int = PICKER_LIMIT) =
        container.database.activityDao().listFor(platform.key, limit)

    fun job(jobId: Long) = container.database.syncDao().observeJob(jobId)

    fun jobLogs(jobId: Long) = container.database.syncDao().observeLogs(jobId)

    private val _loadingActivities = MutableStateFlow(false)
    val loadingActivities: StateFlow<Boolean> = _loadingActivities.asStateFlow()

    /** Pulls a fresh listing from the source platform, without syncing. */
    fun refreshActivityList(direction: SyncDirection) {
        if (_loadingActivities.value) return
        _loadingActivities.value = true
        viewModelScope.launch {
            try {
                // 拉全量历史：本地表就是「有哪些活动」的真相来源，
                // 之后的每次同步只从它上面做增量。
                // 拉完立刻和账本比对，界面上的「已同步」才是最新的。
                val summary = syncEngine.refreshSourceActivities(direction)
                _message.value = buildString {
                    append("本地活动列表已更新：${summary.localTotal} 条")
                    append("（已同步 ${summary.alreadySynced}")
                    if (summary.duplicatesByContent > 0) {
                        append(" · 内容相同 ${summary.duplicatesByContent}")
                    }
                    append(" · 待同步 ${summary.pending}）")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = error.toUserMessage()
            } finally {
                _loadingActivities.value = false
            }
        }
    }

    suspend fun sourceAccount(platform: Platform) = accountRepository.credentials(platform)

    /** One row of the activity picker, joining the cache, ledger and exclusions. */
    data class PickerItem(
        val activityId: String,
        val name: String,
        val startTime: Long?,
        val distanceMeters: Double?,
        val durationSec: Long?,
        val status: SyncStatus,
        val excluded: Boolean,
    )

    fun pickerItems(direction: SyncDirection): kotlinx.coroutines.flow.Flow<List<PickerItem>> =
        kotlinx.coroutines.flow.combine(
            container.database.activityDao().observeFor(direction.source.key, PICKER_LIMIT),
            container.database.syncDao()
                .observeRecordsFor(direction.source.key, direction.target.key),
            container.database.exclusionDao()
                .observeFor(direction.source.key, direction.target.key),
        ) { activities, records, exclusions ->
            val statusById = records.associate { it.activityId to SyncStatus.fromCode(it.status) }
            val syncedFingerprints = records.syncedFingerprints()
            val excluded = exclusions.map { it.activityId }.toSet()
            activities.map { activity ->
                PickerItem(
                    activityId = activity.activityId,
                    name = activity.name,
                    startTime = activity.startTime,
                    distanceMeters = activity.distanceMeters,
                    durationSec = activity.durationSec,
                    status = statusOf(activity, statusById, syncedFingerprints),
                    excluded = activity.activityId in excluded,
                )
            }
        }
        // 同上：指纹计算不要占着主线程（列表刷新时会重算一遍）。
        .flowOn(Dispatchers.Default)

    // ---- cache ------------------------------------------------------------

    fun refreshCache() {
        viewModelScope.launch { _cache.value = cacheManager.snapshot() }
    }

    fun clearCache(key: String, force: Boolean = false) {
        viewModelScope.launch {
            val ok = cacheManager.clear(key, force)
            _message.value = if (ok) "已清理" else "该分类需要确认后才能清理"
            _cache.value = cacheManager.snapshot()
        }
    }

    fun clearAllSafeCache() {
        viewModelScope.launch {
            cacheManager.clearAllSafe()
            _cache.value = cacheManager.snapshot()
            _message.value = "已清理全部可安全删除的缓存"
        }
    }

    fun applyRetentionNow() {
        viewModelScope.launch {
            cacheManager.applyRetention()
            _cache.value = cacheManager.snapshot()
            _message.value = "已按保留策略清理"
        }
    }

    // ---- settings ---------------------------------------------------------

    fun updateDefaults(source: Platform, target: Platform, listLimit: Int) {
        viewModelScope.launch { settingsStore.setDefaults(source, target, listLimit) }
    }

    fun updateCachePolicy(policy: CachePolicy) {
        viewModelScope.launch { settingsStore.setCachePolicy(policy) }
    }

    fun updateBackgroundSync(enabled: Boolean, intervalHours: Int) {
        viewModelScope.launch {
            settingsStore.setBackgroundSync(enabled, intervalHours)
            container.applyBackgroundSchedule()
        }
    }

    fun setWarnCoros(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setWarnCorosSingleSession(enabled) }
    }

    fun submitMfa(code: String?) = container.mfaCoordinator.submit(code)

    // ---- Strava OAuth -----------------------------------------------------

    /** Authorization page to load in the WebView, or null when unconfigured. */
    suspend fun stravaAuthorizeUrl(): String? {
        val (clientId, _) = accountRepository.plainCredentials(Platform.STRAVA)
        return clientId?.takeIf { it.isNotBlank() }?.let(StravaOAuth::authorizeUrl)
    }

    /** Exchanges the intercepted authorization code for tokens. */
    fun completeStravaAuth(code: String) {
        viewModelScope.launch {
            val (clientId, clientSecret) = accountRepository.plainCredentials(Platform.STRAVA)
            if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
                _message.value = "请先保存 Strava Client ID 与 Secret"
                return@launch
            }
            try {
                val tokens = StravaOAuth.exchangeCode(clientId, clientSecret, code)
                accountRepository.save(
                    platform = Platform.STRAVA,
                    email = null,
                    password = null,
                    region = null,
                    token = kotlinx.serialization.json.Json.encodeToString(
                        com.sportfit.sync.platform.strava.StravaTokens.serializer(),
                        tokens,
                    ),
                )
                accountRepository.updateStatus(Platform.STRAVA, AccountEntity.STATUS_CONNECTED)
                _message.value = "Strava 授权成功"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = error.toUserMessage()
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private const val MAX_UI_LOG_LINES = 400

        /**
         * 选择活动页一次渲染多少条。
         *
         * 本地可能存着上千条历史活动，列表只渲染最近的一批；「待同步 / 已同步」
         * 的统计用的是完整计数，不是这一页的长度。
         */
        private const val PICKER_LIMIT = 1000

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(container) as T
            }

        fun formatBytes(bytes: Long): String = CacheManager.formatBytes(bytes)
    }
}
