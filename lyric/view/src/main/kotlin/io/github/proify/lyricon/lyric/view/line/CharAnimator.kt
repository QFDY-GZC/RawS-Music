/*
* Copyright 2026 Proify, Tomakino
* Licensed under the Apache License, Version 2.0
* http://www.apache.org/licenses/LICENSE-2.0
*/

package io.github.proify.lyricon.lyric.view.line

import io.github.proify.lyricon.lyric.view.line.model.WordModel
import io.github.proify.lyricon.lyric.view.spring.SpringAnimation
import io.github.proify.lyricon.lyric.view.spring.SpringForce
import kotlin.math.abs
import kotlin.math.sin

/**
 * 字符动画控制器
 * 负责管理歌词字符的轻微上浮、放大、悬浮、下沉动画
 * 
 * 动画流程（Spring 物理弹簧 + 呼吸脉动驱动）：
 * 1. FLOATING_UP  — 字符开始演唱 → Spring 驱动上浮+放大（欠阻尼微回弹）
 * 2. HOVERING     — 上浮到位后 → 在位脉动呼吸（缩放循环 1.0↔1.06），解决长音节静止问题
 * 3. FLOATING_DOWN — 字符演唱结束 → Spring 驱动下沉+缩小（欠阻尼软着陆）
 * 4. 与下一个字符的上浮动画自然衔接
 * 
 * 参数参考 Apple Music 反编译：
 * - Spring: dampingRatio=0.93（欠阻尼，微妙回弹），stiffness=25（软弹簧，自然减速）
 * - 缩放循环: 1.0 → 1.2 → 1.0（周期 4000ms）
 * - 插值器: PathInterpolator(0.25, 0.1, 0.25, 1.0) 上浮
 *           PathInterpolator(0.25, 0.0, 1.0, 0.2) 下沉（急促减速）
 *
 * 适配我们的微动画场景（1dp偏移），调整 stiffness 使动画时长合理。
 */
class CharAnimator {
    
    companion object {
        // 上浮偏移量（dp）
        const val FLOAT_OFFSET_DP = 1.0f
        
        // 缩放比例 - 放大6%（Apple 用 1.2 但那是对整行，字符级用较小值）
        const val SCALE_RATIO = 1.06f
        
        // --- 上浮弹簧参数（欠阻尼，微回弹，自然减速） ---
        // Apple 原版: dampingRatio=0.93, stiffness=25（对大距离偏移）
        // 适配 1dp 微偏移：提高 stiffness 保持合理动画时长
        private const val FLOAT_UP_DAMPING_RATIO = 0.75f
        private const val FLOAT_UP_STIFFNESS = 120f
        
        // --- 下沉弹簧参数（欠阻尼，软着陆） ---
        // 使用较高阻尼比减少过冲，避免 offset 穿越零点导致闪烁
        private const val FLOAT_DOWN_DAMPING_RATIO = 0.95f
        private const val FLOAT_DOWN_STIFFNESS = 220f
        
        // 下沉提前结束阈值：当 offset 足够接近目标（0）时立即完成动画，
        // 防止欠阻尼弹簧在零点附近振荡导致字符闪烁
        private const val FLOAT_DOWN_END_THRESHOLD_DP = 0.08f
        
        // --- 悬浮呼吸动画参数（参考 Apple 的缩放脉动循环） ---
        // 偏移呼吸：微幅浮动，在目标偏移附近振荡
        private const val HOVER_BREATHE_AMPLITUDE_DP = 0.12f
        // 偏移呼吸周期（ms），接近 Apple 的 4000ms 脉动周期
        private const val HOVER_BREATHE_PERIOD_MS = 4000L
        // 缩放呼吸振幅：脉动式 1.0↔SCALE_RATIO
        private const val HOVER_SCALE_BREATHE_AMPLITUDE = 0.015f
        // 缩放呼吸周期，与偏移略有不同产生自然感
        private const val HOVER_SCALE_BREATHE_PERIOD_MS = 3500L
        
        // 上浮动画时间占字符演唱时长的比例
        private const val FLOAT_UP_DURATION_RATIO = 0.7f
        
        // 最小上浮动画时间（ms）
        private const val MIN_FLOAT_UP_DURATION_MS = 120L
        
        // 最大上浮动画时间（ms），长音节也不会超过此值
        private const val MAX_FLOAT_UP_DURATION_MS = 800L
    }
    
