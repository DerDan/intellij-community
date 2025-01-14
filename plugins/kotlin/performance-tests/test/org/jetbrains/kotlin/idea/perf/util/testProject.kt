// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.perf.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.module.impl.ProjectLoadingErrorsHeadlessNotifier
import org.jetbrains.kotlin.idea.testFramework.ProjectOpenAction

class ExternalProject(val path: String, val openWith: ProjectOpenAction) {
    companion object {
        const val KOTLIN_PROJECT_PATH = "../perfTestProject"

        val KOTLIN_GRADLE = ExternalProject(KOTLIN_PROJECT_PATH, ProjectOpenAction.GRADLE_PROJECT)
        val KOTLIN_JPS = ExternalProject(KOTLIN_PROJECT_PATH, ProjectOpenAction.EXISTING_IDEA_PROJECT)

        // not intended for using in unit tests, only for local verification
        val KOTLIN_AUTO = ExternalProject(KOTLIN_PROJECT_PATH, autoOpenAction(KOTLIN_PROJECT_PATH))

        fun autoOpenAction(path: String): ProjectOpenAction {
            return if (exists(path, ".idea", "modules.xml"))
                ProjectOpenAction.EXISTING_IDEA_PROJECT
            else
                ProjectOpenAction.GRADLE_PROJECT
        }
    }
}

internal fun Disposable.registerLoadingErrorsHeadlessNotifier() {
    ProjectLoadingErrorsHeadlessNotifier.setErrorHandler(
        { description ->
            throw RuntimeException(description.description)
        },
        this
    )

}
