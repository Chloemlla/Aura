package com.google.android.gms.common.moduleinstall

import android.content.Context
import com.google.android.gms.tasks.Task

object ModuleInstall {
    fun getClient(context: Context): ModuleInstallClient = ModuleInstallClient()
}

class ModuleInstallClient {
    fun installModules(request: ModuleInstallRequest): Task<Void?> = Task.success()
}

class ModuleInstallRequest private constructor() {
    class Builder {
        fun addApi(api: Any): Builder = this
        fun build(): ModuleInstallRequest = ModuleInstallRequest()
    }

    companion object {
        fun newBuilder(): Builder = Builder()
    }
}