    /**
     * 动画阶段
     */
    internal enum class AnimPhase {
        IDLE,           // 空闲
        FLOATING_UP,    // 上浮中（Spring 驱动，欠阻尼微回弹）
        HOVERING,       // 悬浮中（脉动呼吸，在目标位置微浮动+缩放脉动）
        FLOATING_DOWN   // 下沉中（Spring 驱动，欠阻尼软着陆）
    }
    
    /**
     * 单个字符的动画状态
     */
    internal class CharAnimState(
        val charIndex: Int,           // 字符在单词中的索引
        val word: WordModel,          // 所属单词
        var phase: AnimPhase = AnimPhase.IDLE,
        var currentOffset: Float = 0f, // 当前上浮偏移（dp）
        var currentScale: Float = 1f,  // 当前缩放比例
        var isFinished: Boolean = false,
        var charStartTime: Long = 0L,  // 字符演唱开始时间（播放位置）
        var charEndTime: Long = 0L,    // 字符演唱结束时间（播放位置）
        var floatUpDuration: Long = 200L, // 上浮动画持续时间
        // Spring 动画实例
        var offsetSpring: SpringAnimation? = null,
        var scaleSpring: SpringAnimation? = null,
        // 悬浮动画状态
        var hoverStartTimeNanos: Long = 0L, // 进入悬浮阶段的系统时间（纳秒）
        var hoverBaseOffset: Float = 0f,     // 悬浮基准偏移（上浮结束时的偏移值）
        var hoverBaseScale: Float = 1f       // 悬浮基准缩放（上浮结束时的缩放值）
    )
    
    // 当前活跃的字符动画
    private var activeChar: CharAnimState? = null
    // 上一个字符（用于下沉动画）
    private var previousChar: CharAnimState? = null
    
    // 上一次 update 时的播放位置，用于检测字符切换
    private var lastPosition = Long.MIN_VALUE
    
    /**
     * 更新动画状态
     * @param currentWord 当前单词
     * @param position 当前播放位置（毫秒）
     * @return 是否需要重绘
     */
    fun update(currentWord: WordModel?, position: Long): Boolean {
        // 没有单词，但可能有正在下沉的字符需要完成动画
        if (currentWord == null) {
            // 不立即终止所有动画，让下沉动画自然完成
            var needsRedraw = false
            
            // 检查 activeChar 是否需要下沉
            activeChar?.let { active ->
                if (active.phase == AnimPhase.HOVERING || active.phase == AnimPhase.FLOATING_UP) {
                    // 还没开始下沉，现在开始
                    transitionToFloatDown(active)
                    needsRedraw = true
                }
            }
            
            // 检查是否还在下沉
            val hasActiveAnimation = activeChar?.let {
                it.phase == AnimPhase.FLOATING_UP || it.phase == AnimPhase.FLOATING_DOWN || it.phase == AnimPhase.HOVERING
            } ?: false
            val hasPreviousAnimation = previousChar?.let {
                it.phase == AnimPhase.FLOATING_DOWN
            } ?: false
            
            if (!hasActiveAnimation && !hasPreviousAnimation) {
                // 确实没有动画了，安全清理
                val hadActive = activeChar != null || previousChar != null
                finishAllAnimations()
                lastPosition = position
                return hadActive
            }
            
            // 更新悬浮动画和检查完成
            updateHoverAnimation()
            checkSpringCompletion()
            
            lastPosition = position
            return needsRedraw || hasActiveAnimation || hasPreviousAnimation
        }
        
        var needsRedraw = false
        
        // 基于播放位置计算当前字符索引
        val charIndex = calculateCurrentCharIndex(currentWord, position)
        
        // 检查是否需要开始新字符的动画
        if (shouldStartNewAnimation(currentWord, charIndex, position)) {
            startFloatUpAnimation(currentWord, charIndex, position)
            needsRedraw = true
        }
        
        // 检查上浮阶段是否完成（Spring 停止），进入悬浮阶段
        activeChar?.let { active ->
            if (active.phase == AnimPhase.FLOATING_UP) {
                val offsetDone = active.offsetSpring?.isRunning == false
                val scaleDone = active.scaleSpring?.isRunning == false
                if (offsetDone && scaleDone) {
                    transitionToHovering(active)
                }
            }
        }
        
        // 悬浮阶段：字符演唱结束，进入下沉
        activeChar?.let { active ->
            if (active.phase == AnimPhase.HOVERING && position >= active.charEndTime) {
                transitionToFloatDown(active)
            }
        }
        
        // 如果上浮阶段字符演唱已经结束（短音节，上浮还没完成就要下沉），直接下沉
        activeChar?.let { active ->
            if (active.phase == AnimPhase.FLOATING_UP && position >= active.charEndTime) {
                transitionToFloatDown(active)
            }
        }
        
        // 更新悬浮动画（呼吸微浮动）
        updateHoverAnimation()
        
        // 检查 Spring 动画是否完成
        checkSpringCompletion()
        
        // 只要还有活跃动画，就需要持续重绘
        val hasActiveAnimation = activeChar?.let { 
            it.phase == AnimPhase.FLOATING_UP || it.phase == AnimPhase.FLOATING_DOWN || it.phase == AnimPhase.HOVERING
        } ?: false
        val hasPreviousAnimation = previousChar?.let { 
            it.phase == AnimPhase.FLOATING_DOWN
        } ?: false
        
        lastPosition = position
        
        return needsRedraw || hasActiveAnimation || hasPreviousAnimation
    }
    
