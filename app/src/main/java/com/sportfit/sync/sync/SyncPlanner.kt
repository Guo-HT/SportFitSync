package com.sportfit.sync.sync

import com.sportfit.sync.domain.ActivityFingerprint
import com.sportfit.sync.domain.ActivityMeta
import com.sportfit.sync.domain.SyncStatus

/**
 * 一次同步任务里，所有活动按比对结果的分类。
 *
 * 用户点进「同步任务」、看到运动记录列表的那一刻，就要能把结果说清楚：
 * 按 id 判重多少条、按内容判重多少条、目标端已有多少条、被排除多少条、
 * 真正要同步多少条。这个数据类就是那份明细。
 *
 * @property candidates 本次真正要同步（下载+上传）的活动，保持传入的顺序。
 * @property alreadySynced 按 id 命中账本、状态为已同步 —— 跳过。
 * @property duplicatesByContent 内容指纹命中本地账本（同方向已同步过的同样数据）—— 跳过。
 * @property duplicatesOnTarget 宽松指纹命中「目标端已有活动」—— 跳过。
 * @property excluded 被用户排除 —— 跳过。
 */
data class SyncPlan(
    /** 本次真正要同步（下载+上传）的活动，保持传入的顺序。 */
    val candidates: List<ActivityMeta>,
    /** 按 id 命中账本、状态为已同步 —— 跳过。 */
    val alreadySynced: List<ActivityMeta>,
    /** 内容指纹命中本地账本（同方向已同步过的同样数据）—— 跳过。 */
    val duplicatesByContent: List<ActivityMeta>,
    /** 宽松指纹命中「目标端已有活动」—— 跳过。 */
    val duplicatesOnTarget: List<ActivityMeta>,
    /** 被用户排除 —— 跳过。 */
    val excluded: List<ActivityMeta>,
)

/**
 * 决定一次同步任务要处理哪些活动。
 *
 * 从 [SyncEngine] 里抽出来的纯函数，因为这里正是上游 Python 项目 bug 最狠的地方：
 * 它的查询是 `WHERE is_sync_x = 0`，于是任何失败的活动（status 2）再也不会被选中，
 * 唯一的「修法」是手动改 SQLite。
 *
 * 规则：
 *  - 显式勾选优先，但排除名单照样生效；
 *  - 其余凡是「还没同步过」的活动都可以同步，其中也包括之前失败的（自动重试）；
 *  - 已经同步过的活动，只有调用方明确要求「重新同步」时才纳入。
 *
 * ## 为什么要内容指纹
 *
 * 账本（ledger）的主键是**源平台自己的 activity id**，但这个 id 不是「这条活动」的
 * 稳定名字 —— 同一趟骑行可能换个全新的 id 再出现一次：
 *
 *  - 把同一个 FIT 重新导入源平台，会多出一条**新活动**（新 id），内容一模一样；
 *  - 换成同平台的另一个账号，id 来自另一个命名空间 —— `activityId` 变了，session 还是那个；
 *  - 有些平台自己重发 id。
 *
 * 这些情况下 id 说「新活动」，数据说「已经同步过了」，再传一遍就是在目标端造副本。
 * [ActivityFingerprint] 对活动**内容**（开始时间 + 粗粒度元数据）取哈希，
 * 于是同样的数据不管 id 是什么都被认成同一份。调用方把「这个方向上已经同步过的」
 * 指纹作为 [plan] 的 `syncedFingerprints` 传进来，把「目标端**已有**的活动」的指纹
 * 作为 `targetLooseFingerprints` 传进来。
 *
 * 取舍（写在这里以免以后被当成 bug 改回去）：
 *
 *  - **时间是指纹的必需项。** 没有开始时间的活动没有可用指纹
 *    （[ActivityFingerprint.NONE]），退回按 id 判断；
 *  - **`includeAlreadySynced` 优先于指纹规则。** 这个开关表示「用户明确要求再同步一次」，
 *    不能被悄悄收窄；不开这个开关时，指纹已同步过的活动按「已同步」处理，不进候选；
 *  - **显式勾选**不**优先于指纹规则。** 用户点名的活动如果内容和这个方向上已同步过的
 *    那条一模一样，再传一遍只会在目标端多出一份副本，所以即使被点名也照样跳过。
 *    这是个有意的取舍：账本的承诺是「目标端一条活动」，而另一个选择——悄悄造副本——更糟。
 *    真想要第二份的用户可以用 `includeAlreadySynced`，它会完全绕开这条规则。
 *
 * 这个对象始终是纯函数：输入全靠参数，不碰数据库、不碰 Android API、不读时钟。
 */
