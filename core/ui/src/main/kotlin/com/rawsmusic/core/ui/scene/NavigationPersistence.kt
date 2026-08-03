package com.rawsmusic.core.ui.scene

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

object NavigationPersistence {
    private const val PREF_NAME = "rawsmusic_navigation_state"
    private const val KEY_STACK = "stack"
    private const val KEY_SCENE = "scene"
    private const val KEY_ARGUMENT = "argument"
    private const val KEY_SAVED_AT = "saved_at"

    fun save(
        context: Context,
        state: NavigationState
    ) {
        val stack = state.persistentBackStackIds()
            .joinToString(",")

        val argument = Uri.encode(state.currentArgument.orEmpty())

        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STACK, stack)
            .putInt(KEY_SCENE, state.currentScene.id)
            .putString(KEY_ARGUMENT, argument)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun restore(
        context: Context,
        state: NavigationState
    ): Boolean {
        val sp = context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        if (!sp.contains(KEY_SCENE)) return false

        val scene = NavScene.fromId(
            sp.getInt(KEY_SCENE, NavScene.HOME.id)
        ) ?: return false

        val stack = sp.getString(KEY_STACK, "")
            .orEmpty()
            .split(",")
            .mapNotNull { raw ->
                raw.toIntOrNull()?.let(NavScene::fromId)
            }
            .ifEmpty {
                listOf(NavScene.HOME)
            }

        val argument = Uri.decode(
            sp.getString(KEY_ARGUMENT, "").orEmpty()
        )

        state.restorePersistentState(
            stack = sanitizeStack(
                stack = stack,
                scene = scene
            ),
            scene = scene,
            argument = argument
        )

        return true
    }

    private fun sanitizeStack(
        stack: List<NavScene>,
        scene: NavScene
    ): List<NavScene> {
        val result = stack.toMutableList()

        if (result.isEmpty() || result.first() != NavScene.HOME) {
            result.add(0, NavScene.HOME)
        }

        if (scene !in result) {
            result.add(scene)
        }

        return result.distinct()
    }
}

@Composable
fun rememberPersistentNavigationState(): NavigationState {
    val context = LocalContext.current.applicationContext

    return remember {
        NavigationState().also { state ->
            NavigationPersistence.restore(context, state)
        }
    }
}

@Composable
fun NavigationPersistenceEffect(
    navState: NavigationState
) {
    val context = LocalContext.current.applicationContext

    val stackKey = navState.backStack.joinToString(",") {
        it.id.toString()
    }

    LaunchedEffect(
        navState.currentScene,
        navState.currentArgument,
        stackKey
    ) {
        NavigationPersistence.save(
            context = context,
            state = navState
        )
    }
}
