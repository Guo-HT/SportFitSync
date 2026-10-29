package com.sportfit.sync.ui.components

import com.sportfit.sync.data.local.SyncJobEntity

/**
 * 同步任务状态 → 展示文案。
 *
 * 首页的「最近一次同步」和记录页、详情页都要显示状态，
 * 三处各写一遍 `when` 迟早会改漏，所以收敛到这一份。
 */
fun jobStatusLabel(status: String): String = when (status) {
    SyncJobEntity.STATUS_SUCCESS -> "成功"
    SyncJobEntity.STATUS_PARTIAL -> "部分成功"
    SyncJobEntity.STATUS_RUNNING -> "进行中"
    SyncJobEntity.STATUS_CANCELLED -> "已取消"
    else -> "失败"
}

/**
 * 同步任务状态 → 色调。
 *
 * 「已取消」算中性色而不是错误色：用户自己按的取消，不是失败。
 */
fun jobStatusTone(status: String): StatusTone = when (status) {
    SyncJobEntity.STATUS_SUCCESS -> StatusTone.OK
    SyncJobEntity.STATUS_PARTIAL -> StatusTone.WARN
    SyncJobEntity.STATUS_RUNNING -> StatusTone.NEUTRAL
    SyncJobEntity.STATUS_CANCELLED -> StatusTone.NEUTRAL
    else -> StatusTone.ERROR
}
