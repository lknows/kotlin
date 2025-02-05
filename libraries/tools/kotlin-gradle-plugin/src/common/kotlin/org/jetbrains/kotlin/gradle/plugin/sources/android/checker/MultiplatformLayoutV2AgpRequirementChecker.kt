/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.sources.android.checker

import com.android.Version
import org.jetbrains.kotlin.gradle.plugin.compareVersionNumbers
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.sources.android.KotlinAndroidSourceSetLayout

internal object MultiplatformLayoutV2AgpRequirementChecker : KotlinAndroidSourceSetLayoutChecker {

    private const val AGP_VERSION_MIN = "7.0.0"

    override fun checkBeforeLayoutApplied(target: KotlinAndroidTarget, layout: KotlinAndroidSourceSetLayout) {
        if (!isAgpRequirementMet()) {
            throw KotlinAndroidSourceSetLayoutChecker.ProjectMisconfiguredException(
                """
                    ${layout.name} requires Android Gradle Plugin Version >= $AGP_VERSION_MIN.
                    Found ${Version.ANDROID_GRADLE_PLUGIN_VERSION}
                """.trimIndent()
            )
        }
    }

    internal fun isAgpRequirementMet(): Boolean {
        return compareVersionNumbers(Version.ANDROID_GRADLE_PLUGIN_VERSION, AGP_VERSION_MIN) >= 0
    }
}