object SyncPlanner {

    /**
     * 规划一次同步任务：把所有活动按比对结果分好类。
     *
     * 判定顺序（先按 id 去重，沿用 `distinctBy { it.id }`）：
     *
     * 1. `excludedIds` 里的活动 → [SyncPlan.excluded]，优先级最高，连指纹都不算。
     * 2. [includeAlreadySynced] 为真（用户明确要求「重新同步已同步项」）→
     *    第 3、4 条指纹规则**都不生效**，走原有语义。
     * 3. 严格内容指纹命中 [syncedFingerprints] → [SyncPlan.duplicatesByContent]。
     * 4. 宽松指纹命中 [targetLooseFingerprints] → [SyncPlan.duplicatesOnTarget]。
     * 5. `selectedIds` 非空 → 只有被点名的才是 [SyncPlan.candidates]，
     *    其余既不是候选也不算重复（静默忽略）。
     * 6. 其余按状态：`SYNCED` → [SyncPlan.alreadySynced]；
     *    `UNSYNCED` / `FAILED` → 候选（失败项照常重试）。
     *
     * 取舍（和 [selectCandidates] 一致，不要当成 bug 改回去）：
     * **显式勾选也不能绕过指纹规则** —— 用户点名的活动如果内容完全重复，
     * 再传一遍只会制造副本，所以照样跳过；真要重复同步请用
     * [includeAlreadySynced]，那个开关会绕过指纹规则。
     *
     * @param syncedFingerprints 这个方向上已经同步过的「同样的数据」的严格指纹。
     * @param targetLooseFingerprints 目标端**已有活动**的宽松指纹；命中即视为
     *   「目标端已经有了」，不必再传一遍。
     */
    fun plan(
        activities: List<ActivityMeta>,
        statusById: Map<String, SyncStatus>,
        excludedIds: Set<String>,
        selectedIds: Set<String>? = null,
        includeAlreadySynced: Boolean = false,
        syncedFingerprints: Set<String> = emptySet(),
        targetLooseFingerprints: Set<String> = emptySet(),
    ): SyncPlan {
        val candidates = mutableListOf<ActivityMeta>()
        val alreadySynced = mutableListOf<ActivityMeta>()
        val duplicatesByContent = mutableListOf<ActivityMeta>()
        val duplicatesOnTarget = mutableListOf<ActivityMeta>()
        val excluded = mutableListOf<ActivityMeta>()

        // 分页重复的 id 先折叠：每个 id 只判一次。
        for (activity in activities.distinctBy { it.id }) {
            // 1. 排除名单最优先：被排除的活动不参与任何比对。
            if (activity.id in excludedIds) {
                excluded += activity
                continue
            }

            // 2~4. 指纹规则。
            // includeAlreadySynced 表示用户明确要求「重新同步已同步项」，必须尊重，
            // 所以这两条规则在它为 true 时不生效。
            val sameContent = !includeAlreadySynced &&
                isAlreadySyncedByFingerprint(activity, syncedFingerprints)
            val onTarget = !includeAlreadySynced &&
                isAlreadyOnTargetByFingerprint(activity, targetLooseFingerprints)

            // 5. 显式勾选：用户点名的才是候选，没点名的既不是候选也不算重复。
            if (selectedIds != null) {
                when {
                    sameContent -> duplicatesByContent += activity
                    onTarget -> duplicatesOnTarget += activity
                    activity.id in selectedIds -> candidates += activity
                }
                continue
            }

            // 6. 没点名时，按「为什么被跳过」分类，界面上才能报出有意义的数字：
            //    - alreadySynced：这个 id 我们同步过（最常见的情况）；
            //    - duplicatesByContent：id 是新的，但数据和已同步的完全一样
            //      （重新导入 FIT、换同平台的另一个账号……）—— 这才是真正的重复。
            //    先看 id 再看指纹，顺序反过来的话「内容相同」会吞掉所有
            //    已经同步过的活动，报出来的数字就没意义了。
            val status = statusById[activity.id] ?: SyncStatus.UNSYNCED
            when {
                // 用户明确要求「重新同步已同步项」：已同步的也照常处理。
                status == SyncStatus.SYNCED && includeAlreadySynced -> candidates += activity
                status == SyncStatus.SYNCED -> alreadySynced += activity
                sameContent -> duplicatesByContent += activity
                onTarget -> duplicatesOnTarget += activity
                // 失败项（FAILED）和未同步项同等对待，照常重试。
                else -> candidates += activity
            }
        }

        return SyncPlan(
            candidates = candidates,
            alreadySynced = alreadySynced,
            duplicatesByContent = duplicatesByContent,
            duplicatesOnTarget = duplicatesOnTarget,
            excluded = excluded,
        )
    }

