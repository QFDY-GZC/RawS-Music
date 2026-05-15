package com.rawsmusic.core.common.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    protected lateinit var binding: VB

    protected open val bindingInflater: (() -> VB)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindingInflater?.let {
            binding = it.invoke()
            setContentView(binding.root)
        }
        initView()
        initData()
        initObserver()
        initListener()
    }

    protected abstract fun initView()

    protected open fun initData() {}

    protected open fun initObserver() {}

    protected open fun initListener() {}

    protected val lifecycleScopeDefault: CoroutineScope
        get() = lifecycleScope

    protected val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
    }
}