    /**
     * 更新悬浮动画（呼吸脉动）
     * 参考 Apple 的缩放脉动：1.0 → 1.2 → 1.0 循环（周期4000ms）
     * 我们用较小的振幅做字符级脉动
     */
    private fun updateHoverAnimation() {
        activeChar?.let { char ->
            if (char.phase != AnimPhase.HOVERING) return
            
            val now = System.nanoTime()
            val elapsedMs = (now - char.hoverStartTimeNanos) / 1_000_000L
            
            // 偏移呼吸：在基准偏移附近微浮动（正弦曲线）
            val breatheOffset = (HOVER_BREATHE_AMPLITUDE_DP * 
                sin(2.0 * Math.PI * elapsedMs.toDouble() / HOVER_BREATHE_PERIOD_MS)).toFloat()
            char.currentOffset = char.hoverBaseOffset + breatheOffset
            
            // 缩放呼吸：在基准缩放附近脉动（正弦曲线）
            val breatheScale = (HOVER_SCALE_BREATHE_AMPLITUDE * 
                sin(2.0 * Math.PI * elapsedMs.toDouble() / HOVER_SCALE_BREATHE_PERIOD_MS)).toFloat()
            char.currentScale = char.hoverBaseScale + breatheScale
        }
    }
    
    /**
     * 基于播放位置计算当前应该高亮的字符索引
     */
    private fun calculateCurrentCharIndex(word: WordModel, position: Long): Int {
        val charCount = word.chars.size
        if (charCount == 0) return 0
        
        // 估算每个字符的时间范围
        val charDuration = word.duration / charCount
        for (i in 0 until charCount) {
            val charStart = word.begin + i * charDuration
            val charEnd = charStart + charDuration
            if (position < charEnd) {
                return i
            }
        }
        
        return charCount - 1
    }
    
    /**
     * 判断是否应该开始新动画
     */
    private fun shouldStartNewAnimation(word: WordModel, charIndex: Int, position: Long): Boolean {
        if (charIndex < 0 || charIndex >= word.chars.size) return false
        
        val active = activeChar ?: return true
        
        // 字符索引或单词改变了
        if (active.charIndex != charIndex || active.word != word) {
            return true
        }
        
        return false
    }
    
    /**
     * 计算字符的上浮动画持续时间
     * 基于字符演唱时长，确保长音节缓慢上浮、短音节快速上浮
     */
    private fun calculateFloatUpDuration(word: WordModel, charIndex: Int): Long {
        val charCount = word.chars.size
        if (charCount <= 0) return MIN_FLOAT_UP_DURATION_MS
        
        // 字符演唱时长 = 单词总时长 / 字符数
        val charDuration = word.duration / charCount
        
        // 上浮时间 = 字符时长的 70%
        val duration = (charDuration * FLOAT_UP_DURATION_RATIO).toLong()
        
        return duration.coerceIn(MIN_FLOAT_UP_DURATION_MS, MAX_FLOAT_UP_DURATION_MS)
    }
    
    /**
     * 计算字符的起止播放时间
     */
    private fun calculateCharTimeRange(word: WordModel, charIndex: Int): Pair<Long, Long> {
        val charCount = word.chars.size
        if (charCount <= 0) return Pair(word.begin, word.end)
        
        val charDuration = word.duration / charCount
        val charStart = word.begin + charIndex * charDuration
        val charEnd = charStart + charDuration
        
        return Pair(charStart, charEnd)
    }
    
