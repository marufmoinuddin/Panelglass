package com.smnexstudio.panelglass.feature.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore

/**
 * Holds view models built by hand in a store, so a test can clear them (cancelling their scopes) before it closes the
 * database they read: a scope left running would query a closed database and fail a later test.
 */
internal class TestViewModels {
    private val store = ViewModelStore()
    private var n = 0

    fun <T : ViewModel> keep(vm: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        }
        return ViewModelProvider(store, factory)["vm" + n++, vm.javaClass]
    }

    fun clear() = store.clear()
}
