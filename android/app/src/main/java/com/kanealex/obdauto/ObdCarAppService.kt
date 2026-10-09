package com.kanealex.obdauto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ObdCarAppService : CarAppService() {
    // Sideloaded personal app, so accept any host (Android Auto, emulator, desktop head unit).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = DashScreen(carContext)
    }
}

/**
 * Live readings as a list. The row count and titles never change, only the values,
 * so each update is a template refresh rather than a new screen.
 */
class DashScreen(carContext: CarContext) : Screen(carContext) {
    private var state = ObdManager.state.value

    private val rowLimit: Int = try {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    } catch (ignored: Exception) {
        6
    }

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ObdManager.state.collect {
                    state = it
                    invalidate()
                    delay(1_000)
                }
            }
        }
        if (!ObdManager.isRunning) ObdService.start(carContext)
    }

    override fun onGetTemplate(): Template {
        val s = state
        val list = ItemList.Builder()
        list.addItem(Row.Builder().setTitle("Status").addText(s.status).build())
        s.values.entries.take(rowLimit - 1).forEach { (label, value) ->
            list.addItem(Row.Builder().setTitle(label).addText(value).build())
        }
        val toggle = if (ObdManager.isRunning) {
            Action.Builder().setTitle("Disconnect").setOnClickListener { ObdManager.disconnect() }.build()
        } else {
            Action.Builder().setTitle("Connect").setOnClickListener { ObdService.start(carContext) }.build()
        }
        return ListTemplate.Builder()
            .setTitle("OBD Auto")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .setActionStrip(ActionStrip.Builder().addAction(toggle).build())
            .build()
    }
}