    /**
     * 开始上浮动画
     */
    private fun startFloatUpAnimation(word: WordModel, charIndex: Int, position: Long) {
        // 清理已完成的上一个字符动画
        if (previousChar != null && previousChar!!.phase == AnimPhase.IDLE) {
            releaseCharState(previousChar!!)
            previousChar = null
        }
        
        // 检查是否是同一个字符（防御性检查）
        activeChar?.let { current ->
            if (current.charIndex == charIndex && current.word == word) {
                return
            }
        }
        
        // 处理旧的 activeChar，转为下沉
        activeChar?.let { current ->
            if (current.phase != AnimPhase.IDLE && current.phase != AnimPhase.FLOATING_DOWN) {
                // 如果 previousChar 还在下沉，强制完成它
                if (previousChar != null && previousChar!!.phase == AnimPhase.FLOATING_DOWN) {
                    forceFinishChar(previousChar!!)
                    releaseCharState(previousChar!!)
                    previousChar = null
                }
                
                // 将当前字符转为下沉
                transitionToFloatDown(current)
                previousChar = current
            } else if (current.phase == AnimPhase.FLOATING_DOWN) {
                previousChar = current
            }
        }
        
        // 计算上浮持续时间
        val floatUpDuration = calculateFloatUpDuration(word, charIndex)
        
        // 计算字符时间范围
        val (charStartTime, charEndTime) = calculateCharTimeRange(word, charIndex)
        
        // 创建新的上浮动画
        val newChar = CharAnimState(
            charIndex = charIndex,
            word = word,
            phase = AnimPhase.FLOATING_UP,
            currentOffset = 0f,
            currentScale = 1f,
            isFinished = false,
            charStartTime = charStartTime,
            charEndTime = charEndTime,
            floatUpDuration = floatUpDuration
        )
        
        // 创建上浮 Spring 动画
        // offset: 0 → FLOAT_OFFSET_DP (dp)，欠阻尼微回弹
        newChar.offsetSpring = SpringAnimation(initialValue = 0f).apply {
            setVelocityScale(0.01f)
            setSpring(
                SpringForce(FLOAT_OFFSET_DP.toDouble())
                    .setDampingRatio(FLOAT_UP_DAMPING_RATIO)
                    .setStiffness(FLOAT_UP_STIFFNESS)
            )
            addUpdateListener { value ->
                newChar.currentOffset = value
            }
            addEndListener { _ ->
                newChar.currentOffset = FLOAT_OFFSET_DP
            }
            start()
        }
        
        // scale: 1.0 → SCALE_RATIO，欠阻尼微回弹
        newChar.scaleSpring = SpringAnimation(initialValue = 1f).apply {
            setVelocityScale(0.001f)
            setSpring(
                SpringForce(SCALE_RATIO.toDouble())
                    .setDampingRatio(FLOAT_UP_DAMPING_RATIO)
                    .setStiffness(FLOAT_UP_STIFFNESS)
            )
            addUpdateListener { value ->
                newChar.currentScale = value
            }
            addEndListener { _ ->
                newChar.currentScale = SCALE_RATIO
            }
            start()
        }
        
        activeChar = newChar
    }
    
    /**
     * 从上浮过渡到悬浮（上浮 Spring 完成后进入）
     * 记录当前偏移/缩放作为悬浮基准，后续呼吸动画基于此微浮动
     */
    private fun transitionToHovering(char: CharAnimState) {
        if (char.phase != AnimPhase.FLOATING_UP) return
        
        char.phase = AnimPhase.HOVERING
        
        // 释放上浮 Spring
        releaseCharState(char)
        
        // 记录悬浮基准值
        char.hoverBaseOffset = FLOAT_OFFSET_DP
        char.hoverBaseScale = SCALE_RATIO
        char.currentOffset = FLOAT_OFFSET_DP
        char.currentScale = SCALE_RATIO
        
        // 记录进入悬浮的时间
        char.hoverStartTimeNanos = System.nanoTime()
    }
    
    /**
     * 将字符动画从悬浮/上浮过渡到下沉
     */
    private fun transitionToFloatDown(char: CharAnimState) {
        if (char.phase == AnimPhase.FLOATING_DOWN || char.phase == AnimPhase.IDLE) return
        
        char.phase = AnimPhase.FLOATING_DOWN
        
        // 取消上浮/悬浮 Spring（悬浮阶段 Spring 已释放，但防御性取消）
        char.offsetSpring?.cancel()
        char.scaleSpring?.cancel()
        
        // 创建下沉 Spring 动画
        // offset: 当前值 → 0，欠阻尼软着陆
        char.offsetSpring = SpringAnimation(initialValue = char.currentOffset).apply {
            setVelocityScale(0.01f)
            setSpring(
                SpringForce(0.0)
                    .setDampingRatio(FLOAT_DOWN_DAMPING_RATIO)
                    .setStiffness(FLOAT_DOWN_STIFFNESS)
            )
            addUpdateListener { value ->
                char.currentOffset = value
            }
            addEndListener { _ ->
                char.currentOffset = 0f
            }
            start()
        }
        
        // scale: 当前值 → 1.0，欠阻尼软着陆
        char.scaleSpring = SpringAnimation(initialValue = char.currentScale).apply {
            setVelocityScale(0.001f)
            setSpring(
                SpringForce(1.0)
                    .setDampingRatio(FLOAT_DOWN_DAMPING_RATIO)
                    .setStiffness(FLOAT_DOWN_STIFFNESS)
            )
            addUpdateListener { value ->
                char.currentScale = value
            }
            addEndListener { _ ->
                char.currentScale = 1f
            }
            start()
        }
    }
    
