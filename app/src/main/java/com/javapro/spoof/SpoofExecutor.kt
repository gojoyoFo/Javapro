package com.javapro.spoof

import java.io.File

object SpoofExecutor {

    private const val MODULE_DIR = "/data/adb/modules/javapro_spoof"

    fun isRooted(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out  = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun applySpoof(device: SpoofDevice): Boolean {
        return try {
            val systemProp = buildSystemProp(device.props)
            val moduleProp = buildModuleProp(device.name)

            val script = """
                rm -rf $MODULE_DIR
                mkdir -p $MODULE_DIR
                cat > $MODULE_DIR/module.prop << 'MODPROP'
$moduleProp
MODPROP
                cat > $MODULE_DIR/system.prop << 'SYSPROP'
$systemProp
SYSPROP
                chmod 644 $MODULE_DIR/module.prop
                chmod 644 $MODULE_DIR/system.prop
            """.trimIndent()

            execSu(script)
        } catch (e: Exception) {
            false
        }
    }

    fun removeSpoof(): Boolean {
        return try {
            execSu("rm -rf $MODULE_DIR")
        } catch (e: Exception) {
            false
        }
    }

    fun isSpoofActive(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "[ -d $MODULE_DIR ] && echo yes || echo no"))
            val out  = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out == "yes"
        } catch (e: Exception) {
            false
        }
    }

    fun getActiveSpoofName(): String? {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "grep '^name=' $MODULE_DIR/module.prop 2>/dev/null | cut -d= -f2-")
            )
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.startsWith("JavaPro Spoof: ")) out.removePrefix("JavaPro Spoof: ") else null
        } catch (e: Exception) {
            null
        }
    }

    private fun buildSystemProp(props: Map<String, String>): String {
        return props.entries.joinToString("\n") { (k, v) -> "$k=$v" }
    }

    private fun buildModuleProp(deviceName: String): String {
        return """
            id=javapro_spoof
            name=JavaPro Spoof: $deviceName
            version=v1
            versionCode=1
            author=Java_nih_deks
            description=Device spoof for $deviceName
        """.trimIndent()
    }

    private fun execSu(script: String): Boolean {
        val proc    = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
        val success = proc.waitFor() == 0
        proc.inputStream.close()
        proc.errorStream.close()
        return success
    }
}
