package com.ladygo.driver

import android.app.Application
import com.ladygo.driver.offer.OfferCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LadyGoDriverApplication : Application() {
    // Field-injected (not referenced from anywhere else) purely to force Hilt to construct this
    // @Singleton at process start - Hilt's generated field injection runs before this onCreate() body,
    // so by the time this executes OfferCoordinator already exists and is already collecting
    // RealtimeEvents. Without this, it would only be constructed lazily on first Activity/ViewModel
    // use, which is not a guarantee an offer can't arrive before that first use happens (fresh-context
    // review finding, CRITICAL - see OfferCoordinator's own doc comment).
    @Inject
    lateinit var offerCoordinator: OfferCoordinator
}
