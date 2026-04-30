package com.javapro.spoof

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.ui.graphics.vector.ImageVector

data class SpoofDevice(
    val name        : String,
    val chipset     : String,
    val model       : String,
    val fingerprint : String,
    val props       : Map<String, String>
)

data class SpoofBrand(
    val name    : String,
    val icon    : ImageVector,
    val devices : List<SpoofDevice>,
    val isSoon  : Boolean = false
)

fun allBrands(): List<SpoofBrand> = listOf(

    SpoofBrand(
        name    = "ASUS ROG",
        icon    = Icons.Filled.SportsEsports,
        devices = listOf(
            SpoofDevice(
                name        = "ASUS ROG Phone 7 Ultimate",
                chipset     = "Snapdragon 8 Gen 2",
                model       = "ASUS_AI2205_D",
                fingerprint = "asus/ASUS_AI2205_D/ASUS_AI2205_D:13/TKQ1.221013.001/AI2205_D.WW_Phone-33.2040.2040.294-0:user/release-keys",
                props       = mapOf(
                    "ro.product.name"                         to "ASUS_AI2205_D",
                    "ro.product.device"                       to "ASUS_AI2205_D",
                    "ro.product.brand"                        to "asus",
                    "ro.product.manufacturer"                 to "asus",
                    "ro.product.model"                        to "ASUS_AI2205_D",
                    "ro.product.system.brand"                 to "asus",
                    "ro.product.system.manufacturer"          to "asus",
                    "ro.product.vendor.brand"                 to "asus",
                    "ro.product.vendor.manufacturer"          to "asus",
                    "ro.product.brand_for_attestation"        to "asus",
                    "ro.product.model_for_attestation"        to "ASUS_AI2205_D",
                    "ro.product.manufacturer_for_attestation" to "asus",
                    "ro.soc.model"                            to "SM8550",
                    "ro.soc.model.external_name"              to "Snapdragon 8 Gen 2",
                    "ro.soc.manufacturer"                     to "Qualcomm Technologies, Inc.",
                    "ro.hardware.chipname"                    to "SM8550",
                    "ro.board.platform"                       to "taro",
                    "ro.vendor.qti.soc_model"                 to "SM8550",
                    "ro.vendor.qti.soc_name"                  to "SM8550-AB",
                    "ro.vendor.qti.chip_name"                 to "SM8550",
                    "ro.vendor.soc.model"                     to "SM8550",
                    "ro.vendor.soc.manufacturer"              to "QTI",
                    "ro.vendor.soc.model.external_name"       to "Snapdragon 8 Gen 2",
                    "ro.product.cpu.abi"                      to "arm64-v8a",
                    "ro.gpu.model"                            to "Adreno (TM) 740",
                    "ro.hardware.gpuname"                     to "Adreno (TM) 740",
                    "persist.sys.gaming.xmode"                to "true",
                    "persist.sys.gaming.xmode.level"          to "3",
                    "persist.sys.gaming.gputurbo"             to "on",
                    "ro.config.battery_capacity"              to "6000",
                    "persist.sys.cooling.fan_support"         to "true",
                    "ro.build.fingerprint"                    to "asus/ASUS_AI2205_D/ASUS_AI2205_D:13/TKQ1.221013.001/AI2205_D.WW_Phone-33.2040.2040.294-0:user/release-keys",
                    "ro.product.build.fingerprint"            to "asus/ASUS_AI2205_D/ASUS_AI2205_D:13/TKQ1.221013.001/AI2205_D.WW_Phone-33.2040.2040.294-0:user/release-keys",
                    "ro.system.build.fingerprint"             to "asus/ASUS_AI2205_D/ASUS_AI2205_D:13/TKQ1.221013.001/AI2205_D.WW_Phone-33.2040.2040.294-0:user/release-keys",
                    "ro.vendor.build.fingerprint"             to "asus/ASUS_AI2205_D/ASUS_AI2205_D:13/TKQ1.221013.001/AI2205_D.WW_Phone-33.2040.2040.294-0:user/release-keys",
                    "ro.bootimage.build.fingerprint"          to "asus/ASUS_AI2205_D/ASUS_AI2205_D:13/TKQ1.221013.001/AI2205_D.WW_Phone-33.2040.2040.294-0:user/release-keys",
                    "ro.odm.build.fingerprint"                to "asus/ASUS_AI2205_D/ASUS_AI2205_D:13/TKQ1.221013.001/AI2205_D.WW_Phone-33.2040.2040.294-0:user/release-keys"
                )
            ),
            SpoofDevice(
                name        = "ASUS ROG Phone 8 Pro",
                chipset     = "Snapdragon 8 Gen 3",
                model       = "ASUS_AI2401_B",
                fingerprint = "asus/ASUS_AI2401_B/ASUS_AI2401_B:14/UKQ1.231003.002/AI2401_B.WW_Phone-34.2410.2410.218-0:user/release-keys",
                props       = mapOf(
                    "ro.product.name"                         to "ASUS_AI2401_B",
                    "ro.product.device"                       to "ASUS_AI2401_B",
                    "ro.product.brand"                        to "asus",
                    "ro.product.manufacturer"                 to "asus",
                    "ro.product.model"                        to "ASUS_AI2401_B",
                    "ro.product.system.brand"                 to "asus",
                    "ro.product.system.manufacturer"          to "asus",
                    "ro.product.vendor.brand"                 to "asus",
                    "ro.product.vendor.manufacturer"          to "asus",
                    "ro.product.brand_for_attestation"        to "asus",
                    "ro.product.model_for_attestation"        to "ASUS_AI2401_B",
                    "ro.product.manufacturer_for_attestation" to "asus",
                    "ro.soc.model"                            to "SM8650",
                    "ro.soc.model.external_name"              to "Snapdragon 8 Gen 3",
                    "ro.soc.manufacturer"                     to "Qualcomm Technologies, Inc.",
                    "ro.hardware.chipname"                    to "SM8650",
                    "ro.board.platform"                       to "kalama",
                    "ro.vendor.qti.soc_model"                 to "SM8650",
                    "ro.vendor.qti.soc_name"                  to "SM8650-AB",
                    "ro.vendor.qti.chip_name"                 to "SM8650",
                    "ro.vendor.soc.model"                     to "SM8650",
                    "ro.vendor.soc.manufacturer"              to "QTI",
                    "ro.vendor.soc.model.external_name"       to "Snapdragon 8 Gen 3",
                    "ro.product.cpu.abi"                      to "arm64-v8a",
                    "ro.gpu.model"                            to "Adreno (TM) 750",
                    "ro.hardware.gpuname"                     to "Adreno (TM) 750",
                    "persist.sys.gaming.xmode"                to "true",
                    "persist.sys.gaming.xmode.level"          to "2",
                    "persist.sys.gaming.gputurbo"             to "on",
                    "ro.config.battery_capacity"              to "5800",
                    "ro.display.panel.manufacturer"           to "Samsung E6 AMOLED",
                    "persist.sys.cooling.fan_support"         to "true",
                    "ro.build.fingerprint"                    to "asus/ASUS_AI2401_B/ASUS_AI2401_B:14/UKQ1.231003.002/AI2401_B.WW_Phone-34.2410.2410.218-0:user/release-keys",
                    "ro.product.build.fingerprint"            to "asus/ASUS_AI2401_B/ASUS_AI2401_B:14/UKQ1.231003.002/AI2401_B.WW_Phone-34.2410.2410.218-0:user/release-keys",
                    "ro.system.build.fingerprint"             to "asus/ASUS_AI2401_B/ASUS_AI2401_B:14/UKQ1.231003.002/AI2401_B.WW_Phone-34.2410.2410.218-0:user/release-keys",
                    "ro.vendor.build.fingerprint"             to "asus/ASUS_AI2401_B/ASUS_AI2401_B:14/UKQ1.231003.002/AI2401_B.WW_Phone-34.2410.2410.218-0:user/release-keys",
                    "ro.bootimage.build.fingerprint"          to "asus/ASUS_AI2401_B/ASUS_AI2401_B:14/UKQ1.231003.002/AI2401_B.WW_Phone-34.2410.2410.218-0:user/release-keys",
                    "ro.odm.build.fingerprint"                to "asus/ASUS_AI2401_B/ASUS_AI2401_B:14/UKQ1.231003.002/AI2401_B.WW_Phone-34.2410.2410.218-0:user/release-keys"
                )
            ),
            SpoofDevice(
                name        = "ASUS ROG Phone 9",
                chipset     = "Snapdragon 8 Elite",
                model       = "ASUS_AI2501",
                fingerprint = "asus/ASUS_AI2501/ASUS_AI2501:14/UKQ1.231003.002/AI2501.WW_Phone-35.2410.2410.218-0:user/release-keys",
                props       = mapOf(
                    "ro.product.name"                         to "ASUS_AI2501",
                    "ro.product.device"                       to "ASUS_AI2501",
                    "ro.product.brand"                        to "asus",
                    "ro.product.manufacturer"                 to "asus",
                    "ro.product.model"                        to "ASUS_AI2501",
                    "ro.product.system.brand"                 to "asus",
                    "ro.product.system.manufacturer"          to "asus",
                    "ro.product.vendor.brand"                 to "asus",
                    "ro.product.vendor.manufacturer"          to "asus",
                    "ro.product.brand_for_attestation"        to "asus",
                    "ro.product.model_for_attestation"        to "ASUS_AI2501",
                    "ro.product.manufacturer_for_attestation" to "asus",
                    "ro.soc.model"                            to "SM8750",
                    "ro.soc.model.external_name"              to "Snapdragon 8 Elite",
                    "ro.soc.manufacturer"                     to "Qualcomm Technologies, Inc.",
                    "ro.hardware.chipname"                    to "SM8750",
                    "ro.board.platform"                       to "sun",
                    "ro.vendor.qti.soc_model"                 to "SM8750",
                    "ro.vendor.qti.soc_name"                  to "SM8750-AB",
                    "ro.vendor.qti.chip_name"                 to "SM8750",
                    "ro.vendor.soc.model"                     to "SM8750",
                    "ro.vendor.soc.manufacturer"              to "QTI",
                    "ro.vendor.soc.model.external_name"       to "Snapdragon 8 Elite",
                    "ro.product.cpu.abi"                      to "arm64-v8a",
                    "ro.gpu.model"                            to "Adreno (TM) 830",
                    "ro.hardware.gpuname"                     to "Adreno (TM) 830",
                    "persist.sys.gaming.xmode"                to "true",
                    "persist.sys.gaming.xmode.level"          to "3",
                    "persist.sys.gaming.gputurbo"             to "on",
                    "persist.sys.gaming.touch.acceleration"   to "1",
                    "persist.sys.gaming.lag_reduction"        to "enabled",
                    "persist.sys.gaming.performance.boost"    to "ultra",
                    "ro.config.battery_capacity"              to "6000",
                    "ro.display.panel.manufacturer"           to "Samsung E6 AMOLED",
                    "persist.sys.cooling.fan_support"         to "true",
                    "ro.build.fingerprint"                    to "asus/ASUS_AI2501/ASUS_AI2501:14/UKQ1.231003.002/AI2501.WW_Phone-35.2410.2410.218-0:user/release-keys",
                    "ro.product.build.fingerprint"            to "asus/ASUS_AI2501/ASUS_AI2501:14/UKQ1.231003.002/AI2501.WW_Phone-35.2410.2410.218-0:user/release-keys",
                    "ro.system.build.fingerprint"             to "asus/ASUS_AI2501/ASUS_AI2501:14/UKQ1.231003.002/AI2501.WW_Phone-35.2410.2410.218-0:user/release-keys",
                    "ro.vendor.build.fingerprint"             to "asus/ASUS_AI2501/ASUS_AI2501:14/UKQ1.231003.002/AI2501.WW_Phone-35.2410.2410.218-0:user/release-keys",
                    "ro.bootimage.build.fingerprint"          to "asus/ASUS_AI2501/ASUS_AI2501:14/UKQ1.231003.002/AI2501.WW_Phone-35.2410.2410.218-0:user/release-keys",
                    "ro.odm.build.fingerprint"                to "asus/ASUS_AI2501/ASUS_AI2501:14/UKQ1.231003.002/AI2501.WW_Phone-35.2410.2410.218-0:user/release-keys"
                )
            )
        )
    ),

    SpoofBrand(
        name    = "OnePlus",
        icon    = Icons.Filled.PhoneAndroid,
        devices = listOf(
            SpoofDevice(
                name        = "OnePlus 15",
                chipset     = "Snapdragon 8 Elite",
                model       = "OP611FL1",
                fingerprint = "OnePlus/OP611FL1/OP611FL1:15/AQ3A.240812.002/R.1726726127OP611FL1:user/release-keys",
                props       = mapOf(
                    "ro.product.brand"                        to "OnePlus",
                    "ro.product.manufacturer"                 to "OnePlus",
                    "ro.product.model"                        to "OP611FL1",
                    "ro.product.name"                         to "OP611FL1",
                    "ro.product.device"                       to "OP611FL1",
                    "ro.product.system.brand"                 to "OnePlus",
                    "ro.product.system.manufacturer"          to "OnePlus",
                    "ro.product.product.manufacturer"         to "OnePlus",
                    "ro.product.vendor.brand"                 to "OnePlus",
                    "ro.product.vendor.manufacturer"          to "OnePlus",
                    "ro.system.manufacturer"                  to "OnePlus",
                    "ro.product.brand_for_attestation"        to "oneplus",
                    "ro.product.device_for_attestation"       to "OP611FL1",
                    "ro.product.manufacturer_for_attestation" to "OnePlus",
                    "ro.product.model_for_attestation"        to "OP611FL1",
                    "ro.soc.model"                            to "SM8750",
                    "ro.soc.model.external_name"              to "Snapdragon 8 Elite",
                    "ro.soc.manufacturer"                     to "Qualcomm Technologies, Inc.",
                    "ro.hardware.chipname"                    to "SM8750",
                    "ro.board.platform"                       to "sun",
                    "ro.vendor.qti.soc_model"                 to "SM8750",
                    "ro.vendor.qti.soc_name"                  to "SM8750-AB",
                    "ro.vendor.qti.chip_name"                 to "SM8750",
                    "ro.vendor.soc.model"                     to "SM8750",
                    "ro.vendor.soc.manufacturer"              to "QTI",
                    "ro.vendor.soc.model.external_name"       to "Snapdragon 8 Elite",
                    "ro.product.cpu.abi"                      to "arm64-v8a",
                    "ro.build.fingerprint"                    to "OnePlus/OP611FL1/OP611FL1:15/AQ3A.240812.002/R.1726726127OP611FL1:user/release-keys",
                    "ro.product.build.fingerprint"            to "OnePlus/OP611FL1/OP611FL1:15/AQ3A.240812.002/R.1726726127OP611FL1:user/release-keys",
                    "ro.system.build.fingerprint"             to "OnePlus/OP611FL1/OP611FL1:15/AQ3A.240812.002/R.1726726127OP611FL1:user/release-keys",
                    "ro.vendor.build.fingerprint"             to "OnePlus/OP611FL1/OP611FL1:15/AQ3A.240812.002/R.1726726127OP611FL1:user/release-keys",
                    "ro.bootimage.build.fingerprint"          to "OnePlus/OP611FL1/OP611FL1:15/AQ3A.240812.002/R.1726726127OP611FL1:user/release-keys",
                    "ro.odm.build.fingerprint"                to "OnePlus/OP611FL1/OP611FL1:15/AQ3A.240812.002/R.1726726127OP611FL1:user/release-keys"
                )
            )
        )
    ),

    SpoofBrand(
        name    = "Samsung",
        icon    = Icons.Filled.Smartphone,
        devices = listOf(
            SpoofDevice(
                name        = "Samsung Galaxy Z Fold5",
                chipset     = "Snapdragon 8 Gen 2",
                model       = "SM-F9460",
                fingerprint = "samsung/q5qzhucu/q5q:14/UP1A.231005.007/F9460ZHU3CXB1:user/release-keys",
                props       = mapOf(
                    "ro.product.brand"                        to "samsung",
                    "ro.product.brand_for_attestation"        to "samsung",
                    "ro.product.manufacturer"                 to "samsung",
                    "ro.product.model"                        to "SM-F9460",
                    "ro.product.name"                         to "q5qzhucu",
                    "ro.product.device"                       to "q5q",
                    "ro.product.odm.model"                    to "SM-F9460",
                    "ro.product.system.model"                 to "SM-F9460",
                    "ro.product.vendor.model"                 to "SM-F9460",
                    "ro.product.odm.marketname"               to "Galaxy Z Fold5",
                    "ro.product.product.marketname"           to "Galaxy Z Fold5",
                    "ro.product.system.marketname"            to "Galaxy Z Fold5",
                    "ro.product.vendor.marketname"            to "Galaxy Z Fold5",
                    "ro.product.marketname"                   to "Galaxy Z Fold5",
                    "ro.product.system.brand"                 to "samsung",
                    "ro.product.vendor.brand"                 to "samsung",
                    "ro.product.odm.brand"                    to "samsung",
                    "ro.product.system.manufacturer"          to "samsung",
                    "ro.product.vendor.manufacturer"          to "samsung",
                    "ro.soc.manufacturer"                     to "Qualcomm",
                    "ro.soc.model"                            to "SM8650",
                    "ro.soc.model.external_name"              to "Snapdragon 8 Gen 2",
                    "ro.hardware.chipname"                    to "SM8650",
                    "ro.board.platform"                       to "kalama",
                    "ro.vendor.qti.soc_model"                 to "SM8650",
                    "ro.vendor.qti.soc_name"                  to "SM8650-AB",
                    "ro.vendor.qti.chip_name"                 to "SM8650",
                    "ro.vendor.soc.model"                     to "SM8650",
                    "ro.vendor.soc.manufacturer"              to "QTI",
                    "ro.vendor.soc.model.external_name"       to "Snapdragon 8 Gen 2",
                    "sys.fps_unlock_allowed"                  to "120",
                    "ro.build.fingerprint"                    to "samsung/q5qzhucu/q5q:14/UP1A.231005.007/F9460ZHU3CXB1:user/release-keys",
                    "ro.product.build.fingerprint"            to "samsung/q5qzhucu/q5q:14/UP1A.231005.007/F9460ZHU3CXB1:user/release-keys",
                    "ro.system.build.fingerprint"             to "samsung/q5qzhucu/q5q:14/UP1A.231005.007/F9460ZHU3CXB1:user/release-keys",
                    "ro.vendor.build.fingerprint"             to "samsung/q5qzhucu/q5q:14/UP1A.231005.007/F9460ZHU3CXB1:user/release-keys",
                    "ro.bootimage.build.fingerprint"          to "samsung/q5qzhucu/q5q:14/UP1A.231005.007/F9460ZHU3CXB1:user/release-keys",
                    "ro.odm.build.fingerprint"                to "samsung/q5qzhucu/q5q:14/UP1A.231005.007/F9460ZHU3CXB1:user/release-keys"
                )
            )
        )
    ),

    SpoofBrand(
        name    = "Nubia RedMagic",
        icon    = Icons.Filled.SportsEsports,
        devices = emptyList(),
        isSoon  = true
    ),

    SpoofBrand(
        name    = "Xiaomi",
        icon    = Icons.Filled.PhoneAndroid,
        devices = listOf(
            SpoofDevice(
                name        = "Xiaomi 14 Ultra",
                chipset     = "Snapdragon 8 Gen 3",
                model       = "24030PN60G",
                fingerprint = "",
                props       = mapOf(
                    "ro.product.brand"                        to "Xiaomi",
                    "ro.product.manufacturer"                 to "Xiaomi",
                    "ro.product.marketname"                   to "Xiaomi 14 Ultra",
                    "ro.product.model"                        to "24030PN60G",
                    "ro.product.odm.brand"                    to "Xiaomi",
                    "ro.product.odm.manufacturer"             to "Xiaomi",
                    "ro.product.odm.marketname"               to "Xiaomi 14 Ultra",
                    "ro.product.odm.model"                    to "24030PN60G",
                    "ro.product.product.brand"                to "Xiaomi",
                    "ro.product.product.manufacturer"         to "Xiaomi",
                    "ro.product.product.marketname"           to "Xiaomi 14 Ultra",
                    "ro.product.product.model"                to "24030PN60G",
                    "ro.product.system.brand"                 to "Xiaomi",
                    "ro.product.system.manufacturer"          to "Xiaomi",
                    "ro.product.system.marketname"            to "Xiaomi 14 Ultra",
                    "ro.product.system.model"                 to "24030PN60G",
                    "ro.product.system_ext.brand"             to "Xiaomi",
                    "ro.product.system_ext.manufacturer"      to "Xiaomi",
                    "ro.product.system_ext.marketname"        to "Xiaomi 14 Ultra",
                    "ro.product.system_ext.model"             to "24030PN60G",
                    "ro.product.vendor.brand"                 to "Xiaomi",
                    "ro.product.vendor.manufacturer"          to "Xiaomi",
                    "ro.product.vendor.marketname"            to "Xiaomi 14 Ultra",
                    "ro.product.vendor.model"                 to "24030PN60G",
                    "ro.soc.manufacturer"                     to "Qualcomm",
                    "ro.soc.model"                            to "SM8650",
                    "sys.fps_unlock_allowed"                  to "120",
                    "ro.vendor.display.default_fps"           to "120"
                )
            )
        )
    ),

    SpoofBrand(
        name    = "POCO",
        icon    = Icons.Filled.PhoneAndroid,
        devices = emptyList(),
        isSoon  = true
    ),
    SpoofBrand(
        name    = "Realme",
        icon    = Icons.Filled.PhoneAndroid,
        devices = emptyList(),
        isSoon  = true
    ),
    SpoofBrand(
        name    = "Vivo",
        icon    = Icons.Filled.Smartphone,
        devices = emptyList(),
        isSoon  = true
    ),
    SpoofBrand(
        name    = "OPPO",
        icon    = Icons.Filled.Smartphone,
        devices = emptyList(),
        isSoon  = true
    )
)
