package com.hinnka.mycamera.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BillingManagerImpl(private val context: Context) : BillingManager {
    private val _isPurchased = MutableStateFlow(true)
    override val isPurchased: StateFlow<Boolean> = _isPurchased.asStateFlow()

    override fun purchase(activity: Activity) {
    }

    override fun refresh() {
    }
}
