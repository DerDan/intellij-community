// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.collaboration.auth.ui

import com.intellij.collaboration.auth.Account
import com.intellij.collaboration.auth.AccountDetails
import com.intellij.collaboration.ui.codereview.SingleValueModelImpl
import com.intellij.collaboration.util.ProgressIndicatorsProvider
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.annotations.Nls
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import javax.swing.Icon

abstract class LoadingAccountsDetailsProvider<in A : Account, D : AccountDetails>(
  private val progressIndicatorsProvider: ProgressIndicatorsProvider
) : AccountsDetailsProvider<A, D> {

  private val detailsMap = mutableMapOf<A, CompletableFuture<DetailsLoadingResult<D>>>()
  override val loadingStateModel = SingleValueModelImpl(false)

  private var runningProcesses = 0

  override fun getDetails(account: A): D? =
    getOrLoad(account).getNow(null)?.details

  private fun getOrLoad(account: A): CompletableFuture<DetailsLoadingResult<D>> {
    return detailsMap.getOrPut(account) {
      val indicator = progressIndicatorsProvider.acquireIndicator()
      runningProcesses++
      loadingStateModel.value = true
      scheduleLoad(account, indicator).whenComplete(BiConsumer { _, _ ->
        invokeAndWaitIfNeeded(ModalityState.any()) {
          progressIndicatorsProvider.releaseIndicator(indicator)
          runningProcesses--
          if(runningProcesses == 0) loadingStateModel.value = false
        }
      })
    }
  }

  abstract fun scheduleLoad(account: A, indicator: ProgressIndicator): CompletableFuture<DetailsLoadingResult<D>>

  override fun getIcon(account: A): Icon? = getOrLoad(account).getNow(null)?.icon

  override fun getErrorText(account: A): String? = getOrLoad(account).getNow(null)?.error

  override fun checkErrorRequiresReLogin(account: A) = getOrLoad(account).getNow(null)?.needReLogin ?: false

  override fun reset(account: A) {
    detailsMap.remove(account)
  }

  override fun resetAll() = detailsMap.clear()

  data class DetailsLoadingResult<D : AccountDetails>(val details: D?,
                                                      val icon: Icon?,
                                                      @Nls val error: String?,
                                                      val needReLogin: Boolean)
}