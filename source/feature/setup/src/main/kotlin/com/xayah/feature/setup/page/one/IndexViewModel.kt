package com.xayah.feature.setup.page.one

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import com.topjohnwu.superuser.Shell
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.command.ShizukuShell
import com.xayah.core.util.withLog
import com.xayah.feature.setup.EnvState
import com.xayah.feature.setup.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import javax.inject.Inject

data class IndexUiState(
    val abiErr: String
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object ValidateRoot : IndexUiIntent()
    data object ValidateAbi : IndexUiIntent()
    data object OnResume : IndexUiIntent()
    data class ValidateNotification(val context: Context) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState(abiErr = "")) {
    @SuppressLint("StringFormatInvalid")
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.OnResume -> {
                mutex.withLock {
                    val isNotificationPermissionGranted = NotificationUtil.checkPermission(context)
                    if (isNotificationPermissionGranted) {
                        _notificationState.value = EnvState.Succeed
                    }
                }
            }

            is IndexUiIntent.ValidateRoot -> {
                emitIntent(IndexUiIntent.ValidateAbi)
                mutex.withLock {
                    if (rootState.value == EnvState.Idle || rootState.value == EnvState.Failed) {
                        _rootState.value = EnvState.Processing
                        runCatching {
                            BaseUtil.initializeEnvironment(context = context)
                        }
                        runCatching {
                            // Kill daemon
                            BaseUtil.kill(context, "${context.packageName}:root:daemon")
                        }.withLog()

                        val hasRoot = runCatching { Shell.getShell().isRoot }.getOrElse { false }
                        _rootState.value = if (hasRoot) EnvState.Succeed else validateShizuku()
                    }
                }
            }

            is IndexUiIntent.ValidateAbi -> {
                mutex.withLock {
                    if (abiState.value == EnvState.Idle || abiState.value == EnvState.Failed) {
                        _abiState.value = EnvState.Processing
                        val buildABI = BuildConfigUtil.FLAVOR_abi
                        val deviceABI = Build.SUPPORTED_ABIS.firstOrNull().toString()
                        if (buildABI == deviceABI) {
                            _abiState.value = if (runCatching { BaseUtil.releaseBase(context = context) }.getOrElse { false }) {
                                emitState(state.copy(abiErr = ""))
                                EnvState.Succeed
                            } else {
                                EnvState.Failed
                            }
                        } else {
                            _abiState.value = EnvState.Failed
                            emitState(
                                state.copy(
                                    abiErr = context.getString(
                                        R.string.this_version_only_supports_but_your_device_is_please_install_version,
                                        buildABI,
                                        deviceABI,
                                        deviceABI
                                    )
                                )
                            )
                        }
                    }
                }
            }

            is IndexUiIntent.ValidateNotification -> {
                mutex.withLock {
                    if (notificationState.value != EnvState.Succeed)
                        NotificationUtil.requestPermissions(intent.context)
                }
            }
        }
    }

    private val mutex = Mutex()

    private var permissionListenerRegistered = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            emitIntentOnIO(IndexUiIntent.ValidateRoot)
        }
    }

    /**
     * Jalur tanpa root.
     *
     * Shizuku memberi kita uid 2000 (shell). Kalau izinnya belum ada, kita
     * minta dulu; [permissionListener] akan memicu validasi ulang begitu
     * pengguna menekan "Allow", sehingga pengguna cukup menekan sekali.
     *
     * Mengembalikan [EnvState.Idle] saat izin masih menunggu supaya tombolnya
     * bisa ditekan lagi kalau dialognya tertutup.
     */
    private suspend fun validateShizuku(): EnvState {
        if (ShizukuShell.isAvailable().not()) return EnvState.Failed

        if (ShizukuShell.hasPermission().not()) {
            ensurePermissionListener()
            ShizukuShell.requestPermission()
            return EnvState.Idle
        }

        return if (BaseUtil.initializeShizukuMode(context = context)) EnvState.Succeed else EnvState.Failed
    }

    private fun ensurePermissionListener() {
        if (permissionListenerRegistered) return
        // Pendaftaran gagal kalau binder Shizuku belum diterima; dicoba lagi
        // pada pemanggilan berikutnya.
        permissionListenerRegistered = runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }.isSuccess
    }

    override fun onCleared() {
        if (permissionListenerRegistered) {
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        }
        super.onCleared()
    }

    private val _rootState: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Idle)
    val rootState: StateFlow<EnvState> = _rootState.stateInScope(EnvState.Idle)
    private val _abiState: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Idle)
    val abiState: StateFlow<EnvState> = _abiState.stateInScope(EnvState.Idle)
    private val _notificationState: MutableStateFlow<EnvState> = MutableStateFlow(EnvState.Idle)
    val notificationState: StateFlow<EnvState> = _notificationState.stateInScope(EnvState.Idle)

    val allRequiredValidated: StateFlow<Boolean> = combine(_rootState, _abiState) { root, abi -> root == EnvState.Succeed && abi == EnvState.Succeed }.flowOnIO().stateInScope(false)
    val allOptionalValidated: StateFlow<Boolean> = _notificationState.map { notification -> notification == EnvState.Succeed }.flowOnIO().stateInScope(false)
}
