package btm.m.liquidglass.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.IdentityHashMap

internal class InjectedLifecycleOwner private constructor(
    private val activity: Activity
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner,
    Application.ActivityLifecycleCallbacks {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val attachedViews = IdentityHashMap<View, OriginalTreeOwners>()
    private var destroyed = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        activity.application.registerActivityLifecycleCallbacks(this)
    }

    fun attachTo(view: View) {
        if (destroyed) return
        attachedViews.putIfAbsent(
            view,
            OriginalTreeOwners(
                lifecycleOwner = view.findViewTreeLifecycleOwner(),
                viewModelStoreOwner = view.findViewTreeViewModelStoreOwner(),
                savedStateRegistryOwner = view.findViewTreeSavedStateRegistryOwner()
            )
        )
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        attachedViews.forEach { (view, owners) ->
            if (view.findViewTreeLifecycleOwner() === this) {
                view.setViewTreeLifecycleOwner(owners.lifecycleOwner)
            }
            if (view.findViewTreeViewModelStoreOwner() === this) {
                view.setViewTreeViewModelStoreOwner(owners.viewModelStoreOwner)
            }
            if (view.findViewTreeSavedStateRegistryOwner() === this) {
                view.setViewTreeSavedStateRegistryOwner(owners.savedStateRegistryOwner)
            }
        }
        attachedViews.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        activity.application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(target: Activity) {
        if (!destroyed && target === activity) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
    }

    override fun onActivityResumed(target: Activity) {
        if (!destroyed && target === activity) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    override fun onActivityPaused(target: Activity) {
        if (!destroyed && target === activity) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    override fun onActivityStopped(target: Activity) {
        if (!destroyed && target === activity) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    override fun onActivityDestroyed(target: Activity) {
        if (target === activity) destroy()
    }

    override fun onActivityCreated(target: Activity, state: Bundle?) = Unit
    override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit

    companion object {
        fun create(activity: Activity) = InjectedLifecycleOwner(activity)
    }

    private data class OriginalTreeOwners(
        val lifecycleOwner: LifecycleOwner?,
        val viewModelStoreOwner: ViewModelStoreOwner?,
        val savedStateRegistryOwner: SavedStateRegistryOwner?
    )
}
