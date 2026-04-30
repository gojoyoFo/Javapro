package com.javapro.spoof

import java.io.File

object SpoofExecutor {

    private const val MODULE_DIR  = "/data/adb/modules/javapro_spoof"
    private const val BACKUP_FILE = "/data/adb/modules/javapro_spoof_backup.prop"

    fun isRooted(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out  = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) { false }
    }

    fun isSpoofActive(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "[ -d $MODULE_DIR ] && echo yes || echo no"))
            val out  = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out == "yes"
        } catch (e: Exception) { false }
    }

    fun getActiveSpoofName(): String? {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "grep '^name=' $MODULE_DIR/module.prop 2>/dev/null | cut -d= -f2-")
            )
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.startsWith("JavaPro Spoof: ")) out.removePrefix("JavaPro Spoof: ") else null
        } catch (e: Exception) { null }
    }

    private enum class Platform { QUALCOMM, MEDIATEK, EXYNOS, UNISOC, KIRIN, UNKNOWN }

    private fun detectPlatform(): Platform {
        return try {
            val read = { key: String ->
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "getprop $key"))
                val v = p.inputStream.bufferedReader().readText().trim().lowercase()
                p.waitFor(); v
            }
            val board    = read("ro.board.platform")
            val hardware = read("ro.hardware")
            val socMfr   = read("ro.soc.manufacturer")
            val chip     = read("ro.hardware.chipname")
            val mtk      = read("ro.mediatek.platform")
            when {
                mtk.isNotEmpty()                                      -> Platform.MEDIATEK
                board.startsWith("mt") || hardware.startsWith("mt")  -> Platform.MEDIATEK
                socMfr.contains("qualcomm") || socMfr.contains("qti")
                        || board in listOf("taro","kalama","sun","lahaina","shima","yupik","waipio","parrot","neo")
                        || chip.startsWith("sm")                      -> Platform.QUALCOMM
                board.contains("exynos") || socMfr.contains("samsung")
                        || hardware.contains("exynos")                -> Platform.EXYNOS
                board.startsWith("ums") || socMfr.contains("unisoc") -> Platform.UNISOC
                board.startsWith("hi") || socMfr.contains("hisilicon")
                        || socMfr.contains("kirin")                   -> Platform.KIRIN
                else                                                   -> Platform.UNKNOWN
            }
        } catch (e: Exception) { Platform.UNKNOWN }
    }

    private fun allSpoofKeys(platform: Platform): List<String> {
        val common = listOf(
            "ro.product.brand","ro.product.manufacturer","ro.product.model",
            "ro.product.name","ro.product.device",
            "ro.product.system.brand","ro.product.system.manufacturer","ro.product.system.model",
            "ro.product.vendor.brand","ro.product.vendor.manufacturer","ro.product.vendor.model",
            "ro.product.odm.brand","ro.product.odm.manufacturer","ro.product.odm.model",
            "ro.product.odm.name","ro.product.odm.device",
            "ro.product.product.brand","ro.product.product.manufacturer","ro.product.product.model",
            "ro.product.product.name","ro.product.product.device",
            "ro.product.system_ext.brand","ro.product.system_ext.manufacturer","ro.product.system_ext.model","ro.product.system_ext.name",
            "ro.product.brand_for_attestation","ro.product.manufacturer_for_attestation",
            "ro.product.model_for_attestation","ro.product.device_for_attestation","ro.product.name_for_attestation",
            "ro.system.manufacturer",
            "ro.soc.model","ro.soc.model.external_name","ro.soc.manufacturer",
            "ro.hardware.chipname","ro.hardware","ro.board.platform","ro.chipname","ro.chip.model",
            "ro.vendor.soc.model","ro.vendor.soc.manufacturer","ro.vendor.soc.model.external_name",
            "ro.build.fingerprint","ro.product.build.fingerprint","ro.system.build.fingerprint",
            "ro.vendor.build.fingerprint","ro.bootimage.build.fingerprint",
            "ro.odm.build.fingerprint","ro.system_ext.build.fingerprint"
        )
        val extra = when (platform) {
            Platform.MEDIATEK -> listOf(
                "ro.mediatek.platform","ro.mediatek.version.release",
                "ro.vendor.mtk.platform","ro.vendor.mtk.soc_model","ro.vendor.mtk.soc_name",
                "ro.mtk.platform","ro.mtk.key.unlock_source","ro.vendor.mediatek.platform",
                "ro.vendor.qti.soc_model","ro.vendor.qti.soc_name","ro.vendor.qti.chip_name","ro.qti.soc_model"
            )
            Platform.QUALCOMM -> listOf(
                "ro.vendor.qti.soc_model","ro.vendor.qti.soc_name","ro.vendor.qti.chip_name","ro.qti.soc_model"
            )
            Platform.EXYNOS -> listOf(
                "ro.hardware.exynosChip","ro.samsung.chipname","persist.vendor.exynos.chipname",
                "ro.vendor.qti.soc_model","ro.vendor.qti.soc_name"
            )
            Platform.UNISOC -> listOf("ro.vendor.unisoc.chipname","ro.vendor.qti.soc_model")
            Platform.KIRIN  -> listOf("ro.vendor.qti.soc_model")
            else             -> listOf("ro.vendor.qti.soc_model","ro.vendor.qti.soc_name")
        }
        return common + extra
    }

    private fun backupOriginalProps(keys: List<String>): Boolean {
        return try {
            val lines = keys.mapNotNull { key ->
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "getprop $key"))
                val v = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                if (v.isNotEmpty()) "$key=$v" else null
            }
            writeFileViaSu(BACKUP_FILE, lines.joinToString("\n"))
            execSu("chmod 600 $BACKUP_FILE")
            true
        } catch (e: Exception) { false }
    }

    private fun restoreFromBackup(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $BACKUP_FILE 2>/dev/null"))
            val content = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (content.isEmpty()) return false
            val resetScript = content.lines()
                .filter { it.contains("=") && !it.startsWith("#") }
                .joinToString("\n") { line ->
                    val key   = line.substringBefore("=")
                    val value = line.substringAfter("=")
                    "resetprop $key ${value.replace("'", "\\'")}"
                }
            if (resetScript.isNotEmpty()) execSu(resetScript)
            true
        } catch (e: Exception) { false }
    }

    fun applySpoof(device: SpoofDevice): Boolean {
        return try {
            val platform = detectPlatform()
            val allKeys  = allSpoofKeys(platform)

            val backupExists = execSuRead("[ -f $BACKUP_FILE ] && echo yes || echo no") == "yes"
            if (!backupExists) backupOriginalProps(allKeys)

            val allProps       = buildUniversalProps(device, platform)
            val systemPropText = allProps.entries.joinToString("\n") { (k, v) -> "$k=$v" }
            val modulePropText = buildModuleProp(device.name)

            execSu("rm -rf $MODULE_DIR && mkdir -p $MODULE_DIR")
            writeFileViaSu("$MODULE_DIR/module.prop", modulePropText)
            writeFileViaSu("$MODULE_DIR/system.prop", systemPropText)
            execSu("chmod 644 $MODULE_DIR/module.prop $MODULE_DIR/system.prop")

            val resetScript = allProps.entries.joinToString("\n") { (k, v) ->
                "resetprop $k ${v.replace("'", "\\'")}"
            }
            execSu(resetScript)
            true
        } catch (e: Exception) { false }
    }

    fun removeSpoof(): Boolean {
        return try {
            execSu("rm -rf $MODULE_DIR")
            val restored = restoreFromBackup()
            execSu("rm -f $BACKUP_FILE")
            restored
        } catch (e: Exception) { false }
    }

    private fun buildUniversalProps(device: SpoofDevice, platform: Platform): Map<String, String> {
        val result            = LinkedHashMap<String, String>()
        result.putAll(device.props)

        val targetSocModel    = device.props["ro.soc.model"] ?: ""
        val targetSocName     = device.props["ro.soc.model.external_name"] ?: device.chipset
        val targetSocMfr      = device.props["ro.soc.manufacturer"] ?: "Qualcomm Technologies, Inc."
        val targetChipname    = device.props["ro.hardware.chipname"] ?: targetSocModel
        val targetBoard       = device.props["ro.board.platform"] ?: ""
        val targetFingerprint = device.fingerprint

        result["ro.soc.model"]                          = targetSocModel
        result["ro.soc.model.external_name"]            = targetSocName
        result["ro.soc.manufacturer"]                   = targetSocMfr
        result["ro.hardware.chipname"]                  = targetChipname
        result["ro.hardware"]                           = targetChipname.lowercase()
        result["ro.board.platform"]                     = targetBoard
        result["ro.chipname"]                           = targetSocModel
        result["ro.chip.model"]                         = targetSocModel

        when (platform) {
            Platform.MEDIATEK -> {
                result["ro.mediatek.platform"]          = targetBoard.uppercase()
                result["ro.mediatek.version.release"]   = targetSocName
                result["ro.vendor.mtk.platform"]        = targetBoard.uppercase()
                result["ro.vendor.mtk.soc_model"]       = targetSocModel
                result["ro.vendor.mtk.soc_name"]        = targetSocName
                result["ro.mtk.platform"]               = targetBoard.uppercase()
                result["ro.mtk.key.unlock_source"]      = "0"
                result["ro.vendor.mediatek.platform"]   = targetBoard.uppercase()
                result["ro.vendor.qti.soc_model"]       = targetSocModel
                result["ro.vendor.qti.soc_name"]        = "${targetSocModel}-AB"
                result["ro.vendor.qti.chip_name"]       = targetSocModel
                result["ro.qti.soc_model"]              = targetSocModel
            }
            Platform.QUALCOMM -> {
                result["ro.vendor.qti.soc_model"]       = targetSocModel
                result["ro.vendor.qti.soc_name"]        = "${targetSocModel}-AB"
                result["ro.vendor.qti.chip_name"]       = targetSocModel
                result["ro.qti.soc_model"]              = targetSocModel
                result["ro.vendor.soc.model"]           = targetSocModel
                result["ro.vendor.soc.manufacturer"]    = "QTI"
                result["ro.vendor.soc.model.external_name"] = targetSocName
            }
            Platform.EXYNOS -> {
                result["ro.hardware.exynosChip"]        = targetSocModel
                result["ro.samsung.chipname"]           = targetSocModel
                result["persist.vendor.exynos.chipname"]= targetSocModel
                result["ro.vendor.qti.soc_model"]       = targetSocModel
                result["ro.vendor.qti.soc_name"]        = "${targetSocModel}-AB"
            }
            Platform.UNISOC -> {
                result["ro.vendor.unisoc.chipname"]     = targetSocModel
                result["ro.vendor.qti.soc_model"]       = targetSocModel
            }
            Platform.KIRIN -> {
                result["ro.hardware.chipname"]          = targetSocModel
                result["ro.vendor.qti.soc_model"]       = targetSocModel
            }
            Platform.UNKNOWN -> {
                result["ro.vendor.qti.soc_model"]       = targetSocModel
                result["ro.vendor.qti.soc_name"]        = "${targetSocModel}-AB"
            }
        }

        result["ro.build.fingerprint"]                  = targetFingerprint
        result["ro.product.build.fingerprint"]          = targetFingerprint
        result["ro.system.build.fingerprint"]           = targetFingerprint
        result["ro.vendor.build.fingerprint"]           = targetFingerprint
        result["ro.bootimage.build.fingerprint"]        = targetFingerprint
        result["ro.odm.build.fingerprint"]              = targetFingerprint
        result["ro.system_ext.build.fingerprint"]       = targetFingerprint

        val brand = device.props["ro.product.brand"] ?: ""
        val model = device.props["ro.product.model"] ?: ""
        val mfr   = device.props["ro.product.manufacturer"] ?: ""
        val name  = device.props["ro.product.name"] ?: ""
        val dev   = device.props["ro.product.device"] ?: ""

        result["ro.product.odm.brand"]                  = brand
        result["ro.product.odm.manufacturer"]           = mfr
        result["ro.product.odm.model"]                  = model
        result["ro.product.odm.name"]                   = name
        result["ro.product.odm.device"]                 = dev
        result["ro.product.product.brand"]              = brand
        result["ro.product.product.manufacturer"]       = mfr
        result["ro.product.product.model"]              = model
        result["ro.product.product.name"]               = name
        result["ro.product.product.device"]             = dev
        result["ro.product.system_ext.brand"]           = brand
        result["ro.product.system_ext.manufacturer"]    = mfr
        result["ro.product.system_ext.model"]           = model
        result["ro.product.system_ext.name"]            = name

        return result
    }

    private fun buildModuleProp(deviceName: String): String =
        "id=javapro_spoof\nname=JavaPro Spoof: $deviceName\nversion=v1\nversionCode=1\nauthor=JavaPro\ndescription=Device spoof for $deviceName"

    private fun writeFileViaSu(path: String, content: String): Boolean {
        return try {
            val tmpFile = File.createTempFile("jp_spoof_", ".tmp")
            tmpFile.writeText(content)
            val ok = execSu("cp ${tmpFile.absolutePath} $path")
            tmpFile.delete()
            ok
        } catch (e: Exception) { false }
    }

    private fun execSuRead(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out  = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(); out
        } catch (e: Exception) { "" }
    }

    private fun execSu(script: String): Boolean {
        return try {
            val proc    = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val success = proc.waitFor() == 0
            proc.inputStream.close()
            proc.errorStream.close()
            success
        } catch (e: Exception) { false }
    }
}
