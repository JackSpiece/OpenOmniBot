package cn.com.omnimind.uikit.api.uieventimpl

import android.content.Context
import cn.com.omnimind.assists.api.eventapi.ExecutingTaskType
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.LocalizedText
import cn.com.omnimind.baselib.util.VibrationUtil
import cn.com.omnimind.uikit.UIKit
import cn.com.omnimind.uikit.api.uievent.UITaskEvent
import cn.com.omnimind.uikit.loader.CancelClickLoader
import cn.com.omnimind.uikit.loader.FloatingHalfScreenLoader
import cn.com.omnimind.uikit.loader.ScreenMaskLoader
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UITaskEventImpl : UITaskEvent {
    private var taskUIJob: CoroutineScope? = null

    private fun tr(zh: String, en: String): String =
        LocalizedText(zhCN = zh, enUS = en).resolve(AppLocaleManager.currentPromptLocale())
    private var context: Context? = null

    override fun onUIInit(context: Context) {
        this.context = context
    }

    override suspend fun startCompanionAndDoingTask() {
        if (taskUIJob?.isActive == true) {
            withContext(Dispatchers.Main) {
                ScreenMaskLoader.destroyInstance()
                DraggableBallInstance.cancelAnimation()
                taskUIJob?.cancel()
                DraggableBallInstance.destroy()
                ScreenMaskLoader.destroyInstance()
                CancelClickLoader.destroyInstance()
                FloatingHalfScreenLoader.destroyInstance()
            }
        }
        taskUIJob = CoroutineScope(Dispatchers.IO)
        taskUIJob?.launch {
            withContext(Dispatchers.Main) {
                VibrationUtil.vibrateLight()
                CancelClickLoader.cancelIntercepting()
                ScreenMaskLoader.loadGoneViewScreenMask()
                DraggableBallInstance.loadBall()
                ScreenMaskLoader.loadLockScreenMask()
                DraggableBallInstance.doingTask(
                    tr("小万已领取任务，即将开始执行", "Omnibot accepted the task, starting now"),
                    tr("执行中", "Running")
                )
            }
        }
    }

    override suspend fun waitingUserAction(message: String): Boolean {
        VibrationUtil.vibrateLight()
        withContext(Dispatchers.Main) {
            ScreenMaskLoader.loadGoneViewScreenMask()
        }
        val isResume = DraggableBallInstance.userTakeover(message)
        if (isResume) {
            val subMessage = when (UIKit.executionTaskEventApi?.taskType) {
                ExecutingTaskType.VLM -> tr("智能执行中", "Running")
                ExecutingTaskType.EMPTY -> tr("智能执行中", "Running")
                null -> tr("智能执行中", "Running")
            }
            withContext(Dispatchers.Main) {
                DraggableBallInstance.doingTask(tr("用户操作已完成", "Your action is complete"), subMessage)
            }
        }
        return isResume
    }

    override suspend fun pauseTask(message: String) {
        VibrationUtil.vibrateLight()
        withContext(Dispatchers.Main) {
            ScreenMaskLoader.loadGoneViewScreenMask()
            DraggableBallInstance.pauseTask(message)
        }
    }

    override suspend fun readyDoingTask(message: String) {
        VibrationUtil.vibrateNormal()
        withContext(Dispatchers.Main) {
            ScreenMaskLoader.loadLockScreenMask()
            DraggableBallInstance.readyDoingTask(message)
        }
    }

    override suspend fun startDoingAutoTask(message: String, subMessage: String) {
        VibrationUtil.vibrateNormal()
        withContext(Dispatchers.Main) {
            ScreenMaskLoader.loadLockScreenMask()
            DraggableBallInstance.doingTask(message, subMessage)
        }
    }

    override suspend fun finishDoingTask(message: String) {
        VibrationUtil.vibrateNormal()
        withContext(Dispatchers.Main) {
            ScreenMaskLoader.loadGoneViewScreenMask()
            DraggableBallInstance.finishDoingTask(message)
        }
    }

    override suspend fun setDoing(message: String, showTakeOver: Boolean) {
        withContext(Dispatchers.Main) {
            ScreenMaskLoader.loadLockScreenMask()
            DraggableBallInstance.setDoing(message, showTakeOver)
        }
    }

    override suspend fun showScheduledTip(
        closeTimer: Long,
        doTaskTimer: Long,
    ) {
        withContext(Dispatchers.Main) {
            DraggableBallInstance.showScheduledTip(closeTimer, doTaskTimer)
        }
    }
}
