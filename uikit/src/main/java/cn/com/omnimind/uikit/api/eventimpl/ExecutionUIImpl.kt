package cn.com.omnimind.uikit.api.eventimpl

import cn.com.omnimind.assists.api.enums.TaskFinishType
import cn.com.omnimind.assists.api.eventapi.ExecutingTaskType
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.assists.task.vlmserver.VLMOperationTask
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.LocalizedText
import cn.com.omnimind.baselib.shizuku.ControlMethod
import cn.com.omnimind.baselib.shizuku.ShizukuControlState
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import cn.com.omnimind.uikit.api.uievent.UIBaseEvent
import cn.com.omnimind.uikit.api.uievent.UIChatEvent
import cn.com.omnimind.uikit.api.uievent.UITaskEvent
import cn.com.omnimind.uikit.util.NotificationUtil
import kotlinx.coroutines.delay

class ExecutionUIImpl(
    val uiChatEvent: UIChatEvent,
    val uiBaseEvent: UIBaseEvent,
    val uiTaskEvent: UITaskEvent,
    override var taskType: ExecutingTaskType = ExecutingTaskType.EMPTY,
    override var vlmTask: VLMOperationTask? = null
) : ExecutionTaskEventApi {


    override suspend fun onReadyStartVLMTask(task: VLMOperationTask) {
        taskType = ExecutingTaskType.VLM
        vlmTask = task
        uiTaskEvent.readyDoingTask(tr("小万即将为您执行任务...", "Omnibot is getting ready to run your task..."))
        
        // 可取消的延迟：每100ms检查一次取消状态，共检查20次（2秒）
        repeat(20) {
             if (task.isCancellationRequested) {
                throw kotlinx.coroutines.CancellationException("Task cancelled during pre-execution delay")
             }
            delay(100)
        }
    }

    override suspend fun onStartVLMTask(isCompanionRunning: Boolean) {
        if (uiChatEvent.isChatBotHalfScreenShowing()) {
            uiChatEvent.dismissHalfScreen()
        }
        if (isCompanionRunning) {
            uiTaskEvent.startDoingAutoTask(
                tr("小万已领取任务，即将开始执行", "Omnibot accepted the task, starting now"),
                tr("智能执行中", "Running")
            )
        } else {
            uiTaskEvent.startCompanionAndDoingTask()
        }
    }

    override suspend fun onVlmTaskPaused(vmlTask: VLMOperationTask) {
        uiBaseEvent.cancelLockScreenMask()
        uiTaskEvent.pauseTask(tr("用户已接管任务", "You've taken over the task"))
    }

    override suspend fun onVLMTaskStop(
        finishType: TaskFinishType, message: String, isCompanionRunning: Boolean
    ) {
        if (finishType == TaskFinishType.WAITING_INPUT) {
            val isResume = uiTaskEvent.waitingUserAction(message)
            if (isResume) {
                vlmTask?.provideUserInput(tr("用户已完成操作,请继续执行", "I've finished, please continue"))
            } else {
                vlmTask?.finishTask()
            }
        } else if (finishType == TaskFinishType.USER_PAUSED) {
            uiTaskEvent.pauseTask(tr("用户已接管任务", "You've taken over the task"))
            OmniLog.d("StateMachine", "VLM任务进入用户主动暂停状态")
        } else {
            vlmTask = null
            ShizukuControlState.clearMethod()
            uiTaskEvent.finishDoingTask(message.ifEmpty { finishType.message })
            if (!isCompanionRunning) {
                delay(500)//动画执行完毕再执行结束
                uiBaseEvent.finishCompanion()
            }
        }
    }

    override suspend fun readyOpenThirdAPP(packageName: String) {
        uiTaskEvent.setDoing(tr("正在打开应用...", "Opening app..."), false);
    }

    //无障碍能力相关
    override suspend fun clickCoordinate(
        x: Float, y: Float, block: suspend () -> Unit
    ) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            uiBaseEvent.move(x, y, x, y)
            uiBaseEvent.showClickIndicator(x.toInt(), y.toInt())
            block.invoke()
        })
    }

    override suspend fun clickCoordinateWithOutLock(
        x: Float, y: Float, clickCoordinateFun: suspend () -> Unit
    ) {
        uiBaseEvent.move(x, y, x, y)
        uiBaseEvent.showClickIndicator(x.toInt(), y.toInt())
        clickCoordinateFun.invoke()
    }

    override suspend fun goBack(goBackFun: suspend () -> Unit) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            goBackFun.invoke()
        })
    }


    override suspend fun scrollCoordinate(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Int,
        scrollCoordinateFun: suspend () -> Unit
    ) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            var endX = x
            var endY = y
            when (direction) {
                ScrollDirection.LEFT -> {
                    endX = x - distance
                }

                ScrollDirection.RIGHT -> {
                    endX = x + distance
                }

                ScrollDirection.UP -> {
                    endY = y - distance
                }

                ScrollDirection.DOWN -> {
                    endY = y + distance
                }
            }
            // 等待动画完成后再执行滑动操作
            uiBaseEvent.move(x, y, endX, endY)
            // 确保动画完成后再执行实际滑动
            scrollCoordinateFun.invoke()
        })
    }

    override suspend fun longClickCoordinate(
        x: Float, y: Float, longClickCoordinateFun: suspend () -> Unit
    ) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            uiBaseEvent.move(x, y, x, y)
            longClickCoordinateFun.invoke()
        })
    }

    override suspend fun goHome(goHomeFun: suspend () -> Unit) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            goHomeFun.invoke()
        })
    }

    override suspend fun inputText(inputTextFun: suspend () -> Unit) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            inputTextFun.invoke()
        })
    }

    override suspend fun pasteText(pasteTextFun: suspend () -> Unit) {
        uiBaseEvent.doAssistsUnlockScreenMask({
            pasteTextFun.invoke()
        })
    }

    //业务相关
    override suspend fun showChatWithSummary() {
        uiChatEvent.showChatBotHalfScreen("summary")
    }

    override suspend fun userTakeover(message: String): Boolean {
        return uiTaskEvent.waitingUserAction(message)
    }

    // Localize fixed floating-window status strings to the app language.
    private fun tr(zh: String, en: String): String =
        LocalizedText(zhCN = zh, enUS = en).resolve(AppLocaleManager.currentPromptLocale())

    override suspend fun updateShowStepText(message: String) {
        uiTaskEvent.setDoing(withControlMethodBadge(message), true)
    }

    /**
     * Prefix the step text with a live badge showing which technique is
     * currently driving the phone (Shizuku vs Accessibility), so the user can
     * see in real time how each action is being delivered. Shows nothing until
     * the first action has run.
     */
    private fun withControlMethodBadge(message: String): String {
        val badge = when (ShizukuControlState.activeMethod.value) {
            ControlMethod.SHIZUKU -> "🛡 Shizuku"
            ControlMethod.ACCESSIBILITY -> "👆 Accessibility"
            null -> return message
        }
        return "[$badge] $message"
    }

    override suspend fun dismissScheduledNotification() {
        NotificationUtil.dismiss()
    }

    override suspend fun startFirstUseMessage(string: String) {
        uiBaseEvent.message(string)
    }

    override suspend fun showScheduledTip(closeTime: Long, doTaskTime: Long) {
        uiTaskEvent.showScheduledTip(closeTime, doTaskTime)
    }


}
