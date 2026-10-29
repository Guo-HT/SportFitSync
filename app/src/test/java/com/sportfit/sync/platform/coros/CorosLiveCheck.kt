package com.sportfit.sync.platform.coros

import com.sportfit.sync.core.format.FitPackaging
import com.sportfit.sync.core.format.FitPayload
import com.sportfit.sync.core.net.Hashing
import com.sportfit.sync.core.oss.CorosStsCredentials
import com.sportfit.sync.core.oss.CorosStsProvider
import com.sportfit.sync.core.oss.OssUploaders
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Live health check against a real Coros account.
 *
 * The Coros upload channel is a moving target: the anonymous STS endpoint was
 * retired on 2026-10-03 and the training-centre proxy that replaced it had
 * never been exercised against a live account in this project. This check is
 * the way to re-verify the chain whenever Coros changes it again.
 *
 * **It is skipped unless credentials are supplied**, so the normal unit-test
 * run stays hermetic:
 *
 * ```bash
 * cd /Users/mac/Code/android/sport-fit-sync-app
 * export COROS_EMAIL='you@example.com' COROS_PASSWORD='…'
 * ./build.sh :app:testDebugUnitTest --tests "*CorosLiveCheck" --rerun --console=plain -i
 * ```
 *
 * Stages are selected with `COROS_LIVE_STAGES` (comma separated):
 *
 * | stage | what it does | side effect |
 * |---|---|---|
 * | `a` | log in (`account/login`) | **yes** — Coros is single-device, the phone app gets signed out |
 * | `b` | mint OSS credentials through the training-centre channel | none |
 * | `c` | `PUT` a ZIP object into the bucket | one orphan object in the bucket |
 * | `d` | `activity/fit/import` via the app's own `CorosTarget` | **yes** — creates an activity in the account |
 * | `e` | list the newest activities | none |
 *
 * `d` is the only one that changes the account, and Coros does **not**
 * deduplicate by content: importing an activity that already exists creates a
 * second copy. Keep `d` out of routine runs.
 */
class CorosLiveCheck {

    private fun log(stage: String, message: String) = println("LIVE|$stage|$message")

    @Test
    fun liveCheck() = runBlocking {
        val email = System.getenv("COROS_EMAIL")
        val password = System.getenv("COROS_PASSWORD")
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            log("skip", "未设置 COROS_EMAIL / COROS_PASSWORD，跳过实时检查")
            return@runBlocking
        }

        val stages = (System.getenv("COROS_LIVE_STAGES") ?: "a,b,c")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        // ---- a: login -------------------------------------------------------
        val client = CorosClient(email, password) { message -> log("a", message) }
        try {
            client.login()
        } catch (error: Exception) {
            log("a", "登录失败：${error.message}")
            return@runBlocking
        }
        log("a", "登录成功 userId=${client.userId} regionId=${client.regionId}")

        // ---- b: OSS credentials ---------------------------------------------
        var credentials: CorosStsCredentials? = null
        try {
            credentials = CorosStsProvider.fetch(client.regionId, client.accessToken)
            log("b", "STS 成功 bucket=${credentials.bucket} service=${credentials.service}")
            log(
                "b",
                "accessKeyId=${credentials.accessKeyId.take(8)}…（${credentials.accessKeyId.length} 字符）" +
                    " secret=${credentials.accessKeySecret.length} 字符" +
                    " token=${credentials.securityToken.length} 字符",
            )
        } catch (error: Exception) {
            log("b", "STS 失败：${error.message}")
            return@runBlocking
        }

        // ---- payload: a real activity, or a synthetic FIT --------------------
        val userId = client.userId ?: return@runBlocking
        var fitBytes: ByteArray? = null
        var origin = "synthetic"
        try {
            val activity = client.listActivities(limit = 5).firstOrNull()
            if (activity != null) {
                val payload = client.download(activity.id, activity.sportType)
                fitBytes = if (payload.zipped) FitPackaging.extractFit(payload.bytes) ?: payload.bytes
                else payload.bytes
                origin = "coros-activity:${activity.id}（${activity.name}）"
            }
        } catch (error: Exception) {
            log("prep", "取样真实 FIT 失败，改用合成载荷：${error.message}")
        }
        fitBytes = fitBytes ?: syntheticFit()
        log("prep", "载荷来源=$origin 大小=${fitBytes!!.size} 字节")

        val zip = FitPackaging.zip(fitBytes!!, "activity.fit")
        val md5 = Hashing.md5Hex(zip)
        val objectKey = "fit_zip/$userId/$md5.zip"

        // ---- c: bucket upload ------------------------------------------------
        try {
            OssUploaders.forCredentials(credentials!!).upload(objectKey, zip)
            log("c", "OSS 上传成功 objectKey=$objectKey 大小=${zip.size} 字节")
        } catch (error: Exception) {
            log("c", "OSS 上传失败：${error.message}")
            return@runBlocking
        }

        // ---- d: the app's own upload path (STS → PUT → import) --------------
        if ("d" in stages) {
            try {
                val payload = FitPayload(
                    bytes = fitBytes!!,
                    fileName = "live-probe.fit",
                    zipped = false,
                )
                log("d", "CorosTarget.upload 结果=${CorosTarget(client).upload(payload)}")
            } catch (error: Exception) {
                log("d", "CorosTarget.upload 抛异常：${error.message}")
            }
        } else {
            log("d", "已跳过（会在账号里创建一条活动）")
        }

        // ---- e: what the account looks like ----------------------------------
        if ("e" in stages) {
            try {
                log("e", "账号最新活动：")
                client.listActivities(limit = 8).forEach { activity ->
                    log(
                        "e",
                        "  id=${activity.id} 名称=${activity.name} 时间=${activity.startTime} " +
                            "距离=${activity.distanceMeters} 类型=${activity.sportType}",
                    )
                }
            } catch (error: Exception) {
                log("e", "读取活动列表失败：${error.message}")
            }
        }
    }

    /** A minimal, well-formed FIT header (14 bytes) — enough to exercise the transport. */
    private fun syntheticFit(): ByteArray {
        val header = ByteArray(14)
        header[0] = 14 // header size
        header[1] = 0x20 // protocol version
        header[2] = 0x00 // profile version (2100)
        header[3] = 0x08
        header[8] = '.'.code.toByte()
        header[9] = 'F'.code.toByte()
        header[10] = 'I'.code.toByte()
        header[11] = 'T'.code.toByte()
        val crc = crc16(header, 0, 12)
        header[12] = (crc and 0xFF).toByte()
        header[13] = ((crc shr 8) and 0xFF).toByte()
        return header
    }

    private fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc
    }
}
