package com.rawsmusic.ui.settings.compose.scene.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Compose 测试
 * 对应原版的测试系统
 *
 * 提供测试功能
 */
@Stable
class ComposeTest {
    // ==================== 测试状态 ====================

    /** 是否正在运行测试 */
    var isRunning by mutableStateOf(false)
        private set

    /** 测试结果 */
    var testResult by mutableStateOf<TestResult?>(null)
        private set

    /** 测试进度 (0..1) */
    var testProgress by mutableStateOf(0f)
        private set

    /** 当前测试名称 */
    var currentTestName by mutableStateOf<String?>(null)
        private set

    // ==================== 测试计数 ====================

    /** 已运行测试数 */
    var testsRun by mutableStateOf(0)
        private set

    /** 通过测试数 */
    var testsPassed by mutableStateOf(0)
        private set

    /** 失败测试数 */
    var testsFailed by mutableStateOf(0)
        private set

    /** 跳过测试数 */
    var testsSkipped by mutableStateOf(0)
        private set

    // ==================== 方法 ====================

    /**
     * 开始测试
     *
     * @param testName 测试名称
     */
    fun startTest(testName: String) {
        isRunning = true
        currentTestName = testName
        testProgress = 0f
    }

    /**
     * 更新测试进度
     *
     * @param progress 进度 (0..1)
     */
    fun updateProgress(progress: Float) {
        testProgress = progress.coerceIn(0f, 1f)
    }

    /**
     * 完成测试
     *
     * @param passed 是否通过
     * @param message 消息
     */
    fun completeTest(passed: Boolean, message: String = "") {
        testsRun++
        if (passed) {
            testsPassed++
        } else {
            testsFailed++
        }

        testResult = TestResult(
            testName = currentTestName ?: "Unknown",
            passed = passed,
            message = message,
            timestamp = System.currentTimeMillis()
        )

        isRunning = false
        currentTestName = null
        testProgress = 1f
    }

    /**
     * 跳过测试
     *
     * @param testName 测试名称
     * @param reason 原因
     */
    fun skipTest(testName: String, reason: String = "") {
        testsRun++
        testsSkipped++

        testResult = TestResult(
            testName = testName,
            passed = false,
            message = "Skipped: $reason",
            timestamp = System.currentTimeMillis(),
            skipped = true
        )
    }

    /**
     * 重置测试状态
     */
    fun reset() {
        isRunning = false
        testResult = null
        testProgress = 0f
        currentTestName = null
        testsRun = 0
        testsPassed = 0
        testsFailed = 0
        testsSkipped = 0
    }

    /**
     * 获取测试报告
     *
     * @return 测试报告
     */
    fun getReport(): TestReport {
        return TestReport(
            testsRun = testsRun,
            testsPassed = testsPassed,
            testsFailed = testsFailed,
            testsSkipped = testsSkipped,
            passRate = if (testsRun > 0) testsPassed.toFloat() / testsRun else 0f
        )
    }
}

/**
 * 测试结果
 */
data class TestResult(
    val testName: String,
    val passed: Boolean,
    val message: String = "",
    val timestamp: Long,
    val skipped: Boolean = false
)

/**
 * 测试报告
 */
data class TestReport(
    val testsRun: Int,
    val testsPassed: Int,
    val testsFailed: Int,
    val testsSkipped: Int,
    val passRate: Float
)

/**
 * 测试用例
 */
data class TestCase(
    val name: String,
    val description: String,
    val test: () -> Boolean
)

/**
 * 测试套件
 */
class TestSuite(
    val name: String,
    val testCases: List<TestCase>
) {
    /**
     * 运行所有测试
     *
     * @param test 测试对象
     */
    fun runAll(test: ComposeTest) {
        testCases.forEach { testCase ->
            test.startTest(testCase.name)
            try {
                val passed = testCase.test()
                test.completeTest(passed)
            } catch (e: Exception) {
                test.completeTest(false, e.message ?: "Unknown error")
            }
        }
    }
}

/**
 * 记住 ComposeTest
 */
@Composable
fun rememberComposeTest(): ComposeTest {
    return remember { ComposeTest() }
}