    /**
     * 检查 Spring 动画是否完成
     * 下沉阶段额外检查：当 offset 足够接近零时提前结束，避免欠阻尼弹簧过冲零点
     */
    private fun checkSpringCompletion() {
        // 检查 activeChar
        activeChar?.let { char ->
            val offsetDone = char.offsetSpring?.isRunning == false
            val scaleDone = char.scaleSpring?.isRunning == false
            
            if (char.phase == AnimPhase.FLOATING_DOWN) {
                // 提前结束：offset 接近零时立即完成动画，防止过冲导致闪烁
                val offsetNearZero = abs(char.currentOffset) < FLOAT_DOWN_END_THRESHOLD_DP
                val scaleNearOne = abs(char.currentScale - 1f) < 0.005f
                
                if ((offsetDone && scaleDone) || (offsetNearZero && scaleNearOne)) {
                    forceFinishChar(char)
                }
            }
        }
        
        // 检查 previousChar
        previousChar?.let { char ->
            val offsetDone = char.offsetSpring?.isRunning == false
            val scaleDone = char.scaleSpring?.isRunning == false
            
            if (char.phase == AnimPhase.FLOATING_DOWN) {
                val offsetNearZero = abs(char.currentOffset) < FLOAT_DOWN_END_THRESHOLD_DP
                val scaleNearOne = abs(char.currentScale - 1f) < 0.005f
                
                if ((offsetDone && scaleDone) || (offsetNearZero && scaleNearOne)) {
                    forceFinishChar(char)
                    releaseCharState(char)
                    previousChar = null
                }
            }
        }
        
        // 清理已完成的 activeChar
        activeChar?.let { char ->
            if (char.phase == AnimPhase.IDLE && char.isFinished) {
                releaseCharState(char)
                activeChar = null
            }
        }
    }
    
    /**
     * 强制完成字符动画
     * 将 offset/scale 设为最终值，标记为 IDLE + isFinished
     * 渲染层通过检查 isFinished 跳过动画渲染，避免与静态层重影
     */
    private fun forceFinishChar(char: CharAnimState) {
        char.offsetSpring?.let {
            if (it.isRunning) it.cancel()
        }
        char.scaleSpring?.let {
            if (it.isRunning) it.cancel()
        }
        
        char.currentOffset = 0f
        char.currentScale = 1f
        char.phase = AnimPhase.IDLE
        char.isFinished = true
    }
    
    /**
     * 释放字符状态的 Spring 资源
     */
    private fun releaseCharState(char: CharAnimState) {
        char.offsetSpring?.cancel()
        char.scaleSpring?.cancel()
        char.offsetSpring = null
        char.scaleSpring = null
    }
    
    /**
     * 完成所有动画
     */
    private fun finishAllAnimations() {
        activeChar?.let {
            forceFinishChar(it)
            releaseCharState(it)
        }
        activeChar = null
        previousChar?.let {
            forceFinishChar(it)
            releaseCharState(it)
        }
        previousChar = null
    }
    
    /**
     * 获取当前动画状态（用于渲染）
     */
    internal fun getActiveAnimation(): CharAnimState? = activeChar
    
    /**
     * 获取上一个动画状态（用于下沉动画）
     */
    internal fun getPreviousAnimation(): CharAnimState? = previousChar
    
    /**
     * 是否有仍在活跃阶段的字符动画（上浮/悬浮/下沉）
     * 用于 AnimationDriver 判断是否需要继续调度帧
     */
    internal val hasActiveAnimation: Boolean
        get() = (activeChar?.phase != null && activeChar!!.phase != AnimPhase.IDLE) ||
                (previousChar?.phase == AnimPhase.FLOATING_DOWN)
    
    /**
     * 重置动画状态
     */
    fun reset() {
        finishAllAnimations()
        lastPosition = Long.MIN_VALUE
    }
}