    /**
     * 只返回「本次要同步的活动」，是 [plan] 的薄封装，参数与默认值保持不变。
     *
     * @param syncedFingerprints 这个方向上已经同步过的活动的指纹。指纹在这个集合里
     *   的活动按「已同步」处理，不会再被选中（除非 [includeAlreadySynced] 为 `true`）。
     */
    fun selectCandidates(
        activities: List<ActivityMeta>,
        statusById: Map<String, SyncStatus>,
        excludedIds: Set<String>,
        selectedIds: Set<String>? = null,
        includeAlreadySynced: Boolean = false,
        syncedFingerprints: Set<String> = emptySet(),
    ): List<ActivityMeta> = plan(
        activities = activities,
        statusById = statusById,
        excludedIds = excludedIds,
        selectedIds = selectedIds,
        includeAlreadySynced = includeAlreadySynced,
        syncedFingerprints = syncedFingerprints,
    ).candidates

    /** 空指纹（没有开始时间）不参与指纹判定，回退到按 id 判断。 */
    private fun isAlreadySyncedByFingerprint(
        activity: ActivityMeta,
        syncedFingerprints: Set<String>,
    ): Boolean {
        if (syncedFingerprints.isEmpty()) return false

        val fingerprint = ActivityFingerprint.of(activity)
        if (fingerprint == ActivityFingerprint.NONE) return false

        return fingerprint in syncedFingerprints
    }

    /**
     * 宽松指纹命中「目标端已有活动」时判为重复。
     *
     * 空指纹（没有开始时间）同样不参与判定，回退到按 id 判断。
     */
    private fun isAlreadyOnTargetByFingerprint(
        activity: ActivityMeta,
        targetLooseFingerprints: Set<String>,
    ): Boolean {
        if (targetLooseFingerprints.isEmpty()) return false

        // 没有开始时间就没有任何可用的指纹，回退到按 id 判断。
        if (activity.startTime == null) return false

        val fingerprint = ActivityFingerprint.looseOf(activity)
        if (fingerprint == ActivityFingerprint.NONE) return false

        return fingerprint in targetLooseFingerprints
    }

    /**
     * 限制一次运行处理的活动条数。
     *
     * 活动列表是按时间倒序的，所以这个上限留下的是最新的那批 —— 和旧版
     * 「只拉最新 N 条」的行为一致。
     *
     * 显式勾选永远不被收窄：用户点名了哪几条，这次就跑哪几条，
     * 即使这一批比平常的上限还多。
     */
    fun applyLimit(
        candidates: List<ActivityMeta>,
        limit: Int,
        explicitSelection: Boolean,
    ): List<ActivityMeta> = when {
        explicitSelection -> candidates
        limit <= 0 -> candidates
        else -> candidates.take(limit)
    }
}
